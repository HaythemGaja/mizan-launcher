package com.mizan.launcher.core

/**
 * v2.0: automatic All Apps categories (pure).
 *
 * Resolution order:
 *  1. the curated package map below (the big streaming/music apps —
 *     their manifest categories are unreliable: several declare none);
 *  2. the app's `ApplicationInfo.category` (API 26+), mapped through
 *     the table in [categoryFor];
 *  3. "Other".
 *
 * The ApplicationInfo.category constants (framework values, repeated
 * here so this file stays free of Android imports and unit-testable):
 * GAME 0, AUDIO 1, VIDEO 2, IMAGE 3, SOCIAL 4, NEWS 5, MAPS 6,
 * PRODUCTIVITY 7, ACCESSIBILITY 8, UNDEFINED −1.
 */
object AppCategories {

    const val VIDEO = "Video"
    const val MUSIC = "Music"
    const val GAMES = "Games"
    const val SOCIAL = "Social"
    const val OTHER = "Other"

    /** Section render order in All Apps; empty sections are dropped. */
    val ORDER: List<String> = listOf(VIDEO, MUSIC, GAMES, SOCIAL, OTHER)

    private const val CAT_GAME = 0
    private const val CAT_AUDIO = 1
    private const val CAT_VIDEO = 2
    private const val CAT_IMAGE = 3
    private const val CAT_SOCIAL = 4
    private const val CAT_NEWS = 5
    private const val CAT_MAPS = 6
    private const val CAT_PRODUCTIVITY = 7
    private const val CAT_ACCESSIBILITY = 8

    /**
     * Curated packages (checked before the manifest category). Keys are
     * package names; both the TV and phone variants of each service are
     * listed where they differ.
     */
    private val CURATED: Map<String, String> = mapOf(
        // Video
        "com.netflix.ninja" to VIDEO,
        "com.netflix.mediaclient" to VIDEO,
        "com.google.android.youtube.tv" to VIDEO,
        "com.google.android.youtube" to VIDEO,
        "com.amazon.amazonvideo.livingroom" to VIDEO,
        "com.amazon.avod.thirdpartyclient" to VIDEO,
        "com.hulu.livingroomplus" to VIDEO,
        "com.hulu.plus" to VIDEO,
        "com.disney.disneyplus" to VIDEO,
        "com.wbd.stream" to VIDEO,
        "com.hbo.hbonow" to VIDEO,
        "com.apple.atve.android.appletv" to VIDEO,
        "com.apple.atve.sony.appletv" to VIDEO,
        "com.plexapp.android" to VIDEO,
        "com.tubitv" to VIDEO,
        "com.peacocktv.peacockandroid" to VIDEO,
        "com.cbs.app" to VIDEO,
        // Music
        "com.spotify.tv.android" to MUSIC,
        "com.spotify.music" to MUSIC,
        "com.pandora.android" to MUSIC,
        "com.amazon.mp3" to MUSIC,
        "com.google.android.apps.youtube.music" to MUSIC,
        "com.aspiro.tidal" to MUSIC,
        "com.deezer.android.app" to MUSIC
    )

    fun categoryFor(pkg: String, androidCategory: Int): String {
        CURATED[pkg]?.let { return it }
        return when (androidCategory) {
            CAT_VIDEO -> VIDEO
            CAT_AUDIO -> MUSIC
            CAT_GAME -> GAMES
            CAT_SOCIAL -> SOCIAL
            // Tools & other: image, news, maps, productivity,
            // accessibility and anything undeclared all land in Other.
            CAT_IMAGE, CAT_NEWS, CAT_MAPS, CAT_PRODUCTIVITY, CAT_ACCESSIBILITY -> OTHER
            else -> OTHER
        }
    }

    /** One app, as the grouping needs to see it. */
    data class Item(val pkg: String, val label: String, val androidCategory: Int)

    /**
     * Group [items] into (category, apps) sections: categories in
     * [ORDER], non-empty only, apps alphabetical (case-insensitive)
     * inside each section.
     */
    fun sections(items: List<Item>): List<Pair<String, List<Item>>> {
        val byCategory = items.groupBy { categoryFor(it.pkg, it.androidCategory) }
        return ORDER.mapNotNull { category ->
            val apps = byCategory[category] ?: return@mapNotNull null
            if (apps.isEmpty()) null
            else category to apps.sortedBy { it.label.lowercase() }
        }
    }
}
