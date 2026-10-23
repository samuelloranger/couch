# Godmode

A hidden page for people who want to tune the TV itself, not just Couch. It stays out of the way
until you look for it, and it costs the home screen nothing while it's locked.

## Opening it

Either way works:

- **Customize → About**, press OK seven times. A countdown appears from the third press.
- On the home screen, press **↑ ↑ ↓ ↓ ← → ← →** then OK. The arrows still move the selection
  normally; only the final OK is taken.

Once open, Godmode is listed in Customize under About. **Hide godmode** takes it out again;
seven presses bring it back.

## The debugging shell

Everything that changes the TV itself needs rights Android only gives to a shell. Couch connects
to the TV's own debugging service and asks for them, so no computer is needed:

1. Turn on **USB debugging** in the TV's developer options (Settings → Device preferences →
   About, press Build seven times, then Developer options).
2. In Godmode, press **Connect to this TV**.
3. Android asks "Allow USB debugging?" about a computer. That computer is Couch. Tick
   **Always allow**, then **Allow**, which sits to the left of Cancel.

One answer covers the whole visit. Fire TV refuses connections from apps; there, Godmode shows
the two commands to run from a computer instead, with this TV's address already filled in.

## Your TV

- **Put my TV back** — undoes every TV setting Godmode changed, exactly as it was. It records
  each setting's original value before the first change. Removed apps have their own list.
- **Animations** — Android's window and transition speed: off, half or normal.
- **Ad blocking** — Android's private DNS for the whole TV, through AdGuard's free resolver or a
  server of your own. It blocks what most apps load, but not the ads inside YouTube videos.
- **Close background apps** — closes what Android would close anyway when memory runs low.

## Tools

- **Remove built-in apps** — see [removing built-in apps](#removing-built-in-apps) below.
- **Free cache space** — clears every app's cache. On a full TV this frees far more than removing
  apps can, because built-in apps live on a read-only partition.
- **Sleep timer** — puts the TV on standby after 15 to 120 minutes, even if you leave Couch.
- **Screenshot** — waits ten seconds, so you can reach the screen you want, then saves it.
- **Make Couch the real home** — sets Couch as Android's preferred home, so HOME opens it without
  the accessibility workaround. Nothing is disabled, and uninstalling Couch hands HOME back by
  itself. TVs that ignore the preference keep working through the workaround.

## Measure

- **Frame HUD** — a small panel on the home screen: one line per remote press with that press's
  slowest frame and, when it ran over budget, the stage that took longest. Read it in
  milliseconds or in frames a second; the rate never claims more than the screen can show.
  The panel only redraws once you stop pressing, so it never measures itself.
- **Run benchmark** — returns to the home screen and plays the same set of presses every time,
  then shows the result with how each figure moved since the last run. Any key stops it.

In milliseconds the figures are P50, P90 and P99, and lower is better. In frames a second the
same frames read as MEDIAN, 10% LOW and 1% LOW, and more is better.

## Removing built-in apps

Couch carries its own short lists, per brand, drawn from published Android TV debloat guides, and
offers nothing outside them. Each app is measured on **your** TV first, so the scan tells you what
removal would actually buy:

- **Worth removing** — it runs all the time or is using memory now.
- **No gain on this TV** — it isn't running, so removing it frees nothing. Folded away.
- **Better kept** — apps people regret removing: casting, USB and media players, voice,
  accessibility, system updates, account sign-in, and anything the sources disagree on. They show
  greyed with the reason and can't be picked. An app you opened in the last 30 days joins them.
- **Never removed** — the home screen, WebView, Play services and the like. Removing one can stop
  the TV from starting.

Apps go four at a time. After each batch, restart the TV and check it still works: some firmware
brings removed apps back at boot, and Couch notices and offers to remove them again. Every removal
keeps the app's files, so **Removed apps** can put any of them back. That also means removal frees
memory, not storage.

On a TV that refuses Couch's shell, the scan and the list still work, and Couch gives you the
commands to run from a computer instead.
