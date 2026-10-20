# Setup

Couch is not on the Play Store. Install it from the TV itself with a downloader app, or from a
computer over ADB. ADB also unlocks the few permissions an app can't request from the remote.

## On the TV, no computer

1. Install **Downloader** (by AFTVnews) from the Play Store.
2. Open it, allow it to install apps when the TV asks, and enter:

   ```
   samlo.cloud/couch/couch-latest.apk
   ```

   It always points to the [latest release](https://github.com/samuelloranger/couch/releases/latest).
3. Install, open Couch, and allow access to TV listings when it asks.
4. Make HOME open Couch: in the TV's accessibility settings, turn on **Couch: Home button**
   (see [step 4](#_4-make-home-open-couch) for what it does).

That covers everything except the now-playing pill and the HOME button repairing itself, which
need the ADB permissions below.

## With a computer (ADB)

### 1. Enable ADB on the TV

Settings → System → About → press OK on *Build* seven times to unlock developer options, then
Settings → System → Developer options → **Network debugging** (or USB debugging).

From a computer on the same network:

```sh
adb connect <tv-address>:5555   # accept the prompt on the TV the first time
```

### 2. Install

```sh
curl -L -o couch.apk https://samlo.cloud/couch/couch-latest.apk   # the latest release
adb install couch.apk
adb shell cmd package compile -m speed -f cloud.samlo.couch   # optional: full ahead-of-time compile
```

The compile step makes the very first launch as smooth as later ones.

### 3. Permissions

| Permission | What it unlocks | Command |
|---|---|---|
| TV listings | App channel rows (YouTube, Jellyfin…) | `adb shell pm grant cloud.samlo.couch android.permission.READ_TV_LISTINGS` |
| Notification access | The now-playing pill, and Fire TV apps' recommendations | `adb shell cmd notification allow_listener cloud.samlo.couch/cloud.samlo.couch.MediaListener` |
| Secure settings | Repairing the HOME takeover by itself | `adb shell pm grant cloud.samlo.couch android.permission.WRITE_SECURE_SETTINGS` |
| Usage access | Screen time in the Index layout, and the Recent row | `adb shell appops set cloud.samlo.couch GET_USAGE_STATS allow` |

Couch asks for TV listings itself on first launch; the others are only possible over ADB.
Everything works without them, minus the feature in that row.

**Customize → Permissions** shows which of them are on. OK on one that is off opens the TV's
own settings screen for it, when the TV has one (many leave these screens out, Fire TV among
them; the command above still works). The group sits at the top of Customize while something is
missing, and near the bottom once everything is granted.

### 4. Make HOME open Couch

Most Android TV devices let you pick a default home app. Many TVs (Sony Bravia among them) don't:
HOME always opens the stock launcher. For those, Couch includes an accessibility service that
replaces the stock launcher as soon as it appears:

```sh
adb shell settings put secure enabled_accessibility_services \
  cloud.samlo.couch/cloud.samlo.couch.HomeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

The service only receives window events from the stock launcher. It never sees or changes key
presses, so volume and every other button behave exactly as before. The trade-off is that the stock
launcher can show for a moment (up to a few seconds right after a HOME press) before Couch
replaces it. See [decisions](decisions.md#home-button) for why.

**Android drops accessibility services when their app is force-stopped** (some TVs do this to free
memory). With the secure-settings permission above, Couch turns its service back on whenever it
opens and at boot. Without it, re-run the two commands.

On Fire TV, the same service replaces Amazon's home screen. Fire TV Settings belongs to the same
app, so only the home screen is replaced: Settings opened from the quick menu (hold HOME) stays
open. If you used a home-redirect app before, only one app should react to HOME; the command above
replaces the whole list of enabled services, which turns the other app's service off.

## Updating

Couch looks for a new release once a day. When there is one, an **Update** button appears in the top
bar, left of the weather: press it, and confirm in the dialog Android shows. The first time, Android also asks you
to allow Couch to install apps. When the HOME button service is on, Couch reopens on its own once
the update is installed. Turn the check off in Customize → System.

You can also install the new APK over the old one yourself: enter the same link in Downloader, or
run `adb install -r couch.apk`. Either way, settings are kept: they live in the app's
storage and survive updates, not uninstalls. To keep them across an uninstall, copy
`/sdcard/Android/data/cloud.samlo.couch/files/` off the TV first.
