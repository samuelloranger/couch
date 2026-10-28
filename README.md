<p align="center">
  <img src="docs/public/logo.svg" width="96" alt="Couch logo" />
</p>

<h1 align="center">Couch</h1>

<p align="center">
  <a href="https://github.com/samuelloranger/couch/releases"><img src="https://img.shields.io/github/v/release/samuelloranger/couch" alt="Latest release" /></a>
  <a href="https://github.com/samuelloranger/couch/actions/workflows/ci.yml"><img src="https://github.com/samuelloranger/couch/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Android%20TV%20%C2%B7%20Google%20TV%20%C2%B7%20Fire%20TV-9%2B-34D399?logo=androidtv&logoColor=white" alt="Android TV, Google TV and Fire TV, Android 9+" />
  <img src="https://img.shields.io/badge/APK-a%20few%20hundred%20KB-111715" alt="APK of a few hundred KB" />
  <a href="https://buymeacoffee.com/samlo122"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black" alt="Buy me a coffee" /></a>
</p>

A light, fast home screen for Android TV, built for TVs whose chips struggle with the stock
launcher. Plain Android Views, no AndroidX, no Compose, no tracking: the APK is a few hundred KB.

Runs on Android TV, Google TV and Fire TV, on Android 9 or later. On each, the HOME button can open
Couch instead of the stock home screen.

<p align="center">
  <img src="docs/screenshots/home.jpg" alt="The Index home screen" width="32%">
  <img src="docs/screenshots/channel.jpg" alt="Moving along an app's channel" width="32%">
  <img src="docs/screenshots/settings.jpg" alt="Customize" width="32%">
</p>

## Why

- **Smooth on weak hardware.** On a 2020 MediaTek-based TV, Couch renders frames in
  10 / 13 / 14 ms (p50 / p90 / p99) where a popular third-party launcher needs 14 / 25 / 31 ms
  for the same remote presses. Focus only animates render properties, rows glide by translation,
  and every bitmap is decoded at the exact size it is drawn.
- **Nothing you didn't ask for.** No ads, no sponsored rows, no analytics. The network is only
  used for the features you turn on (wallpaper, weather, app artwork).
- **Yours to arrange.** Every row, app, input and colour can be changed from the remote.

## Features

- Two layouts: **Index** (the default), a list of your apps beside what the selected one offers
  and your screen time with it, or rows of cards with a hero area for the focused show or film.
- Apps row, recently used apps, HDMI inputs row with brand logos (PlayStation, Xbox, Nintendo
  Switch, Apple TV…), and the preview channels apps publish (YouTube, Jellyfin and others).
- When a console or player on an input turns on, a chip offers to switch to it.
- Wallpaper: Bing photo of the day, the best images of a subreddit, a gradient, or your own.
- Weather (Open-Meteo, located automatically or by city), clock, now playing.
- Ambient mode after a few idle minutes: full-screen wallpaper, large clock, weather.
- Long-press any card to move, rename, hide or change its icon.
- **Customize**: card size, accent colour, dimming, names under cards, row order and
  visibility, app channels, inputs, clock format, weather, ambient delay, and which permissions
  are still missing.
- HOME button takeover on TVs that hardwire HOME to the stock launcher (Google TV, Fire TV, Sony
  Bravia and others), repairing itself if Android drops it.
- Updates itself: an Update button appears when a new release is out, one confirmation installs it.
- **Other TVs**: share screen time between the TVs in the house, over your home network only,
  paired by comparing six digits on both screens. See [the docs](https://samlo.cloud/couch/sync).
- **Godmode**, hidden behind seven OK presses on About: tune the TV's animations, block ads for
  every app through private DNS, remove the apps the TV came with (measured on your TV, reversible),
  free cache space, a sleep timer, screenshots, and a frame HUD with a benchmark. See
  [the docs](https://samlo.cloud/couch/godmode).

The interface is in English, or French on a TV set to French.

## Install

**On the TV:** install **Downloader** from the Play Store, open it and enter
`samlo.cloud/couch/couch-latest.apk`. Then turn on **Couch: Home button** in the TV's
accessibility settings so HOME opens Couch.

**From a computer**, with ADB enabled on the TV:

```sh
adb connect <tv-address>:5555
curl -L -o couch.apk https://samlo.cloud/couch/couch-latest.apk   # the latest release
adb install couch.apk
adb shell pm grant cloud.samlo.couch android.permission.READ_TV_LISTINGS
adb shell cmd notification allow_listener cloud.samlo.couch/cloud.samlo.couch.MediaListener
adb shell pm grant cloud.samlo.couch android.permission.WRITE_SECURE_SETTINGS
adb shell appops set cloud.samlo.couch GET_USAGE_STATS allow
adb shell settings put secure enabled_accessibility_services \
  cloud.samlo.couch/cloud.samlo.couch.HomeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

The ADB permissions add the now-playing pill and screen time, and let the HOME button repair
itself. The [setup guide](https://samlo.cloud/couch/setup) explains each step.

## Documentation

The full documentation is at **[samlo.cloud/couch](https://samlo.cloud/couch)**. The same pages live in [`docs/`](docs):

- [Setup](https://samlo.cloud/couch/setup): install, permissions, HOME button, updating.
- [Configuration](https://samlo.cloud/couch/configuration): every option, `config.json`, custom icons and wallpaper.
- [Architecture](https://samlo.cloud/couch/architecture): how the code is organised and why it stays fast.
- [Development](https://samlo.cloud/couch/development): building, the TV emulator, tests, releases.
- [Decisions](https://samlo.cloud/couch/decisions): the trade-offs behind the less obvious choices.

## Credits

Brand marks from [Simple Icons](https://simpleicons.org) (CC0) and Wikimedia Commons (public
domain). All trademarks belong to their owners. Weather by [Open-Meteo](https://open-meteo.com),
IP location by [ipwho.is](https://ipwho.is), subreddit images through the
[Arctic Shift](https://github.com/ArthurHeitmann/arctic_shift) archive.
