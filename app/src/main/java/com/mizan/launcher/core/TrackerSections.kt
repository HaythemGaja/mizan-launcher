package com.mizan.launcher.core

/**
 * v2.0: per-profile Tracker-view section controls (pure).
 *
 * v2.7: the section set grows to ten — Coming Soon joins after Up
 * Next, and Trending TV / Trending Movies join after Continue
 * Watching (the focus rows the user asked for: up next, coming
 * soon, trending). Profiles whose stored order predates v2.7 keep
 * their order; the new rows append at the end (normalize's
 * missing-keys rule) until the user reorders them.
 *
 * The Tracker home is composed of sections in a canonical
 * order. The hero is special: it can be hidden, but when shown it is
 * ALWAYS first (it is the screen background, not a row). The other
 * sections carry a per-profile order and a hidden set.
 *
 * Rendering composes [compose]: hero first (when shown), then the
 * ordered sections, skipping hidden ones — and the caller additionally
 * skips sections with no data, so a section never renders an empty
 * header. A section gated by data (For You needs tracker ≥ 1.14.4)
 * simply reports "not available" and fails open quietly.
 *
 * Persisted as one line: `order-csv|hidden-csv` using the canonical
 * section keys, e.g. `upnext,continue,foryou,favorites,allapps|`.
 * Decode is fail-open: anything unknown is dropped, anything missing
 * falls back to the canonical defaults.
 *
 * v3.1 (ask 37): Movie Night is REMOVED — it was always empty on
 * Haythem's TV. The key is gone from CANONICAL/ROWS/label, so any
 * stored order or hidden set still mentioning "movienight" loses
 * it through [normalize] on the next read (no separate migration
 * step: decode() normalizes, and normalize drops unknown keys).
 *
 * v3.3 (ask 47): Coming Soon is REMOVED the same way — Up Next
 * and Coming Soon are ONE section now ("Up Next", core
 * TrackerLogic.mergeUpNext). The key is gone from
 * CANONICAL/ROWS/label, so stored orders mentioning
 * "comingsoon" drop it on read. The discovery data itself
 * survives (it feeds the merged section and the hero
 * exclusion); only the standalone section is gone.
 */
object TrackerSections {

    const val HERO = "hero"
    const val UPNEXT = "upnext"
    const val CONTINUE = "continue"
    const val TRENDINGTV = "trendingtv"
    const val TRENDINGMOVIES = "trendingmovies"
    const val FORYOU = "foryou"
    const val FAVORITES = "favorites"
    const val ALLAPPS = "allapps"

    /** Every section, in the order a fresh profile sees them (v3.3: Coming Soon merged into Up Next). */
    val CANONICAL: List<String> = listOf(
        HERO, UPNEXT, CONTINUE, TRENDINGTV, TRENDINGMOVIES,
        FORYOU, FAVORITES, ALLAPPS
    )

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
        TRENDINGTV -> "Trending TV"
        TRENDINGMOVIES -> "Trending Movies"
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
     * The v2.8 one-time order migration. A profile whose order was
     * stored before v2.7 had the focus rows APPENDED at the very
     * end by [normalize] (after foryou/favorites/allapps) — on
     * Haythem's TV the new rows landed below everything and he
     * "didn't see any change". When one still sits in that
     * appended tail position (after any of foryou/favorites/
     * allapps), splice it into its canonical slot: trendingtv
     * right after continue, trendingmovies right after
     * trendingtv. ONLY those ever move; the relative order of
     * every other row is preserved exactly. (v3.3: the
     * comingsoon splice is gone with the section itself —
     * normalize already drops the key on read.)
     *
     * Idempotent by construction: after one pass none of the
     * rows sits after a tail row any more, so a second pass is
     * a no-op — and a user who deliberately parks a focus row
     * mid-list (ahead of foryou) is never touched. (The
     * one-time prefs flag lives in data/Prefs, so a deliberate
     * move to the very end AFTER the migration is also
     * respected.)
     */
    fun migrateOrder(config: Config): Config {
        val order = config.order.toMutableList()
        fun appended(key: String): Boolean {
            val at = order.indexOf(key)
            if (at < 0) return false
            return listOf(FORYOU, FAVORITES, ALLAPPS).any {
                val tailAt = order.indexOf(it)
                tailAt in 0 until at
            }
        }
        var changed = false
        if (appended(TRENDINGTV)) {
            order.remove(TRENDINGTV)
            order.add(order.indexOf(CONTINUE) + 1, TRENDINGTV)
            changed = true
        }
        if (appended(TRENDINGMOVIES)) {
            order.remove(TRENDINGMOVIES)
            order.add(order.indexOf(TRENDINGTV) + 1, TRENDINGMOVIES)
            changed = true
        }
        return if (changed) normalize(config.hidden, order) else config
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
