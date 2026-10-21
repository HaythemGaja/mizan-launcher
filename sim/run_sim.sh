#!/bin/sh
# Headless JVM sim for Mizan Launcher's pure logic (core/ has no Android
# imports, so it compiles standalone with kotlinc).
# Usage: sh sim/run_sim.sh   — writes TEST_LOG.md at the project root.
set -e
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-$HOME/workspace/.tools/kotlin-1.9.24/kotlinc/bin/kotlinc}"
[ -x "$KOTLINC" ] || KOTLINC="$(command -v kotlinc)"
"$KOTLINC" \
  app/src/main/java/com/mizan/launcher/core/Themes.kt \
  app/src/main/java/com/mizan/launcher/core/DominantColor.kt \
  app/src/main/java/com/mizan/launcher/core/NeverList.kt \
  app/src/main/java/com/mizan/launcher/core/LayoutStore.kt \
  app/src/main/java/com/mizan/launcher/core/ArrowPin.kt \
  app/src/main/java/com/mizan/launcher/core/GridMove.kt \
  app/src/main/java/com/mizan/launcher/core/MoveSession.kt \
  app/src/main/java/com/mizan/launcher/core/SettingsFit.kt \
  app/src/main/java/com/mizan/launcher/core/WifiLabel.kt \
  app/src/main/java/com/mizan/launcher/core/TileLayout.kt \
  app/src/main/java/com/mizan/launcher/core/WeatherLogic.kt \
  app/src/main/java/com/mizan/launcher/core/SettingsNav.kt \
  app/src/main/java/com/mizan/launcher/core/JsonMini.kt \
  app/src/main/java/com/mizan/launcher/core/Reddit.kt \
  app/src/main/java/com/mizan/launcher/core/StatusBar.kt \
  app/src/main/java/com/mizan/launcher/core/Forecast.kt \
  app/src/main/java/com/mizan/launcher/core/Rotation.kt \
  app/src/main/java/com/mizan/launcher/core/AppSearch.kt \
  app/src/main/java/com/mizan/launcher/core/SpeedUp.kt \
  app/src/main/java/com/mizan/launcher/core/Recents.kt \
  app/src/main/java/com/mizan/launcher/core/NowPlaying.kt \
  app/src/main/java/com/mizan/launcher/core/GuestGate.kt \
  app/src/main/java/com/mizan/launcher/core/SettingsGate.kt \
  app/src/main/java/com/mizan/launcher/core/AllApps.kt \
  sim/SimMain.kt \
  -include-runtime -d sim/sim.jar
{
  echo "# Mizan Launcher — headless test log"
  echo
  echo "Command: \`sh sim/run_sim.sh\` (kotlinc -> JVM sim over core/ pure logic)"
  echo "Date: $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo
  echo "Note (v1.2): the v1.1 numeric PIN-pad tests were REPLACED by arrow-PIN"
  echo "tests — the numeric PIN is retired (spec section 10.1). All other"
  echo "v1.1 sections carry over unchanged."
  echo
  echo "Note (v1.3): new sections — folder dedupe + folder lock, grid"
  echo "hold-and-move, per-tile status-bar switches, settings-fit metrics,"
  echo "switch logic, Wi-Fi SSID label (spec section 11). All v1.2 tests"
  echo "carry over."
  echo
  echo "Note (v1.4): new sections — forecast formatting, wallpaper"
  echo "rotation + Bing parse, app search filter, speed-up kill list,"
  echo "recents store, now-playing mapping, guest gate, row-order"
  echo "normalize + status codec v1.4 (spec section 12). All v1.3 tests"
  echo "carry over."
  echo
  echo "Note (v1.5): REMOVED with the Cleanup feature — the 18 cleanup"
  echo "classifier checks, replaced by 7 direct never-list checks (the"
  echo "classifier is gone; its never-list data survives as"
  echo "core/NeverList, also exercised through the speed-up kill list)"
  echo "and the 2 guest checks for the Settings/Cleanup"
  echo "actions (Settings entry is now decided by core/SettingsGate;"
  echo "Cleanup no longer exists). Folder constructions use the v1.5"
  echo "typed-entry model throughout. New sections: folder tree"
  echo "(depth/legality/cycle/delete-rehome/recursive dedupe), folder"
  echo "codec migration + nested round-trip, settings gate, All Apps"
  echo "tab list, status codec v1.5 (All Apps tile), move session by"
  echo "folder id + sub-folder entry moves (spec section 13)."
  echo
  echo '```'
  java -jar sim/sim.jar
  echo '```'
} | tee TEST_LOG.md
