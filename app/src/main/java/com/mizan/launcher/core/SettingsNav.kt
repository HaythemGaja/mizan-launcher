package com.mizan.launcher.core

/**
 * Two-column Settings navigation — pure state machine, no Android imports.
 *
 * Roku-style layout: the LEFT column is the category list, the RIGHT
 * column is the selected category's controls. This machine tracks which
 * category is selected and which column holds focus; the Android side
 * applies it to real views (moving focus, highlighting the selection)
 * and consults it for BACK / arrow-key decisions:
 *
 * - RIGHT or OK in the left column → focus moves into the detail pane
 *   ([enterDetail]).
 * - LEFT (from the detail pane) or BACK (from the detail pane) → focus
 *   returns to the category list ([backToList]).
 * - BACK in the left column → exit Settings (the machine reports
 *   [backToList] as a no-op there; the Activity finishes instead).
 * - Selection moves CLAMP at the top/bottom of the category list
 *   (no wrap), matching Roku's settings list.
 * - The selected category is a persistent state, not just "where focus
 *   is": the detail pane always renders [selectedId], and the left
 *   column highlights it whether focus is left or right.
 */
class SettingsNavMachine(categoryIds: List<String>) {

    /** Category ids in display order; never empty in practice. */
    val categories: List<String> = categoryIds.toList()

    /** Index of the selected category (its controls fill the right pane). */
    var selectedIndex: Int = 0
        private set

    /** Which column holds focus: [COLUMN_LEFT] (list) or [COLUMN_RIGHT] (detail). */
    var focusColumn: Int = COLUMN_LEFT
        private set

    val selectedId: String
        get() = categories.getOrElse(selectedIndex) { "" }

    val inDetail: Boolean get() = focusColumn == COLUMN_RIGHT

    /** Select a category (its detail replaces the right pane's contents). */
    fun select(index: Int): Boolean {
        if (categories.isEmpty()) return false
        val clamped = index.coerceIn(0, categories.size - 1)
        val changed = clamped != selectedIndex
        selectedIndex = clamped
        return changed
    }

    /** Move the selection by [delta] rows; clamps at both ends. True if it moved. */
    fun moveSelection(delta: Int): Boolean = select(selectedIndex + delta)

    /** RIGHT / OK in the list: focus enters the detail pane. True if it changed. */
    fun enterDetail(): Boolean {
        if (inDetail) return false
        focusColumn = COLUMN_RIGHT
        return true
    }

    /**
     * LEFT / BACK from the detail pane: focus returns to the list.
     * Returns false when focus is already in the list — the caller
     * treats BACK-there as "exit Settings".
     */
    fun backToList(): Boolean {
        if (!inDetail) return false
        focusColumn = COLUMN_LEFT
        return true
    }

    /** Sync after real focus lands in a column (e.g. system focus search). */
    fun noteFocus(column: Int) {
        if (column == COLUMN_LEFT || column == COLUMN_RIGHT) focusColumn = column
    }

    companion object {
        const val COLUMN_LEFT = 0
        const val COLUMN_RIGHT = 1
    }
}
