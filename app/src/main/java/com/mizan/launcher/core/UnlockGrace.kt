package com.mizan.launcher.core

/**
 * Unlock grace window (v1.9) — pure logic, no Android imports.
 *
 * Haythem's ask: don't re-ask for the arrow pattern on EVERY locked
 * item. After one successful pattern verification, further gates
 * pass silently until the per-profile window lapses — "Re-ask for
 * pattern": Every time (0, the default and pre-v1.9 behaviour) /
 * After 5 min / After 15 min / After 30 min.
 *
 * The stamp itself is ONE device-level timestamp (Prefs.lastUnlockAt)
 * — not per profile, not per gate — so unlocking one app also covers
 * Settings and the guest-exit gate inside the window. Two hard rules
 * live outside this object, at the call sites:
 * - every successful VERIFICATION stamps (pattern-management prompts
 *   — set/change/clear — are not gates and never consult grace);
 * - switching profiles CLEARS the stamp: grace never crosses a
 *   profile boundary (see Prefs.switchProfile).
 *
 * Expiry is exact: at `now - last == window` the grace is over and
 * the gate prompts. A stamp in the future (clock moved backwards)
 * never grants grace.
 */
object UnlockGrace {

    /** The selectable windows, in minutes. 0 = every time. */
    val WINDOWS_MIN: List<Int> = listOf(0, 5, 15, 30)

    /** Picker labels, parallel to [WINDOWS_MIN]. */
    val WINDOW_LABELS: List<String> = listOf(
        "Every time", "After 5 minutes", "After 15 minutes", "After 30 minutes"
    )

    /** Coerce any stored value to a legal window (garbage → every time). */
    fun normalize(minutes: Int): Int = if (minutes in WINDOWS_MIN) minutes else 0

    /** The picker label for a window. */
    fun labelFor(minutes: Int): String =
        WINDOW_LABELS[WINDOWS_MIN.indexOf(normalize(minutes))]

    /**
     * True when a gate may pass silently: a positive window, a real
     * stamp, and [now] still strictly inside the window.
     */
    fun withinGrace(windowMin: Int, lastUnlockAt: Long, now: Long): Boolean {
        val w = normalize(windowMin)
        if (w <= 0) return false
        if (lastUnlockAt <= 0L) return false
        if (now < lastUnlockAt) return false // clock went backwards — never grant
        return now - lastUnlockAt < w * 60_000L
    }
}
