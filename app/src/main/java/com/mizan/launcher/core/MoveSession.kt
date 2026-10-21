package com.mizan.launcher.core

/**
 * Hold-and-move SESSION state — pure, no Android imports (v1.4 fix).
 *
 * Why this class exists: v1.3 kept the session's list as a direct
 * reference into the activity's LayoutData object. But the activity
 * RELOADS its layout from prefs on every rebuild (a fresh decode), so
 * the captured reference was orphaned at grab time — GridMove swapped
 * items in a list nobody rendered or persisted, and the arrows
 * appeared to do nothing (confirmed on-device in v1.3).
 *
 * The fix is structural: the machine OWNS a private working copy of
 * the order. It never exposes that list — callers can only read a
 * snapshot of it ([currentOrder]) or the grab-time order
 * ([snapshotOrder]) and WRITE one of those into whatever store is
 * current at that moment. A layout reload between grab and drop is
 * therefore harmless by construction: every write resolves its target
 * fresh.
 *
 * The activity's session loop per arrow press is:
 *   if (machine.move(dir)) writeBack(machine.currentOrder())
 * and on drop there is nothing left to write; on cancel the activity
 * writes [snapshotOrder] instead. The headless sim replays exactly
 * this loop — including a simulated layout reload mid-session — and
 * asserts the final persisted store order.
 */

/**
 * Which home container a move session edits. ROOT_FOLDERS (v1.7)
 * orders the folder tree's ROOT ids — the iPad grid's folders
 * segment; write-back goes through FolderTree.reorderRoots.
 */
enum class MoveContainer { FAVORITES, ALL_APPS, FOLDER, ROOT_FOLDERS }

class MoveSessionMachine(
    /** The grabbed app's package name. */
    val pkg: String,
    /** The container's order at grab time (copied — never aliased). */
    initialOrder: List<String>,
    /** Grid wrap width (the profile's column count). */
    val columns: Int
) {
    /** Exact order at grab time — what BACK-cancel restores. */
    private val snapshot: List<String> = initialOrder.toList()

    /** The working order. Private and never handed out by reference. */
    private val working: MutableList<String> = initialOrder.toMutableList()

    /** The grabbed item's current index in the working order. */
    var index: Int = working.indexOf(pkg)
        private set

    /** False when [pkg] wasn't in the grabbed order (session must not start). */
    val isValid: Boolean get() = index >= 0

    /**
     * One arrow press: swap the grabbed item inside the working copy.
     * Returns false for an edge no-op (working order untouched).
     */
    fun move(dir: ArrowDir): Boolean {
        val target = GridMove.gridMove(working, index, dir, columns)
        if (target < 0) return false
        index = target
        return true
    }

    /** A copy of the current working order — what the caller persists/renders. */
    fun currentOrder(): List<String> = working.toList()

    /** A copy of the grab-time order — what a cancel writes back. */
    fun snapshotOrder(): List<String> = snapshot
}
