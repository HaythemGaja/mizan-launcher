package com.mizan.launcher.core

/**
 * Guest profile gate — pure decision function, no Android imports (v1.4).
 *
 * One profile can be flagged as the Guest profile. While the GUEST
 * profile is the active one, and "Lock settings in Guest" is on, and
 * an arrow pattern exists, switching AWAY from Guest back to a
 * personal profile asks for the pattern first (otherwise a guest
 * exits in two presses).
 *
 * v1.5: this gate no longer decides OPEN_SETTINGS — Settings entry is
 * decided by core/SettingsGate, which merges the guest rule with the
 * global "Lock Settings" switch into a single prompt. (The Cleanup
 * action left with the Cleanup feature itself, also removed in v1.5.)
 *
 * Fail-open rules (Haythem's standing rule, same as the app/folder
 * locks): with NO pattern set, nothing is gated — the launcher never
 * locks anyone out of their own TV. Switching TO the guest profile is
 * never gated, from anywhere: handing the TV to a guest should be one
 * press, and entering Guest gives access to less, not more.
 */
object GuestGate {

    /** Actions the gate knows about. */
    enum class Action {
        /** Leaving the guest profile for a personal one. */
        SWITCH_AWAY_FROM_GUEST,
        /** Entering the guest profile. Always allowed. */
        SWITCH_TO_GUEST,
        /** Switching between two non-guest profiles. Always allowed. */
        SWITCH_BETWEEN_PERSONAL
    }

    enum class Decision { ALLOW, REQUIRE_PATTERN }

    fun decide(
        guestActive: Boolean,
        lockSettingsInGuest: Boolean,
        patternSet: Boolean,
        action: Action
    ): Decision {
        // Entering Guest, or moving between personal profiles, is
        // never gated — regardless of every other input.
        if (action == Action.SWITCH_TO_GUEST || action == Action.SWITCH_BETWEEN_PERSONAL) {
            return Decision.ALLOW
        }
        // The gate only bites while Guest is active, the lock is on,
        // and a pattern exists to check against. Anything else is the
        // fail-open path.
        if (!guestActive) return Decision.ALLOW
        if (!lockSettingsInGuest) return Decision.ALLOW
        if (!patternSet) return Decision.ALLOW
        return Decision.REQUIRE_PATTERN
    }

    /** True while [currentProfile] is the flagged guest profile. */
    fun isGuestActive(currentProfile: Int, guestProfile: Int): Boolean =
        guestProfile != 0 && currentProfile == guestProfile
}
