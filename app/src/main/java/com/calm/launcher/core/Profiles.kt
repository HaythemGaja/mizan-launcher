package com.calm.launcher.core

/**
 * Profiles: up to [MAX] people sharing one TV, each with their own
 * favorites, recents, folders, wallpaper, clock and locks. Profile ids are 1..MAX.
 * Stored as "1,2,4"; a corrupt value falls back to [DEFAULT], never empty.
 */
object Profiles {
    const val MAX = 4
    val DEFAULT: List<Int> = listOf(1)

    fun decode(raw: String?): List<Int> {
        val parsed = raw?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..MAX }?.distinct()?.sorted().orEmpty()
        return if (parsed.isEmpty()) DEFAULT else parsed
    }

    fun encode(ids: List<Int>): String = ids.distinct().sorted().joinToString(",")

    /** Lowest unused id, or null when all [MAX] are taken. */
    fun add(existing: List<Int>): Int? = (1..MAX).firstOrNull { it !in existing }

    /** The list without [target], or null when that would leave no profile. */
    fun remove(existing: List<Int>, target: Int): List<Int>? {
        if (target !in existing || existing.size <= 1) return null
        return existing - target
    }

    /** The current profile if it still exists, otherwise the lowest one. */
    fun resolveCurrent(stored: Int, existing: List<Int>): Int =
        if (stored in existing) stored else existing.minOrNull() ?: 1

    /** Storage key for one profile's setting, e.g. key(2, "favorites") = "p2.favorites". */
    fun key(profile: Int, name: String): String = "p$profile.$name"
}
