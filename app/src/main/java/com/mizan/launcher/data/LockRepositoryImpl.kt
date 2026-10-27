package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.UnlockGrace
import com.mizan.launcher.domain.LockRepository
import com.mizan.launcher.domain.UnlockGrace as DomainGrace

/**
 * Arrow-pattern gate (MAP §1.13/§3.7). Encoded form is UDLRU
 * (core/ArrowPinCodec); legacy numeric PINs decode empty and were
 * discarded at PrefsStore init, never reinterpreted. Grace covers
 * locked apps, folders, Settings, and leaving Guest; switching
 * profiles always asks again (PrefsStore.switchProfile clears the
 * stamp); a future-dated stamp never grants; expiry is exact at
 * the boundary (core/UnlockGrace.withinGrace).
 */
class LockRepositoryImpl(ctx: Context) : LockRepository {
    private val prefs = PrefsStore(ctx)
    private val apps = AppsRepositoryImpl(ctx)

    override fun isPatternSet(): Boolean = prefs.arrowPin().isNotBlank()
    override fun verifyPattern(encodedInput: String): Boolean {
        val stored = prefs.arrowPin()
        if (stored.isBlank()) return false
        return encodedInput == stored
    }
    override fun setPattern(encodedPattern: String) = prefs.setArrowPin(encodedPattern)
    override fun clearPattern() = prefs.setArrowPin("")

    override fun isWithinGrace(grace: DomainGrace, nowMs: Long): Boolean =
        UnlockGrace.withinGrace(grace.graceMinutes, grace.lastUnlockAtMs, nowMs)

    override fun stampUnlock(nowMs: Long) = prefs.stampUnlock(nowMs)
    override fun clearUnlockStamp() = prefs.clearUnlockStamp()

    override fun isSettingsLocked(): Boolean {
        val explicit = prefs.lockSettingsRaw()
        if (explicit != null) return explicit
        return isPatternSet() // legacy rule: unset => pattern-set means locked
    }
    override fun setSettingsLocked(locked: Boolean?) = prefs.setLockSettings(locked)

    override fun isAppLocked(packageName: String): Boolean = apps.isAppLocked(packageName)
    override fun setAppLocked(packageName: String, locked: Boolean) = apps.setAppLocked(packageName, locked)

    /** Current grace snapshot (minutes from this profile + global stamp). */
    fun currentGrace(): DomainGrace = DomainGrace(prefs.unlockGraceMin(), prefs.lastUnlockAt())
    /** True when the stored stamp covers now under the profile grace. */
    fun withinGraceNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        isWithinGrace(currentGrace(), nowMs)
}
