# Phase 2C UI-layer checklist (ui worker)

Spec: MAP.md (298 items). This file tracks UI-owned items only; data/app
items belong to their workers. UI code lives under
`app/src/main/java/com/mizan/launcher/ui/` and is coded against domain/
interfaces + core/ ONLY (no data/ or app/ imports — verified by grep).
Wiring: `UiWiring.kt` — the app layer sets repositories/use-cases there;
unset repositories render empty/error states, never crash.

## Screens / components delivered

- [x] Home components: hero (shell-frame, left scrim), hero rotation
  (HeroRotator, timer/pauses on focus/resume delay/Next resets/<2 never
  rotate), portrait 2:3 Up Next/Missed cards, Watch tabs Home·Library·
  Apps·Live (Live LAST), unified pill tab bar, safe shell, Watch hint —
  `HomeUi.kt`, `PosterCards.kt`
- [x] TV home rows composition (favorites/recents/mostused/folders/
  nowplaying/continue/inputs/livetv/apps, row cap 20) — `HomeUi.kt`
  (generic rows; TV-listings/inputs/tuner data have no domain repo — see gaps)
- [x] Card hold-OK menus: Play, Play in…, Mark next watched, movie
  watched toggle, Details, For You Dismiss — `HomeUi.kt`
- [x] All Apps grid (A–Z, label ties by package, foldered badged, hidden
  excluded) + hold-OK menu (Add to folder…, Hide, App info, Uninstall
  non-system only, Lock/Unlock, Artwork) + arrow-lock-gated launch —
  `AllAppsActivity.kt`
- [x] Folders view depth-3 (breadcrumb, grid, rename/lock/delete with
  rehome-to-root, locked-folder pattern prompt / set-one-if-none) —
  `FolderViewActivity.kt`
- [x] Search: D-pad keyboard, substring/prefix-first ordering, empty = all,
  voice search, apps + tracker titles, arrow-lock-gated launch —
  `SearchActivity.kt`, `KeyboardDialog.kt`
- [x] Library: merged grid, in-progress → Up Next → A–Z → finished last,
  progress line/fraction, caught-up, hold-OK menu (Details, Mark next,
  movie toggle, Mute/Unmute, Remove with confirmation) —
  `LibraryActivity.kt`
- [x] Details: header (title/poster/year/overview, movie ✓ Watched /
  Not watched), TMDB proxy-first/direct-second via TmdbRepository,
  season picker (default = first season with an unwatched episode),
  episode checklist, movie actions (Play on IPTV, Mark watched/unwatched,
  Add to my list, Mute/Unmute, Remove with confirmation, Retry) —
  `DetailsActivity.kt`
- [x] f26a5f6 unaired gate EXACT via domain AiredGate (blank = aired,
  malformed = aired, today = aired): unaired = no ▶ glyph; unaired+
  unwatched click/long-press = ONLY toast "Hasn't aired yet — airs
  <date>"; unaired+already-marked long-press = ONLY "↩ Mark unwatched";
  aired long-press = ▶ Play + ✓/↩; season button counts only
  unwatched+aired (AiredGate.remainingAiredCount, hidden at 0); season
  marking goes through the aired filter — `DetailsActivity.kt`
- [x] TMDB search: D-pad keyboard, tv+movie, tap opens Details,
  already-added flagged, Add retires For You entry on tracking —
  `TmdbSearchActivity.kt`
- [x] IPTV browser: tabs Live·Movies·Series·Favorites·Search, category
  chips (empty skipped, favorites first, locks applied), channel cards
  with EPG now-title + progress, grid cap 200 + "load more", VOD cards
  (container ext, rating text, resume, finished = near end or ≥95%),
  series detail (seasons numeric, episode fields, resume), favorites
  toggle, search across catalogues paged at 200, hold-OK menus,
  pattern-locked categories, adult-named categories start locked when
  a pattern is set, guarded rebuilds, no fetch on open/resume —
  `IptvActivity.kt`
- [x] IPTV guide: channel rows now/next + progress at current clock,
  paged at 200, hidden/locked excluded, tap plays, catch-up offered for
  tv_archive — `IptvGuideActivity.kt`
- [x] Player UI: PlayItem playback, PiP attempt on Home, control bar
  (title composed once, m:ss / h:mm:ss, Live when unknown, permille),
  play/pause, ±30s seek, Prev/Next (queue neighbours only — see gap),
  aspect Fit→Stretch→Zoom→Fit, speed ladder cycles/wraps (2x→0.5x,
  off-ladder snaps, labels trim zeros), audio/subtitle cycle rules,
  resume (saved on pause/destroy, restored, hidden <10s, cleared at
  ≥95%), auto-mark once on ended (even low position) or exactly 90%
  (not 89%), never tokenless, never twice, no foreground service,
  error screen never silent black — `PlayerActivity.kt`
  (MediaPlayer used: the manual build has no media3 dependency; the
  control-bar/auto-mark/resume contracts are player-backend agnostic)
- [x] Forecast: next 24h from current hour + 7-day outlook, units follow
  Weather setting (°F/mph or °C/km/h), precip only when present —
  `ForecastActivity.kt`
- [x] Settings, all 10 categories in order, two-column Roku-style
  (SettingsNavMachine: Right enters detail, Right again no-op,
  Left/Back returns to list, Back in list exits, selection clamps,
  focus memory per category via selected category), crash-contained
  panes, Settings gate (pattern when Settings lock on, or Guest +
  Guest-lock on; no pattern fails open), version from PackageManager,
  export/import (mizan-launcher-settings.json, excludes marked-set,
  For You/pending overlays, IPTV favorites/resume, TMDB key, IPTV
  credentials; non-Sreeb file rejected with the exact wording),
  Copy diagnostics, safe mode toggle + exit-safe-mode row —
  `SettingsActivity.kt`
- [x] Settings defaults presented from domain defaults (MAP §7):
  theme Apple Black, home style TV, columns 6, weather Bloomington IN
  °F, unlock grace "Every time", Guest lock on, safe mode off,
  status-bar defaults (bar on, sticky off, clock/date/weather/
  network/bluetooth on, quick tiles on, Wi-Fi tile off, others on,
  12h, no seconds, weekday_month_day, medium, transparent, solid) —
  `SettingsActivity.kt`
- [x] Shared: arrow-PIN dialog (4–8 steps, arrow glyphs, confirm with
  mismatch restart, too-short, 9th rejected as full, backspace-on-empty
  cancels, UDLRU codec via core ArrowPin) — `ArrowPinDialog.kt`;
  pattern gate with grace — `PatternGate.kt`; artwork picker (12
  core presets + URL, indices coerced, garbage empty) —
  `ArtworkPicker.kt`; D-pad keyboard + masked text dialogs with MAP
  length caps — `KeyboardDialog.kt`; iPhone-style switches —
  `SwitchRow.kt`; Play in… (self excluded, unresolvable filtered,
  honest "no app") — `PlayIn.kt`; quick-settings popover + status bar
  — `QuickSettingsView.kt`; guarded sections, focus states, dp/tile
  helpers — `Ui.kt`
- [x] No XML layouts added (programmatic Views, reusing res/ themes +
  drawables); strings.xml extended only (5 new strings, none removed)

## Not implemented / partial (with reason)

- [ ] Hold-and-drop move mode (arrows move, OK drops, Back cancels,
  exact snapshot restored on cancel) for Favorites / All Apps order /
  folder contents — reason: no domain model of grid positions or a
  move-session API; AppsRepository.moveEntry moves one entry to a
  folder, but not within-order repositioning. Needs a domain
  move-session contract from the integration worker.
- [ ] Details IPTV series-info fallback shaping (seasons ascend, empty
  dropped, episodes ascend + dedupe, "Episode N" fallback titles,
  header note "▶ This show is on your IPTV — hold OK on an episode to
  play it.") — reason: no domain method maps a TMDB show to its IPTV
  series-info; IptvRepository.getSeriesEpisodes needs a series id the
  UI cannot derive. Closest fit: TMDB episodes via Tracker/Tmdb
  repositories; the note is shown only as a placeholder line when a
  season is empty. Needs a domain `iptvSeriesForShow(tmdbId)` contract.
- [ ] Episode ▶ glyph restricted to IPTV-matched shows — reason: no
  domain matched-flag on EpisodeRef; UI shows ▶ for every aired
  episode and the play path re-checks the match (unmatched → toast
  "Not matched on IPTV"). Needs a matched flag or a
  `playEpisode(show, season, episode)` domain method.
- [ ] Player Prev/Next episode queue neighbours — reason:
  PlaybackRepository has no queue; PlayItem carries no neighbours.
  Button honestly reports "No queue neighbour". Needs a PlayQueue
  provider in the domain layer.
- [ ] Player audio/subtitle track cycling UI — reason: MediaPlayer
  track info is not exposed through any domain repository, and the
  core PlayerLogic track helpers take a TrackDesc list the UI cannot
  obtain without a player-backend API. Cycle rules are implemented in
  core/PlayerLogic and documented in PlayerActivity; wiring needs a
  domain track-list contract.
- [ ] TV rows sourced from system data (Continue Watching via
  READ_TV_LISTINGS, Inputs HDMI row, Live TV tuner row, Now Playing
  toggle action) — reason: no domain repository for TV listings /
  inputs / tuner; NowPlayingRepository covers sessions only.
  HomeUi.buildTvHome accepts generic rows so the app layer can supply
  them when a contract exists.
- [ ] Settings: weather location write, Guest-profile designation write,
  Guest-lock write — reason: SettingsRepository exposes GlobalSettings
  read-only (no setGlobal / setWeatherLocation / setGuestProfile).
  Reads and all profile-scoped writes (via setProfile) work; these
  three global writes need a domain setter. UI shows the current
  values and the location row captures input with an honest note.
- [ ] Settings: "Make Sreeb your home screen" exact checklist text +
  ADB command line — reason: MAP §11 runtime-only item; UI opens the
  system home settings and states the watcher explanation, but the
  exact step text/ADB line must be verified on the onn. box.
- [ ] Screensaver (MizanDreamService), accessibility services,
  BootReceiver, MainActivity staged shell / cold chain / safe shell
  trigger — reason: app-layer ownership (foundation manifest places
  them under .app.*); HomeUi.safeShell + HomeUi.buildWatchHome are
  the hostable compositions the app layer embeds.
- [ ] Real poster/backdrop image loading — reason: no image-loader
  dependency in the manual build and no domain image API; poster URLs
  are computed via TmdbImages (w342/w780) and exposed by
  PosterCards.posterUrl for the app layer's loader; cards render
  complete (never centre-cropped) with FIT_CENTER placeholders.
- [ ] Wallpaper rendering (theme/gradient/solid/picture/Reddit/Bing/
  weather backgrounds, dynamic accent from wallpaper) — reason:
  wallpaper fetchers live in the data layer; UI applies theme presets
  and gradient/solid names through ProfileSettings and exposes the
  scrim treatment in HomeUi.heroView. The app layer owns backdrop
  composition.
- [ ] Speed Up execution — reason: KILL_BACKGROUND_PROCESSES +
  never-list lives in core/SpeedUp but needs an app-layer executor;
  UiWiring.onSpeedUp is the hook, and the Settings row reports
  honestly when it is unset.

## Compile proof

- Command: `cd ~/workspace/mizan-rebuild && bash tools/build.sh units`
  (each kotlinc unit at `-J-Xmx512m`, kotlinc 1.9.24, android-34).
- UI unit result: `== kotlinc unit ui (22 files, -Xmx512m) ==` →
  `== unit ui: OK in 50s ==`, with ZERO errors mentioning `ui/` in the
  log (verified by grepping the full build output; the script's "OK"
  label alone is not trusted — the first run proved it prints OK even
  when a unit has errors, and the first ui run's errors were all fixed
  and confirmed absent in this run).
- UI output: 374 `.class` files under
  `out/classes/com/mizan/launcher/ui/` from 22 source files (~2,200 lines).
- Full-run context: core 64 files OK, domain 14 files OK, app skeleton
  OK in the same run. The data unit in that run carried errors in
  data-worker files only (BackgroundUnits, TrackerStoreDisk,
  UsageStatsCollector, UseCasesImpl, WallpaperFetchers) — not ui-owned,
  not touched, reported to the parent for the data worker.
- Layer check: `python3 tools/check-cycles.py` → PASS (125 files,
  111 import edges, no cycles, no upward imports); grep confirms zero
  `data/` or `app/` imports from ui/.
