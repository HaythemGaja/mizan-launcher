package com.mizan.launcher.ui

import android.app.Activity
import com.mizan.launcher.data.Prefs

/**
 * The shared arrow-pattern gate for screens outside MainActivity
 * (v2.2: the IPTV category unlock). MainActivity keeps its own
 * private copy for its gates; the RULES are identical and owned by
 * Prefs + core/UnlockGrace: inside the profile's grace window the
 * gate passes silently; outside it the unlock prompt shows, and a
 * success stamps the one device-level grace clock. When no pattern
 * is set the gate passes (there is nothing to verify against —
 * callers that require a pattern check `prefs.arrowPin` first).
 */
object PatternGate {

    fun run(activity: Activity, prefs: Prefs, accent: Int, title: String, onPass: () -> Unit) {
        if (prefs.arrowPin.isEmpty() || prefs.withinUnlockGrace()) {
            onPass()
            return
        }
        ArrowPinDialog.showUnlock(
            activity = activity,
            title = title,
            accent = accent,
            storedEncoded = prefs.arrowPin,
            onSuccess = {
                prefs.stampUnlock()
                onPass()
            },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
        )
    }
}
