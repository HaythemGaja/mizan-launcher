# Mizan Launcher (display name "Sreeb") — Phase 1 Feature Map

> **Purpose.** A complete, checkable inventory of the current app, written so a
> from-zero rewrite team can verify **zero feature loss**. Every inventory item
> is a checklist line (`- [ ]`) with the old-tree file(s) that implement it.
> Phase 2 checks items off against new files; do not delete a line — check it
> or record why it intentionally changed.
>
> **Reference tree (READ-ONLY, never modified by this phase):**
> `~/workspace/mizan-launcher` — package `com.mizan.launcher`, display name
> `Sreeb`, app version **3.9** (versionCode 32), `compileSdk`/`targetSdk` 34,
> `minSdk` 26, Views-only Kotlin (no Compose, no media3, no ads, no analytics,
> no accounts), Kotlin stdlib 1.9.24 as the only dependency.
> File count: **121 Kotlin files, ~39,800 lines in `app/`** (64 `core/` +
> 30 `data/` + 19 `ui/` + 7 root-package files + 1 `sim/SimMain.kt`; the
> ~46,500-line figure in the task brief includes the sim/test harness).
> Headless logic tests: `sim/run_sim.sh`, results in the reference tree's
> `TEST_LOG.md` (dated 2026-10-25).
>
> **Standing rules carried from the old tree (verify, don't drop silently):**
> no foreground services anywhere; optional features fail open and silently;
> every stored-value read goes through the type-tolerant prefs funnel (a
> wrong-typed stored value evicts itself and reads as its default); section
> renders are crash-contained (a throwing section yields an error tile, never
> a dead home); credentials (IPTV account, M3U/EPG URLs, TMDB key) live only
> in SharedPreferences — never in backups, diagnostics, logs, or files the app
> produces; diagnostics lines carry counts/state only, never titles, ids, or
> key material. **No keystore material or secret value is reproduced here.**

## 0. How to read this map

- Paths are relative to `app/src/main/java/com/mizan/launcher/` unless stated
  otherwise (`res/` = `app/src/main/res/`).
- "Old file" = where the behaviour lives today, not where Phase 2 must put it.
- Items marked **(v3.9)** / **(f26a5f6)** are the newest behaviours; §8 and §9
  pin them in exact detail.
- Anything that could not be determined from source alone is in
  §11 "Unresolved / needs runtime check" — never inferred.

---

## 1. Screens / Activities

### 1.1 Home — MainActivity (the launcher itself, `HOME`/`LEANBACK_LAUNCHER`)

- [ ] Home is the default-home Activity: `singleTask`, `clearTaskOnLaunch`,
      `excludeFromRecents`, `stateNotNeeded` — `MainActivity.kt`, `AndroidManifest.xml`
- [ ] First frame is a staged shell (frame, status bar, tab bar, section frames
      from in-memory/prefs-light state only, no heavy parse), then the warmed
      home completes it; frame probes record stage 1 (first frame) and stage 2
      (home complete) — `MainActivity.kt` (`buildRoot`, `attachFrameProbe`,
      `onStartupRenderCompleted`)
- [ ] Cold chain: after first frame + 10 s survival, heavy work runs
      sequentially (slim/store memo, full memo parse, discovery hydration);
      navigation-cancelled chains restart the clock — `MainActivity.kt`
      (`scheduleStartupClear`), `core/ColdChain.kt`, `data/ColdChainRunner.kt`
- [ ] Safe shell / safe mode: after two consecutive uncleared starts the third
      launch renders the safe shell (plain frame, Favorites + Apps rows only, no
      wallpaper work, notice tile that opens Settings); safe mode is the same
      composition — `MainActivity.kt` (`buildEmergencyRoot`, `safeShellNotice`),
      `core/StartupHealth.kt`, `data/StartupHealthStore.kt`
- [ ] Emergency root: if even the shell composition throws, a bare root with an
      "open Settings" action renders instead of a black screen — `MainActivity.kt`
- [ ] Status bar (TV style): clock, date, weather, network/Bluetooth glyphs,
      Wi-Fi SSID label, quick tiles, profile tile, Settings gear, corner ⚙
      fallback when the bar's Settings tile is off; sticky-bar option pins it
      while scrolling — `MainActivity.kt`, `core/StatusBar.kt`, `core/WifiLabel.kt`
- [ ] Wallpaper behind home: theme background, gradient, solid colour, user's
      own picture, Reddit picture, Bing daily picture, or live weather
      background (palette follows current condition) — `MainActivity.kt`,
      `data/Weather.kt`, `data/WeatherArt.kt`, `data/RedditFetcher.kt`,
      `data/BingFetcher.kt`, `data/WallpaperRotator.kt`, `core/WeatherLogic.kt`
- [ ] Dynamic accent: dominant colour extracted from the wallpaper drives the
      accent when the Dynamic theme is selected — `MainActivity.kt`
      (`resolveColours`), `data/AccentUtil.kt`, `core/DominantColor.kt`
- [ ] Guest pill in the status bar while a Guest profile is active —
      `MainActivity.kt`, `core/GuestGate.kt`
- [ ] Per-section Loading / Ready / Empty / Error states with per-section Retry
      routing; section failures are throttled to one report per section per
      minute and never take down neighbouring sections — `MainActivity.kt`
      (`sectionErrorTile`, `sectionLoadingTile`, `retrySection`),
      `core/SectionModule.kt`, `core/SectionGuard.kt`
- [ ] No auto-refresh on open: home data refreshes only via "Refresh home data"
      or a per-section Retry (both force); first-build probe excepted —
      `MainActivity.kt` (`refreshHomeData`), `core/RefreshPolicy.kt`
- [ ] Hero rotation: hero advances on a timer, pauses while focus is held,
      resumes after a delay, Next advances immediately and resets the timer,
      fewer than 2 candidates never rotate — `core/HeroRotator.kt`
- [ ] App tiles: icon shape (rounded/circle/square), app size
      (compact/standard/large) scaling all tiles equally, grid columns 3–10,
      label visible only when focused — `core/TileLayout.kt`, `core/Design.kt`
- [ ] Hold-OK (long-press) app menu: Move (hold-and-drop move mode: arrows move,
      OK drops, Back cancels, exact snapshot restored on cancel), Add to
      folder…, Hide app, App info (system), Uninstall (system, non-system apps
      only), Lock/Unlock app — `MainActivity.kt`, `core/MoveSession.kt`,
      `core/GridMove.kt`
- [ ] Hold-OK folder menu: Rename folder, Lock/Unlock folder, Move into
      folder…, Delete folder (sub-folders rehomed to root, apps kept) —
      `MainActivity.kt`, `core/LayoutStore.kt` (`FolderTree`)
- [ ] Move mode works for Favorites, All Apps order, and folder contents
      (including sub-folder entries by entry key) — `core/MoveSession.kt`
- [ ] "Play in…" picker (last menu entry on matched cards): play the title in
      another installed video app; the launcher itself is excluded, unresolvable
      targets filtered, "no app" reported honestly — `ui/PlayIn.kt`,
      `core/PlayInLogic.kt`, `MainActivity.kt`
- [ ] Quick-settings popover: Wi-Fi, Bluetooth, Display, Sound, Storage,
      About device (system intents, filtered if unresolvable), plus internal
      "Sort sections", "Refresh home data", "Sreeb Settings" — `core/QuickSettings.kt`
- [ ] Remote Search key opens app Search; status-bar Search tile does the same —
      `MainActivity.kt`, `ui/SearchActivity.kt`
- [ ] Home hint: one-time "add your IPTV account" hint on Watch, dismissible,
      shown only while no IPTV source is configured at all — `MainActivity.kt`,
      `data/Prefs.kt` (`watchHintDismissed`)

#### 1.1a TV home rows (home style "tv"; order + visibility user-configurable)

- [ ] Favorites row (favorites order persisted, favorites may also live in
      folders) — `MainActivity.kt`, `core/LayoutStore.kt`
- [ ] Recently Used row (MRU, newest first, capped at 8, launcher itself never
      recorded, relaunch dedupes to front) — `core/Recents.kt`, `data/Prefs.kt`
- [ ] Most Used row (count desc, recency breaks count ties, name breaks
      recency ties; hidden/non-launchable excluded, zero counts dropped) —
      `core/MostUsed.kt`, `data/MostUsed.kt`
- [ ] Folders row (root folders; nested folders up to 3 levels deep, see §1.10) —
      `MainActivity.kt`, `core/LayoutStore.kt`
- [ ] Now Playing row (other apps' media sessions: title, artist, state,
      play/pause control when the session's action mask allows; buffering reads
      as playing; system-ui and blank titles filtered; deduped by package, first
      session wins) — `MainActivity.kt`, `core/NowPlaying.kt`,
      `data/NowPlayingData.kt`, `NowPlayingListener.kt`
- [ ] Continue Watching row (system TV listings via `READ_TV_LISTINGS`,
      best-effort; row hides without the permission/data) — `MainActivity.kt`,
      `data/TvData.kt`
- [ ] Inputs row (HDMI inputs) — `MainActivity.kt`
- [ ] Live TV row (device tuner channel list when the system allows it; on many
      TVs the permission is reserved, the row stays hidden, diagnostics says why) —
      `MainActivity.kt`, `core/LiveTv.kt`, `data/LiveTv.kt`
- [ ] Apps row (all apps minus hidden and foldered apps, in stored order) —
      `MainActivity.kt`, `core/AppCatalogue.kt`, `core/AllApps.kt`
- [ ] TV row order default: favorites, recents, mostused, folders, nowplaying,
      continue, inputs, livetv, apps; new rows splice in after their anchors, never
      appended blindly — `core/LayoutStore.kt` (`RowOrder`)

#### 1.1b Watch home sections (home style "watch"; order + visibility user-configurable)

- [ ] Unified pill tab bar (v2.9): ONE bar — tab pills left, status group right
      (info pill = clock · date · weather, OK opens Forecast; Guest pill; glyph
      pill; action pills All Apps / Search / Wi-Fi / Profile / Settings); hiding
      the status bar hides only the status group, never the tabs —
      `core/HomeTabs.kt` (`UnifiedBar`), `MainActivity.kt`
- [ ] Watch tabs, in order: **Home · Library · Apps · Live** (Live LAST) —
      `core/HomeTabs.kt` (`watchTabs`)
- [ ] Hero section: big featured title over a full-bleed backdrop with left
      scrim; hero pool order = Missed (most overdue first) → Up Next →
      Continue (backdrop-first) → For You; backdrop presence never reorders
      tiers — `MainActivity.kt` (`addTrackerBackground`), `core/TrackerLogic.kt`
      (`hero pool`), `core/HeroRotator.kt`
- [ ] Up Next section: next episode per tracked show with countdown label
      (today / tomorrow / "in N d" / "Aired" / far-future date itself; real
      day math across month/year boundaries; empty/malformed dates give no
      label), airing-today count, pill text composing only the numbers the data
      supports — `core/TrackerLogic.kt`, `MainActivity.kt`
- [ ] Missed section: episodes whose air date passed unwatched; filtered by the
      local marked-set and the pending overlay **scoped per show** (v3.9 fix:
      a pending mark for show A no longer filters show B's same SxEy) —
      `core/TrackerLogic.kt` (`missedRow`), `MainActivity.kt`
- [ ] Continue section: in-progress shows/movies with progress count/total/next
      coordinates and poster; caught-up shows detected from the continue data —
      `core/LibraryLogic.kt`, `MainActivity.kt`
- [ ] Trending TV + Trending Movies sections (launcher-computed from TMDB,
      tracker snapshot as fallback; see §5) — `data/DiscoveryData.kt`,
      `core/Discovery.kt`, `MainActivity.kt`
- [ ] Coming Soon section (merged into Up Next presentation in v3.3 lineage;
      Soon items also feed the Up Next fallback when the memo is absent — see
      §11) — `core/Discovery.kt`, `MainActivity.kt`
- [ ] For You section: launcher-computed picks (or tracker snapshot picks when
      not computed); already-tracked titles dropped; overlay-dismissed picks
      dropped; row capped; rating text one decimal, absent when zero; "why"
      genres shown as names — `core/TrackerLogic.kt` (`foryou`), `core/Discovery.kt`
- [ ] Favorites / Folders / All apps sections inside Watch, gated by the same TV
      layout visibility flags — `MainActivity.kt`
- [ ] Watch section default order: Hero, Up Next, Missed, Continue, Trending TV,
      Trending Movies, For You, Favorites, Folders, All apps; hero is always
      first when shown and is hide/show-only (never reorderable) —
      `core/TrackerSections.kt` (`CANONICAL`, `ROWS`)
- [ ] Card play behaviour: matched card ▶ plays via IPTV; unmatched card opens
      in-launcher Details (never the tracker app); matched Trending show
      without a library twin plays from S1 E1 with no library mark token (v3.9);
      matched movie plays via its VOD entry — `MainActivity.kt`
      (`playUpNextItem`, `playMovieItem`, `playHeroPrimary`), `data/WatchPlay.kt`
- [ ] Card hold-OK menus: Play, Play in…, Mark watched/unwatched, (movies:
      Mark watched toggle), For You: Add / Dismiss — `MainActivity.kt`,
      `data/TrackerActions.kt`
- [ ] Hero "Open Tracker" button does NOT exist (deleted v2.9); hero primary
      action is Play — `MainActivity.kt`
- [ ] **v3.9 shell-frame rule (see §9):** a recreated activity (destroyed while
      the user was in Settings, or Home-pressed back) with a warm memo prepares
      tracker data on its first build; only a genuinely memo-less build defers
      to the warm workers. A memo-less shell frame (frame + tabs, no hero, no
      Missed, no Continue, no Library entry row) is a diagnosable state, not a
      normal state — `MainActivity.kt`, `data/TrackerClient.kt`
      (`noteHomeBuild` diagnostics line: memo source, snap counts, hero/
      missed-row counts after filtering, each section's shown-state, coldStart)

### 1.2 All Apps — `ui/AllAppsActivity.kt`

- [ ] Full-screen All Apps grid: every app (foldered ones included, badged with
      their containing folder), alphabetical by label, label ties break by
      package; hidden apps excluded — `ui/AllAppsActivity.kt`, `core/AllApps.kt`
- [ ] Hold-OK app menu: Add to folder… (breadcrumb-labelled folder picker),
      Hide app, App info, Uninstall; launch gated by the arrow lock —
      `ui/AllAppsActivity.kt`
- [ ] Folder creation from All Apps (prompt for name; locked-folder notice
      explains the pattern is set on first open) — `ui/AllAppsActivity.kt`
- [ ] Order edits persist via the shared layout store and refresh home —
      `ui/AllAppsActivity.kt` (`saveLayoutAndRefresh`)

### 1.3 App Search — `ui/SearchActivity.kt`

- [ ] D-pad on-screen keyboard (letters, digits, space, backspace, clear);
      substring match case-insensitive, prefix matches sort before substring
      matches, order preserved inside groups; empty query returns all in order —
      `ui/SearchActivity.kt`, `core/AppSearch.kt`
- [ ] Voice search via system speech recognizer (result feeds the same query
      pipeline) — `ui/SearchActivity.kt`
- [ ] Results mix installed apps AND tracker titles (tracker hits show watched
      episode counts, open Details) — `ui/SearchActivity.kt`,
      `core/TrackerLogic.kt` (`tracker search`)
- [ ] Result hold-OK menu + arrow-lock-gated launch, same as home —
      `ui/SearchActivity.kt`

### 1.4 Library (My Library) — `ui/LibraryActivity.kt`

- [ ] Single merged grid of tracked shows + movies; sort = in-progress first
      (continue order, approximating recently-progressed — the payload has no
      timestamps), then Up Next, then A–Z, finished last — `ui/LibraryActivity.kt`,
      `core/LibraryLogic.kt`
- [ ] Each card: poster (poster cache fills posters the snapshots lack),
      progress line + fraction (count/total/next from continue data), caught-up
      detection — `ui/LibraryActivity.kt`, `core/LibraryLogic.kt`
- [ ] Header buttons (refresh / open TMDB search — verify exact set at runtime,
      see §11) and missing-poster backfill — `ui/LibraryActivity.kt`
- [ ] Hold-OK item menu: Details; Mark next episode watched (shows) /
      Mark watched / Mark unwatched (movies); Mute / Unmute notifications;
      Remove from library… (confirmation) — `ui/LibraryActivity.kt`,
      `data/TrackerActions.kt`
- [ ] Optimistic overlay: library writes render instantly from the pending
      overlay and retire part-by-part as snapshots converge (newest toggle wins;
      add-then-remove settles whole once snapshots are fresher) —
      `core/LibraryLogic.kt` (`decodePending`, `prunePending`)

### 1.5 Details — `ui/DetailsActivity.kt`

- [ ] Header: title, poster/backdrop, year, overview (snapshot overview when
      TMDB details fail), meta line incl. "✓ Watched" / "Not watched" for movies —
      `ui/DetailsActivity.kt`
- [ ] TMDB details load via the tracker proxy first, launcher TMDB key direct
      second (see §5); details parse: fields, year, seasons sorted, empty season
      dropped; season episodes parse ascending — `ui/DetailsActivity.kt`,
      `core/LibraryLogic.kt`, `data/TrackerClient.kt`, `data/TmdbDirect.kt`
- [ ] Movie actions: ▶ Play on IPTV (when matched), ✓ Mark watched /
      ↩ Mark unwatched, ＋ Add to my list (untracked titles only),
      🔇 Mute / 🔔 Unmute notifications, Remove from library… (confirmation),
      Retry (on load failure) — `ui/DetailsActivity.kt`
- [ ] Show season picker: default season = first season with an unwatched
      episode (verify exact rule at runtime, see §11); season block with
      episode checklist — `ui/DetailsActivity.kt`
- [ ] Episode checklist row: ✓ box + E<n> + name + air date; OK toggles
      watched (gated by the unaired rule, §8); ▶ glyph only when the show is
      IPTV-matched AND the episode has aired — `ui/DetailsActivity.kt`
- [ ] Episode hold-OK menu: ▶ Play S<s> E<e> + ✓/↩ Mark watched (aired only;
      unaired variants in §8) — `ui/DetailsActivity.kt`
- [ ] "✓ Mark season watched" button: counts only unwatched episodes that have
      aired (see §8); hidden when zero remain — `ui/DetailsActivity.kt`,
      `data/TrackerActions.kt` (`markSeason`)
- [ ] IPTV episode fallback: when TMDB season data is missing, season/episode
      lists fill from the IPTV series-info (shaped: seasons ascend, empty
      seasons drop, episodes ascend + dedupe by number, missing titles fall
      back to "Episode N"); header note "▶ This show is on your IPTV — hold OK
      on an episode to play it." — `ui/DetailsActivity.kt`,
      `core/IptvLogic.kt` (series-info shaping)
- [ ] "Retry episodes" on season-load failure — `ui/DetailsActivity.kt`

### 1.6 TMDB Search (add titles) — `ui/TmdbSearchActivity.kt`

- [ ] D-pad keyboard search over TMDB (tv + movie only; persons and bad rows
      dropped; fails open to empty) via the tracker proxy, launcher-key direct
      as fallback — `ui/TmdbSearchActivity.kt`, `core/LibraryLogic.kt`
      (`tmdb search parse`), `data/TrackerClient.kt`
- [ ] Result tap opens Details; already-added titles flagged via the refreshed
      added-keys set — `ui/TmdbSearchActivity.kt`
- [ ] Add from search retires the For You overlay entry once the title is
      tracked (or leaves the snapshot) — `core/TrackerLogic.kt`

### 1.7 IPTV browser — `ui/IptvActivity.kt`

- [ ] Tabs: 📺 Live · 🎬 Movies · 📚 Series · ★ Favorites · 🔍 Search
      (Guide lives in its own Activity, §1.8) — `ui/IptvActivity.kt`
- [ ] Live tab: category chips (empty categories skipped, favorites first,
      locks applied), channel cards with EPG now-title + progress, grid page
      cap 200 per category (total preserved, "load more" paging) —
      `ui/IptvActivity.kt`, `ui/IptvCards.kt`, `core/IptvLogic.kt`
- [ ] Movies tab: VOD cards (container extension, numeric rating as text),
      resume positions, "finished" = near end or ≥95% (unknown duration is not
      finished) — `ui/IptvActivity.kt`, `core/IptvLogic.kt`
- [ ] Series tab: series cards → series detail (seasons sorted numerically,
      ghost episodes skipped, episode fields ext/duration/plot) →
      episode rows with resume — `ui/IptvActivity.kt`, `core/IptvLogic.kt`
- [ ] Favorites tab: favorites across live/VOD/series, toggle add/remove by
      identity key, codec round-trips hostile names — `ui/IptvActivity.kt`,
      `core/IptvLogic.kt`
- [ ] Search tab: case-insensitive search across all three catalogues,
      hidden categories excluded, results paged at 200 — `ui/IptvActivity.kt`
- [ ] Hold-OK menus: channel menu (Play, Catch-up when `tv_archive`, Favorite,
      …), VOD menu (Play, Resume/Clear resume, Favorite), series menu,
      episode menu, favorite menu, category menu (incl. pattern-lock a category
      from here) — `ui/IptvActivity.kt`
- [ ] Category locks: pattern-locked categories ask for the arrow pattern;
      adult-named categories (adult, xxx, +18, 18+, case-insensitive) start
      locked when a pattern is set; unlock stamps with grace/session rules —
      `core/IptvLocks.kt`, `ui/IptvActivity.kt`
- [ ] Empty states per kind (unconfigured / auth / empty / error) with
      diagnostics-safe wording; ingest progress tick while refreshing —
      `ui/IptvActivity.kt`, `core/IptvStatus.kt`
- [ ] Browser rebuilds are guarded (a hostile catalogue/EPG fails open to the
      safe shell/empty state, never a blackout) and read store slices, never
      fetching on open/resume — `ui/IptvActivity.kt`, `data/IptvClient.kt`

### 1.8 IPTV Guide — `ui/IptvGuideActivity.kt`

- [ ] Channel rows with now/next programmes + progress fraction at the current
      clock; timezone offsets convert to the same absolute instant; entities
      unescaped, CDATA unwrapped, broken spans skipped — `ui/IptvGuideActivity.kt`,
      `core/IptvEpg.kt`
- [ ] Short-EPG: base64 titles, listing times read as UTC, fetched per channel
      on demand — `ui/IptvGuideActivity.kt`, `core/IptvEpg.kt`
- [ ] Guide rebuild guarded + paged like the browser; refresh tick; hidden/locked
      categories excluded — `ui/IptvGuideActivity.kt`
- [ ] Tapping a channel plays it; catch-up items use the timeshift URL shape —
      `ui/IptvGuideActivity.kt`, `core/IptvLogic.kt`

### 1.9 Player — `ui/PlayerActivity.kt`

- [ ] Plays Live, VOD, and Series episodes from a `PlayItem` (kind + ids +
      URL + resume + queue neighbours); PiP declared — Home during playback
      tries PiP, devices that refuse fall back to normal behaviour —
      `ui/PlayerActivity.kt`, `data/IptvClient.kt` (`PlayItem` data class +
      `PlayQueue` object, at the end of that file)
- [ ] Control bar: title line (title + episode-name meta composed once), clock
      m:ss under an hour / h:mm:ss above (negatives clamp), elapsed/total or
      "Live" when duration unknown, progress permille (unknowns read 0) —
      `ui/PlayerActivity.kt`, `core/PlayerLogic.kt`
- [ ] Transport: play/pause toggle, ±seek, Prev/Next episode (episode plays with
      queue neighbours only) — `ui/PlayerActivity.kt`, `core/PlayerLogic.kt`
- [ ] Aspect cycle: Fit → Stretch → Zoom → Fit (letterbox / exact / cover-crop;
      portrait video fits by height, zooms by width; unknown size falls back to
      container); mode persists per profile — `ui/PlayerActivity.kt`,
      `core/PlayerLogic.kt`, `data/Prefs.kt` (`playerAspect`)
- [ ] Speed ladder cycles and wraps (2x → 0.5x); off-ladder values snap to the
      nearest rung before advancing; labels trim trailing zeros —
      `core/PlayerLogic.kt`, `ui/PlayerActivity.kt`
- [ ] Audio track cycle (audio only, index order; labels prefer language else
      1-based ordinal; button needs >1 audio track) and subtitle cycle
      (Off → tracks → Off; timed-text + subtitle only; button needs ≥1 text
      track) — `core/PlayerLogic.kt`, `ui/PlayerActivity.kt`
- [ ] Resume: position saved on pause/destroy, restored on reopen, hidden
      below threshold, cleared when finished (near end / ≥95%) —
      `ui/PlayerActivity.kt`, `core/IptvLogic.kt` (resume codec)
- [ ] Watch auto-mark: fires once on playback ended (even at low position) or
      at exactly 90% (not 89%); never twice, never tokenless, never on a partial
      watch — `ui/PlayerActivity.kt` (`maybeAutoMark`), `core/WatchMatch.kt`
- [ ] Catch-up: catch-up item offered for `tv_archive` channels —
      `ui/PlayerActivity.kt`, `MainActivity.kt`
- [ ] "Play in…" / Open externally: hand the stream URL to another installed
      video app (self excluded, no hardcoded packages) — `ui/PlayerActivity.kt`,
      `ui/PlayIn.kt`, `core/PlayInLogic.kt`
- [ ] Error screen on playback failure (never a silent black frame) —
      `ui/PlayerActivity.kt` (`showError`)
- [ ] No foreground service anywhere in the player (standing rule) —
      `ui/PlayerActivity.kt`

### 1.10 Folders (in-home, not a separate Activity)

- [ ] Folders hold apps AND other folders, max depth 3; typed entries with
      stable ids; legacy positional lines migrate with sequential ids —
      `core/LayoutStore.kt` (`FolderTree`, `Folder`, `FolderEntry`)
- [ ] Folder open view: breadcrumb (root → folder), grid of entries,
      sub-folder entry moves like apps, parent order persisted with the move —
      `MainActivity.kt`, `core/MoveSession.kt`
- [ ] Folder rules enforced: a folder cannot contain itself; an ancestor cannot
      move under its descendant; a height-3 subtree fits nowhere deeper;
      deleting a folder rehomes sub-folders to root and keeps apps —
      `core/LayoutStore.kt`
- [ ] Folder locks: locked flag round-trips; locked folder with a pattern
      prompts for it; locked folder without a pattern prompts to set one (never
      locked out) — `core/LayoutStore.kt`, `ui/PatternGate.kt`,
      `ui/ArrowPinDialog.kt`
- [ ] Dedupe: apps in folders (any depth) are excluded from the All Apps row
      and the Apps row exactly once; removing an app from its folder restores it;
      favorites unaffected (a favorite may live in a folder) —
      `core/LayoutStore.kt`, `core/AllApps.kt`

### 1.11 Forecast / weather — `ui/ForecastActivity.kt`

- [ ] Full forecast: next 24 hours starting at the current hour (wraps past
      midnight; 12h labels wrap, 24h labels plain) + 7-day outlook (weekday +
      date, condition, hi/lo, precip only when present) — `ui/ForecastActivity.kt`,
      `core/Forecast.kt`, `data/Weather.kt`
- [ ] Opened by OK on the clock / date / weather in the home bar; units follow
      the Weather setting (°F/mph or °C/km/h) — `ui/ForecastActivity.kt`,
      `MainActivity.kt`
- [ ] Condition groups: clear/partly/cloudy/fog/rain/storm/snow day+night
      variants; unknown code falls back to cloudy; choice prefers current,
      falls back to forecast-dominant, then worst — `core/WeatherLogic.kt`

### 1.12 Settings — `ui/SettingsActivity.kt` (two-column Roku-style)

- [ ] Two-column layout: left category list, right detail pane; Right enters
      detail, Right again is a no-op, Left/Back returns to list, Back in list
      exits (caller exits); category change swaps the detail model; selection
      clamps (no wrap); focus memory per category (row title + index) —
      `ui/SettingsActivity.kt`, `core/SettingsNav.kt`, `core/SettingsFit.kt`
- [ ] Compact layout at 1280×720 / 960×540 / narrow portrait (smaller type and
      padding, panes stack); left pane 30% clamped to 260–360dp at 1920×1080;
      bottom padding ≥ one row height at every size — `core/SettingsFit.kt`
- [ ] Detail panes are crash-contained: a failing page shows "This settings page
      hit a snag — the error is noted in Diagnostics" instead of crashing
      Settings — `ui/SettingsActivity.kt`
- [ ] Categories (in order): Home & launcher · Look & wallpaper · Home screen ·
      Sort sections · Status bar · Weather · Lock & profiles · Now playing ·
      IPTV · Data & about — `ui/SettingsActivity.kt` (see §3 for every row)
- [ ] Settings gate: opening Settings asks for the arrow pattern when Settings
      lock is on, or when Guest is active and Guest-lock is on; already-unlocked
      this entry allows; no pattern set fails open — `ui/SettingsActivity.kt`,
      `core/SettingsGate.kt`, `core/UnlockGrace.kt`
- [ ] "Make Sreeb your home screen" checklist replaces the Home category detail
      until completed/dismissed (exact steps incl. the ADB command line for
      setting the default home — see §11 for the exact step text) —
      `ui/SettingsActivity.kt` (`buildMakeHome`), `data/HomeStatus.kt`
- [ ] Version label is read from PackageManager at runtime (never a hardcoded
      "Sreeb v<digit>") — `ui/SettingsActivity.kt` (`appVersionLabel`)

### 1.13 Gates, dialogs, pickers (shared UI)

- [ ] Arrow-pattern dialog: D-pad sequence 4–8 steps, glyphs are arrows not
      digits; set requires confirm (mismatch restarts, then takes a fresh build);
      submit below minimum is "too short"; 9th append rejected as full;
      backspace on empty cancels; encoded form is UDLRU; legacy numeric PINs
      decode empty (migration discard, never reinterpreted) — `ui/ArrowPinDialog.kt`,
      `core/ArrowPin.kt`
- [ ] Pattern gate wrapper used by locked apps, locked folders, Settings, and
      leaving Guest (with unlock-grace stamp honoured) — `ui/PatternGate.kt`,
      `core/SettingsGate.kt`, `core/UnlockGrace.kt`
- [ ] Artwork picker: per-app custom artwork — 12 presets (names + colours,
      indices coerced) or an image URL; codec round-trips presets and escaped
      URLs; garbage decodes empty; setting/removing per package —
      `ui/ArtworkPicker.kt`, `core/Artwork.kt`, `core/ArtworkStore.kt`,
      `data/ArtworkImages.kt`
- [ ] D-pad keyboard dialog: text entry for IPTV server/username/password
      (masked), M3U/EPG URLs, TMDB key (masked), subreddit list, weather
      location, profile names, folder names; length caps per field (IPTV server
      120, username 60, password 60, M3U/EPG 300, TMDB key 80) —
      `ui/KeyboardDialog.kt`, `ui/SettingsActivity.kt`
- [ ] iPhone-style on/off switches: on = green track + knob right, off = grey
      track + knob left, toggle flips both ways — `ui/SwitchRow.kt`
      (`SwitchPillView`, `SwitchRowView`)
- [ ] Generic helpers: dp conversion, tile backgrounds, focus handling —
      `ui/Ui.kt`

### 1.14 Screensaver — `MizanDreamService.kt`

- [ ] Dim clock (64sp, 75% alpha) + date line over the theme gradient dimmed to
      half brightness (OLED burn-in protection); clock ticks every 30 s;
      non-interactive, fullscreen — `MizanDreamService.kt`

### 1.15 Removed / retired surfaces (do NOT rebuild)

- [ ] iPad home style: removed v2.2; a saved "ipad" decodes to TV —
      `core/HomeStyle.kt`
- [ ] Tracker-only home style: removed v3.8; a saved "tracker" decodes to Watch —
      `core/HomeStyle.kt`
- [ ] IPTV home style: removed v3.9; a saved "iptv" decodes to Watch; the IPTV
      browser itself is NOT deleted (it lives under Watch's Live tab) —
      `core/HomeStyle.kt`, `core/HomeTabs.kt`
- [ ] Movie Night section: removed v3.1 (registry/defaults/composition/hero pool/
      stored-order drop) — `core/TrackerSections.kt`
- [ ] Cleanup feature + cleanup classifier: removed v1.5 (never-list data survives
      as `core/NeverList.kt`, also used by Speed Up) — `core/NeverList.kt`
- [ ] Numeric PIN pad: retired v1.2, replaced by the arrow PIN (see §1.13) —
      `core/ArrowPin.kt`, `data/Prefs.kt` (init migration)

---

## 2. Services, receivers, background components

- [ ] **LauncherService — "Sreeb launcher" accessibility service (f26a5f6, §8):**
      performs system global actions on request; off by default; see §8 for the
      exact capability/limit list — `LauncherService.kt`, `res/xml/launcher_config.xml`,
      `AndroidManifest.xml`
- [ ] **WatcherService — "Sreeb launcher watcher" accessibility service:**
      passive; watches for the stock TV launcher window and reopens Sreeb;
      debounce max 1 relaunch / 2 s; loop guard (Sreeb foreground within the
      last 1 s → skip, checked both in-memory and via `own_foreground_at` pref);
      screen-on / unlock recovery (SCREEN_ON / USER_PRESENT → if Sreeb is the
      default home, nudge it front); stock launchers watched =
      `com.google.android.apps.tv.launcherx`, `com.google.android.tvlauncher`,
      `com.google.android.leanbacklauncher`; every relaunch counted in
      diagnostics (count + last time); window-state events only,
      `canRetrieveWindowContent=false`, sends nothing anywhere; off by default —
      `WatcherService.kt`, `res/xml/watcher_config.xml`, `AndroidManifest.xml`
- [ ] **NowPlayingListener — notification listener service:** passive, exists
      only to authorize MediaSessionManager session queries; both notification
      callbacks intentionally empty; reads NO notification content; NOT a
      foreground service; grant via system Notification Listener settings —
      `NowPlayingListener.kt`, `AndroidManifest.xml`
- [ ] **MizanDreamService — screensaver:** see §1.14 — `MizanDreamService.kt`,
      `AndroidManifest.xml`
- [ ] **BootReceiver:** on BOOT_COMPLETED only: warms the app-list cache,
      records `last_boot_at`, and (best-effort, fails open) brings Sreeb front
      if it is the default home; starts NO service — `BootReceiver.kt`,
      `AndroidManifest.xml`
- [ ] **App (Application):** installs diagnostics (crash handler, main-thread
      watchdog, breadcrumbs) before anything else; starts the startup clock;
      begins the startup-health launch (counter via commit, safe-shell decision
      fixed here); touches SharedPreferences early on a background thread;
      kicks the v2.4 legacy-prefs purge, the home-cache mirror warm, the slim
      warm, and the store-memo warm (all single-flight, bounded, non-parsing);
      records memory-pressure trims as death evidence (stage + after-first-frame)
      — `App.kt`
- [ ] **No workers / alarms / widgets / notifications:** there is NO WorkManager,
      AlarmManager, JobScheduler, AppWidget, or NotificationManager usage in the
      tree. All "background" work is: (a) worker threads kicked from Activity /
      Application lifecycle, (b) due-checks on home wake / clock tick (Reddit
      rotation, weather staleness), (c) Handler runnables (hero timer, clock
      tick 1 s with seconds on / 20 s otherwise, screensaver 30 s tick).
      Verify the absence again in Phase 2 by grepping the new tree —
      `MainActivity.kt`, `App.kt`, `data/DiscoveryData.kt`, `data/ColdChainRunner.kt`
- [ ] **Cold chain runner:** sequential post-survival steps on a worker thread,
      cancellable on navigation (onDestroy), single-flight — `data/ColdChainRunner.kt`,
      `core/ColdChain.kt`
- [ ] **Discovery worker:** TMDB trending/soon/for-you refresh on a worker thread
      with 12 h trending TTL, disk file cache, single rebuild landing on change —
      `data/DiscoveryData.kt`
- [ ] **IPTV ingest worker:** catalogue + EPG ingest streams endpoints
      element-by-element and swaps atomically (all-or-nothing: swap only when
      every attempted endpoint succeeded); reads are worker-thread slices;
      refresh is on request only (save account / Refresh buttons) — never on
      home build — `data/IptvClient.kt`, `core/IptvStore.kt`, `core/IptvStream.kt`
- [ ] **Tracker refresh worker:** local-only refresh from the on-disk store
      (provider contact closed after the v3.7 one-time import); single-flight;
      section LOADING placeholders while in flight — `data/TrackerClient.kt`
- [ ] **Tracker missed worker:** recomputes the missed list per show and for all
      shows (kicked on local changes) — `data/LibraryLocal.kt`
      (`recomputeMissedForShow`, `kickMissedAll`)
- [ ] **Diagnostics engine:** crash log (`filesDir/crash.log`), ANR log
      (`filesDir/anr.log`, once per stall episode), breadcrumb ring (persisted,
      oldest-first, capped, HH:mm:ss + label), bounded event log (cap keeps
      newest, char cap never truncates the newest event), startup milestones,
      death evidence, main-thread watchdog (heartbeat fresh < 3.9 s, stale once
      strictly past threshold); all surfaced in Settings → Data & about with
      "Copy diagnostics" — `data/DiagLog.kt`, `core/Diag.kt`, `data/DeathStore.kt`
- [ ] **Startup-health machine:** two consecutive uncleared starts → safe shell
      on the third launch; a clean render + 10 s survival clears; a whole-home
      fallback on the first build HOLDS the clear (so a deterministic
      composition crash still arms the safe shell) — `core/StartupHealth.kt`,
      `data/StartupHealthStore.kt`
- [ ] **Speed Up:** signals OTHER apps' background processes to exit
      (`KILL_BACKGROUND_PROCESSES`, normal permission); never the never-list,
      system apps, the foreground app, or Sreeb itself; disables/uninstalls
      nothing; reports free RAM before/after + count signalled —
      `ui/SettingsActivity.kt`, `core/SpeedUp.kt`, `core/NeverList.kt`

---

## 3. Settings / preferences (SettingsActivity rows + stored defaults)

Per-profile values are stored under a `p<profile>.` prefix in the single
`mizan_launcher` SharedPreferences file; current profile, PIN, safe mode,
weather location, and last error are global. Profile count = 2 (ids 1–2) +
optional Guest designation (0 = none). Defaults below are the code defaults
(verified in `data/Prefs.kt`, `core/StatusBar.kt`, `core/LayoutStore.kt`,
`core/UnlockGrace.kt`, `core/Reddit.kt`).

### 3.1 Home & launcher

- [ ] Home screen setup checklist (make Sreeb the default home; watcher
      explanation; ADB command line for setting default home) — `ui/SettingsActivity.kt`,
      `data/HomeStatus.kt`
- [ ] Home watcher (accessibility) info row: optional, OFF unless enabled in
      system settings; shows watcher + default-home + current-home-package state —
      `ui/SettingsActivity.kt`, `data/HomeStatus.kt`
- [ ] Speed Up row + last-result line (default: no result yet) —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`speedupLastResult`, default "")
- [ ] (No other Home & launcher toggles; the launcher-access service has no
      in-app toggle — it is enabled only in system Accessibility settings, §8)

### 3.2 Look & wallpaper

- [ ] Theme picker: 9 presets — Apple Black (default), Roku Purple, Midnight,
      Dusk, Plum, Forest, Ocean, Graphite, Dynamic (wallpaper accent; exactly
      one dynamic preset); unknown id falls back to the first preset —
      `ui/SettingsActivity.kt`, `core/Themes.kt`
- [ ] Wallpaper picker: Theme background (default) · Gradient (14 built-ins,
      unknown falls back to first) · Solid colour · My picture (system picker,
      stored as content URI) · Reddit picture · Bing daily picture · Weather
      (live conditions) — `ui/SettingsActivity.kt`, `core/Themes.kt`
      (gradients), `data/ArtworkImages.kt`
- [ ] Home style picker: **TV / Watch only** (v3.9; default TV). Labels: TV =
      "TV — rows of apps under the hero bar"; Watch = "Watch — your library +
      IPTV playback of your titles"; safe-mode lock label when safe shell is
      armed. Switching is presentation-only (favorites/folders/order/locks
      shared, nothing lost) — `ui/SettingsActivity.kt`, `core/HomeStyle.kt`
- [ ] Icon shape: rounded (default) / circle / square — `data/Prefs.kt`
      (`iconShape`, default "rounded")
- [ ] App size: compact / standard (default) / large — `data/Prefs.kt`
      (`appSize`, default "standard")
- [ ] Grid columns: 3–10, default 6 — `data/Prefs.kt` (`columns`, default 6)
- [ ] Library sections toggles (Watch only): Hero (always first when shown) +
      every TrackerSections row; a section with nothing in it never appears even
      when switched on; "Reorder library sections…" opens the sort page —
      `ui/SettingsActivity.kt`, `core/TrackerSections.kt`
- [ ] Reddit wallpapers row (when wallpaper = Reddit): "Top of the week from
      your subreddits" + last-try status line — `ui/SettingsActivity.kt`
- [ ] Subreddits: comma-separated, default `wallpaper,wallpapers,EarthPorn`,
      `r/` prefix cleaned, invalid names rejected — `data/Prefs.kt`
      (`redditSubs`), `core/Reddit.kt`
- [ ] Allow NSFW results: OFF by default (over_18 posts skipped) —
      `data/Prefs.kt` (`redditAllowNsfw`, default false)
- [ ] Wallpaper rotation: off (default) / 15m / 1h / 6h / daily; checked on home
      wake or clock tick (no background service/timer); "Next wallpaper" jumps
      immediately — `ui/SettingsActivity.kt`, `core/Rotation.kt`,
      `data/Prefs.kt` (`redditRotation`, default off)
- [ ] Bing daily picture: changes with the calendar day; cached per day-key
      under `filesDir/bing_wallpapers/` — `core/Rotation.kt` (`BingWallpaper`),
      `data/BingFetcher.kt`

### 3.3 Home screen

- [ ] Per-row Show/Hide + Move up / Move down / Hide/Show dialog for every TV
      row (favorites, recents, mostused, folders, nowplaying, continue, inputs,
      livetv, apps); "Recently Used" row note clarifies its entry —
      `ui/SettingsActivity.kt`, `core/LayoutStore.kt`
- [ ] Live TV row note: tuner list only when the system allows; otherwise hidden
      with a diagnostics explanation — `ui/SettingsActivity.kt`
- [ ] Hidden apps manager: count shown, per-app unhide — `ui/SettingsActivity.kt`
- [ ] Folders manager: count shown; create/rename/lock/unlock/move/delete
      (same rules as §1.10) — `ui/SettingsActivity.kt`

### 3.4 Sort sections

- [ ] One page for both styles: tap a section, then Move up / Move down /
      Hide/Show (changes apply at once); hold OK to grab (D-pad moves it, OK
      drops, Back cancels); hero is Hide/Show-only; TV rows and Watch sections
      each keep their own list — `ui/SettingsActivity.kt`,
      `core/SortGrab.kt`, `core/TrackerSections.kt`, `core/LayoutStore.kt`
- [ ] Migration: pre-v2.7 stored Watch orders splice the v2.7 focus rows
      (Coming Soon / Trending) into canonical slots on first read under v2.8,
      once only (later deliberate reorders are never re-migrated) —
      `data/Prefs.kt` (`trackerSectionsConfig`, `tracker_sections_migrated_28`)

### 3.5 Status bar

All defaults from `core/StatusBar.kt` (`StatusBarConfig`); stored inside the
layout document (`status=` line), NOT as separate prefs keys.

- [ ] Show status bar: ON (off = clean Apple-TV style, no top bar at all) —
      default true
- [ ] Sticky status bar: OFF (bar pins at top while home scrolls, in TV/Watch/
      IPTV views) — `data/Prefs.kt` (`stickyStatusBar`, default false)
- [ ] Clock: ON; Date: ON; Weather: ON; Network icon: ON; Bluetooth icon: ON —
      defaults true
- [ ] Quick tiles row (master switch): ON — default true
- [ ] Tile: Wi-Fi: OFF (default false; glyph + popover cover it) · Tile:
      Bluetooth: ON · Tile: Sound: ON · Tile: Picture: ON · Tile: Settings gear:
      ON (corner gear appears even when off, so Settings stays reachable) ·
      Tile: Search: ON · Tile: All Apps: ON
- [ ] Clock format: 12-hour (default) / 24-hour; Show seconds: OFF (clock tick
      1 s when on and not safe mode, else 20 s) — defaults 12h / false
- [ ] Date format: weekday_month_day "Tue, Oct 20" (default) / iso "2026-10-20" /
      month_day_year "Oct 20, 2026"
- [ ] Text size: small / medium (default) / large
- [ ] Bar background: transparent (default) / theme / black; Background opacity:
      solid (default, alpha 0xFF) / translucent (alpha 0x99)
- [ ] Safe mode bar: Settings tile only (all other tiles suppressed) —
      `core/StatusBar.kt` (`barTiles`)

### 3.6 Weather

- [ ] Units: Fahrenheit — °F, wind mph (default) / Celsius — °C, wind km/h —
      `data/Prefs.kt` (`weatherUnitsKey`, per-profile; `WeatherUnits`)
- [ ] Weather location: latitude/longitude text entry, default
      `39.1653, -86.5264` (Bloomington, IN) — `data/Prefs.kt`
      (`weatherLat`, `weatherLon`, global)
- [ ] Forecast entry note: OK on clock/date/weather in the home bar opens the
      full forecast — `ui/SettingsActivity.kt`
- [ ] Weather background status: default "off" (internal; "Weather" wallpaper
      kind drives it) — `data/Prefs.kt` (`weatherBgStatus`)

### 3.7 Lock & profiles

- [ ] Set / change arrow pattern (enter existing pattern first to change) —
      default: no pattern set — `data/Prefs.kt` (`arrowPin`, default "")
- [ ] Clear arrow pattern (asks for it first) — `ui/SettingsActivity.kt`
- [ ] Locked apps manager: count shown, per-app lock/unlock —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`lockedApps`, default empty)
- [ ] Lock Settings: ON when a pattern is set and no explicit value stored
      (legacy rule: unset key reads as "pattern set ⇒ locked"); explicit toggle
      overrides — `data/Prefs.kt` (`lockSettings`)
- [ ] Forgot-pattern recovery note: system Settings → Apps → Sreeb → Clear data
      (resets layout too); "casual lock, by design" — `ui/SettingsActivity.kt`
- [ ] Unlock grace ("Re-ask for pattern"): Every time (default, 0 min) /
      After 5 / 15 / 30 minutes; grace covers locked apps, folders, Settings,
      and leaving Guest; switching profiles always asks again; a future-dated
      stamp never grants; expiry is exact at the boundary —
      `ui/SettingsActivity.kt`, `core/UnlockGrace.kt`, `data/Prefs.kt`
      (`unlockGraceMinutes`, default 0; `lastUnlockAt`, default 0)
- [ ] Profile switch: Profile 1 ⇄ Profile 2 (tap to switch; clears the grace
      stamp); each profile keeps its own theme, wallpaper, layout, status bar,
      weather units, hidden apps; the arrow lock is shared —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`currentProfile`, default 1)
- [ ] Rename Profile 1 / Rename Profile 2: custom name ≤ 16 chars, never leaves
      a trailing space; blank restores the default ("Guest" for the guest
      profile, "Profile N" otherwise); renaming never changes the Guest flag or
      any lock — `ui/SettingsActivity.kt`, `core/ProfileNames.kt`
- [ ] Guest profile: None (default, 0) / Profile 1 / Profile 2; switching TO
      Guest is always instant; while Guest is active a Guest pill shows and
      opening Settings or switching back asks for the pattern (if set) —
      `data/Prefs.kt` (`guestProfile`, default 0)
- [ ] Lock settings in Guest: ON (default true) — `data/Prefs.kt`
      (`guestLockSettings`, default true)

### 3.8 Now playing

- [ ] Now playing row toggle (row visibility flag; needs notification access
      below, otherwise the row stays hidden) — default row visible —
      `ui/SettingsActivity.kt`, `core/LayoutStore.kt`
- [ ] Notification access row: "Granted ✓" / "Not granted — tap to open system
      settings" (opens `ACTION_NOTIFICATION_LISTENER_SETTINGS`) —
      `ui/SettingsActivity.kt`, `data/NowPlayingData.kt`

### 3.9 IPTV

- [ ] Xtream account: Server (default ""), Username (default ""), Password
      (default "", shown as •••••••• when set); server gains `http://` when no
      scheme typed and loses a trailing slash; account needs server + user +
      pass — `ui/SettingsActivity.kt`, `core/IptvLogic.kt`, `data/Prefs.kt`
- [ ] Playlist (M3U) URL (default "") + Guide (XMLTV) URL (default ""):
      independent second source; channels join Live rows (sources NOT merged or
      de-duplicated); guide matched by tvg-id; URLs may embed credentials and
      get the same never-exported treatment — `ui/SettingsActivity.kt`,
      `data/Prefs.kt`
- [ ] Catalogue status line: counts + last-update time + failure class in
      parentheses when one is recorded (never credentials, never in non-error
      lines); "data refreshes only when you save the account or tap Refresh" —
      `ui/SettingsActivity.kt`, `core/IptvStatus.kt`, `data/IptvClient.kt`
- [ ] Refresh now (fetch catalogues + guide again) — `ui/SettingsActivity.kt`
- [ ] Clear IPTV sources… (confirmation dialog; removes account + playlist URLs
      from the TV; favorites and resume positions STAY) — `ui/SettingsActivity.kt`
- [ ] Category locks note: hold OK on a category chip to pattern-lock it; adult
      categories start locked when a pattern is set — `ui/SettingsActivity.kt`,
      `core/IptvLocks.kt`

### 3.10 Data & about

- [ ] Export settings: save all settings as a JSON file named
      `mizan-launcher-settings.json` (via system create-document; includes
      theme, wallpaper kind/value, layout, locked apps, home style, profile
      names, tracker sections, home-hint flag, lock_settings; EXCLUDES the
      tracker marked-set, For You overlay, pending overlay, IPTV favorites /
      resume, TMDB key, IPTV credentials) — `ui/SettingsActivity.kt`
- [ ] Import settings: restore from a previously exported JSON file; rejects
      non-Sreeb files with "That file isn't a Sreeb backup — nothing imported";
      type-tolerant (wrong-typed values evict to defaults) —
      `ui/SettingsActivity.kt`, `data/PrefsSafe.kt`
- [ ] TMDB API key row: Set / Not set only (masked entry, 80 chars); stored
      only on the TV, never in backups/diagnostics/logs; empty clears it;
      powers direct-TMDB fallback only (tracker proxy stays primary) —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`launcher_tmdb_key`, default "")
- [ ] Version header: "Sreeb v<version>" from PackageManager + diagnostics block
      (startup times, cold chain, tracker/library/home lines, TMDB lines, live
      TV line, IPTV line, freeze/crash tails, last death, watcher relaunch count
      + last time, screen-on recoveries) — `ui/SettingsActivity.kt`
- [ ] Copy diagnostics: copies the whole block to the clipboard for problem
      reports — `ui/SettingsActivity.kt`
- [ ] Safe mode toggle: "Safe mode: plain launcher" — themes/widgets/extras off —
      default OFF — `data/Prefs.kt` (`safeMode`, default false)
- [ ] Exit safe mode and retry normal home (shown only while safe mode / safe
      shell is armed; resets the counter and returns to the normal home style
      now) — `ui/SettingsActivity.kt`, `data/StartupHealthStore.kt`
- [ ] Player aspect mode (per profile, not a Settings row): fit (default) /
      stretch / zoom — `data/Prefs.kt` (`playerAspect`), `core/PlayerLogic.kt`

---

## 4. Local storage (files, databases, prefs keys)

No SQLite database exists in the tree. All persistence is SharedPreferences
(`mizan_launcher`) + plain files under `filesDir/`. Diagnostics never include
credential values.

- [ ] SharedPreferences file `mizan_launcher` (MODE_PRIVATE); every read via
      `PrefsSafe` (type-mismatch evicts + reads default) — `data/Prefs.kt`,
      `data/PrefsSafe.kt`
- [ ] Global keys: `current_profile` (1, coerced 1–2), `arrow_pin` (""; legacy
      `pin` removed on first init), `arrow_attempts` (0), `lock_settings`
      (unset ⇒ pattern-set means locked), `safe_mode` (false), `guest_profile`
      (0), `guest_lock_settings` (true), `last_unlock_at` (0),
      `speedup_last_result` (""), `launcher_tmdb_key` (""),
      `iptv_server` / `iptv_user` / `iptv_pass` / `iptv_m3u_url` / `iptv_epg_url`
      (all ""), `weather_lat` ("39.1653"), `weather_lon` ("-86.5264"),
      `weather_last_fetch` (0), `weather_cached` (""), `weather_data` (""),
      `weather_bg_status` ("off"), `last_refresh_at` (0), `last_refresh_kind`
      (""), `library_imported` (false), `library_last_change` (0),
      `last_boot_at` (set by BootReceiver), `own_foreground_at` (watcher loop
      guard), `watcher_relaunches` (0), `watcher_last_at` (0),
      `reddit_attempt_log` (last 12 lines), `last_error` (via `Prefs.noteError`,
      scrubbed) — `data/Prefs.kt`
- [ ] Per-profile keys (`p<profile>.` prefix): `theme` ("apple_black"),
      `wall_kind` ("theme"), `wall_value` (""), `accent_override` (0),
      `icon_shape` ("rounded"), `player_aspect` ("fit"), `app_size`
      ("standard"), `columns` (6), `home_style` ("tv"), `tracker_marked` (""),
      `tracker_hero_index` (0), `watch_hint_dismissed` (false),
      `tracker_sections` (""), `tracker_sections_migrated_28` (false),
      `foryou_overlay` (""), `pending_overlay` (""),
      `iptv_favorites` (""), `iptv_resume` (""), `iptv_locked_cats` (""),
      `iptv_cat_unlock_stamps` (""), `artwork` (""), `layout` (encoded layout
      document incl. `status=` line), `locked` (empty set),
      `reddit_subs` (default list), `reddit_nsfw` (false), `reddit_cache` (""),
      `reddit_last_status` (""), `home_hint_dismissed` (false),
      `reddit_rotation` ("off"), `reddit_rotation_last` (0),
      `reddit_rotation_index` (-1), `bing_cache_date` (""),
      `bing_cache_path` (""), `recents` (""), `sticky_status_bar` (false),
      `unlock_grace_min` (0), `profile_name` (per profile, "") —
      `data/Prefs.kt`, `core/LayoutStore.kt`
- [ ] `filesDir/iptv/`: `live.jsonl`, `vod.jsonl`, `series.jsonl`, `epg.jsonl`
      (one line per entry/programme), `index.json` (totals, per-kind counts,
      byte sizes, stamps), `series_info/` (`<fp>_<id>.json`); tmp+rename atomic
      swap; oversized EPG segments dropped with stream resync; guide retention
      window keeps now/next, drops the long past and far future —
      `data/IptvClient.kt`, `core/IptvStore.kt`, `core/IptvStream.kt`
- [ ] `filesDir/tracker/`: the launcher-owned indexed tracker store (v3.6
      redesign: line files + index, tmp+rename); `missed.txt` + per-title
      `missed_cache/`; written only by `LibraryLocal` queue writers and the
      v3.7 one-time import — `data/TrackerStoreDisk.kt`, `core/TrackerStore.kt`,
      `data/LibraryLocal.kt`
- [ ] `filesDir/home_cache/`: home-cache mirror metadata (section → length)
      + `tracker_slim.json` (≤ 512 KB slim snapshot for first-frame paint) —
      `data/HomeCacheDisk.kt`, `core/HomeCache.kt`, `data/TrackerClient.kt`
- [ ] `filesDir/discovery/`: TMDB payload files (`trending/tv/week`,
      `trending/movie/week`, `trending/all/week`, soon/for-you details) with
      stamps; trending TTL 12 h; details pruned by max age —
      `data/DiscoveryData.kt`
- [ ] `filesDir/artwork/<pkg>.png`: downloaded per-app artwork cache —
      `data/ArtworkImages.kt`
- [ ] `filesDir/reddit_wallpapers/` + `filesDir/bing_wallpapers/`: downloaded
      wallpaper images — `data/RedditFetcher.kt`, `data/BingFetcher.kt`
- [ ] `filesDir/crash.log`, `filesDir/anr.log`, breadcrumb file (see §2):
      diagnostics evidence — `data/DiagLog.kt`
- [ ] Image decode funnel: downsampled decodes + byte-bounded bitmap cache
      (never full-size unbounded bitmaps) — `data/DecodeBounds.kt`,
      `data/ArtworkImages.kt`
- [ ] JSON depth cap: parsers refuse nesting past 64 (a 5000-deep hostile
      payload reads as malformed, never a stack overflow) — `core/JsonMini.kt`,
      `core/IptvStream.kt`
- [ ] Backup/export JSON shape (see §3.10 for the included/excluded sets) —
      `ui/SettingsActivity.kt`

---

## 5. Data sources & integrations

- [ ] **TV tracker (the launcher's library source, local-first since v3.7):**
      shows, movies, episodes, watch progress, plan-to-watch, ignored/muted
      items, marked sets, For You picks, Up Next / Continue / Missed / tracked
      snapshots. Historically read via the tracker app's ContentProvider
      (`content://com.haythem.tvtracker.launcherdata`, permission
      `com.haythem.tvtracker.permission.LAUNCHER_READ`, declared in the
      manifest + `queries`); since v3.7 the ONLY provider contact is the
      one-time import on first v3.7 launch while the tracker is still installed
      (flag `library_imported` closes it forever; after the flag, NO code path
      queries the provider again). All later reads/writes are local
      (`filesDir/tracker/` + pending overlay + `LibraryLocal` queue writers
      that also maintain next-episode and missed recomputation) —
      `data/TrackerClient.kt`, `data/TrackerStoreDisk.kt`, `data/LibraryLocal.kt`,
      `core/TrackerLogic.kt`, `core/LibraryLogic.kt`, `AndroidManifest.xml`
- [ ] **TMDB via the tracker proxy (primary):** details / season / search
      through `TrackerClient.tmdbGet` (paths `details` / `season` / `search`,
      encoded); error kinds `no_key` / `http_<code>` / `io` / `bad_path`;
      retryable = http/io/unknown only; user lines name the fix for `no_key`
      and carry the code for http; diagnostics wording = path + human text +
      raw token in parens (v2.6: `io:<SimpleClassName>` tokens; exception
      messages never carried — they can embed the key) —
      `data/TrackerClient.kt`, `core/LibraryLogic.kt`
- [ ] **TMDB direct (fallback, launcher key only):** `https://api.themoviedb.org/3/`
      + path, contract allowlist, class-name tokens only, never logs (no Log
      import, no key echo); used only when the proxy fails AND a launcher key
      is saved; both-paths-failing surfaces the PROXY error (direct token kept
      for diagnostics only); composed lines never contain key material —
      `data/TmdbDirect.kt`, `data/TrackerClient.kt`
- [ ] **TMDB images:** `https://image.tmdb.org/t/p/<size><path>`; poster `w342`,
      backdrop `w780` (Up Next/Missed portrait cards use the poster, falling
      back to backdrop; Continue keeps landscape cards with blurred fill; hero
      keeps backdrops; a poster in a 16:9 frame is never centre-cropped) —
      `data/TmdbImages.kt`, `MainActivity.kt`, `ui/PosterCards.kt`
- [ ] **IPTV — Xtream Codes API:** `player_api.php` with encoded credentials +
      action; live `/live/<user>/<pass>/<id>.m3u8`; VOD with
      `container_extension` (default mp4); series episodes
      `/series/<user>/<pass>/<episodeId>.<ext>`; timeshift
      `/timeshift/<user>/<pass>/<durationMin>/<startYmdHis>/<id>.ts`;
      auth probe (1 true / 0 false, string ids accepted, junk null); category
      parse (string + numeric ids, blank names skipped); `tv_archive` drives
      catch-up availability — `core/IptvLogic.kt`, `data/IptvClient.kt`
- [ ] **IPTV — M3U playlists:** channel + group parse (quote-aware name,
      ungrouped → "Playlist"); channel identity = tvg-id/logo/direct URL,
      stable ids; streamed (never whole-document) — `core/IptvLogic.kt`,
      `core/IptvStream.kt`
- [ ] **IPTV — XMLTV guide:** `xmltv.php`-shaped URL; streamed parse; guide
      window retention (now/next kept); short-EPG per channel —
      `core/IptvEpg.kt`, `data/IptvClient.kt`
- [ ] **IPTV — Watch matching (tracker title ↔ IPTV catalogue):** squash
      normalization (lowercase, strip non-[a-z0-9]); exact tier, then contains
      tier over once-keyed candidates (repo method: 'Preacher' matches 'Reacher'
      by design — crude, pinned); year extraction (bracketed/trailing/leading
      only) breaks same-name ties and deprioritizes movies; ambiguity →
      first candidate flagged `possible`; missing season/episode → unresolved
      (fail-open); match cache fingerprint + codec stores ids + flags, never
      titles; matching runs on the `mizan-watch-match` worker only, never the
      main thread — `core/WatchMatch.kt`, `data/WatchPlay.kt`
- [ ] **Weather — Open-Meteo (no account/key):**
      `https://api.open-meteo.com/v1/forecast?latitude=…&longitude=…` (+ hourly
      / daily params); cached snapshot + 3 h-ish staleness rule via
      `RefreshPolicy.weatherFetchAllowed` (stale or explicit refresh) —
      `data/Weather.kt`, `core/WeatherLogic.kt`, `core/Forecast.kt`
- [ ] **Reddit wallpapers:** top-of-week JSON chain www → api → old → www
      `.rss` (RSS fallback feeds the same `RedditImage` model); only usable
      landscape image posts survive (direct `i.redd.it` image, preview fallback
      with URL unescaped; stickied / video / gallery / NSFW-unless-allowed /
      portrait rejected; dimensions carried; permalink captured; subreddit
      stamped from caller); attempt lines name endpoint + code + count; last
      12 attempts kept — `core/Reddit.kt`, `data/RedditFetcher.kt`
- [ ] **Bing daily picture:**
      `https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US`;
      relative URLs made absolute; malformed JSON fails open to null; day key
      is the calendar date — `core/Rotation.kt`, `data/BingFetcher.kt`
- [ ] **Installed apps:** PackageManager leanback + launcher intents; catalogue
      dedupes across categories (leanback label wins), system apps included
      and flagged, Sreeb itself excluded, sorted case-insensitively by label
      (ties by package); curated package → category overrides beat the manifest
      category table — `data/AppLoader.kt`, `core/AppCatalogue.kt`,
      `core/AppCategories.kt`
- [ ] **App usage:** Most Used from usage stats (grant-gated; Settings →
      Now playing page pattern — verify the exact grant screen at runtime, see
      §11); Recents from launch recording — `data/MostUsed.kt`,
      `core/MostUsed.kt`, `core/Recents.kt`
- [ ] **Live TV / tuner:** system TV listings provider (`READ_TV_LISTINGS`,
      best-effort, guarded at runtime) — `data/TvData.kt`, `data/LiveTv.kt`,
      `core/LiveTv.kt`
- [ ] **Media sessions:** MediaSessionManager via the notification-listener
      grant (see §2) — `data/NowPlayingData.kt`, `core/NowPlaying.kt`
- [ ] **System intents:** app info, uninstall, Wi-Fi/Bluetooth/Display/Sound/
      Storage/About system settings, notification-listener settings, default-home
      selection — `MainActivity.kt`, `ui/SettingsActivity.kt`,
      `core/QuickSettings.kt`, `data/HomeStatus.kt`
- [ ] **Discovery self-computation (v2.7):** launcher computes Coming Soon /
      Trending / For You itself from TMDB (seeds, affinity, recommendations +
      discover, pick codec); tracker snapshots remain the fallback; diagnostics
      gains a `discovery:` line — `core/Discovery.kt`, `data/DiscoveryData.kt`
- [ ] **Cleartext HTTP:** permitted app-wide (`usesCleartextTraffic=true`,
      targetSdk 34 default would block it) — deliberate: Xtream panels, M3U,
      XMLTV are overwhelmingly plain `http://host:port` — `AndroidManifest.xml`

---

## 6. Cross-cutting behaviours (check these, not just the screens)

- [ ] Fail-open everywhere: garbage/missing/truncated/corrupt stored state
      (layout document, row + section orders, home style, tracker snapshots,
      match cache, discovery cache, store index) decodes to defaults or empty,
      never crashes — `data/PrefsSafe.kt`, `core/LayoutStore.kt`,
      `core/TrackerSections.kt`, `core/HomeStyle.kt`
- [ ] Prefs funnel: every read through `PrefsSafe`; wrong-typed values evict
      themselves — `data/PrefsSafe.kt`
- [ ] Guarded rebuilds in every Activity (rebuild catches Throwable, records
      via `Prefs.noteError`, shows a retryable/snag state) — all `ui/*`,
      `MainActivity.kt`
- [ ] Main-thread audit: no network/parse/match work on the main thread;
      Watch matching worker-only; IPTV reads worker-thread slices; image decodes
      downsampled — `data/WatchPlay.kt`, `data/IptvClient.kt`,
      `data/DecodeBounds.kt`
- [ ] Crash containment: a throwing section yields its error tile; tracker/
      watch/iptv fall back to the TV render, TV to the emergency root; reporting
      throttled 1/section/minute — `core/SectionGuard.kt`, `MainActivity.kt`
- [ ] Startup breadcrumbs + watchdog + crash/ANR logs + death evidence, all in
      Copy diagnostics — `data/DiagLog.kt`, `data/DeathStore.kt`
- [ ] View caps (anti-blackout, v2.3.2): browser/guide grids cap 200, home rows
      capped in composition AND at render (home row view cap 20), favorites row
      capped, hostile EPG index fails open to no rows — `ui/IptvActivity.kt`,
      `ui/IptvGuideActivity.kt`, `MainActivity.kt`
- [ ] Staleness wording + local-first home cache: TTLs, oldest-first prune,
      credential tripwire (a credential-looking value in cached home data is
      treated as a trip, not rendered) — `core/HomeCache.kt`,
      `data/HomeCacheDisk.kt`
- [ ] Section lifecycle machine: LOADING / READY / EMPTY / ERROR + retry
      routing per section — `core/SectionModule.kt`
- [ ] Profile isolation: theme, wallpaper, layout, locks, hidden apps, sections,
      favorites, IPTV favorites/resume/locks never bleed across profiles —
      `data/Prefs.kt` (`p<profile>.` prefix)
- [ ] Never-kill list: gms, launcherx, Play Store, Settings + SystemUI, setup /
      keyboard / bluetooth / tvinput, Sreeb itself — never signalled by Speed Up,
      never offered for kill — `core/NeverList.kt`
- [ ] App-category curation: curated packages win over manifest categories;
      sections render in order, non-empty only, alphabetical inside —
      `core/AppCategories.kt`
- [ ] "Complete poster" artwork rule: posters render complete, never
      centre-cropped (standing v2.9 rule: cut heads are unacceptable) —
      `ui/PosterCards.kt`, `MainActivity.kt`
- [ ] Sim harness: `sim/SimMain.kt` + `sim/run_sim.sh` (kotlinc → JVM sim over
      pure `core/` logic) and its TEST_LOG expectations must be re-pinned, not
      dropped, by Phase 2 — `sim/SimMain.kt`, reference `TEST_LOG.md`

---

## 7. Settings defaults — quick table (see §3 for the rows)

| Setting | Default | Stored as |
|---|---|---|
| Profile | 1 | `current_profile` |
| Theme | Apple Black | `pN.theme` |
| Wallpaper kind | Theme background | `pN.wall_kind` |
| Icon shape | rounded | `pN.icon_shape` |
| App size | standard | `pN.app_size` |
| Grid columns | 6 (3–10) | `pN.columns` |
| Home style | tv | `pN.home_style` |
| Player aspect | fit | `pN.player_aspect` |
| Status bar | shown; clock/date/weather/network/bluetooth on; quick tiles on; Wi-Fi tile off, all other tiles on; 12h; no seconds; weekday_month_day; medium text; transparent bg; solid opacity | layout `status=` line |
| Sticky status bar | off | `pN.sticky_status_bar` |
| Weather units | Fahrenheit | `pN.weather_units` |
| Weather location | 39.1653, -86.5264 (Bloomington, IN) | `weather_lat`, `weather_lon` (global) |
| Reddit subs | wallpaper,wallpapers,EarthPorn | `pN.reddit_subs` |
| Reddit NSFW | off | `pN.reddit_nsfw` |
| Wallpaper rotation | off | `pN.reddit_rotation` |
| Arrow pattern | unset | `arrow_pin` (global) |
| Lock Settings | pattern-set ⇒ locked (until explicitly toggled) | `lock_settings` (global) |
| Unlock grace | Every time (0 min) | `pN.unlock_grace_min` |
| Guest profile | none | `guest_profile` (global) |
| Guest settings lock | on | `guest_lock_settings` (global) |
| Safe mode | off | `safe_mode` (global) |
| TMDB key / IPTV account / M3U / EPG | unset | global keys (§4) |
| Watch sections | all shown, canonical order | `pN.tracker_sections` |
| TV rows | all shown, canonical order | layout row order |

---

## 8. Commit f26a5f6 — exact behaviours (verified from the diff, not the summary)

### 8.1 Unaired-episode gate (Details episode checklist + season marking)

Rule (identical in both call sites — `ui/DetailsActivity.kt#hasAired` and
`data/TrackerActions.kt#hasAired`, duplicated, not shared):
`hasAired(airDate)` = **true** when the trimmed air date is blank (unknown —
fail open, never invent a date), **true** when it does not match
`yyyy-MM-dd` (malformed — fail open), otherwise **true** iff the date string
is ≤ the device's local today (`LocalDate.now().toString()`; ISO strings
compare chronologically, so plain string comparison is exact for well-formed
dates). An episode airing **today counts as aired**.

- [ ] Episode checklist row: an unaired episode carries **no ▶ glyph** (there
      is nothing to play yet); aired + IPTV-matched episodes keep the ▶ —
      `ui/DetailsActivity.kt` (`episodeRow`)
- [ ] Episode OK (click) on an unaired, unwatched episode: **no toggle**; toast
      `Hasn't aired yet — airs <airDate>` (with the date) or `Hasn't aired yet`
      (when the date is blank — unreachable in practice since blank = aired,
      but the branch exists) — `ui/DetailsActivity.kt`
- [ ] Episode hold-OK (long-press) on an unaired, **unwatched** episode: **no
      menu at all**; the same toast as above — `ui/DetailsActivity.kt`
- [ ] Episode hold-OK on an unaired, **already-marked** episode: menu offers
      ONLY `↩ Mark unwatched` (the correction path; no Play, no Mark watched) —
      `ui/DetailsActivity.kt`
- [ ] Episode hold-OK on an aired episode: menu offers `▶ Play S<s> E<e>` +
      `✓ Mark watched` / `↩ Mark unwatched` as before — `ui/DetailsActivity.kt`
- [ ] Episode OK on an aired episode: toggles watched as before (unaired check
      precedes the toggle call) — `ui/DetailsActivity.kt`,
      `data/TrackerActions.kt` (`toggleEpisode`)
- [ ] "✓ Mark season watched": filters the season's episode list through
      `hasAired` FIRST; only aired episodes are written (overlay season mark +
      queue-season-mark writer + per-episode marked-set entries); if NO episode
      has aired, the function returns silently without writing anything —
      `data/TrackerActions.kt` (`markSeason`)
- [ ] "Mark season watched" button's remaining-count label counts only
      unwatched episodes that have aired (`epKey ∉ effectiveWatched AND
      hasAired(airDate)`); the button hides when that count is 0 —
      `ui/DetailsActivity.kt` (season block)
- [ ] Undated episodes (blank air date) behave exactly as before: ▶ shown,
      Play offered, Mark watched offered, season marking includes them —
      `ui/DetailsActivity.kt`, `data/TrackerActions.kt`
- [ ] Malformed air dates behave as aired (fail open) — same two `hasAired`
      implementations
- [ ] Scope note: the gate lives ONLY in Details episode rows + season marking.
      No gate was added to Up Next / Continue / Missed card play paths, Library
      next-episode marking, or Watch auto-mark in this commit — verify whether
      Phase 2 should extend it (see §11).

### 8.2 Accessibility services (both off by default, both in TV Settings → Accessibility → Installed services)

**"Sreeb launcher" — `LauncherService.kt`, `res/xml/launcher_config.xml`:**

- [ ] Label `Sreeb launcher`; description: "Lets Sreeb perform system actions
      (Back, Home, Recents, notifications) on your behalf. It cannot read screen
      contents." — `res/values/strings.xml` (`launcher_access_name`,
      `launcher_access_desc`)
- [ ] Config: `typeWindowStateChanged` events only, `feedbackGeneric`,
      `notificationTimeout=100`, **`canRetrieveWindowContent=false`** (sees
      WHICH window is showing, never what is on it), **no gestures**
      (`canPerformGestures` off — not declared) — `res/xml/launcher_config.xml`
- [ ] `onAccessibilityEvent` is intentionally a no-op: this service does NOT
      monitor windows (the watcher does that); window-state events are declared
      only so the service stays eligible for future launcher features without a
      config change — `LauncherService.kt`
- [ ] `performAction(action: Int): Boolean` fires a system global action via the
      connected instance (`GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`,
      `GLOBAL_ACTION_RECENTS`, `GLOBAL_ACTION_NOTIFICATIONS`,
      `GLOBAL_ACTION_QUICK_SETTINGS`, `GLOBAL_ACTION_POWER_DIALOG`, …);
      returns **false** when not enabled/connected — callers MUST fail open
      (the action simply does not happen) — `LauncherService.kt`
- [ ] `isConnected(): Boolean` = the static instance is non-null (set on
      `onServiceConnected`, cleared on `onUnbind`/`onDestroy`) —
      `LauncherService.kt`
- [ ] Everything try/catch-wrapped; a failure here can never crash or loop the
      launcher; sends nothing anywhere; reads no content — `LauncherService.kt`
- [ ] Enabled-state probe for Settings/diagnostics:
      `HomeStatus.isLauncherServiceEnabled(ctx)` checks
      `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for this service's
      flattened ComponentName (case-insensitive, colon-split; false on any
      error) — `data/HomeStatus.kt`
- [ ] No in-app toggle exists for this service; the opt-in lives with the user
      in system Accessibility settings (Android only runs a service the user
      personally switched on) — `LauncherService.kt` (doc), `AndroidManifest.xml`

**"Sreeb launcher watcher" — `WatcherService.kt`, `res/xml/watcher_config.xml`:**

- [ ] Renamed in f26a5f6 from "Sreeb watcher" to **"Sreeb launcher watcher"**;
      description unchanged: "Watches for the TV's stock home screen appearing
      and reopens Sreeb instead. It cannot read screen contents." —
      `res/values/strings.xml` (`watcher_name`, `watcher_desc`)
- [ ] Config identical in kind: window-state events only,
      `canRetrieveWindowContent=false`, `notificationTimeout=100` —
      `res/xml/watcher_config.xml`
- [ ] Behaviour unchanged by f26a5f6: passive stock-home relaunch + screen-on
      recovery, debounce + loop guards, diagnostics counting (see §2) —
      `WatcherService.kt`
- [ ] In-app references updated to the new name in Settings (watcher info row) —
      `ui/SettingsActivity.kt`

---

## 9. Notable v3.9 behaviours (commit 5d5e4b5, on top of v3.8)

- [ ] **Hero/Missed shell-frame fix:** recreated-activity memo rule (§1.1b) +
      store-memo warm kick in `prepareTrackerData` (bounded line reads on a
      worker, rebuild on landing, per-instance guard so the landing rebuild
      cannot re-kick into a loop; if the slim hits first, the store warm's
      install replaces it; if the store lands first, the slim refuses to
      clobber it) + `home:` diagnostics line (memo source store/slim/prefs/
      none; snap up/cont/missed/foryou/tracked counts; hero + missedRow counts
      AFTER filtering; heroShown + missedShown; coldStart) — `MainActivity.kt`,
      `App.kt`, `data/TrackerClient.kt`
- [ ] **Missed per-show pending scoping:** pending watched adds/removes are
      looked up per show (`LibraryLogic.pendingWatchedFor(pending, tmdbId)`),
      not folded across shows — `MainActivity.kt`, `core/LibraryLogic.kt`
- [ ] **Trending IPTV play:** Trending TV + Trending Movies join the
      visible-first match set (type must match; movie year rides along for
      deprioritization only), so a Trending title now gets an IPTV match,
      badge, and play path; matched Trending shows warm with the rest so the
      tap pays no cold series-info fetch — `MainActivity.kt`,
      `data/WatchPlay.kt`
- [ ] **Trending play without a library twin:** a matched show WITHOUT an Up
      Next twin now plays from S1 E1 (`inSnapshot=false`, no library mark token);
      if S1 E1 is not on IPTV, the failure toast shows and it fails open to
      Details (never a dead end, never an invented later episode) —
      `MainActivity.kt`
- [ ] **IPTV merged into Watch under Live:** home-style picker offers **TV /
      Watch only**; stored "iptv" decodes to Watch (like "tracker" before it);
      Watch tabs are Home · Library · Apps · Live; Live opens `IptvActivity`
      on its Live tab, and the full IPTV browser (Movies, Series, Guide,
      Favorites, Search, locks, PiP, catch-up) is one more tap away inside it;
      the old IPTV tab list (Home · Live · Movies · Series · Guide · Apps) is
      deleted, not kept as a second bar; `buildIptvHome` etc. survive only as
      dead-via-dispatch fallback code + SectionGuard crash-fallback mapping —
      `core/HomeStyle.kt`, `core/HomeTabs.kt`, `MainActivity.kt`,
      `ui/SettingsActivity.kt`
- [ ] **Portrait Up Next / Missed cards:** Up Next and Missed (which renders
      through the same builder) are TALL 2:3 portrait poster cards, like
      Trending's `portraitCard`; the blurred fill is GONE from these cards; a
      true 2:3 poster fills edge-to-edge, off-ratio art letterboxes via
      FIT_CENTER (complete, never centre-cropped); the S/E indicator moves
      from an in-frame chip to the line below the title; countdown/date label
      stays as meta; ▶ IPTV badge, play path, and mark-watched menu unchanged;
      scope is EXACTLY Up Next + Missed — Continue keeps landscape cards with
      blurred fill; Soon, Trending, hero backdrop, and Library grid cards are
      untouched — `MainActivity.kt`
- [ ] Version bump: 3.9 / code 32 (TEST_LOG's v2.5-era "3.9 / code 32" line is
      consistent with `app/build.gradle.kts`) — `app/build.gradle.kts`

---

## 10. Known tangles (import-direction violations — verified by grep)

Layer rule the old tree aspires to: `core/` (pure logic, no Android imports)
← `data/` (Android + I/O, no UI) ← `ui/` (Activities/views) ← root (app entry).
`core/` is CLEAN: no `core/` file imports `data/`, `ui/`, or root-package
classes. Every violation below is real in the current tree; Phase 2 must decide
each one (invert the dependency, move the type, or document the exception) —
not silently copy it.

- [ ] **TANGLE-1 — data → ui (import):** `data/ArtworkImages.kt` imports
      `com.mizan.launcher.ui.Ui` (dp helpers used while caching artwork) —
      `data/ArtworkImages.kt:8`
- [ ] **TANGLE-2 — data → ui (import):** `data/WatchPlay.kt` imports
      `com.mizan.launcher.ui.PlayerActivity` (starts playback from the match
      layer) — `data/WatchPlay.kt:8`
- [ ] **TANGLE-3 — data → ui (fully-qualified, no import — same violation):**
      `data/TrackerActions.kt` builds an intent for
      `com.mizan.launcher.ui.DetailsActivity` and references its `EXTRA_ID` /
      `EXTRA_TYPE` / `EXTRA_TITLE` / `EXTRA_POSTER` constants —
      `data/TrackerActions.kt:263-267`
- [ ] **TANGLE-4 — data → root (import):** `data/HomeStatus.kt` imports
      `com.mizan.launcher.LauncherService` and `com.mizan.launcher.WatcherService`
      (enabled-state probes need the service classes) — `data/HomeStatus.kt:7-8`
- [ ] **TANGLE-5 — data → root (import):** `data/NowPlayingData.kt` imports
      `com.mizan.launcher.NowPlayingListener` (grant check needs the listener
      component) — `data/NowPlayingData.kt:10`
- [ ] **TANGLE-6 — root → ui (import, expected direction but worth noting):**
      `MizanDreamService.kt` imports `com.mizan.launcher.ui.Ui` (dp helper) —
      `MizanDreamService.kt:12`
- [ ] **TANGLE-7 — root ↔ ui CYCLE (the big one):** `MainActivity.kt` imports
      `com.mizan.launcher.ui.SettingsActivity` (to open Settings) AND
      `ui/SettingsActivity.kt` imports `com.mizan.launcher.MainActivity` (and
      uses `MainActivity.DiagCache` + `MainActivity.AppCache` directly) —
      `MainActivity.kt:93`, `ui/SettingsActivity.kt:17,1861,2442-2448`
- [ ] **TANGLE-8 — ui → data (pervasive, by design in the old tree but a layer
      inversion if Phase 2 wants ui thin):** every `ui/` Activity imports
      `data/` types (`Prefs`, `IptvClient`, `TrackerClient`, `TrackerActions`,
      `WatchPlay`, `AppLoader`, `ArtworkImages`, `TmdbImages`, `Weather`, …)
      plus fully-qualified `data.*` references (`DiagLog`, `TrackerStoreDisk`,
      `IptvClient`, `StartupHealthStore`, `MostUsed`, `NowPlayingData`,
      `HomeStatus`, `WallpaperRotator`, `LibraryLocal` …) — all files under
      `ui/`; the full list is greppable via `^import com.mizan.launcher.data`
      in `ui/`
- [ ] **TANGLE-9 — root → data (pervasive, same note):** `App.kt` imports
      `data/DiagLog`, `data/IptvClient`, `data/StartupHealthStore` (plus
      fully-qualified `data/TrackerClient`); `BootReceiver.kt` imports
      `data/AppLoader`, `data/Prefs`, `data/HomeStatus` (FQ); `WatcherService.kt`
      imports `data/HomeStatus`, `data/Prefs` — root files
- [ ] **TANGLE-10 — ui → root (single, part of TANGLE-7):**
      `ui/SettingsActivity.kt` → `MainActivity.DiagCache` / `MainActivity.AppCache`
      (diagnostics + app-list cache live on the Activity companion instead of
      in `data/`) — `ui/SettingsActivity.kt:1861,2442-2448`
- [ ] **TANGLE-11 — duplicated logic (not an import, but a merge hazard):**
      the unaired `hasAired()` rule is implemented twice, identically, in
      `ui/DetailsActivity.kt` and `data/TrackerActions.kt` (f26a5f6) — Phase 2
      should own it once — both files
- [ ] **NOT a tangle (checked):** no `core/` file imports `data/`, `ui/`, or
      root classes; no `data/` file imports `ui/DetailsActivity` via an import
      statement (it is fully-qualified — TANGLE-3); `sim/SimMain.kt` imports
      only `core/` (by design — the sim runs pure logic)

---

## 11. Unresolved / needs runtime check (do NOT invent — verify on device)

- [ ] Exact text/steps of the "Make Sreeb your home screen" checklist in
      Settings → Home & launcher (incl. the ADB command line shown) —
      `ui/SettingsActivity.kt` (`buildMakeHome`), `data/HomeStatus.kt`
- [ ] Exact Library header button set (refresh? TMDB search? sort?) —
      `ui/LibraryActivity.kt`
- [ ] Details default-season rule wording (first season with an unwatched
      episode vs. first unfinished season vs. latest season) —
      `ui/DetailsActivity.kt` (`defaultSeason`)
- [ ] Whether the unaired gate SHOULD extend beyond Details + season marking
      (Up Next / Continue / Missed card play, Library next-episode marking,
      Watch auto-mark) — f26a5f6 scoped it to Details only; product decision
      for Phase 2
- [ ] Most Used usage-stats grant screen: which system settings page it opens
      and its exact row wording — `data/MostUsed.kt`, `ui/SettingsActivity.kt`
- [ ] Coming Soon vs. Up Next presentation boundary on Watch (merged in v3.3;
      Soon items feed the memo-less fallback) — `MainActivity.kt`,
      `core/Discovery.kt`
- [ ] Whether any runtime-only behaviour exists with no code trace (e.g.
      OEM-specific Home-press routing, tuner permission grants on specific TVs,
      PiP refusal behaviour per device) — verify on the onn. box
- [ ] Exact home-hint dismissal persistence across profiles (per-profile flag,
      confirmed) vs. any global dismissal — `data/Prefs.kt`
- [ ] The v3.8→v3.9 "memo-less shell frame" photo diagnosis is code-documented
      (see §9) but the on-device fix itself is UNVERIFIED (emulator testing
      never reached adb; onn.-box confirmation pending) — do not mark §9 items
      device-verified until that confirmation lands
- [ ] TEST_LOG.md tail beyond the read window (the file is long; the sim
      section list in §6 covers its spec sections, but Phase 2 should re-read
      the full log before re-pinning sim expectations) — reference `TEST_LOG.md`

---

## 12. File index (old tree → what it owns)

| Area | Files | Owns |
|---|---|---|
| Root | `App.kt`, `BootReceiver.kt`, `LauncherService.kt`, `MainActivity.kt`, `MizanDreamService.kt`, `NowPlayingListener.kt`, `WatcherService.kt` | App entry, boot, 2 accessibility services, home, screensaver, media-session grant, home watcher |
| `core/` (64) | `AllApps`, `AppCatalogue`, `AppCategories`, `AppSearch`, `ArrowPin`, `Artwork`, `ArtworkStore`, `ColdChain`, `DeathCapture`, `Design`, `Diag`, `Discovery`, `DominantColor`, `Forecast`, `GridMove`, `GuestGate`, `HeroRotator`, `HomeCache`, `HomeStyle`, `HomeTabs`, `IptvEpg`, `IptvLocks`, `IptvLogic`, `IptvStatus`, `IptvStore`, `IptvStream`, `JsonMini`, `LayoutStore`, `LibraryLogic`, `LiveTv`, `MostUsed`, `MoveSession`, `NeverList`, `NowPlaying`, `PlayInLogic`, `PlayerLogic`, `ProfileNames`, `QuickSettings`, `Recents`, `Reddit`, `RefreshPolicy`, `Rotation`, `SectionGuard`, `SectionModule`, `SettingsFit`, `SettingsGate`, `SettingsNav`, `SortGrab`, `SpeedUp`, `StartupHealth`, `StartupStages`, `StatusBar`, `Themes`, `TileLayout`, `TrackerLogic`, `TrackerSections`, `TrackerSlim`, `TrackerStatus`, `TrackerStore`, `UnlockGrace`, `VisibleWindow`, `WatchMatch`, `WeatherLogic`, `WifiLabel` | Pure logic: codecs, ordering, matching, parsing, state machines, design tokens (no Android imports) |
| `data/` (30) | `AccentUtil`, `AppLoader`, `ArtworkImages`, `BingFetcher`, `ColdChainRunner`, `DeathStore`, `DecodeBounds`, `DiagLog`, `DiscoveryData`, `HomeCacheDisk`, `HomeStatus`, `IptvClient`, `LibraryLocal`, `LiveTv`, `MostUsed`, `NowPlayingData`, `Prefs`, `PrefsSafe`, `RedditFetcher`, `StartupHealthStore`, `TmdbDirect`, `TmdbImages`, `TrackerActions`, `TrackerClient`, `TrackerStoreDisk`, `TvData`, `WallpaperRotator`, `WatchPlay`, `Weather`, `WeatherArt` | Android + I/O: prefs, network, files, provider/store, playback matching glue |
| `ui/` (19) | `AllAppsActivity`, `ArrowPinDialog`, `ArtworkPicker`, `DetailsActivity`, `ForecastActivity`, `IptvActivity`, `IptvCards`, `IptvGuideActivity`, `KeyboardDialog`, `LibraryActivity`, `PatternGate`, `PlayIn`, `PlayerActivity`, `PosterCards`, `SearchActivity`, `SettingsActivity`, `SwitchRow`, `TmdbSearchActivity`, `Ui` | Activities, dialogs, pickers, card builders |
| `res/` | `AndroidManifest.xml`, `values/strings.xml`, `values/themes.xml`, `xml/launcher_config.xml`, `xml/watcher_config.xml`, drawables, mipmap | Manifest (11 activities, 4 services, 1 receiver), service configs, app/window labels |
| `sim/` | `SimMain.kt`, `run_sim.sh` | Headless JVM tests over `core/` |

---

*End of Phase 1 map. Reference tree untouched (git status clean at time of
writing). No code was written, nothing was pushed, the user was not contacted.*
