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
  app/src/main/java/com/mizan/launcher/core/HomeTabs.kt \
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
  app/src/main/java/com/mizan/launcher/core/IptvStream.kt \
  app/src/main/java/com/mizan/launcher/core/IptvStore.kt \
  app/src/main/java/com/mizan/launcher/core/IptvEpg.kt \
  app/src/main/java/com/mizan/launcher/core/IptvLocks.kt \
  app/src/main/java/com/mizan/launcher/core/IptvStatus.kt \
  app/src/main/java/com/mizan/launcher/core/WatchMatch.kt \
  app/src/main/java/com/mizan/launcher/core/Artwork.kt \
  app/src/main/java/com/mizan/launcher/core/Discovery.kt \
  app/src/main/java/com/mizan/launcher/core/Diag.kt \
  app/src/main/java/com/mizan/launcher/core/PlayerLogic.kt \
  app/src/main/java/com/mizan/launcher/core/StartupHealth.kt \
  app/src/main/java/com/mizan/launcher/core/SectionGuard.kt \
  app/src/main/java/com/mizan/launcher/core/HomeCache.kt \
  app/src/main/java/com/mizan/launcher/core/SectionModule.kt \
  app/src/main/java/com/mizan/launcher/core/PlayInLogic.kt \
  app/src/main/java/com/mizan/launcher/core/RefreshPolicy.kt \
  app/src/main/java/com/mizan/launcher/core/StartupStages.kt \
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
  echo "Note (v2.3): the Watch home style joins the picker. Section 32's"
  echo "value-list check is updated in place to [tv, tracker, iptv,"
  echo "watch] and gains a watch-codec check — the only v2.2 check"
  echo "changed, because the picker's contents legitimately grew."
  echo "New section 38 — Watch matching (core/WatchMatch): title"
  echo "normalization (punctuation, articles, case, year extraction),"
  echo "exact-beats-fuzzy, year tie-breaks, ambiguity -> possible,"
  echo "no-match, episode resolution from series-info fixtures"
  echo "(missing season/episode -> unresolved), the match-cache"
  echo "fingerprint + codec, the auto-mark decision (ended/90% fires"
  echo "once, partial/tokenless never), and the badge/hero-label/"
  echo "diagnostics models (spec S21)."
  echo
  echo "Note (v2.3.1): hotfix from Haythem's on-device diagnostics"
  echo "photo (spec S22). Three new iptv diag checks: the ERROR line"
  echo "names the recorded failure class in parentheses when one"
  echo "exists, has no parenthetical when none is recorded, and the"
  echo "class never leaks into non-error lines. New section 39 —"
  echo "source checks: the manifest permits cleartext HTTP (his"
  echo "Xtream panel never answered because targetSdk 34 blocks"
  echo "http:// by default), and SettingsActivity carries no"
  echo "hardcoded 'Sreeb v<digit>' label — the version is read from"
  echo "PackageManager at runtime (the photo showed two different"
  echo "stale labels at once). All v2.3 checks carry over."
  echo
  echo "Note (v2.4): the IPTV data layer is rebuilt disk-first"
  echo "(spec S24) — catalogues live in .jsonl files + a small index"
  echo "under filesDir/iptv, ingest streams endpoints element-by-"
  echo "element and swaps atomically (all-or-nothing), reads are"
  echo "worker-thread slices, and refresh is on request only (save"
  echo "account / Refresh buttons). New section 41: streaming JSON"
  echo "+ XMLTV equivalence with the document parsers, the M3U"
  echo "stream parser, store line/index codecs, exact slice windows"
  echo "over a 5,000-entry fixture, find/tuples, the swap rule, the"
  echo "guide retention window, oversized-segment resync, sliced"
  echo "home composition, and source checks (legacy prefs purge, no"
  echo "auto-fetch on home builds, downsampled image decode + byte-"
  echo "bounded bitmap cache). All v2.3.2 checks carry over."
  echo
  echo "Note (v2.5): the ANR hunt (spec S25) — self-instrumentation"
  echo "(crash log, main-thread watchdog, breadcrumbs, all surfaced"
  echo "in Settings > Data & about with Copy diagnostics) plus the"
  echo "main-thread audit fixes. Proven root cause (dropbox ANR"
  echo "2026-10-22 22:50): Watch matching ran ON the main thread"
  echo "inside IptvClient.tuplesAsync's Handler delivery — it now"
  echo "runs worker-only over a once-built key index. New section"
  echo "42: watchdog staleness on a fake clock, the breadcrumb"
  echo "ring + persistence codec, the bounded event-log codec and"
  echo "tail rendering, keyed matchAll == per-title matchOne over"
  echo "an 820-candidate fixture, and source checks (worker match"
  echo "thread, purge commit, App bootstrap, Copy diagnostics)."
  echo "All v2.4 checks carry over."
  echo
  echo "Note (v2.3.2): hotfix from on-device evidence (spec S23):"
  echo "with the cleartext fix the real panel finally loaded, and"
  echo "every IPTV view that built one view per catalogue entry"
  echo "blacked out — the browser grids, the guide, and the home"
  echo "data path were all unbounded and unguarded. New section 40:"
  echo "an oversized fixture (5,000 live + 5,000 movies + 60"
  echo "favorites) proves the view caps (browser/guide GRID_CAP"
  echo "200, home rows capped in composition AND at render), a"
  echo "hostile EPG index proves the home composition fails open"
  echo "to its safe shell, and source checks prove the"
  echo "iptvbrowser / iptvguide / iptvhome guards are wired in."
  echo "All v2.3.1 checks carry over."
  echo
  echo "Note (v2.7): three user directives (spec S27). (1) Watch"
  echo "matching adopts the IPTV web player's method — squash"
  echo "(lowercase, strip non-[a-z0-9]), exact tier, contains tier"
  echo "over once-keyed candidates; section 38's normalization /"
  echo "keyOf / contains checks are RE-PINNED to the repo semantics"
  echo "(the repo method wins where they differ — e.g. 'Preacher'"
  echo "now matches 'Reacher'), and section 45 pins the repo"
  echo "fixtures (exact-after-squash, suffix + quality-tag noise,"
  echo "contains ordering, year deprioritization). (2) Focus rows:"
  echo "Coming Soon + Trending TV/Movies join TrackerSections"
  echo "(section 35's order-sensitive checks re-pinned to the new"
  echo "default order). (3) Self-reliance: the launcher computes"
  echo "Coming Soon / Trending / For You itself (the tracker"
  echo "computeForYou ported to core/Discovery — seeds, affinity,"
  echo "recommendations + discover, pick codec), tracker snapshots"
  echo "remain the fallback, and diagnostics gains a discovery:"
  echo "line. New section 45. All other checks carry over."
  echo
  echo "Note (v3.1): the five 2026-10-24 directives (spec S31). New section"
echo "50: Movie Night removal (registry/defaults/composition/hero pool/"
echo "stored-order drop), the local-first home cache (TTLs, staleness"
echo "wording, oldest-first prune, credential tripwire), the section"
echo "lifecycle machine (LOADING/READY/EMPTY/ERROR + retry routing),"
echo "Play-in candidate filtering (self excluded, no hardcoded"
echo "packages), and the sort page's stores (TrackerSections moves,"
echo "QuickSettings internal target) plus source checks for all five"
echo "wirings. Re-pins with comments: the v2.7 canonical/move/compose/"
echo "migrate expectations and the hero-pool order/sources (movienight"
echo "is gone); the numeric-string movie parse check is renamed (the"
echo "movies data itself is untouched)."
echo
echo "Note (v3.0): the crash-hardening redo (spec S30). New section"
  echo "48: the startup-health state machine (two consecutive"
  echo "uncleared starts -> safe shell on the third launch; a clean"
  echo "render + 10s survival clears), the section-guard policy"
  echo "(a throwing section — Error included — yields its error"
  echo "tile; reporting throttled to one per section per minute;"
  echo "tracker/watch/iptv fall back to the TV render, TV to the"
  echo "emergency root), JSON depth caps (JsonMini + the streaming"
  echo "parser refuse nesting past 64 — a 5000-deep hostile payload"
  echo "now reads as malformed instead of overflowing the stack),"
  echo "malformed-input fixtures for the discovery / season-episode"
  echo "/ store-index / short-EPG parsers, and source checks for the"
  echo "prefs funnel (every read through PrefsSafe; a wrong-typed"
  echo "stored value evicts itself and reads as its default), the"
  echo "guarded rebuilds in every activity, the main-thread-only"
  echo "crash-handler chain, the bounded image decodes and the"
  echo "rename-first store swap. One re-pin: the version check now"
  echo "expects 3.0 / code 23 (intentional bump for the redo). All"
  echo "794 v2.9 checks carry over. Section 49 adds the crash-on-"
  echo "open fixtures: truncated / garbled / half-migrated startup"
  echo "state (layout document, row + section orders, home style,"
  echo "tracker snapshots, match cache, discovery cache, store"
  echo "index) all fail open — and a whole-home fallback on the"
  echo "first build now HOLDS the startup-health clear, so a"
  echo "deterministic composition crash still arms the safe shell."
  echo
  echo '```'
  java -jar sim/sim.jar
  echo '```'
} | tee TEST_LOG.md
