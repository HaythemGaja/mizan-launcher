# Mizan Launcher

An original Android TV launcher — calm, fast, and ad-free by design.
Built for Google TV / Android TV boxes and sets (developed and tested
against an Onn Google TV device).

## Highlights

- Theme presets + dynamic accent colour from your wallpaper
- Wallpapers: gradients, your own photo, Reddit (with auto-rotation),
  Bing daily photo, or a live weather background
- Folders — including folders inside folders (3 levels), folder locks
- Arrow-pattern lock (D-pad directions) for apps, folders, and Settings
- Full-screen All Apps tab, app search with a D-pad keyboard
- Favorites, Recently Used, HDMI inputs, Continue Watching, Now Playing
- Profiles, including a Guest profile with locked settings
- Status bar you control: clock/date formats, per-tile switches,
  Wi-Fi network name, or hide the bar entirely
- iPhone-style on/off switches and two-column Roku-style Settings

## Notes

- Views-only Kotlin — no Compose, no ads, no analytics, no accounts.
- No foreground services. Optional features fail open and silently.
- Headless behaviour tests: `sh sim/run_sim.sh` (see `TEST_LOG.md`).

Installable builds are attached to the GitHub Releases of this repo.
