package com.mizan.launcher.core

/**
 * Design tokens (v1.6) — the ONE source of truth for the launcher's
 * visual system, introduced when Haythem's on-device verdict on v1.5
 * was "looks ugly, not organized or clean". Before v1.6 every screen
 * picked its own radii, paddings and alphas inline; the redesign
 * routes them all through here so home, Settings, Search, All Apps,
 * Forecast and the folder overlay share one spacing scale, one set of
 * corner radii, one tile surface and one focus treatment.
 *
 * Pure values only (no Android imports) so the sim can sanity-check
 * the invariants: the spacing scale increases, the scrim is
 * bottom-weighted and inside a sane alpha band, radii are positive.
 */
object Design {

    // ---- spacing scale (dp): every margin/padding comes from here --
    const val SPACE_S = 8
    const val SPACE_M = 16
    const val SPACE_L = 24
    const val SPACE_XL = 32

    /** The single content margin on TV: all home content aligns to it. */
    const val CONTENT_MARGIN_DP = 48

    /** Vertical gap between home rows; spacing does the organizing. */
    const val ROW_GAP_DP = 28

    /** The first content row starts at this fixed offset below the hero. */
    const val HERO_TO_ROWS_GAP_DP = 20

    // ---- corner radii (dp) -----------------------------------------
    const val RADIUS_TILE = 16
    const val RADIUS_PANEL = 20
    const val RADIUS_CHIP = 12

    // ---- surfaces ----------------------------------------------------
    /** Resting tile fill: flat white at 8% — quiet, no border. */
    val TILE_FILL: Int = 0x14FFFFFF

    /** Grouping panel fill (quick-tile panel, Settings cards): black 25%. */
    val PANEL_FILL: Int = 0x40000000

    /** Focus: accent outline width, and the tint mixed under it. */
    const val FOCUS_STROKE_DP = 3
    val FOCUS_FILL: Int = 0x38000000

    /** The only motion: a slight scale on focus, one value everywhere. */
    const val FOCUS_SCALE = 1.06f

    // ---- wallpaper scrim ----------------------------------------------
    // One gradient over every wallpaper (photo / gradient / weather
    // art): ~15% black at the top deepening to ~35% at the bottom, so
    // text and tiles always sit on a calm, readable field.
    const val SCRIM_TOP_ALPHA = 0x26
    const val SCRIM_BOTTOM_ALPHA = 0x59

    /** ARGB gradient stops for the scrim, top → bottom. */
    fun scrimColors(): IntArray = intArrayOf(
        (SCRIM_TOP_ALPHA shl 24),
        (SCRIM_BOTTOM_ALPHA shl 24)
    )

    // ---- iPad home style (v1.7) --------------------------------------
    /** Grid cells in iPad style render this much larger than the
     * profile's app-size scale alone would make them. */
    const val IPAD_TILE_BOOST = 1.25f
    /** The always-on name under every iPad grid tile. */
    const val IPAD_LABEL_SP = 13f
    /** Slim-strip text (date, clock, weather, network). */
    const val IPAD_STRIP_SP = 16f
    /** The floating dock's corner radius. */
    const val DOCK_RADIUS_DP = 28
    /** The iPad folder panel's corner radius. */
    const val IPAD_FOLDER_PANEL_RADIUS_DP = 28
    /** Mostly-opaque dark fill for the iPad folder panel. */
    val IPAD_PANEL_FILL: Int = 0xE6222222.toInt()
    /** Squircle mask radius as a fraction of the icon size (the
     * "rounded" mask uses 0.22 — the squircle is visibly rounder). */
    const val SQUIRCLE_RADIUS_FRACTION = 0.32f

    // ---- iPad polish (v1.9): airier, wallpaper-forward --------------
    // Haythem's verdict on v1.7's iPad style was "ugly": the grid sat
    // edge-to-edge on a hard panel feel with tight cells. v1.9 gives
    // the grid room to breathe (capped width, ~1.5× cell gaps), drops
    // every opaque surface behind it (scrim only), and moves the
    // dock + strip to ONE lighter translucent material (DOCK_FILL)
    // so the wallpaper stays the picture.
    /** The iPad grid never spans more than this fraction of the screen. */
    const val IPAD_GRID_WIDTH_FRACTION = 0.86f
    /** iPad grid cell margins scale by this over the TV tile margin. */
    const val IPAD_GRID_GAP_SCALE = 1.5f
    /** The always-on tile label's distance from the tile's bottom edge. */
    const val IPAD_LABEL_BOTTOM_DP = 2
    /** The iPad chrome material (dock + top strip): black ~18% —
     * visibly lighter than PANEL_FILL's 25%, one language for both. */
    val DOCK_FILL: Int = 0x2E000000
    /** Dock padding: slimmer than v1.7's 12/12. */
    const val DOCK_PAD_H_DP = 14
    const val DOCK_PAD_V_DP = 7
    /** Secondary strip text (date, network, bluetooth): smaller +
     * tracked out, so the strip reads lighter than the clock. */
    const val IPAD_STRIP_SECONDARY_SP = 13f
    const val IPAD_STRIP_LETTER_SPACING = 0.04f
    /** The strip's vertical padding when it carries a background. */
    const val IPAD_STRIP_PAD_V_DP = 4

    // ---- hero (home top zone) ------------------------------------------
    const val HERO_CLOCK_SP = 54f
    const val HERO_DATE_SP = 18f
    const val HERO_WEATHER_SP = 20f
    const val HERO_STATUS_SP = 16f

    /** Small-caps section headers: letter spacing + size, one style. */
    const val SECTION_HEADER_SP = 15f
    const val SECTION_HEADER_TRACKING = 0.14f
    const val SECTION_RULE_ALPHA = 0x59

    // ---- Netflix look (v2.0, Tracker view ONLY) ----------------------
    // The Tracker home restyles to the Netflix language: a near-black
    // page, the hero backdrop as the full-bleed screen background, a
    // red NEW badge, and portrait 2:3 poster cards. TV and iPad views
    // never read these tokens.
    /** The Tracker page colour — background regardless of theme/wallpaper. */
    val NETFLIX_BG: Int = 0xFF141414.toInt()
    /** The NEW badge / brand red. */
    val NETFLIX_RED: Int = 0xFFE50914.toInt()
    /** Hero title size (lower-left block, over the backdrop). */
    const val NETFLIX_HERO_TITLE_SP = 44f
    /** Hero overview size (up to 3 lines). */
    const val NETFLIX_HERO_OVERVIEW_SP = 16f
    /** The NEW badge text size. */
    const val NETFLIX_BADGE_SP = 13f
    /** Primary hero button: white fill, near-black text. */
    val NETFLIX_BUTTON_FILL: Int = 0xFFFFFFFF.toInt()
    val NETFLIX_BUTTON_TEXT: Int = 0xFF141414.toInt()
    /** Secondary hero buttons: translucent dark fill, white text. */
    val NETFLIX_BUTTON2_FILL: Int = 0x8C2B2B2B.toInt()
    /**
     * Poster card height, in multiples of the uniform tile height: a
     * portrait card stands two tiles tall, its width derived 2:3.
     * (The v2.0 brief's "≈0.9× the Continue wide card's height" would
     * have SHRUNK the cards below v1.8's — the defect was posters not
     * shown full; spec §18 records the substitution.)
     */
    const val POSTER_HEIGHT_TILES = 2f
    /** The hero zone's height as a fraction of the screen height:
     * the backdrop + lower-left block own the top ~62%, rows sit on
     * the fade below it. */
    const val HERO_ZONE_FRACTION = 0.62f
}
