# Railway Time 🚂

A widget-first 128bit app: a pixel railway clock for your home screen.

**Status:** landing page live. Product concept is Daniel's call — this README is a starter for Carlos to react to.

## What it is now

Railway Time is a full clock app, a pixel replacement for Google Clock, built around a Swiss-style railway clock face (the second hand sweeps round in 58.5 s and pauses at 12). Every colour follows the 🎨 picker. Web app: https://hootywhooo86.github.io/railwaytime/app/ · Android app: see below.

- **ALARM**: repeat by days of the week **or by shift rotation** (e.g. 4 on / 4 off from a start date, with a 14-day preview), **skip next** for a day off, labels, 6 chiptune sounds, snooze length and **snooze limit**, vibrate, and **wake-up challenges** (solve a math problem, 3 harder ones, type a random code, or tap 30 times) so you can't turn it off half asleep.
- **CLOCK**: pixel railway clock + big digital time, world clock for any time zone, **countdowns** to trips, birthdays or your last shift, and **🛏 Nightstand mode**: a full-screen bedside clock (digital or railway face) that dims on tap, drifts slightly every minute to avoid burn-in, comes in night red, and glows like a sunrise over the 15 minutes before your alarm.
- **TIMER**: several timers at once, presets, +1:00, pause/resume, and **🍅 focus cycles** (focus/break blocks with a bell at each switch).
- **STOPWATCH**: laps with best/worst highlighting, keeps running across reloads.
- **BEDTIME**: bedtime + wake schedule, wind-down reminder, wake-up alarm, weekly sleep log.
- **⚙ Settings**: 12/24 h, hourly station chime (7 AM to 10 PM), nightstand style and colour, sunrise glow.

## Android app (alarms ring even when it's closed)

The `android/` folder wraps the same web app with [Capacitor](https://capacitorjs.com). In the app, every alarm, timer, focus block, bedtime reminder and countdown is handed to Android's alarm manager as an exact notification (`USE_EXACT_ALARM`, survives reboots), on a channel with that alarm's chiptune sound. Snooze and Stop work straight from the notification. Alarms with a wake-up challenge only offer Snooze there, so you have to open the app and solve it. If the app is open when an alarm goes off, it rings full-screen as usual.

**Install:** GitHub Actions builds the APK on every push (`.github/workflows/android.yml`). Pushes to `main` publish it at https://github.com/Hootywhooo86/railwaytime/releases/tag/android-latest. Open that page on your phone, download `RailwayTime.apk`, and allow installs from your browser. Later builds install over the old one and keep your alarms.

**Build it yourself:** `npm ci && npx cap sync android && cd android && ./gradlew assembleDebug` (needs JDK 21 and the Android SDK).

Known limits:
- Alarm sounds use Android's notification volume (and Do Not Disturb applies) because the notification plugin plays them as notification sounds. The sound lasts 30 seconds, getting louder, unless you open the app.
- Alarms are planned three weeks ahead and re-planned whenever you open the app, so open it at least once every three weeks.
- The APK is signed with `android/app/sideload.keystore`, a key committed to the repo for sideloading. A Play Store release needs a private key kept out of the repo.

## Repo layout

```
index.html              # landing page (GitHub Pages)
app/                    # the clock app (PWA) — also the Android app's web content
android/                # Capacitor Android project
tools/make_assets.py    # regenerates the Android alarm sounds and pixel icons
.github/workflows/      # builds and publishes the APK
supabase/family-feed.sql
```
