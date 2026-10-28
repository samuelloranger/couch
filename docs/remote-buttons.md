# Remote buttons

Couch can answer two of the remote's buttons with its own lists: **Input** opens the TV's inputs
and your apps, and **Settings** opens quick settings. It is experimental and off until you turn it
on. Every other button, volume included, keeps working exactly as before.

## What you need

- **Network debugging (ADB) on.** Couch starts a small helper through the TV's own debugging
  connection; see [Setup](setup.md#_1-enable-adb-on-the-tv). The TV asks once to allow Couch.
- **Couch's accessibility service on.** It draws the lists over whatever is playing. Turning the
  feature on enables it if needed.
- On Android 12 and later, Couch gives itself the Bluetooth permission over the same debugging
  connection, to list the Bluetooth audio devices.

## Turning it on

1. **Customize → Remote buttons → Use Couch's lists.** If the TV asks to allow debugging, tick
   *Always allow* and choose *Allow*. **Status** says *Running* once the helper is up.
2. **Input button**: press OK, then the remote's Input button.
3. **Settings button**: press OK, then the remote's Settings button.

Each button is learned by pressing it, so any remote works, as long as the button reaches Android
as a key. Navigation, OK, Back, Home, volume and power can't be taken over.

## Input

The TV's inputs, then your apps in the Index list's order. A second press of Input moves to the next
input, as the TV's own list does.

## Settings

Quick settings in three groups, showing only what the TV has:

- **Settings**: all settings, network, Bluetooth, accessibility, parental controls and a sleep
  timer.
- **Picture and sound**, on a Sony Bravia that Couch is paired with (below): picture mode,
  brightness, light sensor, sound output and power saving, changed with Left and Right, and
  *Picture off*.
- **Bluetooth audio**: the paired headphones and speakers, and which one is connected. Android
  lets only system apps connect one, so OK opens the device's page in the TV's settings, with
  Connect as the first choice. While one is connected, *Sound output* shows its name.
- **TV**: the TV's own picture, sound, notification, timer and alarm screens, and **TV quick
  settings**, which opens the TV's own quick settings. Pressing Settings again does the same.

## Pairing with a Sony Bravia

Sony TVs have a control API ("IP control") that can change picture and sound settings. Reading
needs nothing; changing needs pairing once: **Customize → Remote buttons → Sony TV control**. The
TV shows a four-digit PIN in a window that takes over the remote, so Couch asks for it on a pad
drawn above that window: Up and Down change a digit, Left and Right move, number keys type, OK
pairs. Couch renews the sign-in by itself afterwards. If the TV refuses, check that remote control
over the network is on in the TV's network settings.

## How it works

Android hands the Input and Settings buttons to the TV's own menus before any app sees them. The
only app-level way around that, filtering keys in an accessibility service, stops held keys from
repeating, so holding volume no longer ramps.

So Couch works one level lower. Through the TV's debugging connection it starts a small helper
program (about 8 KB, built from `app/src/main/native/relay.c`). The helper takes the remote's input
device for itself and passes every event on through a virtual copy of the remote, with the same
name and key layout, so Android sees no difference. Only the buttons you set up are held back and
sent to Couch instead. If the helper stops for any reason, the remote goes back to the TV at once.

Turning the feature off stops the helper and gives the buttons back to the TV. The helper doesn't
survive a restart; Couch starts it again when the TV boots.
