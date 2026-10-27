package com.mizan.launcher.core

/**
 * IPTV category pattern-lock (v2.2, spec §20.8) — pure, no Android
 * imports. Any IPTV category (live / VOD / series, either source)
 * can be locked with the profile's existing arrow pattern; locked
 * categories are hidden from the IPTV home rows and the browser
 * until unlocked once through the pattern gate.
 *
 * Semantics:
 * - Locks only bite when the profile HAS a pattern set — without
 *   one there is nothing to gate behind, and [isLocked] is always
 *   false.
 * - A category is locked when the user locked it explicitly
 *   (stored in the profile's lock set) OR its name matches an
 *   adult marker ([adultLocked]) — adult-named categories START
 *   locked on patterns profiles; this is deliberate, documented in
 *   the spec and the test script, and uses the same unlock flow.
 * - Unlocking runs the existing pattern gate (ArrowPinDialog +
 *   Prefs grace machinery — the same one locked apps/folders use).
 *   A successful unlock stamps the category; the stamp stays valid
 *   for the profile's "Re-ask for pattern" grace window
 *   (core/UnlockGrace: 5/15/30 minutes), or — when the window is
 *   "Every time" (0) — for the rest of this app session
 *   ([stampValid] compares against the caller's session start).
 *   Explicitly-locked categories additionally leave the stored set
 *   on unlock, so they stay open until locked again; adult-named
 *   ones re-lock when the stamp lapses, because the name rule
 *   still applies.
 *
 * The store is per profile (Prefs keys), encoded with the shared
 * IptvLogic field codec.
 */
object IptvLocks {

    /** Name fragments that mark a category as adult (substring, case-insensitive). */
    val ADULT_MARKERS: List<String> = listOf("adult", "xxx", "+18", "18+")

    /** True when [name] carries an adult marker (case-insensitive substring). */
    fun adultLocked(name: String): Boolean {
        val n = name.lowercase()
        return ADULT_MARKERS.any { n.contains(it) }
    }

    // ------------------------------------------------------------ codecs

    fun encodeKeys(set: Set<String>): String =
        set.sorted().joinToString("\n") { IptvLogic.esc(it) }

    fun decodeKeys(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split('\n').map { IptvLogic.unesc(it) }.filter { it.isNotEmpty() }.toSet()
    }

    fun encodeStamps(map: Map<String, Long>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
            IptvLogic.esc(k) + "|" + v.toString()
        }

    fun decodeStamps(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val sep = line.lastIndexOf('|')
            if (sep <= 0) continue
            val key = IptvLogic.unesc(line.substring(0, sep))
            val at = line.substring(sep + 1).toLongOrNull() ?: continue
            if (key.isNotEmpty()) out[key] = at
        }
        return out
    }

    // ------------------------------------------------------------ the decision

    /**
     * Is an unlock stamp for one category still valid?
     * graceMinutes > 0: within that many minutes of the stamp.
     * graceMinutes == 0 ("Every time"): only within this session
     * (stamped at or after [sessionStartMs]).
     */
    fun stampValid(stampMs: Long, graceMinutes: Int, nowMs: Long, sessionStartMs: Long): Boolean {
        if (stampMs <= 0L) return false
        if (graceMinutes > 0) return nowMs - stampMs < graceMinutes * 60_000L
        return stampMs >= sessionStartMs
    }

    /**
     * The one visibility decision, shared by home rows, the
     * browser and the guide: a category is LOCKED (hidden) when it
     * is explicitly locked or adult-named, the profile has a
     * pattern, and no valid unlock stamp covers it.
     */
    fun isLocked(
        catKey: String,
        catName: String,
        storedLocked: Set<String>,
        stamps: Map<String, Long>,
        graceMinutes: Int,
        nowMs: Long,
        sessionStartMs: Long,
        hasPattern: Boolean
    ): Boolean {
        if (!hasPattern) return false
        val locked = catKey in storedLocked || adultLocked(catName)
        if (!locked) return false
        return !stampValid(stamps[catKey] ?: 0L, graceMinutes, nowMs, sessionStartMs)
    }

    /** The hidden-category key set for a catalogue snapshot (home rows + search filtering). */
    fun hiddenKeys(
        cats: List<IptvLogic.Category>,
        storedLocked: Set<String>,
        stamps: Map<String, Long>,
        graceMinutes: Int,
        nowMs: Long,
        sessionStartMs: Long,
        hasPattern: Boolean
    ): Set<String> = cats
        .filter { isLocked(it.key, it.name, storedLocked, stamps, graceMinutes, nowMs, sessionStartMs, hasPattern) }
        .map { it.key }
        .toSet()
}
