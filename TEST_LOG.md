# Sreeb (Mizan Launcher) — headless test log

Command: `sh sim/run_sim.sh` (kotlinc -> JVM sim over core/ pure logic)
Date: 2026-10-25 00:08 UTC

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
PASS  neverlist: gms is never
PASS  neverlist: launcherx is never
PASS  neverlist: Play Store is never
PASS  neverlist: Settings + SystemUI are never
PASS  neverlist: setup/keyboard/bluetooth/tvinput are never
PASS  neverlist: own package is never
PASS  neverlist: ordinary apps are not never
PASS  layout: favorites round-trip
PASS  layout: hidden round-trip
PASS  layout: allAppsOrder round-trip
PASS  layout: folder names with separators round-trip
PASS  layout: folder ids round-trip
PASS  layout: folder apps round-trip
PASS  layout: row order round-trip
PASS  layout: row visibility round-trip
PASS  layout: re-encode is stable
PASS  layout: empty/garbage decode fails open
PASS  layout: profile isolation (favorites)
PASS  layout: profile isolation (hidden)
PASS  layout: move swaps neighbours
PASS  layout: move at edge is no-op
PASS  colour: dominant red wins
PASS  colour: solid blue returned
PASS  colour: near-black image falls back
PASS  colour: empty image falls back
PASS  colour: result is opaque
PASS  themes: at least 8 presets + dynamic
PASS  themes: preset ids unique
PASS  themes: preset 'apple_black' exists
PASS  themes: preset 'roku_purple' exists
PASS  themes: preset 'midnight' exists
PASS  themes: preset 'dusk' exists
PASS  themes: preset 'plum' exists
PASS  themes: preset 'forest' exists
PASS  themes: preset 'ocean' exists
PASS  themes: preset 'graphite' exists
PASS  themes: preset 'dynamic' exists
PASS  themes: exactly one dynamic preset
PASS  themes: every preset defines opaque bg/accent/text
PASS  gradients: exactly 14 built-ins
PASS  gradients: all 14 define render params (unique names, opaque colours)
PASS  themes: unknown preset id falls back to first preset
PASS  themes: unknown gradient falls back to first gradient
PASS  arrow append changes state
PASS  arrow submit below min is too short
PASS  arrow 4 steps can submit
PASS  arrow submit of 4 accepted
PASS  arrow 8 steps still submittable
PASS  arrow 9th append rejected as full
PASS  arrow submit of 8 accepted
PASS  arrow backspace removes last
PASS  arrow backspace on empty cancels
PASS  arrow cancel clears buffer
PASS  arrow set needs confirm
PASS  arrow set confirm match completes
PASS  arrow set confirm mismatch restarts
PASS  arrow set after mismatch takes fresh build
PASS  arrow unlock correct sequence matches
PASS  arrow unlock wrong sequence rejected
PASS  arrow unlock empty never matches unset lock
PASS  arrow encode round-trips
PASS  arrow encoded form is UDLRU
PASS  arrow numeric value is legacy
PASS  arrow numeric value decodes empty (migration discard)
PASS  arrow glyphs are arrows not digits
PASS  reddit: only usable image posts survive
PASS  reddit: direct i.redd.it image accepted
PASS  reddit: stickied rejected
PASS  reddit: video rejected
PASS  reddit: gallery rejected
PASS  reddit: NSFW rejected by default
PASS  reddit: portrait rejected
PASS  reddit: preview fallback accepted + URL unescaped
PASS  reddit: landscape dimensions carried through
PASS  reddit: NSFW accepted only with the toggle on
PASS  reddit: malformed JSON fails open to empty
PASS  reddit: valid subreddit names
PASS  reddit: invalid subreddit names rejected
PASS  reddit: r/ prefix cleaned
PASS  reddit: cache round-trip
PASS  statusbar: defaults match v1.0 behaviour
PASS  statusbar: codec round-trip identity
PASS  statusbar: default codec round-trip identity
PASS  statusbar: garbage decode fails open to defaults
PASS  statusbar: unknown field values fall back individually
PASS  statusbar: clock patterns honour 12/24h + seconds
PASS  statusbar: date patterns cover all formats
PASS  statusbar: text scales small/medium/large
PASS  statusbar: opacity alpha solid vs translucent
PASS  statusbar: survives layout encode/decode
PASS  statusbar: v1.0 layout without status line decodes to defaults

PASS  tiles deterministic: same inputs same size
PASS  tiles scale uniformly S < M < L
PASS  tiles more columns means smaller tiles
PASS  tile icon fits inside tile
PASS  watch card is uniform width > tile, same height
PASS  label visible only when focused
PASS  weather units fahrenheit params
PASS  weather units celsius params
PASS  weather units fromKey falls back to fahrenheit
PASS  weather clear day/night
PASS  weather partly day/night
PASS  weather cloudy + fog
PASS  weather rain group all rain
PASS  weather storm group
PASS  weather snow group
PASS  weather unknown code falls back to cloudy
PASS  weather choice prefers current
PASS  weather choice falls back to forecast dominant
PASS  weather choice fallback worst wins
PASS  weather choice with nothing is null
PASS  weather palettes complete + opaque for every condition
PASS  settings starts in list column, first category
PASS  settings right enters detail
PASS  settings right again is a no-op
PASS  settings left/back returns to list
PASS  settings back in list stays (caller exits)
PASS  settings category change swaps detail model
PASS  settings selection clamps at end
PASS  settings selection clamps at start
PASS  settings move down works
PASS  settings move past end clamps (no wrap)
PASS  settings focus sync marks detail
PASS  settings focus sync ignores nonsense
PASS  dedupe: apps in folders are excluded from All Apps
PASS  dedupe: app in two folders excluded once
PASS  dedupe: removing an app from its folder restores it
PASS  dedupe: hidden + in-folder still excluded
PASS  dedupe: empty folder list keeps everything visible
PASS  dedupe: favorites unaffected (favorite may live in a folder)
PASS  folderlock: locked flag round-trips
PASS  folderlock: re-encode is stable
PASS  folderlock: v1.2 folder strings decode unlocked
PASS  folderlock: legacy strings migrate with sequential ids
PASS  folderlock: locked empty folder round-trips
PASS  foldergate: unlocked folder opens
PASS  foldergate: locked with pattern prompts for pattern
PASS  foldergate: locked without pattern prompts to set one (never locked out)
PASS  gridmove: left swaps with neighbour
PASS  gridmove: left at row start is no-op (no wrap)
PASS  gridmove: right swaps with neighbour
PASS  gridmove: right at row end is no-op (no wrap)
PASS  gridmove: right at very last item is no-op
PASS  gridmove: up swaps with item one row up
PASS  gridmove: up from top row is no-op
PASS  gridmove: down swaps with item one row down
PASS  gridmove: down into partial last row works where an item sits below
PASS  gridmove: down is no-op where partial row has no item below
PASS  gridmove: single item list never moves
PASS  gridmove: invalid index/columns are no-ops
PASS  gridmove: cancel restores the exact snapshot
PASS  gridmove: drop-commit keeps all items exactly once
PASS  tileswitch: defaults all on except the Wi-Fi tile (v2.1: glyph + popover cover it)
PASS  tileswitch: codec round-trip with tiles off
PASS  tileswitch: sound independently off, others on
PASS  tileswitch: v1.2 status strings decode to defaults (Wi-Fi tile off since v2.1)
PASS  tileswitch: tiles survive the layout codec
PASS  tileswitch: garbage tiles segment fails open to defaults (Wi-Fi off, others on ? v2.1)
PASS  tilecodec: v2.0 7-char segment migrates the Wi-Fi tile to off
PASS  tilecodec: 8-char segment honours the Wi-Fi flag
PASS  tilecodec: re-enabled Wi-Fi tile survives encode/decode
PASS  bartiles: default bar = All Apps, Search, Profile, Settings
PASS  bartiles: re-enabled Wi-Fi tile sits between Search and Profile
PASS  bartiles: master tiles off leaves Profile + Settings
PASS  bartiles: safe mode shows the Settings tile only
PASS  bartiles: Settings switch off drops the tile (corner gear covers it)
PASS  statusglyph: Wi-Fi full alpha, Ethernet globe, offline dimmed
PASS  fit: 1920x1080 is normal (not compact)
PASS  fit: left pane is 30% clamped to 260..360dp
PASS  fit: 1280x720 is compact with smaller type/padding
PASS  fit: 960x540 is compact
PASS  fit: density converts px to dp (1920px @2x = 540dp tall = compact)
PASS  fit: portrait/narrow stacks panes
PASS  fit: bottom padding is at least one row height at every size
PASS  switch: on = green track, knob right
PASS  switch: off = grey track, knob left
PASS  switch: toggle flips both ways
PASS  wifi: quoted SSID is cleaned
PASS  wifi: unquoted SSID passes through
PASS  wifi: unknown sentinel becomes no-name
PASS  wifi: null/empty/quotes-only become no-name
PASS  wifi: label shows the network name
PASS  wifi: label falls back to generic Wi-Fi without a name
PASS  wifi: ethernet/other/offline labels unchanged
PASS  forecast: current hour index found
PASS  forecast: current hour index missing is -1
PASS  forecast: window has 24 entries starting at current hour
PASS  forecast: window wraps past midnight
PASS  forecast: 12h labels wrap at midnight
PASS  forecast: 24h labels
PASS  forecast: window wraps modulo at array end
PASS  forecast: short source yields whole source, empty yields empty
PASS  forecast: temp renders in Fahrenheit
PASS  forecast: temp renders in Celsius
PASS  forecast: day row label is weekday + date (2026-10-20 is a Tuesday)
PASS  forecast: day row condition + hi/lo + precip
PASS  forecast: day row without precip omits it
PASS  forecast: hour row formats all fields
PASS  forecast: condition labels cover groups
PASS  rotation: off is never due
PASS  rotation: never rotated counts as due
PASS  rotation: not due before interval, due at exact boundary
PASS  rotation: 1h and 6h and daily intervals
PASS  rotation: next index advances and wraps
PASS  rotation: next index empty list is -1, stale index wraps
PASS  bing: relative url becomes absolute
PASS  bing: absolute url passes through
PASS  bing: malformed json fails open to null
PASS  bing: day key is the calendar date
PASS  bing: day key changes across midnight
PASS  search: empty query returns all in order
PASS  search: substring match is case-insensitive
PASS  search: prefix matches sort before substring matches
PASS  search: no match is empty
PASS  search: order preserved inside groups
PASS  speedup: excludes never-list, system, self, foreground; dedupes
PASS  speedup: null foreground excludes nothing extra
PASS  speedup: never-list wins even for non-system flag
PASS  speedup: empty in, empty out
PASS  recents: empty decodes empty
PASS  recents: MRU order is newest first
PASS  recents: relaunch dedupes to front with new timestamp
PASS  recents: self is never recorded
PASS  recents: capped at 8, oldest dropped
PASS  recents: codec round-trips
PASS  recents: garbage decodes fail-open
PASS  nowplaying: dedupes by package, filters system-ui and blank titles
PASS  nowplaying: first session wins for a package
PASS  nowplaying: controls follow the actions mask
PASS  nowplaying: paused state is not playing, artist may be empty
PASS  nowplaying: no actions means no toggle
PASS  nowplaying: buffering reads as playing
PASS  nowplaying: empty in, empty out
PASS  guest: locked guest must enter pattern to switch away
PASS  guest: switching TO guest is never gated
PASS  guest: fail-open when no pattern set
PASS  guest: lock switch off means no gate
PASS  guest: not in guest means no gate
PASS  guest: personal-to-personal switch is free
PASS  guest: active flag only for the flagged profile
PASS  rows: stored order gains new rows after their anchors
PASS  rows: normalize is idempotent
PASS  rows: custom order preserved, insertions follow their anchors
PASS  rows: empty order gains the inserted rows
PASS  statusbar: search tile flag round-trips (Wi-Fi tile flag is the v2.1 default-off)
PASS  statusbar: v1.3 string decodes with search tile on
PASS  statusbar: v1.4 default round-trips with search on
PASS  statusbar: v1.3 string decodes with All Apps tile on
PASS  statusbar: v1.4 tile string decodes with All Apps tile on
PASS  statusbar: All Apps tile flag round-trips
PASS  movesession: grab finds the item, store untouched until write-back
PASS  movesession: machine moves do not leak into the store by themselves
PASS  movesession: first swap survives a layout reload
PASS  movesession: moves still land after the reload (down)
PASS  movesession: favorites final persisted order after RIGHT+DOWN+drop
PASS  movesession: all-apps grab index
PASS  movesession: all-apps LEFT swaps
PASS  movesession: all-apps UP swaps after reload
PASS  movesession: all-apps final persisted order after LEFT+UP+drop
PASS  movesession: folder RIGHT swaps
PASS  movesession: folder DOWN still lands after the folder object was replaced
PASS  movesession: folder final persisted order after RIGHT+DOWN+drop
PASS  movesession: sub-folder entry grabs by its entry key
PASS  movesession: sub-folder entry swaps like an app
PASS  movesession: parent order persisted with the entry moved
PASS  movesession: the sub-folder itself is untouched by the move
PASS  movesession: cancel restores the exact grab-time order
PASS  movesession: edge move is a no-op and reports false
PASS  movesession: unknown package cannot start a session
PASS  tree: depths are 1 for roots, +1 per level
PASS  tree: subtree heights
PASS  tree: descendants and ancestors
PASS  tree: breadcrumb is root-to-folder
PASS  tree: roots are the parentless folders
PASS  tree: fresh id skips taken ids
PASS  tree: a folder cannot contain itself
PASS  tree: cycle ? an ancestor cannot move under its descendant
PASS  tree: max depth ? a height-3 subtree fits nowhere deeper
PASS  tree: re-parenting a leaf up a level is legal
PASS  tree: legal parents exclude self, descendants, too-deep targets
PASS  tree: move re-parents (old parent loses the entry)
PASS  tree: move to null makes the folder a root
PASS  tree: deleting a nested folder rehomes its sub-folders
PASS  tree: recursive membership spans all depths
PASS  tree: dedupe hides an app nested at depth 3 from All Apps row
PASS  tree: prune drops dangling and self references
PASS  tree: prune leaves a folder with exactly one parent
PASS  tree: nested folders round-trip through the codec
PASS  settingsgate: lock on + pattern set -> prompt
PASS  settingsgate: already unlocked this entry -> allow
PASS  settingsgate: no pattern -> fail open even with lock on
PASS  settingsgate: guest rule alone requires the pattern
PASS  settingsgate: neither lock -> allow
PASS  settingsgate: global lock wins when guest lock is off
PASS  settingsgate: guest active but guest lock off and global off -> allow
PASS  allapps: hidden excluded, everything else listed
PASS  allapps: alphabetical by label
PASS  allapps: foldered apps badged with the containing folder
PASS  allapps: unfoldered apps carry no badge
PASS  allapps: label ties break by package
PASS  design: spacing scale increases S<M<L<XL
PASS  design: content margin is the TV margin (48dp)
PASS  design: scrim is bottom-weighted (bottom alpha > top)
PASS  design: scrim alphas stay in the calm band (10%..45%)
PASS  design: scrim colors carry exactly the token alphas
PASS  design: radii positive, panel rounder than tile, tile rounder than chip
PASS  design: resting tile fill is 8% white, panel fill is 25% black
PASS  design: focus scale is a slight lift (1.0..1.15)
PASS  catalogue: dedupes across categories, leanback label wins
PASS  catalogue: system apps are included, flagged
PASS  catalogue: Mizan itself is excluded
PASS  catalogue: hidden and foldered apps are in the catalogue
PASS  catalogue: sorted by label (case-insensitive)
PASS  catalogue: label ties break by package
PASS  redditchain: four endpoints, JSON first, RSS last
PASS  redditchain: endpoints are www, api, old, then www .rss
PASS  redditchain: subreddit names are cleaned into the URLs
PASS  redditchain: usable means at least one candidate
PASS  redditrss: landscape full-size entry survives
PASS  redditrss: dimensions come from the title tag
PASS  redditrss: known-portrait entry is rejected (landscape rule)
PASS  redditrss: thumbnail is the fallback when no direct file exists
PASS  redditrss: thumbnail URL is entity-unescaped
PASS  redditrss: permalink is captured as the post URL
PASS  redditrss: subreddit is stamped from the caller
PASS  redditrss: malformed input fails open to empty
PASS  redditrss: nsfw-tagged titles are rejected unless allowed
PASS  redditstatus: attempt line names endpoint, code, count
PASS  redditstatus: success summary shows the photo count
PASS  redditstatus: failure summary names the last result
PASS  homestyle: missing/garbage decodes to tv
PASS  homestyle: saved ipad migrates to tv (v2.2 removal)
PASS  homestyle: tracker and iptv survive the codec
PASS  homestyle: value list is tv, tracker, iptv, watch (the Settings picker order)
PASS  homestyle: watch survives the codec (v2.3)
PASS  homestyle: root reorder permutes only the root slots
PASS  homestyle: root reorder keeps nesting intact
PASS  homestyle: root reorder with the current order is a no-op
PASS  tracker: codec ? tracker survives, garbage still decodes to tv
PASS  tracker: upnext parse ? fields land, id-less item dropped
PASS  tracker: parse is fail-open ? garbage and null give empty lists
PASS  tracker: continue parse ? progress counts land
PASS  tracker: movies parse ? planned/poster/watched land, null date reads blank
PASS  tracker: tracked parse ? watched[] becomes a count
PASS  tracker: hero order ? upnext+backdrop, continue+backdrop, rest
PASS  tracker: hero featured pick is the first upnext with a backdrop
PASS  tracker: hero cycle wraps forward and backward
PASS  tracker: hero falls back to continue when upnext has no backdrop
PASS  tracker: hero falls back to first upnext when nothing has a backdrop
PASS  tracker: hero of an empty cycle is null
PASS  tracker: continue hero carries no episode coordinates
PASS  tracker: countdown ? today, tomorrow, in N d
PASS  tracker: countdown ? past is Aired, far future is the date itself
PASS  tracker: countdown ? month/year boundaries use real day math
PASS  tracker: countdown ? empty or malformed dates give no label
PASS  tracker: airing-today counts only today's items
PASS  tracker: pill text composes the numbers the data supports
PASS  tracker: merge ? tracker cards first, in snapshot order
PASS  tracker: merge ? system titles matching a tracker title drop out
PASS  tracker: merge ? unique system titles survive once, with their index
PASS  tracker: title normalization ignores case, spaces, punctuation
PASS  tracker: movies ? planned first by release date, then the rest, watched excluded
PASS  tracker: movies ? unknown release dates sort last within their group
PASS  tracker: movies ? all-watched falls back to the full sorted list
PASS  tracker: movies ? locally-marked movies stay out even in the fallback
PASS  tracker: marked codec round-trips and skips garbage lines
PASS  tracker: withMarked replaces the same item instead of duplicating it
PASS  tracker: filterUpNext hides only the marked episode
PASS  tracker: filterContinue hides a show with any marked episode
PASS  tracker: prune keeps entries the snapshot is not fresher than
PASS  tracker: prune drops a tv entry once a fresher snapshot lost the episode
PASS  tracker: prune keeps a tv entry while the episode is still in the snapshot
PASS  tracker: prune drops a movie entry once the snapshot flags it watched
PASS  tracker: search ? prefix hits (shows then movies) before substring hits
PASS  tracker: search detail counts watched episodes
PASS  tracker: search finds movies with the tracked detail
PASS  tracker: search is case-insensitive substring, prefix first
PASS  names: normalize trims and collapses whitespace runs
PASS  names: blank or null normalizes to no-custom-name
PASS  names: cap is 16 chars and never leaves a trailing space
PASS  names: default labels ? Guest for the flagged profile, Profile N otherwise
PASS  names: display ? custom name wins, blank falls back to the default
PASS  grace: windows and labels line up; garbage coerces to Every time
PASS  grace: window 0 (Every time) never grants, even with a fresh stamp
PASS  grace: no stamp never grants
PASS  grace: inside the window grants (5 min, 1 ms before the edge)
PASS  grace: expiry is exact ? at the boundary the gate prompts again
PASS  grace: a stamp in the future (clock moved back) never grants
PASS  grace: longer windows hold proportionally (30 min at 29:59)
PASS  tracker status: classify ? not installed beats everything
PASS  tracker status: classify ? a permission refusal is reported even with cached data
PASS  tracker status: classify ? answered+data is NONE, answered-empty is EMPTY, silent is OTHER
PASS  tracker status: diag line forms for every state
PASS  tracker status: diag OK form carries per-section counts and the snapshot time (v2.1)
PASS  tracker status: diag NONE without data reads as the empty state
PASS  tracker status: fallback line ? absent tracker shows NO line
PASS  tracker status: fallback line ? permission state is actionable
PASS  tracker status: fallback line ? empty/other/none ask for one tracker open
PASS  netflix: page + brand tokens
PASS  poster metrics: height is two tile heights, width is 2:3
PASS  poster metrics: portrait, and follows the size scale
PASS  rotator: first advance is due one interval out
PASS  rotator: tick at the due time advances and re-times
PASS  rotator: advance wraps past the last candidate
PASS  rotator: fewer than 2 candidates never rotates
PASS  rotator: focus held blocks a late tick
PASS  rotator: release resumes after the resume delay, not at once
PASS  rotator: Next advances immediately and resets the timer
PASS  rotator: a shrunk candidate list clamps the index
PASS  rotator: initial index is clamped into range
PASS  sections: default config composes the canonical order
PASS  sections: codec round-trips a custom config
PASS  sections: garbage decodes to defaults
PASS  sections: a hidden hero drops only the hero
PASS  sections: move up swaps with the previous row
PASS  sections: moves at the edges and for the hero are no-ops
PASS  sections: compose follows the configured order, hero first
PASS  sections: hidden + data-empty sections are both skipped
PASS  sections: unknown keys are dropped by normalize
PASS  foryou: picks parse with the contract shape (whyGenres are names)
PASS  foryou: malformed JSON yields no picks
PASS  foryou: rating text is one decimal, absent when zero
PASS  foryou: already-tracked picks (tv + movie) are dropped
PASS  foryou: overlay-dismissed picks are dropped
PASS  foryou: the row is capped
PASS  foryou: overlay codec round-trips
PASS  foryou: a snapshot older than the mark retires nothing
PASS  foryou: an add retires once the title is tracked
PASS  foryou: an add also retires when the pick leaves the snapshot
PASS  foryou: a dismiss retires only when the pick leaves the snapshot
PASS  categories: curated packages win over the manifest category
PASS  categories: manifest category table
PASS  categories: sections render in order, non-empty only, alpha inside
PASS  mostused: count desc, recency breaks count ties, name breaks recency ties
PASS  mostused: hidden + non-launchable excluded, limit respected, zero counts dropped
PASS  artwork: 12 presets with names + colours, indices coerced
PASS  artwork: codec round-trips presets and escaped URLs
PASS  artwork: garbage decodes empty; with() sets and removes
PASS  quicksettings: six system targets, then Sort sections, Refresh home data, Sreeb Settings
PASS  quicksettings: unresolvable targets are filtered, internal + Sreeb Settings stay
PASS  livetv: state precedence (feature, security, failure, count)
PASS  livetv: diagnostics wording
PASS  library: tracked parse keeps watched set + muted + planned, drops bad rows
PASS  library: tracked parse fails open
PASS  library: next coords parse from continue sub
PASS  library: caught-up / garbage subs yield no next coords
PASS  library: shows + movies merge into one grid
PASS  library: sort - in-progress first (continue order), then up-next, then A-Z, finished last
PASS  library: progress derives from continue (count/total/next) + poster
PASS  library: caught-up show detected from the continue sub
PASS  library: progress line + fraction
PASS  library: poster cache fills a poster the snapshots lack
PASS  library overlay: removed title vanishes from the grid
PASS  library overlay: added title appears as pending (planned)
PASS  library overlay: mute + movie-unwatch override the snapshot
PASS  library overlay: marking the known next episode clears the next claim
PASS  overlay: codec round-trips escaped titles/posters
PASS  overlay: codec fails open on garbage
PASS  overlay: newest toggle wins (mark then unmark leaves only the remove)
PASS  overlay: effective watched = snapshot plus adds minus removes
PASS  overlay: season mark adds every listed episode (and un-marks none)
PASS  overlay prune: converged parts retire, unconverged stay
PASS  overlay prune: a stale snapshot retires nothing
PASS  overlay prune: an add retires when the title reaches the snapshot
PASS  overlay prune: a remove retires when the title leaves the snapshot
PASS  overlay prune: add-then-remove settles whole once snapshots are fresher
PASS  overlay prune: an unwatch retires when the episode leaves the watched set
PASS  tmdb: tv details parse (fields, year, seasons sorted, empty season dropped)
PASS  tmdb: movie details parse (title/release year, no seasons)
PASS  tmdb: details parse fails open
PASS  tmdb: season episodes parse ascending
PASS  tmdb: mark_season CSV is sorted, deduped, positive-only
PASS  tmdb: season parse fails open
PASS  tmdb: search keeps tv+movie, drops persons and bad rows
PASS  tmdb: search parse fails open
PASS  tmdb: proxy paths (details / season / search with encoding)
PASS  tmdb: error kinds + http code
PASS  tmdb: retryable = http/io/unknown only
PASS  tmdb: user lines (no_key names the fix; http carries the code)
PASS  tmdb: diagnostics wording (v2.6: path + human text + raw token in parens)
PASS  poster cache: codec round-trips, garbage dropped
PASS  movies: numeric-string tmdbId still parses
PASS  hero pool: missed first, most overdue first (backdrop never reorders)
PASS  hero pool: missed heroes carry the Missed source + label with date chip
PASS  hero pool: empty schedule falls back to Continue (backdrop-first) + For You (backdrop-gated)
PASS  hero pool: two-source overload keeps the v1.8 order and tags
PASS  iptv: bare host gains http:// and loses the trailing slash
PASS  iptv: account needs server + user + pass
PASS  iptv: api url is player_api.php with encoded credentials + action
PASS  iptv: xmltv url shape
PASS  iptv: live url is /live/user/pass/id.m3u8
PASS  iptv: vod url uses container_extension, defaulting to mp4
PASS  iptv: episode url is /series/user/pass/episodeId.ext
PASS  iptv: timeshift url is /timeshift/user/pass/durationMin/startYmdHis/id.ts
PASS  iptv: auth probe ? 1 true, 0 false, string ids accepted, junk null
PASS  iptv: categories parse (string+numeric ids, blank names skipped, key stamped)
PASS  iptv: live parse skips bad ids + blank names, resolves category names
PASS  iptv: tv_archive flag drives catch-up availability
PASS  iptv: vod parse keeps container_extension + numeric rating as text
PASS  iptv: series list parse
PASS  iptv: series info ? seasons sort numerically, ghost episodes skipped
PASS  iptv: series info ? episode fields (ext, duration, plot)
PASS  iptv: m3u channels + groups (quote-aware name, ungrouped ? Playlist)
PASS  iptv: m3u channel identity ? tvg-id/logo/direct url, stable ids
PASS  iptv: favorites codec round-trips hostile names
PASS  iptv: favorites toggle adds then removes by identity key
PASS  iptv: favorites decode is fail-open on garbage
PASS  iptv: shouldClear ? near the end or ?95% is finished; unknown duration is not
PASS  iptv: resume store replaces by key and drops finished entries
PASS  iptv: resumeFor hides sub-threshold positions
PASS  iptv: continue list is vod/episode only, newest first
PASS  iptv: resume codec round-trips
PASS  iptv: search is case-insensitive across all three catalogues
PASS  iptv: search excludes hidden categories
PASS  iptv: home rows order ? favorites, live categories, continue, movies, series
PASS  iptv: home live card carries the EPG now title + progress
PASS  iptv: home rows drop locked categories (empty category rows never render)
PASS  iptv: home rows with no data compose to nothing
PASS  iptv: xmltv parses channels, unescapes entities, unwraps CDATA, skips broken spans
PASS  iptv: xmltv timezone offsets convert to the same absolute instant
PASS  iptv: now/next selection at a fixed clock + progress fraction
PASS  iptv: short epg ? base64 titles, listing times read as UTC
PASS  iptv: adult markers match case-insensitively
PASS  iptv: lock store codecs round-trip
PASS  iptv: stamp validity ? grace window vs session rule
PASS  iptv: isLocked ? no pattern means nothing locks; stored + adult lock; a valid stamp opens
PASS  iptv: hiddenKeys composes the decision over a category list
PASS  iptv: status classify precedence ? unconfigured, data beats auth, auth, empty, error
PASS  iptv: diag line carries counts + time, never credentials
PASS  iptv: empty-state lines per kind
PASS  iptv: diag line names the failure class after 'not answering' when one is recorded
PASS  iptv: diag line has no parenthetical when no error is recorded
PASS  iptv: a recorded error class never leaks into non-error lines
PASS  watch: normalization is the repo squash (articles/spaces/punctuation vanish)
PASS  watch: splitYear takes bracketed / trailing / leading years only
PASS  watch: keyOf prefers a known year over the title token (key is the whole squash)
PASS  watch: exact normalized match wins, unique + year agree = exact
PASS  watch: punctuation/case variant still exact-matches
PASS  watch: year breaks a same-name tie (remakes)
PASS  watch: ambiguous same-name (no usable year) picks first, flagged possible
PASS  watch: year conflict (both known, different) is possible, not exact
PASS  watch: contains tier matches a decorated name; a year conflict flags possible
PASS  watch: exact tier beats the contains tier outright
PASS  watch: repo contains tier matches 'Preacher' for 'Reacher' (crude by design); empty catalogue -> no match
PASS  watch: matchAll keys by tmdbId and skips unmatched
PASS  watch: episode resolution finds id + extension
PASS  watch: missing season / missing episode resolve to null (fail-open)
PASS  watch: fingerprint is stable for same inputs, moves on catalogue or tracker change
PASS  watch: match codec round-trips ids + flags, skips garbage, stores no titles
PASS  watch: auto-mark fires on ended (even at low position)
PASS  watch: auto-mark fires at exactly 90%, not at 89%
PASS  watch: auto-mark never fires twice, never tokenless, never on a partial watch
PASS  watch: badge only when matched; hero labels name the episode, movies plain
PASS  watch: diag line is counts-only
PASS  iptv232: hostile EPG index ? homeRowsOrEmpty fails open to no rows
PASS  iptv232: the same composition with a sane EPG still composes rows
PASS  iptv232: home rows stay capped on a 5,000-entry catalogue (live + movies ? ROW_CARD_CAP)
PASS  iptv232: the favorites row is capped too (60 stored ? ROW_CARD_CAP)
PASS  iptv232: the home VIEW cap trims any row to HOME_ROW_VIEW_CAP (20)
PASS  iptv232: browser grid page caps a 5,000-channel category at GRID_CAP (200), total preserved
PASS  iptv232: search results page the same way (5,000 matches ? 200 shown)
PASS  iptv232: a list under the cap pages whole and untruncated
PASS  iptv232: the browser rebuild is guarded and records as iptvbrowser
PASS  iptv232: the browser pages its grids through gridPage
PASS  iptv232: the guide rebuild is guarded, records as iptvguide, and pages its rows
PASS  iptv232: the home build records iptvhome and renders rows through homeRowCards
PASS  iptvstore: streamArray delivers every object element (5 objects; the stray scalar is consumed + skipped)
PASS  iptvstore: streamed rows equal the whole-document parse
PASS  iptvstore: streamArray rejects a non-array document (ingest keeps the old store)
PASS  iptvstore: M3U stream parse equals the document parse (cats + channels)
PASS  iptvstore: live line round-trips codec-hostile fields
PASS  iptvstore: M3U live line round-trips (direct URL survives)
PASS  iptvstore: vod line round-trips incl. year
PASS  iptvstore: series line round-trips
PASS  iptvstore: series plots are capped at PLOT_CAP in the store line
PASS  iptvstore: epg line round-trips
PASS  iptvstore: garbage lines decode to null, never throw
PASS  iptvstore: index round-trips (stamps, counts, byte sizes, hostile cat names)
PASS  iptvstore: index serves categories + per-category counts by kind
PASS  iptvstore: sizeText renders B / KB / MB honestly
PASS  iptvstore: slice window [0,200) of 5,000 is exact, total from the index
PASS  iptvstore: slice window at the tail is exact (ids 4801..5000)
PASS  iptvstore: category slice counts its own total (2,000 of 4,000)
PASS  iptvstore: excluded-category slice skips hidden cats, index-adjusted total
PASS  iptvstore: query slice counts matches across the whole file (499 + 4990..4999 = 11)
PASS  iptvstore: find locates one item in a 3,000-line file; a miss is null
PASS  iptvstore: tuples carry only (id, title, year) for matching
PASS  iptvstore: swap only when every attempted catalogue endpoint succeeded
PASS  iptvstore: XMLTV stream delivers the same programmes as the document parse
PASS  iptvstore: guide window keeps now/next, drops the long past and the far future
PASS  iptvstore: an oversized programme segment is dropped, the stream resyncs
PASS  iptvstore: sliced home composes favorites ? live ? movies ? series
PASS  iptvstore: sliced home falls back to a flat Live row without categories
PASS  iptvstore: the client purges the legacy prefs documents on first v2.4 run
PASS  iptvstore: no whole-catalogue snapshot survives in the client (no cached(), no raw snapshot)
PASS  iptvstore: the client streams ingest endpoints and slices reads
PASS  iptvstore: no auto-fetch remains on home builds (refresh is on request only)
PASS  iptvstore: the IPTV home composes from slices (homeRowsSliced + sliceBlocking)
PASS  iptvstore: the browser reads slices and never fetches on open/resume
PASS  iptvstore: image decode is downsampled and the bitmap cache is byte-bounded
PASS  hotfix: manifest permits cleartext HTTP (Xtream panels / M3U / XMLTV are http://)
PASS  hotfix: no hardcoded 'Sreeb v<digit>' label remains in SettingsActivity
PASS  hotfix: the version label is read from PackageManager at runtime
PASS  diag: a heartbeat 3.9 s old is fresh
PASS  diag: a heartbeat exactly at the threshold is not yet stale (strictly past it is)
PASS  diag: a heartbeat a minute old is stale
PASS  diag: a custom threshold is honoured
PASS  diag: no heartbeat yet reads stale once past the threshold
PASS  crumbs: an empty ring snapshots empty
PASS  crumbs: snapshot is oldest-first
PASS  crumbs: the ring keeps only the newest N
PASS  crumbs: lines render an HH:mm:ss clock + the label
PASS  crumbs: the persist/restore round-trip is exact
PASS  crumbs: restore over capacity keeps the newest
PASS  crumbs: restore skips malformed lines
PASS  diaglog: events accumulate oldest-first
PASS  diaglog: the event cap keeps the newest events
PASS  diaglog: tailLines returns the last lines (what diagnostics shows)
PASS  diaglog: a full tail returns every line
PASS  diaglog: an empty log tails empty
PASS  diaglog: the char cap never truncates the newest event
PASS  diaglog: an oversized old event is dropped to fit the cap
PASS  watchkey: keyed matchAll == per-title matchOne over 410 candidates
PASS  watchkey: the fixture actually matches (the equivalence is not vacuous)
PASS  watchkey: the index path keeps the year tie-break
PASS  watchkey: the index path keeps ambiguity -> first candidate + possible
PASS  v25src: Watch matching recomputes on the mizan-watch-match worker
PASS  v25src: WatchPlay never calls the deleted main-thread tuplesAsync
PASS  v25src: tuples are a worker-only blocking read now (tuplesBlocking)
PASS  v25src: the legacy purge lands via a single commit()
PASS  v25src: the manifest bootstraps the App class (DiagLog install)
PASS  v25src: Settings offers Copy diagnostics with the freeze/crash tails
PASS  v25src: the app version is 3.3 / code 26 (bumped by v3.3)
PASS  aspect: cycle runs Fit -> Stretch -> Zoom -> Fit
PASS  aspect: garbled/null stored values normalize to Fit
PASS  aspect: Fit letterboxes a 4:3 video inside the screen
PASS  aspect: Stretch takes the container exactly
PASS  aspect: Zoom covers the screen (overflow crops)
PASS  aspect: a portrait video Fits by height and Zooms by width
PASS  aspect: unknown video size falls back to the container; empty container stays empty
PASS  speed: the ladder cycles and wraps 2x -> 0.5x
PASS  speed: an off-ladder value snaps to the nearest rung before advancing
PASS  speed: labels trim trailing zeros
PASS  tracks: audio filter keeps audio only, in index order
PASS  tracks: text filter keeps timed-text + subtitle only
PASS  tracks: Audio button needs >1 audio track; Subs button needs >=1 text track
PASS  tracks: labels prefer the language, else a 1-based ordinal
PASS  tracks: audio cycle wraps and tolerates an unknown current
PASS  tracks: subtitle cycle runs Off -> tracks -> Off
PASS  bar: the title line composes title + episode-name meta once
PASS  bar: clocks format m:ss under an hour, h:mm:ss above, negatives clamp
PASS  bar: time label shows elapsed/total, or Live when duration is unknown
PASS  bar: progress permille clamps and unknowns read 0
PASS  bar: Prev/Next episode shows only for episode plays with queue neighbours
PASS  tmdb token: io:<ClassName> parses as the raw token; bare io stays io
PASS  tmdb token: http codes still parse through the colon-safe path
PASS  tmdb token: the user line keeps its human text and gains the token
PASS  iptv shape: seasons ascend, empty seasons drop, counts are deduped
PASS  iptv shape: episodes ascend + dedupe by number, first occurrence wins
PASS  iptv shape: titles fall back to Episode N when the panel has none
PASS  iptv shape: a missing season shapes empty; seasonCsv matches the shaped list
PASS  iptv shape: shaped episodes key into the same SxEy watched set as TMDB's
PASS  v26src: the player has the aspect-measuring VideoView + bar machinery
PASS  v26src: no foreground service anywhere in the player (standing rule)
PASS  v26src: the aspect mode persists per profile (player_aspect)
PASS  v26src: Details carries the IPTV episode fallback + its header note
PASS  v26src: LibraryLogic accepts the io:<ClassName> token form
PASS  v26src: tracker 1.14.5 returns io:<simple exception class name>
PASS  resolve: a proxy success stands and the direct path never runs
PASS  resolve: proxy failure + no launcher key surfaces the proxy error, direct uncalled
PASS  resolve: proxy failure + launcher key + direct success answers via direct
PASS  resolve: both paths failing surfaces the PROXY error; the direct token is kept for diagnostics
PASS  resolve: a missing proxy error degrades to io
PASS  direct: a throwing direct path degrades to its class-name token
PASS  direct: error tokens never carry the exception message (which can embed the key)
PASS  direct: http token matches the provider contract shape
PASS  tmdb line: OK via tracker / OK via launcher key
PASS  tmdb line: failure names the proxy token and the direct token
PASS  tmdb line: failure with no launcher key says so
PASS  tmdb line: composed lines never contain key material
PASS  v26src: the launcher key lives in Prefs under its own name
PASS  v26src: Settings has the masked TMDB API key row (set/not-set only)
PASS  v26src: the backup export never touches the TMDB key (redaction)
PASS  v26src: the direct client targets TMDB with the contract allowlist + class-name tokens
PASS  v26src: the direct client never logs (no Log import, no key echo)
PASS  v26src: the funnel composes proxy -> direct via resolveTmdb and records both notes
PASS  repo: exact-after-squash ('Grey's Anatomy' vs 'Greys Anatomy')
PASS  repo: catalogue suffix noise matches via contains ('The Office' vs 'The Office (US)')
PASS  repo: quality-tag noise matches via contains ('Show Name 2023 1080p')
PASS  repo: the contains tier picks the SHORTEST candidate; multiple distinct titles flag possible
PASS  repo: a year disagreement deprioritizes inside the contains tier (beats shorter)
PASS  repo: catalogue order breaks equal-length contains ties
PASS  repo: keyed matchAll == per-title matchOne under the repo core
PASS  repo: 'Dune 2021 1080p' contains-matches the yearless 'Dune'; 'Missing Show' stays unmatched
PASS  discovery: trending/all parses tv+movie, drops people + blank titles, keeps TMDB order
PASS  discovery: per-type trending forces the type when media_type is absent
PASS  discovery: a trending title flags the ? IPTV badge only when the catalogue matches
PASS  discovery: tv facts parse (next episode + genres + rating)
PASS  discovery: tv facts ? a null next_episode_to_air reads blank (no guessing from seasons)
PASS  discovery: movie facts parse (release date + genres)
PASS  soon: future-only, strictly parsed (Feb 30 rejected), sorted ascending
PASS  soon: shows carry episode coordinates, movies none
PASS  soon: chips ? today / tomorrow / in N days / the bare date; past + unparseable read blank
PASS  soon: the strict parser round-trips real dates and rejects invented ones
PASS  soon: the row caps at 20
PASS  foryou: seed weights follow the tracker (tv completion curve, movie watched flag)
PASS  foryou: seeds are the top titles by weight x rating (weight gate > 0.4)
PASS  foryou: affinity spreads each title's weight over its genres; top genres ranked
PASS  foryou: recommendation picks ? poster required, reason names the seed, score = weight*10 + rating
PASS  foryou: discover picks ? representative-title reason, affinity x2 scoring
PASS  foryou: merge dedupes, excludes tracked, sorts by score
PASS  foryou: merge cap applies
PASS  foryou: the discover path mirrors the tracker engine's params
PASS  foryou: computed picks encode in the /foryou contract shape (parseForYou reads them back)
PASS  discovery: first episode skips Specials and takes season 1's first episode
PASS  discovery: cache TTL decisions on a fake clock
PASS  discovery: diagnostics line is source + counts only
PASS  sections: v2.7 canonical order is the directive's focus order
PASS  sections: v2.7 labels
PASS  sections: a pre-v2.7 stored order keeps its order; the new rows append
PASS  v27src: the Tracker/Watch home composes the merged Up Next + Trending rows
PASS  v27src: the IPTV home carries Trending on your IPTV + its play path
PASS  v27src: discovery fetches run on the mizan-discovery worker through the funnel
PASS  v27src: diagnostics carries the discovery line
PASS  v27src: the direct client accepts discover/ (the proxy allowlist has none)
PASS  migrate: a pre-v2.7 stored order has the focus rows appended at the end
PASS  migrate: the appended rows splice into their canonical slots
PASS  migrate: idempotent ? a second pass changes nothing
PASS  migrate: an untouched canonical order is a no-op
PASS  migrate: only the still-appended rows move (comingsoon drops on decode)
PASS  migrate: a focus row parked mid-list (before foryou) is never itself moved
PASS  migrate: the user's other rows keep their relative order exactly
PASS  migrate: the hidden set drops removed keys; the splice still applies
PASS  tabs: Tracker/Watch = Home ? Library ? Apps
PASS  tabs: only Home is selected, and it is STAY
PASS  tabs: Library opens the library; Apps jumps focus to All Apps
PASS  tabs: IPTV = Home ? Live ? Movies ? Series ? Guide ? Apps
PASS  tabs: IPTV actions map Live/Movies/Series/Guide/Apps
PASS  tabs: the TV style has no tab bar; tracker+watch share one; iptv gets its own
PASS  hero: a matched title's primary is Play (v2.9 rule)
PASS  hero: an unmatched title's primary is Details ? never the tracker
PASS  startup: both timings recorded
PASS  startup: nothing recorded renders dashes, never a fake 0
PASS  startup: a zero first frame is honest; a missing full stays a dash
PASS  v28src: both media homes build the pill bar from the pure model
PASS  v28src: the landscape rail + circular All Apps builders exist and are used
PASS  v28src: the hero primary routes through the pinned decision; Open Tracker is never a hero button
PASS  v28src: cold start is staged (warm worker + deferred prepares + shell lines)
PASS  v28src: Details has no primary Open in Tracker ? it lives in the More overflow
PASS  v28src: IPTV cards carry the LIVE pill + in-frame progress + focus border
PASS  v28src: settings nav fixes are in (no selectable dead stop, index memory, focus net, startup line)
PASS  v28src: the window background is the brand splash, not a black void
PASS  v28src: DiagLog records the startup milestones
PASS  v28src: the one-time order migration is wired into the sections read
== (47) v2.9: audit fixes ? play routing, artwork fit, unified bar, scrub, discovery source ==
PASS  route: a matched card's OK press plays
PASS  route: an unmatched card's OK press opens Details (never the tracker)
PASS  route: a matched movie hero leads with ? Play
PASS  route: a matched show hero leads with ? Play S3 E7
PASS  route: an unmatched hero leads with Details ? Play is never offered
PASS  resolve: idle + press starts finding
PASS  resolve: a second press while finding is ignored (no double resolve)
PASS  resolve: finding + found hands off to the player
PASS  resolve: finding + not found fails (the caller falls back to Details)
PASS  resolve: failed + press retries the find
PASS  resolve: the button reads Finding stream? only while finding
PASS  art: landscape art in a landscape frame crops to fill
PASS  art: a portrait poster in a landscape frame renders COMPLETE, never cropped
PASS  art: a poster in its own 2:3 frame fills it
PASS  art: the details header crops a real backdrop but fits a poster complete
PASS  bar: the TV style has no unified bar (it keeps its own top bar)
PASS  bar: tracker = 3 tabs, then info + glyphs + the config's tiles as pills
PASS  bar: the watch style shares the tracker's bar shape (6 IPTV tabs on iptv)
PASS  bar: showBar off hides the whole status group but never the tabs
PASS  bar: clock+date+weather all off drops the info pill; glyphs follow their toggles
PASS  bar: the guest pill appears only for a guest profile outside safe mode
PASS  bar: safe mode drops weather/guest but keeps clock+date info
PASS  scrub: a credential URL in an error becomes ?url?
PASS  scrub: an api_key parameter value is redacted
PASS  scrub: a plain message passes through untouched
PASS  scrub: null and blank become empty; long text is capped
PASS  discovery: launcher-computed For You reads launcher
PASS  discovery: tracker For You + live launcher sets reads launcher+tracker
PASS  discovery: tracker-only reads tracker
PASS  discovery: nothing supplied still reads tracker (the fallback word)
PASS  v29src: the warm-up is display-driven (no session cap survives)
PASS  v29src: WatchPlay resolves on demand and warms what is displayed
PASS  v29src: openTracker survives ONLY as its definition + the three hold-OK menu entries
PASS  v29src: the unified bar replaced the stacked bars in both media homes
PASS  v29src: the forecast opens from the bar's info cluster in every style
PASS  v29src: landscape cards take the fit decision from core/Artwork
PASS  v29src: the settings app pickers no longer scan on the main thread
PASS  v29src: persisted error lines pass through the scrub
PASS  v29src: the details header fits a poster complete (no banner crop)
-- section 48: v3.0 crash hardening (startup health, section guards, depth caps, prefs funnel)
PASS  health: threshold is two consecutive uncleared starts
PASS  health: modeFor ? 0/1 uncleared is NORMAL, 2+ is SAFE_SHELL, negatives coerce to NORMAL
PASS  health: [die, die] -> the THIRD launch is the safe shell
PASS  health: one clean render breaks the run ? [die, clear, die, die] stays NORMAL throughout
PASS  health: the launch after two consecutive deaths is SAFE_SHELL, and its clean render clears
PASS  health: after a cleared safe-shell launch the next launch is NORMAL again
PASS  health: the notice names safe mode and points at Diagnostics
PASS  health: the diagnostics line carries the count and the shell state
PASS  guard: the error tile names the section and points at Diagnostics
PASS  guard: reporting is throttled to one per section per 60s window
PASS  fallback: tracker/watch/iptv homes fall back to the TV render; tv/unknown to the emergency root
PASS  guard: a throwing section (Error included) yields its tile; the others still render
PASS  json: the depth cap is 64
PASS  json: a 5000-deep document parses to null instead of overflowing the stack
PASS  json: nesting inside the cap parses (60 levels), past it reads as malformed (100 levels)
PASS  json: a wide shallow document (200 siblings at depth 3) parses ? depth is per-branch, not cumulative
PASS  json: malformed documents read as null (truncated, bad token, trailing garbage, empty)
PASS  stream: a small array still streams element by element
PASS  stream: a hostile deeply-nested element fails as IllegalArgumentException, not StackOverflowError
PASS  stream: malformed input throws (the ingest keeps the previous store)
PASS  fixtures: discovery parsers fail open on garbage (trending empty, facts null)
PASS  fixtures: series-episodes parser fails open on garbage
PASS  fixtures: the IPTV store index decoder rejects garbage
PASS  fixtures: the short-EPG parser fails open on garbage
PASS  v30src: Prefs reads carry no raw typed getters any more
PASS  v30src: Prefs routes its reads through PrefsSafe
PASS  v30src: PrefsSafe is the ClassCastException funnel (catch + evict + default)
PASS  v30src: the data clients read prefs through PrefsSafe too (no raw p.getString survives)
PASS  v30src: the health counter lives in its own tiny prefs file, committed synchronously
PASS  v30src: App.onCreate begins the startup AFTER the diagnostics install
PASS  v30src: MainActivity reads the shell decision and forces TV in the shell
PASS  v30src: the shell renders its notice and the TV-style row gate uses the shell gate
PASS  v30src: a completed render arms the survival clear (10s, main looper)
PASS  v30src: diagnostics carries the startup-health line
PASS  v30src: the tracker home carries the whole-home Throwable guard (was unguarded)
PASS  v30src: rebuild() itself is Throwable-guarded and renders the emergency root
PASS  v30src: failed home sections render the SectionGuard error tile in all three homes
PASS  v30src: every secondary activity's build is guarded (its noteError tag is present)
PASS  v30src: a single settings page is contained (page tag) without killing Settings
PASS  v30src: the crash handler chains to the system handler ONLY for the main thread
PASS  v30src: the watchdog's judge block is Throwable-guarded (it cannot die silently)
PASS  v30src: artwork decodes are bounded through DecodeBounds (no raw decode left)
PASS  v30src: wallpaper decodes sample from measured bounds, not a fixed factor
PASS  v30src: the weather-art render is size-capped and DecodeBounds does the two-pass decode
PASS  v30src: swapFile renames over the destination first and never deletes it first
PASS  v30src: the ingest loop's per-endpoint catch is Throwable (callbacks still drain in finally)
-- section 49: v3.0 corrupt/partial startup state (the app still opens)
PASS  startup-state: a truncated layout document still decodes (good lines survive)
PASS  startup-state: a garbled layout document decodes to a usable layout, never throws
PASS  startup-state: empty/absent layout documents decode to defaults
PASS  startup-state: a partial row order with an unknown key normalizes without throwing
PASS  startup-state: insertion rows splice after their anchors in a partial order
PASS  startup-state: the half-migrated section order migrates without losing sections
PASS  startup-state: a garbled section order composes canonically (no dupes, no unknowns)
PASS  startup-state: a truncated section order (cut mid-token) still composes
PASS  startup-state: stored home styles normalize (retired ipad -> tv, garbage -> tv)
PASS  startup-state: truncated tracker snapshot documents parse to empty, never throw
PASS  startup-state: a half-corrupt match cache keeps its good lines and skips the rest
PASS  startup-state: an absent match cache decodes empty
PASS  startup-state: empty/truncated discovery cache documents read as no data
PASS  startup-state: a truncated store index decodes to null (the store re-derives, the open survives)
PASS  startup-state: a wrong-typed store index count degrades to 0 in a valid index, never throws
PASS  v30src: a whole-home fallback holds the startup clear (degraded gate wired)
PASS  v30src: the open-path workers record Errors too (Throwable catches, finally-reset flags)
PASS  v31: movienight is not in the canonical order or the row set
PASS  v31: no section claims the Movie Night label anymore
PASS  v31: a stored order mentioning movienight loses it (order AND hidden) without breaking
PASS  v31: compose never emits movienight, even for a stored config that mentions it
PASS  v31: move/hide on the removed key are no-ops (the sort page can't resurrect it)
PASS  v31: the hero pool carries no Movie Night source (today item lands in the up-next tier)
PASS  v31: cache TTLs are trending 12h, discovery + snapshots 24h
PASS  v31: freshness is strict inside the TTL and false at/after it, for 0, and for the future
PASS  v31: age wording is no data / minutes / hours / days
PASS  v31: prune under the cap deletes nothing
PASS  v31: prune over the cap deletes oldest first and never the section being written
PASS  v31: prune keeps the written section even when it alone busts the cap
PASS  v31: the credential tripwire fires on Xtream/TMDB shapes and passes clean snapshot JSON
PASS  v31: the diagnostics line names coverage, oldest age, and size ? never content
PASS  v31: decide precedence ? loading beats failure, failure beats data, data beats empty
PASS  v31: only ERROR is retryable
PASS  v31: retry routing ? tracker, discovery, iptv, apps, unknown
PASS  v31: a section with data never reports loading, even mid-refresh
PASS  v31: loading is tracker-fetching for tracker rows, discovery-unloaded for discovery rows
PASS  v31: Play-in drops Sreeb itself and blank packages, sorts the rest by label
PASS  v31: sort-page moves reuse TrackerSections move (edges are no-ops, middle swaps)
PASS  v31: the Sort sections popover entry survives a resolves-nothing filter (internal target)
PASS  v31src: tracker home renders loading placeholders at configured positions + retry tiles
PASS  v31src: Play-in entries exist in card menus and the hero/menu paths use the shared resolvers
PASS  v31src: quick-settings routes the internal Sort sections target to Settings by category
PASS  v31src: tracker snapshots mirror to the home cache on the fetch worker
PASS  v31src: Play-in discovers handlers via the intent query ? no hardcoded app packages
PASS  v31src: the manifest declares the video/* queries intent (else the picker is always empty)
PASS  v31src: the Sort sections page + its dialog + the category deep-link exist
PASS  v31src: discovery Retry forces one TTL-ignoring worker run
PASS  v31src: the version bump is 3.3 / code 26
PASS  hero51: missed first (most overdue first), then up-next (today, then unknown)
PASS  hero51: future episodes are never hero material
PASS  hero51: missed labels carry Missed + coords + date chip; up-next labels carry Up next + coords
PASS  hero51: a schedule pool never falls back to Continue/For You
PASS  hero51: an all-future schedule falls back to Continue (backdrop-first) + For You (backdrop-gated)
PASS  hero51: an empty library yields an empty pool (never a fabricated hero)
PASS  hero51: a blank today disables schedule shaping (fallback, never a crash)
PASS  hero51: no title shares an episode with Coming Soon (ties go to Coming Soon; other episodes keep their hero)
PASS  hero51: date fill fills matching-coord blanks, fills coords when absent, never overwrites
PASS  hero51src: the home builds its hero from filled schedule dates + Coming Soon exclusion
PASS  hero51src: the discovery worker publishes last-known TMDB facts for the hero fill
PASS  refresh52: open/resume/profile-switch never auto-refresh
PASS  refresh52: manual/retry/first-run-seed are the only allowed triggers
PASS  refresh52: tracker seeds only when nothing is stored
PASS  refresh52: discovery fetches only on force or a missing cache file (TTL never fetches)
PASS  refresh52: weather fetches only from the visible-home tick while stale
PASS  refresh52: accent recomputes only when the wallpaper changed
PASS  stages52: compact name=ms rendering in record order
PASS  stages52: re-record replaces the earlier same-name stage
PASS  stages52: list caps at 32, oldest dropped
PASS  refresh52src: onResume carries no refresh kicks any more
PASS  refresh52src: tracker home refreshes only as first-run seeding
PASS  refresh52src: explicit Refresh home data exists (popover target + handler)
PASS  refresh52src: discovery worker fetches only on force-or-empty-cache
PASS  refresh52src: home build never parses snapshots (memo-only read + worker kick)
PASS  refresh52src: Home screen row options lost Move up/down (sort only in Sort sections)
PASS  refresh52src: diagnostics gains last-refresh + startup-stages lines
PASS  refresh52src: hero schedule rule untouched (missed first, today stays in Coming Soon)
-- section 53: v3.3 lock auto-submit + safe-mode exit
PASS  auto53: stored length 4 submits exactly at 4, never before or after
PASS  auto53: stored length 5 submits exactly at 5, never before or after
PASS  auto53: stored length 6 submits exactly at 6, never before or after
PASS  auto53: stored length 7 submits exactly at 7, never before or after
PASS  auto53: stored length 8 submits exactly at 8, never before or after
PASS  auto53: invalid stored length 0 never auto-submits
PASS  auto53: invalid stored length 1 never auto-submits
PASS  auto53: invalid stored length 3 never auto-submits
PASS  auto53: invalid stored length 9 never auto-submits
PASS  auto53: invalid stored length 12 never auto-submits
PASS  auto53: partial unlock entry (3 of 4) does not auto-submit
PASS  auto53: complete unlock entry (4 of 4) auto-submits with its encoding
PASS  auto53: set flow keeps OK-driven phases (no stored length, no auto-submit)
PASS  clear53: normal completed render waits the full 10s survival
PASS  clear53: safe-shell completed render clears immediately (0ms)
PASS  clear53: degraded build holds the clear in both modes
PASS  clear53: [die, die] -> safe shell, and its clean render clears immediately
PASS  clear53: after the cleared safe launch the next launch is NORMAL
PASS  clear53: user-exit or immediate clear never disables re-arming ? two more deaths re-arm safe mode
PASS  clear53: degraded safe render holds the clear (counter stays)
PASS  clear53: notice names safe mode, Diagnostics, and the exit action
PASS  auto53src: unlock dialog auto-submits at the stored length
PASS  auto53src: set flow passes no auto-submit length (OK-driven phases kept)
PASS  safe53src: Diagnostics gains the exit action, gated on the armed flag
PASS  safe53src: style label + picker disclose the safe-mode TV lock
PASS  safe53src: MainActivity re-reads the armed flag on rebuild and uses the clear-delay rule
PASS  safe53src: store offers a user-initiated exit that resets counter + flag
-- section 53b: v3.3 merged Up Next (ask 47)
PASS  merge53: same episode appears once (Caught Up deduped to its Up Next card)
PASS  merge53: different episodes of one show both stay (Behind S1E5 + Soon S1E8)
PASS  merge53: tier order is missed (oldest first) -> watchable (today, then dateless) -> future (date asc)
PASS  merge53: missed tier is most-overdue first (Oct 01 before Oct 20 before Oct 23)
PASS  merge53: Soon-only today item leads the future tier (date asc)
PASS  merge53: future Up Next item with no Soon twin is kept, in the future tier
PASS  merge53: Soon movies never dedupe against shows and land in the future tier
PASS  merge53: coord-less pair keeps both cards (sameness unproven)
PASS  merge53: registry has no Coming Soon key anywhere
PASS  merge53: stored order + hidden set mentioning comingsoon drop it on read
PASS  merge53: compose never emits comingsoon
PASS  merge53: hero still excludes future items and same-episode Soon twins
PASS  merge53src: home composes the merged section, not two sections
PASS  merge53src: Coming Soon const is gone from the registry
PASS  merge53src: merged retry refreshes both sources
RESULT: 957 passed, 0 failed, 957 total
```
