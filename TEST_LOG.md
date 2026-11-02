# Sreeb (Mizan Launcher) — headless test log

Command: `sh sim/run_sim.sh` (kotlinc -> JVM sim over core/ pure logic)
Date: 2026-11-02 16:20 UTC

Note (v1.2): the v1.1 numeric PIN-pad tests were REPLACED by arrow-PIN
tests — the numeric PIN is retired (spec section 10.1). All other
v1.1 sections carry over unchanged.

Note (v1.3): new sections — folder dedupe + folder lock, grid
hold-and-move, per-tile status-bar switches, settings-fit metrics,
switch logic, Wi-Fi SSID label (spec section 11). All v1.2 tests
carry over.

Note (v1.4): new sections — forecast formatting, wallpaper
rotation + Bing parse, app search filter, speed-up kill list,
recents store, now-playing mapping, guest gate, row-order
normalize + status codec v1.4 (spec section 12). All v1.3 tests
carry over.

Note (v1.6): new sections — design tokens, app catalogue merge
(lock coverage), Reddit endpoint chain + RSS fallback parser +
visible fetch status (spec section 14). All v1.5 tests carry
over; the RSS parser feeds the same RedditImage model as JSON.

Note (v1.8): new section — tracker logic: home style codec v2
(tracker joins tv/ipad; the section-32 value-list check now
expects all three), snapshot parsing, hero selection + cycle,
countdown labels, continue merge/dedupe, movie ordering,
airing-today + pill, marked-set filter + convergence prune,
tracker search (spec section 16). All v1.7 tests carry over.

Note (v2.0): the app is rebranded Sreeb (display name only;
package/signing/internal names unchanged). New section — hero
rotator, tracker sections codec + compose, For You parse/filter/
overlay convergence, app categories, most-used ranking, artwork
codec + presets, quick-settings table, live TV state mapping,
Netflix design tokens + poster metrics (spec section 18). The
section-24 row-normalize expectations grew in place: the row
set gained mostused (after recents) and livetv (after inputs).

Note (v2.1): new section — library logic: full /tracked parse
(watched sets + muted), library merge/sort/progress (continue
order approximates recently-progressed; the payload has no
timestamps), TMDB details/season/search parsing for the
tracker's tmdb_get proxy, its error mapping (no_key/http_/io/
bad_path), the mark_season CSV payload, and the generalized
pending-actions overlay (codec, newest-toggle-wins, snapshot
convergence prune) (spec section 19). All v2.0 tests carry over.

Note (v1.7): new section — home style (codec/default, label
rule, squircle override), iPad grid composition (apps + root
folders + inputs segments), dock composition (favorites cap,
divider, fixed buttons), root-folder reorder, tile boost
(spec section 15). All v1.6 tests carry over.

Note (v1.5): REMOVED with the Cleanup feature — the 18 cleanup
classifier checks, replaced by 7 direct never-list checks (the
classifier is gone; its never-list data survives as
core/NeverList, also exercised through the speed-up kill list)
and the 2 guest checks for the Settings/Cleanup
actions (Settings entry is now decided by core/SettingsGate;
Cleanup no longer exists). Folder constructions use the v1.5
typed-entry model throughout. New sections: folder tree
(depth/legality/cycle/delete-rehome/recursive dedupe), folder
codec migration + nested round-trip, settings gate, All Apps
tab list, status codec v1.5 (All Apps tile), move session by
folder id + sub-folder entry moves (spec section 13).

Note (v2.2): the iPad home style is REMOVED (Haythem: 'it is
bad'). Section 32 was rewritten in place: the label-rule,
squircle-override, grid-composition, dock-composition and
tile-boost checks are DELETED with the renderers they tested,
replaced by codec + migration checks (a saved 'ipad' decodes
to TV) and the value list [tv, tracker, iptv]; the root-
reorder checks stay (shared FolderTree machinery). Section
33's label/shape check went with them. New section 37 — IPTV:
server normalization, Xtream URL shapes (api/live/movie/
series/timeshift), auth probe, catalogue parsing (string +
numeric ids, malformed entries), series-info season/episode
ordering, the M3U parser, favorites + resume codecs and clear
rules, cross-catalogue search, home-row composition (empty
categories skipped, favorites first, locks applied), the
XMLTV parser (timezone offsets, entities, CDATA), now/next +
progress at a fixed clock, short-EPG base64 titles, category
pattern-locks (adult markers, stamp/grace rules), and the
iptv status classification + diagnostics line (spec S20).

Note (v2.3): the Watch home style joins the picker. Section 32's
value-list check is updated in place to [tv, tracker, iptv,
watch] and gains a watch-codec check — the only v2.2 check
changed, because the picker's contents legitimately grew.
New section 38 — Watch matching (core/WatchMatch): title
normalization (punctuation, articles, case, year extraction),
exact-beats-fuzzy, year tie-breaks, ambiguity -> possible,
no-match, episode resolution from series-info fixtures
(missing season/episode -> unresolved), the match-cache
fingerprint + codec, the auto-mark decision (ended/90% fires
once, partial/tokenless never), and the badge/hero-label/
diagnostics models (spec S21).

Note (v2.3.1): hotfix from Haythem's on-device diagnostics
photo (spec S22). Three new iptv diag checks: the ERROR line
names the recorded failure class in parentheses when one
exists, has no parenthetical when none is recorded, and the
class never leaks into non-error lines. New section 39 —
source checks: the manifest permits cleartext HTTP (his
Xtream panel never answered because targetSdk 34 blocks
http:// by default), and SettingsActivity carries no
hardcoded 'Sreeb v<digit>' label — the version is read from
PackageManager at runtime (the photo showed two different
stale labels at once). All v2.3 checks carry over.

Note (v2.4): the IPTV data layer is rebuilt disk-first
(spec S24) — catalogues live in .jsonl files + a small index
under filesDir/iptv, ingest streams endpoints element-by-
element and swaps atomically (all-or-nothing), reads are
worker-thread slices, and refresh is on request only (save
account / Refresh buttons). New section 41: streaming JSON
+ XMLTV equivalence with the document parsers, the M3U
stream parser, store line/index codecs, exact slice windows
over a 5,000-entry fixture, find/tuples, the swap rule, the
guide retention window, oversized-segment resync, sliced
home composition, and source checks (legacy prefs purge, no
auto-fetch on home builds, downsampled image decode + byte-
bounded bitmap cache). All v2.3.2 checks carry over.

Note (v2.5): the ANR hunt (spec S25) — self-instrumentation
(crash log, main-thread watchdog, breadcrumbs, all surfaced
in Settings > Data & about with Copy diagnostics) plus the
main-thread audit fixes. Proven root cause (dropbox ANR
2026-10-22 22:50): Watch matching ran ON the main thread
inside IptvClient.tuplesAsync's Handler delivery — it now
runs worker-only over a once-built key index. New section
42: watchdog staleness on a fake clock, the breadcrumb
ring + persistence codec, the bounded event-log codec and
tail rendering, keyed matchAll == per-title matchOne over
an 820-candidate fixture, and source checks (worker match
thread, purge commit, App bootstrap, Copy diagnostics).
All v2.4 checks carry over.

Note (v2.3.2): hotfix from on-device evidence (spec S23):
with the cleartext fix the real panel finally loaded, and
every IPTV view that built one view per catalogue entry
blacked out — the browser grids, the guide, and the home
data path were all unbounded and unguarded. New section 40:
an oversized fixture (5,000 live + 5,000 movies + 60
favorites) proves the view caps (browser/guide GRID_CAP
200, home rows capped in composition AND at render), a
hostile EPG index proves the home composition fails open
to its safe shell, and source checks prove the
iptvbrowser / iptvguide / iptvhome guards are wired in.
All v2.3.1 checks carry over.

Note (v2.7): three user directives (spec S27). (1) Watch
matching adopts the IPTV web player's method — squash
(lowercase, strip non-[a-z0-9]), exact tier, contains tier
over once-keyed candidates; section 38's normalization /
keyOf / contains checks are RE-PINNED to the repo semantics
(the repo method wins where they differ — e.g. 'Preacher'
now matches 'Reacher'), and section 45 pins the repo
fixtures (exact-after-squash, suffix + quality-tag noise,
contains ordering, year deprioritization). (2) Focus rows:
Coming Soon + Trending TV/Movies join TrackerSections
(section 35's order-sensitive checks re-pinned to the new
default order). (3) Self-reliance: the launcher computes
Coming Soon / Trending / For You itself (the tracker
computeForYou ported to core/Discovery — seeds, affinity,
recommendations + discover, pick codec), tracker snapshots
remain the fallback, and diagnostics gains a discovery:
line. New section 45. All other checks carry over.

Note (v3.1): the five 2026-10-24 directives (spec S31). New section
50: Movie Night removal (registry/defaults/composition/hero pool/
stored-order drop), the local-first home cache (TTLs, staleness
wording, oldest-first prune, credential tripwire), the section
lifecycle machine (LOADING/READY/EMPTY/ERROR + retry routing),
Play-in candidate filtering (self excluded, no hardcoded
packages), and the sort page's stores (TrackerSections moves,
QuickSettings internal target) plus source checks for all five
wirings. Re-pins with comments: the v2.7 canonical/move/compose/
migrate expectations and the hero-pool order/sources (movienight
is gone); the numeric-string movie parse check is renamed (the
movies data itself is untouched).

Note (v3.0): the crash-hardening redo (spec S30). New section
48: the startup-health state machine (two consecutive
uncleared starts -> safe shell on the third launch; a clean
render + 10s survival clears), the section-guard policy
(a throwing section — Error included — yields its error
tile; reporting throttled to one per section per minute;
tracker/watch/iptv fall back to the TV render, TV to the
emergency root), JSON depth caps (JsonMini + the streaming
parser refuse nesting past 64 — a 5000-deep hostile payload
now reads as malformed instead of overflowing the stack),
malformed-input fixtures for the discovery / season-episode
/ store-index / short-EPG parsers, and source checks for the
prefs funnel (every read through PrefsSafe; a wrong-typed
stored value evicts itself and reads as its default), the
guarded rebuilds in every activity, the main-thread-only
crash-handler chain, the bounded image decodes and the
rename-first store swap. One re-pin: the version check now
expects 3.0 / code 23 (intentional bump for the redo). All
794 v2.9 checks carry over. Section 49 adds the crash-on-
open fixtures: truncated / garbled / half-migrated startup
state (layout document, row + section orders, home style,
tracker snapshots, match cache, discovery cache, store
index) all fail open — and a whole-home fallback on the
first build now HOLDS the startup-health clear, so a
deterministic composition crash still arms the safe shell.

```
