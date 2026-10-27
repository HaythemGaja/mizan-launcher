# Mizan Launcher rebuild — Architecture (Phase 2A foundation)

Package `com.mizan.launcher`, display name "Sreeb", minSdk 26,
targetSdk 34, versionCode 32 / versionName 3.9, Views-only Kotlin,
kotlin-stdlib 1.9.24 as the only dependency. Target device: onn.
Android TV box, Android 12, armeabi-v7a + arm64. No native code.

## 1. Layer rules (strict, one-way, enforced by tools/check-cycles.py)

```
core  -> depends on nothing from this app (stdlib + java.* only)
domain -> may depend on core only; NO android.* imports at all
data  -> may depend on core, domain
ui    -> may depend on core, domain, data
app   -> may depend on core, domain, data, ui   (root layer)
```

Concretely: data must NEVER import ui or app; domain must NEVER
import data/ui/app; core must NEVER import domain/data/ui/app; ui
must NEVER import app. Fully-qualified references count exactly
like imports (the old tree's TANGLE-3 pattern). Any import cycle,
across or within layers, fails the check.

How the old tree's tangles are resolved (MAP §10):

| Old tangle | Rebuild resolution |
|---|---|
| TANGLE-1 data→ui (`ArtworkImages` used `ui.Ui` dp helpers) | dp helpers move to core (pure math) or data owns its own copy; data never imports ui |
| TANGLE-2 data→ui (`WatchPlay` started `PlayerActivity`) | data returns a domain `PlayItem`; ui/app starts playback from it |
| TANGLE-3 data→ui fully-qualified (`DetailsActivity` extras) | navigation extras become domain constants / a ui-owned contract the data layer references only via domain |
| TANGLE-4 data→root (`HomeStatus` probed services) | service probes become domain `GlobalActionsGateway` / `HomeWatcherProbe` interfaces; app layer implements them |
| TANGLE-5 data→root (`NowPlayingData` needed listener) | grant check becomes domain `NowPlayingRepository.isListenerGranted()`; app layer supplies the component name |
| TANGLE-6 root→ui (dp helper) | same as TANGLE-1 |
| TANGLE-7 root↔ui cycle (`MainActivity` ↔ `SettingsActivity`, `DiagCache`/`AppCache`) | diagnostics + app-list caches move to data (`DiagLog`, app cache); ui and app both depend on data, never on each other |
| TANGLE-8 ui→data pervasive | resolved by design: ui depends on domain interfaces; data implements them. Direct ui→data imports are allowed only where a domain interface does not yet exist, and must be migrated |
| TANGLE-9 root→data pervasive | allowed direction (app depends on data); keep it behind domain interfaces where the contract is shared |
| TANGLE-10 ui→root (`DiagCache`/`AppCache`) | same as TANGLE-7 |
| TANGLE-11 duplicated `hasAired()` | owned once: domain `AiredGate` (see §4) |

## 2. Package map

```
app/src/main/java/com/mizan/launcher/
  core/     64 files, ported verbatim from the old core/ (see §3).
            Pure logic: codecs, ordering, matching, parsing, state
            machines, design tokens. Zero Android imports (verified
            by grep and by check-cycles.py).
  domain/   14 files, new in this phase. Pure models + repository
            interfaces + use-case interfaces. Zero imports from
            data/ui/app and zero android.* imports.
  data/     24 files (Phase 2B, complete). Android + I/O: prefs (PrefsSafe
            funnel), network, files, provider/store, playback glue.
            Resolves TANGLE-1..5, 9 (zero ui/app imports, verified).
  ui/       22 files (Phase 2C, complete). Activities, dialogs, pickers, cards.
            Depends on domain interfaces; thin over data.
  app/      8 files (Phase 2D, complete): App, MainActivity,
            BootReceiver, LauncherService, WatcherService,
            NowPlayingListener, MizanDreamService.
app/src/main/res/
  values/strings.xml, values/themes.xml, xml/launcher_config.xml,
  xml/watcher_config.xml, drawable/*, mipmap-anydpi-v26/*
  (copied from the v3.9 reference; identity unchanged).
app/src/main/AndroidManifest.xml
  Complete (Phase 2D): full v3.9 component set, root-layer classes under
  .app.*, UI activities under .ui.* — all 12 activities, both accessibility
  services, NowPlayingListener, MizanDreamService, BootReceiver declared.
tools/
  build.sh          manual pipeline (aapt2 + kotlinc + d8 +
                    zipalign + apksigner), -Xmx512m per unit
  check-cycles.py   import-graph + layer-direction checker
```

## 3. core/ port — what was done and verified

- All 64 old `core/` files copied byte-identical into new `core/`
  (`diff -r` clean). No cleanup was needed: the old core was
  already clean — no file imports `data/`, `ui/`, or root classes,
  and no file imports any `android.*` type (only `java.*` stdlib
  imports, plus package-name strings such as
  `com.google.android.youtube.tv` in `AppCategories.kt`, which are
  data, not imports).
- Compile proof (this VM, ~302 MB available, zero swap):
  `tools/build.sh core` — kotlinc 1.9.24, `-J-Xmx512m`,
  64 files → 352 classes, 70 s, exit 0. No file was split or
  trimmed; the whole core fits one 512 MB unit.
- Note: the old tree's core total is 11,989 lines; the largest
  files are `TrackerLogic.kt` (1,171), `LibraryLogic.kt` (1,120),
  `IptvLogic.kt` (927). If a future edit pushes core over the
  512 MB budget, split it along the dependency order in §5 —
  never raise the heap.

## 4. domain/ layer — what it covers

| File | Covers (MAP areas) |
|---|---|
| Models.kt | Tracked shows/movies, episodes, progress, plan-to-watch, ignored/muted, home cards |
| AiredGate.kt | **Single owner of the f26a5f6 unaired gate** (§8.1): blank = aired, malformed = aired, else date <= device local today, today counts; toast text; aired-only filter; remaining-aired count. Callers in Details + season marking MUST use this, never re-implement it |
| TrackerRepository.kt | Local-first tracker store (post-v3.7: no provider queries after the import flag); Up Next / Missed (per-show pending scoping) / Continue / For You / plan-to-watch / ignored / season episodes / optimistic writes |
| TmdbRepository.kt + TmdbImages | Proxy-first / launcher-key-direct-second TMDB; error kinds; poster w342 / backdrop w780 |
| IptvRepository.kt | Xtream + M3U catalogues (not merged/deduped), guide, favorites, resume (finished = near end or >= 95%), category locks, PlayItem construction |
| AppsRepository.kt | Installed apps (no Bitmap/Drawable in domain — artwork by preset/path only), folders (depth 3, rehome-on-delete rules), favorites, recents (cap 8), most-used ordering, hidden apps |
| WeatherRepository.kt | Open-Meteo current + 24 h + 7-day, units |
| SettingsRepository.kt | HomeStyle TV/WATCH only (legacy "ipad"→TV, "tracker"/"iptv"→WATCH decode lives in core/HomeStyle; data maps it here), themes, wallpaper kinds, status-bar settings, profiles + Guest, locks, credentials (stored only, never exported/logged) |
| PlaybackRepository.kt | PlayItem/PlayQueue, resume, auto-mark (once; ended or exactly 90%; never tokenless), aspect cycle, speed ladder |
| NowPlayingRepository.kt | Media-session row (buffering reads as playing; dedupe by package; listener grant probe) |
| DiagnosticsRepository.kt | Sanitized diagnostics (counts/state only), breadcrumbs, copy block; accessibility gateway contracts |
| LockRepository.kt | Arrow-pattern gate (UDLRU codec lives in core/ArrowPin), unlock grace (exact boundary, future stamp never grants, profile switch always re-asks) |
| HomeSections.kt | Section ids + canonical default orders (TV rows, Watch sections; hero always first, hide/show-only) |
| UseCases.kt | GetUpNext/Missed/Continue/Hero/ForYou/Trending, PlayTitle (incl. S1 E1 no-twin play), MarkEpisode (AiredGate-enforced), SearchTitles, NowPlaying, Forecast, Diagnostics, ManageFolders, RefreshDiscovery (12 h TTL) |

Domain rules for later workers:
- Domain interfaces are synchronous; implementations run on
  worker threads. UI owns threading + section state machines
  (LOADING / READY / EMPTY / ERROR + per-section retry).
- Domain `SectionIds` orders mirror core's canonical orders
  (`LayoutStore.RowOrder`, `TrackerSections`). If they ever
  disagree, core wins and this file must be updated; a later
  worker should collapse them to one source (open item, §7).
- Accessibility services (f26a5f6, MAP §8.2) are expressed as
  domain contracts only (`GlobalActionsGateway`,
  `HomeWatcherProbe`): global actions Back/Home/Recents/
  Notifications/Quick Settings/Power dialog;
  `canRetrieveWindowContent=false`; no gestures; no-op event
  handling; fail-open when disconnected; off by default; NO
  in-app toggle — opt-in is in system Accessibility settings.
  The service classes themselves land in app/.

## 5. Compile-unit plan (all units at -Xmx512m, one kotlinc JVM each)

Units compile in dependency order into one shared classes dir;
each later unit sees earlier units on its classpath:

1. `core` — 64 files, 11,989 lines. PROVED: 70 s, 352 classes
   at 512 MB. Single unit; do not split unless a future edit
   breaks the budget.
2. `domain` — 14 files, small pure models/interfaces. Depends
   on core only via classpath (in practice it imports nothing
   from core today, which keeps the unit trivially small).
3. `data` (later worker) — ~30 files. If it OOMs at 512 MB,
   split by dependency, e.g. `data-a` (prefs/store/disk:
   Prefs, PrefsSafe, TrackerStoreDisk, HomeCacheDisk,
   StartupHealthStore, DeathStore, DiagLog) then `data-b`
   (network/clients: IptvClient, TrackerClient, TmdbDirect,
   Weather, fetchers) then `data-c` (glue: TrackerActions,
   WatchPlay, LibraryLocal, DiscoveryData) — each later unit
   on the classpath of the earlier ones. Never raise the heap.
4. `ui` (later worker) — ~19 files + card builders. Split
   similarly if needed (dialogs/pickers first, activities
   second), activities depending only on domain interfaces
   plus data implementations already compiled.
5. `app` (later worker) — Application, MainActivity,
   services, receivers. Small; compiles last against all units
   + android.jar. `app/` (8 files, Phase 2D) compiles last in the
   `units` pass and in the full `apk` pipeline.

Why this fits: each unit is bounded (largest proven unit is
core at ~12k lines / 352 classes in 70 s at 512 MB); data and
ui are each smaller than core in file count and have no
single file larger than `TrackerLogic.kt`. The shared-classes
accumulation keeps cross-unit references resolvable without
a single giant kotlinc invocation (the failure mode that
killed the old 121-file build on this VM).

Build script modes: `tools/build.sh core` (proof target),
`domain` (core+domain), `units` (every present unit, missing
layers reported as skipped), `apk` (full aapt2/d8/zipalign/
apksigner pipeline; REFUSES unless every layer exists and
`KEYSTORE_PASS` is set in the environment — the password is
never written to any file, and the keystore is never copied
into this tree).

## 6. How later workers plug in

- **Data worker:** implement every domain repository interface.
  Map core codecs/logic to domain models; never import ui/app;
  resolve TANGLE-1..5 by the table in §1. Keep the type-tolerant
  prefs funnel (`PrefsSafe`: wrong-typed values evict to
  defaults), fail-open decoding everywhere, JSON depth cap 64,
  credential never-export rule, and the diagnostics sanitization
  rule. Add units to `tools/build.sh` only by extending the
  `apk`/`units` layer lists — do not change heap flags.
- **UI worker:** build Activities/dialogs/pickers/cards against
  domain interfaces + core logic only; keep every rebuild
  guarded (throwing section → error tile, never a dead home);
  honour view caps (browser/guide grids 200, home rows 20);
  use `AiredGate` for every unaired call site; keep posters
  complete, never centre-cropped.
- **App worker:** Application, MainActivity (staged shell →
  warm home, cold chain, safe shell, emergency root), the two
  Sreeb accessibility services (configs already in res/xml),
  NowPlayingListener, MizanDreamService, BootReceiver. Keep:
  no foreground services, no WorkManager/AlarmManager/
  JobScheduler/AppWidget/NotificationManager usage anywhere.
- **All workers:** run `tools/check-cycles.py` and
  `tools/build.sh core` (plus your unit) before handing off;
  update CHECKLIST.md only for items actually implemented in
  the new tree, naming the new file(s).

## 7. Open items / things that did NOT fit this phase

- SUPERSEDED (Phase 2A note, kept for history): data, ui, and app layers
  are all complete as of Phase 2D, and `tools/build.sh apk` produces the
  signed APK at out/Mizan-Launcher-rebuild.apk (Phase 3 review: SHIP for
  onn.-box device confirmation). The "no APK" state no longer applies.
- `domain/SectionIds` canonical orders duplicate core's orders
  (see §4 note) — consolidation to one source is a data-worker
  task, not done here.
- `domain` currently imports nothing from `core`. If a later
  worker wants domain to reuse core types directly (e.g.
  `HomeStyle` decode, `ArrowPin` codec), that is allowed
  downward (domain→core) but must be added deliberately and
  re-checked with `check-cycles.py` — not by accident.
- MAP §11 runtime-only questions (exact Make-Home checklist
  text, Library header buttons, default-season wording, whether
  the unaired gate should extend beyond Details, tuner/PiP
  behaviour per device, the unverified v3.9 shell-frame fix)
  are NOT resolved by this foundation; they need onn.-box
  verification by later workers and must not be marked done
  from source alone.
- The sim harness (`sim/SimMain.kt` + `run_sim.sh` + TEST_LOG
  expectations) is not re-pinned in this phase; the core port
  is byte-identical so its expectations should carry over, but
  re-pinning is a later worker's job.
