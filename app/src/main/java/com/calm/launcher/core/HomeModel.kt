package com.calm.launcher.core

/** One launchable app. [category] is the launcher category it was found under. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val activity: String,
    val category: String
)

/**
 * The launcher's rules, with no Android imports so the sim can test them.
 *
 * - Favorites keep the order you chose, capped at [MAX_FAVORITES].
 * - Recently opened is most-recent-first, excludes favorites, capped at [MAX_RECENT].
 * - Anything no longer installed silently drops out of every list.
 * - Stored lists are plain newline-separated package names; a corrupt value reads as empty.
 */
object HomeModel {

    const val MAX_FAVORITES = 12
    const val MAX_RECENT = 12

    /** Favorites in saved order, keeping only apps that are still installed. */
    fun favorites(saved: List<String>, installed: List<AppEntry>): List<AppEntry> {
        val byPkg = installed.associateBy { it.pkg }
        return saved.distinct().mapNotNull { byPkg[it] }.take(MAX_FAVORITES)
    }

    /** Recently opened: newest first, installed only, not already a favorite. */
    fun recent(saved: List<String>, installed: List<AppEntry>, favoritePkgs: Set<String>): List<AppEntry> {
        val byPkg = installed.associateBy { it.pkg }
        return saved.distinct()
            .filter { it !in favoritePkgs }
            .mapNotNull { byPkg[it] }
            .take(MAX_RECENT)
    }

    /** Move [pkg] to the front of the recent list. */
    fun touchRecent(saved: List<String>, pkg: String): List<String> =
        (listOf(pkg) + saved.filter { it != pkg }).take(MAX_RECENT)

    /**
     * Add [pkg] to the end of the favorites, or remove it if already there.
     * Returns null when adding would exceed [MAX_FAVORITES].
     */
    fun toggleFavorite(saved: List<String>, pkg: String): List<String>? {
        val clean = saved.distinct()
        return if (pkg in clean) {
            clean - pkg
        } else if (clean.size >= MAX_FAVORITES) {
            null
        } else {
            clean + pkg
        }
    }

    /** Every app, once per package, sorted by label ignoring case. */
    fun allApps(installed: List<AppEntry>): List<AppEntry> =
        installed.distinctBy { it.pkg }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    fun encodeList(list: List<String>): String = list.joinToString("\n")

    fun decodeList(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    /** "09:05" in 24-hour mode, "9:05 AM" / "12:30 PM" in 12-hour mode. */
    fun clockText(hour: Int, minute: Int, is24h: Boolean): String {
        require(hour in 0..23 && minute in 0..59) { "bad time $hour:$minute" }
        val mm = minute.toString().padStart(2, '0')
        if (is24h) return "${hour.toString().padStart(2, '0')}:$mm"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12:$mm ${if (hour < 12) "AM" else "PM"}"
    }
}
