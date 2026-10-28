package com.calm.launcher.core

/**
 * The arrow lock: a short sequence of D-pad directions that unlocks apps and folders.
 * Stored as letters, e.g. "UUDL". It is a simple lock for shared TVs, not a security boundary.
 */
object ArrowPin {
    const val MIN_LEN = 3
    const val MAX_LEN = 8
    const val UP = 'U'
    const val DOWN = 'D'
    const val LEFT = 'L'
    const val RIGHT = 'R'

    fun isValid(seq: String): Boolean =
        seq.length in MIN_LEN..MAX_LEN && seq.all { it == UP || it == DOWN || it == LEFT || it == RIGHT }

    /** True when no pattern is set, or [entered] matches [stored] exactly. */
    fun matches(stored: String, entered: String): Boolean =
        stored.isEmpty() || stored == entered

    fun hasPattern(stored: String): Boolean = stored.isNotEmpty()
}
