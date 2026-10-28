# Calm Launcher

A small, fast Android TV home screen. No ads, no accounts, no network,
no third-party libraries, no foreground services.

## What it does (v0.1)

- A clock. Tap it to switch between 12- and 24-hour time.
- **Favorites**: long-press any app to add or remove it (up to 12, in the order you added them).
- **Recently opened**: the apps you launched most recently, excluding favorites.
- **All apps**: every launchable app, sorted by name.
- D-pad moves between tiles; OK opens an app; Back does nothing on the home screen.

## Design

- The rules live in `core/HomeModel.kt` with no Android imports, so they're
  tested by `sim/SimMain.kt` on any JVM.
- The Android layer is a thin view over those rules: `ui/HomeActivity.kt`
  (screen), `data/AppCatalog.kt` (finds apps), `data/Store.kt` (saved state).

## Build

Open in Android Studio, or run `./gradlew assembleRelease`.

Sim (pure logic, runs anywhere with kotlinc):

    kotlinc app/src/main/java/com/calm/launcher/core/HomeModel.kt sim/SimMain.kt -include-runtime -d sim/sim.jar
    java -jar sim/sim.jar
