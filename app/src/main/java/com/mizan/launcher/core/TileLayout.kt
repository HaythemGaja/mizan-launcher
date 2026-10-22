package com.mizan.launcher.core

/**
 * Uniform home-screen tile geometry — pure functions, no Android imports.
 *
 * v1.2 rule (Haythem): EVERY tile on the home screen — apps, folders,
 * inputs, the "new folder" tile — renders at ONE size. The app-size
 * setting (compact / standard / large) scales all tiles equally; there
 * is no per-app and no per-row size difference.
 *
 * The size derives from the inputs that genuinely constrain it: the
 * screen width, the grid column count (tiles in a grid row must fit),
 * and the size setting (a uniform scale around the fitted width).
 * Horizontal-scroller rows (favorites, folders, inputs) use the exact
 * same metrics, so a tile never changes size when it moves rows.
 *
 * Name labels are never part of the tile's layout box: a tile's name is
 * an overlay shown only while the tile has focus (see [labelVisible]),
 * so showing/hiding it cannot resize the tile or shift the row.
 */
object TileLayout {

    /** Horizontal content padding of the home screen, in dp (both sides). */
    const val SIDE_PADDING_DP = 48
    /** Extra horizontal inset of a scroller row, in dp (both sides). */
    const val ROW_PADDING_DP = 10
    /** Tile margin, in dp (each side). */
    const val TILE_MARGIN_DP = 5

    /** Uniform scale applied by the app-size setting. */
    fun sizeScale(sizeKey: String): Float = when (sizeKey) {
        "compact" -> 0.82f
        "large" -> 1.18f
        else -> 1.0f
    }

    /** Pixel size of one tile and of the icon centred inside it. */
    data class Metrics(val tileWidthPx: Int, val tileHeightPx: Int, val iconPx: Int)

    /**
     * The one tile size for the whole home screen.
     *
     * Fit width = what one column of the grid gets (screen minus side
     * and row padding, split by [columns], minus the tile's own
     * margins). The setting's scale is applied to that fit; tile height
     * is icon + fixed vertical padding (the focus name overlays the
     * bottom — it needs no reserved space).
     *
     * [boost] (v1.7) is an extra uniform multiplier layered on the
     * setting's scale — the iPad home grid passes
     * [Design.IPAD_TILE_BOOST] so its cells render ~25% larger than
     * the same profile's TV tiles. Default 1f = no change anywhere.
     */
    fun tileMetrics(
        screenWidthPx: Int,
        density: Float,
        sizeKey: String,
        columns: Int,
        boost: Float = 1f
    ): Metrics {
        val cols = columns.coerceIn(3, 10)
        val insetsPx = ((SIDE_PADDING_DP + ROW_PADDING_DP) * 2 * density).toInt()
        val perColumnPx = ((screenWidthPx - insetsPx).coerceAtLeast(0)) / cols
        val fitWidthPx = (perColumnPx - (TILE_MARGIN_DP * 2 * density).toInt())
            .coerceAtLeast((40 * density).toInt())
        val tileWidthPx = (fitWidthPx * sizeScale(sizeKey) * boost).toInt()
            .coerceAtLeast((40 * density).toInt())
        val iconPx = (tileWidthPx * 0.58f).toInt().coerceAtLeast((24 * density).toInt())
        val tileHeightPx = iconPx + (30 * density).toInt()
        return Metrics(tileWidthPx, tileHeightPx, iconPx)
    }

    /**
     * Continue Watching cards: ONE uniform wide card size for the row —
     * two tile widths plus the gutter, at the standard tile height —
     * and the same focus-label rule as every other tile. [boost]
     * (v1.7 iPad grid) scales the card with its tiles.
     */
    fun cardMetrics(
        screenWidthPx: Int,
        density: Float,
        sizeKey: String,
        columns: Int,
        boost: Float = 1f
    ): Metrics {
        val t = tileMetrics(screenWidthPx, density, sizeKey, columns, boost)
        val gutterPx = (TILE_MARGIN_DP * 2 * density).toInt()
        return Metrics(t.tileWidthPx * 2 + gutterPx, t.tileHeightPx, t.iconPx)
    }

    /**
     * v2.0 portrait poster-card metrics (Tracker view): the card stands
     * [Design.POSTER_HEIGHT_TILES] uniform-tile heights tall and takes
     * its width from the 2:3 poster ratio, so a TMDB poster fills it
     * edge-to-edge at CENTER_CROP with no letterbox. Derived from
     * [tileMetrics] so the profile size key and column count scale
     * posters exactly like tiles.
     */
    fun posterMetrics(
        screenWidthPx: Int,
        density: Float,
        sizeKey: String,
        columns: Int
    ): Metrics {
        val t = tileMetrics(screenWidthPx, density, sizeKey, columns)
        val heightPx = (t.tileHeightPx * Design.POSTER_HEIGHT_TILES).toInt()
        val widthPx = heightPx * 2 / 3
        return Metrics(widthPx, heightPx, t.iconPx)
    }

    /**
     * The label rule: a tile's name is visible ONLY while that tile has
     * focus. Kept as a named function so the sim can pin the rule and
     * the UI has exactly one place that decides it.
     */
    fun labelVisible(hasFocus: Boolean): Boolean = hasFocus
}
