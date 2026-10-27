package com.mizan.launcher.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.data.DataFactory
import com.mizan.launcher.data.DiagnosticsRepositoryImpl
import com.mizan.launcher.data.DiscoveryWorker
import com.mizan.launcher.data.HomeCacheDisk
import com.mizan.launcher.data.HomeWatcherProbeImpl
import com.mizan.launcher.data.NowPlayingRepositoryImpl
import com.mizan.launcher.data.PlaybackRepositoryImpl
import com.mizan.launcher.data.PrefsStore
import com.mizan.launcher.data.SpeedUpRunner
import com.mizan.launcher.data.StartupHealthStore
import com.mizan.launcher.data.TrackerRefreshWorker
import com.mizan.launcher.domain.EpisodeRef
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.ui.UiWiring

/**
 * Application entry point (Phase 2D integration).
 *
 * Order matters (MAP §2): diagnostics install FIRST (crash
 * handler + main-thread watchdog + breadcrumb), then the startup
 * clock + startup-health launch (counter via the prefs funnel;
 * the safe-shell decision is fixed here and read by
 * MainActivity), then early SharedPreferences touch + warms on a
 * worker thread (legacy-prefs purge via PrefsStore init, home
 * -cache mirror metadata, slim metadata — all bounded,
 * non-parsing reads), then the background units that are safe to
 * kick from the Application: tracker local refresh, discovery
 * refresh (12 h TTL, fails open), missed recompute via the
 * refresh worker. IPTV ingest is NEVER kicked here (on request
 * only). No service, worker, alarm, widget, or notification is
 * started from here — background work is worker threads kicked
 * from lifecycle only, and there are no foreground services
 * anywhere in the app.
 *
 * Repository wiring: one instance of each implementation is
 * built here and handed to UiWiring; MainActivity re-calls
 * [wire] defensively (idempotent) so a direct activity launch
 * still has repositories. The auto-mark application (data gap
 * (e)): PlaybackRepositoryImpl.onAutoMark is wired to the SAME
 * tracker instance UiWiring uses, parsing the library mark token
 * ("tv|<id>|<s>|<e>" / "movie|<id>") into a tracker write.
 * The NowPlaying listener component (data gap (f)) and the
 * GlobalActionsGateway delegate registration point (data gap
 * (g): the live delegate itself is registered by
 * LauncherService on connect; until then the stub fails open)
 * are both set here.
 */
class App : Application() {

    companion object {
        /** Process-start clock; MainActivity measures first frame against it. */
        @Volatile var startupT0Ms: Long = 0L

        /** True once MainActivity has rendered its first full home. */
        @Volatile var afterFirstFrame: Boolean = false

        /** Safe-shell decision fixed at Application start (MAP §2). */
        @Volatile var safeShellArmed: Boolean = false

        @Volatile private var wired = false
        @Volatile var diagnostics: DiagnosticsRepositoryImpl? = null

        /** Idempotent repository wiring for App + MainActivity fallback. */
        fun wire(ctx: Context) {
            if (wired) return
            synchronized(this) {
                if (wired) return
                val appCtx = ctx.applicationContext
                val uc = DataFactory.useCases(appCtx)
                val tracker = DataFactory.tracker(appCtx)
                val playback = DataFactory.playback(appCtx) as PlaybackRepositoryImpl
                // Auto-mark application: token -> tracker write.
                playback.onAutoMark = { item ->
                    try {
                        val token = item.libraryMarkToken ?: ""
                        val parts = token.split("|")
                        when {
                            parts.size >= 4 && parts[0] == "tv" -> {
                                val id = parts[1].toLongOrNull()
                                val s = parts[2].toIntOrNull()
                                val e = parts[3].toIntOrNull()
                                if (id != null && s != null && e != null) {
                                    tracker.markEpisodeWatched(EpisodeRef(id, s, e, null), true)
                                }
                            }
                            parts.size >= 2 && parts[0] == "movie" -> {
                                parts[1].toLongOrNull()?.let { tracker.markMovieWatched(it, true) }
                            }
                            else -> {
                                // Tokenless or unknown shape: never mark.
                            }
                        }
                    } catch (ignored: Throwable) { }
                }
                UiWiring.tracker = tracker
                UiWiring.tmdb = DataFactory.tmdb(appCtx)
                UiWiring.iptv = DataFactory.iptv(appCtx)
                UiWiring.apps = DataFactory.apps(appCtx)
                UiWiring.settings = DataFactory.settings(appCtx)
                UiWiring.weather = DataFactory.weather(appCtx)
                UiWiring.playback = playback
                UiWiring.nowPlaying = DataFactory.nowPlaying(appCtx)
                UiWiring.diagnostics = diagnostics ?: DataFactory.diagnostics(appCtx)
                UiWiring.lock = DataFactory.lock(appCtx)
                UiWiring.upNext = uc.getUpNext
                UiWiring.missed = uc.getMissed
                UiWiring.hero = uc.getHero
                UiWiring.forYou = uc.getForYou
                UiWiring.trending = uc.getTrending
                UiWiring.playTitle = DataFactory.playTitle(appCtx)
                UiWiring.markEpisode = uc.markEpisode
                UiWiring.searchTitles = uc.searchTitles
                UiWiring.nowPlayingUseCase = uc.getNowPlaying
                UiWiring.forecast = uc.getForecast
                UiWiring.diagnosticsUseCase = uc.getDiagnostics
                UiWiring.folders = uc.manageFolders
                UiWiring.homeWatcher = HomeWatcherProbeImpl(appCtx)
                UiWiring.onSpeedUp = { SpeedUpRunner(appCtx).run() }
                // Settings export/import bridge (data-layer JSON
                // shape; the UI layer cannot import data classes).
                UiWiring.exportSettings = { PrefsStore(appCtx).exportSettingsJson() }
                UiWiring.importSettings = { json -> PrefsStore(appCtx).importSettingsJson(json) }
                // "Next wallpaper" jump (rotation index advance).
                UiWiring.nextWallpaper = {
                    try {
                        com.mizan.launcher.data.WallpaperRotator.nextNow(PrefsStore(appCtx))?.url
                    } catch (ignored: Throwable) { null }
                }
                UiWiring.imageLoader = { view, url -> ImageLoader.load(view, url) }
                // NowPlaying listener component registration (data gap (f)).
                NowPlayingRepositoryImpl.listenerComponent =
                    ComponentName(appCtx, NowPlayingListener::class.java)
                wired = true
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastBeatMs: Long = 0L
    @Volatile private var anrNotedForEpisode = false

    override fun onCreate() {
        super.onCreate()
        startupT0Ms = System.currentTimeMillis()
        // Diagnostics FIRST, before anything else can throw.
        val diag = DiagnosticsRepositoryImpl(this)
        diagnostics = diag
        diag.coldStart = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { diag.noteCrash("uncaught:${thread.name}", error.message) } catch (ignored: Throwable) { }
            previousHandler?.uncaughtException(thread, error)
        }
        // Main-thread watchdog: heartbeat every 1 s on the main
        // thread; a checker thread notes one ANR entry per stall
        // episode once the heartbeat is stale past 3.9 s
        // (core DiagWatchdog threshold, via the impl helper).
        val beat = object : Runnable {
            override fun run() {
                lastBeatMs = System.currentTimeMillis()
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(beat)
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    val stale = diag.isWatchdogStale(lastBeatMs, System.currentTimeMillis())
                    if (stale && !anrNotedForEpisode) {
                        anrNotedForEpisode = true
                        diag.noteAnr("main-thread stall")
                    } else if (!stale) {
                        anrNotedForEpisode = false
                    }
                } catch (ignored: Throwable) { return@Thread }
            }
        }.apply { isDaemon = true }.start()

        // Startup-health launch: counter bump + safe-shell decision.
        try {
            val store = StartupHealthStore(this)
            store.onLaunch()
            safeShellArmed = store.isSafeShell()
        } catch (ignored: Throwable) { }
        diag.recordBreadcrumb("app_created")

        wire(this)

        // Worker-thread warms + background units (single-flight,
        // bounded, fail-open; never IPTV ingest, never on the main
        // thread). PrefsStore init performs the v2.4 legacy-prefs
        // purge; HomeCacheDisk construction touches the mirror dir.
        Thread {
            try {
                PrefsStore(this)
                HomeCacheDisk(this).diagLine()
                TrackerRefreshWorker(this).refresh()
                DiscoveryWorker(this).invoke(false)
                diag.recordBreadcrumb("app_warms_done")
            } catch (ignored: Throwable) { }
        }.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Death evidence: stage + after-first-frame flag only.
        try {
            diagnostics?.lastDeathStage = "trim:$level afterFirstFrame=$afterFirstFrame"
        } catch (ignored: Throwable) { }
    }
}
