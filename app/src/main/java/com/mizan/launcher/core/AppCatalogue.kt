package com.mizan.launcher.core

/**
 * The full launchable-app catalogue (v1.6) — pure merge logic, no
 * Android imports.
 *
 * The lock manager (and every "all apps" surface) must cover EVERY
 * launchable app on the device, not just the home-visible set: apps
 * that are hidden, inside folders, system/preinstalled apps with a
 * launcher entry (the TV's own Settings app is the classic case), and
 * apps that only declare a phone-style LAUNCHER entry instead of a
 * LEANBACK one. The Android side queries both intent categories and
 * hands the raw records here; this merge is the testable part.
 *
 * Rules:
 * - De-duplicated by package; the LEANBACK record wins over the
 *   plain LAUNCHER record (the TV label/icon source is preferred),
 *   otherwise the first record wins.
 * - Sreeb itself is excluded — locking the launcher's own
 *   entry is meaningless (its Settings have their own lock).
 * - System apps are INCLUDED (flagged, never filtered out).
 * - Sorted by label (case-insensitive), package name as tie-break.
 */
object AppCatalogue {

    /** One raw query result, flattened so the merge is JVM-testable. */
    data class Record(
        val pkg: String,
        val label: String,
        val isSystem: Boolean,
        /** True when this record came from the LEANBACK_LAUNCHER query. */
        val leanback: Boolean
    )

    fun merge(records: List<Record>, ownPkg: String): List<Record> {
        val byPkg = LinkedHashMap<String, Record>()
        for (rec in records) {
            if (rec.pkg == ownPkg || rec.pkg.isBlank()) continue
            val existing = byPkg[rec.pkg]
            when {
                existing == null -> byPkg[rec.pkg] = rec
                // A leanback record replaces a plain-launcher one;
                // anything else keeps the first record seen.
                rec.leanback && !existing.leanback -> byPkg[rec.pkg] = rec
            }
        }
        return byPkg.values.sortedWith(
            compareBy<Record> { it.label.lowercase() }.thenBy { it.pkg }
        )
    }
}
