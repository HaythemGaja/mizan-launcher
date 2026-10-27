package com.mizan.launcher.core

/**
 * Startup-health state machine (v3.0, spec §30) — the self-healing
 * answer to a deterministic startup crash loop.
 *
 * The failure this kills: a build (or a state the build chokes on)
 * that dies during startup dies the same way on EVERY Home press —
 * the user is locked out of his own home screen with no in-app way
 * to reach Diagnostics. The machine is deliberately tiny and pure
 * (data/StartupHealthStore owns the persisted counter):
 *
 *  - every process launch BEGINS a startup: the uncleared counter
 *    increments (persisted with commit() BEFORE composition runs);
 *  - when the home completes its first full render and the process
 *    then survives [SURVIVAL_MS], the startup is CLEARED: the
 *    counter resets to 0;
 *  - a launch that finds the counter already at [SAFE_SHELL_THRESHOLD]
 *    (two consecutive startups died uncleared) runs in the SAFE
 *    SHELL: a plain TV-style home with no hero/discovery/IPTV
 *    composition and a visible notice, Diagnostics reachable.
 *    A safe-shell launch that itself completes + survives clears
 *    the counter like any other, so a fixed state returns to
 *    normal on the next launch — and a still-broken one cycles
 *    (two deaths, one safe shell) instead of bricking.
 *
 * Only CONSECUTIVE uncleared startups count: any cleared startup
 * resets the run.
 */
object StartupHealth {

    /** Consecutive uncleared startups that arm the safe shell. */
    const val SAFE_SHELL_THRESHOLD = 2

    /** How long the process must survive past the full render to clear. */
    const val SURVIVAL_MS = 10_000L

    enum class Mode { NORMAL, SAFE_SHELL }

    /** The mode for a launch that finds [unclearedStarts] on the counter. */
    fun modeFor(unclearedStarts: Int): Mode =
        if (unclearedStarts.coerceAtLeast(0) >= SAFE_SHELL_THRESHOLD) Mode.SAFE_SHELL
        else Mode.NORMAL

    /** The counter after a launch begins (it counts this launch until cleared). */
    fun onLaunch(unclearedStarts: Int): Int = unclearedStarts.coerceAtLeast(0) + 1

    /** The counter after a startup completes + survives: always zero. */
    fun onCleared(): Int = 0

    /**
     * v3.3 (ask 46): how long after a completed render the clear
     * waits, or null to HOLD it. A degraded build (whole-home
     * fallback / emergency root) never clears — that hold is what
     * keeps a deterministic composition crash arming the safe
     * shell. A SAFE-SHELL launch clears IMMEDIATELY on its full
     * render: the safe home is light by design, so its render
     * proves the process can compose a home, and waiting 10s
     * under memory pressure is exactly the trap that left him
     * stuck in safe mode (the TV kills the process inside the
     * window, so the counter could never clear). This does NOT
     * weaken the safety net: the next NORMAL launch still needs
     * the full 10s survival, and two uncleared normal deaths
     * still re-arm the safe shell via beginLaunch counting.
     */
    fun clearDelayMs(safeShell: Boolean, degraded: Boolean): Long? =
        if (degraded) null else if (safeShell) 0L else SURVIVAL_MS

    /** The safe shell's user-facing notice (his language: short, plain, actionable). */
    const val NOTICE_TEXT =
        "Sreeb started in safe mode after repeated start crashes. " +
            "Your apps are below — Diagnostics (Data & about) has an " +
            "'Exit safe mode' action to retry your normal home."

    /** The diagnostics line: the counter + the shell state, always present. */
    fun diagnosticsLine(unclearedStarts: Int, safeShell: Boolean): String =
        "startup health: $unclearedStarts uncleared start(s) · safe shell " +
            (if (safeShell) "ON" else "off")
}
