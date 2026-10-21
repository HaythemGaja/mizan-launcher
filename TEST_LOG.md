# Mizan Launcher — headless test log

Command: `sh sim/run_sim.sh` (kotlinc -> JVM sim over core/ pure logic)
Date: 2026-10-21 14:43 UTC

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
PASS  tileswitch: defaults all on
PASS  tileswitch: codec round-trip with tiles off
PASS  tileswitch: sound independently off, others on
PASS  tileswitch: v1.2 status strings decode to all tiles on
PASS  tileswitch: tiles survive the layout codec
PASS  tileswitch: garbage tiles segment fails open to on
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
PASS  rows: v1.3 order gains recents after favorites
PASS  rows: normalize is idempotent
PASS  rows: custom order preserved, insertions follow their anchors
PASS  rows: empty order gains both rows at the end
PASS  statusbar: search tile flag round-trips
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
RESULT: 321 passed, 0 failed, 321 total
```
