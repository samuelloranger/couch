# Configuration

Everything can be changed from the TV: press Up from the first app to reach Settings and open
**Customize**, or long-press an app. Changes apply the next time the home screen shows.

## Customize

Four groups, down the left of the screen, each with a line saying how it is set. Up and down
move between groups, Right or OK opens one, Back returns to the list of groups. Rows that only
matter for another option sit indented under it and show only when they apply. While a permission
is off, a line at the top says so and opens the permissions page.

| Group | Option | Values |
|---|---|---|
| Appearance | Accent colour | Mint, Ember, Amber, Sky, Coral, White |
| | Ambient mode | Never, 2, 5, 10 or 20 minutes |
| | Wallpaper *(ambient on)* | Photo of the day (Bing), Reddit, Gradient, My image |
| | Subreddit *(Reddit)* | any subreddit; only TV-shaped images (16:9 to 21:9) are kept |
| | Change image *(Reddit)* | every 1, 3, 6 or 24 hours |
| Home screen | App channels | a switch for each channel apps publish |
| | Show or hide apps | a switch for each app |
| | Inputs | rename inputs, pick their icon, and a switch for each |
| Clock and weather | 24-hour clock, Weather | On / Off |
| | City, Units *(weather on)* | |
| Couch | Permissions | HOME button, Notification access, Usage access, TV listings ([setup](setup.md#3-permissions)) |
| | Other TVs, Remote buttons | their own pages |
| | Check for updates | On / Off |
| | About | the installed version; OK installs a newer one when there is one |
| | Android settings | the TV's own settings |

Couch follows the TV's language: English, or French on a TV set to French.

## Home screen

The home screen (Index) is a list of your apps on the left. The right side shows what the
selected app offers (its first channel: the program's title and synopsis, then the channel as a
row of cards with their names), how much you used it over the last seven days with a bar per day,
and when you last opened it. An app without a channel shows its banner in the channel's place. The
bottom line shows the TV's address, network traffic, memory, storage, uptime and whether ADB is on.

- Up and down move through the apps, OK opens one. Right enters its channel; left (from the first
  card), up, down or Back comes back. Up from the first app reaches Inputs and Settings.
- With usage access ([setup](setup.md#3-permissions)), apps are ordered by screen time over the
  last seven days. Without it they follow `pinnedApps`, then the alphabet, and the figures are
  replaced by a hint.
- Long-press an app for Rename, Hide and App info.
- The wallpaper shows in ambient mode.

## Inputs turning on

When a device on an input turns on while the home screen shows (a console woken from its
controller, say), a **Switch to …** chip appears in the top bar. OK switches to that input. The
chip never moves focus to itself (Up reaches it like the other chips), and goes away after 30 seconds or when you leave the home screen. Hidden
inputs never offer themselves, and nothing is offered in the first seconds after the home screen
comes back (a TV waking up reports its inputs changing too).

## Fire TV recommendations

On Fire TV most apps (Netflix, Amazon Kids…) post recommendations instead of publishing
preview channels. Couch shows each app's recommendations as one channel, "Suggestions", on the
home screen and in the App channels list. It reads them through notification access (see
[setup](setup.md#3-permissions)); an app that also publishes preview channels keeps only those.

## Long-press menus

- **App**: Rename, Hide, App info.
- **Show or video**: Open the app, App info.

Inputs are renamed, given an icon and hidden in **Customize → Home screen → Inputs**.

## Input icons

Inputs get an icon guessed from their name ("PS5", "Switch 2", "Serveur"…). Pick another in
the icon picker, which shows every choice as the card it will become. Brand marks are drawn in
white on the brand colour.

## Files

Couch keeps its settings in its external files directory, which ADB can read and write:

```
/sdcard/Android/data/cloud.samlo.couch/files/
├── config.json
├── wallpaper.jpg          # used when the wallpaper source is "Mon image"
└── icons/
    ├── hdmi1.png          # custom image for an input (key = its system name, letters and digits)
    └── <package>.png      # custom image for an app (key = package name)
```

Custom images are centre-cropped to the card's 16:9 shape.

## config.json

Every key is optional; missing keys use the default. The file is rewritten by Couch whenever a
setting changes, so edit it while the home screen is in the background.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `pinnedApps` | string[] | `[]` | App order without usage access, and ties with it (package names). Unlisted apps follow, alphabetically. |
| `hiddenApps` | string[] | vendor extras | Hidden apps (package names). |
| `hiddenInputs` | string[] | `[]` | Hidden inputs (keys like `hdmi3`). |
| `names` | object | `{}` | Custom names, by app package or input key. |
| `inputIcons` | object | `{}` | Input key → icon id (`ps5`, `xbox`, `switch2`, `server`…). |
| `channels` | object | `{}` | Channel id → shown or not, overriding the stock launcher's choice. |
| `accent` | string | `#34D399` | Accent colour (hex). |
| `wallpaper` | string | `daily` | `daily`, `reddit`, `gradient`, `custom`. |
| `subreddit` | string | `WidescreenWallpaper` | Subreddit for the `reddit` source. |
| `wallpaperHours` | number | `3` | Hours between Reddit images. |
| `clock24h` | boolean | `true` | 24-hour clock. |
| `weather` | boolean | `true` | Weather in the top bar. |
| `weatherCity` | string | `""` | City for the weather; empty locates by IP address. |
| `fahrenheit` | boolean | `false` | °F instead of °C. |
| `ambientMinutes` | number | `5` | Idle minutes before ambient mode; `0` turns it off. |
| `checkUpdates` | boolean | `true` | Look for a newer release once a day. |
| `godmode` | boolean | `false` | Set by the seven OK presses on About; lists the hidden [Godmode](godmode.md) page in Customize. |
| `preReleases` | boolean | `false` | Godmode's update channel: offer pre-releases too, never older than what is installed. |
| `hud` | string | `off` | Godmode's frame readout on the home screen: `off`, `ms` or `fps`. |
| `syncEnabled` | boolean | `false` | Share screen time with paired TVs on the home network ([Other TVs](sync.md)). |
| `remoteButtons` | boolean | `false` | The remote's Input and Settings buttons open Couch's lists ([Remote buttons](remote-buttons.md)). |
| `remoteInputKey` | number | `0` | The Input button's key code, as learned in Customize; `0` when not set up. |
| `remoteSettingsKey` | number | `0` | The Settings button's key code; `0` when not set up. |
| `syncAllTvs` | boolean | `true` | With sync on, Index adds the paired TVs' screen time to this one's; `false` shows this TV alone. |

Back up or restore the whole setup by copying the directory:

```sh
adb pull /sdcard/Android/data/cloud.samlo.couch/files couch-backup
adb push couch-backup/. /sdcard/Android/data/cloud.samlo.couch/files/
```

Pairing keys and the other TVs' figures are not in `config.json`: they live in a private file the
app alone can read (see [Other TVs](sync.md#what-is-shared)).
