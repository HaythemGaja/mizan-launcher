package com.mizan.launcher.core

/**
 * Arrow-sequence lock — pure state machine, no Android imports.
 *
 * v1.2 replaces the v1.1 numeric PIN pad entirely: the secret is a
 * sequence of D-pad directions (UP / DOWN / LEFT / RIGHT), 4–8 steps,
 * entered by pressing the remote's arrows — no digits, no on-screen
 * number grid, no soft keyboard.
 *
 * Storage: the sequence is encoded as letters ("UDLRU") in preferences.
 * This is a casual lock (it keeps kids / guests out of an app), NOT
 * cryptography — the stored value is readable by anyone with device
 * access, exactly like the v1.1 PIN was.
 *
 * Migration: v1.1 stored a 4-digit numeric PIN under the old key. Any
 * all-digit stored value is an old numeric PIN and is DISCARDED (the
 * lock becomes unset) rather than reinterpreted as arrows — see
 * [ArrowPinCodec.isValidEncoded] / [ArrowPinCodec.isLegacyNumeric].
 */

/** One D-pad direction. [code] is its storage letter; [glyph] its display arrow. */
enum class ArrowDir(val code: Char, val glyph: String) {
    UP('U', "↑"),
    DOWN('D', "↓"),
    LEFT('L', "←"),
    RIGHT('R', "→");

    companion object {
        fun fromCode(c: Char): ArrowDir? = values().firstOrNull { it.code == c }
    }
}

/** Encode / decode / validate stored arrow sequences. */
object ArrowPinCodec {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun encode(seq: List<ArrowDir>): String = seq.joinToString("") { it.code.toString() }

    /**
     * Decode a stored value. Returns the sequence only when the value is
     * a well-formed encoding (only U/D/L/R letters, length 4–8); anything
     * else — null, garbage, an old numeric PIN — decodes to an empty list,
     * which the app treats as "no lock set" (fail-open, never a lockout).
     */
    fun decode(raw: String?): List<ArrowDir> {
        if (!isValidEncoded(raw)) return emptyList()
        return raw!!.mapNotNull { ArrowDir.fromCode(it) }
    }

    /** True only for a complete, storable arrow encoding. */
    fun isValidEncoded(raw: String?): Boolean {
        if (raw.isNullOrEmpty()) return false
        if (raw.length !in MIN_LENGTH..MAX_LENGTH) return false
        return raw.all { ArrowDir.fromCode(it) != null }
    }

    /** True for a v1.1-style numeric PIN value (all digits, length 4). */
    fun isLegacyNumeric(raw: String?): Boolean =
        !raw.isNullOrEmpty() && raw.length == 4 && raw.all { it.isDigit() }

    /** Does [entered] match the sequence stored as [storedEncoded]? */
    fun matches(entered: List<ArrowDir>, storedEncoded: String?): Boolean {
        val stored = decode(storedEncoded)
        return stored.isNotEmpty() && stored == entered
    }
}

/** What one entry interaction produced. */
sealed class ArrowPinEvent {
    /** Buffer changed (append, or delete with steps remaining). */
    object Changed : ArrowPinEvent()
    /** Append refused: the sequence is already at the 8-step maximum. */
    object RejectedFull : ArrowPinEvent()
    /** User cancelled (BACK on an empty buffer, or long-press BACK). */
    object Cancelled : ArrowPinEvent()
    /** OK pressed with fewer than [ArrowPinCodec.MIN_LENGTH] steps. */
    object TooShort : ArrowPinEvent()
    /** OK pressed with a submittable sequence; [sequence] is the encoding. */
    data class Submitted(val sequence: String) : ArrowPinEvent()
}

/**
 * The entry machine behind the arrow-lock dialog.
 * Rules (verified in the headless sim):
 * - append(dir) adds a step; at 8 steps further appends are refused
 *   (RejectedFull) so the stored encoding can never overflow.
 * - BACK deletes the last step; BACK on an empty buffer cancels.
 * - Long-press BACK always cancels (the UI maps it to [cancel]).
 * - OK submits only at 4–8 steps; below 4 it reports TooShort and the
 *   buffer is kept so the user can keep pressing arrows.
 * - After a successful submit the buffer clears.
 */
class ArrowPinEntry {
    private val buffer = mutableListOf<ArrowDir>()

    /** Steps entered so far — the UI renders this many glyphs/dots. */
    val entered: Int get() = buffer.size

    /** Snapshot of the steps entered so far. */
    fun steps(): List<ArrowDir> = buffer.toList()

    val canSubmit: Boolean get() = buffer.size in ArrowPinCodec.MIN_LENGTH..ArrowPinCodec.MAX_LENGTH

    fun append(dir: ArrowDir): ArrowPinEvent {
        if (buffer.size >= ArrowPinCodec.MAX_LENGTH) return ArrowPinEvent.RejectedFull
        buffer.add(dir)
        return ArrowPinEvent.Changed
    }

    /** BACK (short press): delete one step, or cancel if buffer is empty. */
    fun pressBack(): ArrowPinEvent {
        return if (buffer.isNotEmpty()) {
            buffer.removeAt(buffer.size - 1)
            ArrowPinEvent.Changed
        } else {
            ArrowPinEvent.Cancelled
        }
    }

    /** Long-press BACK: always cancel, whatever is in the buffer. */
    fun cancel(): ArrowPinEvent {
        buffer.clear()
        return ArrowPinEvent.Cancelled
    }

    /** OK / CENTER: submit when long enough, otherwise TooShort. */
    fun submit(): ArrowPinEvent {
        if (!canSubmit) return ArrowPinEvent.TooShort
        val seq = ArrowPinCodec.encode(buffer)
        buffer.clear()
        return ArrowPinEvent.Submitted(seq)
    }

    /** Clear without an event (dialog reshown, wrong attempt, phase change). */
    fun reset() { buffer.clear() }
}

/** Outcome of submitting a complete sequence to the set-lock flow. */
sealed class ArrowPinSetupResult {
    /** First entry accepted; ask for the same sequence again to confirm. */
    object NeedConfirm : ArrowPinSetupResult()
    /** Confirmation matched; [sequence] is the encoding to store. Flow is done. */
    data class Done(val sequence: String) : ArrowPinSetupResult()
    /** Confirmation did not match; the flow restarts at the first entry. */
    object Mismatch : ArrowPinSetupResult()
}

/**
 * Set/change-lock flow: enter a sequence once, then enter the same
 * sequence again to confirm. A mismatch restarts the whole flow so a
 * mistyped first entry can never become the lock by accident.
 */
class ArrowPinSetupFlow {
    private var firstEntry: String? = null

    /** True while waiting for the confirming second entry. */
    val awaitingConfirm: Boolean get() = firstEntry != null

    fun submit(sequence: String): ArrowPinSetupResult {
        val first = firstEntry
        return if (first == null) {
            firstEntry = sequence
            ArrowPinSetupResult.NeedConfirm
        } else if (first == sequence) {
            firstEntry = null
            ArrowPinSetupResult.Done(sequence)
        } else {
            firstEntry = null
            ArrowPinSetupResult.Mismatch
        }
    }

    fun reset() { firstEntry = null }
}
