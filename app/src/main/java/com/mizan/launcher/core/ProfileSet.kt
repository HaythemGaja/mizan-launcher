package com.mizan.launcher.core

/**
 * The set of profiles that exist (v3.14) — pure codec, no Android imports.
 *
 * History: profiles were hardcoded to exactly {1, 2} (Prefs.currentProfile
 * coerced to 1..2, a 1↔2 toggle switch, Rename rows for 1 and 2 only).
 * v3.14 generalizes to at most [MAX_PROFILES] profiles. The set itself is
 * one global SharedPreferences value ("profile_set", CSV like "1,2,4");
 * every per-profile setting still lives under its own `p<N>.` prefix
 * (see data/Prefs), so the set is the ONLY new global key.
 *
 * Rules, all fail-open (a corrupt stored set reads as [DEFAULT]):
 * - [decode] keeps only numbers 1..[MAX_PROFILES], deduped, sorted;
 *   an empty or unparseable value means [DEFAULT] (profiles 1 and 2,
 *   the pre-v3.14 world), never an empty set.
 * - [add] returns the lowest unused number, or null when all
 *   [MAX_PROFILES] slots are taken — callers must refuse a 5th
 *   profile with a toast, never a crash.
 * - [remove] refuses when the set would become empty (returns null);
 *   deleting the last remaining profile is never allowed.
 * - [resolveCurrent] / [resolveGuest] map a stored number onto the
 *   set: a number outside the set falls back to the lowest existing
 *   profile (current) or to None (guest, 0).
 * - [keysForProfile] is the exact key set delete/reset may touch:
 *   every key starting with `p<N>.` and nothing else. Global keys
 *   (arrow lock, safe mode, library flags, TMDB key, IPTV account,
 *   "profile_set" itself) never match a profile prefix, so delete
 *   and reset can never touch them through this filter.
 */
object ProfileSet {

    /** Hard cap on profiles (v3.14 decision: small fixed set, like before). */
    const val MAX_PROFILES = 4

    /** The pre-v3.14 world: profiles 1 and 2 always exist. */
    val DEFAULT: Set<Int> = setOf(1, 2)

    /** Storage prefix for one profile's own keys. */
    fun keyPrefix(profile: Int): String = "p$profile."

    /** Encode a set for storage ("1,2,4"). Empty input encodes as "". */
    fun encode(profiles: Set<Int>): String =
        profiles.filter { it in 1..MAX_PROFILES }.sorted().joinToString(",")

    /**
     * Decode a stored set. Garbage, blanks, out-of-range numbers and
     * duplicates are dropped; a result with no survivors is [DEFAULT].
     */
    fun decode(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..MAX_PROFILES }.toSortedSet()
        return if (parsed.isEmpty()) DEFAULT else parsed
    }

    /** True when [profile] is a usable slot number at all (1..MAX). */
    fun isSlot(profile: Int): Boolean = profile in 1..MAX_PROFILES

    /** Lowest unused slot, or null when every slot is taken. */
    fun add(existing: Set<Int>): Int? =
        (1..MAX_PROFILES).firstOrNull { it !in existing }

    /**
     * The set after removing [target], or null when the removal is
     * refused: [target] is not in the set, or it is the last one.
     */
    fun remove(existing: Set<Int>, target: Int): Set<Int>? {
        if (target !in existing) return null
        if (existing.size <= 1) return null
        return existing - target
    }

    /** Lowest profile in the set (the fallback current profile). */
    fun lowest(existing: Set<Int>): Int = existing.minOrNull() ?: 1

    /** Map a stored current-profile number onto the set (fail-open). */
    fun resolveCurrent(stored: Int, existing: Set<Int>): Int =
        if (stored in existing) stored else lowest(existing)

    /** Map a stored guest number onto the set (0 = None, fail-open). */
    fun resolveGuest(stored: Int, existing: Set<Int>): Int =
        if (stored == 0 || stored in existing) stored else 0

    /** Exactly the keys delete/reset may remove for [profile]. */
    fun keysForProfile(allKeys: Collection<String>, profile: Int): List<String> {
        val prefix = keyPrefix(profile)
        return allKeys.filter { it.startsWith(prefix) }
    }
}
