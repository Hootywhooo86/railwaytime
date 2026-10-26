# Railway Time 🚂

A widget-first 128bit app: a pixel railway clock for your home screen.

**Status:** landing page live. Product concept is Daniel's call — this README is a starter for Carlos to react to.

## What it is now

Railway Time is a full clock app, a pixel replacement for Google Clock, built around a Swiss-style railway clock face (the red second hand sweeps round in 58.5 s and pauses at 12). Live at https://hootywhooo86.github.io/railwaytime/app/

- **ALARM**: repeat days, labels, 6 synthesized chiptune sounds with preview, snooze length, vibrate, "next alarm in…", missed-alarm notice.
- **CLOCK**: analog pixel railway clock + big digital time, 12/24h, world clock with any IANA time zone (day and hour offset, day/night icon).
- **TIMER**: several timers at once, presets, +1:00, pause/resume/reset, progress bars, the countdown in the tab title.
- **STOPWATCH**: laps with best/worst highlighting, keeps running across reloads.
- **BEDTIME**: bedtime + wake schedule, sleep length, wind-down reminder, wake-up alarm, a weekly sleep log with an on-time streak.
- Installable PWA, works offline, notifications when the tab is in the background, ☀ AWAKE keeps the screen on (nightstand mode).

**Web limitation:** a web app can only ring while it's open (a tab or installed app). Phones can suspend background tabs, so for must-not-miss alarms keep it open with ☀ AWAKE on. Ringing with the app fully closed needs a native wrapper (e.g. Capacitor with local notifications). That's the next step if you want it.

## Repo layout

```
index.html              # landing page (GitHub Pages)
app/                    # the clock app (PWA)
supabase/family-feed.sql
```
