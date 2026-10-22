#!/bin/sh
# Headless JVM sim for Mizan Launcher's pure logic (core/ has no Android
# imports, so it compiles standalone with kotlinc).
# Usage: sh sim/run_sim.sh   — writes TEST_LOG.md at the project root.
set -e
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-$HOME/workspace/.tools/kotlin-1.9.24/kotlinc/bin/kotlinc}"
# v2.2: SimMain outgrew the default kotlinc heap (one giant main);
# give the compiler room unless the caller already chose a heap.
: "${JAVA_OPTS:=-Xmx6g}"
export JAVA_OPTS
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
  app/src/main/java/com/mizan/launcher/core/Design.kt \
  app/src/main/java/com/mizan/launcher/core/AppCatalogue.kt \
  app/src/main/java/com/mizan/launcher/core/HomeStyle.kt \
  app/src/main/java/com/mizan/launcher/core/TrackerLogic.kt \
  app/src/main/java/com/mizan/launcher/core/LibraryLogic.kt \
  app/src/main/java/com/mizan/launcher/core/ProfileNames.kt \
  app/src/main/java/com/mizan/launcher/core/UnlockGrace.kt \
  app/src/main/java/com/mizan/launcher/core/TrackerStatus.kt \
  app/src/main/java/com/mizan/launcher/core/HeroRotator.kt \
  app/src/main/java/com/mizan/launcher/core/TrackerSections.kt \
  app/src/main/java/com/mizan/launcher/core/AppCategories.kt \
  app/src/main/java/com/mizan/launcher/core/MostUsed.kt \
  app/src/main/java/com/mizan/launcher/core/ArtworkStore.kt \
  app/src/main/java/com/mizan/launcher/core/QuickSettings.kt \
  app/src/main/java/com/mizan/launcher/core/LiveTv.kt \
  app/src/main/java/com/mizan/launcher/core/IptvLogic.kt \
  app/src/main/java/com/mizan/launcher/core/IptvEpg.kt \
  app/src/main/java/com/mizan/launcher/core/IptvLocks.kt \
  app/src/main/java/com/mizan/launcher/core/IptvStatus.kt \
  sim/SimMain.kt \
  -include-runtime -d sim/sim.jar
{
  echo "# Sreeb (Mizan Launcher) — headless test log"
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
  echo "Note (v1.6): new sections — design tokens, app catalogue merge"
  echo "(lock coverage), Reddit endpoint chain + RSS fallback parser +"
  echo "visible fetch status (spec section 14). All v1.5 tests carry"
  echo "over; the RSS parser feeds the same RedditImage model as JSON."
  echo
  echo "Note (v1.8): new section — tracker logic: home style codec v2"
  echo "(tracker joins tv/ipad; the section-32 value-list check now"
  echo "expects all three), snapshot parsing, hero selection + cycle,"
  echo "countdown labels, continue merge/dedupe, movie ordering,"
  echo "airing-today + pill, marked-set filter + convergence prune,"
  echo "tracker search (spec section 16). All v1.7 tests carry over."
  echo
  echo "Note (v2.0): the app is rebranded Sreeb (display name only;"
  echo "package/signing/internal names unchanged). New section — hero"
  echo "rotator, tracker sections codec + compose, For You parse/filter/"
  echo "overlay convergence, app categories, most-used ranking, artwork"
  echo "codec + presets, quick-settings table, live TV state mapping,"
  echo "Netflix design tokens + poster metrics (spec section 18). The"
  echo "section-24 row-normalize expectations grew in place: the row"
  echo "set gained mostused (after recents) and livetv (after inputs)."
  echo
  echo "Note (v2.1): new section — library logic: full /tracked parse"
  echo "(watched sets + muted), library merge/sort/progress (continue"
  echo "order approximates recently-progressed; the payload has no"
  echo "timestamps), TMDB details/season/search parsing for the"
  echo "tracker's tmdb_get proxy, its error mapping (no_key/http_/io/"
  echo "bad_path), the mark_season CSV payload, and the generalized"
  echo "pending-actions overlay (codec, newest-toggle-wins, snapshot"
  echo "convergence prune) (spec section 19). All v2.0 tests carry over."
  echo
  echo "Note (v1.7): new section — home style (codec/default, label"
  echo "rule, squircle override), iPad grid composition (apps + root"
  echo "folders + inputs segments), dock composition (favorites cap,"
  echo "divider, fixed buttons), root-folder reorder, tile boost"
  echo "(spec section 15). All v1.6 tests carry over."
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
  echo "Note (v2.2): the iPad home style is REMOVED (Haythem: 'it is"
  echo "bad'). Section 32 was rewritten in place: the label-rule,"
  echo "squircle-override, grid-composition, dock-composition and"
  echo "tile-boost checks are DELETED with the renderers they tested,"
  echo "replaced by codec + migration checks (a saved 'ipad' decodes"
  echo "to TV) and the value list [tv, tracker, iptv]; the root-"
  echo "reorder checks stay (shared FolderTree machinery). Section"
  echo "33's label/shape check went with them. New section 37 — IPTV:"
  echo "server normalization, Xtream URL shapes (api/live/movie/"
  echo "series/timeshift), auth probe, catalogue parsing (string +"
  echo "numeric ids, malformed entries), series-info season/episode"
  echo "ordering, the M3U parser, favorites + resume codecs and clear"
  echo "rules, cross-catalogue search, home-row composition (empty"
  echo "categories skipped, favorites first, locks applied), the"
  echo "XMLTV parser (timezone offsets, entities, CDATA), now/next +"
  echo "progress at a fixed clock, short-EPG base64 titles, category"
  echo "pattern-locks (adult markers, stamp/grace rules), and the"
  echo "iptv status classification + diagnostics line (spec S20)."
  echo
  echo '```'
  java -jar sim/sim.jar
  echo '```'
} | tee TEST_LOG.md
