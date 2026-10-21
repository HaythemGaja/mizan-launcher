package com.mizan.launcher.core

/**
 * Settings screen fit computation (v1.3) — pure, no Android imports.
 *
 * Haythem reported the v1.2 Settings sometimes didn't fit the screen:
 * fixed paddings and text sizes overflow short displays. v1.3 derives
 * every size from the actual window:
 * - Left pane width = 30% of the window width, clamped to 260–360dp.
 * - Short screens (height < 800dp) run COMPACT metrics: smaller
 *   paddings, header and row text — the content still scrolls, but
 *   far less of it hides below the fold.
 * - Narrow windows (< 600dp wide) STACK the panes (v1.2 behaviour).
 * - The right pane's bottom padding is at least one full row height
 *   ([Metrics.detailBottomPadDp] >= row height), so the last control
 *   can always scroll fully into view — nothing is ever clipped by
 *   the bottom edge.
 */
object SettingsFit {

    /** Height (dp) below which compact metrics apply. */
    const val COMPACT_HEIGHT_DP = 800

    data class Metrics(
        /** Left category pane width in dp (whole width when stacked). */
        val leftPaneWidthDp: Int,
        /** True on short screens: smaller paddings and type. */
        val compact: Boolean,
        /** True when the window is narrow: panes stack vertically. */
        val stacked: Boolean,
        /** Outer padding of both panes. */
        val panePadDp: Int,
        /** Header text size (sp). */
        val headerSp: Float,
        /** Category row text size (sp). */
        val navTextSp: Float,
        /** Detail row title text size (sp). */
        val rowTextSp: Float,
        /** Detail info/body text size (sp). */
        val infoTextSp: Float,
        /** Vertical padding inside one detail row (dp). */
        val rowPadVDp: Int,
        /** Height of one detail row (dp) — also the scroll headroom unit. */
        val rowHeightDp: Int,
        /** Right pane bottom padding (dp): >= one row height, always. */
        val detailBottomPadDp: Int
    )

    /**
     * Compute fit metrics from the window's pixel size and density.
     * Never throws; degenerate inputs degrade to compact defaults.
     */
    fun compute(screenWidthPx: Int, screenHeightPx: Int, density: Float): Metrics {
        val d = if (density > 0f) density else 1f
        val widthDp = (screenWidthPx / d).toInt()
        val heightDp = (screenHeightPx / d).toInt()
        val stacked = widthDp in 1 until 600
        val compact = heightDp < COMPACT_HEIGHT_DP
        val leftWidth = if (stacked) widthDp.coerceAtLeast(0)
            else (widthDp * 30 / 100).coerceIn(260, 360)
        return if (compact) {
            Metrics(
                leftPaneWidthDp = leftWidth,
                compact = true,
                stacked = stacked,
                panePadDp = 16,
                headerSp = 20f,
                navTextSp = 17f,
                rowTextSp = 17f,
                infoTextSp = 14f,
                rowPadVDp = 8,
                rowHeightDp = 52,
                detailBottomPadDp = 64
            )
        } else {
            Metrics(
                leftPaneWidthDp = leftWidth,
                compact = false,
                stacked = stacked,
                panePadDp = 28,
                headerSp = 24f,
                navTextSp = 19f,
                rowTextSp = 19f,
                infoTextSp = 16f,
                rowPadVDp = 14,
                rowHeightDp = 64,
                detailBottomPadDp = 80
            )
        }
    }
}

/**
 * iPhone-style switch appearance logic (v1.3) — pure.
 *
 * The Android view (ui/SwitchPillView) only draws what this decides:
 * OFF = grey track, knob left; ON = iOS green track, knob right.
 * Colours are ARGB ints (0xFF34C759 green / 0xFF3A3A3C grey are the
 * familiar iOS switch colours).
 */
object SwitchLogic {
    val TRACK_ON: Int = 0xFF34C759.toInt()
    val TRACK_OFF: Int = 0xFF3A3A3C.toInt()
    val KNOB: Int = 0xFFFFFFFF.toInt()

    /** Track colour for a switch state. */
    fun trackColor(on: Boolean): Int = if (on) TRACK_ON else TRACK_OFF

    /** Knob travel: 0f = fully left (off), 1f = fully right (on). */
    fun knobFraction(on: Boolean): Float = if (on) 1f else 0f

    /** A press on a switch always flips it. */
    fun toggled(on: Boolean): Boolean = !on
}
