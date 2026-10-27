package com.mizan.launcher.ui

import android.content.Context
import com.mizan.launcher.core.UnlockGrace

/** Pattern gate wrapper for locked apps/folders, Settings, leaving Guest (MAP §1.13). */
object PatternGate {
    /**
     * Runs [action] if no pattern is set, if within grace, or if the entered
     * pattern verifies. Grace stamp honoured; profile switch always re-asks
     * (caller clears the stamp via SettingsRepository.switchProfile).
     */
    fun run(ctx: Context, onUnlocked: () -> Unit) {
        val lock = UiWiring.lock
        if (lock == null || !lock.isPatternSet()) { onUnlocked(); return }
        val settings = UiWiring.settings
        val graceMin = settings?.getProfile(settings.getGlobal().currentProfile)?.unlockGraceMinutes ?: 0
        // Grace check via repository contract when available.
        if (graceMin > 0) {
            val grace = com.mizan.launcher.domain.UnlockGrace(graceMin, 0L)
            // lastUnlockAt is held by the data layer; isWithinGrace with a
            // zero stamp never grants, so a future-dated stamp cannot be
            // forged from here — the data layer grants only its own stamp.
            if (lock.isWithinGrace(grace, System.currentTimeMillis())) { onUnlocked(); return }
        }
        ArrowPinDialog.showEnter(ctx, { entered ->
            if (lock.verifyPattern(entered)) {
                lock.stampUnlock(System.currentTimeMillis()); onUnlocked()
            } else Ui.toast(ctx, "Wrong pattern")
        })
    }
    fun settingsGateLabel(): String = "Pattern required"
    @Suppress("UNUSED_PARAMETER")
    fun withinGrace(windowMin: Int, lastUnlockAt: Long, now: Long): Boolean =
        UnlockGrace.withinGrace(windowMin, lastUnlockAt, now)
}
