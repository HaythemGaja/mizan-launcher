package com.mizan.launcher.data

import android.app.ActivityManager
import android.content.Context
import com.mizan.launcher.core.ColdChain
import com.mizan.launcher.core.HomeCache
import com.mizan.launcher.core.NeverList
import com.mizan.launcher.core.SpeedUp
import com.mizan.launcher.core.StartupHealth
import com.mizan.launcher.core.TrackerSlim
import java.io.File

/**
 * Plain background work units (MAP §2). No scheduling here —
 * the integration worker wires lifecycle (Activity/Application
 * threads, Handler runnables). Every unit is drivable without
 * any ui/app class. No WorkManager / AlarmManager /
 * JobScheduler / AppWidget / NotificationManager usage anywhere.
 */

// ---- Startup health (two uncleared starts -> safe shell on third) ----
class StartupHealthStore(ctx: Context) {
    private val prefs = PrefsStore(ctx)
    fun onLaunch(): Int {
        val next = StartupHealth.onLaunch(unclearedStarts())
        PrefsSafe.setInt(prefs.sp, "startup_uncleared", next)
        return next
    }
    fun unclearedStarts(): Int = PrefsSafe.int(prefs.sp, "startup_uncleared", 0)
    fun clear() = PrefsSafe.setInt(prefs.sp, "startup_uncleared", StartupHealth.onCleared())
    fun isSafeShell(): Boolean = StartupHealth.modeFor(unclearedStarts()) == StartupHealth.Mode.SAFE_SHELL
    fun diagLine(): String = StartupHealth.diagnosticsLine(unclearedStarts(), isSafeShell())
    /** A whole-home fallback on the first build HOLDS the clear. */
    fun holdClearOnFallback() { /* clear is simply not called; counter stays armed */ }
}

// ---- Cold chain runner: sequential post-survival steps, cancellable ----
class ColdChainRunner(ctx: Context) {
    private val chain = ColdChain()
    private val tracker = TrackerRepositoryImpl(ctx)
    private val discovery = DiscoveryWorker(ctx)
    private val engine = WatchMatchEngine(ctx)
    @Volatile var cancelled = false

    fun arm(reason: ColdChain.Reason) = chain.arm(reason)
    fun cancel() { cancelled = true; chain.cancel() }
    fun diag(): String = chain.diag()

    /** Run one step if allowed; returns false when cancelled/not allowed. */
    fun runStep(survived: Boolean, onDemand: Boolean = false): Boolean {
        if (cancelled) return false
        val step = chain.next() ?: return false
        if (!chain.canStart(step, survived, onDemand)) return false
        if (!chain.start(step, survived, onDemand)) return false
        try {
            when (step) {
                ColdChain.Step.TRACKER_FULL -> tracker.recomputeMissed(null)
                ColdChain.Step.MATCH -> engine.computeMatches()
                ColdChain.Step.DISCOVERY -> discovery.invoke(false)
            }
        } catch (ignored: Throwable) { }
        chain.complete(step)
        return true
    }
    fun runAll(survived: Boolean) {
        while (!cancelled && chain.next() != null) {
            if (!runStep(survived)) break
        }
    }
}

// ---- Tracker refresh worker: local-only, single-flight ----
class TrackerRefreshWorker(ctx: Context) {
    private val tracker = TrackerRepositoryImpl(ctx)
    @Volatile private var inFlight = false
    @Volatile var loading: Boolean = false

    /** Local-only refresh from the on-disk store (provider contact
     *  closed after the v3.7 one-time import flag). */
    fun refresh() {
        if (inFlight) return
        inFlight = true; loading = true
        try {
            tracker.recomputeMissed(null)
            // Prune the pending overlay against the current store
            // snapshot (convergence, part-by-part) — the overlay
            // itself is the optimistic layer; the store is local.
        } finally {
            loading = false; inFlight = false
        }
    }
}

// ---- Home cache mirror + slim snapshot ----
class HomeCacheDisk(ctx: Context) {
    private val context = ctx.applicationContext
    private val dir: File = File(context.filesDir, "home_cache").apply { mkdirs() }
    private val slimFile: File = File(dir, "tracker_slim.json")

    fun readSlim(expectedFingerprint: String): TrackerSlim.Data? = try {
        if (!slimFile.isFile || slimFile.length() > TrackerSlim.MAX_BYTES) null
        else TrackerSlim.decode(slimFile.readText(), expectedFingerprint)
    } catch (e: Throwable) { null }

    fun writeSlim(data: TrackerSlim.Data, fingerprint: String): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val json = TrackerSlim.encode(data.upNext, data.cont, data.movies, data.tracked,
                data.forYou, now, now, now, fingerprint, data.missed) ?: return false
            if (HomeCache.containsCredentialPattern(json)) return false
            val tmp = File(dir, "tracker_slim.json.tmp")
            tmp.writeText(json)
            tmp.renameTo(slimFile)
        } catch (e: Throwable) { false }
    }

    fun diagLine(): String {
        val entries = if (slimFile.isFile)
            listOf(HomeCache.Entry("tracker_slim", slimFile.lastModified(), slimFile.length()))
        else emptyList()
        return HomeCache.diagLine(entries.map { it.section }.toSet(),
            entries.minOfOrNull { it.fetchedAtMs } ?: 0L,
            entries.sumOf { it.bytes }, System.currentTimeMillis())
    }
}

// ---- Speed Up: signal OTHER apps' background processes to exit ----
class SpeedUpRunner(ctx: Context) {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    private val apps = AppsRepositoryImpl(ctx)

    /** Returns the result line stored in speedup_last_result. */
    fun run(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            fun avail(): Long {
                if (am == null) return 0L
                val mi = ActivityManager.MemoryInfo()
                return try { am.getMemoryInfo(mi); mi.availMem } catch (e: Throwable) { 0L }
            }
            val before = avail()
            val candidates = apps.getAllApps().map { SpeedUp.Candidate(it.packageName, it.isSystem) }
            val killList = SpeedUp.killList(candidates, context.packageName, null)
            if (am != null) {
                for (pkg in killList) {
                    try { am.killBackgroundProcesses(pkg) } catch (ignored: Throwable) { }
                }
            }
            val after = avail()
            val line = "Signalled ${killList.size} apps · free RAM ${before / 1_048_576} MB → ${after / 1_048_576} MB"
            prefs.setSpeedUpLastResult(line)
            line
        } catch (e: Throwable) {
            val line = "Speed Up unavailable"
            prefs.setSpeedUpLastResult(line)
            line
        }
    }
    fun neverListContains(pkg: String): Boolean = NeverList.isNever(pkg)
}
