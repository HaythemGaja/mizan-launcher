package com.mizan.launcher.core

/**
 * The visible-window paging rule (v3.6, ask 53) — pure, no
 * Android imports. A home row inflates ONLY its first
 * window; the next page is appended when the user reaches
 * the window's near end. The window is the existing IPTV
 * view cap (20) for consistency across homes — window =
 * what fits on screen + one page, never the data size.
 *
 * View-level note: the home rows are HorizontalScrollView
 * + LinearLayout (no RecyclerView), so there is no
 * focus-near-end hook that survives the card focus ring
 * (Ui.focusLift owns each card's focus listener). The
 * near-end affordance is therefore a trailing "More"
 * tile: focusing it appends exactly the next page and
 * moves focus to the first new card (or drops the tile
 * when the row is complete). The rule below is what
 * that tile — and the sim — pin: first window only,
 * next page only, never the whole row.
 */
object VisibleWindow {

    /** First window size, = the IPTV HOME_ROW_VIEW_CAP. */
    const val FIRST_WINDOW: Int = 20

    /** How many pages a row of [total] items has at [window] per page. */
    fun pageCount(total: Int, window: Int = FIRST_WINDOW): Int {
        if (total <= 0 || window <= 0) return 0
        return (total + window - 1) / window
    }

    /** The [page]-th window's [start, end) item range, or null when the page does not exist. */
    fun windowFor(total: Int, page: Int, window: Int = FIRST_WINDOW): IntRange? {
        if (total <= 0 || window <= 0 || page < 0) return null
        val start = page * window
        if (start >= total) return null
        return start until minOf(total, start + window)
    }

    /** The next page index after [shown] items, or null when the row is complete. */
    fun nextPageIndex(shown: Int, total: Int, window: Int = FIRST_WINDOW): Int? {
        if (window <= 0 || shown < 0 || shown >= total) return null
        return shown / window
    }

    /** True when focus on [focusedIndex] (0-based, among [shown] inflated cards) should page in the next window. */
    fun shouldPageIn(focusedIndex: Int, shown: Int, total: Int, window: Int = FIRST_WINDOW): Boolean {
        if (shown >= total || shown <= 0) return false
        return focusedIndex >= shown - 3 && focusedIndex < shown
    }
}
