# Railway Time 🚂

A widget-first 128bit app: a pixel railway clock for your home screen.

**Status:** landing page live. Product concept is Daniel's call — this README is a starter for Carlos to react to.

## The idea

Railways invented standard time. Railway Time puts it back where you can see it — a pixel railway clock designed as a **widget**, not an app you have to open. Glanceable, pixel-sharp, always on time.

Widget-first means the home-screen widget is the product. Any full app behind it is secondary.

## Open product questions (Daniel's call)

- What does the widget show besides the time? (Countdowns? Shift times? Family feed events?)
- iOS widget, Android widget, or both first?
- What sizes? (Small square? Medium rectangle?)

## Architecture notes

- **Shared 128bit event schema from day one.** One 128bit account, one shared event feed — Railway Time subscribes to `family_events` and can surface events from sibling apps on the widget. It can also emit its own events (e.g. `widget.viewed` is probably noise — don't emit that; meaningful events only).
- **Supabase backend.** The shared family event-feed SQL lives in [`supabase/family-feed.sql`](supabase/family-feed.sql) — run once in Supabase → SQL Editor. Same block as 128bitPlay's setup; keep them in step.
- **Monetization default:** 128bit Pro bundle subscription across all apps (not decided — Daniel's call).

## Android app (CN crew board widget)

`android/` is a native Android app + home-screen widget for CN crew. It shows:

- **Railtime clock**: local time plus an offset you set. By default it drops 1 hour at 02:00 on Nov 1, 2026 (the railway falls back; Alberta doesn't). Local time zone, offsets and the switch date are all in Settings.
- **Board position** and who's ahead: available (`A0`) and resting. `HT` rests until the Return time but holds the turn; `** HT` rests until the Return time and loses the turn (drops behind you) if a train passes while they rest.
- **Your rest** ("Rested 18 22:50 (in 7h06)") and the **next person off rest**.
- **Your likely train**: walks the pool lineup in order. Each train is ordered 2 h before planned (configurable) and goes to the first turn, top down, that's off rest by then; `**` turns it passes lose their turn, `HT` turns keep theirs, and if you're still on rest it passes you. It's an estimate.
- **ON DUTY / OFF DUTY switch** on the widget. On duty: nothing is fetched from CN and board info is hidden, only the clocks show. Off duty: refreshes every 10 minutes.

### How the CN data gets in

You sign in yourself inside the app ("Sign in / refresh from IVRM"): it opens CN SharePoint, you sign in (Microsoft + Authenticator as usual) and tap **Mobile Crew**. As soon as the crew board shows, the app reads it, saves it **on the phone only**, and remembers that page's address. Links that open new windows and crew pages embedded in SharePoint are followed automatically; load errors show at the top of the screen.

Background refreshes go straight to the remembered page: first a single quick request with your saved cookies (well under a second when the board is in the page itself), then, if needed, an off-screen WebView with images blocked that reads the board the moment it appears. When CN signs you out, the widget says so and background refreshes stop until you sign in again. Your password is never stored and nothing is sent anywhere except CN.

Mobile Crew opens in its own window, so it gets a real second page on top of SharePoint (Back closes it).

`ivrm.cn.ca` sends its certificate without the intermediate that links it to a trusted root. Chrome fetches the missing piece; apps don't, which is why IVRM showed a certificate error in the app. The app bundles that intermediate (Sectigo Public Server Authentication CA OV R36, `res/raw/sectigo_ov_r36.pem`) and trusts it for `cn.ca` sites only (`res/xml/network_security_config.xml`); `IvrmCertTest` checks the chain. If CN ever changes certificate and that error comes back, the debug info will show it.

Sites that ask for a device certificate (as Chrome hands over automatically on company-set-up phones) get Android's certificate picker; the choice is remembered for background refreshes.

**Backup that always works:** open IVRM in Chrome, take a screenshot and Share → *Railway Time board* (or "Import IVRM screenshot" in the app). The screenshot is read on the phone with ML Kit text recognition — nothing is uploaded — and the widget updates. "Paste copied IVRM page" does the same with Select all → Copy from Chrome. The reader copes with what screenshots do to the page (lost spaces like `190559`, `AO` for `A0`, a dropped status or initials); `RealScreenshotTest` runs the text read from a real IVRM screenshot through it.

If anything doesn't work, "Share debug info" sends the sign-in steps (addresses without query strings, errors) and the last page text — no passwords or cookies.

### Install

Every push builds the APK in GitHub Actions and attaches it to the `app-latest` release:
https://github.com/hootywhooo86/railwaytime/releases/download/app-latest/railwaytime.apk
Open that on the phone, allow "install unknown apps" for your browser, install, then long-press the home screen → Widgets → Railway Time.

### Build locally

```
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`android/debug.keystore` is a throwaway debug key, committed so every build installs over the last one.

## Landing page

Pixel-styled coming-soon page at the repo root, live via GitHub Pages: https://hootywhooo86.github.io/railwaytime/

Still needs a Formspree ID in `index.html` (search `YOUR_FORM_ID`).

## Repo layout

```
index.html              # coming-soon landing page (GitHub Pages)
README.md               # this file
android/                # Android app + widget (CN board, railtime clock)
  app/src/main/java/app/railwaytime/core/   # parser, predictor, railtime (pure Kotlin, unit tested)
supabase/
  family-feed.sql       # shared 128bit family event feed — run once in Supabase
```
