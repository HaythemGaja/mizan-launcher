package com.mizan.launcher.core

/**
 * "⚡ Speed up now" kill-list selection — pure function.
 *
 * v1.4 hosted the action on the Cleanup screen; v1.5 removed Cleanup
 * but kept Speed Up (Haythem's direction), relocated to Settings →
 * Home & launcher. The action asks ActivityManager to kill background
 * processes; the list of what may be signalled is decided here,
 * conservatively:
 * - never-list packages are excluded (core/NeverList — the TV must
 *   keep what it needs to run),
 * - system-flagged apps are excluded (killing framework processes is
 *   both useless and disruptive),
 * - Mizan Launcher itself is excluded,
 * - the current foreground app (when the caller could determine it)
 *   is excluded — the user may have just come from it.
 * What remains is ordinary user apps' background processes; Android
 * may restart anything it deems important, which is fine — Speed up
 * is a nudge, not a guarantee, and the before/after free-RAM line is
 * informational.
 */
object SpeedUp {

    /** One installed package as the Android side sees it. */
    data class Candidate(val pkg: String, val isSystem: Boolean)

    /**
     * Packages safe to signal with killBackgroundProcesses, in the
     * caller's order, de-duplicated.
     */
    fun killList(candidates: List<Candidate>, selfPkg: String, foregroundPkg: String?): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (c in candidates) {
            if (!seen.add(c.pkg)) continue
            if (c.pkg == selfPkg) continue
            if (foregroundPkg != null && c.pkg == foregroundPkg) continue
            if (c.isSystem) continue
            if (NeverList.isNever(c.pkg)) continue
            out.add(c.pkg)
        }
        return out
    }
}
