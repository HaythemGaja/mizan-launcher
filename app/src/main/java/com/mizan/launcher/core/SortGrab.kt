package com.mizan.launcher.core

/**
 * Hold-and-drop sorting for the Settings "Sort sections" page
 * (v3.4, ask 50) — the pure state machine behind the D-pad
 * interaction. The TV remote has no touch dragging, so the
 * interaction is: hold OK on a row to GRAB it, D-pad up/down to
 * MOVE it live, OK to DROP it, Back to CANCEL.
 *
 * The machine owns only the ORDER: grabbing snapshots the order,
 * moves swap the grabbed row with its neighbour (edges are
 * no-ops), dropping commits the moved order, cancelling restores
 * the snapshot. Persistence lives with the caller (the Settings
 * page writes the store on every move, exactly like the existing
 * Up/Down buttons, and writes the snapshot back on cancel) — so
 * a cancelled grab changes nothing, and a dropped grab is already
 * saved when the page rebuilds.
 *
 * Sits ALONGSIDE the existing Up/Down/Hide controls (ask 50 does
 * not remove them): the same rows offer both, and both edit the
 * same stores (TrackerSections for Tracker/Watch, LayoutStore
 * rowOrder for TV).
 */
object SortGrab {

    /** [order] is the live order; [grabbed] the held row's key (null when idle). */
    data class State(
        val order: List<String>,
        val grabbed: String?,
        val snapshot: List<String>
    ) {
        companion object {
            fun idle(order: List<String>): State =
                State(order = order, grabbed = null, snapshot = order)
        }
    }

    /** Grab [key]: snapshots the order. Unknown keys and re-grabs are no-ops. */
    fun grab(state: State, key: String): State {
        if (state.grabbed != null) return state
        if (key !in state.order) return state
        return state.copy(grabbed = key, snapshot = state.order)
    }

    /** Move the grabbed row one step ([dir] −1 = up, +1 = down). Edges are no-ops. */
    fun move(state: State, dir: Int): State {
        val key = state.grabbed ?: return state
        if (dir == 0) return state
        val idx = state.order.indexOf(key)
        if (idx < 0) return state
        val to = idx + dir
        if (to < 0 || to >= state.order.size) return state
        val next = state.order.toMutableList()
        next[idx] = state.order[to]
        next[to] = key
        return state.copy(order = next)
    }

    /** Drop: the moved order stands, the grab ends. */
    fun drop(state: State): State =
        state.copy(grabbed = null, snapshot = state.order)

    /** Cancel: the snapshot order is restored, the grab ends. */
    fun cancel(state: State): State =
        state.copy(order = state.snapshot, grabbed = null)
}
