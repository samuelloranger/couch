# Architecture

Couch is one Gradle module of plain Kotlin on framework Views. Seven packages, each with one job;
no file is longer than about 160 lines.

```
cloud.samlo.couch
├── MainActivity, HomeAccessibilityService, MediaListener, BootReceiver
├── home/      the home screen: wiring, loading, binding, top bar, menus, reorder, ambient
├── ui/        reusable views: Card, FocusRow, Rows, Hero, Ambient, NowPlaying, tokens
├── data/      config, apps, inputs, channels, weather, wallpapers, image pipeline
├── update/    daily release check, download, checksum, Android's package installer
├── settings/  Customize: activity shell, option rows, one file per page, icon picker
└── index/     the Index layout: app list, spotlight, channel row, usage figures, system line
```

The four classes at the root are referenced by name from the TV's settings (the launcher entry,
the accessibility service, the notification listener, the boot receiver), so they keep their name
and package. `MainActivity` only forwards lifecycle calls and keys to `home.HomeScreen`.

## Data flow

```text
loader thread                                         main thread
─────────────                                         ───────────
config.json ─▶ HomeLoader
                ├─ AppSource ───┐
                ├─ InputSource ─┼─▶ SectionBuilder ─SectionData─▶ SectionBinder
                ├─ Programs ────┘                                    │
                └─ Weather, Wallpapers                               ▼
                                                     Rows / FocusRow / Card ─focus─▶ Hero
settings/* ─▶ config.json
```

1. `HomeLoader` runs on a single background thread: it reads `config.json`, lists apps and inputs,
   queries preview channels, and fetches weather and the wallpaper. Banners are rendered there, at
   their final pixel size.
2. `SectionBuilder` turns that into `SectionData`: one per row, in the user's order, each with a
   signature of everything that affects how it looks.
3. `SectionBinder` applies it on the main thread. Rows whose signature didn't change are left
   alone; inside a changed row, only cards whose data or look changed are replaced
   (`FocusRow.update` and `arrange`). The focused card is never detached before focus has
   somewhere to go, so reloads don't move you.

Loads happen on every return to the home screen and when apps or inputs change
(`SystemWatchers`). Because unchanged rows aren't touched, a reload is invisible.

## Views and motion

- **`Card`** animates only scale and the ring's alpha on focus. Its content (`CardContent`) is a
  pre-rounded bitmap, remote art loaded at its exact size, or an icon with a label.
- **`FocusRow`** never scrolls: it translates its strip so the focused card sits at a fixed
  anchor, retargeting one animation per move. It draws the focused card last and keeps left/right
  inside the row. Coming back from another row returns to the card you left.
- **`Rows`** does the same vertically: the focused row glides to the top of the rows area.
- **`Hero`** updates its text on every move (fixed-size views, so no layout pass) and only loads
  the backdrop once focus has rested, crossfading between two stacked image views.
- **`Tokens`** holds every colour, size and timing. Everything shares one expo-out curve.

## Index layout

`IndexScreen` is the second layout, shown instead of the hero and rows when `config.layout` is
`index`; `HomeLoader` then builds `IndexData` (`IndexBuilder`) instead of rows. Everything sits at
fixed positions from `IndexTokens` (dp on the 960x540 canvas), so focus moves never lay out.

- **`AppList`** glides its column by translation, like `Rows`; `AppRow` only swaps colours when
  selected. `IndexScreen.focusSearch` routes the D-pad between the list, the channel row (a
  `FocusRow` of `Card`s with a thin ring) and the top pills.
- **`Spotlight`** shows the focused program's art at its own shape with the text beside it, or the
  app's banner; like `Hero`, art waits until focus rests. `Backdrop` crossfades a faint copy.
- **`Usage`** reads usage events (with usage access) into seven days per app; `Usage.Tally` is the
  pure part and is unit tested. **`SystemInfo`** gives `SystemLine` its readings every few seconds.
- Text uses Hanken Grotesk (`Fonts`, a Latin subset of the variable font in `assets/fonts`, OFL).

## Data

- **`Config`** is the user's setup; `ConfigJson` reads and writes it atomically. Unknown or broken
  values fall back to defaults instead of failing.
- **`AppSource` / `InputSource` / `Programs`** produce `Tile`s, the one data type every card shows.
  Inputs get their name from the TV's label or the connected CEC device, and their icon from
  `InputIcon.resolve`.
- **`Images`** is the image pipeline: `Fetcher` downloads, `DiskCache` keeps remote art on disk
  (60 MB budget), and decoded bitmaps sit in a memory cache sized from the heap.
- **`Weather`** (Open-Meteo, with Meteocons icons by Bas Milius, MIT) and **`Wallpapers`** (Bing, a subreddit through Arctic Shift, a local
  image) cache every answer in preferences, so a network hiccup keeps the last good result.

## Settings

`SettingsActivity` hosts one page at a time (main, rows, channels, inputs), chosen by an intent
extra. Pages build their list from `OptionList` helpers (choice, toggle, text, link), each producing
an `OptionRow`. Every change is saved immediately; the home screen picks it up when it resumes.
`IconPickerActivity` shows input icons as real `Card`s.

## HOME takeover

`HomeAccessibilityService` only receives window-state events from the stock launchers' packages
and opens `MainActivity` when one appears. It never filters keys. With `WRITE_SECURE_SETTINGS`,
`HomeAccessibilityService.restore` puts the service back in Android's enabled list after a
force-stop removes it; `HomeScreen` and `BootReceiver` call it.

## Tests

JVM unit tests (`app/src/test`) cover the pure logic: config defaults, round-trip and migrations,
input icon guessing, row ordering, weather conditions, subreddit image filtering, input keys,
and how usage events add up to screen time.
