package com.mizan.launcher.core

/**
 * App search filtering — pure functions, no Android imports (v1.4).
 *
 * The Search screen shows the same visible set as All Apps (installed
 * − hidden − in-any-folder; the caller applies that dedupe) and filters
 * it by the typed query:
 * - matching is a case-insensitive substring test on the app label,
 * - labels that START WITH the query sort before mere substring hits
 *   (typing "you" should surface YouTube above "My YouTube Clips"),
 * - original order is preserved inside each group (stable),
 * - an empty/blank query returns the full set, in order.
 */
object AppSearch {

    /** One searchable app: package + display label. */
    data class Item(val pkg: String, val label: String)

    fun filter(apps: List<Item>, query: String): List<Item> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        val prefix = ArrayList<Item>()
        val substring = ArrayList<Item>()
        for (app in apps) {
            val label = app.label.lowercase()
            when {
                label.startsWith(q) -> prefix.add(app)
                label.contains(q) -> substring.add(app)
            }
        }
        return prefix + substring
    }
}
