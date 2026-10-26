# Other TVs

Couch can add up screen time across the TVs in your house, so Index shows how long you used an app
on all of them, not just this one. It is off until you turn it on, and it only ever talks to TVs
you paired yourself, on your home network. Nothing goes through the internet and no account is
involved.

## What is shared

Per app, the last seven days of screen time, how many times it was opened, and when it was last
used, plus the app's name. Nothing else: no apps list beyond those with usage, no viewing history,
no settings. Each answer is encrypted for the one TV that asked, with a key only the two paired TVs
hold.

## Turning it on

1. On each TV: **Customize → Other TVs → Share screen time**. Usage access has to be granted, as
   for Index's screen time.
2. On both TVs: **Add a TV**.
3. On one of them, pick the other from the list. Both screens show the same six digits.
4. If they match, press **They match** on each TV. Nothing is saved unless both TVs agree.

There is no password to type. The digits are what proves nobody is in the middle: a stranger
would end up with different digits on each screen. If they differ, choose **They differ**.

If the other TV doesn't appear (some routers keep Wi-Fi devices apart), choose **By address** and
type its address from its network settings. The port is fixed, so nothing else is needed.

## What Index shows

**Screen time shows** picks This TV or All TVs. With All TVs, each app's week is this TV's usage
plus the paired TVs', day by day. An app matches on its package name first. When the same service
has a different package on another brand of TV, Couch matches it by name instead, in any of the
languages the TV knows it by (a TV set to French that calls it "Télé Fibe" still matches one that
calls it "Fibe TV"). Two apps of one TV are never merged into each other.

Only apps installed on this TV are listed; another TV's own apps don't add rows.

## When it syncs

A TV answers while Couch's process is running, which is normally whenever the TV is on. This TV
asks each paired one it can see, when Couch is opened and the last answer is more than fifteen
minutes old. A TV that is off or asleep keeps its last answer, and the row shows how long ago that
was. Forgetting a TV removes its key and its figures on the spot.

## Limits

- Both TVs must be on the same network and awake for a first sync.
- Totals don't change a TV's own figures: each one always uses its own live usage.
- Opens from another TV are added whole, even if only part of its week is still inside this one.
