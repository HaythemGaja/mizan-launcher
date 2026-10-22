package com.mizan.launcher.core

/**
 * Home style codec (v1.7, extended v1.8, reworked v2.2) — pure
 * decisions, no Android imports.
 *
 * Every profile picks one home style: TV (the original rows home),
 * Tracker (the v1.8 Netflix-style tracker view, spec §16), IPTV
 * (the v2.2 Xtream/M3U view, spec §20) or Watch (v2.3, spec §21 —
 * the Tracker composition with the IPTV engine linked in, so
 * matched titles play in place). The style is stored per
 * profile as a short string key; this object is the single codec —
 * Prefs reads and writes go through [normalize], so a stored value
 * can never select a renderer that does not exist. The style
 * changes PRESENTATION + COMPOSITION ONLY: the underlying data
 * (favorites, folders, allAppsOrder, locks) and every menu, gate
 * and move session are shared between the styles.
 *
 * v2.2 REMOVED the iPad style (Haythem's verdict: "it is bad"). The
 * stored value "ipad" now decodes to [TV] like any other unknown
 * value, so profiles saved with iPad open in the TV style with no
 * migration step and no data loss — the iPad style never owned any
 * layout data of its own; it rendered the same favorites/folders.
 */
object HomeStyle {

    const val TV = "tv"
    const val TRACKER = "tracker"
    const val IPTV = "iptv"
    const val WATCH = "watch"

    /** Picker order: TV first (the fallback), then Tracker, IPTV, Watch. */
    val VALUES: List<String> = listOf(TV, TRACKER, IPTV, WATCH)

    /**
     * The codec: the exact "tracker"/"iptv"/"watch" keys select
     * those styles; EVERYTHING else — missing, empty, garbled,
     * "TV" in the wrong case, an old backup, and the removed
     * "ipad" — is TV.
     */
    fun normalize(raw: String?): String = when (raw) {
        TRACKER -> TRACKER
        IPTV -> IPTV
        WATCH -> WATCH
        else -> TV
    }

    fun isTracker(style: String): Boolean = style == TRACKER

    fun isIptv(style: String): Boolean = style == IPTV

    fun isWatch(style: String): Boolean = style == WATCH
}
