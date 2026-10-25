package com.mizan.launcher.core

/**
 * v3.5 (spec §35): the `last death:` diagnostics model.
 *
 * Sreeb's deaths are system low-memory kills, not code crashes:
 * no crash.log, no uncaught exception — so nothing ever named the
 * killer. This module turns the two facts the process CAN observe
 * into one honest line:
 *
 * - ComponentCallbacks2.onTrimMemory levels (the system's memory
 *   pressure warnings, recorded with the last startup stage and
 *   whether the first frame had drawn yet), and
 * - the startup-health uncleared counter (whether the previous
 *   process actually died mid-startup).
 *
 * The line has exactly three honest states — it never fabricates
 * a cause:
 * - no uncleared start -> "none recorded" (the last process
 *   survived; a stale record is ignored, never shown);
 * - uncleared start + a recorded trim -> the trim level, the last
 *   stage, and before/after first frame;
 * - uncleared start + NO recorded trim -> "unknown" (killed with
 *   no warning, or the warning never landed) with the last stage
 *   when one was persisted.
 *
 * Pure (no Android) so the sim pins every state. data/DeathStore
 * owns the persistence; the record carries ids/flags/timestamps
 * only — never titles, URLs, or credentials.
 */
object DeathCapture {

    /** One recorded trim warning: the level, the stage it hit at, first-frame state, wall time. */
    data class DeathRecord(
        val trimLevel: Int,
        val stage: String,
        val afterFirstFrame: Boolean,
        val atMs: Long
    )

    /**
     * Android ComponentCallbacks2 RUNNING trim levels, named
     * (a foreground app only ever sees these three; any other
     * int stays numeric, never misnamed).
     */
    fun trimName(level: Int): String = when (level) {
        5 -> "RUNNING_MODERATE"
        10 -> "RUNNING_LOW"
        15 -> "RUNNING_CRITICAL"
        else -> "trim $level"
    }

    /**
     * The diagnostics line. [uncleared] = the startup-health
     * counter for the previous process (> 0 means it died
     * mid-startup); [record] = the last trim warning that process
     * recorded, if any; [lastStage] = the last persisted startup
     * stage (best-effort, may be null).
     */
    fun lastDeathLine(
        uncleared: Int,
        record: DeathRecord?,
        lastStage: String?
    ): String {
        // A survived previous process means "none recorded" even
        // when a stale record lingers (it is cleared on survival;
        // the counter, not the record, is the authority).
        if (uncleared <= 0) return "last death: none recorded"
        if (record != null) {
            val whenText = if (record.afterFirstFrame) "after first frame" else "before first frame"
            val stageText = record.stage.ifEmpty { "unknown stage" }
            return "last death: ${trimName(record.trimLevel)} at $stageText ($whenText)"
        }
        val stageText = lastStage?.ifEmpty { null } ?: "unknown stage"
        return "last death: unknown (last stage: $stageText)"
    }

    /** One-line codec for the record ("level|stage|afterFirst|atMs"); malformed decodes to null. */
    fun encode(record: DeathRecord): String =
        "${record.trimLevel}|${record.stage.replace("|", " ")}|${if (record.afterFirstFrame) 1 else 0}|${record.atMs}"

    fun decode(raw: String?): DeathRecord? {
        if (raw.isNullOrBlank()) return null
        val f = raw.split('|')
        if (f.size != 4) return null
        val level = f[0].toIntOrNull() ?: return null
        val after = when (f[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val at = f[3].toLongOrNull() ?: return null
        if (level < 0 || at < 0L) return null
        return DeathRecord(level, f[1], after, at)
    }
}
