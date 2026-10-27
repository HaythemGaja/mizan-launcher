package com.mizan.launcher.core

/**
 * Local-first home data (v3.1, ask 38): the pure rules for the
 * bounded on-disk home cache.
 *
 * Why this exists when tracker snapshots already live in prefs and
 * discovery already has a disk cache: prefs are one big document —
 * a wrong-typed write or a corrupt prefs file (the v3.0 crash
 * class) can strand the home with NOTHING, and the home's other
 * row payloads still had no second copy. The home cache is a
 * second, per-section, on-disk copy of the row payloads the home
 * renders from: tracker snapshots (upnext/continue/movies/tracked/
 * foryou) mirrored on every successful provider fetch, plus the
 * discovery outputs (already disk-cached by data/DiscoveryData —
 * this file only owns their TTL/staleness vocabulary, not their
 * files).
 *
 * Rules, all pure and sim-pinned:
 * - Render rule: the home ALWAYS renders the cached payload first
 *   (fresh or stale — stale last-known data beats an empty home);
 *   network refreshes happen on workers and swap sections in as
 *   they land. A payload is never dropped for being stale.
 * - TTLs are for the diagnostics staleness NOTE only: trending
 *   12h, discovery 24h, tracker snapshots 24h. Past TTL the
 *   section still renders; diagnostics says how old it is.
 * - Size bound: the whole cache dir is capped at [TOTAL_CAP_BYTES];
 *   [prune] picks the oldest sections first when a write would
 *   overflow. Pruning deletes a section's mirror file, never the
 *   live prefs copy.
 * - Credential rule: NO credential-bearing material is ever
 *   written here — no TMDB key, no IPTV username/password, no
 *   full Xtream stream URL (those carry the panel credentials in
 *   the query string). Row payloads store catalogue ids/keys and
 *   poster paths only. [containsCredentialPattern] is the
 *   writer-side tripwire: a payload matching it is refused
 *   (fail-open — the prefs copy and the home are unaffected).
 */
object HomeCache {

    /** Trending rows refresh twice a day at most (matches the v2.7 discovery TTL). */
    const val TRENDING_TTL_MS: Long = 12L * 60L * 60L * 1000L

    /** Discovery outputs + tracker snapshots: a day before they count as stale. */
    const val DISCOVERY_TTL_MS: Long = 24L * 60L * 60L * 1000L
    const val SNAPSHOT_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /** Whole home-cache directory cap (tracker snapshots are small; this is headroom, not a target). */
    const val TOTAL_CAP_BYTES: Long = 4L * 1024L * 1024L

    /** Tracker snapshot sections mirrored here, in provider-path order. */
    val TRACKER_SECTIONS: List<String> =
        listOf("upnext", "continue", "movies", "tracked", "foryou")

    /** One section's manifest row (kept in manifest.json next to the files). */
    data class Entry(
        val section: String,
        val fetchedAtMs: Long,
        val bytes: Long
    )

    /** Fresh = inside its TTL. Stale payloads still render — see the render rule above. */
    fun isFresh(fetchedAtMs: Long, ttlMs: Long, nowMs: Long): Boolean =
        fetchedAtMs > 0L && nowMs >= fetchedAtMs && nowMs - fetchedAtMs < ttlMs

    /** Diagnostics age wording for one section: "fresh", "3h old", "2d old", or "no data". */
    fun ageLabel(fetchedAtMs: Long, nowMs: Long): String {
        if (fetchedAtMs <= 0L || nowMs < fetchedAtMs) return "no data"
        val ageMs = nowMs - fetchedAtMs
        val mins = ageMs / 60_000L
        if (mins < 60L) return "${mins}m old"
        val hours = mins / 60L
        if (hours < 24L) return "${hours}h old"
        return "${hours / 24L}d old"
    }

    /**
     * Prune choice: given the manifest AFTER the incoming write
     * landed (so its bytes are already counted), the sections to
     * delete (oldest first) so the total lands at or under the
     * cap. Returns empty when already under. Never prunes the
     * section being written (the caller passes it in [keepSection]
     * so a refresh can't delete its own payload).
     */
    fun prune(
        manifest: List<Entry>,
        keepSection: String = "",
        capBytes: Long = TOTAL_CAP_BYTES
    ): List<String> {
        var total = 0L
        for (e in manifest) total += e.bytes
        if (total <= capBytes) return emptyList()
        val out = ArrayList<String>()
        for (e in manifest.sortedBy { it.fetchedAtMs }) {
            if (total <= capBytes) break
            if (e.section == keepSection) continue
            out.add(e.section)
            total -= e.bytes
        }
        return out
    }

    /**
     * Credential tripwire for the writer side. Tracker-snapshot and
     * discovery JSON never contain these; a match means something
     * upstream handed us a URL or a key blob by mistake, and the
     * write must be refused. Checked case-insensitively; the
     * patterns are the shapes Xtream/TMDB credential material
     * takes, not any real credential value.
     */
    fun containsCredentialPattern(payload: String): Boolean {
        val lower = payload.lowercase()
        return lower.contains("username=") ||
            lower.contains("password=") ||
            lower.contains("api_key=") ||
            lower.contains("\"tmdb_key\"") ||
            lower.contains("tmdb_key=")
    }

    /**
     * The quiet diagnostics line (ask 38: one staleness note, never
     * on-screen error spam): "5/5 sections · oldest 3h old · 84KB".
     * [present] is the set of mirrored sections, [oldestFetchedAtMs]
     * the oldest fetchedAt among them (0 = none).
     */
    fun diagLine(present: Set<String>, oldestFetchedAtMs: Long, totalBytes: Long, nowMs: Long): String {
        val age = if (present.isEmpty()) "empty" else ageLabel(oldestFetchedAtMs, nowMs)
        return "${present.size}/${TRACKER_SECTIONS.size} sections · $age · ${sizeText(totalBytes)}"
    }

    private fun sizeText(bytes: Long): String = when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
        else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
    }
}
