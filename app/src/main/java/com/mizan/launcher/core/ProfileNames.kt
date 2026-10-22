package com.mizan.launcher.core

/**
 * Profile display names (v1.9) — pure codec, no Android imports.
 *
 * Each profile may carry a user-chosen name (set in Settings ›
 * Lock & profiles with the D-pad keyboard). The codec owns every
 * rule about what a name may be, so the Settings UI, the status-bar
 * profile tile, the switch menu/toasts and the Guest pill all agree:
 *
 * - [normalize] is what gets STORED: trimmed, internal whitespace
 *   runs collapsed to one space, capped at [MAX_LEN] characters.
 *   A name that normalizes to empty means "no custom name" — the
 *   profile falls back to its default label (see [display]).
 * - [display] is what gets SHOWN: the custom name when one exists,
 *   otherwise the default — "Guest" for the guest-flagged profile,
 *   "Profile N" for a personal one.
 *
 * Renaming changes the label ONLY: the guest flag, the gates and
 * every per-profile setting are keyed by profile NUMBER, never by
 * name, so a rename can never disturb them.
 */
object ProfileNames {

    /** Hard cap on a stored custom name (characters, after normalize). */
    const val MAX_LEN = 16

    /** The default label for a profile with no custom name. */
    fun defaultLabel(profile: Int, isGuest: Boolean): String =
        if (isGuest) "Guest" else "Profile $profile"

    /**
     * Normalize raw keyboard input into a storable custom name.
     * Returns "" when nothing usable remains (blank / whitespace
     * only) — callers store "" to mean "back to the default label".
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return ""
        return if (collapsed.length > MAX_LEN) {
            collapsed.substring(0, MAX_LEN).trimEnd()
        } else {
            collapsed
        }
    }

    /** The name to show for [profile] everywhere a label appears. */
    fun display(customName: String?, profile: Int, isGuest: Boolean): String {
        val custom = normalize(customName)
        return if (custom.isNotEmpty()) custom else defaultLabel(profile, isGuest)
    }
}
