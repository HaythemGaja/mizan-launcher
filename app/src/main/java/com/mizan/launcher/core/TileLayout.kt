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

    /**
     * Home media-row card scale (v3.4, ask 52): Tracker/Watch/IPTV
     * home rows render their poster cards at 0.75× the standard
     * card width (height follows proportionally, so the 16:9 frame
     * and the 2:3 poster fit inside it are unchanged in shape —
     * only smaller, so visibly more cards fit per row). App tiles
     * never pass through this scale (their size is the v1.2
     * uniform tile rule, untouched), and neither does the hero.
     */
    const val HOME_ROW_CARD_SCALE: Float = 0.75f

    /** A standard card width scaled for a home media row (floor, min 1px). */
    fun homeRowCardWidthPx(cardWidthPx: Int): Int =
        (cardWidthPx * HOME_ROW_CARD_SCALE).toInt().coerceAtLeast(1)

    /** Uniform scale applied by the app-size setting. */
    fun sizeScale(sizeKey: String): Float = when (sizeKey) {
        "compact" -> 0.82f
        "large" -> 1.18f
        else -> 1.0f
    }

    /** Pixel size of one tile and of the icon centred inside it. */
    data class Metrics(val tileWidthPx: Int, val tileHeightPx: Int, val iconPx: Int)

    /**
     * The raw one-column fit at [columns] — NO app-size multiplier.
     * [tileMetrics] layers the setting's scale on top for free-flowing
     * rows; [gridColumns]/[gridTileMetrics] use this raw fit so a
     * GRID row can never exceed the screen (see below).
     */
    private fun rawTileMetrics(screenWidthPx: Int, density: Float, columns: Int): Metrics {
        val cols = columns.coerceIn(1, 10)
        val insetsPx = ((SIDE_PADDING_DP + ROW_PADDING_DP) * 2 * density).toInt()
        val perColumnPx = ((screenWidthPx - insetsPx).coerceAtLeast(0)) / cols
        val fitWidthPx = (perColumnPx - (TILE_MARGIN_DP * 2 * density).toInt())
            .coerceAtLeast((40 * density).toInt())
        val iconPx = (fitWidthPx * 0.58f).toInt().coerceAtLeast((24 * density).toInt())
        val tileHeightPx = iconPx + (30 * density).toInt()
        return Metrics(fitWidthPx, tileHeightPx, iconPx)
    }

    /**
     * The one tile size for free-flowing (horizontal-scroller) rows.
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
        val raw = rawTileMetrics(screenWidthPx, density, columns)
        val tileWidthPx = (raw.tileWidthPx * sizeScale(sizeKey) * boost).toInt()
            .coerceAtLeast((40 * density).toInt())
        val iconPx = (tileWidthPx * 0.58f).toInt().coerceAtLeast((24 * density).toInt())
        val tileHeightPx = iconPx + (30 * density).toInt()
        return Metrics(tileWidthPx, tileHeightPx, iconPx)
    }

    /**
     * v3.17 (app-grid fit): how many columns a GRID row really gets
     * at this app size.
     *
     * The app-size setting used to be a pure multiplier on a fitted
     * cell, which is fine for a horizontal scroller but impossible for
     * a grid: at "large" every row of [wish] cells became 18% wider
     * than the screen and the last column was clipped (the same class
     * of bug the Library grid fixed in v3.16). A grid instead turns
     * the setting into a COLUMN BUDGET — large = fewer, wider cells;
     * compact = more, narrower cells — and the tile width is then
     * derived from that real count, so the row always fits exactly.
     *
     * The walk-down guard keeps it honest on narrow screens and with
     * extreme font/scale combinations: a count only wins when
     * `n * (tile + both margins) <= usable width`.
     */
    fun gridColumns(
        screenWidthPx: Int,
        density: Float,
        sizeKey: String,
        wishColumns: Int
    ): Int {
        val wish = wishColumns.coerceIn(3, 10)
        val target = when (sizeKey) {
            "large" -> (wish / sizeScale("large")).toInt().coerceAtLeast(1)
            "compact" -> (wish / sizeScale("compact")).toInt().coerceAtMost(10)
            else -> wish
        }.coerceIn(1, 10)
        val marginPx = (TILE_MARGIN_DP * 2 * density).toInt()
        val usable = (screenWidthPx -
            ((SIDE_PADDING_DP + ROW_PADDING_DP) * 2 * density).toInt()).coerceAtLeast(0)
        var n = target
        while (n > 1) {
            val cell = rawTileMetrics(screenWidthPx, density, n).tileWidthPx + marginPx
            if (n * cell <= usable) return n
            n--
        }
        return 1
    }

    /**
     * The one tile size for a GRID row — the raw fit at the column
     * count [gridColumns] selected, with no second size multiplier.
     * Pair with `gridColumns(...)` when chunking rows: the two use the
     * same count, so the row sums to the usable width instead of
     * running off the right edge.
     */
    fun gridTileMetrics(
        screenWidthPx: Int,
        density: Float,
        sizeKey: String,
        wishColumns: Int,
        boost: Float = 1f
    ): Metrics {
        val cols = gridColumns(screenWidthPx, density, sizeKey, wishColumns)
        val raw = rawTileMetrics(screenWidthPx, density, cols)
        if (boost == 1f) return raw
        val marginPx = (TILE_MARGIN_DP * 2 * density).toInt()
        val maxWidth = ((screenWidthPx -
            ((SIDE_PADDING_DP + ROW_PADDING_DP) * 2 * density).toInt()) / cols) - marginPx
        val tileWidthPx = (raw.tileWidthPx * boost).toInt()
            .coerceAtMost(maxWidth.coerceAtLeast((40 * density).toInt()))
        val iconPx = (tileWidthPx * 0.58f).toInt().coerceAtLeast((24 * density).toInt())
        return Metrics(tileWidthPx, iconPx + (30 * density).toInt(), iconPx)
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

    /** [cardMetrics] for a GRID column budget (see [gridColumns]). */
    fun gridCardMetrics(
        screenWidthPx: Int,
        density: Float,
        sizeKey: String,
        columns: Int,
        boost: Float = 1f
    ): Metrics {
        val t = gridTileMetrics(screenWidthPx, density, sizeKey, columns, boost)
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
