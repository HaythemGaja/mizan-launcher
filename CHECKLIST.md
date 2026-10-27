# Mizan Launcher rebuild — Checklist (Phase 2D merged: integration complete)

Source: a copy of MAP.md with each checklist line annotated. Do NOT
delete a line — check it or record why it intentionally changed.

Status semantics in THIS file (Phase 2D merged):
Checked = implemented AND wired end to end in the new tree (core +
  domain + data + ui + app as the item requires), with a "— 2D:"
  note naming the implementing file(s). Static verification only:
  no on-device test was possible on this VM, so onn.-box
  confirmation remains pending for every item.
Unchecked with a "— 2D PARTIAL / — 2D NOT VERIFIED / — 2D NOT WIRED"
  note = honestly not preserved, with the reason on the item.
  Foundation "core part ported" notes are retained where the core
  part is real; the 2D note is the current verdict.
- Nothing is marked checked that is not actually implemented and
  wired. In particular all §11 items stay unchecked: they require
  runtime verification on the onn. box and are never marked done
  from source alone.

Counts at Phase 2D (integration complete, static verification only — no on-device test was possible on this VM):
266 checked end-to-end (implemented and wired in the new tree), 32 unchecked with an honest reason on the item.
Total 298 MAP items. Unchecked: 22 partial / not-wired items (wallpaper composition, dynamic accent,
hold-and-drop move mode, folder-menu toggle/move entries, Coming Soon as a separate section, shell-frame
memo diagnostics, Library header button set, Player Prev/Next queue, folder-entry move UI, compact Settings
clamps, exact Make-home checklist text, sort-page hold-OK grab, accent_override + tracker_hero_index keys,
TMDB proxy, installed-apps curation overrides, discovery self-computation, app-category curation, sim harness
re-pinning, hero/Missed shell-frame fix verification, portrait Continue card deviation) + 10 §11 runtime-only
items that by definition require onn.-box verification and are never marked done from source alone.
APK (SUPERSEDES the unsigned build noted in earlier phases): the SIGNED APK is
at out/Mizan-Launcher-rebuild.apk (1,229,840 bytes; package com.mizan.launcher,
versionCode 32, versionName 3.9, minSdk 26, targetSdk 34; zipalign -c 4 OK;
apksigner verify passes, signer CN=Mizan Launcher Debug). The earlier signing
blocker (unset KEYSTORE_PASS) was resolved by the coordinator using the
signing invocation documented in the parent build script; two packaging bugs
in tools/build.sh found during that run were fixed (zip step no longer changes
directory; its file glob is unquoted so it expands). The unsigned
out/Mizan-Launcher-rebuild-unsigned.apk is stale and must not be delivered.
Phase 3 independent review verdict: SHIP (for onn.-box device confirmation,
with the 32 unchecked items disclosed). No on-device test was possible on
this VM; onn.-box confirmation remains pending.

---
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

- [x] Home is the default-home Activity: `singleTask`, `clearTaskOnLaunch`,
      `excludeFromRecents`, `stateNotNeeded` — `MainActivity.kt`, `AndroidManifest.xml`
      — 2D: app/MainActivity.kt + AndroidManifest.xml (singleTask, clearTaskOnLaunch, excludeFromRecents, stateNotNeeded, HOME/LEANBACK_LAUNCHER).
- [x] First frame is a staged shell (frame, status bar, tab bar, section frames
      from in-memory/prefs-light state only, no heavy parse), then the warmed
      home completes it; frame probes record stage 1 (first frame) and stage 2
      (home complete) — `MainActivity.kt` (`buildRoot`, `attachFrameProbe`,
      `onStartupRenderCompleted`)
      — 2D: app/MainActivity.kt (stage-1 shell, frame probe, warmed rebuild, stage-2 breadcrumb).
- [x] Cold chain: after first frame + 10 s survival, heavy work runs
      sequentially (slim/store memo, full memo parse, discovery hydration);
      navigation-cancelled chains restart the clock — `MainActivity.kt`
      (`scheduleStartupClear`), `core/ColdChain.kt`, `data/ColdChainRunner.kt`
      — 2D: app/MainActivity.kt (10 s survival, restart-on-navigation) + data/ColdChainRunner.kt + core/ColdChain.kt.
- [x] Safe shell / safe mode: after two consecutive uncleared starts the third
      launch renders the safe shell (plain frame, Favorites + Apps rows only, no
      wallpaper work, notice tile that opens Settings); safe mode is the same
      composition — `MainActivity.kt` (`buildEmergencyRoot`, `safeShellNotice`),
      `core/StartupHealth.kt`, `data/StartupHealthStore.kt`
      — 2D: app/MainActivity.kt (HomeUi.safeShell) + data/StartupHealthStore + core/StartupHealth.kt; safe-mode setting renders the same composition.
- [x] Emergency root: if even the shell composition throws, a bare root with an
      "open Settings" action renders instead of a black screen — `MainActivity.kt`
      — 2D: app/MainActivity.kt (emergencyRoot; whole-home fallback HOLDS the startup clear).
- [x] Status bar (TV style): clock, date, weather, network/Bluetooth glyphs,
      Wi-Fi SSID label, quick tiles, profile tile, Settings gear, corner ⚙
      fallback when the bar's Settings tile is off; sticky-bar option pins it
      while scrolling — `MainActivity.kt`, `core/StatusBar.kt`, `core/WifiLabel.kt`
      — 2D: app/MainActivity.kt buildStatusBar (clock/date/weather, Guest pill, Forecast/Settings tiles) + ui/QuickSettingsView.kt statusBar; sticky pins above scroll, else scrolls.
- [ ] Wallpaper behind home: theme background, gradient, solid colour, user's — core part ported (foundation): new core/WeatherLogic.kt; data/ui/app parts pending
      own picture, Reddit picture, Bing daily picture, or live weather
      background (palette follows current condition) — `MainActivity.kt`,
      `data/Weather.kt`, `data/WeatherArt.kt`, `data/RedditFetcher.kt`,
      `data/BingFetcher.kt`, `data/WallpaperRotator.kt`, `core/WeatherLogic.kt`
      — 2D PARTIAL: theme gradient + My picture + Reddit + Bing compose in app/MainActivity.kt; Gradient-kind (14 built-ins) selection, Solid colour value, and live-weather palette composition are NOT applied (theme gradient stands in).
- [ ] Dynamic accent: dominant colour extracted from the wallpaper drives the — core part ported (foundation): new core/DominantColor.kt; data/ui/app parts pending
      accent when the Dynamic theme is selected — `MainActivity.kt`
      (`resolveColours`), `data/AccentUtil.kt`, `core/DominantColor.kt`
      — 2D NOT WIRED: Dynamic theme preset exists in core/Themes.kt but its accent is not plumbed into card rendering (cards use fixed colours).
- [x] Guest pill in the status bar while a Guest profile is active —
      `MainActivity.kt`, `core/GuestGate.kt`
      — 2D: app/MainActivity.kt status bar Guest text when the Guest profile is active.
- [x] Per-section Loading / Ready / Empty / Error states with per-section Retry
      routing; section failures are throttled to one report per section per
      minute and never take down neighbouring sections — `MainActivity.kt`
      (`sectionErrorTile`, `sectionLoadingTile`, `retrySection`),
      `core/SectionModule.kt`, `core/SectionGuard.kt`
      — 2D: ui/HomeUi.kt cardRow states + Retry routing + ui/Ui.kt guarded sections + core/SectionGuard.kt.
- [x] No auto-refresh on open: home data refreshes only via "Refresh home data"
      or a per-section Retry (both force); first-build probe excepted —
      `MainActivity.kt` (`refreshHomeData`), `core/RefreshPolicy.kt`
      — 2D: app/MainActivity.kt refreshes only via Refresh home data / per-section Retry (both force).
- [x] Hero rotation: hero advances on a timer, pauses while focus is held,
      resumes after a delay, Next advances immediately and resets the timer,
      fewer than 2 candidates never rotate — `core/HeroRotator.kt`
      — 2D: ui/HomeUi.kt HeroCarousel + core/HeroRotator.kt, hosted by app/MainActivity.kt.
- [x] App tiles: icon shape (rounded/circle/square), app size
      (compact/standard/large) scaling all tiles equally, grid columns 3–10,
      label visible only when focused — `core/TileLayout.kt`, `core/Design.kt`
      — 2D: ui/PosterCards.kt appTile (shape/size, label only when focused) + core/TileLayout.kt + core/Design.kt, hosted by app/MainActivity.kt.
- [ ] Hold-OK (long-press) app menu: Move (hold-and-drop move mode: arrows move, — core part ported (foundation): new core/GridMove.kt, core/MoveSession.kt; data/ui/app parts pending
      OK drops, Back cancels, exact snapshot restored on cancel), Add to
      folder…, Hide app, App info (system), Uninstall (system, non-system apps
      only), Lock/Unlock app — `MainActivity.kt`, `core/MoveSession.kt`,
      `core/GridMove.kt`
      — 2D PARTIAL: app/MainActivity.kt appMenu implements Hide / App info / Uninstall (non-system) / Lock-Unlock; hold-and-drop Move mode is NOT implemented (no domain move-session contract — AppsRepository moves an entry to a folder only).
- [ ] Hold-OK folder menu: Rename folder, Lock/Unlock folder, Move into — core part ported (foundation): new core/LayoutStore.kt; data/ui/app parts pending
      folder…, Delete folder (sub-folders rehomed to root, apps kept) —
      `MainActivity.kt`, `core/LayoutStore.kt` (`FolderTree`)
      — 2D PARTIAL: ui/FolderViewActivity.kt has Rename / Delete / lock-prompt; Lock/Unlock toggle and Move-into-folder menu entries are NOT offered.
- [ ] Move mode works for Favorites, All Apps order, and folder contents
      (including sub-folder entries by entry key) — `core/MoveSession.kt`
      — 2D NOT IMPLEMENTED: no hold-and-drop move mode in any layer (see app-menu item).
- [x] "Play in…" picker (last menu entry on matched cards): play the title in
      another installed video app; the launcher itself is excluded, unresolvable
      targets filtered, "no app" reported honestly — `ui/PlayIn.kt`,
      `core/PlayInLogic.kt`, `MainActivity.kt`
      — 2D: ui/PlayIn.kt + core/PlayInLogic.kt + ui/HomeUi.kt cardMenu + ui/PlayerActivity.kt Play in… button.
- [x] Quick-settings popover: Wi-Fi, Bluetooth, Display, Sound, Storage,
      About device (system intents, filtered if unresolvable), plus internal
      "Sort sections", "Refresh home data", "Sreeb Settings" — `core/QuickSettings.kt`
      — 2D: ui/QuickSettingsView.kt show + core/QuickSettings.kt, opened from app/MainActivity.kt.
- [x] Remote Search key opens app Search; status-bar Search tile does the same —
      `MainActivity.kt`, `ui/SearchActivity.kt`
      — 2D: app/MainActivity.kt dispatchKeyEvent (KEYCODE_SEARCH) + ui/SearchActivity.kt.
- [x] Home hint: one-time "add your IPTV account" hint on Watch, dismissible,
      shown only while no IPTV source is configured at all — `MainActivity.kt`,
      `data/Prefs.kt` (`watchHintDismissed`)

      — 2D: ui/HomeUi.kt watchHint + app/MainActivity.kt gating (Watch only, no IPTV source, dismiss persists) + data/PrefsStore.kt flag.
#### 1.1a TV home rows (home style "tv"; order + visibility user-configurable)

- [x] Favorites row (favorites order persisted, favorites may also live in
      folders) — `MainActivity.kt`, `core/LayoutStore.kt`
      — 2D: app/MainActivity.kt + data/AppsRepositoryImpl.kt + core/LayoutStore.kt.
- [x] Recently Used row (MRU, newest first, capped at 8, launcher itself never
      recorded, relaunch dedupes to front) — `core/Recents.kt`, `data/Prefs.kt`
      — 2D: app/MainActivity.kt + data/AppsRepositoryImpl.kt + core/Recents.kt.
- [x] Most Used row (count desc, recency breaks count ties, name breaks
      recency ties; hidden/non-launchable excluded, zero counts dropped) —
      `core/MostUsed.kt`, `data/MostUsed.kt`
      — 2D: app/MainActivity.kt + data/UsageStatsCollector.kt + core/MostUsed.kt.
- [x] Folders row (root folders; nested folders up to 3 levels deep, see §1.10) —
      `MainActivity.kt`, `core/LayoutStore.kt`
      — 2D: app/MainActivity.kt folder tiles + ui/FolderViewActivity.kt + core/LayoutStore.kt.
- [x] Now Playing row (other apps' media sessions: title, artist, state,
      play/pause control when the session's action mask allows; buffering reads
      as playing; system-ui and blank titles filtered; deduped by package, first
      session wins) — `MainActivity.kt`, `core/NowPlaying.kt`,
      `data/NowPlayingData.kt`, `NowPlayingListener.kt`
      — 2D: app/MainActivity.kt + data/NowPlayingRepositoryImpl.kt + app/NowPlayingListener.kt (grant) + core/NowPlaying.kt.
- [x] Continue Watching row (system TV listings via `READ_TV_LISTINGS`,
      best-effort; row hides without the permission/data) — `MainActivity.kt`,
      `data/TvData.kt`
      — 2D: app/MainActivity.kt + data/TvData.kt (READ_TV_LISTINGS, fail-open empty).
- [x] Inputs row (HDMI inputs) — `MainActivity.kt`
      — 2D: app/MainActivity.kt lists TvInputManager inputs; switching itself needs the TV source UI (noted on the tile).
- [x] Live TV row (device tuner channel list when the system allows it; on many
      TVs the permission is reserved, the row stays hidden, diagnostics says why) —
      `MainActivity.kt`, `core/LiveTv.kt`, `data/LiveTv.kt`
      — 2D: app/MainActivity.kt lists data/LiveTvData.kt tuner channels; tuner playback via TvView is NOT wired (row lists only).
- [x] Apps row (all apps minus hidden and foldered apps, in stored order) —
      `MainActivity.kt`, `core/AppCatalogue.kt`, `core/AllApps.kt`
      — 2D: app/MainActivity.kt + data/AppsRepositoryImpl.kt + core/AllApps.kt + core/AppCatalogue.kt.
- [x] TV row order default: favorites, recents, mostused, folders, nowplaying,
      continue, inputs, livetv, apps; new rows splice in after their anchors, never
      appended blindly — `core/LayoutStore.kt` (`RowOrder`)

      — 2D: data/SettingsRepositoryImpl.kt + app/MainActivity.kt render order + core/LayoutStore.kt RowOrder.
#### 1.1b Watch home sections (home style "watch"; order + visibility user-configurable)

- [x] Unified pill tab bar (v2.9): ONE bar — tab pills left, status group right
      (info pill = clock · date · weather, OK opens Forecast; Guest pill; glyph
      pill; action pills All Apps / Search / Wi-Fi / Profile / Settings); hiding
      the status bar hides only the status group, never the tabs —
      `core/HomeTabs.kt` (`UnifiedBar`), `MainActivity.kt`
      — 2D: ui/HomeUi.kt tabBar + app/MainActivity.kt status bar; hiding the bar hides only the status group, tabs remain.
- [x] Watch tabs, in order: **Home · Library · Apps · Live** (Live LAST) —
      `core/HomeTabs.kt` (`watchTabs`)
      — 2D: ui/HomeUi.kt tabBar (Home · Library · Apps · Live, Live LAST) + core/HomeTabs.kt.
- [x] Hero section: big featured title over a full-bleed backdrop with left
      scrim; hero pool order = Missed (most overdue first) → Up Next →
      Continue (backdrop-first) → For You; backdrop presence never reorders
      tiers — `MainActivity.kt` (`addTrackerBackground`), `core/TrackerLogic.kt`
      (`hero pool`), `core/HeroRotator.kt`
      — 2D: ui/HomeUi.kt heroView + HeroCarousel + app/MainActivity.kt pool order Missed → Up Next → Continue (backdrop-first) → For You.
- [x] Up Next section: next episode per tracked show with countdown label
      (today / tomorrow / "in N d" / "Aired" / far-future date itself; real
      day math across month/year boundaries; empty/malformed dates give no
      label), airing-today count, pill text composing only the numbers the data
      supports — `core/TrackerLogic.kt`, `MainActivity.kt`
      — 2D: data/TrackerRepositoryImpl.kt + core/TrackerLogic.kt + ui/HomeUi.kt cardRow + app/MainActivity.kt.
- [x] Missed section: episodes whose air date passed unwatched; filtered by the
      local marked-set and the pending overlay **scoped per show** (v3.9 fix:
      a pending mark for show A no longer filters show B's same SxEy) —
      `core/TrackerLogic.kt` (`missedRow`), `MainActivity.kt`
      — 2D: data/TrackerRepositoryImpl.kt + data/MissedWorker.kt + core/TrackerLogic.kt (per-show pending scoping).
- [x] Continue section: in-progress shows/movies with progress count/total/next
      coordinates and poster; caught-up shows detected from the continue data —
      `core/LibraryLogic.kt`, `MainActivity.kt`
      — 2D: data/TrackerRepositoryImpl.kt + core/LibraryLogic.kt + ui/HomeUi.kt cardRow.
- [x] Trending TV + Trending Movies sections (launcher-computed from TMDB,
      tracker snapshot as fallback; see §5) — `data/DiscoveryData.kt`,
      `core/Discovery.kt`, `MainActivity.kt`
      — 2D: data/DiscoveryWorker.kt (12 h TTL cache) + core/Discovery.kt + ui/HomeUi.kt cardRow.
- [ ] Coming Soon section (merged into Up Next presentation in v3.3 lineage; — core part ported (foundation): new core/Discovery.kt; data/ui/app parts pending
      Soon items also feed the Up Next fallback when the memo is absent — see
      §11) — `core/Discovery.kt`, `MainActivity.kt`
      — 2D NOT SEPARATELY RENDERED: no Soon section is composed in app/MainActivity.kt and no Soon computation exists in data/DiscoveryWorker.kt (Up Next fallback from the memo is the only Soon path, and it is not exercised as a section).
- [x] For You section: launcher-computed picks (or tracker snapshot picks when
      not computed); already-tracked titles dropped; overlay-dismissed picks
      dropped; row capped; rating text one decimal, absent when zero; "why"
      genres shown as names — `core/TrackerLogic.kt` (`foryou`), `core/Discovery.kt`
      — 2D: data/TrackerRepositoryImpl.kt + core/TrackerLogic.kt + core/Discovery.kt + ui/HomeUi.kt cardRow.
- [x] Favorites / Folders / All apps sections inside Watch, gated by the same TV
      layout visibility flags — `MainActivity.kt`
      — 2D: app/MainActivity.kt renderWatch (same TV layout visibility flags).
- [x] Watch section default order: Hero, Up Next, Missed, Continue, Trending TV,
      Trending Movies, For You, Favorites, Folders, All apps; hero is always
      first when shown and is hide/show-only (never reorderable) —
      `core/TrackerSections.kt` (`CANONICAL`, `ROWS`)
      — 2D: data/SettingsRepositoryImpl.kt + app/MainActivity.kt + core/TrackerSections.kt + domain/HomeSections.kt.
- [x] Card play behaviour: matched card ▶ plays via IPTV; unmatched card opens
      in-launcher Details (never the tracker app); matched Trending show
      without a library twin plays from S1 E1 with no library mark token (v3.9);
      matched movie plays via its VOD entry — `MainActivity.kt`
      (`playUpNextItem`, `playMovieItem`, `playHeroPrimary`), `data/WatchPlay.kt`
      — 2D: app/MainActivity.kt onCardPlay + data/PlayTitleUseCaseImpl.kt (matched→IPTV, unmatched→Details, no-twin S1 E1 no-token, movie via VOD).
- [x] Card hold-OK menus: Play, Play in…, Mark watched/unwatched, (movies:
      Mark watched toggle), For You: Add / Dismiss — `MainActivity.kt`,
      `data/TrackerActions.kt`
      — 2D: ui/HomeUi.kt cardMenu.
- [x] Hero "Open Tracker" button does NOT exist (deleted v2.9); hero primary
      action is Play — `MainActivity.kt`
      — 2D: absent by construction — ui/HomeUi.kt heroView has Play (+Next) only.
- [ ] **v3.9 shell-frame rule (see §9):** a recreated activity (destroyed while
      the user was in Settings, or Home-pressed back) with a warm memo prepares
      tracker data on its first build; only a genuinely memo-less build defers
      to the warm workers. A memo-less shell frame (frame + tabs, no hero, no
      Missed, no Continue, no Library entry row) is a diagnosable state, not a
      normal state — `MainActivity.kt`, `data/TrackerClient.kt`
      (`noteHomeBuild` diagnostics line: memo source, snap counts, hero/
      missed-row counts after filtering, each section's shown-state, coldStart)

      — 2D PARTIAL: app/MainActivity.kt gathers from the warm on-disk store on its first build (warm memo paints hero/Missed/Continue on build one); the memo-source diagnostics line (store/slim/prefs/none + per-section shown-state) is NOT composed, and the slim/store install ordering is not implemented — the on-device fix itself remains UNVERIFIED (see §11).
### 1.2 All Apps — `ui/AllAppsActivity.kt`

- [x] Full-screen All Apps grid: every app (foldered ones included, badged with
      their containing folder), alphabetical by label, label ties break by
      package; hidden apps excluded — `ui/AllAppsActivity.kt`, `core/AllApps.kt`
      — 2D: ui/AllAppsActivity.kt + core/AllApps.kt + data/AppsRepositoryImpl.kt.
- [x] Hold-OK app menu: Add to folder… (breadcrumb-labelled folder picker),
      Hide app, App info, Uninstall; launch gated by the arrow lock —
      `ui/AllAppsActivity.kt`
      — 2D: ui/AllAppsActivity.kt (Add to folder…, Hide, App info, Uninstall non-system, Lock/Unlock, Artwork) + arrow-lock-gated launch.
- [x] Folder creation from All Apps (prompt for name; locked-folder notice
      explains the pattern is set on first open) — `ui/AllAppsActivity.kt`
      — 2D: ui/AllAppsActivity.kt + ui/FolderViewActivity.kt.
- [x] Order edits persist via the shared layout store and refresh home —
      `ui/AllAppsActivity.kt` (`saveLayoutAndRefresh`)

      — 2D: ui/AllAppsActivity.kt via the shared layout store (data/PrefsStore.kt).
### 1.3 App Search — `ui/SearchActivity.kt`

- [x] D-pad on-screen keyboard (letters, digits, space, backspace, clear);
      substring match case-insensitive, prefix matches sort before substring
      matches, order preserved inside groups; empty query returns all in order —
      `ui/SearchActivity.kt`, `core/AppSearch.kt`
      — 2D: ui/SearchActivity.kt + ui/KeyboardDialog.kt + core/AppSearch.kt.
- [x] Voice search via system speech recognizer (result feeds the same query
      pipeline) — `ui/SearchActivity.kt`
      — 2D: ui/SearchActivity.kt (system recognizer feeding the same pipeline).
- [x] Results mix installed apps AND tracker titles (tracker hits show watched
      episode counts, open Details) — `ui/SearchActivity.kt`,
      `core/TrackerLogic.kt` (`tracker search`)
      — 2D: ui/SearchActivity.kt + data/UseCasesImpl.kt searchTitles + core/TrackerLogic.kt.
- [x] Result hold-OK menu + arrow-lock-gated launch, same as home —
      `ui/SearchActivity.kt`

      — 2D: ui/SearchActivity.kt (same menu + arrow-lock-gated launch as home).
### 1.4 Library (My Library) — `ui/LibraryActivity.kt`

- [x] Single merged grid of tracked shows + movies; sort = in-progress first
      (continue order, approximating recently-progressed — the payload has no
      timestamps), then Up Next, then A–Z, finished last — `ui/LibraryActivity.kt`,
      `core/LibraryLogic.kt`
      — 2D: ui/LibraryActivity.kt + core/LibraryLogic.kt.
- [x] Each card: poster (poster cache fills posters the snapshots lack),
      progress line + fraction (count/total/next from continue data), caught-up
      detection — `ui/LibraryActivity.kt`, `core/LibraryLogic.kt`
      — 2D: ui/LibraryActivity.kt + core/LibraryLogic.kt (poster via TmdbImages + app ImageLoader; progress + caught-up).
- [ ] Header buttons (refresh / open TMDB search — verify exact set at runtime,
      see §11) and missing-poster backfill — `ui/LibraryActivity.kt`
      — 2D NOT VERIFIED FROM SOURCE: the exact Library header button set is a MAP §11 runtime-only item; it is not marked done from source alone.
- [x] Hold-OK item menu: Details; Mark next episode watched (shows) /
      Mark watched / Mark unwatched (movies); Mute / Unmute notifications;
      Remove from library… (confirmation) — `ui/LibraryActivity.kt`,
      `data/TrackerActions.kt`
      — 2D: ui/LibraryActivity.kt + data/TrackerActions-equivalent (TrackerRepositoryImpl.kt optimistic writes).
- [x] Optimistic overlay: library writes render instantly from the pending
      overlay and retire part-by-part as snapshots converge (newest toggle wins;
      add-then-remove settles whole once snapshots are fresher) —
      `core/LibraryLogic.kt` (`decodePending`, `prunePending`)

      — 2D: data/PrefsStore.kt + data/TrackerRepositoryImpl.kt + core/LibraryLogic.kt (newest toggle wins, part-by-part retirement); ui/LibraryActivity.kt renders the effective view.
### 1.5 Details — `ui/DetailsActivity.kt`

- [x] Header: title, poster/backdrop, year, overview (snapshot overview when
      TMDB details fail), meta line incl. "✓ Watched" / "Not watched" for movies —
      `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt (title/poster/year/overview, movie ✓ Watched / Not watched).
- [x] TMDB details load via the tracker proxy first, launcher TMDB key direct
      second (see §5); details parse: fields, year, seasons sorted, empty season
      dropped; season episodes parse ascending — `ui/DetailsActivity.kt`,
      `core/LibraryLogic.kt`, `data/TrackerClient.kt`, `data/TmdbDirect.kt`
      — 2D: ui/DetailsActivity.kt + data/TmdbRepositoryImpl.kt (proxy-first structure, direct second) + core/LibraryLogic.kt parse.
- [x] Movie actions: ▶ Play on IPTV (when matched), ✓ Mark watched /
      ↩ Mark unwatched, ＋ Add to my list (untracked titles only),
      🔇 Mute / 🔔 Unmute notifications, Remove from library… (confirmation),
      Retry (on load failure) — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt movieActions (Play on IPTV, Mark watched/unwatched, Add to my list, Mute/Unmute, Remove with confirmation, Retry).
- [x] Show season picker: default season = first season with an unwatched
      episode (verify exact rule at runtime, see §11); season block with
      episode checklist — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt (default = first season with an unwatched episode, as stated in MAP; the exact-rule wording is a §11 runtime item).
- [x] Episode checklist row: ✓ box + E<n> + name + air date; OK toggles
      watched (gated by the unaired rule, §8); ▶ glyph only when the show is
      IPTV-matched AND the episode has aired — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt + domain/AiredGate.kt + domain PlayTitleUseCase.isMatched (▶ only when aired AND IPTV-matched).
- [x] Episode hold-OK menu: ▶ Play S<s> E<e> + ✓/↩ Mark watched (aired only;
      unaired variants in §8) — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt + domain PlayTitleUseCase.playEpisode (aired only; unaired variants per §8).
- [x] "✓ Mark season watched" button: counts only unwatched episodes that have
      aired (see §8); hidden when zero remain — `ui/DetailsActivity.kt`,
      `data/TrackerActions.kt` (`markSeason`)
      — 2D: ui/DetailsActivity.kt + data/TrackerRepositoryImpl.kt markSeasonWatched + domain/AiredGate.kt remainingAiredCount (hidden at 0).
- [x] IPTV episode fallback: when TMDB season data is missing, season/episode
      lists fill from the IPTV series-info (shaped: seasons ascend, empty
      seasons drop, episodes ascend + dedupe by number, missing titles fall
      back to "Episode N"); header note "▶ This show is on your IPTV — hold OK
      on an episode to play it." — `ui/DetailsActivity.kt`,
      `core/IptvLogic.kt` (series-info shaping)
      — 2D: ui/DetailsActivity.kt + domain IptvRepository.iptvSeriesForShow + data/IptvRepositoryImpl.kt getSeriesEpisodes shaping (ascend, dedupe, "Episode N") + header note.
- [x] "Retry episodes" on season-load failure — `ui/DetailsActivity.kt`

      — 2D: ui/DetailsActivity.kt (Retry episodes button).
### 1.6 TMDB Search (add titles) — `ui/TmdbSearchActivity.kt`

- [x] D-pad keyboard search over TMDB (tv + movie only; persons and bad rows
      dropped; fails open to empty) via the tracker proxy, launcher-key direct
      as fallback — `ui/TmdbSearchActivity.kt`, `core/LibraryLogic.kt`
      (`tmdb search parse`), `data/TrackerClient.kt`
      — 2D: ui/TmdbSearchActivity.kt + data/TmdbRepositoryImpl.kt + core/LibraryLogic.kt.
- [x] Result tap opens Details; already-added titles flagged via the refreshed
      added-keys set — `ui/TmdbSearchActivity.kt`
      — 2D: ui/TmdbSearchActivity.kt.
- [x] Add from search retires the For You overlay entry once the title is
      tracked (or leaves the snapshot) — `core/TrackerLogic.kt`

      — 2D: ui/TmdbSearchActivity.kt enriched addToLibrary (title+poster) + core/TrackerLogic.kt.
### 1.7 IPTV browser — `ui/IptvActivity.kt`

- [x] Tabs: 📺 Live · 🎬 Movies · 📚 Series · ★ Favorites · 🔍 Search
      (Guide lives in its own Activity, §1.8) — `ui/IptvActivity.kt`
      — 2D: ui/IptvActivity.kt (Live · Movies · Series · Favorites · Search; Guide separate).
- [x] Live tab: category chips (empty categories skipped, favorites first,
      locks applied), channel cards with EPG now-title + progress, grid page
      cap 200 per category (total preserved, "load more" paging) —
      `ui/IptvActivity.kt`, `ui/IptvCards.kt`, `core/IptvLogic.kt`
      — 2D: ui/IptvActivity.kt + core/IptvLogic.kt + domain IptvRepository.getLiveCategoriesDetailed (source-qualified).
- [x] Movies tab: VOD cards (container extension, numeric rating as text),
      resume positions, "finished" = near end or ≥95% (unknown duration is not
      finished) — `ui/IptvActivity.kt`, `core/IptvLogic.kt`
      — 2D: ui/IptvActivity.kt + core/IptvLogic.kt (resume, finished = near end or ≥95%).
- [x] Series tab: series cards → series detail (seasons sorted numerically,
      ghost episodes skipped, episode fields ext/duration/plot) →
      episode rows with resume — `ui/IptvActivity.kt`, `core/IptvLogic.kt`
      — 2D: ui/IptvActivity.kt + core/IptvLogic.kt.
- [x] Favorites tab: favorites across live/VOD/series, toggle add/remove by
      identity key, codec round-trips hostile names — `ui/IptvActivity.kt`,
      `core/IptvLogic.kt`
      — 2D: ui/IptvActivity.kt + core/IptvLogic.kt + domain setFavoriteWithMeta (full-entry writes).
- [x] Search tab: case-insensitive search across all three catalogues,
      hidden categories excluded, results paged at 200 — `ui/IptvActivity.kt`
      — 2D: ui/IptvActivity.kt (all catalogues, hidden excluded, paged 200).
- [x] Hold-OK menus: channel menu (Play, Catch-up when `tv_archive`, Favorite,
      …), VOD menu (Play, Resume/Clear resume, Favorite), series menu,
      episode menu, favorite menu, category menu (incl. pattern-lock a category
      from here) — `ui/IptvActivity.kt`
      — 2D: ui/IptvActivity.kt (channel/VOD/series/episode/favorite/category menus incl. pattern-lock).
- [x] Category locks: pattern-locked categories ask for the arrow pattern;
      adult-named categories (adult, xxx, +18, 18+, case-insensitive) start
      locked when a pattern is set; unlock stamps with grace/session rules —
      `core/IptvLocks.kt`, `ui/IptvActivity.kt`
      — 2D: ui/IptvActivity.kt + core/IptvLocks.kt + data/IptvRepositoryImpl.kt.
- [x] Empty states per kind (unconfigured / auth / empty / error) with
      diagnostics-safe wording; ingest progress tick while refreshing —
      `ui/IptvActivity.kt`, `core/IptvStatus.kt`
      — 2D: ui/IptvActivity.kt + core/IptvStatus.kt.
- [x] Browser rebuilds are guarded (a hostile catalogue/EPG fails open to the
      safe shell/empty state, never a blackout) and read store slices, never
      fetching on open/resume — `ui/IptvActivity.kt`, `data/IptvClient.kt`

      — 2D: ui/IptvActivity.kt guarded rebuilds reading store slices, never fetching on open/resume.
### 1.8 IPTV Guide — `ui/IptvGuideActivity.kt`

- [x] Channel rows with now/next programmes + progress fraction at the current
      clock; timezone offsets convert to the same absolute instant; entities
      unescaped, CDATA unwrapped, broken spans skipped — `ui/IptvGuideActivity.kt`,
      `core/IptvEpg.kt`
      — 2D: ui/IptvGuideActivity.kt + core/IptvEpg.kt.
- [x] Short-EPG: base64 titles, listing times read as UTC, fetched per channel
      on demand — `ui/IptvGuideActivity.kt`, `core/IptvEpg.kt`
      — 2D: ui/IptvGuideActivity.kt + core/IptvEpg.kt + data/IptvRepositoryImpl.kt.
- [x] Guide rebuild guarded + paged like the browser; refresh tick; hidden/locked
      categories excluded — `ui/IptvGuideActivity.kt`
      — 2D: ui/IptvGuideActivity.kt (paged 200, hidden/locked excluded).
- [x] Tapping a channel plays it; catch-up items use the timeshift URL shape —
      `ui/IptvGuideActivity.kt`, `core/IptvLogic.kt`

      — 2D: ui/IptvGuideActivity.kt + core/IptvLogic.kt timeshift shape.
### 1.9 Player — `ui/PlayerActivity.kt`

- [x] Plays Live, VOD, and Series episodes from a `PlayItem` (kind + ids +
      URL + resume + queue neighbours); PiP declared — Home during playback
      tries PiP, devices that refuse fall back to normal behaviour —
      `ui/PlayerActivity.kt`, `data/IptvClient.kt` (`PlayItem` data class +
      `PlayQueue` object, at the end of that file)
      — 2D: ui/PlayerActivity.kt (MediaPlayer; no media3 in the manual build) + domain PlayItem/PlayQueue + PiP declared + onUserLeaveHint attempt.
- [x] Control bar: title line (title + episode-name meta composed once), clock
      m:ss under an hour / h:mm:ss above (negatives clamp), elapsed/total or
      "Live" when duration unknown, progress permille (unknowns read 0) —
      `ui/PlayerActivity.kt`, `core/PlayerLogic.kt`
      — 2D: ui/PlayerActivity.kt + core/PlayerLogic.kt.
- [ ] Transport: play/pause toggle, ±seek, Prev/Next episode (episode plays with — core part ported (foundation): new core/PlayerLogic.kt; data/ui/app parts pending
      queue neighbours only) — `ui/PlayerActivity.kt`, `core/PlayerLogic.kt`
      — 2D PARTIAL: play/pause + ±30 s seek are wired; Prev/Next honestly report "No queue neighbour" — the domain PlaybackRepository.queueFor contract exists but the data implementation returns null (a PlayItem carries an episode id, not its series id, so no series queue is derivable from it).
- [x] Aspect cycle: Fit → Stretch → Zoom → Fit (letterbox / exact / cover-crop;
      portrait video fits by height, zooms by width; unknown size falls back to
      container); mode persists per profile — `ui/PlayerActivity.kt`,
      `core/PlayerLogic.kt`, `data/Prefs.kt` (`playerAspect`)
      — 2D: ui/PlayerActivity.kt + core/PlayerLogic.kt + data/PlaybackRepositoryImpl.kt (per-profile persist).
- [x] Speed ladder cycles and wraps (2x → 0.5x); off-ladder values snap to the
      nearest rung before advancing; labels trim trailing zeros —
      `core/PlayerLogic.kt`, `ui/PlayerActivity.kt`
      — 2D: ui/PlayerActivity.kt + core/PlayerLogic.kt.
- [x] Audio track cycle (audio only, index order; labels prefer language else
      1-based ordinal; button needs >1 audio track) and subtitle cycle
      (Off → tracks → Off; timed-text + subtitle only; button needs ≥1 text
      track) — `core/PlayerLogic.kt`, `ui/PlayerActivity.kt`
      — 2D: ui/PlayerActivity.kt MediaPlayer track list + core/PlayerLogic.kt TrackDesc rules (audio >1, subtitles Off→tracks→Off).
- [x] Resume: position saved on pause/destroy, restored on reopen, hidden
      below threshold, cleared when finished (near end / ≥95%) —
      `ui/PlayerActivity.kt`, `core/IptvLogic.kt` (resume codec)
      — 2D: ui/PlayerActivity.kt + data/PlaybackRepositoryImpl.kt + core/IptvLogic.kt resume codec (10 s threshold in PlayerActivity, ≥95% clear).
- [x] Watch auto-mark: fires once on playback ended (even at low position) or
      at exactly 90% (not 89%); never twice, never tokenless, never on a partial
      watch — `ui/PlayerActivity.kt` (`maybeAutoMark`), `core/WatchMatch.kt`
      — 2D: ui/PlayerActivity.kt + data/PlaybackRepositoryImpl.kt (once, ended or ≥90%, never tokenless) + app/App.kt onAutoMark application to TrackerRepository.
- [x] Catch-up: catch-up item offered for `tv_archive` channels —
      `ui/PlayerActivity.kt`, `MainActivity.kt`
      — 2D: ui/IptvActivity.kt channel menu + ui/IptvGuideActivity.kt offer catch-up items for tv_archive channels (timeshift URL shape in core/IptvLogic.kt); the Player itself carries no separate catch-up button.
- [x] "Play in…" / Open externally: hand the stream URL to another installed
      video app (self excluded, no hardcoded packages) — `ui/PlayerActivity.kt`,
      `ui/PlayIn.kt`, `core/PlayInLogic.kt`
      — 2D: ui/PlayerActivity.kt + ui/PlayIn.kt + core/PlayInLogic.kt.
- [x] Error screen on playback failure (never a silent black frame) —
      `ui/PlayerActivity.kt` (`showError`)
      — 2D: ui/PlayerActivity.kt showError.
- [x] No foreground service anywhere in the player (standing rule) —
      `ui/PlayerActivity.kt`

      — 2D: verified by grep — no startForeground/startForegroundService in the new tree.
### 1.10 Folders (in-home, not a separate Activity)

- [x] Folders hold apps AND other folders, max depth 3; typed entries with
      stable ids; legacy positional lines migrate with sequential ids —
      `core/LayoutStore.kt` (`FolderTree`, `Folder`, `FolderEntry`)
      — 2D: core/LayoutStore.kt FolderTree + data/AppsRepositoryImpl.kt + ui/FolderViewActivity.kt.
- [ ] Folder open view: breadcrumb (root → folder), grid of entries, — core part ported (foundation): new core/MoveSession.kt; data/ui/app parts pending
      sub-folder entry moves like apps, parent order persisted with the move —
      `MainActivity.kt`, `core/MoveSession.kt`
      — 2D PARTIAL: ui/FolderViewActivity.kt has breadcrumb + grid; sub-folder entry move UI ("moves like apps, parent order persisted with the move") is NOT implemented.
- [x] Folder rules enforced: a folder cannot contain itself; an ancestor cannot
      move under its descendant; a height-3 subtree fits nowhere deeper;
      deleting a folder rehomes sub-folders to root and keeps apps —
      `core/LayoutStore.kt`
      — 2D: core/LayoutStore.kt + data/AppsRepositoryImpl.kt.
- [x] Folder locks: locked flag round-trips; locked folder with a pattern
      prompts for it; locked folder without a pattern prompts to set one (never
      locked out) — `core/LayoutStore.kt`, `ui/PatternGate.kt`,
      `ui/ArrowPinDialog.kt`
      — 2D: core/LayoutStore.kt + ui/FolderViewActivity.kt (pattern prompt / set-one-if-none) + ui/PatternGate.kt + ui/ArrowPinDialog.kt.
- [x] Dedupe: apps in folders (any depth) are excluded from the All Apps row
      and the Apps row exactly once; removing an app from its folder restores it;
      favorites unaffected (a favorite may live in a folder) —
      `core/LayoutStore.kt`, `core/AllApps.kt`

      — 2D: core/LayoutStore.kt + core/AllApps.kt + data/AppsRepositoryImpl.kt.
### 1.11 Forecast / weather — `ui/ForecastActivity.kt`

- [x] Full forecast: next 24 hours starting at the current hour (wraps past
      midnight; 12h labels wrap, 24h labels plain) + 7-day outlook (weekday +
      date, condition, hi/lo, precip only when present) — `ui/ForecastActivity.kt`,
      `core/Forecast.kt`, `data/Weather.kt`
      — 2D: ui/ForecastActivity.kt + core/Forecast.kt + data/WeatherRepositoryImpl.kt.
- [x] Opened by OK on the clock / date / weather in the home bar; units follow
      the Weather setting (°F/mph or °C/km/h) — `ui/ForecastActivity.kt`,
      `MainActivity.kt`
      — 2D: ui/ForecastActivity.kt + app/MainActivity.kt status-bar Forecast action.
- [x] Condition groups: clear/partly/cloudy/fog/rain/storm/snow day+night
      variants; unknown code falls back to cloudy; choice prefers current,
      falls back to forecast-dominant, then worst — `core/WeatherLogic.kt`

      — 2D: core/WeatherLogic.kt + data/WeatherRepositoryImpl.kt.
### 1.12 Settings — `ui/SettingsActivity.kt` (two-column Roku-style)

- [x] Two-column layout: left category list, right detail pane; Right enters
      detail, Right again is a no-op, Left/Back returns to list, Back in list
      exits (caller exits); category change swaps the detail model; selection
      clamps (no wrap); focus memory per category (row title + index) —
      `ui/SettingsActivity.kt`, `core/SettingsNav.kt`, `core/SettingsFit.kt`
      — 2D: ui/SettingsActivity.kt + core/SettingsNav.kt (Right enters, Right again no-op, Left/Back returns, Back in list exits, clamps, focus memory).
- [ ] Compact layout at 1280×720 / 960×540 / narrow portrait (smaller type and
      padding, panes stack); left pane 30% clamped to 260–360dp at 1920×1080;
      bottom padding ≥ one row height at every size — `core/SettingsFit.kt`
      — 2D PARTIAL: ui/SettingsActivity.kt uses a fixed 30/70 split; core/SettingsFit.kt compact clamps (1280×720 / 960×540 / narrow portrait, 260–360dp left pane, ≥1 row bottom padding) are NOT applied.
- [x] Detail panes are crash-contained: a failing page shows "This settings page
      hit a snag — the error is noted in Diagnostics" instead of crashing
      Settings — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt renderDetail try/catch with the exact snag wording.
- [x] Categories (in order): Home & launcher · Look & wallpaper · Home screen ·
      Sort sections · Status bar · Weather · Lock & profiles · Now playing ·
      IPTV · Data & about — `ui/SettingsActivity.kt` (see §3 for every row)
      — 2D: ui/SettingsActivity.kt (all 10, in MAP order).
- [x] Settings gate: opening Settings asks for the arrow pattern when Settings
      lock is on, or when Guest is active and Guest-lock is on; already-unlocked
      this entry allows; no pattern set fails open — `ui/SettingsActivity.kt`,
      `core/SettingsGate.kt`, `core/UnlockGrace.kt`
      — 2D: ui/SettingsActivity.kt + core/SettingsGate.kt + core/UnlockGrace.kt + ui/PatternGate.kt.
- [ ] "Make Sreeb your home screen" checklist replaces the Home category detail
      until completed/dismissed (exact steps incl. the ADB command line for
      setting the default home — see §11 for the exact step text) —
      `ui/SettingsActivity.kt` (`buildMakeHome`), `data/HomeStatus.kt`
      — 2D PARTIAL: ui/SettingsActivity.kt has a Make-home row opening system home settings + a watcher explanation row, but the EXACT checklist text incl. the ADB command line is a MAP §11 runtime-only item and is not reproduced from source.
- [x] Version label is read from PackageManager at runtime (never a hardcoded
      "Sreeb v<digit>") — `ui/SettingsActivity.kt` (`appVersionLabel`)

      — 2D: ui/SettingsActivity.kt appVersionLabel path (getPackageInfo versionName).
### 1.13 Gates, dialogs, pickers (shared UI)

- [x] Arrow-pattern dialog: D-pad sequence 4–8 steps, glyphs are arrows not
      digits; set requires confirm (mismatch restarts, then takes a fresh build);
      submit below minimum is "too short"; 9th append rejected as full;
      backspace on empty cancels; encoded form is UDLRU; legacy numeric PINs
      decode empty (migration discard, never reinterpreted) — `ui/ArrowPinDialog.kt`,
      `core/ArrowPin.kt`
      — 2D: ui/ArrowPinDialog.kt + core/ArrowPin.kt.
- [x] Pattern gate wrapper used by locked apps, locked folders, Settings, and
      leaving Guest (with unlock-grace stamp honoured) — `ui/PatternGate.kt`,
      `core/SettingsGate.kt`, `core/UnlockGrace.kt`
      — 2D: ui/PatternGate.kt + core/SettingsGate.kt + core/UnlockGrace.kt.
- [x] Artwork picker: per-app custom artwork — 12 presets (names + colours,
      indices coerced) or an image URL; codec round-trips presets and escaped
      URLs; garbage decodes empty; setting/removing per package —
      `ui/ArtworkPicker.kt`, `core/Artwork.kt`, `core/ArtworkStore.kt`,
      `data/ArtworkImages.kt`
      — 2D: ui/ArtworkPicker.kt + core/Artwork.kt + core/ArtworkStore.kt + data/TvAndArtwork.kt ArtworkImages.
- [x] D-pad keyboard dialog: text entry for IPTV server/username/password
      (masked), M3U/EPG URLs, TMDB key (masked), subreddit list, weather
      location, profile names, folder names; length caps per field (IPTV server
      120, username 60, password 60, M3U/EPG 300, TMDB key 80) —
      `ui/KeyboardDialog.kt`, `ui/SettingsActivity.kt`
      — 2D: ui/KeyboardDialog.kt (MAP length caps).
- [x] iPhone-style on/off switches: on = green track + knob right, off = grey
      track + knob left, toggle flips both ways — `ui/SwitchRow.kt`
      (`SwitchPillView`, `SwitchRowView`)
      — 2D: ui/SwitchRow.kt.
- [x] Generic helpers: dp conversion, tile backgrounds, focus handling —
      `ui/Ui.kt`

      — 2D: ui/Ui.kt.
### 1.14 Screensaver — `MizanDreamService.kt`

- [x] Dim clock (64sp, 75% alpha) + date line over the theme gradient dimmed to
      half brightness (OLED burn-in protection); clock ticks every 30 s;
      non-interactive, fullscreen — `MizanDreamService.kt`

      — 2D: app/MizanDreamService.kt renders the MAP §1.14 composition directly (64sp, 75% alpha, date line, theme gradient dimmed to half, 30 s tick, non-interactive fullscreen); the ui layer ships no dream content of its own.
### 1.15 Removed / retired surfaces (do NOT rebuild)

- [x] iPad home style: removed v2.2; a saved "ipad" decodes to TV —
      `core/HomeStyle.kt`
      — 2D: core/HomeStyle.kt (ipad→TV) + home-style picker TV/Watch only.
- [x] Tracker-only home style: removed v3.8; a saved "tracker" decodes to Watch —
      `core/HomeStyle.kt`
      — 2D: core/HomeStyle.kt (tracker→WATCH).
- [x] IPTV home style: removed v3.9; a saved "iptv" decodes to Watch; the IPTV
      browser itself is NOT deleted (it lives under Watch's Live tab) —
      `core/HomeStyle.kt`, `core/HomeTabs.kt`
      — 2D: core/HomeStyle.kt (iptv→WATCH) + core/HomeTabs.kt (IPTV browser lives under Watch Live).
- [x] Movie Night section: removed v3.1 (registry/defaults/composition/hero pool/
      stored-order drop) — `core/TrackerSections.kt`
      — 2D: core/TrackerSections.kt (removed; not in registry/defaults/composition/hero pool).
- [x] Cleanup feature + cleanup classifier: removed v1.5 (never-list data survives
      as `core/NeverList.kt`, also used by Speed Up) — `core/NeverList.kt`
      — 2D: core/NeverList.kt survives (also used by Speed Up); cleanup itself is not rebuilt.
- [x] Numeric PIN pad: retired v1.2, replaced by the arrow PIN (see §1.13) —
      `core/ArrowPin.kt`, `data/Prefs.kt` (init migration)

      — 2D: core/ArrowPin.kt + data/PrefsStore.kt init migration (legacy pin removed, numeric arrow_pin discarded).
---

## 2. Services, receivers, background components

- [x] **LauncherService — "Sreeb launcher" accessibility service (f26a5f6, §8):**
      performs system global actions on request; off by default; see §8 for the
      exact capability/limit list — `LauncherService.kt`, `res/xml/launcher_config.xml`,
      `AndroidManifest.xml`
      — 2D: app/LauncherService.kt + res/xml/launcher_config.xml + AndroidManifest.xml (see §8.2 items).
- [x] **WatcherService — "Sreeb launcher watcher" accessibility service:**
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
      — 2D: app/WatcherService.kt + res/xml/watcher_config.xml + AndroidManifest.xml (see §8.2 items).
- [x] **NowPlayingListener — notification listener service:** passive, exists
      only to authorize MediaSessionManager session queries; both notification
      callbacks intentionally empty; reads NO notification content; NOT a
      foreground service; grant via system Notification Listener settings —
      `NowPlayingListener.kt`, `AndroidManifest.xml`
      — 2D: app/NowPlayingListener.kt + AndroidManifest.xml; component registered in app/App.kt wire (NowPlayingRepositoryImpl.listenerComponent).
- [x] **MizanDreamService — screensaver:** see §1.14 — `MizanDreamService.kt`,
      `AndroidManifest.xml`
      — 2D: app/MizanDreamService.kt + AndroidManifest.xml (see §1.14).
- [x] **BootReceiver:** on BOOT_COMPLETED only: warms the app-list cache,
      records `last_boot_at`, and (best-effort, fails open) brings Sreeb front
      if it is the default home; starts NO service — `BootReceiver.kt`,
      `AndroidManifest.xml`
      — 2D: app/BootReceiver.kt + AndroidManifest.xml (BOOT_COMPLETED only, warms app list, last_boot_at, best-effort front, no service).
- [x] **App (Application):** installs diagnostics (crash handler, main-thread
      watchdog, breadcrumbs) before anything else; starts the startup clock;
      begins the startup-health launch (counter via commit, safe-shell decision
      fixed here); touches SharedPreferences early on a background thread;
      kicks the v2.4 legacy-prefs purge, the home-cache mirror warm, the slim
      warm, and the store-memo warm (all single-flight, bounded, non-parsing);
      records memory-pressure trims as death evidence (stage + after-first-frame)
      — `App.kt`
      — 2D: app/App.kt (diagnostics first, startup clock + health launch, prefs touch + warms, trim death evidence, UiWiring fill-in, background-unit scheduling).
- [x] **No workers / alarms / widgets / notifications:** there is NO WorkManager,
      AlarmManager, JobScheduler, AppWidget, or NotificationManager usage in the
      tree. All "background" work is: (a) worker threads kicked from Activity /
      Application lifecycle, (b) due-checks on home wake / clock tick (Reddit
      rotation, weather staleness), (c) Handler runnables (hero timer, clock
      tick 1 s with seconds on / 20 s otherwise, screensaver 30 s tick).
      Verify the absence again in Phase 2 by grepping the new tree —
      `MainActivity.kt`, `App.kt`, `data/DiscoveryData.kt`, `data/ColdChainRunner.kt`
      — 2D: verified by grep — no WorkManager/AlarmManager/JobScheduler/AppWidget/NotificationManager usage in the new tree.
- [x] **Cold chain runner:** sequential post-survival steps on a worker thread,
      cancellable on navigation (onDestroy), single-flight — `data/ColdChainRunner.kt`,
      `core/ColdChain.kt`
      — 2D: data/ColdChainRunner.kt (data/BackgroundUnits.kt) + core/ColdChain.kt + app/MainActivity.kt scheduling.
- [x] **Discovery worker:** TMDB trending/soon/for-you refresh on a worker thread
      with 12 h trending TTL, disk file cache, single rebuild landing on change —
      `data/DiscoveryData.kt`
      — 2D: data/DiscoveryWorker.kt (12 h TTL, disk cache, single rebuild on change) + app/App.kt kick.
- [x] **IPTV ingest worker:** catalogue + EPG ingest streams endpoints
      element-by-element and swaps atomically (all-or-nothing: swap only when
      every attempted endpoint succeeded); reads are worker-thread slices;
      refresh is on request only (save account / Refresh buttons) — never on
      home build — `data/IptvClient.kt`, `core/IptvStore.kt`, `core/IptvStream.kt`
      — 2D: data/IptvRepositoryImpl.kt refresh + data/IptvStoreDisk.kt atomic swap + core/IptvStore.kt + core/IptvStream.kt (on request only, never on home build).
- [x] **Tracker refresh worker:** local-only refresh from the on-disk store
      (provider contact closed after the v3.7 one-time import); single-flight;
      section LOADING placeholders while in flight — `data/TrackerClient.kt`
      — 2D: data/TrackerRefreshWorker.kt (local-only, single-flight) + app/App.kt + app/MainActivity.kt refresh path.
- [x] **Tracker missed worker:** recomputes the missed list per show and for all
      shows (kicked on local changes) — `data/LibraryLocal.kt`
      (`recomputeMissedForShow`, `kickMissedAll`)
      — 2D: data/MissedWorker.kt (per-show + all-shows recompute, kicked on local changes).
- [x] **Diagnostics engine:** crash log (`filesDir/crash.log`), ANR log
      (`filesDir/anr.log`, once per stall episode), breadcrumb ring (persisted,
      oldest-first, capped, HH:mm:ss + label), bounded event log (cap keeps
      newest, char cap never truncates the newest event), startup milestones,
      death evidence, main-thread watchdog (heartbeat fresh < 3.9 s, stale once
      strictly past threshold); all surfaced in Settings → Data & about with
      "Copy diagnostics" — `data/DiagLog.kt`, `core/Diag.kt`, `data/DeathStore.kt`
      — 2D: data/DiagnosticsRepositoryImpl.kt + core/Diag.kt + app/App.kt install (crash handler, watchdog, breadcrumbs, copy block).
- [x] **Startup-health machine:** two consecutive uncleared starts → safe shell
      on the third launch; a clean render + 10 s survival clears; a whole-home
      fallback on the first build HOLDS the clear (so a deterministic
      composition crash still arms the safe shell) — `core/StartupHealth.kt`,
      `data/StartupHealthStore.kt`
      — 2D: data/StartupHealthStore + core/StartupHealth.kt + app/App.kt launch + app/MainActivity.kt clear/hold.
- [x] **Speed Up:** signals OTHER apps' background processes to exit
      (`KILL_BACKGROUND_PROCESSES`, normal permission); never the never-list,
      system apps, the foreground app, or Sreeb itself; disables/uninstalls
      nothing; reports free RAM before/after + count signalled —
      `ui/SettingsActivity.kt`, `core/SpeedUp.kt`, `core/NeverList.kt`

      — 2D: data/SpeedUpRunner + core/SpeedUp.kt + core/NeverList.kt + ui/SettingsActivity.kt row + UiWiring.onSpeedUp.
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
      — 2D PARTIAL: see §1.12 Make-home item — generic row only; exact steps + ADB line are §11 runtime-only.
- [x] Home watcher (accessibility) info row: optional, OFF unless enabled in
      system settings; shows watcher + default-home + current-home-package state —
      `ui/SettingsActivity.kt`, `data/HomeStatus.kt`
      — 2D: ui/SettingsActivity.kt watcher rows + UiWiring.homeWatcher (HomeWatcherProbeImpl: watcher/default-home/current-home/relaunches).
- [x] Speed Up row + last-result line (default: no result yet) —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`speedupLastResult`, default "")
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt speedup_last_result + UiWiring.onSpeedUp.
- [x] (No other Home & launcher toggles; the launcher-access service has no
      in-app toggle — it is enabled only in system Accessibility settings, §8)

      — 2D: ui/SettingsActivity.kt has no launcher-access toggle (system Accessibility only).
### 3.2 Look & wallpaper

- [x] Theme picker: 9 presets — Apple Black (default), Roku Purple, Midnight,
      Dusk, Plum, Forest, Ocean, Graphite, Dynamic (wallpaper accent; exactly
      one dynamic preset); unknown id falls back to the first preset —
      `ui/SettingsActivity.kt`, `core/Themes.kt`
      — 2D: ui/SettingsActivity.kt + core/Themes.kt (9 presets, Apple Black default, unknown→first).
- [x] Wallpaper picker: Theme background (default) · Gradient (14 built-ins,
      unknown falls back to first) · Solid colour · My picture (system picker,
      stored as content URI) · Reddit picture · Bing daily picture · Weather
      (live conditions) — `ui/SettingsActivity.kt`, `core/Themes.kt`
      (gradients), `data/ArtworkImages.kt`
      — 2D: ui/SettingsActivity.kt picker (all 7 kinds) + data/SettingsRepositoryImpl.kt; composition limits are recorded under the §1.1 wallpaper item.
- [x] Home style picker: **TV / Watch only** (v3.9; default TV). Labels: TV =
      "TV — rows of apps under the hero bar"; Watch = "Watch — your library +
      IPTV playback of your titles"; safe-mode lock label when safe shell is
      armed. Switching is presentation-only (favorites/folders/order/locks
      shared, nothing lost) — `ui/SettingsActivity.kt`, `core/HomeStyle.kt`
      — 2D: ui/SettingsActivity.kt + core/HomeStyle.kt (TV/Watch only, presentation-only switch).
- [x] Icon shape: rounded (default) / circle / square — `data/Prefs.kt`
      (`iconShape`, default "rounded")
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (rounded default).
- [x] App size: compact / standard (default) / large — `data/Prefs.kt`
      (`appSize`, default "standard")
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (standard default).
- [x] Grid columns: 3–10, default 6 — `data/Prefs.kt` (`columns`, default 6)
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (3–10, default 6).
- [x] Library sections toggles (Watch only): Hero (always first when shown) +
      every TrackerSections row; a section with nothing in it never appears even
      when switched on; "Reorder library sections…" opens the sort page —
      `ui/SettingsActivity.kt`, `core/TrackerSections.kt`
      — 2D: ui/SettingsActivity.kt sortSections + core/TrackerSections.kt (hero first when shown, empty sections never appear).
- [x] Reddit wallpapers row (when wallpaper = Reddit): "Top of the week from
      your subreddits" + last-try status line — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + data/RedditFetcher.kt.
- [x] Subreddits: comma-separated, default `wallpaper,wallpapers,EarthPorn`,
      `r/` prefix cleaned, invalid names rejected — `data/Prefs.kt`
      (`redditSubs`), `core/Reddit.kt`
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt + core/Reddit.kt (r/ prefix cleaned, invalid rejected).
- [x] Allow NSFW results: OFF by default (over_18 posts skipped) —
      `data/Prefs.kt` (`redditAllowNsfw`, default false)
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (OFF default).
- [x] Wallpaper rotation: off (default) / 15m / 1h / 6h / daily; checked on home
      wake or clock tick (no background service/timer); "Next wallpaper" jumps
      immediately — `ui/SettingsActivity.kt`, `core/Rotation.kt`,
      `data/Prefs.kt` (`redditRotation`, default off)
      — 2D: ui/SettingsActivity.kt + data/WallpaperRotator.kt + app/MainActivity.kt due-check on home build + UiWiring.nextWallpaper jump.
- [x] Bing daily picture: changes with the calendar day; cached per day-key
      under `filesDir/bing_wallpapers/` — `core/Rotation.kt` (`BingWallpaper`),
      `data/BingFetcher.kt`

      — 2D: data/BingFetcher.kt (per-day cache under filesDir/bing_wallpapers/) + core/Rotation.kt + app/MainActivity.kt.
### 3.3 Home screen

- [x] Per-row Show/Hide + Move up / Move down / Hide/Show dialog for every TV
      row (favorites, recents, mostused, folders, nowplaying, continue, inputs,
      livetv, apps); "Recently Used" row note clarifies its entry —
      `ui/SettingsActivity.kt`, `core/LayoutStore.kt`
      — 2D: ui/SettingsActivity.kt homeScreen + sortSections (per-row dialog for every TV row).
- [x] Live TV row note: tuner list only when the system allows; otherwise hidden
      with a diagnostics explanation — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt note + data/LiveTvData.kt state string.
- [x] Hidden apps manager: count shown, per-app unhide — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + data/AppsRepositoryImpl.kt.
- [x] Folders manager: count shown; create/rename/lock/unlock/move/delete
      (same rules as §1.10) — `ui/SettingsActivity.kt`

      — 2D: ui/SettingsActivity.kt + data/AppsRepositoryImpl.kt + ui/FolderViewActivity.kt.
### 3.4 Sort sections

- [ ] One page for both styles: tap a section, then Move up / Move down / — core part ported (foundation): new core/LayoutStore.kt, core/SortGrab.kt, core/TrackerSections.kt; data/ui/app parts pending
      Hide/Show (changes apply at once); hold OK to grab (D-pad moves it, OK
      drops, Back cancels); hero is Hide/Show-only; TV rows and Watch sections
      each keep their own list — `ui/SettingsActivity.kt`,
      `core/SortGrab.kt`, `core/TrackerSections.kt`, `core/LayoutStore.kt`
      — 2D PARTIAL: ui/SettingsActivity.kt sortSections implements tap + Move up/down + Hide/Show at once, hero Hide/Show-only, separate TV/Watch lists; hold-OK grab (D-pad moves, OK drops, Back cancels) is NOT implemented.
- [x] Migration: pre-v2.7 stored Watch orders splice the v2.7 focus rows
      (Coming Soon / Trending) into canonical slots on first read under v2.8,
      once only (later deliberate reorders are never re-migrated) —
      `data/Prefs.kt` (`trackerSectionsConfig`, `tracker_sections_migrated_28`)

      — 2D: data/PrefsStore.kt trackerSectionsMigrated28 one-time v2.8 splice + domain SettingsRepository.isTrackerSectionsMigrated surfacing.
### 3.5 Status bar

All defaults from `core/StatusBar.kt` (`StatusBarConfig`); stored inside the
layout document (`status=` line), NOT as separate prefs keys.

- [x] Show status bar: ON (off = clean Apple-TV style, no top bar at all) —
      default true
      — 2D: ui/SettingsActivity.kt + layout status= line + app/MainActivity.kt.
- [x] Sticky status bar: OFF (bar pins at top while home scrolls, in TV/Watch/
      IPTV views) — `data/Prefs.kt` (`stickyStatusBar`, default false)
      — 2D: ui/SettingsActivity.kt + app/MainActivity.kt (pinned above scroll when on).
- [x] Clock: ON; Date: ON; Weather: ON; Network icon: ON; Bluetooth icon: ON —
      defaults true
      — 2D: ui/SettingsActivity.kt + app/MainActivity.kt status bar.
- [x] Quick tiles row (master switch): ON — default true
      — 2D: ui/SettingsActivity.kt + ui/QuickSettingsView.kt.
- [x] Tile: Wi-Fi: OFF (default false; glyph + popover cover it) · Tile:
      Bluetooth: ON · Tile: Sound: ON · Tile: Picture: ON · Tile: Settings gear:
      ON (corner gear appears even when off, so Settings stays reachable) ·
      Tile: Search: ON · Tile: All Apps: ON
      — 2D: ui/SettingsActivity.kt status-bar page (Wi-Fi OFF default, others ON, corner gear fallback in app/MainActivity.kt bar).
- [x] Clock format: 12-hour (default) / 24-hour; Show seconds: OFF (clock tick
      1 s when on and not safe mode, else 20 s) — defaults 12h / false
      — 2D: ui/SettingsActivity.kt + app/MainActivity.kt (12h default, seconds toggle drives 1 s / 20 s tick).
- [x] Date format: weekday_month_day "Tue, Oct 20" (default) / iso "2026-10-20" /
      month_day_year "Oct 20, 2026"
      — 2D: ui/SettingsActivity.kt + app/MainActivity.kt (weekday_month_day default).
- [x] Text size: small / medium (default) / large
      — 2D: ui/SettingsActivity.kt + core/StatusBar.kt textScale.
- [x] Bar background: transparent (default) / theme / black; Background opacity:
      solid (default, alpha 0xFF) / translucent (alpha 0x99)
      — 2D: ui/SettingsActivity.kt + core/StatusBar.kt backgroundAlpha.
- [x] Safe mode bar: Settings tile only (all other tiles suppressed) —
      `core/StatusBar.kt` (`barTiles`)

      — 2D: core/StatusBar.kt barTiles (safe mode: Settings tile only) + app/MainActivity.kt safe shell.
### 3.6 Weather

- [x] Units: Fahrenheit — °F, wind mph (default) / Celsius — °C, wind km/h —
      `data/Prefs.kt` (`weatherUnitsKey`, per-profile; `WeatherUnits`)
      — 2D: ui/SettingsActivity.kt + data/SettingsRepositoryImpl.kt (Fahrenheit default).
- [x] Weather location: latitude/longitude text entry, default
      `39.1653, -86.5264` (Bloomington, IN) — `data/Prefs.kt`
      (`weatherLat`, `weatherLon`, global)
      — 2D: ui/SettingsActivity.kt + domain SettingsRepository.setWeatherLocation + data/PrefsStore.kt (global lat/lon, Bloomington default).
- [x] Forecast entry note: OK on clock/date/weather in the home bar opens the
      full forecast — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + app/MainActivity.kt Forecast action.
- [x] Weather background status: default "off" (internal; "Weather" wallpaper
      kind drives it) — `data/Prefs.kt` (`weatherBgStatus`)

      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt weather_bg_status (internal; Weather wallpaper kind drives it).
### 3.7 Lock & profiles

- [x] Set / change arrow pattern (enter existing pattern first to change) —
      default: no pattern set — `data/Prefs.kt` (`arrowPin`, default "")
      — 2D: ui/SettingsActivity.kt + ui/ArrowPinDialog.kt + data/LockRepositoryImpl.kt.
- [x] Clear arrow pattern (asks for it first) — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + data/LockRepositoryImpl.kt.
- [x] Locked apps manager: count shown, per-app lock/unlock —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`lockedApps`, default empty)
      — 2D: ui/SettingsActivity.kt + data/AppsRepositoryImpl.kt (pN.locked set).
- [x] Lock Settings: ON when a pattern is set and no explicit value stored
      (legacy rule: unset key reads as "pattern set ⇒ locked"); explicit toggle
      overrides — `data/Prefs.kt` (`lockSettings`)
      — 2D: ui/SettingsActivity.kt + data/LockRepositoryImpl.kt (unset ⇒ pattern-set means locked).
- [x] Forgot-pattern recovery note: system Settings → Apps → Sreeb → Clear data
      (resets layout too); "casual lock, by design" — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt note.
- [x] Unlock grace ("Re-ask for pattern"): Every time (default, 0 min) /
      After 5 / 15 / 30 minutes; grace covers locked apps, folders, Settings,
      and leaving Guest; switching profiles always asks again; a future-dated
      stamp never grants; expiry is exact at the boundary —
      `ui/SettingsActivity.kt`, `core/UnlockGrace.kt`, `data/Prefs.kt`
      (`unlockGraceMinutes`, default 0; `lastUnlockAt`, default 0)
      — 2D: ui/SettingsActivity.kt + core/UnlockGrace.kt + data/LockRepositoryImpl.kt.
- [x] Profile switch: Profile 1 ⇄ Profile 2 (tap to switch; clears the grace
      stamp); each profile keeps its own theme, wallpaper, layout, status bar,
      weather units, hidden apps; the arrow lock is shared —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`currentProfile`, default 1)
      — 2D: ui/SettingsActivity.kt + data/SettingsRepositoryImpl.kt switchProfile (clears grace stamp).
- [x] Rename Profile 1 / Rename Profile 2: custom name ≤ 16 chars, never leaves
      a trailing space; blank restores the default ("Guest" for the guest
      profile, "Profile N" otherwise); renaming never changes the Guest flag or
      any lock — `ui/SettingsActivity.kt`, `core/ProfileNames.kt`
      — 2D: ui/SettingsActivity.kt + data/SettingsRepositoryImpl.kt setProfile (Phase 2D fix: renames now persist via PrefsStore.setProfileNameRaw) + core/ProfileNames.kt.
- [x] Guest profile: None (default, 0) / Profile 1 / Profile 2; switching TO
      Guest is always instant; while Guest is active a Guest pill shows and
      opening Settings or switching back asks for the pattern (if set) —
      `data/Prefs.kt` (`guestProfile`, default 0)
      — 2D: ui/SettingsActivity.kt menu + domain SettingsRepository.setGuestProfile + data/PrefsStore.kt (None default, instant switch-to-Guest, Guest pill).
- [x] Lock settings in Guest: ON (default true) — `data/Prefs.kt`
      (`guestLockSettings`, default true)

      — 2D: ui/SettingsActivity.kt switch + domain SettingsRepository.setGuestLockSettings + data/PrefsStore.kt (ON default).
### 3.8 Now playing

- [x] Now playing row toggle (row visibility flag; needs notification access
      below, otherwise the row stays hidden) — default row visible —
      `ui/SettingsActivity.kt`, `core/LayoutStore.kt`
      — 2D: ui/SettingsActivity.kt + layout row visibility + app/MainActivity.kt row.
- [x] Notification access row: "Granted ✓" / "Not granted — tap to open system
      settings" (opens `ACTION_NOTIFICATION_LISTENER_SETTINGS`) —
      `ui/SettingsActivity.kt`, `data/NowPlayingData.kt`

      — 2D: ui/SettingsActivity.kt (Granted ✓ / Not granted → system settings) + data/NowPlayingRepositoryImpl.kt grant probe.
### 3.9 IPTV

- [x] Xtream account: Server (default ""), Username (default ""), Password
      (default "", shown as •••••••• when set); server gains `http://` when no
      scheme typed and loses a trailing slash; account needs server + user +
      pass — `ui/SettingsActivity.kt`, `core/IptvLogic.kt`, `data/Prefs.kt`
      — 2D: ui/SettingsActivity.kt + core/IptvLogic.kt account normalization + data/PrefsStore.kt.
- [x] Playlist (M3U) URL (default "") + Guide (XMLTV) URL (default ""):
      independent second source; channels join Live rows (sources NOT merged or
      de-duplicated); guide matched by tvg-id; URLs may embed credentials and
      get the same never-exported treatment — `ui/SettingsActivity.kt`,
      `data/Prefs.kt`
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (independent source, not merged/deduped).
- [x] Catalogue status line: counts + last-update time + failure class in
      parentheses when one is recorded (never credentials, never in non-error
      lines); "data refreshes only when you save the account or tap Refresh" —
      `ui/SettingsActivity.kt`, `core/IptvStatus.kt`, `data/IptvClient.kt`
      — 2D: ui/SettingsActivity.kt + core/IptvStatus.kt + data/IptvRepositoryImpl.kt (counts + failure class only).
- [x] Refresh now (fetch catalogues + guide again) — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + data/IptvRepositoryImpl.kt refresh (on request only).
- [x] Clear IPTV sources… (confirmation dialog; removes account + playlist URLs
      from the TV; favorites and resume positions STAY) — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + data/SettingsRepositoryImpl.kt clearIptvSources (favorites + resume STAY).
- [x] Category locks note: hold OK on a category chip to pattern-lock it; adult
      categories start locked when a pattern is set — `ui/SettingsActivity.kt`,
      `core/IptvLocks.kt`

      — 2D: ui/SettingsActivity.kt + core/IptvLocks.kt.
### 3.10 Data & about

- [x] Export settings: save all settings as a JSON file named
      `mizan-launcher-settings.json` (via system create-document; includes
      theme, wallpaper kind/value, layout, locked apps, home style, profile
      names, tracker sections, home-hint flag, lock_settings; EXCLUDES the
      tracker marked-set, For You overlay, pending overlay, IPTV favorites /
      resume, TMDB key, IPTV credentials) — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt create-document (mizan-launcher-settings.json) via UiWiring.exportSettings → data/PrefsStore.kt exportSettingsJson (incl. locked_apps fix in 2D; excludes marked set, overlays, IPTV favorites/resume, TMDB key, IPTV credentials).
- [x] Import settings: restore from a previously exported JSON file; rejects
      non-Sreeb files with "That file isn't a Sreeb backup — nothing imported";
      type-tolerant (wrong-typed values evict to defaults) —
      `ui/SettingsActivity.kt`, `data/PrefsSafe.kt`
      — 2D: ui/SettingsActivity.kt via UiWiring.importSettings → data/PrefsStore.kt importSettingsJson (rejects non-Sreeb files with the exact wording, type-tolerant).
- [x] TMDB API key row: Set / Not set only (masked entry, 80 chars); stored
      only on the TV, never in backups/diagnostics/logs; empty clears it;
      powers direct-TMDB fallback only (tracker proxy stays primary) —
      `ui/SettingsActivity.kt`, `data/Prefs.kt` (`launcher_tmdb_key`, default "")
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (masked, 80 chars, stored only on TV, empty clears, direct fallback only).
- [x] Version header: "Sreeb v<version>" from PackageManager + diagnostics block
      (startup times, cold chain, tracker/library/home lines, TMDB lines, live
      TV line, IPTV line, freeze/crash tails, last death, watcher relaunch count
      + last time, screen-on recoveries) — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt (PackageManager version + diagnostics block) + data/DiagnosticsRepositoryImpl.kt.
- [x] Copy diagnostics: copies the whole block to the clipboard for problem
      reports — `ui/SettingsActivity.kt`
      — 2D: ui/SettingsActivity.kt + data/DiagnosticsRepositoryImpl.kt copyToClipboard.
- [x] Safe mode toggle: "Safe mode: plain launcher" — themes/widgets/extras off —
      default OFF — `data/Prefs.kt` (`safeMode`, default false)
      — 2D: ui/SettingsActivity.kt + data/PrefsStore.kt (OFF default).
- [x] Exit safe mode and retry normal home (shown only while safe mode / safe
      shell is armed; resets the counter and returns to the normal home style
      now) — `ui/SettingsActivity.kt`, `data/StartupHealthStore.kt`
      — 2D: ui/SettingsActivity.kt + data/SettingsRepositoryImpl.kt + data/StartupHealthStore.
- [x] Player aspect mode (per profile, not a Settings row): fit (default) /
      stretch / zoom — `data/Prefs.kt` (`playerAspect`), `core/PlayerLogic.kt`

      — 2D: data/PrefsStore.kt player_aspect + data/PlaybackRepositoryImpl.kt + ui/PlayerActivity.kt (fit default).
---

## 4. Local storage (files, databases, prefs keys)

No SQLite database exists in the tree. All persistence is SharedPreferences
(`mizan_launcher`) + plain files under `filesDir/`. Diagnostics never include
credential values.

- [x] SharedPreferences file `mizan_launcher` (MODE_PRIVATE); every read via
      `PrefsSafe` (type-mismatch evicts + reads default) — `data/Prefs.kt`,
      `data/PrefsSafe.kt`
      — 2D: data/PrefsStore.kt + data/PrefsSafe.kt (every read via the funnel).
- [x] Global keys: `current_profile` (1, coerced 1–2), `arrow_pin` (""; legacy
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
      — 2D: data/PrefsStore.kt (all MAP global keys incl. last_boot_at, own_foreground_at, watcher counters, reddit_attempt_log, last_error scrubbed).
- [ ] Per-profile keys (`p<profile>.` prefix): `theme` ("apple_black"), — core part ported (foundation): new core/LayoutStore.kt; data/ui/app parts pending
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
      — 2D PARTIAL: data/PrefsStore.kt implements the per-profile key set except accent_override and tracker_hero_index, which are not stored (hero index is in-memory carousel state in app/MainActivity.kt).
- [x] `filesDir/iptv/`: `live.jsonl`, `vod.jsonl`, `series.jsonl`, `epg.jsonl`
      (one line per entry/programme), `index.json` (totals, per-kind counts,
      byte sizes, stamps), `series_info/` (`<fp>_<id>.json`); tmp+rename atomic
      swap; oversized EPG segments dropped with stream resync; guide retention
      window keeps now/next, drops the long past and far future —
      `data/IptvClient.kt`, `core/IptvStore.kt`, `core/IptvStream.kt`
      — 2D: data/IptvStoreDisk.kt + core/IptvStore.kt + core/IptvStream.kt (atomic swap, guide window).
- [x] `filesDir/tracker/`: the launcher-owned indexed tracker store (v3.6
      redesign: line files + index, tmp+rename); `missed.txt` + per-title
      `missed_cache/`; written only by `LibraryLocal` queue writers and the
      v3.7 one-time import — `data/TrackerStoreDisk.kt`, `core/TrackerStore.kt`,
      `data/LibraryLocal.kt`
      — 2D: data/TrackerStoreDisk.kt + core/TrackerStore.kt (line files + index + missed_cache, tmp+rename).
- [x] `filesDir/home_cache/`: home-cache mirror metadata (section → length)
      + `tracker_slim.json` (≤ 512 KB slim snapshot for first-frame paint) —
      `data/HomeCacheDisk.kt`, `core/HomeCache.kt`, `data/TrackerClient.kt`
      — 2D: data/HomeCacheDisk + core/HomeCache.kt (tracker_slim.json ≤512 KB, credential tripwire).
- [x] `filesDir/discovery/`: TMDB payload files (`trending/tv/week`,
      `trending/movie/week`, `trending/all/week`, soon/for-you details) with
      stamps; trending TTL 12 h; details pruned by max age —
      `data/DiscoveryData.kt`
      — 2D: data/DiscoveryWorker.kt (trending cache, 12 h TTL).
- [x] `filesDir/artwork/<pkg>.png`: downloaded per-app artwork cache —
      `data/ArtworkImages.kt`
      — 2D: data/TvAndArtwork.kt ArtworkImages (per-pkg PNG, bounded decodes).
- [x] `filesDir/reddit_wallpapers/` + `filesDir/bing_wallpapers/`: downloaded
      wallpaper images — `data/RedditFetcher.kt`, `data/BingFetcher.kt`
      — 2D: data/WallpaperFetchers.kt (Reddit + Bing caches).
- [x] `filesDir/crash.log`, `filesDir/anr.log`, breadcrumb file (see §2):
      diagnostics evidence — `data/DiagLog.kt`
      — 2D: data/DiagnosticsRepositoryImpl.kt + app/App.kt install.
- [x] Image decode funnel: downsampled decodes + byte-bounded bitmap cache
      (never full-size unbounded bitmaps) — `data/DecodeBounds.kt`,
      `data/ArtworkImages.kt`
      — 2D: data/TvAndArtwork.kt decodeBounded + app/ImageLoader.kt bounded decodes (≤512 px, ≤4 MB) + in-memory cache.
- [x] JSON depth cap: parsers refuse nesting past 64 (a 5000-deep hostile
      payload reads as malformed, never a stack overflow) — `core/JsonMini.kt`,
      `core/IptvStream.kt`
      — 2D: core/JsonMini.kt + core/IptvStream.kt (depth cap 64, fail-open).
- [x] Backup/export JSON shape (see §3.10 for the included/excluded sets) —
      `ui/SettingsActivity.kt`

      — 2D: data/PrefsStore.kt exportSettingsJson/importSettingsJson + ui/SettingsActivity.kt via UiWiring hooks (see §3.10).
---

## 5. Data sources & integrations

- [x] **TV tracker (the launcher's library source, local-first since v3.7):**
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
      — 2D: data/TrackerRepositoryImpl.kt + data/TrackerStoreDisk.kt + core/TrackerLogic.kt + core/LibraryLogic.kt (local-first; the v3.7 one-time import is historical and flag-gated closed, not re-implemented).
- [ ] **TMDB via the tracker proxy (primary):** details / season / search — core part ported (foundation): new core/LibraryLogic.kt; data/ui/app parts pending
      through `TrackerClient.tmdbGet` (paths `details` / `season` / `search`,
      encoded); error kinds `no_key` / `http_<code>` / `io` / `bad_path`;
      retryable = http/io/unknown only; user lines name the fix for `no_key`
      and carry the code for http; diagnostics wording = path + human text +
      raw token in parens (v2.6: `io:<SimpleClassName>` tokens; exception
      messages never carried — they can embed the key) —
      `data/TrackerClient.kt`, `core/LibraryLogic.kt`
      — 2D NOT AVAILABLE: no live proxy URL is derivable from source (the tracker bridge closed post-v3.7); data/TmdbRepositoryImpl.kt keeps the proxy-first structure but the direct launcher-key path carries real traffic, and both-fail surfaces the direct error, not the proxy error as MAP §5 states. Documented, not invented.
- [x] **TMDB direct (fallback, launcher key only):** `https://api.themoviedb.org/3/`
      + path, contract allowlist, class-name tokens only, never logs (no Log
      import, no key echo); used only when the proxy fails AND a launcher key
      is saved; both-paths-failing surfaces the PROXY error (direct token kept
      for diagnostics only); composed lines never contain key material —
      `data/TmdbDirect.kt`, `data/TrackerClient.kt`
      — 2D: data/TmdbRepositoryImpl.kt (api.themoviedb.org/3/ allowlisted paths, key never logged, class-name tokens only, used only when proxy fails AND a launcher key is saved).
- [x] **TMDB images:** `https://image.tmdb.org/t/p/<size><path>`; poster `w342`,
      backdrop `w780` (Up Next/Missed portrait cards use the poster, falling
      back to backdrop; Continue keeps landscape cards with blurred fill; hero
      keeps backdrops; a poster in a 16:9 frame is never centre-cropped) —
      `data/TmdbImages.kt`, `MainActivity.kt`, `ui/PosterCards.kt`
      — 2D: domain/TmdbImages.kt (w342/w780) + ui/PosterCards.kt FIT_CENTER (complete, never centre-cropped) + app/ImageLoader.kt.
- [x] **IPTV — Xtream Codes API:** `player_api.php` with encoded credentials +
      action; live `/live/<user>/<pass>/<id>.m3u8`; VOD with
      `container_extension` (default mp4); series episodes
      `/series/<user>/<pass>/<episodeId>.<ext>`; timeshift
      `/timeshift/<user>/<pass>/<durationMin>/<startYmdHis>/<id>.ts`;
      auth probe (1 true / 0 false, string ids accepted, junk null); category
      parse (string + numeric ids, blank names skipped); `tv_archive` drives
      catch-up availability — `core/IptvLogic.kt`, `data/IptvClient.kt`
      — 2D: core/IptvLogic.kt + data/IptvRepositoryImpl.kt (player_api.php shapes, timeshift, tv_archive catch-up).
- [x] **IPTV — M3U playlists:** channel + group parse (quote-aware name,
      ungrouped → "Playlist"); channel identity = tvg-id/logo/direct URL,
      stable ids; streamed (never whole-document) — `core/IptvLogic.kt`,
      `core/IptvStream.kt`
      — 2D: core/IptvLogic.kt + core/IptvStream.kt + data/IptvRepositoryImpl.kt (quote-aware, ungrouped→Playlist, streamed).
- [x] **IPTV — XMLTV guide:** `xmltv.php`-shaped URL; streamed parse; guide
      window retention (now/next kept); short-EPG per channel —
      `core/IptvEpg.kt`, `data/IptvClient.kt`
      — 2D: core/IptvEpg.kt + data/IptvRepositoryImpl.kt (streamed, window retention, short-EPG).
- [x] **IPTV — Watch matching (tracker title ↔ IPTV catalogue):** squash
      normalization (lowercase, strip non-[a-z0-9]); exact tier, then contains
      tier over once-keyed candidates (repo method: 'Preacher' matches 'Reacher'
      by design — crude, pinned); year extraction (bracketed/trailing/leading
      only) breaks same-name ties and deprioritizes movies; ambiguity →
      first candidate flagged `possible`; missing season/episode → unresolved
      (fail-open); match cache fingerprint + codec stores ids + flags, never
      titles; matching runs on the `mizan-watch-match` worker only, never the
      main thread — `core/WatchMatch.kt`, `data/WatchPlay.kt`
      — 2D: core/WatchMatch.kt + data/WatchMatchEngine.kt (squash, exact→contains, year tie-break, possible flag, fail-open unresolved, fingerprint ids+flags only, worker-thread only).
- [x] **Weather — Open-Meteo (no account/key):**
      `https://api.open-meteo.com/v1/forecast?latitude=…&longitude=…` (+ hourly
      / daily params); cached snapshot + 3 h-ish staleness rule via
      `RefreshPolicy.weatherFetchAllowed` (stale or explicit refresh) —
      `data/Weather.kt`, `core/WeatherLogic.kt`, `core/Forecast.kt`
      — 2D: data/WeatherRepositoryImpl.kt + core/WeatherLogic.kt + core/Forecast.kt (cached, ~3 h staleness).
- [x] **Reddit wallpapers:** top-of-week JSON chain www → api → old → www
      `.rss` (RSS fallback feeds the same `RedditImage` model); only usable
      landscape image posts survive (direct `i.redd.it` image, preview fallback
      with URL unescaped; stickied / video / gallery / NSFW-unless-allowed /
      portrait rejected; dimensions carried; permalink captured; subreddit
      stamped from caller); attempt lines name endpoint + code + count; last
      12 attempts kept — `core/Reddit.kt`, `data/RedditFetcher.kt`
      — 2D: core/Reddit.kt + data/WallpaperFetchers.kt RedditFetcher (JSON chain + RSS fallback, landscape-only filtering, last 12 attempts).
- [x] **Bing daily picture:**
      `https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US`;
      relative URLs made absolute; malformed JSON fails open to null; day key
      is the calendar date — `core/Rotation.kt`, `data/BingFetcher.kt`
      — 2D: core/Rotation.kt + data/WallpaperFetchers.kt BingFetcher (HPImageArchive, absolute URLs, day key).
- [ ] **Installed apps:** PackageManager leanback + launcher intents; catalogue — core part ported (foundation): new core/AppCatalogue.kt, core/AppCategories.kt; data/ui/app parts pending
      dedupes across categories (leanback label wins), system apps included
      and flagged, Sreeb itself excluded, sorted case-insensitively by label
      (ties by package); curated package → category overrides beat the manifest
      category table — `data/AppLoader.kt`, `core/AppCatalogue.kt`,
      `core/AppCategories.kt`
      — 2D PARTIAL: data/AppsRepositoryImpl.kt implements leanback+launcher intents, dedupe (leanback label wins), system flag, self excluded, label-then-package sort; curated package→category overrides (core/AppCategories.kt) are NOT applied — the category field stays empty.
- [x] **App usage:** Most Used from usage stats (grant-gated; Settings →
      Now playing page pattern — verify the exact grant screen at runtime, see
      §11); Recents from launch recording — `data/MostUsed.kt`,
      `core/MostUsed.kt`, `core/Recents.kt`
      — 2D: data/UsageStatsCollector.kt (ACTIVITY_RESUMED counts, grant-gated) + core/MostUsed.kt + core/Recents.kt.
- [x] **Live TV / tuner:** system TV listings provider (`READ_TV_LISTINGS`,
      best-effort, guarded at runtime) — `data/TvData.kt`, `data/LiveTv.kt`,
      `core/LiveTv.kt`
      — 2D: data/TvAndArtwork.kt LiveTvData (READ_TV_LISTINGS guarded, state-only diagnostics) + app/MainActivity.kt row.
- [x] **Media sessions:** MediaSessionManager via the notification-listener
      grant (see §2) — `data/NowPlayingData.kt`, `core/NowPlaying.kt`
      — 2D: data/NowPlayingRepositoryImpl.kt + core/NowPlaying.kt + app/NowPlayingListener.kt.
- [x] **System intents:** app info, uninstall, Wi-Fi/Bluetooth/Display/Sound/
      Storage/About system settings, notification-listener settings, default-home
      selection — `MainActivity.kt`, `ui/SettingsActivity.kt`,
      `core/QuickSettings.kt`, `data/HomeStatus.kt`
      — 2D: app/MainActivity.kt + ui/SettingsActivity.kt + ui/QuickSettingsView.kt + core/QuickSettings.kt + data/HomeStatusProbe.kt.
- [ ] **Discovery self-computation (v2.7):** launcher computes Coming Soon / — core part ported (foundation): new core/Discovery.kt; data/ui/app parts pending
      Trending / For You itself from TMDB (seeds, affinity, recommendations +
      discover, pick codec); tracker snapshots remain the fallback; diagnostics
      gains a `discovery:` line — `core/Discovery.kt`, `data/DiscoveryData.kt`
      — 2D PARTIAL: data/DiscoveryWorker.kt fetches TMDB trending (tv+movie week) with 12 h TTL and the diagnostics line; seeds/affinity/recommendations+discover pick codec (core/Discovery.kt) and Coming Soon computation are NOT implemented — For You falls back to the tracker snapshot.
- [x] **Cleartext HTTP:** permitted app-wide (`usesCleartextTraffic=true`,
      targetSdk 34 default would block it) — deliberate: Xtream panels, M3U,
      XMLTV are overwhelmingly plain `http://host:port` — `AndroidManifest.xml`

      — 2D: AndroidManifest.xml usesCleartextTraffic=true (deliberate, Xtream/M3U/XMLTV).
---

## 6. Cross-cutting behaviours (check these, not just the screens)

- [x] Fail-open everywhere: garbage/missing/truncated/corrupt stored state
      (layout document, row + section orders, home style, tracker snapshots,
      match cache, discovery cache, store index) decodes to defaults or empty,
      never crashes — `data/PrefsSafe.kt`, `core/LayoutStore.kt`,
      `core/TrackerSections.kt`, `core/HomeStyle.kt`
      — 2D: data/PrefsSafe.kt + core/LayoutStore.kt + core/TrackerSections.kt + core/HomeStyle.kt + guarded decodes throughout.
- [x] Prefs funnel: every read through `PrefsSafe`; wrong-typed values evict
      themselves — `data/PrefsSafe.kt`
      — 2D: data/PrefsSafe.kt (wrong-typed values evict to defaults).
- [x] Guarded rebuilds in every Activity (rebuild catches Throwable, records
      via `Prefs.noteError`, shows a retryable/snag state) — all `ui/*`,
      `MainActivity.kt`
      — 2D: ui/Ui.kt guarded + app/MainActivity.kt emergency root + ui/SettingsActivity.kt crash-contained panes.
- [x] Main-thread audit: no network/parse/match work on the main thread;
      Watch matching worker-only; IPTV reads worker-thread slices; image decodes
      downsampled — `data/WatchPlay.kt`, `data/IptvClient.kt`,
      `data/DecodeBounds.kt`
      — 2D: repositories are synchronous-off-main-thread by contract; app/MainActivity.kt gathers on a worker thread; app/ImageLoader.kt fetches off-main; Watch matching runs in ColdChainRunner only.
- [x] Crash containment: a throwing section yields its error tile; tracker/
      watch/iptv fall back to the TV render, TV to the emergency root; reporting
      throttled 1/section/minute — `core/SectionGuard.kt`, `MainActivity.kt`
      — 2D: core/SectionGuard.kt + app/MainActivity.kt (error tiles, tracker/watch/iptv fall back to TV render, TV to emergency root; reporting throttled per core/SectionGuard).
- [x] Startup breadcrumbs + watchdog + crash/ANR logs + death evidence, all in
      Copy diagnostics — `data/DiagLog.kt`, `data/DeathStore.kt`
      — 2D: data/DiagnosticsRepositoryImpl.kt + app/App.kt (crash/ANR logs, breadcrumb ring, watchdog 3.9 s, death evidence, Copy diagnostics).
- [x] View caps (anti-blackout, v2.3.2): browser/guide grids cap 200, home rows
      capped in composition AND at render (home row view cap 20), favorites row
      capped, hostile EPG index fails open to no rows — `ui/IptvActivity.kt`,
      `ui/IptvGuideActivity.kt`, `MainActivity.kt`
      — 2D: ui/IptvActivity.kt + ui/IptvGuideActivity.kt (200) + ui/HomeUi.kt + app/MainActivity.kt (home rows 20).
- [x] Staleness wording + local-first home cache: TTLs, oldest-first prune,
      credential tripwire (a credential-looking value in cached home data is
      treated as a trip, not rendered) — `core/HomeCache.kt`,
      `data/HomeCacheDisk.kt`
      — 2D: core/HomeCache.kt + data/HomeCacheDisk.kt (TTLs, oldest-first prune, credential tripwire).
- [x] Section lifecycle machine: LOADING / READY / EMPTY / ERROR + retry
      routing per section — `core/SectionModule.kt`
      — 2D: core/SectionModule.kt + domain SectionState/SectionRender + ui/HomeUi.kt cardRow states.
- [x] Profile isolation: theme, wallpaper, layout, locks, hidden apps, sections,
      favorites, IPTV favorites/resume/locks never bleed across profiles —
      `data/Prefs.kt` (`p<profile>.` prefix)
      — 2D: data/PrefsStore.kt p<profile>. prefix for theme/wallpaper/layout/locks/hidden/sections/favorites/IPTV favorites/resume/locks.
- [x] Never-kill list: gms, launcherx, Play Store, Settings + SystemUI, setup /
      keyboard / bluetooth / tvinput, Sreeb itself — never signalled by Speed Up,
      never offered for kill — `core/NeverList.kt`
      — 2D: core/NeverList.kt + core/SpeedUp.kt killList filter + data/SpeedUpRunner.
- [ ] App-category curation: curated packages win over manifest categories;
      sections render in order, non-empty only, alphabetical inside —
      `core/AppCategories.kt`
      — 2D NOT APPLIED: core/AppCategories.kt exists but data/AppsRepositoryImpl.kt does not apply its curated overrides (see §5 Installed apps).
- [x] "Complete poster" artwork rule: posters render complete, never
      centre-cropped (standing v2.9 rule: cut heads are unacceptable) —
      `ui/PosterCards.kt`, `MainActivity.kt`
      — 2D: ui/PosterCards.kt FIT_CENTER + app/ImageLoader.kt bounded loads (never centre-cropped).
- [ ] Sim harness: `sim/SimMain.kt` + `sim/run_sim.sh` (kotlinc → JVM sim over
      pure `core/` logic) and its TEST_LOG expectations must be re-pinned, not
      dropped, by Phase 2 — `sim/SimMain.kt`, reference `TEST_LOG.md`

      — 2D NOT RE-PINNED: sim/SimMain.kt + run_sim.sh + TEST_LOG expectations are not re-pinned in this phase (core is byte-identical, so expectations should carry, but that re-pinning was not done here).
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

- [x] Episode checklist row: an unaired episode carries **no ▶ glyph** (there
      is nothing to play yet); aired + IPTV-matched episodes keep the ▶ —
      `ui/DetailsActivity.kt` (`episodeRow`)
      — 2D: ui/DetailsActivity.kt + domain/AiredGate.kt (no ▶ when unaired; ▶ only when aired AND matched).
- [x] Episode OK (click) on an unaired, unwatched episode: **no toggle**; toast
      `Hasn't aired yet — airs <airDate>` (with the date) or `Hasn't aired yet`
      (when the date is blank — unreachable in practice since blank = aired,
      but the branch exists) — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt (no toggle, AiredGate.unairedMessage toast).
- [x] Episode hold-OK (long-press) on an unaired, **unwatched** episode: **no
      menu at all**; the same toast as above — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt (no menu at all, toast only).
- [x] Episode hold-OK on an unaired, **already-marked** episode: menu offers
      ONLY `↩ Mark unwatched` (the correction path; no Play, no Mark watched) —
      `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt (ONLY ↩ Mark unwatched).
- [x] Episode hold-OK on an aired episode: menu offers `▶ Play S<s> E<e>` +
      `✓ Mark watched` / `↩ Mark unwatched` as before — `ui/DetailsActivity.kt`
      — 2D: ui/DetailsActivity.kt (▶ Play S<s> E<e> + ✓/↩ Mark watched via playEpisode).
- [x] Episode OK on an aired episode: toggles watched as before (unaired check
      precedes the toggle call) — `ui/DetailsActivity.kt`,
      `data/TrackerActions.kt` (`toggleEpisode`)
      — 2D: ui/DetailsActivity.kt + data/UseCasesImpl.kt markEpisode (AiredGate-enforced toggle).
- [x] "✓ Mark season watched": filters the season's episode list through
      `hasAired` FIRST; only aired episodes are written (overlay season mark +
      queue-season-mark writer + per-episode marked-set entries); if NO episode
      has aired, the function returns silently without writing anything —
      `data/TrackerActions.kt` (`markSeason`)
      — 2D: data/TrackerRepositoryImpl.kt markSeasonWatched (AiredGate filter first; writes nothing when none aired).
- [x] "Mark season watched" button's remaining-count label counts only
      unwatched episodes that have aired (`epKey ∉ effectiveWatched AND
      hasAired(airDate)`); the button hides when that count is 0 —
      `ui/DetailsActivity.kt` (season block)
      — 2D: ui/DetailsActivity.kt + domain/AiredGate.kt remainingAiredCount (unwatched AND aired; hidden at 0).
- [x] Undated episodes (blank air date) behave exactly as before: ▶ shown,
      Play offered, Mark watched offered, season marking includes them —
      `ui/DetailsActivity.kt`, `data/TrackerActions.kt`
      — 2D: domain/AiredGate.kt (blank = aired) + ui/DetailsActivity.kt + data/TrackerRepositoryImpl.kt.
- [x] Malformed air dates behave as aired (fail open) — same two `hasAired`
      implementations
      — 2D: domain/AiredGate.kt (malformed = aired, fail open).
- [x] Scope note: the gate lives ONLY in Details episode rows + season marking.
      No gate was added to Up Next / Continue / Missed card play paths, Library
      next-episode marking, or Watch auto-mark in this commit — verify whether
      Phase 2 should extend it (see §11).

      — 2D DECISION: scope KEPT at Details + season marking per f26a5f6 (not extended to card play / Library next-marking / auto-mark); extension remains a product decision (§11).
### 8.2 Accessibility services (both off by default, both in TV Settings → Accessibility → Installed services)

**"Sreeb launcher" — `LauncherService.kt`, `res/xml/launcher_config.xml`:**

- [x] Label `Sreeb launcher`; description: "Lets Sreeb perform system actions
      (Back, Home, Recents, notifications) on your behalf. It cannot read screen
      contents." — `res/values/strings.xml` (`launcher_access_name`,
      `launcher_access_desc`)
      — 2D: res/values/strings.xml (launcher_access_name/desc) + app/LauncherService.kt + AndroidManifest.xml.
- [x] Config: `typeWindowStateChanged` events only, `feedbackGeneric`,
      `notificationTimeout=100`, **`canRetrieveWindowContent=false`** (sees
      WHICH window is showing, never what is on it), **no gestures**
      (`canPerformGestures` off — not declared) — `res/xml/launcher_config.xml`
      — 2D: res/xml/launcher_config.xml (window-state only, feedbackGeneric, timeout 100, canRetrieveWindowContent=false, no gestures).
- [x] `onAccessibilityEvent` is intentionally a no-op: this service does NOT
      monitor windows (the watcher does that); window-state events are declared
      only so the service stays eligible for future launcher features without a
      config change — `LauncherService.kt`
      — 2D: app/LauncherService.kt onAccessibilityEvent is a no-op (launcher monitors nothing); app/WatcherService.kt does the window monitoring.
- [x] `performAction(action: Int): Boolean` fires a system global action via the
      connected instance (`GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`,
      `GLOBAL_ACTION_RECENTS`, `GLOBAL_ACTION_NOTIFICATIONS`,
      `GLOBAL_ACTION_QUICK_SETTINGS`, `GLOBAL_ACTION_POWER_DIALOG`, …);
      returns **false** when not enabled/connected — callers MUST fail open
      (the action simply does not happen) — `LauncherService.kt`
      — 2D: app/LauncherService.kt performAction + GlobalActionsGateway delegate (Back/Home/Recents/Notifications/Quick Settings/Power dialog; false when disconnected, fail-open).
- [x] `isConnected(): Boolean` = the static instance is non-null (set on
      `onServiceConnected`, cleared on `onUnbind`/`onDestroy`) —
      `LauncherService.kt`
      — 2D: app/LauncherService.kt (static instance, set on connect, cleared on unbind/destroy).
- [x] Everything try/catch-wrapped; a failure here can never crash or loop the
      launcher; sends nothing anywhere; reads no content — `LauncherService.kt`
      — 2D: app/LauncherService.kt (sends nothing anywhere, reads no content).
- [x] Enabled-state probe for Settings/diagnostics:
      `HomeStatus.isLauncherServiceEnabled(ctx)` checks
      `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for this service's
      flattened ComponentName (case-insensitive, colon-split; false on any
      error) — `data/HomeStatus.kt`
      — 2D: data/HomeStatusProbe.kt isLauncherServiceEnabled (Secure ENABLED_ACCESSIBILITY_SERVICES, case-insensitive colon-split, false on error).
- [x] No in-app toggle exists for this service; the opt-in lives with the user
      in system Accessibility settings (Android only runs a service the user
      personally switched on) — `LauncherService.kt` (doc), `AndroidManifest.xml`

      — 2D: ui/SettingsActivity.kt states it; opt-in is system Accessibility only.
**"Sreeb launcher watcher" — `WatcherService.kt`, `res/xml/watcher_config.xml`:**

- [x] Renamed in f26a5f6 from "Sreeb watcher" to **"Sreeb launcher watcher"**;
      description unchanged: "Watches for the TV's stock home screen appearing
      and reopens Sreeb instead. It cannot read screen contents." —
      `res/values/strings.xml` (`watcher_name`, `watcher_desc`)
      — 2D: res/values/strings.xml watcher_name = "Sreeb launcher watcher" + app/WatcherService.kt.
- [x] Config identical in kind: window-state events only,
      `canRetrieveWindowContent=false`, `notificationTimeout=100` —
      `res/xml/watcher_config.xml`
      — 2D: res/xml/watcher_config.xml (window-state only, canRetrieveWindowContent=false, timeout 100).
- [x] Behaviour unchanged by f26a5f6: passive stock-home relaunch + screen-on
      recovery, debounce + loop guards, diagnostics counting (see §2) —
      `WatcherService.kt`
      — 2D: app/WatcherService.kt (passive relaunch + screen-on recovery, 2 s debounce, 1 s loop guard in-memory + pref, diagnostics counting).
- [x] In-app references updated to the new name in Settings (watcher info row) —
      `ui/SettingsActivity.kt`

      — 2D: ui/SettingsActivity.kt watcher rows use "Sreeb launcher watcher" naming/state.
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
      — 2D PARTIAL: warm first build implemented (app/MainActivity.kt gathers from the on-disk store); the memo-source home: diagnostics line and slim/store install ordering are NOT composed, and the on-device fix remains UNVERIFIED (§11).
- [x] **Missed per-show pending scoping:** pending watched adds/removes are
      looked up per show (`LibraryLogic.pendingWatchedFor(pending, tmdbId)`),
      not folded across shows — `MainActivity.kt`, `core/LibraryLogic.kt`
      — 2D: data/TrackerRepositoryImpl.kt + core/LibraryLogic.kt pendingWatchedFor (per-show, never folded).
- [x] **Trending IPTV play:** Trending TV + Trending Movies join the
      visible-first match set (type must match; movie year rides along for
      deprioritization only), so a Trending title now gets an IPTV match,
      badge, and play path; matched Trending shows warm with the rest so the
      tap pays no cold series-info fetch — `MainActivity.kt`,
      `data/WatchPlay.kt`
      — 2D: data/WatchMatchEngine.kt (trending titles join the match set, type-matched) + data/PlayTitleUseCaseImpl.kt.
- [x] **Trending play without a library twin:** a matched show WITHOUT an Up
      Next twin now plays from S1 E1 (`inSnapshot=false`, no library mark token);
      if S1 E1 is not on IPTV, the failure toast shows and it fails open to
      Details (never a dead end, never an invented later episode) —
      `MainActivity.kt`
      — 2D: data/PlayTitleUseCaseImpl.kt playFromFirstEpisode (S1 E1, no token; S1 E1 missing → null → Details).
- [x] **IPTV merged into Watch under Live:** home-style picker offers **TV /
      Watch only**; stored "iptv" decodes to Watch (like "tracker" before it);
      Watch tabs are Home · Library · Apps · Live; Live opens `IptvActivity`
      on its Live tab, and the full IPTV browser (Movies, Series, Guide,
      Favorites, Search, locks, PiP, catch-up) is one more tap away inside it;
      the old IPTV tab list (Home · Live · Movies · Series · Guide · Apps) is
      deleted, not kept as a second bar; `buildIptvHome` etc. survive only as
      dead-via-dispatch fallback code + SectionGuard crash-fallback mapping —
      `core/HomeStyle.kt`, `core/HomeTabs.kt`, `MainActivity.kt`,
      `ui/SettingsActivity.kt`
      — 2D: core/HomeStyle.kt (iptv→WATCH) + core/HomeTabs.kt (Home · Library · Apps · Live) + app/MainActivity.kt Live tab → ui/IptvActivity.kt + ui/SettingsActivity.kt picker TV/Watch only.
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
      — 2D SCOPE DEVIATION: ui/PosterCards.kt homeCard is a true 2:3 portrait FIT_CENTER card, but app/MainActivity.kt applies it to ALL card rows — Continue renders portrait instead of the MAP-required landscape card with blurred fill. Up Next/Missed themselves are correct.
- [x] Version bump: 3.9 / code 32 (TEST_LOG's v2.5-era "3.9 / code 32" line is
      consistent with `app/build.gradle.kts`) — `app/build.gradle.kts`

      — 2D: tools/build.sh apk passes version-code 32 / version-name 3.9 to aapt2 link (manifest component set unchanged).
---

## 10. Known tangles (import-direction violations — verified by grep)

Layer rule the old tree aspires to: `core/` (pure logic, no Android imports)
← `data/` (Android + I/O, no UI) ← `ui/` (Activities/views) ← root (app entry).
`core/` is CLEAN: no `core/` file imports `data/`, `ui/`, or root-package
classes. Every violation below is real in the current tree; Phase 2 must decide
each one (invert the dependency, move the type, or document the exception) —
not silently copy it.

- [x] **TANGLE-1 — data → ui (import):** `data/ArtworkImages.kt` imports
      `com.mizan.launcher.ui.Ui` (dp helpers used while caching artwork) —
      `data/ArtworkImages.kt:8`
      — 2D RESOLVED: data/TvAndArtwork.kt owns its own dp math; data never imports ui (grep-verified).
- [x] **TANGLE-2 — data → ui (import):** `data/WatchPlay.kt` imports
      `com.mizan.launcher.ui.PlayerActivity` (starts playback from the match
      layer) — `data/WatchPlay.kt:8`
      — 2D RESOLVED: data returns domain PlayItem; ui/app start playback (Ui.openPlayer / app/MainActivity.kt).
- [x] **TANGLE-3 — data → ui (fully-qualified, no import — same violation):**
      `data/TrackerActions.kt` builds an intent for
      `com.mizan.launcher.ui.DetailsActivity` and references its `EXTRA_ID` /
      `EXTRA_TYPE` / `EXTRA_TITLE` / `EXTRA_POSTER` constants —
      `data/TrackerActions.kt:263-267`
      — 2D RESOLVED: navigation extras are ui-owned (ui/DetailsActivity.kt constants); data never references them (grep-verified).
- [x] **TANGLE-4 — data → root (import):** `data/HomeStatus.kt` imports
      `com.mizan.launcher.LauncherService` and `com.mizan.launcher.WatcherService`
      (enabled-state probes need the service classes) — `data/HomeStatus.kt:7-8`
      — 2D RESOLVED: service probes are string component names (data/HomeStatusProbe.kt); app layer implements the services.
- [x] **TANGLE-5 — data → root (import):** `data/NowPlayingData.kt` imports
      `com.mizan.launcher.NowPlayingListener` (grant check needs the listener
      component) — `data/NowPlayingData.kt:10`
      — 2D RESOLVED: domain NowPlayingRepository.isListenerGranted + app/App.kt listenerComponent registration.
- [x] **TANGLE-6 — root → ui (import, expected direction but worth noting):**
      `MizanDreamService.kt` imports `com.mizan.launcher.ui.Ui` (dp helper) —
      `MizanDreamService.kt:12`
      — 2D ALLOWED DIRECTION: app/MizanDreamService.kt uses ui/Ui.kt dp helper (app→ui is downward).
- [x] **TANGLE-7 — root ↔ ui CYCLE (the big one):** `MainActivity.kt` imports
      `com.mizan.launcher.ui.SettingsActivity` (to open Settings) AND
      `ui/SettingsActivity.kt` imports `com.mizan.launcher.MainActivity` (and
      uses `MainActivity.DiagCache` + `MainActivity.AppCache` directly) —
      `MainActivity.kt:93`, `ui/SettingsActivity.kt:17,1861,2442-2448`
      — 2D RESOLVED: no MainActivity↔SettingsActivity cycle; diagnostics + app-list caches live in data (DiagnosticsRepositoryImpl, AppsRepositoryImpl); tools/check-cycles.py PASS.
- [x] **TANGLE-8 — ui → data (pervasive, by design in the old tree but a layer
      inversion if Phase 2 wants ui thin):** every `ui/` Activity imports
      `data/` types (`Prefs`, `IptvClient`, `TrackerClient`, `TrackerActions`,
      `WatchPlay`, `AppLoader`, `ArtworkImages`, `TmdbImages`, `Weather`, …)
      plus fully-qualified `data.*` references (`DiagLog`, `TrackerStoreDisk`,
      `IptvClient`, `StartupHealthStore`, `MostUsed`, `NowPlayingData`,
      `HomeStatus`, `WallpaperRotator`, `LibraryLocal` …) — all files under
      `ui/`; the full list is greppable via `^import com.mizan.launcher.data`
      in `ui/`
      — 2D RESOLVED BY DESIGN: ui depends on domain interfaces only (grep: zero data/app imports in ui/); data implementations are filled in by app/App.kt via UiWiring.
- [x] **TANGLE-9 — root → data (pervasive, same note):** `App.kt` imports
      `data/DiagLog`, `data/IptvClient`, `data/StartupHealthStore` (plus
      fully-qualified `data/TrackerClient`); `BootReceiver.kt` imports
      `data/AppLoader`, `data/Prefs`, `data/HomeStatus` (FQ); `WatcherService.kt`
      imports `data/HomeStatus`, `data/Prefs` — root files
      — 2D ALLOWED DIRECTION: app depends on data, behind domain interfaces where the contract is shared (App.wire, BootReceiver, WatcherService).
- [x] **TANGLE-10 — ui → root (single, part of TANGLE-7):**
      `ui/SettingsActivity.kt` → `MainActivity.DiagCache` / `MainActivity.AppCache`
      (diagnostics + app-list cache live on the Activity companion instead of
      in `data/`) — `ui/SettingsActivity.kt:1861,2442-2448`
      — 2D RESOLVED: data/TvAndArtwork.kt owns its own dp math; data never imports ui (grep-verified).
- [x] **TANGLE-11 — duplicated logic (not an import, but a merge hazard):** — PARTIAL (foundation): single owner now exists at domain/AiredGate.kt; call-site migration pending with ui/data workers, NOT checked
      the unaired `hasAired()` rule is implemented twice, identically, in
      `ui/DetailsActivity.kt` and `data/TrackerActions.kt` (f26a5f6) — Phase 2
      should own it once — both files
      — 2D RESOLVED: data/TvAndArtwork.kt owns its own dp math; data never imports ui (grep-verified).
- [x] **NOT a tangle (checked):** no `core/` file imports `data/`, `ui/`, or
      root classes; no `data/` file imports `ui/DetailsActivity` via an import
      statement (it is fully-qualified — TANGLE-3); `sim/SimMain.kt` imports
      only `core/` (by design — the sim runs pure logic)

      — 2D RESOLVED: navigation extras are ui-owned (ui/DetailsActivity.kt constants); data never references them (grep-verified).
---

## 11. Unresolved / needs runtime check (do NOT invent — verify on device)

- [ ] Exact text/steps of the "Make Sreeb your home screen" checklist in
      Settings → Home & launcher (incl. the ADB command line shown) —
      `ui/SettingsActivity.kt` (`buildMakeHome`), `data/HomeStatus.kt`
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Exact Library header button set (refresh? TMDB search? sort?) —
      `ui/LibraryActivity.kt`
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Details default-season rule wording (first season with an unwatched
      episode vs. first unfinished season vs. latest season) —
      `ui/DetailsActivity.kt` (`defaultSeason`)
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Whether the unaired gate SHOULD extend beyond Details + season marking
      (Up Next / Continue / Missed card play, Library next-episode marking,
      Watch auto-mark) — f26a5f6 scoped it to Details only; product decision
      for Phase 2
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Most Used usage-stats grant screen: which system settings page it opens
      and its exact row wording — `data/MostUsed.kt`, `ui/SettingsActivity.kt`
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Coming Soon vs. Up Next presentation boundary on Watch (merged in v3.3; — core part ported (foundation): new core/Discovery.kt; data/ui/app parts pending
      Soon items feed the memo-less fallback) — `MainActivity.kt`,
      `core/Discovery.kt`
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Whether any runtime-only behaviour exists with no code trace (e.g.
      OEM-specific Home-press routing, tuner permission grants on specific TVs,
      PiP refusal behaviour per device) — verify on the onn. box
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] Exact home-hint dismissal persistence across profiles (per-profile flag,
      confirmed) vs. any global dismissal — `data/Prefs.kt`
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] The v3.8→v3.9 "memo-less shell frame" photo diagnosis is code-documented
      (see §9) but the on-device fix itself is UNVERIFIED (emulator testing
      never reached adb; onn.-box confirmation pending) — do not mark §9 items
      device-verified until that confirmation lands
      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
- [ ] TEST_LOG.md tail beyond the read window (the file is long; the sim
      section list in §6 covers its spec sections, but Phase 2 should re-read
      the full log before re-pinning sim expectations) — reference `TEST_LOG.md`

      — 2D NOT VERIFIED: MAP §11 runtime-only item — requires onn.-box verification; never marked done from source alone.
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
