package com.mizan.launcher.core

/**
 * Clean Tracker home composition (v3.18) — pure decisions, no
 * Android imports.
 *
 * The Clean Tracker style ("clean", core/HomeStyle) renders the
 * tracker library in a FIXED layout, in the calm Clean Your TV
 * manner: Hero, Up Next, Missed, For You, then a simple
 * apps/favorites dock — in that order, always. It deliberately
 * takes NO settings input: no LayoutStore row order/visibility,
 * no TrackerSections order/hidden set, no StatusBarConfig. The
 * fixed order IS the simplification Haythem asked for
 * ("without sreeb complicated settings"): the Home screen rows,
 * Sort sections and Status bar pages keep editing the TV/Watch
 * stores (so switching back restores those homes exactly), but
 * they cannot change this home, because this home never reads
 * them. That is enforced by the signature: [compose] takes data
 * availability and the cold-start flag only — there is no
 * parameter through which a rows/sort/bar preference could
 * reach the result.
 *
 * Data comes from the EXISTING tracker pipeline (the same
 * working set the Watch home renders); this object only decides
 * which sections render and in which order. Posters keep the
 * standing rule (2:3 portrait, never centre-cropped) via the
 * existing row builders; nothing here fetches, caches or keys
 * anything new.
 *
 * Fail-open: an empty library renders no library sections (the
 * renderer shows its empty-state hint line instead); the dock
 * still renders once the app list has landed, so the home is
 * never a blank screen and never a crash. Unknown inputs fail
 * open the same way — every parameter is a plain Boolean, so
 * there is no key to mistype.
 */
object CleanHome {

    const val HERO = "hero"
    const val UPNEXT = "upnext"
    const val MISSED = "missed"
    const val FORYOU = "foryou"
    const val DOCK = "dock"

    /** The fixed order, top to bottom. Never reordered, never hidden by prefs. */
    val ORDER: List<String> = listOf(HERO, UPNEXT, MISSED, FORYOU, DOCK)

    /**
     * v3.19 one-shot default: the first launch after the update
     * makes Clean the active home (Haythem: "build it as sreeb
     * update to not lose data" + CleanYourTV launcher as the
     * home). [alreadyApplied] is the persisted flag — once set,
     * the user's own later style choice is never overridden
     * again. Safe mode still forces the TV render regardless
     * of the saved style, so this never fights safe mode.
     */
    fun defaultStyle(current: String, alreadyApplied: Boolean): String =
        if (alreadyApplied) current else HomeStyle.CLEAN

    /**
     * Which sections render, in fixed order, from data
     * availability alone. A library section renders only when
     * it has something to show (an empty section never renders
     * a bare header); the dock renders once the app list has
     * landed, so it is suppressed on the cold shell exactly
     * like the Watch home's app sections and joins at the warm
     * rebuild.
     */
    fun compose(
        hero: Boolean,
        upNext: Boolean,
        missed: Boolean,
        forYou: Boolean,
        coldStart: Boolean
    ): List<String> {
        val out = ArrayList<String>()
        if (hero) out.add(HERO)
        if (upNext) out.add(UPNEXT)
        if (missed) out.add(MISSED)
        if (forYou) out.add(FORYOU)
        if (!coldStart) out.add(DOCK)
        return out
    }
}
