package com.mizan.launcher.core

/**
 * The Google-TV-style pill tab bar model (v2.8, spec §28) — pure
 * decisions, no Android imports.
 *
 * Both media home styles (Tracker/Watch and IPTV) carry a pill tab
 * bar under the status bar, modelled on the stock Google TV home
 * (Haythem's reference photos): a row of rounded pills, the current
 * page's pill filled white with dark text, the rest translucent.
 *
 * Only the FIRST tab is a page — the home itself, always selected.
 * Every other tab is an ACTION: it opens another surface (the
 * in-launcher Library, the IPTV browser on a tab, the guide) or
 * moves focus within this page (Apps → the All Apps section). The
 * renderer keys off [Tab.action]; this object owns the lists so the
 * sim can pin them.
 */
object HomeTabs {

    enum class Action {
        /** The home page itself — the selected pill; tapping is a no-op. */
        STAY,

        /** Open ui/LibraryActivity (Tracker/Watch styles). */
        OPEN_LIBRARY,

        /** Open ui/IptvActivity on its Live tab. */
        OPEN_IPTV_LIVE,

        /** Open ui/IptvActivity on its Movies tab. */
        OPEN_IPTV_MOVIES,

        /** Open ui/IptvActivity on its Series tab. */
        OPEN_IPTV_SERIES,

        /** Open ui/IptvGuideActivity. */
        OPEN_GUIDE,

        /** Move focus to this page's All Apps section. */
        FOCUS_ALL_APPS
    }

    data class Tab(
        val id: String,
        val label: String,
        val action: Action,
        val selected: Boolean
    )

    const val ID_HOME = "home"
    const val ID_LIBRARY = "library"
    const val ID_LIVE = "live"
    const val ID_MOVIES = "movies"
    const val ID_SERIES = "series"
    const val ID_GUIDE = "guide"
    const val ID_APPS = "apps"

    /** Tracker + Watch: Home · Library · Apps. */
    fun trackerTabs(): List<Tab> = listOf(
        Tab(ID_HOME, "Home", Action.STAY, selected = true),
        Tab(ID_LIBRARY, "Library", Action.OPEN_LIBRARY, selected = false),
        Tab(ID_APPS, "Apps", Action.FOCUS_ALL_APPS, selected = false)
    )

    /** IPTV: Home · Live · Movies · Series · Guide · Apps. */
    fun iptvTabs(): List<Tab> = listOf(
        Tab(ID_HOME, "Home", Action.STAY, selected = true),
        Tab(ID_LIVE, "Live", Action.OPEN_IPTV_LIVE, selected = false),
        Tab(ID_MOVIES, "Movies", Action.OPEN_IPTV_MOVIES, selected = false),
        Tab(ID_SERIES, "Series", Action.OPEN_IPTV_SERIES, selected = false),
        Tab(ID_GUIDE, "Guide", Action.OPEN_GUIDE, selected = false),
        Tab(ID_APPS, "Apps", Action.FOCUS_ALL_APPS, selected = false)
    )

    /**
     * The tabs for a home-style key (core/HomeStyle keys). The TV
     * style has no tab bar — an empty list, and the renderer adds
     * nothing.
     */
    fun forStyle(styleKey: String): List<Tab> = when {
        HomeStyle.isIptv(styleKey) -> iptvTabs()
        HomeStyle.isTracker(styleKey) || HomeStyle.isWatch(styleKey) -> trackerTabs()
        else -> emptyList()
    }
}
