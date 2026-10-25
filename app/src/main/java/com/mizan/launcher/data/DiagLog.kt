package com.mizan.launcher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.BreadcrumbRing
import com.mizan.launcher.core.DiagLogs
import com.mizan.launcher.core.DiagWatchdog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Self-diagnostics plumbing (v2.5, spec §25) — the Android half
 * of core/Diag. Installed once from [com.mizan.launcher.App]:
 *
 *  - a default uncaught-exception handler that appends the crash
 *    (timestamp, thread, full stack, breadcrumb trail) to
 *    filesDir/crash.log and then CHAINS to the previous handler,
 *    so the system's own crash handling is untouched;
 *  - a main-thread WATCHDOG: a daemon thread posts a heartbeat
 *    runnable to the main looper every ~2 s; when the heartbeat
 *    goes stale (>4 s, core/DiagWatchdog) the main thread is
 *    stuck — its stack (plus every other thread's name/state/top
 *    frames and the breadcrumb trail) is dumped to
 *    filesDir/anr.log, once per stall episode. Android's own ANR
 *    record names no stack we are allowed to read on Haythem's
 *    TV (the dropbox traces are permission-denied), so the app
 *    captures its own;
 *  - the breadcrumb trail (core/BreadcrumbRing): meaningful user
 *    actions, kept in memory and persisted best-effort to a tiny
 *    file so the trail survives the process death it explains.
 *
 * Both logs are event-capped (anr: 3, crash: 5) and char-capped
 * by core/DiagLogs — they can never grow unboundedly. Everything
 * here is fail-open and cheap: a diagnostics failure must never
 * disturb the launcher.
 */
object DiagLog {

    private const val CRASH_FILE = "crash.log"
    private const val ANR_FILE = "anr.log"
    private const val CRUMB_FILE = "diag_crumbs.txt"
    private const val ANR_MAX_EVENTS = 3
    private const val CRASH_MAX_EVENTS = 5

    @Volatile
    private var installed = false

    @Volatile
    private var appCtx: Context? = null

    private val ring = BreadcrumbRing()

    /** Serial, tiny writes only (crumb file + anr events). */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mizan-diag-io").apply { isDaemon = true }
    }

    @Volatile
    private var lastBeatMs: Long = 0L

    @Volatile
    private var stallDumped = false

    // ------------------------------------------------------------ cold start (v2.8)

    private const val K_STARTUP_FIRST = "startup_first_frame_ms"
    private const val K_STARTUP_FULL = "startup_full_ms"

    /**
     * Process-start uptime (SystemClock.uptimeMillis at App.onCreate
     * entry — the closest honest "process start" an app can read).
     * Every startup milestone is a delta from this. 0 = never marked.
     */
    @Volatile
    private var startUptimeMs: Long = 0L

    @Volatile
    private var firstFrameMs: Long = -1L

    @Volatile
    private var fullMs: Long = -1L

    private fun startupPrefs(ctx: Context) =
        ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    /** Milliseconds since [markStartupStart]; -1 before it runs. */
    private fun startupElapsed(): Long =
        if (startUptimeMs <= 0L) -1L
        else android.os.SystemClock.uptimeMillis() - startUptimeMs

    /**
     * The cold-start clock's zero — called as the FIRST statement of
     * App.onCreate. Also drops a breadcrumb, so the trail of a slow
     * start reads as a timed sequence (app → main → first frame →
     * home complete).
     */
    fun markStartupStart() {
        if (startUptimeMs <= 0L) {
            startUptimeMs = android.os.SystemClock.uptimeMillis()
        }
        note("startup: app")
    }

    /** A named startup milestone: stored when final, always breadcrumbed with its delta. */
    fun markStartup(stage: String) {
        val at = startupElapsed()
        note(if (at >= 0L) "startup: $stage +$at ms" else "startup: $stage")
    }

    /**
     * v3.2: the fine-grained stage list behind the diagnostics
     * `startup stages:` line. Unlike markStartup this does NOT
     * breadcrumb (a stage per rebuild would spam the crumb ring);
     * it only appends to the capped list, so the next diagnostics
     * photo can name which stage ate the first-frame time
     * (provider read, matching, composition, decode, wallpaper).
     */
    private val stages = com.mizan.launcher.core.StartupStages()

    /** Record one composition stage with its process-start delta. Cheap, never IO. */
    fun markStage(name: String) {
        val at = startupElapsed()
        if (at >= 0L) stages.record(name, at)
        // v3.5 (spec §35): the death record's "last stage" feed —
        // an async apply() to the tiny death prefs, best-effort.
        // When the process later dies with no trim warning, the
        // `last death: unknown` line names this stage.
        try {
            appCtx?.let { DeathStore.updateStage(it, name) }
        } catch (e: Exception) {
            // Evidence only.
        }
    }

    /** Compact diagnostics rendering ("" when nothing recorded yet). */
    fun stagesText(): String = stages.text()

    /** v3.5: the last recorded stage name, or null (feeds the trim record + unknown line). */
    fun lastStage(): String? = stages.names().lastOrNull()

    /** v3.5: whether this process drew its first frame yet (feeds the trim record's before/after flag). */
    fun firstFrameRecorded(): Boolean = firstFrameMs >= 0L

    /** The staged shell's first frame was drawn (MainActivity's pre-draw probe). */
    fun recordFirstFrame(ctx: Context) {
        if (firstFrameMs >= 0L) return
        val at = startupElapsed()
        if (at < 0L) return
        firstFrameMs = at
        markStartup("first frame")
        io.execute {
            try {
                startupPrefs(ctx).edit().putLong(K_STARTUP_FIRST, at).apply()
            } catch (e: Exception) {
                // Persistence is best-effort; the in-memory value serves this process.
            }
        }
    }

    /** The fully-warmed home's frame was drawn — the cold start is over. */
    fun recordHomeComplete(ctx: Context) {
        if (fullMs >= 0L) return
        val at = startupElapsed()
        if (at < 0L) return
        fullMs = at
        markStartup("home complete")
        io.execute {
            try {
                startupPrefs(ctx).edit().putLong(K_STARTUP_FULL, at).apply()
            } catch (e: Exception) {
                // Best-effort, as above.
            }
        }
    }

    /** The diagnostics values: this process's timings, else the persisted last cold start. */
    fun startupFirstMs(ctx: Context): Long {
        if (firstFrameMs >= 0L) return firstFrameMs
        return try {
            PrefsSafe.long(startupPrefs(ctx), K_STARTUP_FIRST, -1L)
        } catch (e: Exception) {
            -1L
        }
    }

    /** See [startupFirstMs]. */
    fun startupFullMs(ctx: Context): Long {
        if (fullMs >= 0L) return fullMs
        return try {
            PrefsSafe.long(startupPrefs(ctx), K_STARTUP_FULL, -1L)
        } catch (e: Exception) {
            -1L
        }
    }

    fun install(ctx: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            appCtx = ctx.applicationContext
        }
        installCrashHandler()
        // Restore the previous process's breadcrumb trail (a
        // freeze kills the process; its last actions must survive
        // for the next launch's diagnostics to mean anything).
        io.execute {
            try {
                val f = File(ctx.filesDir, CRUMB_FILE)
                if (f.isFile) ring.restore(f.readText())
            } catch (e: Exception) {
                // A lost trail is a diagnostics gap, never a failure.
            }
        }
        startWatchdog()
    }

    // ------------------------------------------------------------ breadcrumbs

    /**
     * Record one meaningful user action. Cheap: an in-memory
     * ring add plus a coalesced async persist of the tiny trail
     * file. Safe to call from any thread.
     */
    fun note(label: String) {
        try {
            ring.add(System.currentTimeMillis(), label)
            val ctx = appCtx ?: return
            val encoded = ring.encode()
            io.execute {
                try {
                    File(ctx.filesDir, CRUMB_FILE).writeText(encoded)
                } catch (e: Exception) {
                    // Best-effort persistence only.
                }
            }
        } catch (ignored: Throwable) {
            // Diagnostics must never break the action it records.
        }
    }

    /** The current trail, oldest first, as "HH:mm:ss label" lines. */
    fun crumbs(): List<String> = ring.lines()

    // ------------------------------------------------------------ crash handler

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val entry = buildString {
                    append(stamp(System.currentTimeMillis()))
                    append(" CRASH thread=").append(t.name).append('\n')
                    append(sw.toString())
                    append(crumbsBlock())
                }
                appCtx?.let { appendEventSync(it, CRASH_FILE, entry, CRASH_MAX_EVENTS) }
            } catch (ignored: Throwable) {
                // The handler must never throw before chaining.
            }
            // v3.0: chain to the system handler (which kills the
            // process) ONLY for the main thread. Before this, ANY
            // worker's uncaught throwable — a fetch thread, the
            // artwork decoder — took the whole launcher down with
            // it. Every worker's state is finally-reset or
            // disk-atomic (ingest flags, kick in-flight flags,
            // executor self-replacement), so a dead worker degrades
            // its feature and is already logged above; a main-thread
            // death keeps the platform behavior (the process is
            // unusable anyway, and the startup-health counter +
            // safe shell handle a repeat).
            val isMain = try {
                t === Looper.getMainLooper().thread
            } catch (ignored: Throwable) {
                true
            }
            if (isMain) {
                prev?.uncaughtException(t, e)
            }
        }
    }

    // ------------------------------------------------------------ watchdog

    private fun startWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        lastBeatMs = System.currentTimeMillis()
        thread(name = "mizan-watchdog", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(2_000L)
                } catch (e: InterruptedException) {
                    return@thread
                }
                // v3.0: the judge block is Throwable-guarded — an
                // unguarded post/judge failure used to kill the
                // watchdog thread silently, and with it all stall
                // evidence for the rest of the process's life.
                try {
                    mainHandler.post { lastBeatMs = System.currentTimeMillis() }
                    // Give the posted beat a beat to land before judging.
                    try {
                        Thread.sleep(250L)
                    } catch (e: InterruptedException) {
                        return@thread
                    }
                    val now = System.currentTimeMillis()
                    if (DiagWatchdog.isStale(lastBeatMs, now)) {
                        if (!stallDumped) {
                            stallDumped = true
                            dumpStall(now)
                        }
                    } else {
                        stallDumped = false
                    }
                } catch (ignored: Throwable) {
                    // Keep watching.
                }
            }
        }
    }

    /**
     * The main thread is stuck: capture its stack (and a summary
     * of every other thread) plus the breadcrumb trail into
     * anr.log. Runs on the watchdog thread; the dump text is
     * bounded (main stack in full, other threads name/state +
     * top 3 frames) and the write rides the serial IO executor.
     */
    private fun dumpStall(nowMs: Long) {
        try {
            val ctx = appCtx ?: return
            val mainThread = Looper.getMainLooper().thread
            val traces = Thread.getAllStackTraces()
            val entry = buildString {
                append(stamp(nowMs)).append(" MAIN THREAD STUCK (no heartbeat for >4s)\n")
                append("--- main thread ---\n")
                val mainStack = traces[mainThread]
                if (mainStack != null) {
                    for (frame in mainStack) append("    at ").append(frame).append('\n')
                } else {
                    append("    (no stack captured)\n")
                }
                append("--- other threads ---\n")
                for ((t, stack) in traces) {
                    if (t === mainThread) continue
                    append(t.name).append(" [").append(t.state).append("]\n")
                    for (i in 0 until minOf(3, stack.size)) {
                        append("    at ").append(stack[i]).append('\n')
                    }
                }
                append(crumbsBlock())
            }
            io.execute {
                try {
                    appendEventSync(ctx, ANR_FILE, entry, ANR_MAX_EVENTS)
                } catch (e: Exception) {
                    // The dump is best-effort by definition.
                }
            }
        } catch (ignored: Throwable) {
            // The watchdog must never become the problem it watches for.
        }
    }

    // ------------------------------------------------------------ log files

    /** Read-modify-write one event into a log file (bounded by core/DiagLogs). */
    private fun appendEventSync(ctx: Context, name: String, entry: String, maxEvents: Int) {
        val f = File(ctx.filesDir, name)
        val existing = try {
            if (f.isFile) f.readText() else ""
        } catch (e: Exception) {
            ""
        }
        f.writeText(DiagLogs.appendEvent(existing, entry, maxEvents))
    }

    /** The diagnostics tail of the crash log ("" when none). Small bounded file — safe to read anywhere. */
    fun crashTail(ctx: Context, maxLines: Int): String = tail(ctx, CRASH_FILE, maxLines)

    /** The diagnostics tail of the watchdog freeze log ("" when none). */
    fun anrTail(ctx: Context, maxLines: Int): String = tail(ctx, ANR_FILE, maxLines)

    private fun tail(ctx: Context, name: String, maxLines: Int): String = try {
        val f = File(ctx.filesDir, name)
        if (f.isFile) DiagLogs.tailLines(f.readText(), maxLines) else ""
    } catch (e: Exception) {
        ""
    }

    private fun crumbsBlock(): String {
        val lines = ring.lines()
        if (lines.isEmpty()) return ""
        return buildString {
            append("--- recent actions ---\n")
            for (line in lines) append(line).append('\n')
        }
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
