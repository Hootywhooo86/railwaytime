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

## Landing page

Pixel-styled coming-soon page at the repo root, live via GitHub Pages: https://hootywhooo86.github.io/railwaytime/

Still needs a Formspree ID in `index.html` (search `YOUR_FORM_ID`).

## Repo layout

```
index.html              # coming-soon landing page (GitHub Pages)
README.md               # this file
supabase/
  family-feed.sql       # shared 128bit family event feed — run once in Supabase
```
