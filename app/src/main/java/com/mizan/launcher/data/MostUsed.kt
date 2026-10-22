package com.mizan.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.mizan.launcher.core.MostUsedLogic

/**
 * v2.0: the Most Used row's Android side. Usage comes from
 * UsageStatsManager EVENTS (chosen over aggregated usageStats totals:
 * events give both an exact per-package resume count AND the
 * last-used timestamp the pure tie-break needs; totals only carry a
 * foreground-time sum, which ranks long-idle apps above daily ones).
 *
 * Everything is gated on the Usage Access app-op: without it,
 * queryEvents returns an empty stream (no crash, no data) — the row
 * hides and Settings offers the grant link. All calls fail-open.
 */
object MostUsed {

    /** True when the user has granted Usage Access to this app. */
    fun isGranted(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    // v2.0 cold-start rule: the home's first frame never waits on a
    // UsageStats query (30 days of events can take a moment on a slow
    // box). The row renders from this volatile cache; refreshAsync
    // fills it on a background thread and the caller rebuilds.
    @Volatile
    private var statsCache: List<MostUsedLogic.Stat>? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The last computed tallies, or null until the first refresh lands. */
    fun cachedStats(): List<MostUsedLogic.Stat>? = statsCache

    /** Recompute the tallies off the UI thread; [onDone] on the main thread. */
    fun refreshAsync(ctx: Context, labels: Map<String, String>, onDone: (List<MostUsedLogic.Stat>) -> Unit = {}) {
        val appCtx = ctx.applicationContext
        kotlin.concurrent.thread(name = "mizan-mostused") {
            val stats = topStats(appCtx, labels)
            statsCache = stats
            mainHandler.post {
                try { onDone(stats) } catch (e: Exception) {
                    // A caller's rebuild must never kill the refresh.
                }
            }
        }
    }

    /**
     * Raw per-package tallies over the last [days] days: ACTIVITY_RESUMED
     * event counts + last event time, labelled from [labels] (packages
     * without a label fall back to the package name; core/MostUsedLogic
     * filters to launchable, non-hidden apps and ranks them).
     * Synchronous — call from a background thread (see refreshAsync).
     */
    fun topStats(ctx: Context, labels: Map<String, String>, days: Int = 30): List<MostUsedLogic.Stat> {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()
            val end = System.currentTimeMillis()
            val begin = end - days.toLong() * 24L * 60L * 60L * 1000L
            val events = usm.queryEvents(begin, end) ?: return emptyList()
            val resumedType = if (Build.VERSION.SDK_INT >= 29) {
                UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val counts = HashMap<String, Int>()
            val last = HashMap<String, Long>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != resumedType) continue
                val pkg = event.packageName ?: continue
                counts[pkg] = (counts[pkg] ?: 0) + 1
                val t = event.timeStamp
                if (t > (last[pkg] ?: 0L)) last[pkg] = t
            }
            counts.map { (pkg, count) ->
                MostUsedLogic.Stat(pkg, count, last[pkg] ?: 0L, labels[pkg] ?: pkg)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
