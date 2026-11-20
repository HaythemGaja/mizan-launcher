# Mizan Launcher 3.22

A lightweight, ad-free Android TV home screen written in Kotlin using Android Views.
The app retains its existing **Sreeb** display name and Mizan package identity.
Requires Android TV / Google TV, Android 8.0 (API 26) or later.

## Settings overhaul in 3.22

- Search all 12 settings categories by feature name or aliases such as backup,
  PIN, wallpaper, pairing or parental controls. Results open the relevant page.
- Category navigation updates the detail pane without rebuilding the menu or
  replacing the currently focused category view.
- Left returns to categories on TVs. OK/click toggles a switch; arrows never
  change its value. Back returns from details, then exits from categories.
- Narrow windows use a horizontal category strip: Left/Right browse, Down/OK
  enters the selected page, and Back returns to categories.
- Selected category, pane and last focused controls survive activity recreation;
  the settings entry lock is still re-evaluated.
- Clearer title/value text, larger control targets, navigation hints, reduced
  wasted detail padding and accessible switch labels with checked states.
- Backup imports validate the full envelope and known field types before any
  preference writes and cap provider reads at 4 MiB. Exports restore the active
  profile even if serialization fails and report inaccessible destinations.
- Six additional settings regression tests; 28 unit tests across both projects.
  Execution remains blocked here; see TEST_LOG.md.

## Launcher improvements included from 3.21

- Clean home has direct Apps, Search and My Library navigation, a live clock,
  a glass app dock, rounded hero artwork and stronger text contrast.
- Shared tiles have subtle layered surfaces and an accent focus outline.
  Hero gradients now follow actual screen bounds at any resolution.
- App search supports accents, multiple words and external keyboards. It
  restores the query after activity recreation and ranks exact labels first.
- Custom URL artwork stays saved after download. Writes are atomic, shaped
  cache hits invalidate older requests, and two workers handle artwork loading.
- Posters use bounded longest-side decoding, a byte-limited bitmap cache,
  size-aware request guards, weak view references and a bounded work queue.
- Phone remote uses bounded connections, strict HTTP framing, UTF-8 byte lengths,
  serialized command handling and bounded replies. Search and All Apps respect
  its app-lock enforcement switch.
- Library file replacement preserves the old file when a rename fails.
- Initial home composition no longer runs twice; activity teardown clears its
  handler callbacks and hero rotation.
- Debug builds work without the previous developer's private keystore path.
- 22 new unit tests and a GitHub Actions build/lint/test workflow are included.

## Existing features

Favorites, nested folders, profiles and Guest mode; arrow-pattern locks;
TV, Watch and Clean homes; wallpapers and dynamic accents; customizable status
bar; weather; a media library with episode progress and upcoming airings;
IPTV playlists, programme guide and playback; and an optional LAN phone remote.
No Compose, ads, analytics or launcher accounts.

## Build and run

Open this directory in Android Studio. Use JDK 17 and install Android SDK
Platform 34 and Build Tools 34.0.0. Allow Gradle to download its dependencies.
The wrapper uses Gradle 8.7 and the Android Gradle Plugin is 8.5.2.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mizan.launcher/.MainActivity
```

Choose Mizan as the default Home app through the TV's system settings if supported.
Accessibility services are optional; enable them only for the features you use.

On Windows, use `gradlew.bat` in place of `./gradlew` (or build from Android Studio).

The phone companion, version 1.1, is a separate project:

```sh
cd phone-remote
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Its debug APK is at `phone-remote/app/build/outputs/apk/debug/app-debug.apk`
relative to the launcher project. Pair using the address and code under the TV
launcher's Settings → Phone remote. It uses plain HTTP on the local network;
use it on a trusted LAN.

## Signing and updating an existing installation

A fresh debug build uses Android's standard local debug key. To update an
existing installation **without losing its data**, use the same signing key
that signed the installed APK. A version increase alone cannot bypass Android's
signature check. Keep your original key safe; it is not part of this archive.

The TV build accepts `MIZAN_KEYSTORE`, `MIZAN_STOREPASS`, `MIZAN_KEY_ALIAS`
and `MIZAN_KEYPASS`. The alias defaults to `mizanlauncher`; passwords default
to `android` for compatibility with the original debug key. Supply your own
passwords for a private release key. The companion uses the same suffixes with
`SREEBREMOTE_` and defaults to alias `sreebremote`.

```sh
export MIZAN_KEYSTORE=/absolute/path/to/original.keystore
./gradlew :app:assembleRelease
```

Without an explicit key, release output is unsigned. Do not uninstall your
existing launcher to work around a signing mismatch unless you have exported
and verified a backup of your data.

## Verification

See `TEST_LOG.md` for the actual checks and limitations of this revision.
`TEST_LOG-v3.20.md` is the original archive's historical log, not evidence that
3.22 passed. The existing broad pure-Kotlin simulation is still available:

```sh
sh sim/run_sim.sh
```

It requires Kotlin 1.9.24 (`kotlinc` on PATH, or set `KOTLINC`) and a JDK.
The runner propagates test failures and only writes a new log after compilation.
GitHub Actions builds and runs the unit tests for both Android projects when
this code is pushed to a repository. That workflow has not been run here.
