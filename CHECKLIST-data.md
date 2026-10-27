# Phase 2B — Data-layer checklist (data worker)

Scope: ONLY `app/src/main/java/com/mizan/launcher/data/` + this file.
Checked = implemented in the new data layer (file named). Unchecked =
not implemented by this worker, with the reason. UI-owned, app-owned
and res/manifest items are intentionally left to their owners — they
are not data-layer gaps, they are out of scope here. No file outside
data/ was edited by this worker, and the reference tree
`~/workspace/mizan-launcher` was never modified.

Compile proof (clean, sequential, each kotlinc at `-J-Xmx512m`):
core 64 files → 352 classes; +domain 14 files → 428 classes;
+data 24 files → 517 classes (89 data classes), total 119 s, exit 0.
Layer check: `tools/check-cycles.py` PASS (125 files, no cycles,
no upward imports); grep confirms zero ui/app references in data/.

## Implemented (data layer)

### Storage / prefs (§4)
- [x] SharedPreferences `mizan_launcher`, every read via PrefsSafe
      funnel (wrong-typed value evicts + reads default) — PrefsSafe.kt, PrefsStore.kt
- [x] Global keys incl. `current_profile` (coerced 1–2), `arrow_pin`
      (legacy `pin` removed on init; legacy numeric arrow_pin discarded),
      `lock_settings` (unset ⇒ pattern-set means locked), `safe_mode`,
      `guest_profile`, `guest_lock_settings`, `last_unlock_at`,
      `speedup_last_result`, `launcher_tmdb_key`, IPTV keys, weather
      lat/lon `39.1653`/`-86.5264`, weather cache keys, `library_imported`,
      `last_boot_at`, `own_foreground_at`, `watcher_relaunches`,
      `watcher_last_at`, `reddit_attempt_log` (last 12) — PrefsStore.kt
- [x] Per-profile `p<profile>.` keys: theme, wall_kind/value, icon_shape,
      app_size, columns (3–10, default 6), home_style, player_aspect,
      tracker_marked, pending_overlay, foryou_overlay, tracker_sections
      (+ `tracker_sections_migrated_28` one-time v2.8 splice migration),
      iptv_favorites/resume/locked_cats/unlock_stamps, artwork, layout
      (incl. `status=` line via LayoutCodec), `locked` string-set,
      reddit_subs/nsfw/rotation(+last/index), bing cache, recents,
      sticky_status_bar, unlock_grace_min, profile_name — PrefsStore.kt
- [x] `filesDir/tracker/`: titles.jsonl, upnext/continue/movies/foryou/
      missed.txt, index.json, episodes/tv_<id>.json, missed_cache/
      tv_<id>.txt (`season|episode|date`); tmp+rename atomic writes;
      bounded fail-open reads — TrackerStoreDisk.kt
- [x] `filesDir/iptv/`: live/vod/series/epg.jsonl, index.json,
      series_info/; atomic swap only when every attempted endpoint
      succeeded — IptvStoreDisk.kt, IptvRepositoryImpl.kt
- [x] `filesDir/home_cache/` + `tracker_slim.json` (≤512 KB, v2,
      credential-tripwired) — BackgroundUnits.kt (HomeCacheDisk)
- [x] `filesDir/discovery/` trending cache, 12 h TTL — DiscoveryWorker.kt
- [x] `filesDir/artwork/<pkg>.png`, downsampled byte-bounded decodes — TvAndArtwork.kt
- [x] `filesDir/reddit_wallpapers/`, `filesDir/bing_wallpapers/` (per-day
      cache) — WallpaperFetchers.kt
- [x] `filesDir/crash.log`, `anr.log`, breadcrumbs file — DiagnosticsRepositoryImpl.kt
- [x] JSON depth cap honoured by reusing core/JsonMini-based codecs
      (TrackerStore/IptvLogic parsers); no new unbounded parser added
- [x] Settings export shape excluding marked set, For You overlay,
      pending overlay, IPTV favorites/resume, TMDB key, IPTV credentials;
      import rejects non-Sreeb files, type-tolerant — PrefsStore.kt
      (exportSettingsJson/importSettingsJson; UI owns the file picker)

### Tracker (§5, §1.1b, §1.4, §1.5 data parts)
- [x] Local-first tracker store, no provider queries after the
      `library_imported` flag (provider path closed by design) — TrackerRepositoryImpl.kt
- [x] Shows, movies, episodes (season air-date cache + Up Next fallback),
      watch progress, plan-to-watch, ignored/muted, per-show pending
      scoping (pendingWatchedFor per tmdbId, never folded across shows) — TrackerRepositoryImpl.kt
- [x] Up Next with countdown labels (core TrackerLogic), Missed
      (stored missed.txt filtered by missedRow + per-show watched, plus
      recompute from season air dates), Continue, For You (tracked +
      overlay-dismissed dropped), Library effective views — TrackerRepositoryImpl.kt, MissedWorker.kt
- [x] Optimistic writes via pending overlay (newest toggle wins,
      part-by-part retirement via core prune) — PrefsStore.kt, TrackerRepositoryImpl.kt
- [x] f26a5f6 AiredGate: season marking filters through AiredGate
      first and writes nothing if none aired; season counts count only
      aired+unwatched (remainingAiredCount semantics in UseCasesImpl +
      TrackerRepositoryImpl.markSeasonWatched); blank/malformed = aired,
      today counts — TrackerRepositoryImpl.kt, UseCasesImpl.kt
      (UI affordances — no Play glyph, toast text — belong to UI worker)
- [x] Hero pool order Missed (most overdue first) → Up Next →
      Continue (backdrop-first) → For You — UseCasesImpl.kt
- [x] Missed worker recompute per show / all shows, kicked on local
      changes — MissedWorker.kt
- [x] Tracker refresh worker: local-only, single-flight — BackgroundUnits.kt

### TMDB (§5)
- [x] Proxy-first / launcher-key-direct-second structure; direct path
      `https://api.themoviedb.org/3/` allowlisted paths, key never logged,
      exception messages never carried (token kinds only), both-fail
      surfaces the proxy error — TmdbRepositoryImpl.kt
- [x] Image sizes poster w342 / backdrop w780 via domain TmdbImages
      (reused, not re-implemented) — TmdbRepositoryImpl.kt
- [x] Search (tv+movie only, persons dropped by type), details
      (seasons sorted, empty dropped by core parse), season episodes
      ascending — TmdbRepositoryImpl.kt
- [x] IPTV series-info fallback shaping (seasons ascend, empty dropped,
      episodes ascend + dedupe by number via core) — IptvRepositoryImpl.kt
      (getSeriesEpisodes) + core LibraryLogic.iptvSeasonInfos/iptvEpisodes

### IPTV (§5, §1.7, §1.8 data parts)
- [x] Xtream Codes API: player_api.php, live/VOD/series URL shapes,
      timeshift shape, auth/category parse, tv_archive catch-up flag — IptvRepositoryImpl.kt
- [x] M3U parse (quote-aware, ungrouped → Playlist), streamed via
      core parse; sources NOT merged/de-duplicated — IptvRepositoryImpl.kt
- [x] XMLTV guide streamed parse, guide-window retention, short-EPG
      per channel on demand — IptvRepositoryImpl.kt
- [x] Favorites (identity keys, survive source clears), resume
      (finished = near end or ≥95%; unknown duration not finished;
      hidden below 5 s threshold), category locks (explicit + adult
      names, unlock stamps + grace/session rules) — IptvRepositoryImpl.kt
- [x] Search across live/VOD/series, hidden categories excluded, 200 cap — IptvRepositoryImpl.kt
- [x] PlayItem construction for channel/VOD/episode; refresh on
      request only, never on home build — IptvRepositoryImpl.kt
- [x] Watch matching: squash normalization, exact then contains tier,
      year tie-break, ambiguity → first flagged possible, missing
      S/E unresolved fail-open; match cache fingerprint stores ids+flags
      only — WatchMatchEngine.kt
- [x] Card play: matched → IPTV; unmatched → Details (null);
      matched show without library twin → S1 E1, no mark token;
      S1 E1 missing → null (fail open to Details) — WatchMatchEngine.kt
- [x] Trending titles join the visible-first match set (v3.9) — WatchMatchEngine.kt

### Apps / folders / recents / most-used (§1.10, §1.1a)
- [x] PackageManager leanback + launcher intents, dedupe (leanback
      label wins), system flagged, self excluded, label-then-package
      sort — AppsRepositoryImpl.kt
- [x] Favorites order persisted; favorites may live in folders — AppsRepositoryImpl.kt
- [x] Recents MRU newest-first capped at 8, launcher never recorded,
      relaunch dedupes to front — AppsRepositoryImpl.kt
- [x] Most Used from usage-stats ACTIVITY_RESUMED counts (grant-gated;
      empty when ungranted, never invented), count desc / recency /
      name ordering via core — UsageStatsCollector.kt, AppsRepositoryImpl.kt
- [x] Folders depth-3 rules, cannot-contain-self / ancestor rules via
      core FolderTree.canNest, delete rehomes sub-folders + keeps apps,
      folder locks round-trip — AppsRepositoryImpl.kt
- [x] Hidden apps excluded from All Apps + Apps row exactly once;
      removing from folder restores — AppsRepositoryImpl.kt
- [x] App search: substring, prefix before substring, order preserved — UseCasesImpl.kt

### Weather (§1.11, §3.6, §5)
- [x] Open-Meteo current + 24 h (from current hour, wraps midnight) +
      7-day, default 39.1653/-86.5264 Bloomington IN, user-changed
      locations from prefs, Fahrenheit default / Celsius, cached +
      ~3 h staleness, fail open to cache — WeatherRepositoryImpl.kt
- [x] Condition groups clear/partly/cloudy/fog/rain/storm/snow
      (motif mapped), unknown → cloudy — WeatherRepositoryImpl.kt

### Settings (§3, §7)
- [x] Theme Apple Black default; wallpaper kinds; home style TV/WATCH
      only with legacy ipad→TV, tracker/iptv→WATCH via core/HomeStyle — SettingsRepositoryImpl.kt
- [x] Icon shape rounded, app size standard, columns 6 (3–10),
      player aspect fit — SettingsRepositoryImpl.kt, PrefsStore.kt
- [x] Status-bar defaults (shown; clock/date/weather/network/bluetooth
      on; quick tiles on; Wi-Fi tile off, others on; 12 h; no seconds;
      weekday_month_day; medium; transparent; solid) from layout
      status= line — SettingsRepositoryImpl.kt
- [x] Sticky status bar off; unlock grace Every time (0) / 5/15/30;
      Guest lock on; safe mode off; profiles 1–2 + Guest (0=none);
      profile switch clears grace stamp — SettingsRepositoryImpl.kt, PrefsStore.kt
- [x] Reddit subs default, NSFW off, rotation off/15m/1h/6h/daily — PrefsStore.kt, WallpaperFetchers.kt
- [x] IPTV account normalization (http:// added, trailing slash
      removed via core IptvLogic.account), M3U/EPG URLs, clear sources
      keeps favorites+resume — PrefsStore.kt, SettingsRepositoryImpl.kt
- [x] TV row order + Watch section order, hero hide/show-only
      (domain↔core id mapping: apps↔allapps, trending_tv↔trendingtv) — SettingsRepositoryImpl.kt
- [x] Lock Settings legacy rule (unset ⇒ pattern-set means locked) — LockRepositoryImpl.kt

### Playback / Now Playing / Locks / Diagnostics / Background (§1.9, §2, §8.2 contracts)
- [x] Resume save/clear, aspect Fit→Stretch→Zoom→Fit (per profile),
      speed ladder cycles/wraps (2x→0.5x, nearest-rung snap via core) — PlaybackRepositoryImpl.kt
- [x] Auto-mark: once, ended (even at low position) or ≥90% threshold
      (89% does not fire), never twice, never tokenless, never partial
      — PlaybackRepositoryImpl.kt (see note on "exactly 90%" below)
- [x] Now Playing: media-session row, buffering reads as playing,
      dedupe by package first-wins, system-ui + blank filtered,
      play/pause only when action mask allows, listener-grant probe — NowPlayingRepositoryImpl.kt
- [x] Arrow-pattern gate: UDLRU codec via core, legacy numeric discarded,
      grace exact boundary, future stamp never grants, profile switch
      re-asks, Settings lock, app locks — LockRepositoryImpl.kt
- [x] Diagnostics: crash/anr logs, breadcrumb ring persisted capped,
      bounded event log, startup milestones, death evidence, watchdog
      staleness, sanitized copy block (counts/state only) — DiagnosticsRepositoryImpl.kt
- [x] Accessibility gateway contracts: GlobalActionsGateway stub
      (fail-open when disconnected; app layer registers the live
      delegate), HomeWatcherProbe (watcher/default-home/current-home/
      relaunch count), stock launcher list — DiagnosticsRepositoryImpl.kt, HomeStatusProbe.kt
- [x] Cold chain runner (sequential, cancellable, single-flight) — BackgroundUnits.kt
- [x] Discovery worker (12 h TTL, disk cache, single rebuild on change) — DiscoveryWorker.kt
- [x] IPTV ingest worker shape (refresh() in repo; atomic swap rule) — IptvRepositoryImpl.kt
- [x] Startup-health machine (2 uncleared → safe shell on 3rd; clean
      render + 10 s survival clears; first-build fallback holds clear) — BackgroundUnits.kt
- [x] Speed Up (KILL_BACKGROUND_PROCESSES; never never-list/system/
      foreground/self; free RAM before/after + count) — BackgroundUnits.kt
- [x] Reddit/Bing fetchers + rotation due/next logic — WallpaperFetchers.kt
- [x] TV listings (READ_TV_LISTINGS, fail-open empty) + tuner channels
      (state-only diagnostics) — TvAndArtwork.kt
- [x] Home/watcher/service probes via string component names (no
      app-layer imports; TANGLE-4 resolved) — HomeStatusProbe.kt
- [x] Artwork cache + bounded decode; dp math owned locally (TANGLE-1 resolved) — TvAndArtwork.kt
- [x] Use cases: UpNext/Missed/Continue/Hero/ForYou/Trending/PlayTitle/
      MarkEpisode (AiredGate)/SearchTitles/NowPlaying/Forecast/
      Diagnostics/ManageFolders/RefreshDiscovery — UseCasesImpl.kt, WatchMatchEngine.kt
- [x] DataFactory wiring helper for the integration worker — UseCasesImpl.kt

## Not implemented by this worker (with reason)

- [ ] Any Activity, dialog, picker, card, status-bar view, hero
      rotation timer, move-mode UI, quick-settings popover, screensaver
      view — UI worker owns ui/; data returns models only.
- [ ] LauncherService / WatcherService / NowPlayingListener /
      MizanDreamService / BootReceiver / MainActivity / App wiring —
      app worker owns app/; data supplies only the gateway/probe
      contracts and the string-name probes above.
- [ ] Live TMDB tracker-proxy endpoint — the proxy was the tracker
      app's bridge, closed post-v3.7; the direct launcher-key path
      carries real traffic and the proxy error is surfaced on
      both-fail per MAP §5. Integration worker must confirm whether
      a live proxy URL still exists; none is derivable from source.
- [ ] Full TMDB episode lists for a show inside TrackerRepository
      alone — TrackerRepository.getSeasonEpisodes serves the local
      season air-date cache (+ Up Next fallback); full lists come
      from TmdbRepository.getSeasonEpisodes. Integration worker
      should wire Details to prefer TMDB and cache into missed_cache/.
- [ ] The v3.9 shell-frame "memo source" diagnostics line (store/slim/
      prefs/none) — that line is composed by MainActivity (app worker)
      from the store/slim reads this layer provides (TrackerStoreDisk
      + HomeCacheDisk.readSlim).
- [ ] Reddit/Bing image rendering, dominant-colour accent extraction,
      weather-background palettes as bitmaps — data supplies URLs/
      condition groups; rendering is UI/core (WeatherLogic palettes
      already in core).
- [ ] SIM harness re-pinning (sim/SimMain.kt + TEST_LOG) — out of
      data scope; core is byte-identical so expectations should carry.
- [ ] On-device verification (onn. box, Moto, real IPTV panel, real
      TMDB key, tuner/PiP behaviour) — cannot be done on this VM;
      MAP §11 runtime items remain open for device testing.

## Compatibility decisions (exact, not invented)

- All prefs keys, file names, and line codecs above reuse the old
  formats via core codecs (TrackerStore, IptvStore, LayoutCodec,
  LibraryLogic pending codec, IptvLogic fav/resume codecs,
  IptvLocks codecs, TrackerSections codec) so a v3.9 upgrade keeps
  tracked shows, settings, and state.
- Locked apps: old per-profile string-set key `pN.locked`
  (PrefsSafe.stringSet), NOT a new key — preserved exactly.
- `foryou_overlay` format could not be determined from source
  (old code referenced it opaquely): this layer stores newline
  `type|id` lines and reads them fail-open. Old overlay values, if
  in a different shape, will read as empty (dismissed picks may
  reappear once) — documented, not silently assumed.
- Season air-date cache: `missed_cache/tv_<id>.txt` lines
  `season|episode|date` as in old TrackerStoreDisk.
- Auto-mark "exactly 90%": old code + core use the ≥90% threshold
  (WatchMatch.shouldAutoMark / watchedThrough: pos*10 >= dur*9);
  the domain/task wording is read as that threshold (89% does not
  fire). A millisecond-exact equality test would almost never fire.
- IptvRepository.getLiveCategories() returns id→name pairs with no
  source field (domain gap, see below): M3U category ids are
  prefixed `m3u:` in that list and in getLiveChannels() to avoid
  Xtream/M3U numeric-id collisions.
- Most Used: old tree counted ACTIVITY_RESUMED events; this layer
  counts them via UsageStatsManager.queryEvents (not foreground
  time), grant-gated, empty when ungranted.
- Weather staleness: MAP says "3 h-ish" via RefreshPolicy; this
  layer uses exactly 3 h (RefreshPolicy.weatherFetchAllowed is a
  predicate on a caller-supplied stale flag, not a duration).

## Domain-interface gaps (for the integration worker, exact)

1. IptvRepository.getLiveCategories(): returns Pair<id, name> with
   no source — Xtream vs M3U categories sharing a numeric id
   collide. Needed: a source-qualified category id (or a
   Category model carrying source). Workaround in place: `m3u:`
   prefix (see above).
2. IptvRepository.setFavorite()/setResume() take identity-key
   strings only, so a favorite/resume written through them cannot
   carry name/icon/category metadata. Needed: full-entry overloads
   (provided as extra data-layer methods setFavoritesFull /
   setResumeFull on IptvRepositoryImpl; UI should prefer those).
3. TrackerRepository.addToLibrary() takes only (tmdbId, kind) —
   no title/poster — so the pending add entry carries empty
   title/poster until enriched. Needed: an add overload carrying
   title/poster, or integration-worker enrichment from TMDB
   before/after the call.
4. TmdbRepository has no proxy endpoint/URL parameter: the proxy
   path cannot be wired without a URL the domain does not carry.
   Needed: either a proxy base URL in SettingsRepository or a
   TmdbRepository constructor parameter (integration decision).
5. PlaybackRepository.maybeAutoMark() fires but has no mark
   application: the token→episode/movie mapping lives with the
   caller. Provided: PlaybackRepositoryImpl.onAutoMark callback
   for the UI worker to apply via TrackerRepository.
6. NowPlayingRepository.isListenerGranted() needs the listener
   component: the app layer must set
   NowPlayingRepositoryImpl.listenerComponent (or the fallback
   package-prefix check is used).
7. GlobalActionsGateway has no live implementation in data/ by
   design (service lives in app/): the app layer must register a
   delegate on GlobalActionsGatewayStub, else perform() fails open.
8. Domain UseCases are plain (non-fun) interfaces, so they cannot
   be built with SAM lambdas — implementations use object
   expressions (UseCasesImpl.kt). No domain change needed, just
   noted for UI callers expecting lambdas.
9. SettingsRepository has no field for the v2.8 tracker-sections
   migration flag or the Make-Home checklist state — both live in
   PrefsStore (trackerSectionsMigrated28, HomeStatusProbe) and are
   not exposed on the domain interface.
