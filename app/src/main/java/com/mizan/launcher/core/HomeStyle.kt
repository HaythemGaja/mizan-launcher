package com.mizan.launcher.core

/**
 * Home style codec (v1.7, extended v1.8, reworked v2.2, v3.8) — pure
 * decisions, no Android imports.
 *
 * Every profile picks one home style: TV (the original rows home),
 * IPTV (the v2.2 Xtream/M3U view, spec §20) or Watch (v2.3, spec
 * §21 — the tracked-library composition with the IPTV engine
 * linked in, so matched titles play in place). The style is stored
 * per profile as a short string key; this object is the single
 * codec — Prefs reads and writes go through [normalize], so a
 * stored value can never select a renderer that does not exist.
 * The style changes PRESENTATION + COMPOSITION ONLY: the
 * underlying data (favorites, folders, allAppsOrder, locks) and
 * every menu, gate and move session are shared between the
 * styles.
 *
 * v2.2 REMOVED the iPad style (Haythem's verdict: "it is bad"). The
 * stored value "ipad" now decodes to [TV] like any other unknown
 * value, so profiles saved with iPad open in the TV style with no
 * migration step and no data loss — the iPad style never owned any
 * layout data of its own; it rendered the same favorites/folders.
 *
 * v3.8 REMOVED the Tracker-only style (Haythem 2026-10-25: the
 * Tracker view and the Watch view are exactly the same — keep
 * Watch only). The stored value "tracker" now decodes to [WATCH],
 * so profiles saved with Tracker open in the Watch style with no
 * migration step and no data loss — Watch renders the same
 * library sections (hero, Up Next, Missed, Continue, Trending,
 * For You) plus the IPTV match/play layer, which is a strict
 * superset of what the Tracker view showed. The old Tracker
 * predicate is gone: any call site that branched on the
 * Tracker style now branches on Watch, and the Tracker style
 * name must never appear in the picker, the diagnostics
 * home-style label, or a rendered home.
 *
 * v3.9 REMOVED the IPTV home style (Haythem 2026-10-25:
 * "merge iptv home view with watch home view under live,
 * keep watch, remove iptv view"). The stored value "iptv"
 * now decodes to [WATCH] the same way, so profiles saved
 * with IPTV open in Watch with no migration step and no
 * data loss — and the IPTV browser itself is NOT deleted:
 * Watch's new Live tab opens IptvActivity (Live channels
 * plus its Movies/Series/Guide/Favorites/Search tabs,
 * locks, PiP, catch-up), so a migrated profile loses no
 * IPTV feature, only the IPTV-as-home layout. The IPTV
 * const stays for the dead-via-dispatch fallback code
 * (buildIptvHome etc.) and SectionGuard's crash-fallback
 * mapping — see spec §39. Final styles: TV / Watch.
 */
object HomeStyle {

    const val TV = "tv"
    const val IPTV = "iptv"
    const val WATCH = "watch"

    /** Picker order: TV first (the fallback), then Watch (v3.9: IPTV removed). */
    val VALUES: List<String> = listOf(TV, WATCH)

    /**
     * The codec: the exact "watch" key selects Watch; the
     * removed "tracker" and "iptv" keys migrate to Watch;
     * EVERYTHING else — missing, empty, garbled, "TV" in the
     * wrong case, an old backup, and the removed "ipad" — is
     * TV.
     */
    fun normalize(raw: String?): String = when (raw) {
        "tracker" -> WATCH
        "iptv" -> WATCH
        WATCH -> WATCH
        else -> TV
    }

    fun isIptv(style: String): Boolean = style == IPTV

    fun isWatch(style: String): Boolean = style == WATCH
}
