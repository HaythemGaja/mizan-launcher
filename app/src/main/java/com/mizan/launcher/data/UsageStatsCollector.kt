package com.mizan.launcher.data

import android.app.usage.UsageStatsManager
import android.content.Context
import com.mizan.launcher.core.MostUsedLogic
import com.mizan.launcher.domain.MostUsedApp

/**
 * Most Used from usage stats (MAP §5): grant-gated; without the
 * PACKAGE_USAGE_STATS grant it returns empty (fail open, never
 * invented counts). Ordering is core/MostUsedLogic: count desc,
 * recency breaks ties, name breaks recency ties; zero counts
 * dropped; hidden/non-launchable excluded by the caller.
 */
object UsageStatsCollector {
    fun collect(ctx: Context): List<MostUsedApp> {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()
            val end = System.currentTimeMillis()
            val start = end - 30L * 24 * 3600 * 1000
            // Count ACTIVITY_RESUMED events per package over 30 days
            // (the old data/MostUsed tally), not foreground time.
            val pm = ctx.packageManager
            val counts = HashMap<String, Int>()
            val lastUsed = HashMap<String, Long>()
            val events = usm.queryEvents(start, end)
            val ev = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType != android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = ev.packageName ?: continue
                counts[pkg] = (counts[pkg] ?: 0) + 1
                lastUsed[pkg] = ev.timeStamp
            }
            val allowed = HashSet<String>()
            val labels = HashMap<String, String>()
            for (pkg in counts.keys) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getLaunchIntentForPackage(pkg) ?: continue
                    allowed.add(pkg)
                    labels[pkg] = pm.getApplicationLabel(ai).toString()
                } catch (ignored: Throwable) { }
            }
            val mapped = counts.map { (pkg, count) ->
                MostUsedLogic.Stat(pkg, count, lastUsed[pkg] ?: 0L, labels[pkg] ?: pkg)
            }
            MostUsedLogic.topUsed(mapped, allowed, emptySet()).map {
                MostUsedApp(it.pkg, it.label, it.count.toLong())
            }
        } catch (e: Throwable) { emptyList() }
    }
}
