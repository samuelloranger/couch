# Configuration

Everything can be changed from the TV: press UP from the top row to reach ⚙ and open
**Customize**, or long-press any card. Changes apply the next time the home screen shows.

## Customize

Five groups. Rows that only matter for another option sit indented under it and show only when
they apply. In the Index layout, the options that only change the Rows layout wait at the end of
their group behind *More for the Rows layout*. While a permission is off, a line at the top says
so and opens the permissions page.

| Group | Option | Values |
|---|---|---|
| Appearance | Layout | Rows, Index (see below) |
| | Accent colour | Mint, Ember, Amber, Sky, Coral, White |
| | Card size *(Rows)* | Small, Medium, Large |
| | Names under cards *(Rows)* | On / Off |
| | Descriptions *(Rows)* | On / Off |
| Wallpaper and ambient | Source | Photo of the day (Bing), Reddit, Gradient, My image |
| | Subreddit *(Reddit)* | any subreddit; only TV-shaped images (16:9 to 21:9) are kept |
| | Change image *(Reddit)* | every 1, 3, 6 or 24 hours |
| | Ambient mode | Never, 2, 5, 10 or 20 minutes |
| | Dim *(Rows)* | Light, Medium, Strong |
| Home screen | Inputs | rename inputs, pick their icon |
| | App channels | show any preview channel an app publishes |
| | Show or hide apps | one checklist for apps and inputs |
| | Rows *(Rows)* | show, hide and reorder rows |
| | Rearrange apps *(Rows)* | OK picks an app up, left/right moves it, OK puts it down |
| Clock and weather | 24-hour clock, Weather | On / Off |
| | City, Units *(weather on)* | |
| | Date *(Rows)* | On / Off |
| Couch | Permissions | HOME button, Notification access, Usage access, TV listings ([setup](setup.md#3-permissions)) |
| | Other TVs, Remote buttons | their own pages |
| | Check for updates | On / Off |
| | About | the installed version; OK installs a newer one when there is one |
| | Android settings | the TV's own settings |

Couch follows the TV's language: English, or French on a TV set to French.

## Index layout

Index is the default layout: a list of your apps on the left, in place of the rows
(**Layout → Rows** brings the rows back). The right side shows what the selected app offers (its
first channel: the program's title and synopsis, then the channel as a row of cards with their
names), how much you used it over the last seven days with a bar per day, and when you last
opened it. An app without a channel shows its banner in the channel's place. The bottom line
shows the TV's address, network traffic, memory, storage, uptime and whether ADB is on.

- Up and down move through the apps, OK opens one. Right enters its channel; left (from the first
  card), up, down or Back comes back. Up from the first app reaches Inputs and Settings.
- With usage access ([setup](setup.md#3-permissions)), apps are ordered by screen time over the
  last seven days. Without it they keep your order and the figures are replaced by a hint.
- Long-press an app for Rename, Hide and App info.
- Customize only lists what applies to Index: card size, names under cards, descriptions, dim,
  rows, rearranging apps and the date belong to the rows layout and are hidden. The wallpaper
  still shows in ambient mode.

## Recent row

With usage access, the rows layout adds a **Recent** row: the apps you opened in the last seven
days, most recent first, up to eight. Until you move it, it sits right below Apps; hide or move
it in **Rows**. Move from a Recent card moves the app in the Apps row, whose order is the one kept.

## Inputs turning on

When a device on an input turns on while the home screen shows (a console woken from its
controller, say), a **Switch to …** chip appears in the top bar. OK switches to that input. The
chip never moves focus to itself (Up reaches it like the other chips), and goes away after 30 seconds or when you leave the home screen. Hidden
inputs never offer themselves, and nothing is offered in the first seconds after the home screen
comes back (a TV waking up reports its inputs changing too).

## Fire TV recommendations

On Fire TV most apps (Netflix, Amazon Kids…) post recommendations instead of publishing
preview channels. Couch shows each app's recommendations as one channel, "Suggestions", in both
layouts and in the App channels list. It reads them through notification access (see
[setup](setup.md#3-permissions)); an app that also publishes preview channels keeps only those.

## Long-press menus

- **App**: Move, Rename, Hide, App info.
- **Input**: Rename, Change icon, Hide.
- **Show or video**: Open the app, App info.

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
| `pinnedApps` | string[] | `[]` | App order (package names). Unlisted apps follow, alphabetically. |
| `hiddenApps` | string[] | vendor extras | Hidden apps (package names). |
| `hiddenInputs` | string[] | `[]` | Hidden inputs (keys like `hdmi3`). |
| `names` | object | `{}` | Custom names, by app package or input key. |
| `inputIcons` | object | `{}` | Input key → icon id (`ps5`, `xbox`, `switch2`, `server`…). |
| `sectionOrder` | string[] | `[]` | Row order: `apps`, `inputs`, `ch:<channel id>`. |
| `hiddenSections` | string[] | `[]` | Hidden rows. |
| `channels` | object | `{}` | Channel id → shown or not, overriding the stock launcher's choice. |
| `layout` | string | `index` | `index` or `rows`. |
| `cardSize` | string | `medium` | `small`, `medium`, `large`. |
| `accent` | string | `#34D399` | Accent colour (hex). |
| `wallpaper` | string | `daily` | `daily`, `reddit`, `gradient`, `custom`. |
| `subreddit` | string | `WidescreenWallpaper` | Subreddit for the `reddit` source. |
| `wallpaperHours` | number | `3` | Hours between Reddit images. |
| `dim` | string | `medium` | `light`, `medium`, `strong`. |
| `showLabels` | boolean | `false` | Names under cards. |
| `showDescriptions` | boolean | `true` | Synopsis in the hero area. |
| `clock24h` | boolean | `true` | 24-hour clock. |
| `showDate` | boolean | `true` | Date under the clock. |
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
