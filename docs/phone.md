# Phone

The Couch app for iPhone controls a TV running Couch from the phone: a remote with a keyboard,
every Couch setting, the apps and inputs, screen time across your TVs, and a live picture of the
screen. Like [Other TVs](sync.md), it only talks to TVs you paired yourself, on your home network.
No account, no server in between.

The iPhone app isn't on the App Store yet: it is built from [`ios/`](https://github.com/samuelloranger/couch/tree/main/ios)
in this repository (see [Development](development.md)).

## Pairing

1. On the TV: **Customize → Couch → Phone → Pair a phone**.
2. In the app: **Add a TV**, then pick the TV, or type its address with **By address**.
3. Both screens show the same six digits. If they match, confirm on each.

As with TVs, there is no password: the digits prove nobody is in the middle. Stay in the app while
you compare them; leaving it closes the connection and pairing starts over. To remove a phone, open
it on the Phone page and press OK twice.

## What works, and what it needs

| | Needs |
|---|---|
| Settings (live, both ways), apps, inputs, screen time | Nothing more |
| Remote, keyboard, the TV's picture | The TV's debugging, allowed once for Couch, or a rooted TV |

Couch reaches keys and the screen through the TV's own debugging port, the same way
[Godmode](godmode.md) does. The first time, the TV asks **Allow debugging?**; choose
**Always allow**. On a rooted TV Couch uses root instead; a root manager may ask once.

Fire TV doesn't let apps reach its own debugging port, so there the remote and the picture need
a rooted Fire TV. Settings, apps and screen time work everywhere.

## The picture

Tap **Tap to watch the TV** on the remote. Three qualities: Low (540p), Medium (720p) and High
(1080p); Low is the default and the lightest for weak TVs. Apps that protect their video (most
streaming apps and live TV) show black: Android blanks them in any screen capture.

How fresh the picture is depends on the TV: on some, the screen reaches the phone about half a
second late. Keys themselves arrive at once.

## Away from home

The app only reaches TVs on your home network. To use it elsewhere, join your home network with a
VPN (WireGuard or similar); the app remembers each TV's address, so it finds it without the local
discovery a VPN doesn't carry.

## What is sent

Each connection uses a fresh key derived from the one you made when pairing, and every message is
encrypted with it. The phone sends keys, text and setting changes; the TV sends its settings, the
apps and inputs list, screen time and, while you watch, the picture. Nothing is stored on the TV
except the phone's name and its pairing key.
