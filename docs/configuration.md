# Configuration

Everything can be changed from the TV: press UP from the top row to reach ⚙ and open
**Customize**, or long-press any card. Changes apply the next time the home screen shows.

## Customize

| Section | Option | Values |
|---|---|---|
| Appearance | Layout | Rows, Index (see below) |
| | Card size | Small, Medium, Large |
| | Accent colour | Mint, Ember, Amber, Sky, Coral, White |
| | Names under cards | On / Off |
| | Descriptions | On / Off |
| Wallpaper | Source | Photo of the day (Bing), Reddit, Gradient, My image |
| | Subreddit | any subreddit; only TV-shaped images (16:9 to 21:9) are kept |
| | Change image | every 1, 3, 6 or 24 hours |
| | Dim | Light, Medium, Strong |
| Home | Rows | show, hide and reorder rows |
| | Inputs | rename inputs, pick their icon |
| | App channels | show any preview channel an app publishes |
| | Show or hide apps | one checklist for apps and inputs |
| | Rearrange apps | OK picks an app up, left/right moves it, OK puts it down |
| Clock and weather | 24-hour clock, Date, Weather, City, Units | |
| Standby | Ambient mode | Never, 2, 5, 10 or 20 minutes |
| System | Check for updates | On / Off |
| | About | the installed version; OK installs a newer one when there is one |

Couch follows the TV's language: English, or French on a TV set to French.

## Index layout

**Layout → Index** swaps the rows for a list of your apps on the left. The right side shows what
the selected app offers (its first channel: the program's art, title and synopsis, then the
channel as a row of cards), how much you used it over the last seven days, when you last opened
it, and how often. The bottom line shows the TV's address, network traffic, memory, storage,
uptime and whether ADB is on.

- Up and down move through the apps, OK opens one. Right enters its channel; left (from the first
  card), up, down or Back comes back. Up from the first app reaches Inputs and Settings.
- With usage access ([setup](setup.md#3-permissions)), apps are ordered by screen time over the
  last seven days. Without it they keep your order and the figures are replaced by a hint.
- Long-press an app for Rename, Hide and App info.
- Customize only lists what applies to Index: card size, names under cards, descriptions, dim,
  rows, rearranging apps and the date belong to the rows layout and are hidden. The wallpaper
  still shows in ambient mode.

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
| `layout` | string | `rows` | `rows` or `index`. |
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

Back up or restore the whole setup by copying the directory:

```sh
adb pull /sdcard/Android/data/cloud.samlo.couch/files couch-backup
adb push couch-backup/. /sdcard/Android/data/cloud.samlo.couch/files/
```
