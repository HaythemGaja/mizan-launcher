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
            prev?.uncaughtException(t, e)
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
