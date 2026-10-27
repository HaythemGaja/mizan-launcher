package com.mizan.launcher.core

/**
 * Recently Used store — pure data + codec, no Android imports (v1.4).
 *
 * Records launches made FROM Mizan only (home tiles, search results,
 * folder contents, Continue Watching when its intent names a package).
 * Launches via remote hardware buttons (the YouTube/Netflix keys) or
 * from other apps are invisible without Usage Access permission, which
 * Mizan deliberately does not request — they are not tracked, by design.
 *
 * The store is most-recent-first, de-duplicated (a relaunch moves the
 * app back to the front), capped at [CAP] entries, and never contains
 * Mizan itself. It is per profile (the prefs key is namespaced like
 * every other per-profile value) and is NOT part of backups.
 */
object RecentsStore {

    const val CAP = 8

    /** One recorded launch: package + when (epoch ms). */
    data class Entry(val pkg: String, val at: Long)

    /**
     * Record a launch of [pkg] at [nowMillis]: it moves to the front,
     * any older entry for the same package is dropped, the list is
     * capped at [CAP], and Mizan itself is never recorded.
     */
    fun record(entries: List<Entry>, pkg: String, nowMillis: Long, selfPkg: String): List<Entry> {
        if (pkg.isBlank() || pkg == selfPkg) return entries
        val out = ArrayList<Entry>(CAP)
        out.add(Entry(pkg, nowMillis))
        for (e in entries) {
            if (e.pkg == pkg) continue
            out.add(e)
            if (out.size >= CAP) break
        }
        return out
    }

    // ------------------------------------------------------------ codec

    private fun esc(s: String): String = s
        .replace("%", "%25").replace("|", "%7C").replace(",", "%2C").replace("\n", "%0A")

    private fun unesc(s: String): String = s
        .replace("%0A", "\n").replace("%2C", ",").replace("%7C", "|").replace("%25", "%")

    /** "pkg|millis,pkg|millis,..." — newest first. Empty list → "". */
    fun encode(entries: List<Entry>): String =
        entries.joinToString(",") { "${esc(it.pkg)}|${it.at}" }

    /** Decode; malformed entries are skipped (fail-open), never throws. */
    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Entry>()
        for (part in raw.split(",")) {
            try {
                val bar = part.lastIndexOf('|')
                if (bar <= 0) continue
                val pkg = unesc(part.substring(0, bar))
                val at = part.substring(bar + 1).toLongOrNull() ?: continue
                if (pkg.isNotBlank()) out.add(Entry(pkg, at))
            } catch (e: Exception) {
                // Skip the bad entry, keep the rest.
            }
        }
        return out.take(CAP)
    }
}
