package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.StartupHealth

/**
 * The persisted half of core/StartupHealth (v3.0, spec §30).
 *
 * The counter lives in its OWN tiny prefs file ("mizan_health"),
 * not in "mizan_launcher": that file carries the multi-megabyte
 * tracker snapshot documents, and the increment must be durable
 * BEFORE composition runs — commit() rewrites the whole file, so
 * it has to be a small one. Every write is commit() (never
 * apply()): an apply() still sitting in the async queue when the
 * process dies mid-startup would lose the increment and the safe
 * shell could never arm. Every read goes through PrefsSafe, and
 * every operation is fail-open wrapped — a broken health store
 * degrades to NORMAL mode, never to a startup failure of its own.
 */
object StartupHealthStore {

    private const val FILE = "mizan_health"
    private const val K_UNCLEARED = "uncleared_starts"

    /** This process's launch decision, computed once in [beginLaunch]. */
    @Volatile
    var safeShellArmed: Boolean = false
        private set

    @Volatile
    private var begun = false

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The persisted counter (0 when unreadable). */
    fun unclearedCount(ctx: Context): Int =
        try {
            PrefsSafe.int(prefs(ctx), K_UNCLEARED, 0)
        } catch (e: Throwable) {
            0
        }

    /**
     * Begin this process's startup (App.onCreate, right after the
     * diagnostics install): read the counter, decide the mode,
     * persist counter+1 with commit(), remember the decision.
     * Idempotent per process. Returns the mode.
     */
    fun beginLaunch(ctx: Context): StartupHealth.Mode {
        if (begun) return if (safeShellArmed) StartupHealth.Mode.SAFE_SHELL else StartupHealth.Mode.NORMAL
        begun = true
        val mode = try {
            val p = prefs(ctx)
            val before = PrefsSafe.int(p, K_UNCLEARED, 0)
            val m = StartupHealth.modeFor(before)
            p.edit().putInt(K_UNCLEARED, StartupHealth.onLaunch(before)).commit()
            m
        } catch (e: Throwable) {
            StartupHealth.Mode.NORMAL
        }
        safeShellArmed = mode == StartupHealth.Mode.SAFE_SHELL
        if (safeShellArmed) {
            DiagLog.note("startup health: safe shell ON")
        }
        return mode
    }

    /**
     * The startup completed its full render and survived the
     * window: reset the counter (commit()). Called from
     * MainActivity's delayed survival hook; a process that dies
     * first never calls it, which is exactly the point.
     */
    fun markCleared(ctx: Context) {
        try {
            prefs(ctx).edit().putInt(K_UNCLEARED, StartupHealth.onCleared()).commit()
        } catch (e: Throwable) {
            // Best-effort: a stuck counter only means the safe
            // shell may arm once more than strictly needed.
        }
    }

    /** The diagnostics line (core-shaped) for this process's state. */
    fun diagnosticsLine(ctx: Context): String =
        StartupHealth.diagnosticsLine(unclearedCount(ctx), safeShellArmed)
}
