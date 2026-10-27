package com.mizan.launcher.domain

/**
 * Arrow-pattern gate. D-pad sequence 4-8 steps, glyphs are arrows
 * not digits; encoded form is UDLRU; legacy numeric PINs decode
 * empty (migration discard, never reinterpreted). The codec itself
 * lives in core/ArrowPin; this layer only defines the gate contract
 * and the grace data — implementations delegate to core.
 *
 * Grace covers locked apps, folders, Settings, and leaving Guest;
 * switching profiles always asks again; a future-dated stamp never
 * grants; expiry is exact at the boundary.
 */
data class UnlockGrace(
    /** Minutes; 0 = re-ask every time. */
    val graceMinutes: Int = 0,
    val lastUnlockAtMs: Long = 0,
)

interface LockRepository {
    fun isPatternSet(): Boolean
    fun verifyPattern(encodedInput: String): Boolean
    fun setPattern(encodedPattern: String)
    fun clearPattern()

    /** True when the grace stamp covers `nowMs` (exact boundary). */
    fun isWithinGrace(grace: UnlockGrace, nowMs: Long): Boolean
    fun stampUnlock(nowMs: Long)
    fun clearUnlockStamp()

    /** Settings lock: explicit toggle wins; unset => pattern-set means locked. */
    fun isSettingsLocked(): Boolean
    fun setSettingsLocked(locked: Boolean?)

    fun isAppLocked(packageName: String): Boolean
    fun setAppLocked(packageName: String, locked: Boolean)
}
