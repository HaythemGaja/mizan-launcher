package com.mizan.launcher.core

/**
 * v2.0: the Tracker hero auto-rotation state machine (pure — the
 * activity owns the Handler, this file owns the rules).
 *
 * Rules (spec §18):
 *  - advance every [INTERVAL_MS] while rotating;
 *  - rotation needs at least 2 candidates;
 *  - while focus is on a hero action button the timer is HELD (no
 *    advance, however late the tick arrives);
 *  - when focus leaves the hero, the next advance is due [RESUME_MS]
 *    later — not immediately, so the user sees the current hero for a
 *    beat before it moves on;
 *  - the Next button advances immediately and restarts the interval.
 */
object HeroRotator {

    const val INTERVAL_MS = 10_000L
    const val RESUME_MS = 3_000L

    data class State(
        val index: Int,
        val count: Int,
        val focusHeld: Boolean,
        val dueAt: Long
    )

    /** Fresh state for [count] candidates starting at [index]. */
    fun initial(index: Int, count: Int, now: Long): State {
        val n = count.coerceAtLeast(0)
        val i = if (n > 0) index.coerceIn(0, n - 1) else 0
        return State(i, n, focusHeld = false, dueAt = now + INTERVAL_MS)
    }

    fun canRotate(s: State): Boolean = s.count >= 2

    /**
     * Timer tick at [now]: the advanced state when an advance is due,
     * or null when nothing should happen (too early, focus held, or
     * fewer than 2 candidates).
     */
    fun tick(s: State, now: Long): State? {
        if (!canRotate(s) || s.focusHeld || now < s.dueAt) return null
        return s.copy(index = (s.index + 1) % s.count, dueAt = now + INTERVAL_MS)
    }

    /** The Next button: advance now (wrapping) and restart the timer. */
    fun next(s: State, now: Long): State {
        if (s.count <= 0) return s
        return s.copy(index = (s.index + 1) % s.count, dueAt = now + INTERVAL_MS)
    }

    /** Focus entered a hero action button: hold the timer. */
    fun focusGained(s: State): State = s.copy(focusHeld = true)

    /** Focus left the hero entirely: resume [RESUME_MS] from [now]. */
    fun focusReleased(s: State, now: Long): State =
        s.copy(focusHeld = false, dueAt = now + RESUME_MS)

    /** The candidate list changed size (fresh snapshot): clamp + retime. */
    fun withCount(s: State, count: Int, now: Long): State {
        val n = count.coerceAtLeast(0)
        val i = if (n > 0) s.index.coerceIn(0, n - 1) else 0
        return if (n == s.count && i == s.index) s
        else s.copy(index = i, count = n, dueAt = now + INTERVAL_MS)
    }
}
