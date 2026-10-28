// Couch's remote relay. Runs as the ADB shell user, which may read and grab input devices and
// create virtual ones. It grabs the remote, so Android stops seeing it, and replays every event
// through a virtual copy of it, with the same name and so the same key layout. Only the keys
// Couch asked for are held back and sent to Couch instead. Volume and every other key take
// Android's usual path, so holding volume keeps ramping.
//
// Usage: couch-relay <port> <token>. It connects to Couch on 127.0.0.1:<port> and exits when that
// connection ends; the kernel then lets go of the grabs, and the remote works as before.
//
// Couch sends, one per line:   keys <code,code|->   learn <0|1>   emit <code>
// The relay sends:             key <code> <1 down|0 up>   seen <code>  (learning: every key down)
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define MAX_DEVICES 16
#define MAX_KEYS 8
#define PHYS "couch-relay"
#define BITS(n) (((n) + 7) / 8)
#define HAS(bits, n) ((bits)[(n) / 8] & (1 << ((n) % 8)))

struct device {
    int src, clone;
    char path[64];
    unsigned char keys[BITS(KEY_MAX + 1)];
};

static struct device devices[MAX_DEVICES];
static int ndevices, link_fd = -1, learning, keys[MAX_KEYS], nkeys;
// While learning every key is held back, so learning ends on its own after LEARN_MS.
#define LEARN_MS 15000
static long long learn_until;
// Bumped whenever a device is taken or dropped, so a poll list built before is never trusted.
static unsigned generation;

static long long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}

static void say(const char *fmt, int a, int b) {
    char line[64];
    int n = snprintf(line, sizeof line, fmt, a, b);
    if (write(link_fd, line, n) != n) exit(0);
}

static int wanted(int code) {
    for (int i = 0; i < nkeys; i++) if (keys[i] == code) return 1;
    return 0;
}

static void copy_bits(int src, int u, int type, int max, unsigned long set) {
    unsigned char bits[BITS(KEY_MAX + 1)];
    memset(bits, 0, sizeof bits);
    if (ioctl(src, EVIOCGBIT(type, BITS(max + 1)), bits) <= 0) return;
    ioctl(u, UI_SET_EVBIT, type);
    for (int k = 0; k <= max; k++) if (HAS(bits, k)) ioctl(u, set, k);
}

// A virtual twin of the device: same name and ids (so Android loads the same key layout), the
// same keys, axes and properties, and a phys tag so the relay never grabs its own twins.
static int make_clone(int src) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (u < 0) return -1;
    ioctl(u, UI_SET_EVBIT, EV_SYN);
    copy_bits(src, u, EV_KEY, KEY_MAX, UI_SET_KEYBIT);
    copy_bits(src, u, EV_REL, REL_MAX, UI_SET_RELBIT);
    copy_bits(src, u, EV_ABS, ABS_MAX, UI_SET_ABSBIT);
    copy_bits(src, u, EV_MSC, MSC_MAX, UI_SET_MSCBIT);
    unsigned char props[BITS(INPUT_PROP_MAX + 1)] = {0};
    ioctl(src, EVIOCGPROP(sizeof props), props);
    for (int k = 0; k <= INPUT_PROP_MAX; k++) if (HAS(props, k)) ioctl(u, UI_SET_PROPBIT, k);
    ioctl(u, UI_SET_PHYS, PHYS);
    struct uinput_user_dev d;
    memset(&d, 0, sizeof d);
    ioctl(src, EVIOCGNAME(sizeof d.name), d.name);
    ioctl(src, EVIOCGID, &d.id);
    for (int a = 0; a <= ABS_MAX; a++) {
        struct input_absinfo info;
        if (ioctl(src, EVIOCGABS(a), &info) < 0) continue;
        d.absmin[a] = info.minimum;
        d.absmax[a] = info.maximum;
        d.absfuzz[a] = info.fuzz;
        d.absflat[a] = info.flat;
    }
    if (write(u, &d, sizeof d) != sizeof d || ioctl(u, UI_DEV_CREATE) < 0) {
        close(u);
        return -1;
    }
    return u;
}

static void drop(int i) {
    ioctl(devices[i].clone, UI_DEV_DESTROY);
    close(devices[i].clone);
    close(devices[i].src);
    devices[i] = devices[--ndevices];
    generation++;
}

static void scan(void);

// A remote: keys, but no pointer, touch or stick, whose buttons and axes the relay has no use
// for. Remotes often report a stray scroll or volume axis too; those are copied as they are.
static int remote_like(const unsigned char *keys, int src) {
    unsigned char abs[BITS(ABS_MAX + 1)] = {0};
    ioctl(src, EVIOCGBIT(EV_ABS, sizeof abs), abs);
    return !HAS(abs, ABS_X) && !HAS(keys, BTN_MOUSE) && !HAS(keys, BTN_TOUCH) &&
        !HAS(keys, BTN_GAMEPAD) && !HAS(keys, BTN_JOYSTICK);
}

static int needed(const unsigned char *bits) {
    if (learning) return 1;
    for (int i = 0; i < nkeys; i++) if (keys[i] <= KEY_MAX && HAS(bits, keys[i])) return 1;
    return 0;
}

// Grabs a remote that has a key Couch wants (any remote while learning), never one of the
// relay's own twins.
static void take(const char *path) {
    if (ndevices == MAX_DEVICES) return;
    for (int i = 0; i < ndevices; i++) if (!strcmp(devices[i].path, path)) return;
    int src = open(path, O_RDONLY | O_NONBLOCK);
    if (src < 0) return;
    char phys[64] = "";
    unsigned long types = 0;
    ioctl(src, EVIOCGPHYS(sizeof phys), phys);
    ioctl(src, EVIOCGBIT(0, sizeof types), &types);
    struct device *d = &devices[ndevices];
    memset(d->keys, 0, sizeof d->keys);
    ioctl(src, EVIOCGBIT(EV_KEY, sizeof d->keys), d->keys);
    if (!(types & (1 << EV_KEY)) || !strncmp(phys, PHYS, strlen(PHYS)) || !remote_like(d->keys, src) || !needed(d->keys)) {
        close(src);
        return;
    }
    // Grab first: a device someone else holds is left alone without ever getting a twin, whose
    // own appearance would otherwise set off another scan, and another try.
    if (ioctl(src, EVIOCGRAB, 1) < 0) {
        close(src);
        return;
    }
    d->clone = make_clone(src);
    if (d->clone < 0) {
        ioctl(src, EVIOCGRAB, 0);
        close(src);
        return;
    }
    d->src = src;
    snprintf(d->path, sizeof d->path, "%s", path);
    ndevices++;
    generation++;
}

// Lets go of the devices no longer needed, then takes any that now are.
static void regrab(void) {
    for (int i = ndevices - 1; i >= 0; i--) if (!needed(devices[i].keys)) drop(i);
    scan();
}

static void scan(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return;
    for (struct dirent *e; (e = readdir(dir));) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char path[64];
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        take(path);
    }
    closedir(dir);
}

static void press(int fd, int code, int value) {
    struct input_event e[2];
    memset(e, 0, sizeof e);
    e[0].type = EV_KEY;
    e[0].code = code;
    e[0].value = value;
    e[1].type = EV_SYN;
    write(fd, e, sizeof e);
}

// Presses a key as the remote that has it, for Couch to hand the TV its own menu.
static void emit(int code) {
    for (int i = 0; i < ndevices; i++) {
        if (code < 0 || code > KEY_MAX || !HAS(devices[i].keys, code)) continue;
        press(devices[i].clone, code, 1);
        press(devices[i].clone, code, 0);
        return;
    }
}

static void command(char *line) {
    if (!strncmp(line, "keys ", 5)) {
        nkeys = 0;
        char *save = NULL;
        for (char *t = strtok_r(line + 5, ",", &save); t && nkeys < MAX_KEYS; t = strtok_r(NULL, ",", &save)) {
            int code = atoi(t);
            if (code > 0) keys[nkeys++] = code;
        }
        regrab();
    } else if (!strncmp(line, "learn ", 6)) {
        learning = atoi(line + 6);
        learn_until = learning ? now_ms() + LEARN_MS : 0;
        regrab();
    } else if (!strncmp(line, "emit ", 5)) {
        emit(atoi(line + 5));
    }
}

// Couch's link: complete lines are run, a closed link ends the relay.
static void read_link(void) {
    static char buf[256];
    static int len;
    ssize_t n = read(link_fd, buf + len, sizeof buf - 1 - len);
    if (n <= 0) exit(0);
    len += n;
    buf[len] = 0;
    char *start = buf, *nl;
    while ((nl = strchr(start, '\n'))) {
        *nl = 0;
        command(start);
        start = nl + 1;
    }
    len = strlen(start);
    memmove(buf, start, len + 1);
    if (len == sizeof buf - 1) len = 0;
}

static void read_device(int i) {
    struct input_event ev[64];
    ssize_t n = read(devices[i].src, ev, sizeof ev);
    if (n < 0 && errno == ENODEV) {
        drop(i);
        return;
    }
    for (int j = 0; j < n / (ssize_t)sizeof ev[0]; j++) {
        struct input_event *e = &ev[j];
        if (e->type == EV_KEY && learning) {
            if (e->value == 1) say("seen %d\n", e->code, 0);
            continue;
        }
        if (e->type == EV_KEY && wanted(e->code)) {
            if (e->value != 2) say("key %d %d\n", e->code, e->value);
            continue;
        }
        write(devices[i].clone, e, sizeof *e);
    }
}

static int connect_couch(int port, const char *token) {
    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(port)};
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    for (int tries = 0; tries < 20; tries++) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd >= 0 && connect(fd, (struct sockaddr *)&addr, sizeof addr) == 0) {
            dprintf(fd, "hello %s\n", token);
            return fd;
        }
        if (fd >= 0) close(fd);
        usleep(250 * 1000);
    }
    return -1;
}

// Leaves the ADB shell that started it, which ends the moment the command returns and takes its
// processes with it: a new session, no hang-up, and a second fork so it can never regain a
// terminal. The first process returns at once, so the command does too.
static void detach(void) {
    if (fork() != 0) _exit(0);
    setsid();
    signal(SIGHUP, SIG_IGN);
    if (fork() != 0) _exit(0);
    int null = open("/dev/null", O_RDWR);
    dup2(null, 0);
    dup2(null, 1);
    dup2(null, 2);
    if (null > 2) close(null);
    chdir("/");
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    detach();
    link_fd = connect_couch(atoi(argv[1]), argv[2]);
    if (link_fd < 0) return 1;
    int watch = inotify_init1(IN_NONBLOCK);
    inotify_add_watch(watch, "/dev/input", IN_CREATE | IN_ATTRIB);
    for (;;) {
        struct pollfd p[MAX_DEVICES + 2];
        p[0] = (struct pollfd){.fd = link_fd, .events = POLLIN};
        p[1] = (struct pollfd){.fd = watch, .events = POLLIN};
        for (int i = 0; i < ndevices; i++) p[i + 2] = (struct pollfd){.fd = devices[i].src, .events = POLLIN};
        int count = ndevices;
        unsigned seen = generation;
        int wait = learning ? (int)(learn_until - now_ms()) : -1;
        if (learning && wait <= 0) {
            // Nobody pressed anything: give the keys back, and tell Couch as if Back was pressed.
            learning = 0;
            regrab();
            say("seen %d\n", KEY_BACK, 0);
            continue;
        }
        if (poll(p, count + 2, wait) <= 0) continue;
        if (p[0].revents) {
            read_link();
            // A command may have taken or dropped devices: poll again with the new list.
            if (generation != seen) continue;
        }
        if (p[1].revents & POLLIN) {
            char events[512];
            while (read(watch, events, sizeof events) > 0) {}
            // A remote that reconnects (Bluetooth after standby) gets a new node; its permissions
            // can land a moment after the node, hence IN_ATTRIB too.
            scan();
            if (generation != seen) continue;
        }
        // Highest first: dropping a device moves the last one into its slot, already handled.
        for (int i = count - 1; i >= 0; i--) if (p[i + 2].revents) read_device(i);
    }
}
