package com.mizan.launcher.core

/**
 * Hold-and-move reorder math (v1.3) — pure, no Android imports.
 *
 * In move mode the grabbed tile is repositioned with the D-pad arrows.
 * Every container (All Apps, Favorites, a folder's contents) is treated
 * as ONE grid wrapped by the current column count: items fill rows of
 * [columns] left-to-right, top-to-bottom, and the last row may be
 * partial. (Favorites / folders render as horizontal scrollers on a TV
 * wide enough to show one row, but their order still wraps by the same
 * column count, so UP/DOWN move between wrapped rows uniformly.)
 *
 * Rules (all sim-tested):
 * - LEFT swaps with the previous item — never wraps to the row above.
 * - RIGHT swaps with the next item — never wraps to the row below,
 *   and stops at the last item.
 * - UP swaps with the item one row up (index − columns); from the top
 *   row it is a no-op.
 * - DOWN swaps with the item one row down (index + columns); when no
 *   item sits below (including a hole in a partial last row) it is a
 *   no-op.
 * - OK drops (the caller persists the list); BACK cancels by restoring
 *   a snapshot taken when the tile was grabbed (the caller owns the
 *   snapshot; this object owns only the swap math).
 */
object GridMove {

    /**
     * Index the item at [index] would swap with for [dir], or −1 for a
     * no-op (edge of the grid / nothing below / invalid input).
     */
    fun targetIndex(size: Int, index: Int, dir: ArrowDir, columns: Int): Int {
        if (size <= 0 || index !in 0 until size) return -1
        val cols = columns.coerceAtLeast(1)
        val col = index % cols
        return when (dir) {
            ArrowDir.LEFT -> if (col == 0) -1 else index - 1
            ArrowDir.RIGHT -> if (col == cols - 1 || index == size - 1) -1 else index + 1
            ArrowDir.UP -> if (index - cols < 0) -1 else index - cols
            ArrowDir.DOWN -> if (index + cols > size - 1) -1 else index + cols
        }
    }

    /**
     * Swap the item at [index] toward [dir] inside [list].
     * Returns the item's new index, or −1 when the move is a no-op
     * (list untouched). The caller persists + re-renders on success.
     */
    fun gridMove(list: MutableList<String>, index: Int, dir: ArrowDir, columns: Int): Int {
        val target = targetIndex(list.size, index, dir, columns)
        if (target < 0) return -1
        val tmp = list[index]
        list[index] = list[target]
        list[target] = tmp
        return target
    }
}
