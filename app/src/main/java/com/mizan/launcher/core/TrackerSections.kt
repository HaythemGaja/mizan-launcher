package com.mizan.launcher.core

/**
 * v2.0: per-profile Tracker-view section controls (pure).
 *
 * The Tracker home is composed of seven sections in a canonical
 * order. The hero is special: it can be hidden, but when shown it is
 * ALWAYS first (it is the screen background, not a row). The other
 * six sections carry a per-profile order and a hidden set.
 *
 * Rendering composes [compose]: hero first (when shown), then the
 * ordered sections, skipping hidden ones — and the caller additionally
 * skips sections with no data, so a section never renders an empty
 * header. A section gated by data (For You needs tracker ≥ 1.14.4)
 * simply reports "not available" and fails open quietly.
 *
 * Persisted as one line: `order-csv|hidden-csv` using the canonical
 * section keys, e.g. `upnext,continue,movienight,foryou,favorites,allapps|`.
 * Decode is fail-open: anything unknown is dropped, anything missing
 * falls back to the canonical defaults.
 */
object TrackerSections {

    const val HERO = "hero"
    const val UPNEXT = "upnext"
    const val CONTINUE = "continue"
    const val MOVIENIGHT = "movienight"
    const val FORYOU = "foryou"
    const val FAVORITES = "favorites"
    const val ALLAPPS = "allapps"

    /** Every section, in the order a fresh profile sees them. */
    val CANONICAL: List<String> =
        listOf(HERO, UPNEXT, CONTINUE, MOVIENIGHT, FORYOU, FAVORITES, ALLAPPS)

    /** The reorderable rows (everything except the hero). */
    val ROWS: List<String> = CANONICAL.drop(1)

    data class Config(
        val hidden: Set<String> = emptySet(),
        val order: List<String> = ROWS
    )

    fun label(key: String): String = when (key) {
        HERO -> "Hero"
        UPNEXT -> "Up Next"
        CONTINUE -> "Continue Watching"
        MOVIENIGHT -> "Movie Night"
        FORYOU -> "For You"
        FAVORITES -> "Favorites"
        ALLAPPS -> "All apps"
        else -> key
    }

    /** Coerce arbitrary input into a valid config. */
    fun normalize(hidden: Collection<String>, order: List<String>): Config {
        val cleanHidden = hidden.filter { it in CANONICAL }.toSet()
        val seen = mutableSetOf<String>()
        val cleanOrder = mutableListOf<String>()
        for (key in order) {
            if (key in ROWS && seen.add(key)) cleanOrder.add(key)
        }
        for (key in ROWS) if (key !in seen) cleanOrder.add(key)
        return Config(cleanHidden, cleanOrder)
    }

    fun encode(config: Config): String {
        val c = normalize(config.hidden, config.order)
        val hiddenCsv = CANONICAL.filter { it in c.hidden }.joinToString(",")
        return c.order.joinToString(",") + "|" + hiddenCsv
    }

    fun decode(raw: String?): Config {
        if (raw.isNullOrBlank()) return Config()
        val parts = raw.split("|", limit = 2)
        val order = parts[0].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val hidden = if (parts.size > 1) {
            parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return normalize(hidden, order)
    }

    fun isShown(config: Config, key: String): Boolean = key !in config.hidden

    fun setShown(config: Config, key: String, shown: Boolean): Config {
        if (key !in CANONICAL) return config
        val hidden = if (shown) config.hidden - key else config.hidden + key
        return normalize(hidden, config.order)
    }

    /** Move a row one step ([dir] −1 = up, +1 = down); edges are no-ops. */
    fun move(config: Config, key: String, dir: Int): Config {
        if (key !in ROWS || dir == 0) return config
        val order = config.order.toMutableList()
        val at = order.indexOf(key)
        if (at < 0) return config
        val to = at + if (dir < 0) -1 else 1
        if (to < 0 || to >= order.size) return config
        order.removeAt(at)
        order.add(to, key)
        return config.copy(order = order)
    }

    /**
     * The sections to render, in order: the hero first when shown,
     * then the configured row order — every entry additionally gated
     * by [available] (data presence / feature gates, supplied by the
     * caller). Hidden and unavailable sections are both skipped.
     */
    fun compose(config: Config, available: (String) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        if (isShown(config, HERO) && available(HERO)) out.add(HERO)
        for (key in config.order) {
            if (isShown(config, key) && available(key)) out.add(key)
        }
        return out
    }
}
