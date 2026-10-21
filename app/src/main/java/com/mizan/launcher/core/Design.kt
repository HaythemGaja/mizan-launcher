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

    // ---- hero (home top zone) ------------------------------------------
    const val HERO_CLOCK_SP = 54f
    const val HERO_DATE_SP = 18f
    const val HERO_WEATHER_SP = 20f
    const val HERO_STATUS_SP = 16f

    /** Small-caps section headers: letter spacing + size, one style. */
    const val SECTION_HEADER_SP = 15f
    const val SECTION_HEADER_TRACKING = 0.14f
    const val SECTION_RULE_ALPHA = 0x59
}
