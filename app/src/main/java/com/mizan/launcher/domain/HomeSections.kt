package com.mizan.launcher.domain

/**
 * Home section identities + canonical default orders.
 * NOTE (consolidation task for the data worker): these lists mirror
 * core's canonical orders (LayoutStore.RowOrder for TV rows,
 * TrackerSections for Watch sections). Runtime order/visibility
 * (user reorders, hides) is owned by SettingsRepository; if the two
 * ever disagree, core's lists win and this file must be updated to
 * match — a later worker should collapse them to one source.
 * Hero is always first when shown and is hide/show-only.
 *
 * TV rows: favorites, recents, mostused, folders, nowplaying,
 * continue, inputs, livetv, apps.
 * Watch sections: hero, upnext, missed, continue, trending_tv,
 * trending_movies, foryou, favorites, folders, apps.
 * Hero is always first when shown and is hide/show-only
 * (never reorderable).
 */
object SectionIds {
    const val HERO = "hero"
    const val FAVORITES = "favorites"
    const val RECENTS = "recents"
    const val MOST_USED = "mostused"
    const val FOLDERS = "folders"
    const val NOW_PLAYING = "nowplaying"
    const val CONTINUE = "continue"
    const val INPUTS = "inputs"
    const val LIVE_TV = "livetv"
    const val APPS = "apps"
    const val UP_NEXT = "upnext"
    const val MISSED = "missed"
    const val TRENDING_TV = "trending_tv"
    const val TRENDING_MOVIES = "trending_movies"
    const val FOR_YOU = "foryou"
    const val LIBRARY = "library"

    val TV_ROWS = listOf(
        FAVORITES, RECENTS, MOST_USED, FOLDERS, NOW_PLAYING,
        CONTINUE, INPUTS, LIVE_TV, APPS,
    )

    val WATCH_SECTIONS = listOf(
        HERO, UP_NEXT, MISSED, CONTINUE, TRENDING_TV,
        TRENDING_MOVIES, FOR_YOU, FAVORITES, FOLDERS, APPS,
    )
}

data class SectionVisibility(val sectionId: String, val visible: Boolean)

/** Per-section render state for the home composition. */
enum class SectionState { LOADING, READY, EMPTY, ERROR }

data class SectionRender(
    val sectionId: String,
    val state: SectionState,
    val cards: List<HomeCard> = emptyList(),
)
