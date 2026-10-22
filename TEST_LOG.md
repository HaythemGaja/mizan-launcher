# Sreeb (Mizan Launcher) — headless test log

Command: `sh sim/run_sim.sh` (kotlinc -> JVM sim over core/ pure logic)
Date: 2026-10-22 04:14 UTC

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
PASS  homestyle: ipad survives the codec
PASS  homestyle: value list is tv then ipad (v1.8: tracker appended)
PASS  homestyle: label rule ? tv needs focus, ipad always shows
PASS  homestyle: ipad forces squircle, tv keeps the preference
PASS  homestyle: grid apps exclude hidden and foldered (any depth), order kept
PASS  homestyle: grid folders are the ROOTS in row order
PASS  homestyle: grid inputs appended when enabled and present
PASS  homestyle: grid apps keep the caller's order
PASS  homestyle: grid inputs empty when the row is disabled
PASS  homestyle: grid inputs empty when the device reports none
PASS  homestyle: root reorder permutes only the root slots
PASS  homestyle: root reorder keeps nesting intact
PASS  homestyle: root reorder with the current order is a no-op
PASS  homestyle: dock caps favorites at 10, in order
PASS  homestyle: dock divider sits after the favorites
PASS  homestyle: dock fixed buttons follow, profile + settings last
PASS  homestyle: dock drops search/all-apps when their switches are off
PASS  homestyle: dock with no favorites has no divider
PASS  homestyle: tile boost enlarges width and icon
PASS  homestyle: default boost leaves metrics unchanged
PASS  tracker: codec ? tracker survives, garbage still decodes to tv
PASS  tracker: tracker style keeps tv label + shape rules
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
PASS  quicksettings: six system targets then Sreeb Settings, actions distinct
PASS  quicksettings: unresolvable targets are filtered, Sreeb Settings stays
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
PASS  tmdb: diagnostics wording
PASS  poster cache: codec round-trips, garbage dropped
PASS  movie night: numeric-string tmdbId still parses
PASS  hero pool: order is Up Next, Continue, Movie Night, For You (backdrop-first in section)
PASS  hero pool: every candidate carries its exact section name as source
PASS  hero pool: poster-only For You pick stays out; a known backdrop lets it in
PASS  hero pool: movie hero uses the movie path (type movie, release date, no fake backdrop)
PASS  hero pool: two-source overload keeps the v1.8 order and tags
RESULT: 501 passed, 0 failed, 501 total
```
