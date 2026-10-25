package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.DeathCapture

/**
 * v3.5 (spec §35): persistence for the `last death:` line.
 *
 * A tiny dedicated prefs file ("mizan_death") — deliberately NOT
 * the big mizan_launcher prefs, so reading the death record never
 * loads the multi-megabyte snapshot documents. One codec string
 * (core/DeathCapture: level|stage|afterFirst|atMs) + the last
 * stage, committed/applied as follows:
 *
 * - record(): onTrimMemory — commit() (synchronous). The trim
 *   callback runs off the main thread and the file is ~100 bytes;
 *   commit is chosen over apply() because a kill can follow the
 *   warning within milliseconds, and an apply() queued behind
 *   the death would lose the evidence. The trim record also
 *   carries the stage + first-frame state observed AT trim time,
 *   so it survives even if the stage log below lags.
 * - updateStage(): every DiagLog stage — apply() (async, cheap).
 *   Best-effort only: it feeds the "unknown" line's last stage
 *   when no trim was ever recorded.
 * - clear(): on normal-launch survival ONLY (the delayed
 *   survival runnable). A safe-shell immediate clear does NOT
 *   clear it — the safe shell IS evidence of deaths, and wiping
 *   the record on the shell launch would hide the death before
 *   anyone photographs it. The record survives until a normal
 *   launch proves healthy for 10 s.
 * - The record NEVER clears on a user safe-mode exit either, for
 *   the same reason; the next normal survival clears it.
 *
 * Contents are ids/flags/timestamps only — never titles, URLs,
 * or credentials. All calls are best-effort (try/catch): death
 * evidence must never break startup.
 */
object DeathStore {

    private const val FILE = "mizan_death"
    private const val K_RECORD = "death_record"
    private const val K_LAST_STAGE = "last_stage"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Record a trim warning (commit — see the header). */
    fun record(ctx: Context, trimLevel: Int, stage: String, afterFirstFrame: Boolean) {
        try {
            val rec = DeathCapture.DeathRecord(
                trimLevel = trimLevel,
                stage = stage,
                afterFirstFrame = afterFirstFrame,
                atMs = System.currentTimeMillis()
            )
            prefs(ctx).edit()
                .putString(K_RECORD, DeathCapture.encode(rec))
                .putString(K_LAST_STAGE, stage)
                .commit()
        } catch (e: Exception) {
            // Evidence only; never break the trim callback.
        }
    }

    /** Best-effort last-stage update (apply — see the header). */
    fun updateStage(ctx: Context, stage: String) {
        try {
            prefs(ctx).edit().putString(K_LAST_STAGE, stage).apply()
        } catch (e: Exception) {
            // Evidence only.
        }
    }

    /** The last recorded trim, or null (no death recorded / malformed record fails open). */
    fun readRecord(ctx: Context): DeathCapture.DeathRecord? {
        return try {
            DeathCapture.decode(PrefsSafe.strOrNull(prefs(ctx), K_RECORD))
        } catch (e: Exception) {
            null
        }
    }

    /** The last persisted stage, or null. */
    fun readLastStage(ctx: Context): String? {
        return try {
            PrefsSafe.strOrNull(prefs(ctx), K_LAST_STAGE)?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Clear the trim record (normal-launch survival only — see the header). */
    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit().remove(K_RECORD).apply()
        } catch (e: Exception) {
            // A stale record is ignored once the counter is 0 (see DeathCapture).
        }
    }

    /**
     * The diagnostics line: the startup-health counter is the
     * authority (0 = "none recorded" even with a stale record);
     * otherwise the trim record, else the unknown line with the
     * last persisted stage.
     */
    fun diagLine(ctx: Context): String {
        return try {
            val uncleared = StartupHealthStore.unclearedCount(ctx)
            DeathCapture.lastDeathLine(uncleared, readRecord(ctx), readLastStage(ctx))
        } catch (e: Exception) {
            "last death: none recorded"
        }
    }
}
