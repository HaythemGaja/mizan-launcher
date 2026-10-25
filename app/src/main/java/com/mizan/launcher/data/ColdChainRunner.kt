package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.ColdChain
import kotlin.concurrent.thread

/**
 * v3.5 (spec §35): the cold chain's thread.
 *
 * ONE worker thread runs the post-survival work steps in strict
 * order — TRACKER_FULL, then MATCH, then DISCOVERY — so the
 * process never holds two full data sets at once. This replaces
 * the v3.4 parallel warms (tracker memo thread + store thread +
 * match kick + discovery preload, all at process start) and the
 * MainActivity colddwarm threads, which stacked a tracker parse,
 * a 47k catalogue scan + match, and a trend/details parse on top
 * of each other at cold open.
 *
 * Triggers (all single-flight; a kick during a run is a no-op):
 * - kickAfterSurvival: MainActivity's delayed survival runnable
 *   (normal launches only — a safe-shell launch skips the chain:
 *   the shell exists because the process kept dying, and warming
 *   heavy data there could kill it again; the next normal launch
 *   runs the chain instead).
 * - kickAfterFetch: a tracker fetch landing (TrackerClient).
 *   Steps re-check cheaply when inputs did not move (memo-hit
 *   parse, fingerprint-hit match with NO catalogue stream,
 *   discovery re-shape only when kicked).
 *
 * Step bodies run synchronously on THIS thread (never spawned
 * workers): TrackerClient.cached() (parses only when the slim
 * missed or the memo key moved), WatchPlay.recomputeSyncNow()
 * (fingerprint-first; streams key-only only on change),
 * DiscoveryData.runNow() (memo-only shaping unless forced).
 * The chain thread IS the single heavy worker — no two steps
 * ever overlap, and no step runs before survival except on an
 * on-demand trigger (which these kicks are not: survival and
 * fetch landings both happen post-first-frame by construction —
 * survival is 10 s after the full render, and a fetch landing
 * means the user already had a rendered home to refresh from).
 *
 * Cancellation: MainActivity.onDestroy cancels between steps
 * (the in-flight step finishes; the next never starts).
 * [onLanded] fires on the chain thread when a run completes —
 * MainActivity posts it to the main thread for its one rebuild.
 */
object ColdChainRunner {

    private val chain = ColdChain()

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    /**
     * A kick that arrived while a run was in flight (e.g. a fetch
     * landing after the chain's MATCH step already passed — the
     * table would otherwise stay stale until the next process).
     * The run's tail re-kicks once with the pending reason; a
     * kick during a run is never silently dropped.
     */
    @Volatile
    private var pendingReason: ColdChain.Reason? = null

    /** Fired (on the chain thread) when a run completes all steps. Null = no listener. */
    @Volatile
    var onLanded: (() -> Unit)? = null

    /** Compact diagnostics state (see core/ColdChain.diag). */
    @Volatile
    var diagState: String = "waiting survival"
        private set

    /** Kick after normal-launch survival (the 10 s post-render window). */
    fun kickAfterSurvival(ctx: Context) = kick(ctx, ColdChain.Reason.SURVIVAL)

    /** Kick after a tracker fetch landing (inputs moved). */
    fun kickAfterFetch(ctx: Context) = kick(ctx, ColdChain.Reason.FETCH)

    fun kick(ctx: Context, reason: ColdChain.Reason) {
        synchronized(this) {
            if (running) {
                pendingReason = reason
                return
            }
            running = true
            cancelled = false
        }
        val appCtx = ctx.applicationContext
        thread(name = "mizan-coldchain") {
            try {
                runChain(appCtx, reason)
            } catch (e: Throwable) {
                // Throwable since v3.0: a hostile catalogue or
                // snapshot during the chain degrades to stale data
                // and is recorded — never a silent thread death.
                try { Prefs.noteError(appCtx, "coldChain", e) } catch (_: Exception) { }
            } finally {
                val pending: ColdChain.Reason?
                synchronized(this) {
                    running = false
                    pending = pendingReason
                    pendingReason = null
                }
                // One re-kick for a kick dropped mid-run — unless
                // the activity cancelled the chain on destroy.
                if (pending != null && !cancelled) kick(appCtx, pending)
            }
        }
    }

    /** Cancel between steps (the in-flight step still finishes). */
    fun cancel() {
        cancelled = true
        chain.cancel()
    }

    private fun runChain(appCtx: Context, reason: ColdChain.Reason) {
        chain.arm(reason)
        diagState = chain.diag()
        // Survival/fetch kicks are post-first-frame by construction
        // (see the header), so the gate is satisfied for every step.
        val survived = true
        val onDemand = false
        while (true) {
            if (cancelled) {
                diagState = chain.diag()
                return
            }
            val step = chain.next() ?: break
            if (!chain.start(step, survived, onDemand)) {
                diagState = chain.diag()
                return
            }
            diagState = chain.diag()
            try {
                when (step) {
                    ColdChain.Step.TRACKER_FULL -> {
                        // Parses only on slim miss / moved memo key;
                        // a memo-hit returns instantly.
                        TrackerClient.cached(appCtx)
                        DiagLog.markStage("chain: tracker")
                    }
                    ColdChain.Step.MATCH -> {
                        // Fingerprint-first; streams key-only only on change.
                        WatchPlay.recomputeSyncNow(appCtx)
                        DiagLog.markStage("chain: match")
                    }
                    ColdChain.Step.DISCOVERY -> {
                        // Memo-only shaping unless a forced run is pending.
                        DiscoveryData.runNow(appCtx)
                        DiagLog.markStage("chain: discovery")
                    }
                }
            } catch (e: Throwable) {
                // One step failing must not wedge the later steps:
                // record it and continue the chain (fail-open).
                try { Prefs.noteError(appCtx, "coldChain:$step", e) } catch (_: Exception) { }
            } finally {
                chain.complete(step)
            }
            if (cancelled) {
                diagState = chain.diag()
                return
            }
        }
        diagState = chain.diag()
        if (chain.isDone()) {
            try { onLanded?.invoke() } catch (e: Exception) {
                // The listener's rebuild guard handles dead activities.
            }
        }
    }
}
