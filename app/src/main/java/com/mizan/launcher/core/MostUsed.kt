package com.mizan.launcher.core

/**
 * v2.0: the Most Used row's aggregation rules (pure). The Android
 * side (data/MostUsed) counts ACTIVITY_RESUMED events per package over
 * the last 30 days and hands the raw tallies here; this file decides
 * the row: launchable + not hidden only, most events first, ties
 * broken by most-recent use, then by name.
 */
object MostUsedLogic {

    data class Stat(
        val pkg: String,
        val count: Int,
        val lastUsed: Long,
        val label: String
    )

    fun topUsed(
        stats: List<Stat>,
        allowed: Set<String>,
        hidden: Set<String>,
        limit: Int = 8
    ): List<Stat> {
        if (limit <= 0) return emptyList()
        return stats
            .filter { it.pkg in allowed && it.pkg !in hidden && it.count > 0 }
            .sortedWith(
                compareByDescending<Stat> { it.count }
                    .thenByDescending { it.lastUsed }
                    .thenBy { it.label.lowercase() }
            )
            .take(limit)
    }
}
