package com.mizan.launcher.core

/**
 * The Settings entry gate (v1.5) — ONE pure decision merging the two
 * reasons Settings may ask for the arrow pattern before it opens:
 *
 *  1. The global "Lock Settings" switch (Lock & profiles): Settings
 *     itself is protected, on every profile.
 *  2. The v1.4 Guest rule: while the Guest profile is active and
 *     "Lock settings in Guest" is on, Settings is protected even if
 *     the global switch is off.
 *
 * Either reason alone is enough; both together still produce exactly
 * ONE prompt (the caller — SettingsActivity.onCreate — shows a single
 * ArrowPinDialog). Fail-open, per the standing rule: with NO pattern
 * set, nothing is ever gated (the launcher must never lock Haythem out
 * of his own Settings), and once the pattern has been entered for
 * this entry ([unlockedThisEntry]) rebuilds within the same entry
 * never re-prompt. This is a casual lock, not cryptography.
 */
object SettingsGate {

    enum class Decision { ALLOW, REQUIRE_PATTERN }

    fun decide(
        /** The global "Lock Settings" switch. */
        lockSettings: Boolean,
        /** The Guest profile is the active profile. */
        guestActive: Boolean,
        /** The "Lock settings in Guest" switch. */
        guestLockSettings: Boolean,
        /** An arrow pattern exists to check against. */
        patternSet: Boolean,
        /** The pattern was already entered during this Settings entry. */
        unlockedThisEntry: Boolean
    ): Decision {
        if (unlockedThisEntry) return Decision.ALLOW
        if (!patternSet) return Decision.ALLOW
        val guestRequires = guestActive && guestLockSettings
        return if (lockSettings || guestRequires) {
            Decision.REQUIRE_PATTERN
        } else {
            Decision.ALLOW
        }
    }
}
