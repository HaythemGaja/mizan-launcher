package com.mizan.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.ArtworkStore
import com.mizan.launcher.core.Folder
import com.mizan.launcher.core.FolderEntry
import com.mizan.launcher.core.FolderGate
import com.mizan.launcher.core.FolderLogic
import com.mizan.launcher.core.FolderTree
import com.mizan.launcher.core.GuestGate
import com.mizan.launcher.core.HeroRotator
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLocks
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvStatus
import com.mizan.launcher.core.LiveTvLogic
import com.mizan.launcher.core.MostUsedLogic
import com.mizan.launcher.core.MoveContainer
import com.mizan.launcher.core.MoveSessionMachine
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.NowPlayingLogic
import com.mizan.launcher.core.QuickSettings
import com.mizan.launcher.core.RecentsStore
import com.mizan.launcher.core.Discovery
import com.mizan.launcher.core.HomeTabs
import com.mizan.launcher.core.UnifiedBar
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerSections
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.core.RotationLogic
import com.mizan.launcher.core.RowOrder
import com.mizan.launcher.core.SectionGuard
import com.mizan.launcher.core.StartupHealth
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.core.WeatherLogic
import com.mizan.launcher.core.WifiLabel
import com.mizan.launcher.data.AccentUtil
import com.mizan.launcher.data.AppEntry
import com.mizan.launcher.data.DiagLog
import com.mizan.launcher.data.DiscoveryData
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.ArtworkImages
import com.mizan.launcher.data.BingFetcher
import com.mizan.launcher.data.LiveTv
import com.mizan.launcher.data.MostUsed
import com.mizan.launcher.data.NowPlayingData
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.StartupHealthStore
import com.mizan.launcher.data.TmdbImages
import com.mizan.launcher.data.TrackerActions
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.PlayItem
import com.mizan.launcher.data.PlayQueue
import com.mizan.launcher.data.WatchPlay
import com.mizan.launcher.data.TrackerClient
import com.mizan.launcher.data.TvData
import com.mizan.launcher.data.WallpaperRotator
import com.mizan.launcher.data.WatchItem
import com.mizan.launcher.data.Weather
import com.mizan.launcher.data.WeatherArt
import com.mizan.launcher.ui.ArrowPinDialog
import com.mizan.launcher.ui.ArtworkPicker
import com.mizan.launcher.ui.ForecastActivity
import com.mizan.launcher.ui.IptvActivity
import com.mizan.launcher.ui.IptvCards
import com.mizan.launcher.ui.IptvGuideActivity
import com.mizan.launcher.ui.PlayerActivity
import com.mizan.launcher.ui.SearchActivity
import com.mizan.launcher.ui.SettingsActivity
import com.mizan.launcher.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen. One calm screen, Views only:
 * top bar (clock/date, weather, network, quick tiles) + rows in the
 * profile's chosen order (Favorites, Folders, Continue Watching, Inputs,
 * All Apps). Rebuilt on resume so Settings changes apply immediately.
 * Every optional row hides itself on any failure (fail-open).
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var layout: LayoutData
    private var apps: List<AppEntry> = emptyList()
    private var appsByPkg: Map<String, AppEntry> = emptyMap()
    private var accent: Int = 0
    private var textColour: Int = 0
    private var subColour: Int = 0

    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var networkView: TextView? = null
    private var firstTile: View? = null

    // ---- v2.0 state ----
    /** This profile's custom artwork, parsed once per rebuild. */
    private var artworkByPkg: Map<String, ArtworkStore.Entry> = emptyMap()

    // Tracker-view working set, computed once per rebuild by
    // prepareTrackerData() so the root background (hero backdrop)
    // and the hero text block can never disagree about the hero.
    private var tSnap: TrackerClient.Snapshots = TrackerClient.Snapshots.EMPTY
    private var tMarked: List<TrackerLogic.MarkedEntry> = emptyList()
    private var tUpNext: List<TrackerLogic.UpNextItem> = emptyList()
    private var tCont: List<TrackerLogic.ContinueItem> = emptyList()
    private var tPicks: List<TrackerLogic.ForYouPick> = emptyList()
    private var tHeroCandidates: List<TrackerLogic.HeroItem> = emptyList()
    /** v2.7: the discovery working set (data/DiscoveryData's last landed state). */
    private var dState: DiscoveryData.State = DiscoveryData.State.EMPTY
    private var dSoon: List<Discovery.SoonItem> = emptyList()
    private var dTrendTv: List<Discovery.TrendItem> = emptyList()
    private var dTrendMovies: List<Discovery.TrendItem> = emptyList()

    // v2.3 Watch working set (Watch style only), computed alongside
    // the tracker set in buildRoot: the tracker↔IPTV match table
    // (data/WatchPlay, keyed "type|tmdbId"). v2.4: matches run
    // over the disk store's tuples — there is no materialized
    // catalogue to keep here any more. Empty in every other style.
    private var wMatches: Map<String, WatchMatch.Match> = emptyMap()

    // v2.4 IPTV home working set: the composed rows plus the slice
    // items behind them (card-click lookups), loaded on a worker
    // thread from the disk store, keyed by the store generation
    // they were read from (IptvClient.storeStamp).
    // ---- v2.8 cold start (spec §28) ----
    /**
     * True until the cold-warm worker lands: while set, builds
     * render the STAGED SHELL — frame, status bar, tab bar and
     * section frames from in-memory/prefs-light state only (no
     * tracker snapshot parse, no IPTV store index read, no match
     * table read). The warm worker runs those reads off the main
     * thread (warming the data layer's memos), then one full
     * rebuild fills the sections; the discovery / tracker-refresh /
     * slice / match landings keep filling per source afterwards,
     * exactly as they did before v2.8.
     */
    private var coldStart = true
    private var coldWarmScheduled = false

    /** v3.2: the memo-parse kick guard for prepareTrackerData (see there). */
    private var trackerMemoKickDone = false

    /** v3.2: wallpaper value the dynamic accent was last computed for (see resolveColours). */
    private var lastAccentWallpaper: String? = null

    // ---- v3.0 startup health (spec §30) ----
    /**
     * This process launched into the SAFE SHELL (data/
     * StartupHealthStore's decision, from core/StartupHealth):
     * two consecutive startups died uncleared, so this launch
     * renders the plain TV home — no hero/discovery/IPTV
     * composition — with the safe-mode notice. Read once in
     * onCreate; the decision is per-process.
     */
    private var safeShell = false

    /** Set once the startup-clear timer is armed for this activity. */
    private var startupClearScheduled = false

    /**
     * The CURRENT build fell back as a whole (tracker/IPTV home →
     * TV render, or the emergency root): the intended home did
     * NOT complete, so the startup-health clear must be HELD for
     * this build — a deterministic composition failure would
     * otherwise "clear" on every fallback render and the safe
     * shell could never arm. Section-level tiles do NOT set this:
     * the intended home rendered (degraded), the app opened.
     * Reset at every rebuild; read by the frame probe of the
     * build that set it.
     */
    private var buildDegraded = false

    /** Per-section failure-report throttle state (core/SectionGuard). */
    private val sectionReportAt = HashMap<String, Long>()

    /** 0 = no frame probe owed, 1 = first frame, 2 = home-complete frame. */
    private var frameProbeStage = 0

    /** The All Apps section of the current build (the Apps tab's focus target). */
    private var allAppsAnchor: View? = null

    private var iptvHomeStamp: Long = Long.MIN_VALUE
    private var iptvHomeLoading = false
    private var iptvHomeRows: List<IptvLogic.HomeRow> = emptyList()
    private var iptvHomeLive: List<IptvLogic.LiveChannel> = emptyList()
    private var iptvHomeVods: List<IptvLogic.VodItem> = emptyList()
    private var iptvHomeSeries: List<IptvLogic.SeriesItem> = emptyList()
    private var iptvHomeCats: List<IptvLogic.Category> = emptyList()

    // Hero rotation (core/HeroRotator owns the rules; the Handler
    // lives here — activity-scoped, no service).
    private var heroRotator: HeroRotator.State? = null
    private var heroTicking = false
    private var heroLayerFront: ImageView? = null
    private var heroLayerBack: ImageView? = null
    private var heroBackdropShown: String = ""
    private var heroTextHost: LinearLayout? = null
    private var heroButtonViews: MutableList<View> = mutableListOf()
    /** Role tag ("open"/"mark"/"next") of the hero button holding focus. */
    private var heroFocusRole: String? = null
    /** v2.9: the hero ▶ Play on-demand resolution phase (core/WatchMatch machine). */
    private var heroResolvePhase: WatchMatch.ResolvePhase = WatchMatch.ResolvePhase.IDLE
    private val heroTick = object : Runnable {
        override fun run() {
            if (!heroTicking) return
            val state = heroRotator
            if (state != null && isTrackerFamily()) {
                HeroRotator.tick(state, System.currentTimeMillis())?.let { next ->
                    heroRotator = next
                    applyHero(next.index)
                }
            }
            if (heroTicking) handler.postDelayed(this, 1_000)
        }
    }
    private val heroResumeCheck = Runnable {
        // Focus left every hero button: resume on the rotator's terms.
        if (heroButtonViews.none { it.hasFocus() }) {
            heroFocusRole = null
            heroRotator = heroRotator?.let {
                HeroRotator.focusReleased(it, System.currentTimeMillis())
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            // v1.4 housekeeping rides the tick (throttled inside):
            // wallpaper rotation / Bing day change / Now Playing changes.
            housekeeping()
            // One second ticks only when the seconds display is on.
            val interval = if (!prefs.safeMode && layout.statusBar.showSeconds) 1_000L else 20_000L
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.note("home opened")
        // v2.8: the startup breadcrumb trail (app → main → first
        // frame → home complete) — App.onCreate marked the zero.
        DiagLog.markStartup("main created")
        prefs = Prefs(this)
        // v3.2: fine-grained stage marks for the diagnostics
        // `startup stages:` line — the next slow-start photo can
        // name the stage instead of just the 10 s total.
        DiagLog.markStage("prefs ready")
        // v3.0: the safe-shell decision was made in App.onCreate
        // (data/StartupHealthStore.beginLaunch) — read it before
        // the first build so homeStyleKey() can force TV.
        safeShell = StartupHealthStore.safeShellArmed
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) rebuild()
        // The watcher reads this to avoid a relaunch loop against us.
        try {
            prefs.sp.edit().putLong("own_foreground_at", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // Cosmetic coordination only.
        }
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        // v3.2 (ask 43): NOTHING refreshes on resume any more.
        // The v2.0–v2.5 resume kicks (Live TV, WatchNext, Most
        // Used) and the rotation/Bing check all moved: Live TV /
        // WatchNext / Most Used refresh only via the explicit
        // "Refresh home data" control or a section Retry, and
        // rotation/Bing/weather run from the visible-home clock
        // tick (housekeeping) instead. A resume is pure
        // composition from the local cache.
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        // v2.0: hero rotation halts whenever the view isn't visible.
        stopHeroRotation()
    }

    /**
     * Move mode owns the D-pad while a tile is grabbed (v1.3) — but
     * only for home-screen sessions; folder-overlay sessions get their
     * keys from the dialog's own listener (separate window).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // The remote's Search key opens App Search (v1.4), unless a
        // move-mode session owns the D-pad right now.
        if (moveSession == null &&
            event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH
        ) {
            try { startActivity(Intent(this, SearchActivity::class.java)) } catch (e: Exception) {
                Prefs.noteError(this, "searchKey", e)
            }
            return true
        }
        val session = moveSession
        if (session != null && session.container != MoveContainer.FOLDER &&
            event.action == android.view.KeyEvent.ACTION_DOWN
        ) {
            return handleMoveKey(event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ UI

    private fun rebuild() {
        stopHeroRotation()
        // v3.3 (ask 46): re-read the safe-shell decision on every
        // rebuild, not just onCreate — the Diagnostics "Exit safe
        // mode" action clears the armed flag mid-process, and a
        // resumed MainActivity must pick that up instead of
        // rendering TV from the stale onCreate value.
        safeShell = StartupHealthStore.safeShellArmed
        buildDegraded = false
        try {
            layout = prefs.layout
            apps = AppCache.get(this)
            appsByPkg = apps.associateBy { it.pkg }
            artworkByPkg = if (prefs.safeMode) emptyMap() else prefs.artworkMap()
            DiagLog.markStage("apps ready")
            resolveColours()
            setContentView(buildRoot())
            DiagLog.markStage("composition done")
            // v2.8 cold-start staging: the shell's first frame is
            // probed (DiagLog's startup timings), and the warm
            // worker is kicked — the data reads the shell skipped
            // run THERE, never on this thread.
            if (coldStart) {
                attachFrameProbe(homeRoot, 1)
                scheduleColdWarm()
            } else if (frameProbeStage == 2) {
                frameProbeStage = 0
                attachFrameProbe(homeRoot, 2)
            }
            // v2.0 cold-start rule (watcher-redirect path): the
            // wallpaper bitmap decode/render happens OFF the first
            // frame — background thread, then the image is inserted
            // at the root's bottom (under the scrim) once decoded.
            // Tracker view never sets a pending wallpaper (its hero
            // backdrop loads through TmdbImages, already async).
            val pending = pendingWallpaper
            pendingWallpaper = null
            val rootView = homeRoot
            if (pending != null && rootView != null) {
                kotlin.concurrent.thread(name = "mizan-wallpaper") {
                    val bmp = try { pending() } catch (e: Throwable) { null }
                    if (bmp != null) {
                        rootView.post {
                            if (rootView.isAttachedToWindow) {
                                val iv = ImageView(this@MainActivity)
                                iv.setImageBitmap(bmp)
                                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                                rootView.addView(
                                    iv, 0, FrameLayout.LayoutParams(-1, -1)
                                )
                            }
                        }
                    }
                }
            }
            // v3.2 (ask 43): the rebuild only paints the CACHED
            // weather text — the fetch moved to the visible-home
            // tick (maybeFetchWeather, via housekeeping), so an
            // open/resume never pays for a network call here.
            updateWeatherWidget()
            firstTile?.post { if (currentFocus == null) firstTile?.requestFocus() }
        } catch (e: Throwable) {
            // v3.0: Throwable, not Exception (an inflation OOM is
            // an Error) — and the failure now renders the EMERGENCY
            // ROOT instead of leaving whatever content view was (or
            // wasn't) set. The repeating black screen WAS this
            // catch's silence: noteError recorded the death and the
            // window stayed empty on every launch.
            Prefs.noteError(this, "rebuild", e)
            try {
                setContentView(buildEmergencyRoot())
            } catch (ignored: Throwable) {
                // Nothing left to render with — the note above is
                // the evidence, and the uncleared startup arms the
                // safe shell for the next launch.
            }
        }
    }

    /**
     * v3.0 emergency root (core/HomeFallback's EMERGENCY_ROOT):
     * rendered when buildRoot itself fails, so the window is never
     * left empty. Deliberately minimal — a notice, the Settings &
     * Diagnostics entry (focus lands there), and the apps section
     * when IT builds. Surviving this render clears the
     * startup-health counter like any completed home (the stage-2
     * probe attached below).
     */
    private fun buildEmergencyRoot(): View {
        // The emergency root is a fallback, not a completed home:
        // hold the startup-health clear for this build.
        buildDegraded = true
        val root = FrameLayout(this)
        val preset = Themes.preset("apple_black")
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(preset.bgTop, preset.bgBottom)
        )
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(
            Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 40)
        )
        val notice = TextView(this)
        notice.text = "Sreeb hit a problem building the home screen."
        notice.setTextColor(preset.text)
        notice.textSize = 20f
        content.addView(notice)
        val settings = TextView(this)
        settings.text = "⚙  Settings & Diagnostics"
        settings.setTextColor(preset.text)
        settings.textSize = 17f
        settings.isFocusable = true
        settings.isClickable = true
        settings.setPadding(
            Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13)
        )
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.setMargins(0, Ui.dp(this, 16), Ui.dp(this, 60), Ui.dp(this, 12))
        settings.layoutParams = slp
        settings.background = Ui.tileBackground(
            preset.accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this
        )
        settings.setOnClickListener { openSettings() }
        content.addView(settings)
        firstTile = settings
        try {
            content.addView(buildAppsSection())
        } catch (ignored: Throwable) {
            // Even the apps section failed — the two entries above
            // are the floor, and they are enough to reach help.
        }
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        homeRoot = root
        attachFrameProbe(root, 2)
        return root
    }

    /**
     * One-shot pre-draw probe on a freshly built root: when its
     * first frame is about to draw, record the startup milestone
     * (stage 1 = the staged shell's first frame; stage 2 = the
     * warmed home's frame — the cold start is over). The TV style
     * has no staged second phase (its data was already
     * memory-light), so its first frame also completes the start.
     */
    private fun attachFrameProbe(root: View?, stage: Int) {
        val r = root ?: return
        val appCtx = applicationContext
        r.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    r.viewTreeObserver.removeOnPreDrawListener(this)
                    if (stage == 1) {
                        DiagLog.recordFirstFrame(appCtx)
                        if (!isTrackerFamily() && !isIptvStyle()) {
                            DiagLog.recordHomeComplete(appCtx)
                            onStartupRenderCompleted()
                        }
                    } else {
                        DiagLog.recordHomeComplete(appCtx)
                        onStartupRenderCompleted()
                    }
                    return true
                }
            }
        )
    }

    /**
     * A home render completed (the frame probe's completing
     * stage). The startup-health clear arms ONLY when the build
     * that rendered was the intended home — a whole-home
     * fallback (tracker/IPTV → TV render, or the emergency root)
     * means the startup did NOT complete, and clearing here
     * would reset the counter on every fallback render so the
     * safe shell could never arm against a deterministic
     * composition crash. The hold is breadcrumbed so Diagnostics
     * shows it. Section-level error tiles do not hold the clear:
     * the intended home rendered and the app opened.
     * v3.3 (ask 46): the delay comes from StartupHealth
     * .clearDelayMs — a SAFE-SHELL launch clears IMMEDIATELY on
     * its full render instead of waiting 10s, because a TV that
     * kills the process inside the window could never clear and
     * left him trapped in safe mode. The anti-brick property
     * survives: the next NORMAL launch still needs the 10s
     * survival, and two uncleared normal deaths still re-arm.
     */
    private fun onStartupRenderCompleted() {
        when (StartupHealth.clearDelayMs(safeShell, buildDegraded)) {
            null -> DiagLog.note("startup health: degraded build — clear held")
            0L -> StartupHealthStore.markCleared(applicationContext)
            else -> scheduleStartupClear()
        }
    }

    /**
     * v3.0: arm the startup-health clear (core/StartupHealth): the
     * home completed its full render, so if the process now
     * survives [StartupHealth.SURVIVAL_MS] the persisted uncleared
     * counter resets. The runnable rides the main looper — a
     * process that dies first takes the message with it, which is
     * exactly the semantics the safe shell needs. Armed once per
     * activity; later rebuilds don't re-arm (the counter is
     * already 0 by then in the normal case).
     */
    private fun scheduleStartupClear() {
        if (startupClearScheduled) return
        startupClearScheduled = true
        val appCtx = applicationContext
        handler.postDelayed(
            { StartupHealthStore.markCleared(appCtx) },
            StartupHealth.SURVIVAL_MS
        )
    }

    /**
     * The cold-warm worker (v2.8): runs the first-build data reads
     * the staged shell skipped — the tracker snapshot parse, the
     * IPTV store index load, the Watch match-table read — purely
     * to warm the data layer's memos off the main thread. Landing
     * flips [coldStart] off and rebuilds ONCE with everything
     * warm; per-source async landings (discovery, tracker refresh,
     * IPTV slices, match recompute) fill further from there, as
     * they always have.
     */
    private fun scheduleColdWarm() {
        if (coldWarmScheduled) return
        coldWarmScheduled = true
        val appCtx = applicationContext
        kotlin.concurrent.thread(name = "mizan-coldwarm") {
            try { TrackerClient.cached(appCtx) } catch (e: Exception) {
                // Warm-up is an optimisation; the full build re-reads fail-open.
            }
            DiagLog.markStage("warm: tracker read")
            try { IptvClient.storeStamp(appCtx) } catch (e: Exception) {
                // As above.
            }
            DiagLog.markStage("warm: store read")
            try { WatchPlay.matches(appCtx) } catch (e: Exception) {
                // As above.
            }
            DiagLog.markStage("warm: matches read")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                coldStart = false
                if (isTrackerFamily() || isIptvStyle()) {
                    frameProbeStage = 2
                    rebuild()
                }
            }
        }
    }

    /** Theme colours for this profile, with Dynamic accent and Safe mode applied. */
    private fun resolveColours() {
        if (prefs.safeMode || safeShell) {
            val p = Themes.preset("apple_black")
            accent = p.accent; textColour = p.text; subColour = p.subText
            return
        }
        val preset = Themes.preset(prefs.themeId)
        textColour = preset.text
        subColour = preset.subText
        accent = if (preset.dynamic) {
            // v2.5: the recompute (a wallpaper decode for image /
            // Reddit kinds) runs off the main thread now — this
            // frame uses the preset accent, a landed change
            // rebuilds once with the real one.
            // v3.2 (ask 43): recompute ONLY when the wallpaper
            // actually changed — the old code re-decoded on every
            // rebuild (every resume), pure open-path churn.
            if (prefs.accentOverride == 0 &&
                com.mizan.launcher.core.RefreshPolicy.accentRecomputeNeeded(
                    lastAccentWallpaper ?: "", prefs.wallpaperValue
                )
            ) {
                lastAccentWallpaper = prefs.wallpaperValue
                AccentUtil.recomputeAsync(this, prefs) { rebuild() }
            }
            if (prefs.accentOverride != 0) prefs.accentOverride else preset.accent
        } else preset.accent
    }

    /**
     * The dim corner gear (the always-visible way back into
     * Settings): shown when the status bar can't carry its
     * Settings tile — and, since v3.0, also installed when the top
     * bar's build fails, so a bar failure can never trap the user
     * out of Settings.
     */
    private fun addCornerGear(root: FrameLayout) {
        val gear = quickTile("⚙") { openSettings() }
        // v2.0: hold-OK on the gear opens Quick settings.
        gear.setOnLongClickListener { showQuickSettings(); true }
        gear.alpha = 0.55f
        val glp = FrameLayout.LayoutParams(-2, -2)
        glp.gravity = Gravity.TOP or Gravity.END
        glp.setMargins(0, Ui.dp(this, 18), Ui.dp(this, 40), 0)
        gear.layoutParams = glp
        root.addView(gear)
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this)

        // v2.0: the Tracker view computes its working set FIRST — the
        // root background below IS the hero backdrop, so the hero
        // selection must exist before any view is added.
        // v2.3: the Watch style is the same composition + the IPTV
        // link, so every "trackerStyle" gate below is family-wide.
        val trackerStyle = isTrackerFamily()
        // v3.0: the safe shell composes like safe mode (plain
        // frame, favorites + apps rows only, no wallpaper work) —
        // one gate for both, so the shell path adds no composition
        // of its own that could fail.
        val shellSafe = prefs.safeMode || safeShell
        // v2.7: discovery (Coming Soon / Trending / launcher For
        // You / IPTV trending) prepares BEFORE the tracker working
        // set — prepareTrackerData consults it for the For You
        // source. Its snapshot never blocks: stale state renders,
        // the worker's landing rebuilds once.
        // v3.0: each prepare is Throwable-guarded — a prepare
        // failure degrades its working set to the fail-open empty
        // state, never the whole home with it.
        if (trackerStyle || isIptvStyle()) {
            try { prepareDiscoveryData() } catch (e: Throwable) {
                Prefs.noteError(this, "prepareDiscovery", e)
            }
            DiagLog.markStage("discovery ready")
        }
        // v2.8: on the COLD build both prepares are deferred to the
        // warm worker — the tracker prepare parses the snapshot
        // documents and the Watch prepare reads the match table,
        // and neither may sit between the process start and the
        // first frame. The Watch listener still installs (it IS
        // the fill path); the tracker refresh has no listener to
        // miss — the warm landing's rebuild runs both prepares
        // with every memo warm.
        if (trackerStyle && !coldStart) {
            try { prepareTrackerData() } catch (e: Throwable) {
                Prefs.noteError(this, "prepareTracker", e)
            }
            DiagLog.markStage("tracker ready")
        }
        if (isWatchStyle()) {
            if (coldStart) {
                WatchPlay.onMatchesChanged = {
                    runOnUiThread { if (isWatchStyle()) rebuild() }
                }
            } else {
                try { prepareWatchData() } catch (e: Throwable) {
                    Prefs.noteError(this, "prepareWatch", e)
                }
                DiagLog.markStage("watch ready")
            }
        }
        pendingWallpaper = null

        // Background: drawn once. Image wallpaper wins; otherwise a gradient.
        // The Tracker view skips wallpapers entirely: near-black
        // #141414, with the hero backdrop as the screen background.
        val preset = Themes.preset(prefs.themeId)
        var top = preset.bgTop
        var bottom = preset.bgBottom
        var imageShown = false
        if (!shellSafe && prefs.wallpaperKind != "weather") {
            prefs.weatherBgStatus = "off"
        }
        if (!shellSafe && !trackerStyle) {
            when (prefs.wallpaperKind) {
                // Weather background (v1.2): drawn from the cached
                // snapshot's palette + motif. No data (offline / never
                // fetched) → leave the theme gradient in place below
                // (fail-open) and record why in diagnostics.
                "weather" -> {
                    val snap = Weather.decodeSnapshot(prefs.weatherData)
                    val cond = snap?.let {
                        WeatherLogic.chooseCondition(it.code, it.isDay, it.dailyCodes)
                    }
                    if (cond != null) {
                        val palette = WeatherLogic.paletteFor(cond)
                        val dm = resources.displayMetrics
                        // v2.0 cold-start rule: the render is DEFERRED
                        // (see pendingWallpaper) — the first frame
                        // paints the theme gradient, the weather art
                        // swaps in underneath the scrim a beat later.
                        pendingWallpaper = {
                            WeatherArt.render(palette, dm.widthPixels, dm.heightPixels)
                        }
                        prefs.weatherBgStatus =
                            "active: ${cond.name.lowercase()} (${palette.motif})"
                        imageShown = true
                    } else {
                        prefs.weatherBgStatus = "fallback: no weather data (theme gradient shown)"
                    }
                }
                "gradient" -> Themes.gradient(prefs.wallpaperValue).let { top = it.top; bottom = it.bottom }
                "solid" -> (prefs.wallpaperValue.toIntOrNull() ?: top).let { top = it; bottom = it }
                "image", "reddit", "bing" -> {
                    // "image" stores a content URI; "reddit" (v1.1)
                    // and "bing" (v1.4) store a downloaded file's
                    // absolute path. v2.0 cold-start rule: the decode
                    // is DEFERRED off the first frame (pendingWallpaper,
                    // consumed in rebuild) — a full-screen bitmap
                    // decode must never delay the watcher-redirect
                    // landing. A decode that fails simply leaves the
                    // theme gradient (the pre-v2.0 fail-open result).
                    val kind = prefs.wallpaperKind
                    val value = prefs.wallpaperValue
                    pendingWallpaper = {
                        if (kind == "reddit" || kind == "bing") {
                            AccentUtil.loadImageFile(value)
                        } else {
                            AccentUtil.loadImage(this, value)
                        }
                    }
                    imageShown = true
                }
            }
        }
        if (trackerStyle) {
            root.background = android.graphics.drawable.ColorDrawable(
                com.mizan.launcher.core.Design.NETFLIX_BG
            )
            addTrackerBackground(root)
        } else {
            // v2.0: the theme gradient is ALWAYS the root background —
            // with an image wallpaper it is what the first frame shows
            // until the deferred decode swaps the image in above it.
            val grad = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            root.background = grad
        }

        // v1.6: ONE scrim over every background kind (photo, gradient,
        // weather art) — the unified bottom-weighted gradient from
        // core/Design, replacing the per-kind flat scrims (0x40/0x66)
        // that made some wallpapers murky and others unreadable.
        // (The Tracker view added its own fade stack above instead.)
        if (!trackerStyle) {
            val scrim = View(this)
            scrim.background = Ui.scrimDrawable()
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(
            Ui.dp(this, com.mizan.launcher.core.Design.CONTENT_MARGIN_DP),
            Ui.dp(this, com.mizan.launcher.core.Design.SPACE_L),
            Ui.dp(this, com.mizan.launcher.core.Design.CONTENT_MARGIN_DP),
            Ui.dp(this, 40)
        )
        scroll.addView(content)

        // Status bar hidden (clean Apple-TV style): the bar's Settings
        // tile goes with it, which would trap the user out of Settings.
        // Keep one dim corner gear as the escape hatch — it is part of
        // the frame, not the bar, and it's the only trace left.
        val barCfg = if (shellSafe) {
            com.mizan.launcher.core.StatusBarConfig()
        } else {
            layout.statusBar
        }
        // v1.9 STICKY STATUS BAR: with the profile's sticky toggle on
        // (and a bar to stick — safe mode never sticks), the bar/strip
        // renders into a FIXED header: a vertical frame holds
        // [header][scroll] instead of the bar being the scroll
        // content's first child, so scrolling home no longer moves
        // it. Off (default) the frame is just the scroller — the
        // pre-v1.9 composition exactly. Bar builders are unchanged;
        // only their parent differs ([barHost]), so focus travel
        // keeps its shape: header first, rows below.
        val stickyBar = !shellSafe && prefs.stickyStatusBar && barCfg.showBar
        val stickyHeader = LinearLayout(this)
        stickyHeader.orientation = LinearLayout.VERTICAL
        val homeFrame = LinearLayout(this)
        homeFrame.orientation = LinearLayout.VERTICAL
        root.addView(homeFrame, FrameLayout.LayoutParams(-1, -1))
        if (stickyBar) {
            homeFrame.addView(stickyHeader, LinearLayout.LayoutParams(-1, -2))
            homeFrame.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            homeFrame.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        }
        val barHost: LinearLayout = if (stickyBar) stickyHeader else content
        // v1.3: the corner gear also appears when the bar is shown but
        // its Settings tile was switched off (per-tile toggles) — there
        // must ALWAYS be a visible way back into Settings.
        if (!barCfg.showBar || !barCfg.showTileSettings) {
            addCornerGear(root)
        }
        // Guest pill when the bar is fully hidden (v1.4): the pill that
        // normally lives in the bar (see buildTopBar) moves to the
        // top-LEFT corner — the top-right belongs to the corner gear —
        // so Guest mode is always visible somewhere on screen.
        if (!barCfg.showBar && guestActive()) {
            val pill = guestPill()
            val plp = FrameLayout.LayoutParams(-2, -2)
            plp.gravity = Gravity.TOP or Gravity.START
            plp.setMargins(Ui.dp(this, 40), Ui.dp(this, 18), 0, 0)
            pill.layoutParams = plp
            root.addView(pill)
        }

        // v1.8: the Tracker home style — same frame (wallpaper, scrim,
        // corner gear/pill), an alternate root render. The TV render
        // below is untouched and tracker-free.
        // v3.0: the call carries the same whole-home guard the IPTV
        // render got in v2.3.2 — before this, ANY throw in the
        // tracker composition (hero select, the unified bar, the
        // sections pre-work) escaped buildRoot into rebuild()'s
        // catch and left no content view at all: the black screen.
        // Now the partial views drop and the TV render below runs
        // instead (core/HomeFallback: tracker-family → TV_RENDER).
        // The tracker backdrop layer stays in the root under the
        // TV rows — a cosmetic trace of the fallback, nothing more.
        if (isTrackerFamily()) {
            val trackerBuilt = try {
                buildTrackerHome(content, barHost)
                true
            } catch (e: Throwable) {
                Prefs.noteError(this, "trackerhome", e)
                buildDegraded = true
                content.removeAllViews()
                barHost.removeAllViews()
                firstTile = null
                false
            }
            if (trackerBuilt) {
                homeRoot = root
                if (moveSession != null && moveSession?.container != MoveContainer.FOLDER) {
                    attachMoveHint(root)
                }
                return root
            }
            // Fall through to the standard TV render.
        }

        // v2.2: the IPTV home style — same frame again (the wallpaper
        // treatment is the TV one; no hero backdrop here), a third
        // root render. Safe mode forces TV, so this never runs there.
        // v2.3.2: the call itself is guarded — rebuild() only RECORDS
        // an escape from buildRoot (it cannot swap in a fallback
        // after the fact), so before this wrap a throw in the IPTV
        // render left the default home with NO content view at all:
        // a black screen on every launch. Now the partial IPTV
        // views are dropped and the standard TV render below runs
        // instead. Throwable, not Exception: an inflation OOM on
        // real panel data is one of the defended failure modes.
        if (isIptvStyle()) {
            val iptvBuilt = try {
                buildIptvHome(content, barHost)
                true
            } catch (e: Throwable) {
                Prefs.noteError(this, "iptvhome", e)
                buildDegraded = true
                content.removeAllViews()
                barHost.removeAllViews()
                firstTile = null
                false
            }
            if (iptvBuilt) {
                homeRoot = root
                if (moveSession != null && moveSession?.container != MoveContainer.FOLDER) {
                    attachMoveHint(root)
                }
                return root
            }
            // Fall through to the standard TV render.
        }

        // v3.0: a failing top bar degrades to NO bar (plus the
        // corner gear, so Settings stays reachable) instead of
        // taking the whole home with it.
        try {
            barHost.addView(buildTopBar())
        } catch (e: Throwable) {
            Prefs.noteError(this, "topbar", e)
            addCornerGear(root)
        }

        // v3.0: the safe shell announces itself at the top of the
        // home — visible, plain, and a tap away from Diagnostics.
        if (safeShell) {
            content.addView(safeShellNotice())
        }

        // v1.4: stored orders from older versions lack the new rows;
        // RowOrder.normalize slots them at their canonical positions.
        val rowKeys = if (shellSafe) listOf("favorites", "apps") else RowOrder.normalize(layout.rowOrder)
        for (key in rowKeys) {
            if (!shellSafe && !layout.isRowVisible(key)) continue
            val section = try {
                when (key) {
                    "favorites" -> buildFavoritesSection()
                    "recents" -> if (shellSafe) null else buildRecentsSection()
                    "mostused" -> if (shellSafe) null else buildMostUsedSection()
                    "folders" -> if (shellSafe) null else buildFoldersSection()
                    "nowplaying" -> if (shellSafe) null else buildNowPlayingSection()
                    "continue" -> if (shellSafe) null else buildContinueSection()
                    "inputs" -> if (shellSafe) null else buildInputsSection()
                    "livetv" -> if (shellSafe) null else buildLiveTvSection()
                    "apps" -> buildAppsSection()
                    else -> null
                }
            } catch (e: Throwable) {
                // v3.0 (core/SectionGuard): a failed row renders
                // the error tile in its place — visible, reported
                // (throttled), never silent, never fatal.
                // v3.1 (ask 39): Retry re-renders (TV rows are
                // in-memory; there is no loader to re-run).
                noteSectionFailure("row:$key", e)
                sectionErrorTile(rowTileLabel(key)) { rebuild() }
            }
            if (section != null) content.addView(section)
        }
        // Move mode's hint bar rides on every rebuild while a
        // home-screen session is active (folder sessions show the
        // grab-time toast instead — the dialog covers this window).
        homeRoot = root
        if (moveSession != null && moveSession?.container != MoveContainer.FOLDER) attachMoveHint(root)
        return root
    }

    // ---------------------------------------------------------------
    // v3.0 crash-contained composition helpers (core/SectionGuard).
    // ---------------------------------------------------------------

    /**
     * Report a home section's build failure — throttled per
     * section (core/SectionGuard.shouldReport): rebuilds retry
     * failing sections on every resume, and the diagnostics error
     * line must stay readable. The error TILE renders on every
     * failure regardless; only the report is throttled.
     */
    private fun noteSectionFailure(where: String, e: Throwable) {
        val now = System.currentTimeMillis()
        if (SectionGuard.shouldReport(sectionReportAt[where] ?: 0L, now)) {
            sectionReportAt[where] = now
            DiagLog.note("section failed: $where (${e.javaClass.simpleName})")
            Prefs.noteError(this, where, e)
        }
    }

    /** The quiet error tile a failed home section renders in its place. */
    private fun sectionErrorTile(label: String, onRetry: (() -> Unit)? = null): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val tile = TextView(this)
        tile.text = SectionGuard.tileText(label)
        tile.setTextColor(subColour)
        tile.textSize = 15f
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 60), Ui.dp(this, 10))
        tile.layoutParams = lp
        box.addView(tile)
        // v3.1 (ask 39): the tile's own Retry re-runs ONLY this
        // section's loader (see retrySection). No retry → the
        // pre-v3.1 plain tile, unchanged.
        if (onRetry != null) {
            val retry = TextView(this)
            retry.text = "↻ Retry"
            retry.setTextColor(accent)
            retry.textSize = 15f
            retry.isFocusable = true
            retry.setPadding(
                Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8)
            )
            retry.setOnClickListener { onRetry() }
            retry.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    onRetry()
                    true
                } else false
            }
            box.addView(retry)
        }
        return box
    }

    /**
     * v3.1 (ask 39): a section's LOADING placeholder — its title
     * plus one "Loading…" card. The card is NOT focusable, so the
     * D-pad skips it (never focus-trapping); the section title
     * still anchors the position while its worker runs.
     */
    private fun sectionLoadingTile(label: String): View {
        val box = section(label)
        val loading = TextView(this)
        loading.text = "Loading…"
        loading.setTextColor(subColour)
        loading.textSize = 15f
        loading.isFocusable = false
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 60), Ui.dp(this, 10))
        loading.layoutParams = lp
        box.addView(loading)
        return box
    }

    /**
     * v3.1 (ask 39): is this tracker-family section LOADING right
     * now — shown, no data yet, and its source worker still
     * running? Pure routing lives in core/SectionModule; this
     * supplies the live worker flags. Sections WITH data never
     * report loading (stale-while-revalidate).
     */
    private fun sectionLoading(key: String, continueCards: List<TrackerLogic.ContinueCard>): Boolean {
        val hasData = when (key) {
            TrackerSections.HERO -> false
            // v3.3 (ask 47): the merged section has data when
            // EITHER source does (dedupe can never empty a
            // non-empty union).
            TrackerSections.UPNEXT -> tUpNext.isNotEmpty() || dSoon.isNotEmpty()
            TrackerSections.CONTINUE -> continueCards.isNotEmpty()
            TrackerSections.TRENDINGTV -> dTrendTv.isNotEmpty()
            TrackerSections.TRENDINGMOVIES -> dTrendMovies.isNotEmpty()
            TrackerSections.FORYOU -> tPicks.isNotEmpty()
            else -> true
        }
        if (key == TrackerSections.UPNEXT) {
            // v3.3 (ask 47): the merged section loads while
            // EITHER source worker still runs — tracker-only
            // routing would hide the placeholder while the
            // discovery worker (the future tier) is still out.
            return !hasData &&
                (TrackerClient.isRefreshing() || !DiscoveryData.isLoaded())
        }
        return com.mizan.launcher.core.SectionModule.isLoading(
            key, hasData,
            TrackerClient.isRefreshing(), DiscoveryData.isLoaded()
        )
    }

    /**
     * v3.1 (ask 39): a section's own Retry — re-runs ONLY that
     * section's loader. Tracker → forced provider fetch (lands
     * one rebuild); discovery → TTL-ignoring worker run (lands
     * one rebuild via onChanged); IPTV rows → forced slice reload;
     * apps/favorites/unknown → plain re-render (in-memory data).
     */
    private fun retrySection(key: String) {
        // v3.2 (ask 43): a per-section Retry is an explicit
        // refresh too — it stamps the `last refresh:` line.
        if (::prefs.isInitialized) {
            prefs.lastRefreshAt = System.currentTimeMillis()
            prefs.lastRefreshKind = "retry:$key"
        }
        // v3.3 (ask 47): the merged Up Next retries BOTH sources —
        // a tracker forced fetch AND a discovery worker run (each
        // lands its own rebuild); retrying one alone would leave
        // the other tier stale.
        if (key == TrackerSections.UPNEXT) {
            TrackerClient.refreshAsync(this, force = true) {
                if (isTrackerFamily()) rebuild()
            }
            DiscoveryData.refreshNow(this)
            return
        }
        when (com.mizan.launcher.core.SectionModule.retrySource(key)) {
            com.mizan.launcher.core.SectionModule.Source.TRACKER ->
                TrackerClient.refreshAsync(this, force = true) {
                    if (isTrackerFamily()) rebuild()
                }
            com.mizan.launcher.core.SectionModule.Source.DISCOVERY ->
                DiscoveryData.refreshNow(this)
            com.mizan.launcher.core.SectionModule.Source.IPTV -> {
                iptvHomeStamp = Long.MIN_VALUE
                ensureIptvHomeData(IptvClient.storeStamp(this))
            }
            else -> rebuild()
        }
    }

    /** Human labels for the TV home's row keys (error tiles). */
    private fun rowTileLabel(key: String): String = when (key) {
        "favorites" -> "Favorites"
        "recents" -> "Recently used"
        "mostused" -> "Most used"
        "folders" -> "Folders"
        "nowplaying" -> "Now playing"
        "continue" -> "Continue watching"
        "inputs" -> "Inputs"
        "livetv" -> "Live TV"
        "apps" -> "Apps"
        else -> key
    }

    /** Human labels for the tracker home's section keys (error tiles). */
    private fun trackerTileLabel(key: String): String = when (key) {
        TrackerSections.HERO -> "Hero"
        TrackerSections.UPNEXT -> "Up Next"
        TrackerSections.CONTINUE -> "Continue"
        TrackerSections.TRENDINGTV -> "Trending TV"
        TrackerSections.TRENDINGMOVIES -> "Trending Movies"
        TrackerSections.FORYOU -> "For You"
        TrackerSections.FAVORITES -> "Favorites"
        TrackerSections.ALLAPPS -> "Apps"
        else -> key
    }

    /**
     * The safe shell's notice (v3.0): the plain line from
     * core/StartupHealth at the top of the shell home; tapping it
     * opens Settings, where Diagnostics lives.
     */
    private fun safeShellNotice(): View {
        val notice = TextView(this)
        notice.text = StartupHealth.NOTICE_TEXT
        notice.setTextColor(textColour)
        notice.textSize = 16f
        notice.isFocusable = true
        notice.isClickable = true
        notice.setPadding(
            Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13)
        )
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 60), Ui.dp(this, 12))
        notice.layoutParams = lp
        notice.background = Ui.tileBackground(
            accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this
        )
        notice.setOnClickListener { openSettings() }
        return notice
    }

    // ---------------------------------------------------------------
    // v2.0 Tracker-view data prep + the full-bleed hero background.
    // ---------------------------------------------------------------

    /**
     * Compute the Tracker working set once per rebuild: prune the
     * marked-set and the For You overlay against the freshest cached
     * snapshot (writing back only when something retired), then the
     * filtered lists every Tracker builder reads. The hero candidate
     * list computed here drives BOTH the root backdrop (added by
     * buildRoot before the scroll frame exists) and the hero text
     * block, so the two can never feature different titles.
     */
    /**
     * v2.7: the discovery working set — data/DiscoveryData's last
     * landed state (in-memory; the worker refreshes stale pieces
     * behind it and lands ONE rebuild through onChanged). Runs
     * before prepareTrackerData (the For You source decision reads
     * it) and for the IPTV style (its Trending row).
     */
    private fun prepareDiscoveryData() {
        DiscoveryData.onChanged = {
            runOnUiThread {
                if ((isTrackerFamily() || isIptvStyle()) && !isFinishing && !isDestroyed) {
                    rebuild()
                }
            }
        }
        dState = DiscoveryData.snapshot(this)
        dSoon = dState.comingSoon
        // Locally dismissed titles leave the Trending rows at once
        // (the same For You overlay "Not interested" writes).
        val dismissed = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
            .filter { !it.added }.map { it.key() }.toHashSet()
        dTrendTv = dState.trendingTv.filter { it.key() !in dismissed }
        dTrendMovies = dState.trendingMovies.filter { it.key() !in dismissed }
    }

    private fun prepareTrackerData() {
        // v3.2: the home build NEVER parses snapshots — it reads
        // only the worker-warmed memo. Cold memo + stored data →
        // one worker parse kick (guarded per activity instance,
        // so its landing rebuild cannot re-kick into a loop);
        // cold memo + no stored data → the shell's empty set
        // until first-run seeding lands (see buildTrackerHome).
        val snap = TrackerClient.memoOrNull() ?: run {
            if (!trackerMemoKickDone && TrackerClient.hasStoredData(this)) {
                trackerMemoKickDone = true
                TrackerClient.warmMemoAsync(this) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) rebuild() }
                }
            }
            TrackerClient.Snapshots.EMPTY
        }
        tSnap = snap
        var marked = TrackerLogic.decodeMarked(prefs.trackerMarked)
        val pruned = TrackerLogic.pruneMarked(marked, snap.upNext, snap.movies, snap.updated)
        if (pruned != marked) {
            prefs.trackerMarked = TrackerLogic.encodeMarked(pruned)
            marked = pruned
        }
        tMarked = marked
        tUpNext = TrackerLogic.filterUpNext(snap.upNext, marked)
        tCont = TrackerLogic.filterContinue(snap.cont, marked)
        var overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
        val prunedOverlay = TrackerLogic.pruneForYouOverlay(
            overlay, snap.forYou, snap.tracked, snap.movies, snap.forYouUpdated
        )
        if (prunedOverlay != overlay) {
            prefs.forYouOverlay = TrackerLogic.encodeForYouOverlay(prunedOverlay)
            overlay = prunedOverlay
        }
        // v2.7: with a launcher TMDB key set, the launcher's OWN
        // computed For You (data/DiscoveryData) supplies the picks;
        // the tracker's /foryou snapshot is the fallback — and the
        // only source when no launcher key is set. Either way the
        // same filter applies (tracked + local overlay out, cap 20).
        val fySource = if (prefs.tmdbKey.isNotBlank() && dState.forYouComputed) {
            dState.forYou
        } else {
            snap.forYou
        }
        tPicks = TrackerLogic.filterForYou(fySource, snap.tracked, snap.movies, overlay)
        // v3.1 (hero schedule amendment): the hero comes from the
        // SCHEDULE — missed episodes first, then watchable-now
        // up-next, with the previous Continue → For You pool only
        // as the empty-schedule fallback. Dates prefer the
        // tracker's /upnext snapshot fields; blank ones fill from
        // the discovery worker's last-known TMDB facts (memory
        // only — the hero never waits on a fetch; the worker's
        // landing rebuild refreshes the pool). dSoon is already
        // set (prepareDiscoveryData runs first), so the
        // same-episode Coming Soon exclusion applies.
        val heroUpNext = TrackerLogic.fillHeroDates(
            tUpNext, DiscoveryData.tvFacts()
        )
        tHeroCandidates = TrackerLogic.heroCandidates(
            heroUpNext, tCont, tPicks, TrackerClient.backdropCache(this),
            todayIso(), dSoon
        )
    }

    /**
     * v2.3: the Watch working set — the IPTV catalogues and the
     * match table (cached by data/WatchPlay; recomputed only when
     * a catalogue or the tracker snapshot moves). Then warm the
     * series-info cache for matched Up Next shows so their Play
     * buttons resolve without a tap-time wait; a warm landing
     * rebuilds once with the resolved hero/cards.
     */
    private fun prepareWatchData() {
        // v2.4: matches() returns the best table available NOW
        // (the prefs cache while the store tuples load in the
        // background); when the tuples land and the table is
        // recomputed, onMatchesChanged rebuilds once with fresh
        // badges — no catalogue is ever materialized here.
        WatchPlay.onMatchesChanged = {
            runOnUiThread { if (isWatchStyle()) rebuild() }
        }
        wMatches = WatchPlay.matches(this)
        // v2.9 (ask F.25): the warm-up set is WHAT IS DISPLAYED —
        // the hero pool first (in pool order), then the Up Next
        // row, then Continue Watching's shows — every matched
        // series among them, no quota. (Pre-v2.9 the warm-up
        // stopped at 6 Up Next shows per session, and the hero
        // only offered Play for already-warmed shows: a matched
        // hero past the cap rendered with no Play button at all.)
        val displayed = ArrayList<Long>()
        fun displayShow(tmdbId: Long) {
            val m = wMatches["tv|$tmdbId"] ?: return
            if (m.iptvId !in displayed) displayed.add(m.iptvId)
        }
        for (hero in tHeroCandidates) {
            if (hero.type == "tv") displayShow(hero.tmdbId)
        }
        for (item in tUpNext) displayShow(item.tmdbId)
        for (item in tCont) displayShow(item.tmdbId)
        WatchPlay.warmUpDisplayed(this, displayed) {
            // The warm-up reports from its own thread; rebuilds are UI work.
            runOnUiThread { if (isWatchStyle()) rebuild() }
        }
    }

    /** The Watch match for one tracker title (null = unmatched / not Watch style). */
    private fun watchMatch(type: String, tmdbId: Long): WatchMatch.Match? =
        if (isWatchStyle()) wMatches["$type|$tmdbId"] else null

    /**
     * Play one Up Next episode from the Watch home: resolve via
     * data/WatchPlay; ANY resolution failure fails open to the
     * v2.1 behaviour (the title's details) with a toast.
     */
    private fun playUpNextItem(item: TrackerLogic.UpNextItem, inSnapshot: Boolean) {
        DiagLog.note("watch: episode play tapped")
        val match = watchMatch("tv", item.tmdbId)
        if (match == null) {
            openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
            return
        }
        // v2.4: the series entry is resolved from the disk store
        // on a worker thread; any miss fails open to details.
        WatchPlay.resolveSeries(this, match) { series ->
            if (isFinishing || isDestroyed) return@resolveSeries
            if (series == null) {
                openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
                return@resolveSeries
            }
            WatchPlay.playEpisode(
                this, series, item.tmdbId, item.season, item.episode,
                item.title, item.poster, withToken = inSnapshot
            ) {
                Toast.makeText(
                    this, "That episode isn't on your IPTV — showing details instead",
                    Toast.LENGTH_LONG
                ).show()
                openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
            }
        }
    }

    /** Play a matched movie from the Watch home (hero / For You). */
    private fun playMovieItem(movie: TrackerLogic.MovieItem) {
        DiagLog.note("watch: movie play tapped")
        val match = watchMatch("movie", movie.tmdbId)
        if (match == null) {
            openTitleDetails(movie.tmdbId, "movie", movie.title, movie.poster)
            return
        }
        val token = if (TrackerClient.cached(this).movies.any { it.tmdbId == movie.tmdbId }) {
            WatchPlay.Token(movie.tmdbId, "movie", 0, 0)
        } else {
            null
        }
        // v2.4: store resolution on a worker thread, as above.
        WatchPlay.resolveVod(this, match) { vod ->
            if (isFinishing || isDestroyed) return@resolveVod
            if (vod == null) {
                openTitleDetails(movie.tmdbId, "movie", movie.title, movie.poster)
                return@resolveVod
            }
            WatchPlay.playMovie(this, vod, token)
        }
    }

    /**
     * v3.1 (ask 40): "Play in…" for a matched Up Next episode —
     * the episode's IPTV stream, handed to another video app via
     * ui/PlayIn. Unmatched or coordinate-less cards never offer
     * the entry (the call site gates on the match).
     */
    private fun playInEpisode(item: TrackerLogic.UpNextItem) {
        val match = watchMatch("tv", item.tmdbId)
        if (match == null || item.season <= 0 || item.episode <= 0) {
            Toast.makeText(this, "No playable stream for this episode", Toast.LENGTH_LONG)
                .show()
            return
        }
        com.mizan.launcher.ui.PlayIn.showForEpisode(
            this, match, item.season, item.episode, item.title
        )
    }

    /** v3.1 (ask 40): "Play in…" for a matched movie (same VOD resolution as playMovieItem). */
    private fun playInMovie(movie: TrackerLogic.MovieItem) {
        val match = watchMatch("movie", movie.tmdbId)
        if (match == null) {
            Toast.makeText(this, "No playable stream for this title", Toast.LENGTH_LONG)
                .show()
            return
        }
        val token = if (TrackerClient.cached(this).movies.any { it.tmdbId == movie.tmdbId }) {
            WatchPlay.Token(movie.tmdbId, "movie", 0, 0)
        } else {
            null
        }
        WatchPlay.resolveVod(this, match) { vod ->
            if (isFinishing || isDestroyed) return@resolveVod
            if (vod == null) {
                Toast.makeText(this, "Couldn't find this title on your IPTV", Toast.LENGTH_LONG)
                    .show()
                return@resolveVod
            }
            com.mizan.launcher.ui.PlayIn.showForPlayItem(
                this, WatchPlay.moviePlayItem(vod, token)
            )
        }
    }

    /**
     * The hero's ▶ Play press (v2.9, ask F.25): resolve on demand,
     * then play. Movies hand off to playMovieItem (its VOD store
     * scan is local and fast). Shows run the resolve flow —
     * core/WatchMatch's phase machine owns the states: the button
     * shows "Finding stream…" and refuses a second press while
     * the series entry + series info resolve (the info fetch is
     * the one real wait, and WatchPlay.playEpisode performs it on
     * demand when the cache is cold). Any failure restores the
     * button and fails open to the title's details with a toast —
     * never a dead end, never the tracker app.
     */
    private fun playHeroPrimary(
        hero: TrackerLogic.HeroItem,
        coords: Pair<Int, Int>,
        button: TextView,
        primaryLabel: String
    ) {
        if (heroResolvePhase == WatchMatch.ResolvePhase.FINDING) return
        if (hero.type == "movie") {
            // v3.1: no tMovies list anymore (Movie Night removed) —
            // the hero itself carries every field playMovieItem needs.
            val movieItem = TrackerLogic.MovieItem(
                hero.tmdbId, hero.title, hero.dateIso, false, hero.poster, false
            )
            playMovieItem(movieItem)
            return
        }
        val m = watchMatch("tv", hero.tmdbId)
        if (m == null) {
            openTitleDetails(hero.tmdbId, hero.type, hero.title, hero.poster)
            return
        }
        heroResolvePhase = WatchMatch.resolvePhase(
            heroResolvePhase, WatchMatch.ResolveEvent.PRESS
        )
        button.text = WatchMatch.resolveLabel(heroResolvePhase, primaryLabel)
        button.isEnabled = false
        fun restore() {
            heroResolvePhase = WatchMatch.ResolvePhase.IDLE
            if (!isFinishing && !isDestroyed && button.isAttachedToWindow) {
                button.text = primaryLabel
                button.isEnabled = true
            }
        }
        fun fail() {
            heroResolvePhase = WatchMatch.resolvePhase(
                heroResolvePhase, WatchMatch.ResolveEvent.NOT_FOUND
            )
            val attached = button.isAttachedToWindow
            restore()
            // A detached button means a rebuild already replaced
            // the hero (its fresh buttons offer Play again; the
            // resolution is now cached) — only the still-showing
            // hero takes the details fallback.
            if (attached && !isFinishing && !isDestroyed) {
                Toast.makeText(
                    this,
                    "That episode isn't on your IPTV — showing details instead",
                    Toast.LENGTH_LONG
                ).show()
                openTitleDetails(hero.tmdbId, hero.type, hero.title, hero.poster)
            }
        }
        val inSnap = tSnap.upNext.any {
            it.tmdbId == hero.tmdbId &&
                it.season == coords.first && it.episode == coords.second
        }
        WatchPlay.resolveSeries(this, m) { series ->
            if (isFinishing || isDestroyed) return@resolveSeries
            if (series == null) {
                fail()
                return@resolveSeries
            }
            heroResolvePhase = WatchMatch.resolvePhase(
                heroResolvePhase, WatchMatch.ResolveEvent.FOUND
            )
            WatchPlay.playEpisode(
                this, series, hero.tmdbId, coords.first, coords.second,
                hero.title, hero.poster, withToken = inSnap
            ) { fail() }
            // The press is handed off (the player owns the screen
            // now, or playEpisode's own fail path ran): the hero's
            // next render starts from IDLE.
            heroResolvePhase = WatchMatch.ResolvePhase.IDLE
        }
    }

    /**
     * The Tracker view's background stack (v2.0 Netflix look), added
     * to the root BEFORE the scroll frame: the hero backdrop as a
     * full-bleed image — two layered ImageViews so rotation can
     * crossfade — under the gradient fades that blend it into the
     * #141414 page (bottom fade for the rows, a left scrim behind the
     * hero text, a top shade behind the status bar). With no hero,
     * the layers stay empty and the page is simply #141414.
     */
    private fun addTrackerBackground(root: FrameLayout) {
        val dm = resources.displayMetrics
        val back = ImageView(this)
        back.scaleType = ImageView.ScaleType.CENTER_CROP
        back.alpha = 0f
        val front = ImageView(this)
        front.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(back, FrameLayout.LayoutParams(-1, -1))
        root.addView(front, FrameLayout.LayoutParams(-1, -1))
        heroLayerBack = back
        heroLayerFront = front

        val hero = TrackerLogic.selectHero(tHeroCandidates, prefs.trackerHeroIndex)
        heroBackdropShown = ""
        if (hero != null && hero.backdrop.isNotBlank()) {
            TmdbImages.load(TmdbImages.url("w1280", hero.backdrop), front, TmdbImages.HERO_MAX_DIM)
            heroBackdropShown = hero.backdrop
        }

        val bg = com.mizan.launcher.core.Design.NETFLIX_BG
        // Left scrim behind the hero text block (hero zone only).
        val heroZonePx = (dm.heightPixels * com.mizan.launcher.core.Design.HERO_ZONE_FRACTION).toInt()
        val leftScrim = View(this)
        leftScrim.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xC2141414.toInt(), 0x00000000)
        )
        val lslp = FrameLayout.LayoutParams((dm.widthPixels * 0.78f).toInt(), heroZonePx)
        lslp.gravity = Gravity.TOP or Gravity.START
        leftScrim.layoutParams = lslp
        root.addView(leftScrim)
        // Top shade so the transparent status bar reads over bright
        // art. v2.1: sized to the reorganized SINGLE-row bar (the
        // old 190dp covered the two-line hero block).
        val topShade = View(this)
        topShade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x66000000, 0x00000000)
        )
        val tslp = FrameLayout.LayoutParams(-1, Ui.dp(this, 120))
        tslp.gravity = Gravity.TOP
        topShade.layoutParams = tslp
        root.addView(topShade)
        // Bottom fade: the backdrop melts into the page well before
        // the first row, so rows always sit on solid #141414.
        val fade = View(this)
        fade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0x00000000, bg, bg)
        )
        root.addView(fade, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------------------------------------------------------------
    // Home styles (v1.7 TV, v1.8 Tracker, v2.2 IPTV). v2.2 REMOVED
    // the iPad style (Haythem: "it is bad") — its renderers are
    // gone; the HomeStyle codec decodes a stored "ipad" to TV, so
    // profiles saved with it open in the TV style (spec §20.1).
    // ---------------------------------------------------------------

    /** The effective home style: safe mode — and the v3.0 safe shell — always force TV. */
    private fun homeStyleKey(): String =
        if (prefs.safeMode || safeShell) com.mizan.launcher.core.HomeStyle.TV else prefs.homeStyle

    private fun isTrackerStyle(): Boolean =
        com.mizan.launcher.core.HomeStyle.isTracker(homeStyleKey())

    private fun isIptvStyle(): Boolean =
        com.mizan.launcher.core.HomeStyle.isIptv(homeStyleKey())

    private fun isWatchStyle(): Boolean =
        com.mizan.launcher.core.HomeStyle.isWatch(homeStyleKey())

    /** Tracker + Watch share one composition (v2.3: Watch adds behaviour, not a layout). */
    private fun isTrackerFamily(): Boolean = isTrackerStyle() || isWatchStyle()

    /** The icon shape home tiles render with (the profile's preference, unmodified). */
    private fun effectiveIconShape(): String = prefs.iconShape

    // ---------------------------------------------------------------
    // v2.2 IPTV home style — TV-style rows under the bar over the
    // standard wallpaper frame: a Browse entry row, then the
    // composed IPTV rows (core/IptvLogic.homeRows — Favorites,
    // Live, Continue Watching, Movies, Series), then the profile's
    // app rows (Favorites / All Apps) at the bottom like the
    // Tracker style, so apps stay reachable. Fail-open: no account
    // or no data renders the classified empty-state line
    // (core/IptvStatus) above the app rows, never a crash.
    // ---------------------------------------------------------------

    private fun buildIptvHome(content: LinearLayout, barHost: LinearLayout) {
        // v2.9 (ask C.13): ONE unified pill bar — tabs + status
        // items in a single row/design (was status bar + separate
        // tab bar in v2.8).
        barHost.addView(buildUnifiedBar(overHero = false))
        // v2.4: NO catalogue fetch on build (refresh is on request
        // only now). The EPG index still loads LOCALLY from the
        // store file in the background — that's disk, not network.
        IptvClient.epgIndex(this) { if (isIptvStyle()) rebuild() }

        content.addView(buildIptvEntryRow())

        if (coldStart) {
            // v2.8 staged shell: the store index is NOT read on
            // this build (storeStamp/status parse it on the main
            // thread otherwise) — the warm worker heats the memo
            // and its landing rebuild composes the real rows.
            content.addView(trackerInfoLine("Loading your channels…"))
            return
        }

        // v2.4: the rows are composed from store SLICES on a
        // worker thread (ensureIptvHomeData) and kept in fields,
        // keyed by the store generation. Until the slices for the
        // current generation land, the home shows the shell +
        // a status line — never a synchronous catalogue load.
        // v2.3.2's guard discipline stands: a render failure falls
        // back to the safe shell (bar + entry row + app rows).
        val stamp = IptvClient.storeStamp(this)
        val fetchedAt = IptvClient.fetchedAt(this)
        if (fetchedAt > 0L) {
            val timeText = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                .format(java.util.Date(fetchedAt))
            content.addView(trackerInfoLine("IPTV · Updated $timeText"))
        }
        if (iptvHomeStamp != stamp) {
            ensureIptvHomeData(stamp)
            val lineText = when {
                IptvClient.isIngesting() -> IptvClient.ingestProgressText()
                else -> IptvStatus.emptyLine(IptvClient.status(this))
                    ?: "Loading your channels…"
            }
            content.addView(trackerInfoLine(lineText))
        } else {
            if (IptvClient.isIngesting()) {
                content.addView(trackerInfoLine(IptvClient.ingestProgressText()))
            }
            if (iptvHomeRows.isEmpty()) {
                val lineText = IptvStatus.emptyLine(IptvClient.status(this))
                    ?: "Nothing to show here yet — IPTV rows appear when your catalogues load."
                content.addView(trackerInfoLine(lineText))
            }
            for (row in iptvHomeRows) {
                try {
                    content.addView(buildIptvRow(row))
                } catch (e: Throwable) {
                    // v3.0 (core/SectionGuard): error tile, not a silent drop.
                    // v3.1 (ask 39): Retry re-slices this store generation.
                    noteSectionFailure("row:iptv:${row.kind}", e)
                    content.addView(
                        sectionErrorTile("IPTV row") { retrySection("iptv-row") }
                    )
                }
            }
        }
        // v2.7: Trending on your IPTV — TMDB's chart ∩ the
        // catalogue, after the catalogue rows and before the app
        // rows. Worker-matched (data/DiscoveryData); no matches
        // (or no TMDB at all) hides it entirely.
        if (dState.trendingIptv.isNotEmpty()) {
            try {
                content.addView(buildIptvTrendingSection(dState.trendingIptv))
            } catch (e: Throwable) {
                noteSectionFailure("row:iptv:trending", e)
                content.addView(
                    sectionErrorTile("Trending on your IPTV") {
                        retrySection("iptv-trending")
                    }
                )
            }
        }
        if (layout.isRowVisible("favorites")) {
            try {
                content.addView(buildFavoritesSection())
            } catch (e: Throwable) {
                noteSectionFailure("row:iptv:favorites", e)
                content.addView(
                    sectionErrorTile("Favorites") { retrySection("favorites") }
                )
            }
        }
        if (layout.isRowVisible("apps")) {
            try {
                content.addView(buildAppsSection())
            } catch (e: Throwable) {
                noteSectionFailure("row:iptv:apps", e)
                content.addView(
                    sectionErrorTile("Apps") { retrySection("allapps") }
                )
            }
        }
    }

    /**
     * Load the IPTV home's slices for store generation [stamp] on
     * a worker thread: the first visible live categories' first
     * pages (from the index's counts), the movies/series first
     * pages, then the pure composition (core/IptvLogic.
     * homeRowsSliced). Lands in fields + one rebuild. Any failure
     * composes empty rows — the home's safe shell — and records.
     */
    private fun ensureIptvHomeData(stamp: Long) {
        if (iptvHomeLoading) return
        iptvHomeLoading = true
        val appCtx = applicationContext
        kotlin.concurrent.thread(name = "mizan-iptv-home") {
            var rows: List<IptvLogic.HomeRow> = emptyList()
            var live: List<IptvLogic.LiveChannel> = emptyList()
            var vods: List<IptvLogic.VodItem> = emptyList()
            var series: List<IptvLogic.SeriesItem> = emptyList()
            var cats: List<IptvLogic.Category> = emptyList()
            try {
                val snap = IptvClient.snapshot(appCtx)
                cats = snap.allCats()
                val hidden = iptvHiddenCatKeys(cats)
                val liveRowsData = ArrayList<Pair<IptvLogic.Category, List<IptvLogic.LiveChannel>>>()
                val gathered = ArrayList<IptvLogic.LiveChannel>()
                for (cat in snap.liveCats) {
                    if (liveRowsData.size >= IptvLogic.LIVE_ROW_CAP) break
                    if (cat.key in hidden) continue
                    if ((snap.index?.countForCat(cat.key) ?: 0) <= 0) continue
                    val page = IptvClient.sliceBlocking(
                        appCtx, IptvLogic.KIND_LIVE, catKey = cat.key,
                        limit = IptvLogic.ROW_CARD_CAP
                    )
                    if (page.live.isNotEmpty()) {
                        liveRowsData.add(cat to page.live)
                        gathered.addAll(page.live)
                    }
                }
                var flat: List<IptvLogic.LiveChannel> = emptyList()
                if (liveRowsData.isEmpty()) {
                    flat = IptvClient.sliceBlocking(
                        appCtx, IptvLogic.KIND_LIVE, excludeCats = hidden,
                        limit = IptvLogic.ROW_CARD_CAP
                    ).live
                    gathered.addAll(flat)
                }
                vods = IptvClient.sliceBlocking(
                    appCtx, IptvLogic.KIND_VOD, limit = IptvLogic.ROW_CARD_CAP
                ).vods
                series = IptvClient.sliceBlocking(
                    appCtx, IptvLogic.KIND_SERIES, limit = IptvLogic.ROW_CARD_CAP
                ).series
                val activityPrefs = prefs
                rows = IptvLogic.homeRowsSliced(
                    activityPrefs.iptvFavoriteList(), activityPrefs.iptvResumeList(),
                    hidden, liveRowsData, flat, vods, series,
                    IptvClient.epgIndex(appCtx), System.currentTimeMillis()
                )
                live = gathered
            } catch (e: Throwable) {
                Prefs.noteError(appCtx, "iptvhome", e)
            }
            runOnUiThread {
                iptvHomeLoading = false
                iptvHomeStamp = stamp
                iptvHomeRows = rows
                iptvHomeLive = live
                iptvHomeVods = vods
                iptvHomeSeries = series
                iptvHomeCats = cats
                if (isIptvStyle() && !isFinishing && !isDestroyed) rebuild()
            }
        }
    }

    /**
     * Trending on your IPTV (v2.7): the trending titles the
     * repo-method matcher found in the catalogue (worker-computed
     * in data/DiscoveryData). Cards PLAY in place — movies straight
     * to their VOD stream, series to their first episode — and
     * wear the ▶ IPTV badge like every matched card.
     */
    private fun buildIptvTrendingSection(plays: List<DiscoveryData.TrendPlay>): View {
        val box = section("Trending on your IPTV")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (tp in plays) {
            val item = tp.item
            val card = portraitCard(
                posterPath = item.poster,
                title = item.title,
                meta = TrackerLogic.ratingText(item.voteAverage) ?: item.year,
                glyph = if (item.type == "movie") "🎬" else "📺",
                onClick = { playTrendPlay(tp) },
                onLong = {
                    openTrackerCardMenu(
                        item.title,
                        onMark = null,
                        onDetails = {
                            openTitleDetails(item.id, item.type, item.title, item.poster)
                        }
                    )
                },
                badge = WatchMatch.BADGE_TEXT
            )
            row.addView(card)
        }
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * Play one Trending-on-IPTV card: resolve the store entry on a
     * worker thread (data/WatchPlay), then play — a movie via its
     * VOD entry, a series via its first episode (core/Discovery.
     * firstEpisode). Every failure fails open to the title's
     * in-launcher details; never a dead end, never an auto-mark
     * (these plays carry no tracker token).
     */
    private fun playTrendPlay(tp: DiscoveryData.TrendPlay) {
        val item = tp.item
        if (item.type == "movie") {
            val match = WatchMatch.Match(item.id, "movie", tp.iptvId, exact = true, possible = false)
            WatchPlay.resolveVod(this, match) { vod ->
                if (isFinishing || isDestroyed) return@resolveVod
                if (vod == null) {
                    openTitleDetails(item.id, item.type, item.title, item.poster)
                } else {
                    WatchPlay.playMovie(this, vod, null)
                }
            }
            return
        }
        val match = WatchMatch.Match(item.id, "tv", tp.iptvId, exact = true, possible = false)
        WatchPlay.resolveSeries(this, match) { series ->
            if (isFinishing || isDestroyed) return@resolveSeries
            if (series == null) {
                openTitleDetails(item.id, item.type, item.title, item.poster)
                return@resolveSeries
            }
            fun startWith(info: IptvLogic.SeriesInfo?) {
                if (isFinishing || isDestroyed) return
                val first = info?.let { Discovery.firstEpisode(it) }
                if (first == null) {
                    openTitleDetails(item.id, item.type, item.title, item.poster)
                    return
                }
                WatchPlay.playEpisode(
                    this, series, item.id, first.first, first.second,
                    item.title, item.poster, withToken = false
                ) {
                    openTitleDetails(item.id, item.type, item.title, item.poster)
                }
            }
            val cached = IptvClient.cachedSeriesInfo(this, series.id)
            if (cached != null) {
                startWith(cached)
            } else {
                Toast.makeText(
                    this, "Finding the episode on your IPTV…", Toast.LENGTH_SHORT
                ).show()
                IptvClient.fetchSeriesInfoAsync(this, series.id) { info -> startWith(info) }
            }
        }
    }

    /** The category-lock hidden set over every IPTV category (both sources). */
    private fun iptvHiddenCatKeys(allCats: List<IptvLogic.Category>): Set<String> =
        IptvLocks.hiddenKeys(
            allCats,
            IptvLocks.decodeKeys(prefs.iptvLockedCats),
            IptvLocks.decodeStamps(prefs.iptvCatUnlockStamps),
            prefs.unlockGraceMinutes,
            System.currentTimeMillis(),
            IptvClient.sessionStartMs,
            prefs.arrowPin.isNotEmpty()
        )

    /** The always-present entry row into the IPTV browser + guide. */
    private fun buildIptvEntryRow(): View {
        val rowLayout = LinearLayout(this)
        rowLayout.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 10))
        rowLayout.layoutParams = lp
        rowLayout.addView(iptvEntryButton("📺  Browse IPTV — live, movies, series") {
            startActivity(IptvActivity.intent(this))
        })
        rowLayout.addView(iptvEntryButton("📖  Programme guide") {
            startActivity(Intent(this, IptvGuideActivity::class.java))
        })
        return rowLayout
    }

    private fun iptvEntryButton(text: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(0xFFFFFFFF.toInt())
        b.textSize = 16f
        b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
        b.gravity = Gravity.CENTER_VERTICAL
        b.isFocusable = true
        b.isClickable = true
        b.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 12), 0)
        b.layoutParams = lp
        b.background = Ui.tileBackground(
            accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this
        )
        Ui.focusLift(b, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
        b.setOnClickListener { onClick() }
        return b
    }

    /** One composed IPTV row: section header + a horizontal card scroller. */
    private fun buildIptvRow(row: IptvLogic.HomeRow): View {
        val box = section(row.title)
        val rowLayout = LinearLayout(this)
        rowLayout.orientation = LinearLayout.HORIZONTAL
        // v2.3.2: the view renders at most HOME_ROW_VIEW_CAP cards
        // per row, whatever the composition produced.
        for (card in IptvLogic.homeRowCards(row)) {
            val view = IptvCards.build(
                activity = this, prefs = prefs, accent = accent,
                imageUrl = card.image, title = card.title, meta = card.meta,
                line2 = card.nowTitle.ifEmpty { null },
                glyph = if (card.kind == IptvLogic.KIND_VOD) "🎬"
                else if (card.kind == IptvLogic.KIND_SERIES) "📚" else "📺",
                wide = card.kind == IptvLogic.KIND_LIVE,
                tag = card.key, progress = card.progress,
                // v2.8: Live cards wear the red LIVE pill.
                liveBadge = card.kind == IptvLogic.KIND_LIVE,
                onClick = { iptvCardClicked(card, row) },
                onLong = { iptvCardMenu(card, row) }
            )
            if (firstTile == null) firstTile = (view as LinearLayout).getChildAt(0)
            rowLayout.addView(view)
        }
        box.addView(horizontalScroller(rowLayout))
        return box
    }

    // ---------------------------------------------------------- IPTV actions

    private fun iptvPlayItemOf(ch: IptvLogic.LiveChannel): PlayItem = PlayItem(
        kind = IptvLogic.KIND_LIVE, source = ch.source, id = ch.id.toString(),
        title = ch.name, icon = ch.icon, meta = ch.categoryName,
        catKey = ch.catKey, directUrl = ch.directUrl,
        tvArchive = ch.tvArchive, epgId = ch.epgId
    )

    private fun iptvPlayLive(channels: List<IptvLogic.LiveChannel>, start: IptvLogic.LiveChannel) {
        PlayQueue.set(
            channels.map { iptvPlayItemOf(it) },
            channels.indexOf(start).coerceAtLeast(0)
        )
        startActivity(PlayerActivity.intent(this, iptvPlayItemOf(start)))
    }

    /** Play straight from a composed card (it carries ext / direct URL). */
    private fun iptvPlayCardItem(card: IptvLogic.Card) {
        PlayQueue.set(emptyList(), 0)
        startActivity(
            PlayerActivity.intent(
                this,
                PlayItem(
                    kind = card.kind, source = card.source, id = card.id,
                    title = card.title, icon = card.image, meta = card.meta,
                    ext = card.ext, catKey = card.catKey, directUrl = card.directUrl
                )
            )
        )
    }

    private fun iptvCardClicked(
        card: IptvLogic.Card,
        row: IptvLogic.HomeRow
    ) {
        when (card.kind) {
            IptvLogic.KIND_LIVE -> {
                // v2.4: lookups run over the home's slice items.
                val ch = iptvHomeLive.firstOrNull { it.key == card.key }
                if (ch != null) {
                    val siblings = row.cards.mapNotNull { c ->
                        iptvHomeLive.firstOrNull { it.key == c.key }
                    }
                    iptvPlayLive(if (siblings.isEmpty()) listOf(ch) else siblings, ch)
                } else {
                    iptvPlayCardItem(card)
                }
            }
            IptvLogic.KIND_SERIES -> startActivity(
                IptvActivity.intent(
                    this, IptvActivity.TAB_SERIES, card.id.toLongOrNull() ?: 0L
                )
            )
            else -> iptvPlayCardItem(card) // vod + episode (Continue) cards
        }
    }

    private fun iptvCardMenu(
        card: IptvLogic.Card,
        row: IptvLogic.HomeRow
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val isSeries = card.kind == IptvLogic.KIND_SERIES
        labels.add(if (isSeries) "Open" else "▶ Play")
        actions.add { iptvCardClicked(card, row) }
        if (!isSeries) {
            // v3.1 (ask 40): the card's own stream, handed over —
            // same PlayItem construction as iptvPlayCardItem.
            labels.add("Play in…")
            actions.add {
                com.mizan.launcher.ui.PlayIn.showForPlayItem(
                    this,
                    PlayItem(
                        kind = card.kind, source = card.source, id = card.id,
                        title = card.title, icon = card.image, meta = card.meta,
                        ext = card.ext, catKey = card.catKey, directUrl = card.directUrl
                    )
                )
            }
        }
        if (card.kind == IptvLogic.KIND_LIVE) {
            val ch = iptvHomeLive.firstOrNull { it.key == card.key }
            val shift = ch?.let { iptvCatchUpItem(it) }
            if (shift != null) {
                labels.add("⏮ Watch from start")
                actions.add {
                    PlayQueue.set(emptyList(), 0)
                    startActivity(PlayerActivity.intent(this, shift))
                }
            }
        }
        val favEntry = iptvFavEntryFor(card)
        if (favEntry != null) {
            val isFav = IptvLogic.isFavorite(prefs.iptvFavoriteList(), favEntry.key)
            labels.add(if (isFav) "★ Remove from favorites" else "☆ Add to favorites")
            actions.add {
                prefs.setIptvFavoriteList(
                    IptvLogic.toggleFavorite(prefs.iptvFavoriteList(), favEntry)
                )
                Toast.makeText(
                    this,
                    if (isFav) "Removed from favorites" else "Added to favorites",
                    Toast.LENGTH_SHORT
                ).show()
                rebuild()
            }
        }
        if (card.kind == IptvLogic.KIND_VOD || card.kind == IptvLogic.KIND_EPISODE) {
            if (IptvLogic.resumeFor(prefs.iptvResumeList(), card.key) != null) {
                labels.add("↺ Play from the beginning")
                actions.add {
                    prefs.setIptvResumeList(
                        IptvLogic.withoutResume(prefs.iptvResumeList(), card.key)
                    )
                    iptvPlayCardItem(card)
                }
            }
        }
        AlertDialog.Builder(this).setTitle(card.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    /** The favorite entry a card toggles (slice object when present, else the stored favorite). */
    private fun iptvFavEntryFor(
        card: IptvLogic.Card
    ): IptvLogic.FavEntry? = when (card.kind) {
        IptvLogic.KIND_LIVE -> iptvHomeLive.firstOrNull { it.key == card.key }
            ?.let { IptvLogic.favOf(it) }
            ?: prefs.iptvFavoriteList().firstOrNull { it.key == card.key }
        IptvLogic.KIND_VOD -> iptvHomeVods.firstOrNull { it.key == card.key }
            ?.let { IptvLogic.favOf(it) }
            ?: prefs.iptvFavoriteList().firstOrNull { it.key == card.key }
        IptvLogic.KIND_SERIES -> iptvHomeSeries.firstOrNull { it.key == card.key }
            ?.let { IptvLogic.favOf(it) }
            ?: prefs.iptvFavoriteList().firstOrNull { it.key == card.key }
        else -> null
    }

    /** The catch-up item for a channel right now, or null when it doesn't apply (§20.10). */
    private fun iptvCatchUpItem(ch: IptvLogic.LiveChannel): PlayItem? {
        if (!ch.hasArchive) return null
        val (now, _) = IptvEpg.nowNext(
            IptvLogic.epgFor(IptvClient.epgIndex(this), ch), System.currentTimeMillis()
        )
        if (now == null) return null
        val durationMin = ((now.stopMs - now.startMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return iptvPlayItemOf(ch).copy(
            kind = IptvClient.KIND_SHIFT,
            title = ch.name + " — from the start",
            meta = now.title,
            shiftStartMs = now.startMs,
            shiftDurationMin = durationMin
        )
    }


    /** The current home root (for the move-mode hint bar). */
    private var homeRoot: FrameLayout? = null

    /**
     * v2.0 cold-start deferral: buildRoot stores the wallpaper
     * decode/render here instead of running it inline; rebuild()
     * consumes it on a background thread AFTER the first frame's
     * view tree exists, then inserts the image under the scrim.
     */
    private var pendingWallpaper: (() -> android.graphics.Bitmap?)? = null

    /**
     * Top bar + the not-default-home hint, wrapped in one vertical box.
     * The bar is driven by the profile's StatusBarConfig (v1.1): hidden
     * entirely for the clean Apple-TV style, or any mix of clock, date,
     * weather, network, Bluetooth and quick tiles. In Safe mode the
     * v1.0 default bar is used.
     */
    /**
     * The status bar. [overHero] (v2.0, Tracker view only): the bar
     * renders OVER the full-bleed hero backdrop — its zone background
     * and padding are suppressed (fully transparent) and its text
     * gains a shadow for legibility. Element gates and formats are
     * unchanged; the sticky header is transparent in this view for
     * the same reason (it has no background of its own).
     */
    // ---------------------------------------------------------------
    // v2.8 Google-TV pill tab bar (spec §28) — merged INTO the
    // unified bar in v2.9 (below); the pills themselves and their
    // model (core/HomeTabs) are unchanged. Modelled on the stock
    // Google TV home in Haythem's reference photos. The model is
    // pure (core/HomeTabs): only "Home" is a page (this one, shown
    // selected); every other pill is an action — the Library, the
    // IPTV browser on a tab, the guide, or a focus jump to this
    // page's All Apps section. Selected = white fill / dark text;
    // unselected = translucent dark / white text; FOCUS is a white
    // ring + lift, distinct from selection (the hero buttons'
    // pattern). Tabs deliberately never take the initial focus —
    // firstTile stays the hero/first card, as before.
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // v2.9 (ask C.13): the UNIFIED bar. The v2.8 homes stacked the
    // v2.1 status bar over a separate pill tab bar — two designs,
    // two rows (his 2026-10-23 photo). The media styles now build
    // ONE row of pills in one design: the tab pills, then the
    // status group as pills — the info pill (the shared
    // clock/date/weather cluster; OK opens the forecast, ask
    // C.11), the Guest pill, the glyph pill, and the action pills
    // (All Apps / Search / Wi-Fi / Profile / Settings with the
    // same click + hold-OK behaviour as the old tiles). The item
    // list is the pure core/UnifiedBar.items — the status-bar
    // config gates the status group exactly as before (showBar
    // off hides the group, never the tabs); the per-element
    // toggles and the sticky option keep working. Honest
    // adaptation: the status-bar ZONE background (theme/black)
    // no longer paints a band in these styles — every pill
    // carries its own fill, as in the Google TV reference. The
    // TV style has no tabs and keeps buildTopBar unchanged.
    // ---------------------------------------------------------------

    /** The pill fill shared by every element of the unified bar. */
    private fun pillBackground(focused: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = Ui.dp(this, 100).toFloat()
        d.setColor(com.mizan.launcher.core.Design.NETFLIX_BUTTON2_FILL)
        if (focused) d.setStroke(Ui.dp(this, 3), 0xFFFFFFFF.toInt())
        return d
    }

    private fun buildUnifiedBar(overHero: Boolean): View {
        val cfg = if (prefs.safeMode) {
            com.mizan.launcher.core.StatusBarConfig()
        } else {
            layout.statusBar
        }
        val scale = cfg.textScale()
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val rlp = LinearLayout.LayoutParams(-1, -2)
        rlp.setMargins(Ui.dp(this, 28), Ui.dp(this, 12), Ui.dp(this, 28), Ui.dp(this, 4))
        row.layoutParams = rlp
        val items = UnifiedBar.items(homeStyleKey(), cfg, guestActive(), prefs.safeMode)
        val tabs = HomeTabs.forStyle(homeStyleKey())
        var tabIndex = 0
        var spacerAdded = false
        for (item in items) {
            if (item != UnifiedBar.Item.TAB && !spacerAdded) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                spacerAdded = true
            }
            when (item) {
                UnifiedBar.Item.TAB -> row.addView(tabPill(tabs[tabIndex++]))
                UnifiedBar.Item.INFO ->
                    row.addView(unifiedInfoPill(cfg, scale, overHero))
                UnifiedBar.Item.GUEST -> row.addView(unifiedGuestPill())
                UnifiedBar.Item.GLYPHS ->
                    buildGlyphCluster(cfg, scale, overHero)?.let { glyphs ->
                        val shell = LinearLayout(this)
                        shell.orientation = LinearLayout.HORIZONTAL
                        shell.gravity = Gravity.CENTER_VERTICAL
                        shell.background = pillBackground(false)
                        shell.setPadding(
                            Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6)
                        )
                        val slp = LinearLayout.LayoutParams(-2, -2)
                        slp.setMargins(0, 0, Ui.dp(this, 8), 0)
                        shell.layoutParams = slp
                        shell.addView(glyphs)
                        row.addView(shell)
                    }
                UnifiedBar.Item.TILE_ALL_APPS -> row.addView(
                    unifiedActionPill("▦ All Apps") {
                        try {
                            startActivity(
                                Intent(this, com.mizan.launcher.ui.AllAppsActivity::class.java)
                            )
                        } catch (e: Exception) {
                            Prefs.noteError(this, "allAppsTile", e)
                        }
                    }
                )
                UnifiedBar.Item.TILE_SEARCH -> row.addView(
                    unifiedActionPill("Search") {
                        try {
                            startActivity(Intent(this, SearchActivity::class.java))
                        } catch (e: Exception) {
                            Prefs.noteError(this, "searchTile", e)
                        }
                    }
                )
                UnifiedBar.Item.TILE_WIFI ->
                    unifiedWifiPill()?.let { row.addView(it) }
                UnifiedBar.Item.TILE_PROFILE -> {
                    val pill = unifiedActionPill(
                        "👤 " + prefs.profileDisplayName(prefs.currentProfile)
                    ) { showProfileSwitchMenu() }
                    pill.setOnLongClickListener { showProfileSwitchMenu(); true }
                    row.addView(pill)
                }
                UnifiedBar.Item.TILE_SETTINGS -> {
                    val pill = unifiedActionPill("⚙  Settings") { openSettings() }
                    pill.setOnLongClickListener { showQuickSettings(); true }
                    row.addView(pill)
                }
            }
        }
        wrap.addView(row)
        buildHomeHint()?.let { wrap.addView(it) }
        updateClock()
        return wrap
    }

    /**
     * The unified info pill: the shared info cluster (clock ·
     * date · weather) inside the pill shell. Focusable — OK opens
     * the full forecast (ask C.11). Carries the tag-"weather"
     * wrapper updateWeatherWidget finds.
     */
    private fun unifiedInfoPill(
        cfg: com.mizan.launcher.core.StatusBarConfig,
        scale: Float,
        overHero: Boolean
    ): View {
        val shell = LinearLayout(this)
        shell.orientation = LinearLayout.HORIZONTAL
        shell.gravity = Gravity.CENTER_VERTICAL
        shell.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        shell.isFocusable = true
        shell.isClickable = true
        shell.background = pillBackground(false)
        shell.setOnFocusChangeListener { _, focused ->
            shell.background = pillBackground(focused)
            shell.scaleX = if (focused) 1.04f else 1f
            shell.scaleY = if (focused) 1.04f else 1f
        }
        shell.setOnClickListener { openForecast() }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 8), 0)
        shell.layoutParams = lp
        shell.addView(buildInfoCluster(cfg, scale, overHero, Gravity.CENTER_VERTICAL))
        return shell
    }

    /** The unified Guest pill (name only; non-focusable, as the old bar's pill was). */
    private fun unifiedGuestPill(): TextView {
        val tv = TextView(this)
        tv.text = prefs.profileName(prefs.currentProfile).ifBlank { "Guest" }
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 15f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        tv.background = pillBackground(false)
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 8), 0)
        tv.layoutParams = lp
        return tv
    }

    /** One unified action pill — tabPill's design, tile semantics. */
    private fun unifiedActionPill(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 15f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6))
        tv.isFocusable = true
        tv.isClickable = true
        tv.background = pillBackground(false)
        tv.setOnFocusChangeListener { _, focused ->
            tv.background = pillBackground(focused)
            tv.scaleX = if (focused) 1.06f else 1f
            tv.scaleY = if (focused) 1.06f else 1f
            tv.elevation = if (focused) Ui.dp(this, 8).toFloat() else 0f
        }
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 8), 0)
        tv.layoutParams = lp
        return tv
    }

    /**
     * The unified Wi-Fi action pill — null when the Wi-Fi settings
     * screen doesn't resolve on this box (same rule as barTileWifi).
     */
    private fun unifiedWifiPill(): TextView? {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            if (intent.resolveActivity(packageManager) == null) {
                null
            } else {
                unifiedActionPill("Wi-Fi") {
                    try {
                        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Prefs.noteError(this, "quickTile:Wi-Fi", e)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Open the full forecast (ask C.11). Fail-open. */
    private fun openForecast() {
        try {
            startActivity(Intent(this, ForecastActivity::class.java))
        } catch (e: Exception) {
            Prefs.noteError(this, "openForecast", e)
            Toast.makeText(this, "Couldn't open the forecast", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tabPill(tab: HomeTabs.Tab): TextView {
        val tv = TextView(this)
        tv.text = tab.label
        tv.textSize = 15f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6))
        tv.isFocusable = true
        tv.isClickable = true
        tv.tag = "tab:" + tab.id
        fun bg(focused: Boolean) {
            val d = GradientDrawable()
            d.cornerRadius = Ui.dp(this, 100).toFloat()
            when {
                tab.selected -> {
                    d.setColor(0xFFFFFFFF.toInt())
                    tv.setTextColor(0xFF141414.toInt())
                }
                else -> {
                    d.setColor(com.mizan.launcher.core.Design.NETFLIX_BUTTON2_FILL)
                    tv.setTextColor(0xFFFFFFFF.toInt())
                }
            }
            if (focused) d.setStroke(Ui.dp(this, 3), 0xFFFFFFFF.toInt())
            tv.background = d
        }
        bg(false)
        tv.setOnFocusChangeListener { _, hasFocus ->
            bg(hasFocus)
            tv.scaleX = if (hasFocus) 1.06f else 1f
            tv.scaleY = if (hasFocus) 1.06f else 1f
            tv.elevation = if (hasFocus) Ui.dp(this, 8).toFloat() else 0f
        }
        tv.setOnClickListener { onTabAction(tab.action) }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 8), 0)
        tv.layoutParams = lp
        return tv
    }

    private fun onTabAction(action: HomeTabs.Action) {
        when (action) {
            HomeTabs.Action.STAY -> Unit
            HomeTabs.Action.OPEN_LIBRARY -> runCatching {
                startActivity(Intent(this, com.mizan.launcher.ui.LibraryActivity::class.java))
            }
            HomeTabs.Action.OPEN_IPTV_LIVE -> runCatching {
                startActivity(IptvActivity.intent(this, IptvActivity.TAB_LIVE))
            }
            HomeTabs.Action.OPEN_IPTV_MOVIES -> runCatching {
                startActivity(IptvActivity.intent(this, IptvActivity.TAB_MOVIES))
            }
            HomeTabs.Action.OPEN_IPTV_SERIES -> runCatching {
                startActivity(IptvActivity.intent(this, IptvActivity.TAB_SERIES))
            }
            HomeTabs.Action.OPEN_GUIDE -> runCatching {
                startActivity(Intent(this, IptvGuideActivity::class.java))
            }
            HomeTabs.Action.FOCUS_ALL_APPS -> focusAllAppsSection()
        }
    }

    /**
     * The Apps tab: move focus into this page's All Apps section
     * (its first focusable descendant). Fail-open: a hidden/absent
     * section says so instead of stranding focus.
     */
    private fun focusAllAppsSection() {
        val anchor = allAppsAnchor
        val target = anchor?.let { firstFocusableDescendant(it) }
        if (target == null) {
            Toast.makeText(this, "All Apps is hidden for this profile", Toast.LENGTH_SHORT).show()
            return
        }
        anchor.requestRectangleOnScreen(
            android.graphics.Rect(0, 0, anchor.width, anchor.height), true
        )
        target.post {
            target.requestFocus()
            target.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, target.width, target.height), true
            )
        }
    }

    /** Breadth-first first focusable view inside [root] (or root itself). */
    private fun firstFocusableDescendant(root: View): View? {
        if (root.isFocusable && root.visibility == View.VISIBLE) return root
        if (root is ViewGroup) {
            val queue = ArrayDeque<View>()
            for (i in 0 until root.childCount) queue.add(root.getChildAt(i))
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                if (v.visibility != View.VISIBLE) continue
                if (v.isFocusable) return v
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
                }
            }
        }
        return null
    }

    /** The v2.8 focus ring: a white rounded stroke overlay toggled by card focus. */
    private fun gtvBorderOverlay(radiusDp: Int): View {
        val v = View(this)
        val d = GradientDrawable()
        d.cornerRadius = Ui.dp(this, radiusDp).toFloat()
        d.setColor(0x00000000)
        d.setStroke(Ui.dp(this, 3), 0xFFFFFFFF.toInt())
        v.background = d
        v.layoutParams = FrameLayout.LayoutParams(-1, -1)
        v.visibility = View.INVISIBLE
        v.isFocusable = false
        v.isClickable = false
        return v
    }

    /**
     * The bar's info cluster (v2.1): clock · date · weather on ONE
     * baseline, plain text, dot separators. Sets the [clockView] /
     * [dateView] fields; the weather segment lives in a
     * tag-"weather" wrapper so updateWeatherWidget can hide it whole
     * (separator included) when there is no weather, and its text
     * view keeps the tag "weather_text" for findWeatherView.
     * Extracted in v2.9 so the unified bar's info pill builds the
     * exact same cluster.
     */
    private fun buildInfoCluster(
        cfg: com.mizan.launcher.core.StatusBarConfig,
        scale: Float,
        overHero: Boolean,
        gravity: Int
    ): LinearLayout {
        val left = LinearLayout(this)
        left.orientation = LinearLayout.HORIZONTAL
        left.gravity = gravity
        clockView = null
        dateView = null
        var infoItems = 0
        fun infoSep(target: LinearLayout) {
            val sep = TextView(this)
            sep.text = "·"
            sep.setTextColor(subColour)
            sep.textSize = com.mizan.launcher.core.Design.BAR_META_SP * scale
            val slp2 = LinearLayout.LayoutParams(-2, -2)
            slp2.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0)
            sep.layoutParams = slp2
            target.addView(sep)
        }
        if (cfg.showClock) {
            clockView = TextView(this).apply {
                setTextColor(textColour)
                textSize = com.mizan.launcher.core.Design.BAR_CLOCK_SP * scale
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
            }
            left.addView(clockView)
            infoItems++
        }
        if (cfg.showDate) {
            if (infoItems > 0) infoSep(left)
            dateView = TextView(this).apply {
                setTextColor(subColour)
                textSize = com.mizan.launcher.core.Design.BAR_META_SP * scale
            }
            left.addView(dateView)
            infoItems++
        }
        if (!prefs.safeMode && cfg.showWeather) {
            val weatherWrap = LinearLayout(this)
            weatherWrap.orientation = LinearLayout.HORIZONTAL
            weatherWrap.gravity = Gravity.BOTTOM
            weatherWrap.tag = "weather"
            weatherWrap.visibility = View.GONE
            if (infoItems > 0) infoSep(weatherWrap)
            val weather = TextView(this)
            weather.tag = "weather_text"
            weather.setTextColor(subColour)
            weather.textSize = com.mizan.launcher.core.Design.BAR_META_SP * scale
            if (overHero) weather.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            weatherWrap.addView(weather)
            left.addView(weatherWrap)
            infoItems++
        }
        return left
    }

    /**
     * The bar's status GLYPHS (v2.1): Wi-Fi + Bluetooth,
     * non-focusable, no word labels, dimmed when off — as one
     * cluster. Sets [networkView]. Extracted in v2.9 so the
     * unified bar's glyph pill shares it. Null when neither
     * glyph renders.
     */
    private fun buildGlyphCluster(
        cfg: com.mizan.launcher.core.StatusBarConfig,
        scale: Float,
        overHero: Boolean
    ): LinearLayout? {
        val cluster = LinearLayout(this)
        cluster.orientation = LinearLayout.HORIZONTAL
        cluster.gravity = Gravity.CENTER_VERTICAL
        var any = false
        networkView = null
        if (cfg.showNetwork) {
            val glyph = TextView(this)
            glyph.setTextColor(textColour)
            glyph.textSize = com.mizan.launcher.core.Design.BAR_GLYPH_SP * scale
            glyph.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0)
            networkView = glyph
            cluster.addView(glyph)
            any = true
        }
        if (!prefs.safeMode && cfg.showBluetooth) {
            bluetoothGlyph()?.let { (glyphText, glyphAlpha) ->
                val bt = TextView(this)
                bt.text = glyphText
                bt.alpha = glyphAlpha
                bt.setTextColor(textColour)
                bt.textSize = com.mizan.launcher.core.Design.BAR_GLYPH_SP * scale
                bt.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0)
                if (overHero) bt.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
                cluster.addView(bt)
                any = true
            }
        }
        return if (any) cluster else null
    }

    private fun buildTopBar(overHero: Boolean = false): View {
        val cfg = if (prefs.safeMode) {
            com.mizan.launcher.core.StatusBarConfig()
        } else {
            layout.statusBar
        }
        val scale = cfg.textScale()

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL

        if (cfg.showBar) {
            // v2.1 REORGANIZED (Haythem's photo: the giant clock, the
            // weather as a focusable CHIP holding focus, floating
            // "• Wi-Fi BT" text, Wi-Fi twice). The bar is ONE row:
            // LEFT = an info cluster of NON-focusable plain text
            // (clock · date · weather, dot separators, no chips);
            // RIGHT = status glyphs (Wi-Fi / Bluetooth, no words)
            // then the action tiles in one panel — composition from
            // core/StatusBarLogic.barTiles. Focus never lands in the
            // info cluster.
            val hero = LinearLayout(this)
            hero.orientation = LinearLayout.HORIZONTAL
            hero.gravity = Gravity.CENTER_VERTICAL

            // Hero background follows the bar background config:
            // transparent (wallpaper + scrim show through), the
            // theme's top colour, or black — solid or translucent,
            // now as one rounded zone instead of a hard-edged strip.
            // v2.0: over the Tracker hero it is ALWAYS transparent.
            when (if (overHero) {
                com.mizan.launcher.core.StatusBarConfig.BG_TRANSPARENT
            } else {
                cfg.background
            }) {
                com.mizan.launcher.core.StatusBarConfig.BG_THEME -> {
                    val alpha = com.mizan.launcher.core.StatusBarConfig.backgroundAlpha(cfg.opacity)
                    val bg = GradientDrawable()
                    bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
                    bg.setColor((alpha shl 24) or (Themes.preset(prefs.themeId).bgTop and 0x00FFFFFF))
                    hero.background = bg
                }
                com.mizan.launcher.core.StatusBarConfig.BG_BLACK -> {
                    val alpha = com.mizan.launcher.core.StatusBarConfig.backgroundAlpha(cfg.opacity)
                    val bg = GradientDrawable()
                    bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
                    bg.setColor(alpha shl 24)
                    hero.background = bg
                }
                else -> { /* transparent: no background */ }
            }
            if (!overHero && cfg.background != com.mizan.launcher.core.StatusBarConfig.BG_TRANSPARENT) {
                hero.setPadding(
                    Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M),
                    Ui.dp(this, 12),
                    Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M),
                    Ui.dp(this, 12)
                )
            }

            // LEFT (v2.1): the info cluster — built by the shared
            // buildInfoCluster (v2.9 extraction; the unified bar's
            // info pill uses the same builder).
            val left = buildInfoCluster(cfg, scale, overHero, Gravity.BOTTOM)
            // v2.9 (ask C.11): the forecast is reachable from the
            // bar in EVERY style — OK on the info cluster opens
            // ui/ForecastActivity. Settings' weather text has
            // promised exactly this press since v1.4; the v2.1
            // plain-text cluster could never take it. One focus
            // stop, only when the cluster shows anything at all.
            if (left.childCount > 0) {
                left.isFocusable = true
                left.isClickable = true
                left.setOnClickListener { openForecast() }
                left.setOnFocusChangeListener { _, focused ->
                    left.background = if (focused) {
                        GradientDrawable().apply {
                            cornerRadius = Ui.dp(this@MainActivity, 100).toFloat()
                            setStroke(Ui.dp(this@MainActivity, 2), 0xFFFFFFFF.toInt())
                            setColor(0x22FFFFFF)
                        }
                    } else {
                        null
                    }
                }
            }
            hero.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            // RIGHT (v2.1): the status GLYPHS first (Guest pill,
            // Wi-Fi, Bluetooth — non-focusable, no word labels,
            // dimmed when off), then the action tiles in ONE
            // translucent panel, composed by core/StatusBarLogic.
            val right = LinearLayout(this)
            right.orientation = LinearLayout.HORIZONTAL
            right.gravity = Gravity.CENTER_VERTICAL

            // Guest pill (v1.4): while the Guest profile is active.
            // (Bar hidden → the pill moves to the top-left corner;
            // see buildRoot.)
            if (!prefs.safeMode && guestActive()) {
                right.addView(guestPill())
            }
            // v2.9 extraction: the glyph cluster is shared with
            // the unified bar's glyph pill (buildGlyphCluster).
            buildGlyphCluster(cfg, scale, overHero)?.let { right.addView(it) }

            val panel = LinearLayout(this)
            panel.orientation = LinearLayout.HORIZONTAL
            panel.gravity = Gravity.CENTER_VERTICAL
            val panelBg = GradientDrawable()
            panelBg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
            panelBg.setColor(com.mizan.launcher.core.Design.PANEL_FILL)
            panel.background = panelBg
            panel.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
            for (tile in com.mizan.launcher.core.StatusBarLogic.barTiles(cfg, prefs.safeMode)) {
                when (tile) {
                    com.mizan.launcher.core.StatusBarLogic.BarTile.ALL_APPS ->
                        panel.addView(barTileAllApps())
                    com.mizan.launcher.core.StatusBarLogic.BarTile.SEARCH ->
                        panel.addView(barTileSearch())
                    com.mizan.launcher.core.StatusBarLogic.BarTile.WIFI ->
                        barTileWifi()?.let { panel.addView(it) }
                    // Profile tile (v1.4): the one consistent place
                    // profile switching lives on the home screen.
                    // Click or hold-OK opens the switch menu;
                    // switching TO Guest is instant, switching AWAY
                    // may ask for the pattern (core/GuestGate).
                    com.mizan.launcher.core.StatusBarLogic.BarTile.PROFILE -> {
                        val profileTile = quickTile(
                            "👤 " + prefs.profileDisplayName(prefs.currentProfile)
                        ) { showProfileSwitchMenu() }
                        profileTile.setOnLongClickListener { showProfileSwitchMenu(); true }
                        panel.addView(profileTile)
                    }
                    // The bar's Settings tile (v2.0: hold-OK opens
                    // the Quick settings popover).
                    com.mizan.launcher.core.StatusBarLogic.BarTile.SETTINGS -> {
                        val settingsTile = quickTile("⚙  Settings") { openSettings() }
                        settingsTile.setOnLongClickListener { showQuickSettings(); true }
                        panel.addView(settingsTile)
                    }
                }
            }
            right.addView(panel)
            hero.addView(right)
            wrap.addView(hero)
        }

        if (overHero) {
            // Legibility over the backdrop art (v2.0).
            clockView?.setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
            dateView?.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            networkView?.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
        }

        buildHomeHint()?.let { wrap.addView(it) }
        updateClock()
        return wrap
    }

    /**
     * v2.0 Quick settings popover (hold-OK on either gear): the
     * system settings screens that resolve on this device (targets
     * pre-filtered through core/QuickSettings — a missing screen
     * simply never appears) plus Sreeb Settings. Each launch is
     * fail-open: a screen that still refuses gets a toast.
     */
    private fun showQuickSettings() {
        val visible = QuickSettings.visible(QuickSettings.TARGETS) { action ->
            try {
                Intent(action).resolveActivity(packageManager) != null
            } catch (e: Exception) {
                false
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Quick settings")
            .setItems(visible.map { it.label }.toTypedArray()) { _, which ->
                val target = visible[which]
                if (target.action.isEmpty()) {
                    openSettings()
                } else if (com.mizan.launcher.core.QuickSettings.isInternal(target)) {
                    // v3.1 (ask 41): internal targets route by
                    // scheme to our own pages — never startActivity
                    // on the raw action string.
                    when (target.action) {
                        "sreeb:sortsections" -> openSettings("sortsections")
                        "sreeb:refreshhome" -> refreshHomeData()
                        else -> openSettings()
                    }
                } else {
                    try {
                        startActivity(Intent(target.action))
                    } catch (e: Exception) {
                        Prefs.noteError(this, "quickSettings", e)
                        Toast.makeText(
                            this, "${target.label} isn't available on this TV",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * v3.2 (ask 43): the EXPLICIT home-data refresh — the only
     * full refresh left, and the only one the user can ask for
     * by name (quick-settings popover, "Refresh home data").
     * Forces every source once (tracker provider, discovery
     * worker, match recompute, Live TV, WatchNext, Most Used,
     * weather, app list); each landing rebuilds once with what
     * changed. Records the `last refresh:` diagnostics stamp
     * (kind "manual") so the next photo can prove no refresh
     * happened without the user asking.
     */
    private fun refreshHomeData() {
        if (!::prefs.isInitialized) return
        prefs.lastRefreshAt = System.currentTimeMillis()
        prefs.lastRefreshKind = "manual"
        Toast.makeText(this, "Refreshing home data…", Toast.LENGTH_SHORT).show()
        TrackerClient.refreshAsync(this, force = true) { if (isTrackerFamily()) rebuild() }
        DiscoveryData.refreshNow(this)
        WatchPlay.kickRecompute(this)
        if (!prefs.safeMode) {
            val beforeLive = LiveTv.cached()
            LiveTv.refreshAsync(this) { result ->
                if (result != beforeLive && !isTrackerFamily()) rebuild()
            }
            TvData.refreshWatchNextAsync(this) { rebuild() }
            if (MostUsed.isGranted(this)) {
                val beforeStats = MostUsed.cachedStats()
                MostUsed.refreshAsync(this, appsByPkg.mapValues { it.value.label }) { stats ->
                    if (stats != beforeStats && !isTrackerFamily() && !isIptvStyle()) rebuild()
                }
            }
        }
        maybeFetchWeather(force = true)
        AppCache.refreshNow(this)
        rebuild()
    }

    /** (v2.1: the wordy "ᛒ BT" label retired with the bar reorganization — see bluetoothGlyph.) */

    /**
     * One-line dismissible hint shown whenever Sreeb is not the
     * default home. v2.0: the watcher-enabled suppression is GONE —
     * with the watcher on but Sreeb not default, Home lands on the
     * stock launcher for a second or two before the watcher pulls
     * Sreeb forward (Haythem's on-device report), and this hint is
     * the pointer to the real fix (Settings › Make Sreeb your home
     * screen, Step 1). It renders in every home style via the top bar.
     */
    private fun buildHomeHint(): View? {
        if (prefs.safeMode || prefs.homeHintDismissed) return null
        return try {
            if (com.mizan.launcher.data.HomeStatus.isDefaultHome(this)) return null
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, Ui.dp(this, 10), 0, 0)
            row.layoutParams = lp
            row.setBackgroundColor(0x552196F3)
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8))

            val label = TextView(this)
            label.text = "Sreeb isn't your home screen yet — open Settings > Make Sreeb your home screen"
            label.setTextColor(textColour); label.textSize = 16f
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))

            val dismiss = TextView(this)
            dismiss.text = "Dismiss"
            dismiss.setTextColor(textColour); dismiss.textSize = 16f
            dismiss.isFocusable = true; dismiss.isClickable = true
            dismiss.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6))
            Ui.focusLift(dismiss, accent, 10)
            dismiss.setOnClickListener {
                prefs.homeHintDismissed = true
                rebuild()
            }
            row.addView(dismiss)
            row
        } catch (e: Exception) {
            Prefs.noteError(this, "homeHint", e)
            null
        }
    }

    /**
     * Wi-Fi / Bluetooth / Sound / Picture tiles — each hides if its
     * intent resolves nowhere, and (v1.3) each is gated by its own
     * per-tile switch under the showQuickTiles master.
     */
    /** v2.1 bar tile: All Apps — opens the full-screen tab (v1.5). */
    private fun barTileAllApps(): View = quickTile("▦ All Apps") {
        try {
            startActivity(Intent(this, com.mizan.launcher.ui.AllAppsActivity::class.java))
        } catch (e: Exception) { Prefs.noteError(this, "allAppsTile", e) }
    }

    /** v2.1 bar tile: Search — opens Sreeb's own Search screen (v1.4). */
    private fun barTileSearch(): View = quickTile("Search") {
        try { startActivity(Intent(this, SearchActivity::class.java)) }
        catch (e: Exception) { Prefs.noteError(this, "searchTile", e) }
    }

    /**
     * v2.1 bar tile: Wi-Fi settings. Default OFF since v2.1 (the
     * status glyph + the gear's quick-settings popover cover it);
     * renders only when the profile re-enabled the tile switch, and
     * only when the settings screen resolves (fail-open).
     */
    private fun barTileWifi(): View? {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            if (intent.resolveActivity(packageManager) == null) {
                null
            } else {
                quickTile("Wi-Fi") {
                    try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { Prefs.noteError(this, "quickTile:Wi-Fi", e) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The Bluetooth status glyph for the bar/strip clusters (v2.1):
     * "ᛒ" at full alpha when Bluetooth is on, dimmed when off; null
     * when the device has no Bluetooth adapter at all.
     */
    private fun bluetoothGlyph(): Pair<String, Float>? {
        return try {
            @Suppress("DEPRECATION")
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return null
            if (adapter.isEnabled) "ᛒ" to 1f else "ᛒ" to 0.35f
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------- guest / profiles (v1.4)

    /** True while the flagged Guest profile is the active profile. */
    private fun guestActive(): Boolean =
        GuestGate.isGuestActive(prefs.currentProfile, prefs.guestProfile)

    /**
     * The subtle tag shown while the Guest profile is active.
     * v1.9 choice: when the guest profile has been RENAMED, the pill
     * shows the NAME (the pill's job is "whose home is this"); that
     * the profile is a guest is conveyed by the gate behaviour and
     * Settings, not by a second word on the pill.
     */
    private fun guestPill(): TextView {
        val pill = TextView(this)
        pill.text = prefs.profileName(prefs.currentProfile).ifBlank { "Guest" }
        pill.setTextColor(textColour)
        pill.textSize = 15f
        pill.gravity = Gravity.CENTER
        pill.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0)
        pill.layoutParams = lp
        pill.background = Ui.tileBackground(accent, false, 12, this)
        pill.alpha = 0.85f
        return pill
    }

    /**
     * Open Settings. Since v1.5 the entry gate lives INSIDE
     * SettingsActivity.onCreate (core/SettingsGate merges the global
     * "Lock Settings" switch with the Guest rule into one prompt), so
     * every entry point — this tile, the corner gear, watcher
     * deep-links — is covered exactly once, and nothing prompts here.
     */
    /**
     * v3.1 (ask 41): [category] deep-links SettingsActivity at a
     * page (used by the quick-settings "Sort sections" entry);
     * null opens at the default first category, as before.
     */
    private fun openSettings(category: String? = null) {
        val intent = Intent(this, SettingsActivity::class.java)
        if (category != null) intent.putExtra("category", category)
        startActivity(intent)
    }

    /** Profile switch menu on the status-bar profile tile (click or hold-OK). */
    private fun showProfileSwitchMenu() {
        val others = listOf(1, 2).filter { it != prefs.currentProfile }
        val labels = others.map { "Switch to ${prefs.profileDisplayName(it)}" }
        AlertDialog.Builder(this)
            .setTitle("Profile: ${prefs.profileDisplayName(prefs.currentProfile)}")
            .setItems(labels.toTypedArray()) { _, which -> requestProfileSwitch(others[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Switch profiles through the Guest gate: TO Guest is instant and
     * never gated; AWAY from Guest (lock on + pattern set) asks for the
     * pattern first; between personal profiles is free.
     */
    private fun requestProfileSwitch(target: Int) {
        val action = when {
            prefs.guestProfile != 0 && target == prefs.guestProfile ->
                GuestGate.Action.SWITCH_TO_GUEST
            guestActive() -> GuestGate.Action.SWITCH_AWAY_FROM_GUEST
            else -> GuestGate.Action.SWITCH_BETWEEN_PERSONAL
        }
        val decision = GuestGate.decide(
            guestActive(), prefs.guestLockSettings, prefs.arrowPin.isNotEmpty(), action
        )
        if (decision == GuestGate.Decision.REQUIRE_PATTERN) {
            runPatternGate("Enter pattern to leave Guest") { doSwitchProfile(target) }
        } else {
            doSwitchProfile(target)
        }
    }

    private fun doSwitchProfile(target: Int) {
        // v1.9: Prefs.switchProfile also clears the unlock-grace
        // stamp — grace never crosses a profile switch.
        prefs.switchProfile(target)
        lastNowPlayingSignature = null
        rebuild()
        Toast.makeText(this, "Switched to ${prefs.profileDisplayName(target)}", Toast.LENGTH_SHORT).show()
    }

    private fun quickTile(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(textColour)
        tv.textSize = 17f
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0)
        tv.layoutParams = lp
        Ui.focusLift(tv, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
        tv.background = Ui.tileBackground(accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this)
        tv.setOnClickListener { onClick() }
        return tv
    }

    // -------------------------------------------------------------- sections

    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        // v1.6: the unified small-caps + accent-rule header.
        box.addView(Ui.sectionHeader(this, title, textColour, accent))
        return box
    }

    private fun buildFavoritesSection(): View {
        val box = section("Favorites")
        val favs = layout.favorites.mapNotNull { appsByPkg[it] }
            .filter { it.pkg !in layout.hidden }
        if (favs.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No favorites yet — hold OK on any app below and choose “Add favorite”."
            hint.setTextColor(subColour); hint.textSize = 17f
            box.addView(hint)
            return box
        }
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (entry in favs) row.addView(appTile(entry, Source.Favorites))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * Recently Used row (v1.4): the profile's MRU launches FROM Sreeb
     * (core/RecentsStore). Hidden when the store is empty or every
     * recorded app was uninstalled/hidden since. Tiles are the uniform
     * app tiles; the hold-OK menu works as elsewhere (minus Move —
     * this row's order IS its recency).
     */
    private fun buildRecentsSection(): View? {
        val entries = RecentsStore.decode(prefs.recents)
            .mapNotNull { appsByPkg[it.pkg] }
            .filter { it.pkg !in layout.hidden }
        if (entries.isEmpty()) return null
        val box = section("Recently Used")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (entry in entries) row.addView(appTile(entry, Source.Recents))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * Most Used row (v2.0, TV view): the profile's most-launched apps
     * over the last 30 days, from UsageStatsManager resume events
     * (data/MostUsed gathers, core/MostUsedLogic ranks). Needs Usage
     * Access: without the grant the row hides (Settings › Home screen
     * rows offers the grant link). Tiles use the Recents menu flavour
     * (no Move — the order IS the usage ranking).
     * v3.2 (ask 43): the 30-day query runs ONLY when the cache
     * is empty (first-build probe) or via explicit Refresh home
     * data — never on open/resume any more.
     */
    private fun buildMostUsedSection(): View? {
        if (!MostUsed.isGranted(this)) return null
        // Render from the usage cache only — an empty cache probes
        // once on a background thread, its completion rebuilds.
        val stats = MostUsed.cachedStats()
        if (stats == null) {
            MostUsed.refreshAsync(this, appsByPkg.mapValues { it.value.label }) {
                if (!isTrackerStyle()) rebuild()
            }
            return null
        }
        val top = MostUsedLogic.topUsed(stats, appsByPkg.keys, layout.hidden, 8)
        if (top.isEmpty()) return null
        val box = section("Most Used")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (stat in top) {
            appsByPkg[stat.pkg]?.let { row.addView(appTile(it, Source.Recents)) }
        }
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * Live TV row (v2.0, TV view, BEST-EFFORT): tuner channels from
     * TvContract via data/LiveTv's cached probe. Renders only when
     * the probe classified OK with channels — on the many TVs where
     * READ_TV_LISTINGS is system-reserved the row simply never
     * appears and the diagnostics `live tv:` line explains why.
     */
    private fun buildLiveTvSection(): View? {
        val result = LiveTv.cached()
        if (result == null) {
            // First build: probe in the background, rebuild when it lands.
            LiveTv.refreshAsync(this) { if (!isTrackerStyle() && !isIptvStyle()) rebuild() }
            return null
        }
        if (result.state != LiveTvLogic.State.OK || result.channels.isEmpty()) return null
        val box = section("Live TV")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (channel in result.channels.take(24)) row.addView(channelTile(channel))
        box.addView(horizontalScroller(row))
        return box
    }

    /** One channel card: logo over the 📺 glyph tile; OK tunes via the TV app. */
    private fun channelTile(channel: LiveTvLogic.Channel): View {
        val tile = glyphTile("📺", channel.name, wide = true)
        val iv = ImageView(this)
        // v2.9 (ask C.14): a channel logo renders COMPLETE —
        // centre-crop could cut the logo's edges.
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        // Index 1: above the centred glyph, below the focus name label.
        tile.addView(iv, 1, FrameLayout.LayoutParams(-1, -1))
        LiveTv.loadLogo(this, channel, iv)
        tile.setOnClickListener {
            try {
                startActivity(LiveTv.channelIntent(channel))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No TV app can tune this channel", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Prefs.noteError(this, "liveTvTune", e)
                Toast.makeText(this, "Couldn't tune that channel", Toast.LENGTH_SHORT).show()
            }
        }
        return tile
    }

    // ------------------------------------------------------------ Now Playing (v1.4)

    /** Signature of the currently-shown Now Playing cards; null = not yet known. */
    private var lastNowPlayingSignature: String? = null

    private fun nowPlayingSignature(cards: List<NowPlayingLogic.Card>): String =
        cards.joinToString(";") { "${it.pkg}|${it.title}|${it.playing}" }

    /**
     * Now Playing row: wide cards for the active media sessions. The
     * row exists only when notification access is granted AND at least
     * one session maps to a card — otherwise it hides silently
     * (fail-open), and diagnostics records granted/session counts.
     */
    private fun buildNowPlayingSection(): View? {
        val granted = NowPlayingData.isGranted(this)
        DiagCache.nowPlayingGranted = granted
        if (!granted) {
            DiagCache.nowPlayingCount = 0
            lastNowPlayingSignature = ""
            return null
        }
        val live = NowPlayingData.activeCards(this)
        DiagCache.nowPlayingCount = NowPlayingData.sessionCount(this)
        lastNowPlayingSignature = nowPlayingSignature(live.map { it.card })
        if (live.isEmpty()) return null
        val box = section("Now Playing")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (item in live) row.addView(nowPlayingCard(item))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * One Now Playing card (the uniform wide size): artwork when the
     * session provides it, title + artist always visible, state glyph
     * in the corner. OK toggles play/pause; LEFT/RIGHT skip prev/next
     * when the session supports them (consumed only then — otherwise
     * focus moves along the row as usual).
     */
    private fun nowPlayingCard(item: NowPlayingData.LiveCard): View {
        val card = item.card
        val tile = baseTile(wide = true)
        if (item.artwork != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(item.artwork)
            // v2.9 (ask C.14): session artwork (usually square
            // album art) renders COMPLETE over the tile's themed
            // fill — centre-crop in the wide tile cut it.
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            tile.addView(iv, FrameLayout.LayoutParams(-1, -1))
        } else {
            val glyph = TextView(this)
            glyph.text = "♪"
            glyph.setTextColor(textColour)
            glyph.textSize = 40f
            glyph.gravity = Gravity.CENTER
            val glp = FrameLayout.LayoutParams(-1, -1)
            glyph.layoutParams = glp
            tile.addView(glyph)
        }
        // Title + artist plate along the bottom.
        val plate = LinearLayout(this)
        plate.orientation = LinearLayout.VERTICAL
        plate.setBackgroundColor(0xB3000000.toInt())
        plate.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 6))
        val plp = FrameLayout.LayoutParams(-1, -2)
        plp.gravity = Gravity.BOTTOM
        plate.layoutParams = plp
        val title = TextView(this)
        title.text = card.title
        title.setTextColor(textColour); title.textSize = 15f
        title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        plate.addView(title)
        if (card.artist.isNotEmpty()) {
            val artist = TextView(this)
            artist.text = card.artist
            artist.setTextColor(0xFFCCCCCC.toInt()); artist.textSize = 13f
            artist.maxLines = 1; artist.ellipsize = android.text.TextUtils.TruncateAt.END
            plate.addView(artist)
        }
        tile.addView(plate)
        val state = TextView(this)
        state.text = if (card.playing) "❚❚" else "▶"
        state.setTextColor(textColour); state.textSize = 14f
        val slp = FrameLayout.LayoutParams(-2, -2)
        slp.gravity = Gravity.TOP or Gravity.END
        slp.setMargins(0, Ui.dp(this, 5), Ui.dp(this, 8), 0)
        state.layoutParams = slp
        tile.addView(state)

        Ui.focusLift(tile, accent, 16)
        tile.setOnClickListener {
            try {
                val controls = item.controller.transportControls
                if (card.playing) controls.pause() else controls.play()
                handler.postDelayed({ rebuild() }, 450)
            } catch (e: Exception) {
                Prefs.noteError(this, "nowPlayingToggle", e)
            }
        }
        tile.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            try {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!card.canPrev) return@setOnKeyListener false
                        item.controller.transportControls.skipToPrevious()
                        handler.postDelayed({ rebuild() }, 450)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!card.canNext) return@setOnKeyListener false
                        item.controller.transportControls.skipToNext()
                        handler.postDelayed({ rebuild() }, 450)
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Prefs.noteError(this, "nowPlayingSkip", e)
                false
            }
        }
        return tile
    }

    private fun buildAppsSection(): View {
        // v2.8: the media styles render All Apps as the Google-TV
        // circular row ("Your apps"); the TV style keeps its grid.
        if (isTrackerFamily() || isIptvStyle()) return buildAppsSectionCircular()
        val box = section("All Apps")
        // v1.3 folder dedupe: an app inside any folder lives ONLY in
        // its folder here (see FolderLogic); removing it from the
        // folder brings it back. Favorites are unaffected.
        val visiblePkgs = FolderLogic.allAppsVisible(
            apps.map { it.pkg }, layout.hidden.toSet(), layout.folders
        ).toSet()
        val visible = apps.filter { it.pkg in visiblePkgs }
        if (visible.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No launchable apps found."
            hint.setTextColor(subColour); hint.textSize = 17f
            box.addView(hint)
            return box
        }
        val ordered = orderedApps(visible)
        val cols = prefs.columns
        var i = 0
        while (i < ordered.size) {
            val rowLayout = LinearLayout(this); rowLayout.orientation = LinearLayout.HORIZONTAL
            val chunk = ordered.subList(i, minOf(i + cols, ordered.size))
            for (entry in chunk) rowLayout.addView(appTile(entry, Source.AllApps))
            box.addView(rowLayout)
            i += cols
        }
        return box
    }

    /**
     * The All Apps section in the Google-TV language (v2.8, the
     * media styles only): CIRCULAR icons on subtle dark discs with
     * the label always under — the stock home's "Your apps" row.
     * Same machinery as the grid (folder dedupe via
     * [FolderLogic.allAppsVisible], custom order via [orderedApps],
     * hold-OK menu, hold-and-move by tag); focus is a white ring
     * around the disc + a slight scale.
     */
    private fun buildAppsSectionCircular(): View {
        val box = section("All Apps")
        allAppsAnchor = box
        val visiblePkgs = FolderLogic.allAppsVisible(
            apps.map { it.pkg }, layout.hidden.toSet(), layout.folders
        ).toSet()
        val visible = apps.filter { it.pkg in visiblePkgs }
        if (visible.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No launchable apps found."
            hint.setTextColor(subColour); hint.textSize = 17f
            box.addView(hint)
            return box
        }
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (entry in orderedApps(visible)) row.addView(appCircleTile(entry))
        box.addView(horizontalScroller(row))
        return box
    }

    /** One circular app icon (see [buildAppsSectionCircular]). */
    private fun appCircleTile(entry: AppEntry): View {
        val m = currentTileMetrics()
        val discPx = (m.iconPx * 1.45f).toInt().coerceAtLeast(m.iconPx + Ui.dp(this, 16))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val colLp = LinearLayout.LayoutParams(discPx + Ui.dp(this, 16), -2)
        colLp.setMargins(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4))
        col.layoutParams = colLp

        val disc = FrameLayout(this)
        disc.isFocusable = true
        disc.isClickable = true
        // Move mode finds the grabbed tile by package after a rebuild.
        disc.tag = entry.pkg
        disc.layoutParams = LinearLayout.LayoutParams(discPx, discPx)
        val discBg = GradientDrawable()
        discBg.shape = GradientDrawable.OVAL
        discBg.setColor(0xFF2A2A2A.toInt())
        disc.background = discBg
        if (firstTile == null) firstTile = disc

        val iv = ImageView(this)
        // Custom artwork (core/ArtworkStore) as on app tiles, but
        // always rendered in the circle shape — the row IS circles.
        val fallbackIcon = Ui.shapedIcon(entry.icon, m.iconPx, "circle")
        when (val art = artworkByPkg[entry.pkg]) {
            null -> iv.setImageBitmap(fallbackIcon)
            else -> when (art.kind) {
                ArtworkStore.Kind.PRESET -> iv.setImageBitmap(
                    Ui.presetArtwork(
                        ArtworkStore.presetColors(ArtworkStore.presetIndex(art.value)),
                        entry.label, m.iconPx, "circle"
                    )
                )
                ArtworkStore.Kind.URL -> ArtworkImages.load(
                    this, entry.pkg, art.value, iv, m.iconPx, "circle", fallbackIcon
                )
            }
        }
        val ilp = FrameLayout.LayoutParams(m.iconPx, m.iconPx)
        ilp.gravity = Gravity.CENTER
        iv.layoutParams = ilp
        disc.addView(iv)
        if (prefs.lockedApps.contains(entry.pkg)) {
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(-2, -2)
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 2), Ui.dp(this, 4), 0)
            lock.layoutParams = llp
            disc.addView(lock)
        }
        // Focus ring: an oval white stroke overlay (focusLift can't
        // be used here — it swaps in a rounded-rect background).
        val ring = View(this)
        val ringBg = GradientDrawable()
        ringBg.shape = GradientDrawable.OVAL
        ringBg.setColor(0x00000000)
        ringBg.setStroke(Ui.dp(this, 3), 0xFFFFFFFF.toInt())
        ring.background = ringBg
        ring.layoutParams = FrameLayout.LayoutParams(-1, -1)
        ring.visibility = View.INVISIBLE
        ring.isFocusable = false
        ring.isClickable = false
        disc.addView(ring)
        disc.setOnFocusChangeListener { _, hasFocus ->
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            disc.scaleX = if (hasFocus) 1.05f else 1f
            disc.scaleY = if (hasFocus) 1.05f else 1f
            disc.elevation = if (hasFocus) Ui.dp(this, 8).toFloat() else 0f
        }
        disc.setOnClickListener { launchWithPin(entry) }
        disc.setOnLongClickListener { openAppMenu(entry, Source.AllApps, null); true }
        col.addView(disc)

        val label = TextView(this)
        label.text = entry.label
        label.setTextColor(0xFFFFFFFF.toInt())
        label.textSize = 13f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        val llp2 = LinearLayout.LayoutParams(-1, -2)
        llp2.setMargins(0, Ui.dp(this, 5), 0, 0)
        label.layoutParams = llp2
        col.addView(label)
        return col
    }

    /** Custom order first (as arranged with hold-and-move), then the rest alphabetically. */
    private fun orderedApps(visible: List<AppEntry>): List<AppEntry> {
        val byPkg = visible.associateBy { it.pkg }
        val head = layout.allAppsOrder.mapNotNull { byPkg[it] }
        val headPkgs = head.map { it.pkg }.toSet()
        return head + visible.filter { it.pkg !in headPkgs }
    }

    private fun buildFoldersSection(): View? {
        // v1.5: the home Folders row shows ROOT folders only — nested
        // folders live inside their parent and are reached by opening it.
        val roots = FolderTree.rootFolders(layout.folders)
        if (roots.isEmpty() && !prefs.safeMode) {
            // Still show the section so "New folder" is discoverable.
            val box = section("Folders")
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.addView(newFolderTile())
            box.addView(horizontalScroller(row))
            return box
        }
        val box = section("Folders")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (folder in roots) {
            row.addView(folderTile(folder))
        }
        row.addView(newFolderTile())
        box.addView(horizontalScroller(row))
        return box
    }

    /** "3 apps · 1 folder" style contents summary for folder tiles/menus. */
    private fun folderContentsLabel(folder: Folder): String {
        val apps = folder.appPackages.size
        val subs = folder.subFolderIds.size
        return when {
            apps == 0 && subs == 0 -> "empty"
            subs == 0 -> if (apps == 1) "1 app" else "$apps apps"
            apps == 0 -> if (subs == 1) "1 folder" else "$subs folders"
            else -> "$apps apps · $subs ${if (subs == 1) "folder" else "folders"}"
        }
    }

    /** A folder's place in the tree, for pickers: "Media › Video". */
    private fun folderPathLabel(folder: Folder): String =
        FolderTree.breadcrumb(layout.folders, folder.id).joinToString(" › ") { it.name }

    private fun buildContinueSection(): View? {
        // v2.5: the cached list — the blocking provider query
        // runs only via explicit Refresh home data (v3.2: the
        // old onResume kick is gone).
        val items = TvData.watchNextCached()
        DiagCache.watchNextCount = items.size
        if (items.isEmpty()) return null
        val box = section("Continue Watching")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (item in items) row.addView(watchTile(item))
        box.addView(horizontalScroller(row))
        return box
    }

    // ---------------------------------------------------------------
    // v1.8 Tracker home style — a Netflix-style view of the user's
    // shows & movies from TVShowsMoviesTracker (data/TrackerClient,
    // core/TrackerLogic; contract in the tracker repo's
    // LAUNCHER_SYNC.md). Composition: the TV status bar, the hero
    // panel (or the no-data fallback), Up Next, merged Continue
    // Watching, then Favorites + All Apps EXACTLY as
    // the TV view builds them. Recently Used / Now Playing rows are
    // NOT part of this view. Everything fail-open: no tracker data
    // simply means no hero and no tracker rows.
    // ---------------------------------------------------------------

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * The v2.3 Watch hint row: one line, OK dismisses it for this
     * profile. Focusable like the Library entry row; shown only
     * while NO IPTV source is configured (spec §21.5).
     */
    private fun buildWatchHintRow(): View {
        val row = TextView(this)
        row.text = "📺  Add your IPTV account in Settings › IPTV and titles here play in place — OK dismisses this hint"
        row.setTextColor(0xFFFFFFFF.toInt())
        row.textSize = 17f
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 60), Ui.dp(this, 12))
        row.layoutParams = lp
        row.background = Ui.tileBackground(
            accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this
        )
        Ui.focusLift(row, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
        row.setOnClickListener {
            prefs.watchHintDismissed = true
            rebuild()
        }
        return row
    }

    private fun buildTrackerHome(content: LinearLayout, barHost: LinearLayout) {
        // The working set was computed in buildRoot (prepareTrackerData)
        // because the root background IS the hero backdrop.
        heroTextHost = null
        heroButtonViews = mutableListOf()
        val hero = TrackerLogic.selectHero(tHeroCandidates, prefs.trackerHeroIndex)
        // v1.9: the status bar goes to the sticky header when sticky
        // is on (barHost == content otherwise). v2.0: in this view the
        // bar renders transparent over the hero backdrop. v2.9
        // (ask C.13): the status bar and the tab bar are ONE
        // unified pill bar now (buildUnifiedBar).
        barHost.addView(buildUnifiedBar(overHero = hero != null))

        // v3.2 (ask 43): NO refresh on open/resume any more — the
        // old unconditional call re-fetched the provider on
        // nearly every open (the 60 s throttle lapses between
        // opens). The only automatic fetch left is first-run
        // seeding: nothing stored yet, so there is no cache to
        // render. Every later update is explicit (Refresh home
        // data, or a section Retry) or a fetch landing from
        // another screen (Library/Details), which warms the memo.
        if (!TrackerClient.hasStoredData(this)) {
            TrackerClient.refreshAsync(this) { if (isTrackerFamily()) rebuild() }
        }
        if (isWatchStyle()) {
            // Watch (v2.3): the IPTV matches ride along — fresh
            // badges land via data/WatchPlay.onMatchesChanged (set
            // in prepareWatchData). v2.4: NO catalogue fetch here —
            // data loads on request only (Settings › IPTV / the
            // browser's Refresh); the store on disk is what shows.
            // Hint row (§21.5): no IPTV source at all → the Watch
            // home renders EXACTLY as Tracker, plus ONE dismissible
            // hint. A configured-but-empty/failed catalogue gets NO
            // hint — the `iptv:` diagnostics line covers it.
            // v2.8: anyConfigured reads the IPTV store — skipped on
            // the cold shell; the hint (if it applies) joins at the
            // warm rebuild like everything store-backed.
            if (!coldStart && !IptvClient.anyConfigured(this) && !prefs.watchHintDismissed) {
                content.addView(buildWatchHintRow())
            }
        }

        // The merged Continue cards decide that section's availability
        // (and are then built from, below) — system cards included.
        val system = TvData.watchNextCached()
        DiagCache.watchNextCount = system.size
        val continueCards = TrackerLogic.mergeContinue(tCont, system.map { it.title })

        // v2.0 section controls (core/TrackerSections): compose the
        // profile's order, skipping hidden sections AND data-empty
        // ones — an empty section never renders a bare header. For You
        // additionally needs tracker ≥ 1.14.4: no picks, no section.
        // v3.3 (ask 47): Up Next + Coming Soon are ONE section.
        // The merge uses the hero's date-filled Up Next list (the
        // same conservative TMDB fill), so tiering and dedupe see
        // the best-known dates and coordinates.
        val mergedUpNext = TrackerLogic.mergeUpNext(
            TrackerLogic.fillHeroDates(tUpNext, DiscoveryData.tvFacts()),
            dSoon, todayIso()
        )
        val sectionsCfg = prefs.trackerSectionsConfig()
        val available = mapOf(
            TrackerSections.HERO to (hero != null),
            TrackerSections.UPNEXT to mergedUpNext.isNotEmpty(),
            // v2.7 focus rows: Trending rides the discovery state
            // (empty until the worker first lands).
            TrackerSections.CONTINUE to continueCards.isNotEmpty(),
            TrackerSections.TRENDINGTV to dTrendTv.isNotEmpty(),
            TrackerSections.TRENDINGMOVIES to dTrendMovies.isNotEmpty(),
            TrackerSections.FORYOU to tPicks.isNotEmpty(),
            // v2.8: on the cold shell the app list has not landed
            // yet — the two app-backed sections join at the warm
            // rebuild instead of flashing their empty-state hints.
            TrackerSections.FAVORITES to (!coldStart && layout.isRowVisible("favorites")),
            TrackerSections.ALLAPPS to (!coldStart && layout.isRowVisible("apps"))
        )
        val order = TrackerSections.compose(sectionsCfg) { available[it] == true }
        // v3.1 (ask 39): the render list is compose() PLUS loading
        // placeholders at their configured position for SHOWN
        // sections whose source worker is still running. Empty
        // sections stay skipped (no bare headers); the order-empty
        // fallback below still reads compose()'s output alone.
        // On the cold shell the single "Loading your library…"
        // line covers every section, so placeholders start at the
        // warm rebuild (coldStart suppresses them).
        val renderKeys = ArrayList<String>()
        if (TrackerSections.isShown(sectionsCfg, TrackerSections.HERO) && hero != null) {
            renderKeys.add(TrackerSections.HERO)
        }
        for (key in sectionsCfg.order) {
            if (!TrackerSections.isShown(sectionsCfg, key)) continue
            if (available[key] == true || (!coldStart && sectionLoading(key, continueCards))) {
                renderKeys.add(key)
            }
        }

        if (order.isEmpty()) {
            if (coldStart) {
                // v2.8 staged shell: no tracker status read at all
                // (it parses the snapshots) — just the loading line.
                content.addView(trackerInfoLine("Loading your library…"))
            } else if (!tSnap.hasData) {
                // v1.9: the no-data line is worded by the CLASSIFIED
                // fetch state (core/TrackerStatus) — v1.8 showed one
                // generic line for every cause, and the permission-
                // blocked state (the prime suspect for an empty view)
                // was indistinguishable from "no data yet". Not
                // installed → no line at all, as before.
                val tStatus = TrackerClient.status(this)
                val lineText = com.mizan.launcher.core.TrackerStatus.emptyLine(
                    tStatus.kind,
                    tStatus.installed || appsByPkg.containsKey(TrackerClient.TRACKER_PKG)
                )
                if (lineText != null) content.addView(trackerInfoLine(lineText))
            } else {
                content.addView(
                    trackerInfoLine(
                        "Nothing to show here right now — sections appear when " +
                            "they have something in them, and hidden sections come " +
                            "back in Settings › Look & wallpaper › Tracker sections."
                    )
                )
            }
        }

        // v2.1: with no hero to carry the My Library button (hero
        // hidden in section controls, or no candidates), the entry
        // renders as its own row whenever the snapshots hold any
        // library data at all.
        if (hero == null && tSnap.hasData) {
            content.addView(buildLibraryEntryRow())
        }

        for (key in renderKeys) {
            // v3.1: a loading section renders its placeholder and
            // nothing else — it never delays its neighbours.
            if (sectionLoading(key, continueCards)) {
                content.addView(sectionLoadingTile(trackerTileLabel(key)))
                continue
            }
            try {
                when (key) {
                    TrackerSections.HERO ->
                        hero?.let { content.addView(buildHeroZone(it)) }
                    TrackerSections.UPNEXT ->
                        content.addView(buildMergedUpNextSection(mergedUpNext))
                    TrackerSections.CONTINUE ->
                        buildTrackerContinueSection(continueCards, system)
                            ?.let { content.addView(it) }
                    TrackerSections.TRENDINGTV ->
                        content.addView(buildTrendingSection(dTrendTv, "tv"))
                    TrackerSections.TRENDINGMOVIES ->
                        content.addView(buildTrendingSection(dTrendMovies, "movie"))
                    TrackerSections.FORYOU ->
                        content.addView(buildForYouSection(tPicks))
                    TrackerSections.FAVORITES ->
                        content.addView(buildFavoritesSection())
                    TrackerSections.ALLAPPS ->
                        content.addView(buildAppsSection())
                }
            } catch (e: Throwable) {
                // v3.0 (core/SectionGuard): was Exception + silent
                // drop — the section now renders its error tile.
                // v3.1 (ask 39): the tile carries its own Retry,
                // which re-runs ONLY this section's loader.
                noteSectionFailure("row:tracker:$key", e)
                content.addView(
                    sectionErrorTile(trackerTileLabel(key)) { retrySection(key) }
                )
            }
        }
        startHeroRotation()
    }

    /** v2.1: the standalone My Library entry row (no-hero case). */
    private fun buildLibraryEntryRow(): View {
        val row = TextView(this)
        row.text = "▦  My Library — all your shows & movies"
        row.setTextColor(0xFFFFFFFF.toInt())
        row.textSize = 17f
        row.setTypeface(row.typeface, android.graphics.Typeface.BOLD)
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 60), Ui.dp(this, 12))
        row.layoutParams = lp
        row.background = Ui.tileBackground(
            accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this
        )
        Ui.focusLift(row, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
        row.setOnClickListener {
            startActivity(Intent(this, com.mizan.launcher.ui.LibraryActivity::class.java))
        }
        return row
    }

    /** A small informational line in the Tracker view (fallback states). */
    private fun trackerInfoLine(text: String): TextView {
        val line = TextView(this)
        line.text = text
        line.setTextColor(subColour)
        line.textSize = 16f
        val llp = LinearLayout.LayoutParams(-1, -2)
        llp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 60), Ui.dp(this, 10))
        line.layoutParams = llp
        return line
    }

    /**
     * The hero zone (v2.0 Netflix layout): a tall transparent block in
     * the scroll content whose lower-left text sits over the root's
     * full-bleed backdrop. The text lives in [heroTextHost] so
     * rotation can rebuild JUST the block while the backdrop
     * crossfades underneath. First focus is NOT here — it lands on
     * the first row card (the baseTile/posterCard mechanism), as in
     * v1.8.
     */
    private fun buildHeroZone(hero: TrackerLogic.HeroItem): View {
        val zone = LinearLayout(this)
        zone.orientation = LinearLayout.VERTICAL
        val dm = resources.displayMetrics
        val zonePx = (dm.heightPixels * com.mizan.launcher.core.Design.HERO_ZONE_FRACTION).toInt()
        val zlp = LinearLayout.LayoutParams(-1, zonePx)
        zlp.setMargins(0, 0, 0, Ui.dp(this, com.mizan.launcher.core.Design.SPACE_S))
        zone.layoutParams = zlp
        zone.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val host = LinearLayout(this)
        host.orientation = LinearLayout.VERTICAL
        host.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 120), Ui.dp(this, 6))
        zone.addView(host)
        heroTextHost = host
        fillHeroText(host, hero)
        return zone
    }

    /** (Re)fill the hero text block for [hero] — rotation's text half. */
    private fun fillHeroText(host: LinearLayout, hero: TrackerLogic.HeroItem) {
        heroButtonViews = mutableListOf()
        // v2.1: the source label — which section this hero was
        // pulled from ("UP NEXT" / "CONTINUE WATCHING" / "MOVIE
        // NIGHT" / "FOR YOU"), small + letter-spaced over the
        // backdrop, above the title. Rebuilt with the text block, so
        // it follows rotation and › Next automatically. (The
        // no-data clock/weather fallback has no hero and no label.)
        val sourceLabel = TextView(this)
        sourceLabel.text = hero.source.uppercase()
        sourceLabel.setTextColor(0xB3FFFFFF.toInt())
        sourceLabel.textSize = 12f
        sourceLabel.setTypeface(sourceLabel.typeface, android.graphics.Typeface.BOLD)
        sourceLabel.letterSpacing = 0.18f
        sourceLabel.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
        val slp = LinearLayout.LayoutParams(-2, -2)
        slp.setMargins(0, 0, 0, Ui.dp(this, 6))
        sourceLabel.layoutParams = slp
        host.addView(sourceLabel)

        // Red NEW badge when the featured item airs today.
        if (hero.dateIso.isNotBlank() && hero.dateIso.trim() == todayIso()) {
            val badge = TextView(this)
            badge.text = "NEW"
            badge.setTextColor(0xFFFFFFFF.toInt())
            badge.textSize = com.mizan.launcher.core.Design.NETFLIX_BADGE_SP
            badge.setTypeface(badge.typeface, android.graphics.Typeface.BOLD)
            badge.letterSpacing = 0.12f
            badge.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3))
            val badgeBg = GradientDrawable()
            badgeBg.cornerRadius = Ui.dp(this, 4).toFloat()
            badgeBg.setColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            badge.background = badgeBg
            val blp = LinearLayout.LayoutParams(-2, -2)
            blp.setMargins(0, 0, 0, Ui.dp(this, 8))
            badge.layoutParams = blp
            host.addView(badge)
        }

        val title = TextView(this)
        title.text = hero.title
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = com.mizan.launcher.core.Design.NETFLIX_HERO_TITLE_SP
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setShadowLayer(8f, 0f, 3f, 0xCC000000.toInt())
        host.addView(title)

        val metaParts = ArrayList<String>()
        if (hero.sub.isNotBlank()) metaParts.add(hero.sub)
        var countdown = TrackerLogic.countdownLabel(hero.dateIso, todayIso())
        if (countdown.isNotEmpty()) metaParts.add(countdown)
        if (metaParts.isNotEmpty()) {
            val meta = TextView(this)
            meta.text = metaParts.joinToString("  ·  ")
            meta.setTextColor(0xFFDDDDDD.toInt())
            meta.textSize = 17f
            meta.maxLines = 1
            meta.ellipsize = android.text.TextUtils.TruncateAt.END
            meta.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            host.addView(meta)
        }

        if (hero.overview.isNotBlank()) {
            val overview = TextView(this)
            overview.text = hero.overview
            overview.setTextColor(0xFFDDDDDD.toInt())
            overview.textSize = com.mizan.launcher.core.Design.NETFLIX_HERO_OVERVIEW_SP
            overview.maxLines = 3
            overview.ellipsize = android.text.TextUtils.TruncateAt.END
            overview.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            val olp = LinearLayout.LayoutParams(-1, -2)
            olp.setMargins(0, Ui.dp(this, 5), 0, 0)
            overview.layoutParams = olp
            host.addView(overview)
        }

        val pillText = TrackerLogic.pillText(
            TrackerLogic.airingTodayCount(tUpNext, todayIso()), tUpNext.size
        )
        if (pillText.isNotEmpty()) {
            val pill = TextView(this)
            pill.text = "📺  $pillText"
            pill.setTextColor(0xFFFFFFFF.toInt())
            pill.textSize = 13f
            pill.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4))
            pill.background = Ui.tileBackground(
                accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this
            )
            val pilp = LinearLayout.LayoutParams(-2, -2)
            pilp.setMargins(0, Ui.dp(this, 10), 0, 0)
            pill.layoutParams = pilp
            host.addView(pill)
        }

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val btnLp = LinearLayout.LayoutParams(-2, -2)
        btnLp.setMargins(0, Ui.dp(this, 12), 0, 0)
        buttons.layoutParams = btnLp
        val trackerHere = TrackerClient.isInstalled(this) ||
            appsByPkg.containsKey(TrackerClient.TRACKER_PKG)
        if (hero.source == TrackerLogic.SOURCE_FOR_YOU) {
            // v2.1 For You hero: a pick has nothing to mark watched —
            // primary opens its in-launcher details, secondary adds
            // it to the list (queued + overlays, like the row's menu).
            // v2.3 Watch: a matched MOVIE pick gains ▶ Play as the
            // primary (Details drops to secondary); a matched show
            // pick keeps Details/Add — a pick carries no episode
            // coordinates to play.
            val fyMovie = if (isWatchStyle() && hero.type == "movie") {
                // v3.1: ad-hoc MovieItem (no tMovies list anymore).
                TrackerLogic.MovieItem(
                    hero.tmdbId, hero.title, hero.dateIso, false, hero.poster, false
                )
            } else {
                null
            }
            // v2.4: gated on the match alone — the store entry is
            // resolved on the worker thread at tap time, and a
            // miss falls back to Details inside playMovieItem.
            val fyPlayable = fyMovie != null &&
                watchMatch("movie", hero.tmdbId) != null
            if (fyPlayable && fyMovie != null) {
                buttons.addView(
                    heroButton("▶ Play", "play", primary = true) { playMovieItem(fyMovie) }
                )
            }
            buttons.addView(
                heroButton("ⓘ Details", "details", primary = !fyPlayable) {
                    openTitleDetails(hero.tmdbId, hero.type, hero.title, hero.poster)
                }
            )
            buttons.addView(
                heroButton("＋ Add to my list", "add", primary = false) {
                    addForYouHero(hero)
                }
            )
            buttons.addView(
                heroButton("▦ My Library", "library", primary = false) {
                    startActivity(
                        Intent(this, com.mizan.launcher.ui.LibraryActivity::class.java)
                    )
                }
            )
        } else if (trackerHere) {
            // Mark watched needs episode coordinates: Up Next heroes
            // carry their own; a Continue hero borrows them from the
            // same show's Up Next item; a movie hero uses the
            // movie path (0, 0). No coordinates → no button.
            val coords: Pair<Int, Int>? = when {
                hero.type == "movie" -> 0 to 0
                !hero.fromContinue -> hero.season to hero.episode
                else -> tSnap.upNext.firstOrNull { it.tmdbId == hero.tmdbId }
                    ?.let { it.season to it.episode }
            }
            // v2.9 (ask F.25): the hero primary is decided by the
            // MATCH alone — core/WatchMatch.heroPrimary — never by
            // whether a resolution is already cached. (v2.3–v2.8
            // gated ▶ Play on the episode being present in the
            // series-info cache at build time; the warm-up that
            // filled that cache stopped at 6 Up Next shows, so a
            // matched hero past the cap — Haythem's photo,
            // 2026-10-23 — rendered Details as its primary and NO
            // Play button at all.) A matched hero leads with
            // ▶ Play and resolves ON DEMAND at press time
            // (playHeroPrimary, with the visible "Finding stream…"
            // state); an unmatched hero leads with Details.
            val heroCoords = coords ?: (0 to 0)
            val heroMatched = isWatchStyle() && coords != null &&
                watchMatch(hero.type, hero.tmdbId) != null
            val heroPrimary = WatchMatch.heroPrimary(
                heroMatched, hero.type, heroCoords.first, heroCoords.second
            )
            if (heroPrimary.action == WatchMatch.PressAction.PLAY) {
                lateinit var playBtn: TextView
                playBtn = heroButton(heroPrimary.label, "play", primary = true) {
                    playHeroPrimary(hero, heroCoords, playBtn, heroPrimary.label)
                }
                buttons.addView(playBtn)
            } else {
                buttons.addView(
                    heroButton(heroPrimary.label, "details", primary = true) {
                        openTitleDetails(hero.tmdbId, hero.type, hero.title, hero.poster)
                    }
                )
            }
            if (coords != null) {
                buttons.addView(
                    heroButton("✓ Mark watched", "mark", primary = false) {
                        markWatched(hero.tmdbId, hero.type, coords.first, coords.second)
                    }
                )
            }
            // v2.9: NO "Open Tracker" hero button at all (ask
            // F.25 — never a hero action; the tracker app remains
            // reachable from the hold-OK card menus and its own
            // launcher icon; Sreeb's hero carries Sreeb's actions).
            // v2.1: the My Library entry — the whole tracked library
            // (details, episode checklists, add/remove) now lives in
            // the launcher; the tracker app is only for its Settings.
            buttons.addView(
                heroButton("▦ My Library", "library", primary = false) {
                    startActivity(
                        Intent(this, com.mizan.launcher.ui.LibraryActivity::class.java)
                    )
                }
            )
        }
        if (tHeroCandidates.size > 1) {
            buttons.addView(
                heroButton("› Next", "next", primary = false) { advanceHeroFromNext() }
            )
        }
        if (buttons.childCount > 0) host.addView(buttons)
    }

    /**
     * One Netflix hero button: white-filled/dark-text primary,
     * translucent secondary; focus gets a ring + a slight scale, and
     * reports into the rotator's pause/resume (rotation holds while
     * ANY hero button has focus).
     */
    private fun heroButton(
        text: String,
        role: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.tag = role
        tv.setTextColor(
            if (primary) {
                com.mizan.launcher.core.Design.NETFLIX_BUTTON_TEXT
            } else {
                0xFFFFFFFF.toInt()
            }
        )
        tv.textSize = 16f
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 10), 0)
        tv.layoutParams = lp
        fun bg(focused: Boolean): GradientDrawable {
            val d = GradientDrawable()
            // v2.8: a full PILL (the Google TV hero buttons in the
            // reference photos) — the radius exceeds any button
            // height, so the ends are always semicircles.
            d.cornerRadius = Ui.dp(this, 100).toFloat()
            d.setColor(
                if (primary) {
                    com.mizan.launcher.core.Design.NETFLIX_BUTTON_FILL
                } else {
                    com.mizan.launcher.core.Design.NETFLIX_BUTTON2_FILL
                }
            )
            if (focused) {
                d.setStroke(
                    Ui.dp(this, 3),
                    if (primary) {
                        com.mizan.launcher.core.Design.NETFLIX_RED
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                )
            }
            return d
        }
        tv.background = bg(false)
        tv.setOnFocusChangeListener { _, hasFocus ->
            tv.background = bg(hasFocus)
            val s = if (hasFocus) 1.05f else 1f
            tv.scaleX = s
            tv.scaleY = s
            if (hasFocus) {
                heroFocusRole = role
                handler.removeCallbacks(heroResumeCheck)
                heroRotator = heroRotator?.let { HeroRotator.focusGained(it) }
            } else {
                handler.removeCallbacks(heroResumeCheck)
                handler.postDelayed(heroResumeCheck, 200)
            }
        }
        tv.setOnClickListener { onClick() }
        heroButtonViews.add(tv)
        return tv
    }

    /** Arm the rotation driver for this build (halts itself in safe paths). */
    private fun startHeroRotation() {
        heroRotator = HeroRotator.initial(
            prefs.trackerHeroIndex, tHeroCandidates.size, System.currentTimeMillis()
        )
        heroTicking = HeroRotator.canRotate(heroRotator!!) && !prefs.safeMode
        handler.removeCallbacks(heroTick)
        if (heroTicking) handler.postDelayed(heroTick, 1_000)
    }

    private fun stopHeroRotation() {
        heroTicking = false
        handler.removeCallbacks(heroTick)
        handler.removeCallbacks(heroResumeCheck)
    }

    /** The Next button: advance immediately; the rotator resets its timer. */
    private fun advanceHeroFromNext() {
        val state = heroRotator ?: return
        val next = HeroRotator.next(state, System.currentTimeMillis())
        heroRotator = next
        applyHero(next.index)
    }

    /**
     * Show hero [index]: persist the cycle position, crossfade the
     * root backdrop layers (~600 ms), rebuild the text block, and put
     * focus back on the same-role button when it was in the hero.
     */
    private fun applyHero(index: Int) {
        val hero = TrackerLogic.selectHero(tHeroCandidates, index) ?: return
        prefs.trackerHeroIndex = index
        val front = heroLayerFront
        val back = heroLayerBack
        if (front != null && back != null) {
            if (hero.backdrop.isBlank()) {
                front.animate().alpha(0f).setDuration(600).start()
                heroBackdropShown = ""
            } else if (hero.backdrop != heroBackdropShown) {
                val url = TmdbImages.url("w1280", hero.backdrop)
                back.alpha = 0f
                TmdbImages.load(url, back, TmdbImages.HERO_MAX_DIM) {
                    back.animate().alpha(1f).setDuration(600).start()
                    front.animate().alpha(0f).setDuration(600).start()
                    heroLayerFront = back
                    heroLayerBack = front
                    heroBackdropShown = hero.backdrop
                }
            }
        }
        val host = heroTextHost ?: return
        host.removeAllViews()
        fillHeroText(host, hero)
        heroFocusRole?.let { role ->
            heroButtonViews.firstOrNull { it.tag == role }?.post {
                heroButtonViews.firstOrNull { it.tag == role }?.requestFocus()
            }
        }
    }

    /** Open the tracker app through the shared arrow-lock gate. */
    private fun openTracker() {
        val label = appsByPkg[TrackerClient.TRACKER_PKG]?.label ?: TrackerClient.TRACKER_LABEL
        gateLockedApp(TrackerClient.TRACKER_PKG, label) {
            try {
                val intent = AppLoader.launchIntent(packageManager, TrackerClient.TRACKER_PKG)
                if (intent == null) {
                    Toast.makeText(
                        this, "TV Shows & Movies Tracker isn't installed",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@gateLockedApp
                }
                startActivity(intent)
                prefs.recordLaunch(TrackerClient.TRACKER_PKG)
            } catch (e: Exception) {
                Prefs.noteError(this, "openTracker", e)
                Toast.makeText(this, "Couldn't open the tracker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * "Mark watched" from any tracker surface: queue the action with
     * the tracker AND hide the item locally (marked-set) right away.
     * The tracker's own data converges at its next boot + refresh,
     * and pruneMarked then retires the local entry.
     */
    private fun markWatched(tmdbId: Long, type: String, season: Int, episode: Int) {
        val entry = TrackerLogic.MarkedEntry(
            tmdbId, if (type == "movie") "movie" else "tv",
            season, episode, System.currentTimeMillis()
        )
        val current = TrackerLogic.decodeMarked(prefs.trackerMarked)
        prefs.trackerMarked = TrackerLogic.encodeMarked(
            TrackerLogic.withMarked(current, entry)
        )
        TrackerClient.markWatched(this, tmdbId, entry.type, season, episode)
        Toast.makeText(
            this, "Marked watched — applies next time the tracker opens",
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    /**
     * Hold-OK menu for a tracker card: Details (v2.1, in-launcher) /
     * Open Tracker (+ Mark watched when offered).
     */
    /**
     * v3.1 (ask 40): [onPlayIn] appends a "Play in…" entry LAST
     * (existing positions never move). Only call sites with a
     * playable IPTV match pass it — unmatched cards get no entry,
     * because Sreeb has no stream to hand over for them.
     */
    private fun openTrackerCardMenu(
        title: String,
        onMark: (() -> Unit)?,
        onDetails: (() -> Unit)? = null,
        onPlayIn: (() -> Unit)? = null
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (onDetails != null) {
            labels.add("Details")
            actions.add(onDetails)
        }
        labels.add("Open Tracker")
        actions.add { openTracker() }
        if (onMark != null) {
            labels.add("Mark watched")
            actions.add(onMark)
        }
        if (onPlayIn != null) {
            labels.add("Play in…")
            actions.add(onPlayIn)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    /** v2.1: open the in-launcher details screen for a tracker title. */
    private fun openTitleDetails(tmdbId: Long, type: String, title: String, poster: String) {
        startActivity(TrackerActions.detailsIntent(this, tmdbId, type, title, poster))
    }

    /**
     * A portrait poster card (v2.0, the "posters are not full" fix):
     * a dedicated 2:3 card (core/TileLayout.posterMetrics) whose
     * poster fills it EDGE-TO-EDGE — CENTER_CROP, no padding, no
     * letterbox, rounded corners — with the title and meta BELOW the
     * card, never overlaid on the art. Focus lifts the poster frame
     * (Netflix-style scale + ring via Ui.focusLift).
     */
    private fun portraitCard(
        posterPath: String,
        title: String,
        meta: String,
        line2: String? = null,
        line3: String? = null,
        glyph: String,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null,
        badge: String? = null
    ): View {
        val dm = resources.displayMetrics
        val pm = TileLayout.posterMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(pm.tileWidthPx, -2)
        val margin = Ui.dp(this, 5)
        colLp.setMargins(margin, margin, margin, margin)
        col.layoutParams = colLp

        val frame = FrameLayout(this)
        frame.isFocusable = true
        frame.isClickable = true
        frame.layoutParams = LinearLayout.LayoutParams(pm.tileWidthPx, pm.tileHeightPx)
        frame.background = Ui.tileBackground(accent, false, 16, this)
        if (firstTile == null) firstTile = frame
        if (posterPath.isNotBlank()) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Ui.roundClip(iv, 16, this)
            frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
            TmdbImages.load(TmdbImages.url("w342", posterPath), iv)
        } else {
            val g = TextView(this)
            g.text = glyph
            g.setTextColor(textColour)
            g.textSize = 44f
            g.gravity = Gravity.CENTER
            frame.addView(g, FrameLayout.LayoutParams(-1, -1))
        }
        if (badge != null) {
            // v2.3 Watch: the "▶ IPTV" chip on a matched title —
            // poster corner, Netflix red, never focusable.
            val chip = TextView(this)
            chip.text = badge
            chip.setTextColor(0xFFFFFFFF.toInt())
            chip.textSize = 11f
            chip.setTypeface(chip.typeface, android.graphics.Typeface.BOLD)
            val chipBg = GradientDrawable()
            chipBg.cornerRadius = Ui.dp(this, 6).toFloat()
            chipBg.setColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            chip.background = chipBg
            chip.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3))
            val chipLp = FrameLayout.LayoutParams(-2, -2)
            chipLp.gravity = Gravity.TOP or Gravity.START
            chipLp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), 0, 0)
            frame.addView(chip, chipLp)
        }
        // v2.8: the Google-TV focus treatment — a white border on
        // the focused card (over the Ui.focusLift scale), the same
        // in every media style.
        val focusBorder = gtvBorderOverlay(16)
        frame.addView(focusBorder)
        Ui.focusLift(frame, accent, 16) { hasFocus ->
            focusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
        frame.setOnClickListener { onClick() }
        if (onLong != null) frame.setOnLongClickListener { onLong(); true }
        col.addView(frame)

        val t = TextView(this)
        t.text = title
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 15f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.maxLines = 2
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.setMargins(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), 0)
        t.layoutParams = tlp
        col.addView(t)
        if (meta.isNotBlank()) {
            val s = TextView(this)
            s.text = meta
            s.setTextColor(0xFFB3B3B3.toInt())
            s.textSize = 13f
            s.maxLines = 1
            s.ellipsize = android.text.TextUtils.TruncateAt.END
            val slp = LinearLayout.LayoutParams(-1, -2)
            slp.setMargins(Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2), 0)
            s.layoutParams = slp
            col.addView(s)
        }
        for (extra in listOfNotNull(line2, line3)) {
            if (extra.isBlank()) continue
            val s = TextView(this)
            s.text = extra
            s.setTextColor(0xFF8C8C8C.toInt())
            s.textSize = 12f
            s.maxLines = 1
            s.ellipsize = android.text.TextUtils.TruncateAt.END
            val slp = LinearLayout.LayoutParams(-1, -2)
            slp.setMargins(Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2), 0)
            s.layoutParams = slp
            col.addView(s)
        }
        return col
    }

    /**
     * A landscape 16:9 card (v2.8, the Google-TV language of the
     * reference photos' "Top free picks" rail): landscape art
     * (backdrop/still) fills the frame edge-to-edge; a portrait
     * poster — [imageLandscape] false — renders COMPLETE over a
     * blurred fill of itself instead (v2.9, ask C.14; the scale
     * decision is core/Artwork.landscapeFit). An optional pill chip rides
     * inside the frame's lower-left (a date, "S1 E2", "% watched"),
     * an optional progress bar runs along the frame's bottom edge,
     * and title + meta read below — portraitCard's contract in
     * landscape form, same focus treatment (white border + lift).
     * [progress] < 0 means no bar; [chip] null means no chip.
     */
    private fun landscapeCard(
        imagePath: String,
        imageSize: String,
        imageLandscape: Boolean,
        title: String,
        meta: String,
        chip: String? = null,
        progress: Float = -1f,
        glyph: String,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null,
        badge: String? = null
    ): View {
        val dm = resources.displayMetrics
        val cm = TileLayout.cardMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        val w = cm.tileWidthPx
        val h = w * 9 / 16
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(w, -2)
        val margin = Ui.dp(this, 5)
        colLp.setMargins(margin, margin, margin, margin)
        col.layoutParams = colLp

        val frame = FrameLayout(this)
        frame.isFocusable = true
        frame.isClickable = true
        frame.layoutParams = LinearLayout.LayoutParams(w, h)
        frame.background = Ui.tileBackground(accent, false, 16, this)
        if (firstTile == null) firstTile = frame
        if (imagePath.isNotBlank()) {
            val imgUrl = TmdbImages.url(imageSize, imagePath)
            if (com.mizan.launcher.core.Artwork.landscapeFit(imageLandscape) ==
                com.mizan.launcher.core.Artwork.Fit.FIT_COMPLETE
            ) {
                // v2.9 (ask C.14): only a PORTRAIT poster exists
                // for this title — it renders COMPLETE, never
                // centre-cropped into 16:9 (his 2026-10-23 photo:
                // posters cut off in the landscape cards). The
                // fill is the same image decoded tiny (maxDim 64)
                // and upscaled edge-to-edge — a blurred version of
                // itself; the poster rides on top, fit whole.
                val fill = ImageView(this)
                fill.scaleType = ImageView.ScaleType.CENTER_CROP
                Ui.roundClip(fill, 16, this)
                frame.addView(fill, FrameLayout.LayoutParams(-1, -1))
                TmdbImages.load(imgUrl, fill, maxDim = 64)
                val fg = ImageView(this)
                fg.scaleType = ImageView.ScaleType.FIT_CENTER
                Ui.roundClip(fg, 16, this)
                frame.addView(fg, FrameLayout.LayoutParams(-1, -1))
                TmdbImages.load(imgUrl, fg)
            } else {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                Ui.roundClip(iv, 16, this)
                frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
                TmdbImages.load(imgUrl, iv)
            }
        } else {
            val g = TextView(this)
            g.text = glyph
            g.setTextColor(textColour)
            g.textSize = 44f
            g.gravity = Gravity.CENTER
            frame.addView(g, FrameLayout.LayoutParams(-1, -1))
        }
        if (badge != null) {
            val chipTv = TextView(this)
            chipTv.text = badge
            chipTv.setTextColor(0xFFFFFFFF.toInt())
            chipTv.textSize = 11f
            chipTv.setTypeface(chipTv.typeface, android.graphics.Typeface.BOLD)
            val chipBg = GradientDrawable()
            chipBg.cornerRadius = Ui.dp(this, 6).toFloat()
            chipBg.setColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            chipTv.background = chipBg
            chipTv.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3))
            val chipLp = FrameLayout.LayoutParams(-2, -2)
            chipLp.gravity = Gravity.TOP or Gravity.START
            chipLp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), 0, 0)
            frame.addView(chipTv, chipLp)
        }
        if (progress >= 0f) {
            val track = View(this)
            val trackBg = GradientDrawable()
            trackBg.setColor(0x66FFFFFF)
            track.background = trackBg
            val trackLp = FrameLayout.LayoutParams(-1, Ui.dp(this, 4))
            trackLp.gravity = Gravity.BOTTOM
            frame.addView(track, trackLp)
            val fillW = (w * progress.coerceIn(0f, 1f)).toInt().coerceAtLeast(Ui.dp(this, 2))
            val fill = View(this)
            val fillBg = GradientDrawable()
            fillBg.setColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            fill.background = fillBg
            val fillLp = FrameLayout.LayoutParams(fillW, Ui.dp(this, 4))
            fillLp.gravity = Gravity.BOTTOM or Gravity.START
            frame.addView(fill, fillLp)
        }
        if (chip != null) {
            val chipTv = TextView(this)
            chipTv.text = chip
            chipTv.setTextColor(0xFFFFFFFF.toInt())
            chipTv.textSize = 11f
            chipTv.setTypeface(chipTv.typeface, android.graphics.Typeface.BOLD)
            val chipBg = GradientDrawable()
            chipBg.cornerRadius = Ui.dp(this, 100).toFloat()
            chipBg.setColor(0xB3000000.toInt())
            chipTv.background = chipBg
            chipTv.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3))
            val chipLp = FrameLayout.LayoutParams(-2, -2)
            chipLp.gravity = Gravity.BOTTOM or Gravity.START
            chipLp.setMargins(
                Ui.dp(this, 8), 0, 0,
                if (progress >= 0f) Ui.dp(this, 10) else Ui.dp(this, 8)
            )
            frame.addView(chipTv, chipLp)
        }
        val focusBorder = gtvBorderOverlay(16)
        frame.addView(focusBorder)
        Ui.focusLift(frame, accent, 16) { hasFocus ->
            focusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
        frame.setOnClickListener { onClick() }
        if (onLong != null) frame.setOnLongClickListener { onLong(); true }
        col.addView(frame)

        val t = TextView(this)
        t.text = title
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 15f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.setMargins(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), 0)
        t.layoutParams = tlp
        col.addView(t)
        if (meta.isNotBlank()) {
            val s = TextView(this)
            s.text = meta
            s.setTextColor(0xFFB3B3B3.toInt())
            s.textSize = 13f
            s.maxLines = 1
            s.ellipsize = android.text.TextUtils.TruncateAt.END
            val slp = LinearLayout.LayoutParams(-1, -2)
            slp.setMargins(Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2), 0)
            s.layoutParams = slp
            col.addView(s)
        }
        return col
    }

    /**
     * One Up Next card (v3.3, ask 47): the merged section renders
     * Up Next-sourced items with exactly this card (matched play
     * badge + play, mark-watched menu, Play in). Extracted
     * unchanged from the old buildUpNextSection loop body.
     */
    private fun upNextCard(item: TrackerLogic.UpNextItem): View {
        val today = todayIso()
        val countdown = TrackerLogic.countdownLabel(item.dateIso, today)
        // v2.3 Watch: a matched card wears the ▶ IPTV badge and
        // OK plays the episode in place (failing open to details).
        // v2.9: an unmatched card opens the in-launcher Details
        // (it used to open the tracker app) — ask F.25.
        val wMatch = watchMatch("tv", item.tmdbId)
        // v2.8: Up Next rides the landscape rail (backdrop,
        // "Sx Ey" pill chip) — the Google-TV card language.
        val card = landscapeCard(
            imagePath = item.backdrop.ifBlank { item.poster },
            imageSize = if (item.backdrop.isNotBlank()) "w780" else "w342",
            imageLandscape = item.backdrop.isNotBlank(),
            title = item.title,
            meta = countdown.ifBlank { item.sub },
            chip = if (item.season > 0 && item.episode > 0) {
                "S${item.season} E${item.episode}"
            } else {
                null
            },
            glyph = "📺",
            onClick = {
                // v2.9 (ask F.25): matched plays; unmatched
                // opens the in-launcher Details — a card press
                // never lands in the tracker app.
                if (wMatch != null) playUpNextItem(item, inSnapshot = true)
                else openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
            },
            onLong = {
                openTrackerCardMenu(
                    item.title,
                    onMark = {
                        markWatched(item.tmdbId, item.type, item.season, item.episode)
                    },
                    onDetails = {
                        openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
                    },
                    // v3.1 (ask 40): matched shows only — the
                    // entry hands the episode stream over.
                    onPlayIn = if (wMatch != null) {
                        { playInEpisode(item) }
                    } else {
                        null
                    }
                )
            },
            badge = WatchMatch.cardBadge(wMatch != null)
        )
        return card
    }

    /**
     * The merged Up Next section (v3.3, ask 47): one Up Next
     * row holding the merged tiers (missed, watchable, future;
     * core TrackerLogic.mergeUpNext). Up Next-sourced items
     * render as Up Next cards; Soon-only items render as Soon
     * cards (details-only, as Coming Soon did).
     */
    private fun buildMergedUpNextSection(items: List<TrackerLogic.MergedUpNextItem>): View {
        val box = section("Up Next")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (item in items) {
            row.addView(if (item.upNext != null) upNextCard(item.upNext) else soonCard(item.soon!!))
        }
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * Continue Watching for the Tracker view: tracker cards merged
     * with the TvContract cards (core merge — tracker wins title
     * ties; the merge itself happens in buildTrackerHome, which also
     * needs it for section availability). Tracker cards are portrait
     * posters; system cards keep their 16:9 landscape card — full-bleed
     * thumbnail via [watchTile] — in the same row.
     */
    private fun buildTrackerContinueSection(
        cards: List<TrackerLogic.ContinueCard>,
        system: List<WatchItem>
    ): View? {
        if (cards.isEmpty()) return null
        val box = section("Continue Watching")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (card in cards) {
            val trackerItem = card.tracker
            if (trackerItem != null) {
                // v2.3 Watch: matched Continue cards play the same
                // episode the Up Next row targets (coordinates
                // borrowed from the snapshot item, as the hero does);
                // without coordinates they open details, as v2.1.
                val wMatch = watchMatch("tv", trackerItem.tmdbId)
                val upNextTwin = if (wMatch != null) {
                    tSnap.upNext.firstOrNull { it.tmdbId == trackerItem.tmdbId }
                } else {
                    null
                }
                // v2.8: Continue Watching rides the landscape rail
                // too — backdrop, "% watched" pill chip, and the
                // watched/total progress bar along the frame's
                // bottom edge. System (WatchNext) cards keep their
                // own tile, below.
                val progress = if (trackerItem.total > 0) {
                    trackerItem.watched.toFloat() / trackerItem.total.toFloat()
                } else {
                    -1f
                }
                val view = landscapeCard(
                    imagePath = trackerItem.backdrop.ifBlank { trackerItem.poster },
                    imageSize = if (trackerItem.backdrop.isNotBlank()) "w780" else "w342",
                    imageLandscape = trackerItem.backdrop.isNotBlank(),
                    title = trackerItem.title,
                    meta = trackerItem.sub,
                    chip = if (progress >= 0f) {
                        "${(progress * 100).toInt()}% watched"
                    } else {
                        null
                    },
                    progress = progress,
                    glyph = "📺",
                    onClick = {
                        // v2.9 (ask F.25): the last branch opened
                        // the tracker app in the Tracker style —
                        // now Details, like everywhere else.
                        when {
                            upNextTwin != null -> playUpNextItem(upNextTwin, inSnapshot = true)
                            else -> openTitleDetails(
                                trackerItem.tmdbId, trackerItem.type,
                                trackerItem.title, trackerItem.poster
                            )
                        }
                    },
                    onLong = {
                        openTrackerCardMenu(
                            trackerItem.title,
                            onMark = null,
                            onDetails = {
                                openTitleDetails(
                                    trackerItem.tmdbId, trackerItem.type,
                                    trackerItem.title, trackerItem.poster
                                )
                            }
                        )
                    },
                    badge = WatchMatch.cardBadge(wMatch != null)
                )
                row.addView(view)
            } else {
                row.addView(watchTile(system[card.systemIndex]))
            }
        }
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * For You (v2.0): the tracker's personalised picks (≥ 1.14.4),
     * already filtered in core (tracked + local overlay removed,
     * capped at 20). Portrait poster; below it the title, the ★
     * rating, the reason line and the genre-names line. OK opens the
     * tracker; hold-OK adds or dismisses (queued actions + the local
     * overlay hides the card at once).
     */
    private fun buildForYouSection(picks: List<TrackerLogic.ForYouPick>): View {
        val box = section("For You")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (pick in picks) {
            // v2.3 Watch: a matched pick also wears the badge.
            // v2.9 (ask F.25): OK used to open the TRACKER APP for
            // every pick — now a matched movie pick PLAYS, and
            // every other pick opens its in-launcher Details (a
            // show pick carries no episode coordinates to play).
            val wMatch = watchMatch(pick.type, pick.id)
            val card = portraitCard(
                posterPath = pick.poster,
                title = pick.title,
                meta = TrackerLogic.ratingText(pick.voteAverage) ?: "",
                line2 = pick.reason,
                line3 = pick.whyGenres.joinToString(" · ").ifBlank { null },
                glyph = if (pick.type == "movie") "🎬" else "📺",
                onClick = {
                    if (wMatch != null && pick.type == "movie") {
                        playMovieItem(
                            TrackerLogic.MovieItem(
                                pick.id, pick.title, "", false, pick.poster, false
                            )
                        )
                    } else {
                        openTitleDetails(pick.id, pick.type, pick.title, pick.poster)
                    }
                },
                onLong = { openForYouMenu(pick) },
                badge = WatchMatch.cardBadge(wMatch != null)
            )
            row.addView(card)
        }
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * One Soon card (v3.3, ask 47): Soon-only items inside the
     * merged Up Next section render with exactly this card
     * (poster complete over its blurred fill, date chip,
     * details-only). Extracted unchanged from the old
     * buildComingSoonSection loop body.
     */
    private fun soonCard(item: Discovery.SoonItem): View {
        val today = todayIso()
        val chip = Discovery.soonChip(item.dateIso, today)
        val meta = listOf(chip, item.sub).filter { it.isNotBlank() }
            .joinToString(" · ")
        // v2.8: Coming Soon rides the landscape rail and the
        // air/release date is the pill chip. v2.9 (ask C.14):
        // SoonItem carries NO backdrop — the poster is
        // portrait, so it renders COMPLETE over its blurred
        // fill (imageLandscape = false), never cropped.
        val card = landscapeCard(
            imagePath = item.poster,
            imageSize = "w500",
            imageLandscape = false,
            title = item.title,
            meta = meta,
            chip = Discovery.monthDay(item.dateIso).ifBlank { null },
            glyph = if (item.type == "movie") "🎬" else "📺",
            onClick = {
                openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
            },
            onLong = {
                openTrackerCardMenu(
                    item.title,
                    onMark = null,
                    onDetails = {
                        openTitleDetails(item.tmdbId, item.type, item.title, item.poster)
                    }
                )
            }
        )
        return card
    }

    /**
     * Trending TV / Trending Movies (v2.7): TMDB's week charts,
     * fetched + shaped in the launcher. Full poster cards with the
     * ★ rating; in the Watch style a matched title wears the
     * ▶ IPTV badge (the matcher's verdict, as everywhere); a title
     * already in the library reads "In library" and is not offered
     * for add. OK opens details; hold-OK adds / dismisses (the
     * For You writes, shared with [addToMyList] / [dismissDiscovery]).
     */
    private fun buildTrendingSection(items: List<Discovery.TrendItem>, type: String): View {
        val box = section(if (type == "movie") "Trending Movies" else "Trending TV")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        val overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
        val addedKeys = overlay.filter { it.added }.map { it.key() }.toHashSet()
        val trackedKeys = HashSet<String>()
        for (s in tSnap.tracked) trackedKeys.add("tv|${s.tmdbId}")
        for (m in tSnap.movies) trackedKeys.add("movie|${m.tmdbId}")
        for (item in items) {
            val inLibrary = item.key() in trackedKeys || item.key() in addedKeys
            val wMatch = watchMatch(item.type, item.id)
            val card = portraitCard(
                posterPath = item.poster,
                title = item.title,
                meta = TrackerLogic.ratingText(item.voteAverage) ?: item.year,
                line2 = if (inLibrary) "In library" else null,
                glyph = if (item.type == "movie") "🎬" else "📺",
                onClick = {
                    // v2.9 (ask F.25): a trending title that is
                    // also tracked + matched plays like its row
                    // twins (movie via its VOD entry; a show via
                    // its Up Next twin); everything else opens
                    // Details, as before.
                    val twin = if (wMatch != null && item.type == "tv") {
                        tSnap.upNext.firstOrNull { it.tmdbId == item.id }
                    } else {
                        null
                    }
                    when {
                        wMatch != null && item.type == "movie" -> playMovieItem(
                            TrackerLogic.MovieItem(
                                item.id, item.title, "", false, item.poster, false
                            )
                        )
                        twin != null -> playUpNextItem(twin, inSnapshot = true)
                        else -> openTitleDetails(item.id, item.type, item.title, item.poster)
                    }
                },
                onLong = { openTrendingMenu(item, inLibrary) },
                badge = WatchMatch.cardBadge(wMatch != null)
            )
            row.addView(card)
        }
        box.addView(horizontalScroller(row))
        return box
    }

    /** Hold-OK on a Trending card: details / add to list / not interested. */
    private fun openTrendingMenu(item: Discovery.TrendItem, inLibrary: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add("Details")
        actions.add { openTitleDetails(item.id, item.type, item.title, item.poster) }
        if (!inLibrary) {
            labels.add("Add to my list")
            actions.add { addToMyList(item.id, item.type, item.title, item.poster) }
            labels.add("Not interested")
            actions.add { dismissDiscovery(item.id, item.type) }
        }
        labels.add("Open Tracker")
        actions.add { openTracker() }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    /**
     * "Add to my list" (Trending, v2.7): the same writes as a
     * For You add — the overlay hides/marks the title at once,
     * TrackerActions queues the add + mirrors it into the pending
     * overlay, so the title appears in My Library as Pending.
     */
    private fun addToMyList(tmdbId: Long, type: String, title: String, poster: String) {
        val overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
        prefs.forYouOverlay = TrackerLogic.encodeForYouOverlay(
            TrackerLogic.withForYouMark(
                overlay,
                TrackerLogic.ForYouMark(
                    tmdbId, type, added = true, System.currentTimeMillis()
                )
            )
        )
        TrackerActions.addTitle(this, prefs, tmdbId, type, title, poster, "")
        Toast.makeText(
            this, "Added to your list — applies next time the tracker opens",
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    /**
     * "Not interested" (Trending, v2.7): the overlay dismissal —
     * the title leaves Trending AND For You at once — plus the
     * queued dismiss_foryou for the tracker.
     */
    private fun dismissDiscovery(tmdbId: Long, type: String) {
        val overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
        prefs.forYouOverlay = TrackerLogic.encodeForYouOverlay(
            TrackerLogic.withForYouMark(
                overlay,
                TrackerLogic.ForYouMark(
                    tmdbId, type, added = false, System.currentTimeMillis()
                )
            )
        )
        TrackerClient.dismissForYou(this, tmdbId, type)
        Toast.makeText(
            this, "Got it — that won't be suggested again",
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    /**
     * "Add to my list" from a For You HERO (v2.1): the same writes
     * as the row menu's add — the For You overlay hides the pick
     * everywhere (hero pool included) and TrackerActions queues the
     * add + mirrors it into the generalized pending overlay, so the
     * title appears in My Library as Pending at once.
     */
    private fun addForYouHero(hero: TrackerLogic.HeroItem) {
        val overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
        prefs.forYouOverlay = TrackerLogic.encodeForYouOverlay(
            TrackerLogic.withForYouMark(
                overlay,
                TrackerLogic.ForYouMark(
                    hero.tmdbId, hero.type, added = true, System.currentTimeMillis()
                )
            )
        )
        TrackerActions.addTitle(this, prefs, hero.tmdbId, hero.type, hero.title, hero.poster, "")
        Toast.makeText(
            this, "Added to your list — applies next time the tracker opens",
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    /** Hold-OK on a For You card: open / add to list / not interested. */
    private fun openForYouMenu(pick: TrackerLogic.ForYouPick) {
        // v2.3 Watch: a matched MOVIE pick gains "▶ Play" first —
        // shows have no episode coordinates here, so they keep the
        // plain menu (the spec's For You play entry is movie-only).
        val labels = mutableListOf<String>()
        val prefix = mutableListOf<() -> Unit>()
        if (isWatchStyle() && pick.type == "movie" && watchMatch("movie", pick.id) != null) {
            labels.add("▶ Play")
            prefix.add {
                playMovieItem(
                    TrackerLogic.MovieItem(pick.id, pick.title, "", false, pick.poster, false)
                )
            }
            // v3.1 (ask 40): same match, handed to another video
            // app instead of the built-in player.
            labels.add("Play in…")
            prefix.add {
                playInMovie(
                    TrackerLogic.MovieItem(pick.id, pick.title, "", false, pick.poster, false)
                )
            }
        }
        labels.addAll(listOf("Open Tracker", "Add to my list", "Not interested"))
        val base = prefix.size
        AlertDialog.Builder(this)
            .setTitle(pick.title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < base) {
                    prefix[which].invoke()
                    return@setItems
                }
                when (which - base) {
                    0 -> openTracker()
                    1 -> {
                        val overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
                        prefs.forYouOverlay = TrackerLogic.encodeForYouOverlay(
                            TrackerLogic.withForYouMark(
                                overlay,
                                TrackerLogic.ForYouMark(
                                    pick.id, pick.type, added = true,
                                    System.currentTimeMillis()
                                )
                            )
                        )
                        // For You picks carry no release date — "" per contract.
                        // v2.1: routed through TrackerActions so the title
                        // ALSO lands in the generalized pending overlay —
                        // it shows in My Library as Pending right away.
                        TrackerActions.addTitle(
                            this, prefs, pick.id, pick.type, pick.title, pick.poster, ""
                        )
                        Toast.makeText(
                            this, "Added to your list — applies next time the tracker opens",
                            Toast.LENGTH_SHORT
                        ).show()
                        rebuild()
                    }
                    2 -> {
                        val overlay = TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)
                        prefs.forYouOverlay = TrackerLogic.encodeForYouOverlay(
                            TrackerLogic.withForYouMark(
                                overlay,
                                TrackerLogic.ForYouMark(
                                    pick.id, pick.type, added = false,
                                    System.currentTimeMillis()
                                )
                            )
                        )
                        TrackerClient.dismissForYou(this, pick.id, pick.type)
                        Toast.makeText(
                            this, "Got it — that won't be suggested again",
                            Toast.LENGTH_SHORT
                        ).show()
                        rebuild()
                    }
                }
            }
            .show()
    }

    private fun buildInputsSection(): View? {
        val inputs = TvData.listInputs(this)
        DiagCache.inputsCount = inputs.size
        if (inputs.isEmpty()) return null
        val box = section("HDMI & Inputs")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (info in inputs) row.addView(inputTile(info))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * One HDMI/TV input tile — shared by the TV-style inputs row and
     * the iPad grid's inputs segment, so the v1.6 lock gate below
     * applies identically in both styles. Inputs are never move-mode
     * reorderable: their tile has no menu at all.
     */
    private fun inputTile(info: android.media.tv.TvInputInfo): View {
        val tile = glyphTile("📺", TvData.inputLabel(this, info))
        tile.setOnClickListener {
            // v1.6 gate audit: switching to an input starts an
            // activity inside the input's own package (e.g. the
            // TV/tuner app) — if that package is arrow-locked,
            // the lock applies here too.
            val pkg = try { info.serviceInfo.packageName } catch (e: Exception) { null }
            val launch = {
                if (!TvData.launchInput(this, info)) {
                    Toast.makeText(this, "Couldn't open that input on this device", Toast.LENGTH_SHORT).show()
                }
            }
            if (!pkg.isNullOrEmpty() && pkg in prefs.lockedApps) {
                gateLockedApp(pkg, TvData.inputLabel(this, info), launch)
            } else {
                launch()
            }
        }
        return tile
    }

    private fun horizontalScroller(row: LinearLayout): HorizontalScrollView {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        val lp = LinearLayout.LayoutParams(-1, -2)
        hs.layoutParams = lp
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10))
        hs.addView(row)
        return hs
    }

    // ---------------------------------------------------------------- tiles

    private enum class Source { Favorites, AllApps, Folder, Recents }

    /** The ONE tile size for the whole home screen (v1.2: core/TileLayout). */
    private fun currentTileMetrics(wide: Boolean = false): TileLayout.Metrics {
        val dm = resources.displayMetrics
        return if (wide) {
            TileLayout.cardMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        } else {
            TileLayout.tileMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        }
    }

    /**
     * Base frame for every home tile: fixed uniform size + panel
     * background. The name label is added by [finishTile] as an overlay,
     * so it never affects the tile's size or the row's layout.
     */
    private fun baseTile(wide: Boolean = false): FrameLayout {
        val m = currentTileMetrics(wide)
        val frame = FrameLayout(this)
        frame.isFocusable = true
        frame.isClickable = true
        val lp = LinearLayout.LayoutParams(m.tileWidthPx, m.tileHeightPx)
        val margin = Ui.dp(this, TileLayout.TILE_MARGIN_DP)
        lp.setMargins(margin, margin, margin, margin)
        frame.layoutParams = lp
        frame.background = Ui.tileBackground(accent, false, 16, this)
        if (firstTile == null) firstTile = frame
        return frame
    }

    /**
     * Adds the focus-only name overlay (bottom of the tile, translucent
     * pill) and the focus lift. The label is INVISIBLE unless the tile
     * has focus — the single decision point is TileLayout.labelVisible.
     */
    private fun finishTile(frame: FrameLayout, name: String) {
        val label = TextView(this)
        label.text = name
        label.setTextColor(textColour)
        label.textSize = 14f
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setBackgroundColor(0xB3000000.toInt())
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3))
        val llp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        llp.gravity = Gravity.BOTTOM
        label.layoutParams = llp
        label.visibility = View.INVISIBLE
        frame.addView(label)
        Ui.focusLift(frame, accent, 16) { hasFocus ->
            label.visibility = if (TileLayout.labelVisible(hasFocus)) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * One app tile: centered icon + focus-only name pill.
     * Click/long-click: launch + the shared menu (incl. Move).
     */
    private fun appTile(
        entry: AppEntry,
        source: Source,
        folderId: String? = null
    ): View {
        val m = currentTileMetrics()
        val tile = baseTile()
        // Move mode finds the grabbed tile by package after a rebuild.
        tile.tag = entry.pkg
        val iv = ImageView(this)
        // v2.0 custom artwork (core/ArtworkStore): consulted BEFORE the
        // PackageManager icon — preset → generated gradient tile,
        // URL → cached image (real icon shows until/unless it loads).
        val shape = effectiveIconShape()
        val fallbackIcon = Ui.shapedIcon(entry.icon, m.iconPx, shape)
        when (val art = artworkByPkg[entry.pkg]) {
            null -> iv.setImageBitmap(fallbackIcon)
            else -> when (art.kind) {
                ArtworkStore.Kind.PRESET -> iv.setImageBitmap(
                    Ui.presetArtwork(
                        ArtworkStore.presetColors(ArtworkStore.presetIndex(art.value)),
                        entry.label, m.iconPx, shape
                    )
                )
                ArtworkStore.Kind.URL -> ArtworkImages.load(
                    this, entry.pkg, art.value, iv, m.iconPx, shape, fallbackIcon
                )
            }
        }
        val ilp = FrameLayout.LayoutParams(m.iconPx, m.iconPx)
        ilp.gravity = Gravity.CENTER
        iv.layoutParams = ilp
        tile.addView(iv)
        if (prefs.lockedApps.contains(entry.pkg)) {
            // The lock badge stays visible even though the NAME is
            // focus-only, so a locked app is recognisable at a glance.
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            tile.addView(lock)
        }
        finishTile(tile, entry.label)
        tile.setOnClickListener { launchWithPin(entry) }
        tile.setOnLongClickListener { openAppMenu(entry, source, folderId); true }
        return tile
    }

    /** A tile whose centre is a glyph/symbol and whose name shows on focus. */
    private fun glyphTile(
        glyph: String,
        name: String,
        wide: Boolean = false
    ): FrameLayout {
        val tile = baseTile(wide)
        val centre = TextView(this)
        centre.text = glyph
        centre.setTextColor(textColour)
        centre.textSize = 32f
        centre.gravity = Gravity.CENTER
        val clp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        clp.gravity = Gravity.CENTER
        centre.layoutParams = clp
        tile.addView(centre)
        finishTile(tile, name)
        return tile
    }

    private fun watchTile(item: WatchItem): View {
        // Continue Watching uses the row's one uniform wide card size
        // (TileLayout.cardMetrics) and the same focus-label rule.
        val tile = glyphTile("▶", item.title, wide = true)
        tile.setOnClickListener {
            // v1.6 gate audit: this used to launch directly. When the
            // item's intent names a package, that package's arrow lock
            // applies here exactly like a home launch; items without a
            // package can't be attributed to an app and just launch.
            val pkg = try {
                item.intentUri?.let {
                    Intent.parseUri(it, Intent.URI_INTENT_SCHEME).`package`
                }
            } catch (e: Exception) { null }
            val launch = {
                if (!TvData.launchWatchItem(this, item)) {
                    Toast.makeText(this, "Couldn't open that title", Toast.LENGTH_SHORT).show()
                } else if (!pkg.isNullOrEmpty()) {
                    // Recently Used records Continue Watching launches
                    // where applicable (package-named intents only).
                    prefs.recordLaunch(pkg)
                }
            }
            if (!pkg.isNullOrEmpty()) gateLockedApp(pkg, item.title, launch) else launch()
        }
        return tile
    }

    /**
     * v1.6: a folder tile's face is a tidy 2×2 mini-grid of its first
     * four apps' icons (the Apple/Roku look) instead of a lone glyph;
     * a folder with no direct apps keeps the glyph. The mini-grid is
     * presentation only — name, badge, menus and moves are unchanged.
     */
    /**
     * A folder tile's face: the 2×2 mini-grid of its first apps'
     * icons (v1.6), or the 🗂 glyph when it holds no apps, centered.
     * The mini icons use the profile's icon shape.
     */
    private fun folderFace(folder: Folder): View {
        val contained = folder.appPackages.mapNotNull { appsByPkg[it] }
        if (contained.isEmpty()) {
            val centre = TextView(this)
            centre.text = "🗂"
            centre.setTextColor(textColour)
            centre.textSize = 32f
            centre.gravity = Gravity.CENTER
            val clp = FrameLayout.LayoutParams(-2, -2)
            clp.gravity = Gravity.CENTER
            centre.layoutParams = clp
            return centre
        }
        val m = currentTileMetrics()
        val gap = Ui.dp(this, 4)
        val mini = ((m.iconPx - gap) / 2).coerceAtLeast(8)
        val grid = android.widget.GridLayout(this)
        grid.columnCount = 2
        grid.rowCount = 2
        val glp = FrameLayout.LayoutParams(-2, -2)
        glp.gravity = Gravity.CENTER
        grid.layoutParams = glp
        for (i in 0 until 4) {
            val cell = FrameLayout(this)
            val clp = android.widget.GridLayout.LayoutParams()
            clp.width = mini
            clp.height = mini
            clp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            cell.layoutParams = clp
            val entry = contained.getOrNull(i)
            if (entry != null) {
                val iv = ImageView(this)
                iv.setImageBitmap(Ui.shapedIcon(entry.icon, mini, effectiveIconShape()))
                cell.addView(iv, FrameLayout.LayoutParams(mini, mini))
            } else {
                val dot = View(this)
                val bg = GradientDrawable()
                bg.cornerRadius = Ui.dp(this, 6).toFloat()
                bg.setColor(com.mizan.launcher.core.Design.TILE_FILL)
                dot.background = bg
                cell.addView(dot, FrameLayout.LayoutParams(mini, mini))
            }
            grid.addView(cell)
        }
        return grid
    }

    /**
     * One folder tile: face + focus-only name. The Folders row
     * deliberately has no reorder Move (folders are ordered by
     * creation/nesting, v1.3 rule).
     */
    private fun folderTile(folder: Folder): View {
        val tile = baseTile()
        tile.addView(folderFace(folder))
        finishTile(tile, "${folder.name} · ${folderContentsLabel(folder)}")
        // v1.3: locked folders carry the same 🔒 badge as locked apps.
        if (folder.locked) {
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            tile.addView(lock)
        }
        tile.setOnClickListener { openFolderGated(folder) }
        tile.setOnLongClickListener {
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            labels.add("Rename folder"); actions.add { renameFolder(folder) }
            labels.add(if (folder.locked) "Unlock folder" else "Lock folder")
            actions.add { toggleFolderLock(folder) }
            labels.add("Move into folder…"); actions.add { chooseFolderParent(folder) }
            labels.add("Delete folder"); actions.add {
                // v1.5: deleting a folder never cascade-deletes — its
                // sub-folders return to the home Folders row and its
                // apps simply stop being folder members.
                FolderTree.deleteFolder(layout.folders, folder.id)
                saveLayout(); rebuild()
                Toast.makeText(
                    this,
                    "Folder deleted — sub-folders are back on the home screen; apps are still installed",
                    Toast.LENGTH_LONG
                ).show()
            }
            AlertDialog.Builder(this).setTitle(folder.name)
                .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
                .show()
            true
        }
        return tile
    }

    /**
     * "Move into folder…" for a folder (v1.5): the picker offers ONLY
     * legal targets (core/FolderTree.canNest — no self, no cycles, no
     * depth overflow), labelled by breadcrumb path, plus "Home (top
     * level)" when the folder is currently nested. Moving REPLACES the
     * folder's parent: it leaves wherever it was.
     */
    private fun chooseFolderParent(folder: Folder) {
        val targets = FolderTree.legalParents(layout.folders, folder.id)
        val nested = FolderTree.parentOf(layout.folders, folder.id) != null
        val labels = mutableListOf<String>()
        if (nested) labels.add("⌂ Home (top level)")
        labels.addAll(targets.map { "🗂 ${folderPathLabel(it)}" })
        if (labels.isEmpty()) {
            Toast.makeText(this, "No folder can hold this one (depth limit ${FolderTree.MAX_DEPTH})", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Move “${folder.name}” into…")
            .setItems(labels.toTypedArray()) { _, which ->
                val parentId: String? = if (nested) {
                    if (which == 0) null else targets[which - 1].id
                } else {
                    targets[which].id
                }
                FolderTree.moveFolder(layout.folders, folder.id, parentId)
                saveLayout(); rebuild()
                Toast.makeText(
                    this,
                    if (parentId == null) "“${folder.name}” is back on the home screen"
                    else "“${folder.name}” moved into ${FolderTree.byId(layout.folders, parentId)?.name ?: "folder"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Flip a folder's lock flag. Locking with no arrow pattern set runs
     * the set-pattern flow first (same fail-open rule as apps: nobody
     * gets locked out of their own launcher). If the user cancels the
     * setup, the folder simply stays unlocked.
     */
    private fun toggleFolderLock(folder: Folder) {
        if (folder.locked) {
            folder.locked = false
            saveLayout(); rebuild()
            Toast.makeText(this, "Folder unlocked", Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.arrowPin.isEmpty()) {
            ArrowPinDialog.showSetup(this, "Set a pattern to lock “${folder.name}”", accent, onDone = { seq ->
                prefs.arrowPin = seq
                folder.locked = true
                saveLayout(); rebuild()
                Toast.makeText(this, "Folder locked", Toast.LENGTH_SHORT).show()
            })
        } else {
            folder.locked = true
            saveLayout(); rebuild()
            Toast.makeText(this, "Folder locked", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open a folder honouring its lock (v1.3): unlocked → open; locked
     * with a pattern → pattern first (wrong pattern keeps it closed);
     * locked without a pattern → set-pattern flow, then open
     * (fail-open, mirroring the app-lock rule).
     */
    private fun openFolderGated(folder: Folder) {
        when (FolderLogic.folderGate(folder, prefs.arrowPin.isNotEmpty())) {
            FolderGate.OPEN -> openFolder(folder)
            FolderGate.PROMPT_PATTERN -> {
                // Wrong entries keep the dialog open (retry) — the
                // folder opens only via onSuccess, so a wrong pattern
                // leaves it closed. Cancelling changes nothing.
                runPatternGate("Unlock “${folder.name}”") { openFolder(folder) }
            }
            FolderGate.PROMPT_SET_PATTERN -> {
                ArrowPinDialog.showSetup(this, "Set a pattern to open “${folder.name}”", accent, onDone = { seq ->
                    prefs.arrowPin = seq
                    openFolder(folder)
                })
            }
        }
    }

    private fun newFolderTile(): View {
        val tile = glyphTile("＋", "New folder")
        tile.setOnClickListener { promptFolderName(null) }
        return tile
    }

    // -------------------------------------------------------------- folders

    private fun promptFolderName(after: ((String) -> Unit)?) {
        val input = EditText(this)
        input.hint = "Folder name"
        input.setTextColor(0xFF111111.toInt())
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (after != null) after(name) else {
                        // v1.5: every folder gets a stable id at birth.
                        layout.folders.add(Folder(FolderTree.freshId(layout.folders), name))
                        saveLayout(); rebuild()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameFolder(folder: Folder) {
        val input = EditText(this)
        input.setText(folder.name)
        input.setTextColor(0xFF111111.toInt())
        AlertDialog.Builder(this).setTitle("Rename folder").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    folder.name = name; saveLayout(); rebuild()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Folder overlay: a d-pad navigable dialog showing one folder's
     * contents (v1.5: apps AND sub-folder tiles). Navigation is a
     * STACK of folder ids: opening a sub-folder pushes its id, BACK
     * pops one level, and BACK at the root level closes the overlay.
     * The dialog title shows the breadcrumb ("Media › Video"). The
     * folder on display is always resolved fresh from the CURRENT
     * layout by id — layout reloads replace Folder objects, so a
     * captured object would go stale (the v1.3/v1.4 lesson).
     */
    private fun openFolder(folder: Folder) {
        folderStack.clear()
        folderStack.add(folder.id)
        val scroll = ScrollView(this)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16))
        scroll.addView(box)
        val dlg = AlertDialog.Builder(this).setView(scroll)
            .setNegativeButton("Close", null).create()
        // Folder tiles close the overlay before launching, so Back is sane.
        box.tag = dlg
        dlg.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // Move mode inside the overlay is driven from the dialog
            // window (a separate window from the activity's key
            // dispatch). The session identifies its folder by ID
            // (v1.5): layout reloads replace Folder objects, so object
            // or index identity would silently stop matching.
            val session = moveSession
            if (session != null && session.container == MoveContainer.FOLDER &&
                session.folderId == folderStack.lastOrNull()
            ) {
                return@setOnKeyListener handleMoveKey(keyCode)
            }
            // BACK pops one level of the folder stack; at the root
            // level it falls through and closes the overlay.
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && folderStack.size > 1) {
                folderStack.removeAt(folderStack.lastIndex)
                renderFolderOverlay()
                return@setOnKeyListener true
            }
            false
        }
        dlg.setOnDismissListener {
            if (moveSession?.container == MoveContainer.FOLDER) {
                endMoveMode(commit = false)
            }
            if (currentFolderDialog === dlg) {
                currentFolderDialog = null
                currentFolderBox = null
                folderStack.clear()
            }
        }
        currentFolderDialog = dlg
        currentFolderBox = box
        renderFolderOverlay()
        dlg.show()
    }

    private var currentFolderDialog: AlertDialog? = null
    private var currentFolderBox: LinearLayout? = null

    /** Folder ids from the overlay's root down to the folder on display. */
    private val folderStack = mutableListOf<String>()

    /** The folder the overlay is currently showing (top of the stack). */
    private fun currentOverlayFolder(): Folder? =
        folderStack.lastOrNull()?.let { FolderTree.byId(layout.folders, it) }

    /**
     * Re-render the open folder overlay for the folder on top of the
     * stack (after navigating, a move, or a contents change). The
     * folder is resolved from the CURRENT layout by id, never from a
     * captured object.
     */
    private fun renderFolderOverlay() {
        val box = currentFolderBox ?: return
        val folder = currentOverlayFolder()
        if (folder == null) {
            // The folder vanished (deleted elsewhere) — close quietly.
            currentFolderDialog?.dismiss()
            return
        }
        currentFolderDialog?.setTitle(
            FolderTree.breadcrumb(layout.folders, folder.id).joinToString(" › ") { it.name }
        )
        box.removeAllViews()
        // Tiles in entry order: apps resolve to app entries, folder
        // references to sub-folder tiles. Hidden apps stay hidden.
        val tiles = mutableListOf<View>()
        for (entry in folder.entries) {
            when (entry) {
                is FolderEntry.App -> {
                    val app = appsByPkg[entry.pkg]
                    if (app != null && app.pkg !in layout.hidden) {
                        tiles.add(appTile(app, Source.Folder, folder.id))
                    }
                }
                is FolderEntry.FolderRef -> {
                    FolderTree.byId(layout.folders, entry.folderId)?.let { sub ->
                        tiles.add(subFolderTile(sub, folder.id))
                    }
                }
            }
        }
        if (tiles.isEmpty()) {
            val t = TextView(this); t.text = "This folder is empty."; t.textSize = 18f
            box.addView(t)
            return
        }
        val cols = prefs.columns
        var i = 0
        while (i < tiles.size) {
            val rowLayout = LinearLayout(this); rowLayout.orientation = LinearLayout.HORIZONTAL
            for (tile in tiles.subList(i, minOf(i + cols, tiles.size))) {
                rowLayout.addView(tile)
            }
            box.addView(rowLayout)
            i += cols
        }
    }

    /**
     * A sub-folder tile inside the overlay: the same uniform tile +
     * focus-name + 🔒 badge rules as home folder tiles. Clicking it
     * pushes the folder onto the overlay's stack — gated by the
     * folder lock at push time, exactly like opening from home.
     * Hold-OK: Move (reorder the entry inside its parent), Remove
     * from folder (the sub-folder returns to the home Folders row,
     * contents intact), Lock/Unlock.
     */
    private fun subFolderTile(folder: Folder, parentId: String): View {
        val tile: FrameLayout =
            glyphTile("🗂", "${folder.name} · ${folderContentsLabel(folder)}")
        // The grabbed-tile search (move mode) finds tiles by entry key.
        tile.tag = FolderEntry.FolderRef(folder.id).key()
        if (folder.locked) {
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            tile.addView(lock)
        }
        tile.setOnClickListener { pushFolderGated(folder) }
        tile.setOnLongClickListener {
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            labels.add("Move")
            actions.add { startEntryMove(FolderEntry.FolderRef(folder.id).key(), parentId) }
            labels.add("Remove from folder")
            actions.add {
                FolderTree.byId(layout.folders, parentId)?.entries?.removeAll {
                    it is FolderEntry.FolderRef && it.folderId == folder.id
                }
                saveLayout(); renderFolderOverlay(); rebuild()
                Toast.makeText(
                    this, "“${folder.name}” is back on the home screen", Toast.LENGTH_SHORT
                ).show()
            }
            labels.add(if (folder.locked) "Unlock folder" else "Lock folder")
            actions.add {
                toggleFolderLock(folder)
                // toggleFolderLock rebuilds HOME; the overlay shows a
                // possibly-stale layout object afterwards, so re-render
                // it from the current layout too.
                renderFolderOverlay()
            }
            AlertDialog.Builder(this).setTitle(folder.name)
                .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
                .show()
            true
        }
        return tile
    }

    /**
     * Push a sub-folder onto the overlay stack, honouring its lock
     * (same gate as opening a folder from home): locked + pattern →
     * pattern first; locked without a pattern → set-pattern flow,
     * then push (fail-open). A move session in progress blocks
     * navigation — the grabbed tile must be dropped/cancelled first.
     */
    private fun pushFolderGated(folder: Folder) {
        if (moveSession != null) return
        when (FolderLogic.folderGate(folder, prefs.arrowPin.isNotEmpty())) {
            FolderGate.OPEN -> pushFolder(folder)
            FolderGate.PROMPT_PATTERN -> {
                runPatternGate("Unlock “${folder.name}”") { pushFolder(folder) }
            }
            FolderGate.PROMPT_SET_PATTERN -> {
                ArrowPinDialog.showSetup(this, "Set a pattern to open “${folder.name}”", accent, onDone = { seq ->
                    prefs.arrowPin = seq
                    pushFolder(folder)
                })
            }
        }
    }

    private fun pushFolder(folder: Folder) {
        if (folderStack.lastOrNull() == folder.id) return
        folderStack.add(folder.id)
        renderFolderOverlay()
        // Focus the first tile of the pushed folder, like a fresh open.
        currentFolderBox?.post { currentFolderBox?.let { focusFirstTile(it) } }
    }

    /** Best-effort focus of the first focusable tile inside [view]. */
    private fun focusFirstTile(view: View): Boolean {
        if (view.isFocusable && view.requestFocus()) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (focusFirstTile(view.getChildAt(i))) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- menus

    private fun saveLayout() { prefs.layout = layout }

    private fun openAppMenu(entry: AppEntry, source: Source, folderId: String?) {
        val isFav = layout.favorites.contains(entry.pkg)
        val isLocked = prefs.lockedApps.contains(entry.pkg)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // v1.3: hold-and-move replaces the old step left/right entries.
        // v1.4: Recently Used tiles get the same menu MINUS Move — the
        // row's order is its recency, so there is nothing to rearrange.
        if (source != Source.Recents) {
            labels.add("Move")
            actions.add { startMoveMode(entry, source, folderId) }
        }
        labels.add(if (isFav) "Remove favorite" else "Add favorite")
        actions.add {
            if (isFav) layout.favorites.remove(entry.pkg) else layout.favorites.add(entry.pkg)
            saveLayout(); rebuild()
        }
        if (source == Source.Folder && folderId != null) {
            labels.add("Remove from folder")
            actions.add {
                // v1.5: removing the entry takes the app out of the
                // folder; it reappears in All Apps (recursive dedupe).
                FolderTree.byId(layout.folders, folderId)?.entries?.removeAll {
                    it is FolderEntry.App && it.pkg == entry.pkg
                }
                saveLayout(); currentFolderDialog?.dismiss(); rebuild()
            }
        } else {
            labels.add("Add to folder…")
            actions.add { chooseFolder(entry) }
        }
        labels.add(if (isLocked) "Remove arrow lock" else "Arrow-lock this app")
        actions.add {
            val set = prefs.lockedApps.toMutableSet()
            if (isLocked) set.remove(entry.pkg) else set.add(entry.pkg)
            prefs.lockedApps = set
            if (!isLocked && prefs.arrowPin.isEmpty()) {
                Toast.makeText(this, "Locked. You'll set an arrow pattern the first time you open it (or in Settings).", Toast.LENGTH_LONG).show()
            }
            rebuild()
        }
        // v2.0: custom artwork (presets / image URL / reset).
        labels.add("Custom artwork…")
        actions.add {
            currentFolderDialog?.dismiss()
            ArtworkPicker.show(this, prefs, accent, entry.pkg, entry.label) { rebuild() }
        }
        labels.add("Hide app")
        actions.add {
            layout.hidden.add(entry.pkg)
            layout.favorites.remove(entry.pkg)
            saveLayout(); currentFolderDialog?.dismiss(); rebuild()
            Toast.makeText(this, "Hidden. Unhide it in Settings.", Toast.LENGTH_SHORT).show()
        }
        labels.add("App info")
        actions.add {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${entry.pkg}"))
                )
            } catch (e: Exception) { Prefs.noteError(this, "appInfo", e) }
        }
        if (!entry.isSystem) {
            labels.add("Uninstall")
            actions.add {
                try {
                    startActivity(Intent(Intent.ACTION_DELETE).setData(android.net.Uri.parse("package:${entry.pkg}")))
                } catch (e: Exception) { Prefs.noteError(this, "uninstall", e) }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    // ------------------------------------------------------------ move mode

    /**
     * One active hold-and-move session (v1.4 rework). The ordering
     * state lives ENTIRELY in the pure [MoveSessionMachine], which
     * owns a private working copy of the container's order. This
     * holder only remembers WHERE the order belongs (container +
     * folder ID) so each write-back resolves its target list from
     * the CURRENT layout at write time.
     *
     * v1.3 stored a direct reference to the layout's list here — and
     * rebuild() reloads the layout from prefs (a fresh object) on
     * every rebuild, orphaning that reference at grab time: swaps
     * landed in a list nobody rendered or persisted, and arrows
     * appeared to do nothing (confirmed on-device). Never hold a
     * layout list reference across a rebuild again.
     *
     * v1.5: folder sessions resolve their folder by its STABLE ID
     * (v1.4 used the list index; nesting made positions meaningless),
     * and the session orders ENTRY KEYS — inside a folder, apps and
     * sub-folder entries move through the same machine.
     */
    private class ActiveMove(
        val machine: MoveSessionMachine,
        val container: MoveContainer,
        /** Stable folder id for FOLDER sessions; null otherwise. */
        val folderId: String?
    )

    private var moveSession: ActiveMove? = null
    private var moveHintBar: TextView? = null
    private var movePulse: Runnable? = null
    private var movePulseOn: Boolean = false

    /**
     * Enter move mode for [entry] from the hold-OK menu. All Apps moves
     * edit the DISPLAYED order (folder-hidden apps are not in it; the
     * displayed order is materialized into allAppsOrder first — folder
     * membership itself is never touched by moving). Favorites and
     * folder contents edit their own lists, via the machine's copy.
     */
    private fun startMoveMode(entry: AppEntry, source: Source, folderId: String?) {
        // Recents tiles never enter move mode (their menu omits Move).
        if (source == Source.Recents) return
        val container: MoveContainer
        val initialOrder: List<String>
        when (source) {
            Source.Favorites -> {
                container = MoveContainer.FAVORITES
                initialOrder = layout.favorites.toList()
            }
            Source.AllApps -> {
                container = MoveContainer.ALL_APPS
                val visiblePkgs = FolderLogic.allAppsVisible(
                    apps.map { it.pkg }, layout.hidden.toSet(), layout.folders
                ).toSet()
                val current = orderedApps(apps.filter { it.pkg in visiblePkgs }).map { it.pkg }
                if (layout.allAppsOrder != current) {
                    layout.allAppsOrder.clear(); layout.allAppsOrder.addAll(current)
                    saveLayout()
                }
                initialOrder = layout.allAppsOrder.toList()
            }
            Source.Folder -> {
                // v1.5: a folder session orders the folder's ENTRY
                // KEYS (apps and sub-folders alike), resolved by id.
                val folder = folderId?.let { FolderTree.byId(layout.folders, it) } ?: return
                container = MoveContainer.FOLDER
                initialOrder = folder.entryKeys()
            }
            Source.Recents -> return // unreachable (guarded above); keeps the when exhaustive
        }
        beginMoveSession(entry.pkg, container, initialOrder, folderId)
    }

    /**
     * Enter move mode for a folder ENTRY (v1.5): a sub-folder tile
     * inside its parent's overlay. The grabbed "item" is the entry
     * key (`@f:<id>`); from here it moves through the parent's entry
     * list exactly like an app.
     */
    private fun startEntryMove(entryKey: String, parentFolderId: String) {
        val folder = FolderTree.byId(layout.folders, parentFolderId) ?: return
        beginMoveSession(entryKey, MoveContainer.FOLDER, folder.entryKeys(), parentFolderId)
    }

    /** Shared session start for app moves and folder-entry moves. */
    private fun beginMoveSession(
        grabbedKey: String,
        container: MoveContainer,
        initialOrder: List<String>,
        folderId: String?
    ) {
        val machine = MoveSessionMachine(grabbedKey, initialOrder, prefs.columns)
        if (!machine.isValid) return
        moveSession = ActiveMove(
            machine, container,
            if (container == MoveContainer.FOLDER) folderId else null
        )
        // Re-render (buildRoot attaches the hint bar while a home
        // session is active), re-find the grabbed tile, mark it.
        rebuildAfterMove()
        Toast.makeText(this, "Move mode: arrows move, OK drops, Back cancels", Toast.LENGTH_SHORT).show()
    }

    /**
     * Write a session order (current or snapshot) into the CURRENT
     * layout's list for the session's container and persist it. The
     * target is resolved HERE, at write time — never from a reference
     * captured earlier — so layout reloads (rebuild re-decodes prefs)
     * cannot orphan a move again. FOLDER write-back converts the
     * session's entry keys back into typed entries.
     */
    private fun applyMoveOrder(session: ActiveMove, order: List<String>) {
        when (session.container) {
            MoveContainer.FAVORITES -> {
                layout.favorites.clear()
                layout.favorites.addAll(order)
            }
            MoveContainer.ALL_APPS -> {
                layout.allAppsOrder.clear()
                layout.allAppsOrder.addAll(order)
            }
            MoveContainer.FOLDER -> {
                val folder = session.folderId?.let { FolderTree.byId(layout.folders, it) }
                if (folder != null) {
                    folder.entries.clear()
                    folder.entries.addAll(order.map { FolderEntry.fromKey(it) })
                }
            }
            MoveContainer.ROOT_FOLDERS -> {
                // v1.7: permute only the roots' own slots in the
                // flat list — nested folders keep their positions.
                FolderTree.reorderRoots(layout.folders, order)
            }
        }
        saveLayout()
    }

    /** Hint bar pinned at the top of the home root while move mode runs. */
    private fun attachMoveHint(root: FrameLayout) {
        hideMoveChrome()
        val bar = TextView(this)
        bar.text = "✥ Move with arrows · OK = drop · Back = cancel"
        bar.setTextColor(0xFFFFFFFF.toInt())
        bar.textSize = 16f
        bar.gravity = Gravity.CENTER
        bar.setBackgroundColor(0xE6222222.toInt())
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP
        bar.layoutParams = lp
        root.addView(bar)
        moveHintBar = bar
    }

    private fun hideMoveChrome() {
        moveHintBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        moveHintBar = null
        stopMovePulse()
    }

    /** Gentle pulse on the grabbed tile so it reads as "held". */
    private fun startMovePulse(tile: View) {
        stopMovePulse()
        val pulse = object : Runnable {
            override fun run() {
                movePulseOn = !movePulseOn
                tile.scaleX = if (movePulseOn) 1.06f else 1.12f
                tile.scaleY = tile.scaleX
                tile.elevation = Ui.dp(this@MainActivity, 10).toFloat()
                tile.background = Ui.tileBackground(accent, movePulseOn, 16, this@MainActivity)
                handler.postDelayed(this, 450)
            }
        }
        movePulse = pulse
        movePulseOn = false
        handler.post(pulse)
    }

    private fun stopMovePulse() {
        movePulse?.let { handler.removeCallbacks(it) }
        movePulse = null
    }

    /** The grabbed tile, searched in the window that actually hosts it. */
    private fun findGrabbedTile(session: ActiveMove): View? {
        // Folder tiles live in the DIALOG's window, not the activity's —
        // v1.3 searched only the activity window, so folder sessions
        // never got their focus/pulse back after a re-render.
        val roots = listOfNotNull(
            if (session.container == MoveContainer.FOLDER) {
                currentFolderDialog?.window?.decorView
            } else null,
            window?.decorView
        )
        for (root in roots) {
            root.findViewWithTag<View>(session.machine.pkg)?.let { return it }
        }
        return null
    }

    /**
     * After any move-mode state change: re-render the container, put
     * focus back on the grabbed tile, and re-apply its grabbed look.
     */
    private fun rebuildAfterMove() {
        val session = moveSession ?: return
        if (session.container == MoveContainer.FOLDER) renderFolderOverlay() else rebuild()
        val tile = findGrabbedTile(session) ?: return
        tile.post { tile.requestFocus() }
        startMovePulse(tile)
    }

    /**
     * One key press inside move mode. Returns true when consumed
     * (everything is, while a session runs — the home screen must not
     * navigate away mid-move).
     */
    private fun handleMoveKey(keyCode: Int): Boolean {
        val session = moveSession ?: return false
        val dir = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> com.mizan.launcher.core.ArrowDir.LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> com.mizan.launcher.core.ArrowDir.RIGHT
            android.view.KeyEvent.KEYCODE_DPAD_UP -> com.mizan.launcher.core.ArrowDir.UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> com.mizan.launcher.core.ArrowDir.DOWN
            else -> null
        }
        when {
            dir != null -> {
                // The machine swaps inside its own working copy; the
                // result is written into the CURRENT layout (resolved
                // at this moment) and persisted BEFORE the re-render,
                // which reloads the layout from prefs — so what renders
                // is exactly what was written.
                if (session.machine.move(dir)) {
                    applyMoveOrder(session, session.machine.currentOrder())
                    rebuildAfterMove()
                }
                // Edge no-ops are swallowed: focus stays on the tile.
            }
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                endMoveMode(commit = true)
                Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show()
            }
            keyCode == android.view.KeyEvent.KEYCODE_BACK -> endMoveMode(commit = false)
            else -> Unit // swallow other keys while moving
        }
        return true
    }

    /** Leave move mode: drop (keep order) or cancel (restore snapshot). */
    private fun endMoveMode(commit: Boolean) {
        val session = moveSession ?: return
        if (!commit) {
            // Restore the grab-time order into the CURRENT layout.
            applyMoveOrder(session, session.machine.snapshotOrder())
        }
        // On drop nothing is pending: every move was already written
        // into the layout (and prefs) as it happened.
        moveSession = null
        hideMoveChrome()
        if (session.container == MoveContainer.FOLDER) renderFolderOverlay() else rebuild()
        val tile = findGrabbedTile(session)
        tile?.post { tile.requestFocus() }
    }

    /**
     * "Add to folder…" picker for an APP (v1.5): every folder is a
     * legal target for an app (the depth/cycle rules constrain
     * folder-in-folder placement, not apps), so all folders are
     * offered — nested ones labelled by breadcrumb path so "Video"
     * under "Media" reads differently from a root "Video".
     */
    private fun chooseFolder(entry: AppEntry) {
        if (layout.folders.isEmpty()) {
            promptFolderName { name ->
                val f = Folder(
                    FolderTree.freshId(layout.folders), name,
                    mutableListOf(FolderEntry.App(entry.pkg))
                )
                layout.folders.add(f); saveLayout(); rebuild()
            }
            return
        }
        val names = layout.folders.map { folderPathLabel(it) } + "＋ New folder…"
        AlertDialog.Builder(this).setTitle("Add ${entry.label} to…")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == layout.folders.size) {
                    promptFolderName { name ->
                        layout.folders.add(
                            Folder(
                                FolderTree.freshId(layout.folders), name,
                                mutableListOf(FolderEntry.App(entry.pkg))
                            )
                        )
                        saveLayout(); rebuild()
                    }
                } else {
                    val f = layout.folders[which]
                    if (!f.appPackages.contains(entry.pkg)) {
                        f.entries.add(FolderEntry.App(entry.pkg))
                    }
                    saveLayout(); rebuild()
                    Toast.makeText(this, "Added to ${f.name}", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    // -------------------------------------------------------------- launching

    private fun launchWithPin(entry: AppEntry) {
        currentFolderDialog?.dismiss()
        currentFolderDialog = null
        gateLockedApp(entry.pkg, entry.label) { doLaunch(entry) }
    }

    /**
     * THE arrow-lock gate (v1.6: one shared funnel). Every launch path
     * that ends in some package goes through here — home/folder/
     * recents tiles (via launchWithPin), Continue Watching items whose
     * intent names a package, and HDMI input tiles (an input switch
     * starts an activity inside the input's own package). Until v1.6
     * the last two launched directly and bypassed the lock entirely.
     *
     * Not locked -> [onPass] runs immediately. Locked + no pattern
     * set -> FAIL-OPEN (v1.2 rule): prompt to SET a pattern first,
     * then run — the launcher never locks Haythem out of his own TV.
     * Locked + pattern -> the unlock prompt; success runs [onPass].
     */
    private fun gateLockedApp(pkg: String, label: String, onPass: () -> Unit) {
        if (!prefs.lockedApps.contains(pkg)) {
            onPass()
            return
        }
        val stored = prefs.arrowPin
        if (stored.isEmpty()) {
            ArrowPinDialog.showSetup(
                activity = this,
                title = "Set an arrow pattern to lock $label",
                accent = accent,
                onDone = { seq ->
                    prefs.arrowPin = seq
                    Toast.makeText(this, "Arrow lock set", Toast.LENGTH_SHORT).show()
                    onPass()
                }
            )
            return
        }
        // Arrow-sequence unlock — dots only, no digits anywhere.
        runPatternGate("$label is locked", onPass)
    }

    /**
     * THE pattern prompt for GATES (v1.9): every gate verification
     * in this activity — locked apps (via [gateLockedApp]), locked
     * folders, the guest-exit gate — comes through here, so the
     * unlock-grace window (core/UnlockGrace, per profile) applies
     * identically everywhere: inside the window the gate passes
     * silently; outside it the prompt shows, and a SUCCESS stamps
     * Prefs.lastUnlockAt (the one device-level grace clock). Wrong
     * attempts feed the diagnostics counter as always. Pattern
     * MANAGEMENT prompts (set/change/clear, in Settings) are not
     * gates and never consult grace — they always verify.
     */
    private fun runPatternGate(title: String, onPass: () -> Unit) {
        if (prefs.withinUnlockGrace()) {
            onPass()
            return
        }
        ArrowPinDialog.showUnlock(
            activity = this,
            title = title,
            accent = accent,
            storedEncoded = prefs.arrowPin,
            onSuccess = {
                prefs.stampUnlock()
                onPass()
            },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
        )
    }

    private fun doLaunch(entry: AppEntry) {
        try {
            val intent = AppLoader.launchIntent(packageManager, entry.pkg)
            if (intent == null) {
                Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(intent)
            // v1.4: launches FROM Sreeb feed the Recently Used row.
            prefs.recordLaunch(entry.pkg)
        } catch (e: Exception) {
            Prefs.noteError(this, "launch:${entry.pkg}", e)
            Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ housekeeping (v1.4)

    private var lastHousekeepAt: Long = 0
    private var bingFetchInFlight: Boolean = false

    /**
     * Throttled tick work (called from the clock tick): Now Playing
     * change detection plus the rotation/Bing checks. Each piece is
     * fail-open; a changed Now Playing signature triggers one rebuild
     * so the row appears/disappears/updates without user action.
     */
    private fun housekeeping() {
        val now = System.currentTimeMillis()
        if (now - lastHousekeepAt < 15_000) return
        lastHousekeepAt = now
        try { checkRotationAndBing() } catch (e: Exception) {
            Prefs.noteError(this, "housekeeping:wallpaper", e)
        }
        // v3.2 (ask 43): weather's stale-fetch lives HERE (the
        // visible-home tick), not on open/resume/rebuild.
        try { maybeFetchWeather() } catch (e: Exception) {
            // Widget/background only — ignore.
        }
        try { checkNowPlayingChanged() } catch (e: Exception) {
            // Row refresh is cosmetic; never disturb the home screen.
        }
    }

    /**
     * The service-free wallpaper schedule: Reddit rotation due-check
     * and the Bing calendar-day check. Called on resume and on the
     * (throttled) clock tick. No timers, no services, no alarms.
     */
    private fun checkRotationAndBing() {
        if (!::prefs.isInitialized || prefs.safeMode) return
        val now = System.currentTimeMillis()
        // Reddit auto-rotation: due → advance to the next candidate.
        if (prefs.wallpaperKind == "reddit" &&
            RotationLogic.isDue(now, prefs.redditRotationLast, prefs.redditRotation)
        ) {
            WallpaperRotator.advanceReddit(this, prefs) { rebuild() }
        }
        // Bing daily: a new fetch only when the calendar day changed
        // (or today's file is missing). The cached file for today is
        // applied directly, with no network at all.
        if (prefs.wallpaperKind == "bing") {
            val today = com.mizan.launcher.core.BingWallpaper.dayKey(now)
            val cached = if (prefs.bingCacheDate == today && prefs.bingCachePath.isNotEmpty()) {
                java.io.File(prefs.bingCachePath)
            } else null
            if (cached != null && cached.exists()) {
                if (prefs.wallpaperValue != cached.absolutePath) {
                    prefs.wallpaperValue = cached.absolutePath
                    // v2.5: decode off the main thread; the accent
                    // catch-up rebuild fires only if it changed.
                    AccentUtil.recomputeAsync(this, prefs) { rebuild() }
                    rebuild()
                }
            } else if (!bingFetchInFlight) {
                bingFetchInFlight = true
                BingFetcher.fetchToday(this, today) { file ->
                    runOnUiThread {
                        bingFetchInFlight = false
                        if (file != null) {
                            prefs.bingCacheDate = today
                            prefs.bingCachePath = file.absolutePath
                            prefs.wallpaperValue = file.absolutePath
                            AccentUtil.recomputeAsync(this, prefs) { rebuild() }
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    /** Rebuild home when the Now Playing card set changed under us. */
    private fun checkNowPlayingChanged() {
        if (prefs.safeMode || !layout.isRowVisible("nowplaying")) return
        if (!NowPlayingData.isGranted(this)) {
            lastNowPlayingSignature = ""
            return
        }
        val sig = nowPlayingSignature(NowPlayingData.activeCards(this).map { it.card })
        val last = lastNowPlayingSignature
        if (last == null) {
            lastNowPlayingSignature = sig
        } else if (sig != last) {
            rebuild() // buildNowPlayingSection refreshes the signature
        }
    }

    // -------------------------------------------------------------- top bar data

    private fun updateClock() {
        try {
            val now = Date()
            val cfg = if (prefs.safeMode) {
                com.mizan.launcher.core.StatusBarConfig()
            } else {
                layout.statusBar
            }
            val datePattern = cfg.datePattern()
            dateView?.text = SimpleDateFormat(datePattern, Locale.getDefault()).format(now)
            clockView?.text = SimpleDateFormat(cfg.clockPattern(), Locale.getDefault()).format(now)
            // v2.1: the network element is a GLYPH now (core/WifiLabel
            // mapping) — symbol + dimming, no SSID/word text.
            networkView?.let { glyph ->
                val kind = networkKind()
                glyph.text = WifiLabel.glyph(kind)
                glyph.alpha = WifiLabel.glyphAlpha(kind)
            }
        } catch (e: Exception) {
            // Clock is cosmetic; never let it take the launcher down.
        }
    }

    /**
     * The active network's transport kind for the status glyph.
     * (v1.3–v2.0 showed the SSID as text next to the glyph; v2.1's
     * reorganization keeps the bar glanceable — glyph only. The
     * WifiLabel text mapping survives in core for the sim and any
     * future surface that wants words.)
     */
    private fun networkKind(): WifiLabel.Kind {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ?: return WifiLabel.Kind.OFFLINE
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WifiLabel.Kind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    WifiLabel.Kind.ETHERNET
                else -> WifiLabel.Kind.OTHER
            }
        } catch (e: Exception) {
            WifiLabel.Kind.OFFLINE
        }
    }

    /**
     * v3.2 (ask 43): the rebuild-time half of weather — paints
     * the CACHED text only. No network, no rebuild trigger.
     */
    private fun updateWeatherWidget() {
        if (prefs.safeMode) return
        // The cached text feeds BOTH the status-bar widget (when
        // present) and the weather background (when that wallpaper
        // kind is chosen).
        val weatherView = findWeatherView()
        if (weatherView == null && prefs.wallpaperKind != "weather") return
        val cached = prefs.cachedWeather
        if (weatherView != null && cached.isNotEmpty()) weatherView.text = cached
        setWeatherVisibility(if (cached.isEmpty()) View.GONE else View.VISIBLE)
    }

    /**
     * v3.2 (ask 43): the fetch half of weather — called ONLY
     * from the visible-home clock tick (housekeeping) while
     * stale, or from the explicit Refresh home data control
     * (force). Never from open/resume/rebuild. The landing
     * updates the widget text in place; a weather-wallpaper
     * background swap rebuilds, but that rebuild happens while
     * the home is already visible (a wallpaper change, not an
     * open-path refresh) and re-renders purely from cache.
     */
    private fun maybeFetchWeather(force: Boolean = false) {
        if (!::prefs.isInitialized || prefs.safeMode) return
        val cached = prefs.cachedWeather
        val stale = System.currentTimeMillis() - prefs.lastWeatherFetch > 30 * 60 * 1000
        if (!force && !com.mizan.launcher.core.RefreshPolicy.weatherFetchAllowed(stale, true)) return
        Weather.fetch(prefs.weatherLat, prefs.weatherLon, prefs.weatherUnits) { snap ->
            runOnUiThread {
                try {
                    if (snap != null) {
                        prefs.cachedWeather = snap.text
                        prefs.weatherData = Weather.encodeSnapshot(snap)
                        prefs.lastWeatherFetch = System.currentTimeMillis()
                        val v = findWeatherView()
                        if (v != null) {
                            v.text = snap.text
                            setWeatherVisibility(View.VISIBLE)
                        }
                        // Fresh conditions may change the background.
                        if (prefs.wallpaperKind == "weather") rebuild()
                    } else if (cached.isEmpty()) {
                        setWeatherVisibility(View.GONE) // hide quietly offline (fail-open)
                    }
                } catch (e: Exception) {
                    // Widget/background only — ignore.
                }
            }
        }
    }

    private fun findWeatherView(): TextView? {
        return try {
            val root = window?.decorView as? ViewGroup ?: return null
            findTagged(root, "weather_text") as? TextView
        } catch (e: Exception) { null }
    }

    /**
     * v2.1: weather visibility applies to the tag-"weather" WRAPPER
     * (text + its dot separator), so an empty weather never leaves a
     * stray separator in the info cluster. Falls back to the text
     * view itself when no wrapper exists.
     */
    private fun setWeatherVisibility(visibility: Int) {
        try {
            val root = window?.decorView as? ViewGroup ?: return
            val wrap = findTagged(root, "weather")
            if (wrap != null) {
                wrap.visibility = visibility
            } else {
                findWeatherView()?.visibility = visibility
            }
        } catch (e: Exception) { /* cosmetic only */ }
    }

    private fun findTagged(group: ViewGroup, tag: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.tag == tag) return child
            if (child is ViewGroup) findTagged(child, tag)?.let { return it }
        }
        return null
    }

    /**
     * App list cache so resume-rebuilds don't re-query
     * PackageManager every time.
     *
     * v2.5: stale-while-revalidate. get() used to run the FULL
     * PackageManager scan (queryIntentActivities + a label and
     * icon load per app) synchronously on the caller's thread
     * whenever the 30 s TTL lapsed — and rebuild() runs on every
     * onResume, so any resume past the TTL paid the whole scan on
     * the main thread inside the home build. Now get() NEVER
     * queries: it returns the last-good list immediately (empty
     * on the very first cold start) and a single-flight
     * background refresh swaps in the fresh list and rebuilds
     * once when the package set actually changed — the same
     * pattern as Live TV / Most Used on resume.
     */
    object AppCache {
        private var cached: List<AppEntry>? = null
        private var at: Long = 0

        @Volatile
        private var loading = false

        fun get(activity: Activity): List<AppEntry> {
            val c = cached
            if (c == null || System.currentTimeMillis() - at >= 30_000) {
                refreshAsync(activity)
            }
            return c ?: emptyList()
        }

        /** v3.2 (ask 43): explicit app-list refresh (Refresh home data). Single-flight, as usual. */
        fun refreshNow(activity: Activity) {
            refreshAsync(activity)
        }

        private fun refreshAsync(activity: Activity) {
            synchronized(this) {
                if (loading) return
                loading = true
            }
            val pm = activity.packageManager
            val ownPkg = activity.packageName
            kotlin.concurrent.thread(name = "mizan-apps") {
                val fresh = try {
                    AppLoader.loadApps(pm, ownPkg)
                } catch (e: Exception) {
                    null
                }
                synchronized(this) { loading = false }
                if (fresh != null) {
                    val oldPkgs: List<String> = cached?.map { it.pkg } ?: emptyList()
                    val changed = fresh.map { it.pkg } != oldPkgs
                    cached = fresh
                    at = System.currentTimeMillis()
                    DiagCache.appsCount = fresh.size
                    if (changed && !activity.isFinishing && !activity.isDestroyed) {
                        activity.runOnUiThread {
                            (activity as? MainActivity)?.let { host ->
                                if (!host.isFinishing && !host.isDestroyed) host.rebuild()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Values the diagnostics screen reads (apps/inputs/watchnext counts). */
    object DiagCache {
        var appsCount: Int = 0
        var inputsCount: Int = 0
        var watchNextCount: Int = 0
        /** v1.4: Now Playing diagnostics — grant state + sessions with a title. */
        var nowPlayingGranted: Boolean = false
        var nowPlayingCount: Int = 0
    }
}
