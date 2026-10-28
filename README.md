# Calm Launcher

A small, fast Android TV home screen. No ads, no accounts, no network,
no third-party libraries, no foreground services.

## What it does (v0.2)

- **Clock** with 12- or 24-hour time (set in Settings).
- **Folders**: one level deep, each app in at most one. Long-press an app → Move to folder.
  Folder tiles open a list of their apps. Folders hide when empty.
- **Favorites** (up to 12, in the order you added them) and **Recently opened**
  (excludes favorites). Long-press any app to change either.
- **All apps**: every launchable app, sorted by name.
- **Profiles** (up to 4): each has its own favorites, recents, folders, locks,
  wallpaper and clock. Switch, add or delete them in Settings → Profile.
- **Wallpapers**: six built-in gradients, per profile.
- **Arrow lock**: a D-pad pattern (3–8 arrows) that opens locked apps and folders.
  Lock an app or folder from its long-press menu. Changing or removing the pattern needs the current one.
- D-pad moves between tiles; OK opens; Back does nothing on the home screen.

## Not yet (planned)

- IPTV and the tracker integration: both depend on accounts or another app,
  so they need decisions before they're built.
- Photo wallpapers (needs a file picker).
- Nested folders, profile renaming, and Settings-level folder management.

## Notes

- The arrow lock is a simple lock for shared TVs, not a security boundary.

## Design

- The rules live in `core/HomeModel.kt` with no Android imports, so they're
  tested by `sim/SimMain.kt` on any JVM.
- Pure rules: `core/HomeModel`, `core/Profiles`, `core/Folders`, `core/ArrowPin`, `core/Wallpapers`.
- The Android layer is a thin view over those rules: `ui/HomeActivity.kt`,
  `ui/SettingsActivity.kt`, `ui/Dialogs.kt`, `data/AppCatalog.kt`, `data/Store.kt`.

## Build

Open in Android Studio, or run `./gradlew assembleRelease`.

Sim (pure logic, runs anywhere with kotlinc):

    kotlinc app/src/main/java/com/calm/launcher/core/*.kt sim/SimMain.kt -include-runtime -d sim/sim.jar
    java -jar sim/sim.jar
