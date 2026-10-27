package com.mizan.launcher.core

/**
 * v3.5 (spec §35): the cold-start work chain.
 *
 * Why this exists: v3.4 made the cold warms PARALLEL (tracker memo
 * parse + store stamp + match kick + discovery preload all at
 * process start), which speeds the average case but SPIKES peak
 * memory — on a TV with ~500 MB free, that spike is what gets the
 * process low-memory-killed. Tracker shows its own list from local
 * storage and Smarters pages its catalogue lazily; Sreeb used to do
 * all of both at once, plus matching and discovery.
 *
 * The chain replaces the parallel warms with ONE ordered sequence
 * run on ONE worker thread, so the process never holds two full
 * data sets at once:
 *
 *   TRACKER_FULL -> MATCH -> DISCOVERY -> DONE
 *
 * Gates (the whole point):
 * - The first frame needs slim-painted rows ONLY. No chain step
 *   may start before first frame + survival, EXCEPT on an explicit
 *   on-demand trigger (a Details/Play open, a manual Refresh, a
 *   section Retry, a fetch landing) — user-initiated work is
 *   allowed anytime; open-path work is not.
 * - Steps run strictly in order: a step may start only when every
 *   earlier step completed. Starting out of order, or starting any
 *   step twice without a reset, is refused.
 * - Cancel stops the chain between steps (a dying activity, a
 *   process going away): the in-flight step finishes, no next
 *   step starts.
 *
 * This class is the pure state machine; data/ColdChainRunner owns
 * the thread and the actual step bodies. Pure (no Android) so the
 * sim pins the ordering + gates.
 */
class ColdChain {

    /** The post-survival work steps, in run order. */
    enum class Step { TRACKER_FULL, MATCH, DISCOVERY }

    /** Why the chain is running (diagnostics only). */
    enum class Reason { SURVIVAL, FETCH, MANUAL }

    private val completed = LinkedHashSet<Step>()
    private var started: Step? = null
    private var cancelled = false
    var reason: Reason = Reason.SURVIVAL
        private set

    /** Arm the chain for a run (a fresh chain per run; re-kicks reset). */
    fun arm(reason: Reason) {
        completed.clear()
        started = null
        cancelled = false
        this.reason = reason
    }

    /** Whether [step] may start now: in order, not done, not cancelled, and gated. */
    fun canStart(step: Step, survived: Boolean, onDemand: Boolean): Boolean {
        if (cancelled) return false
        if (started != null) return false
        if (step in completed) return false
        // Order: every earlier step must have completed.
        for (earlier in Step.values()) {
            if (earlier == step) break
            if (earlier !in completed) return false
        }
        // Gate: post-survival work needs survival or an on-demand trigger.
        if (!survived && !onDemand) return false
        return true
    }

    /** Mark [step] started (no-op unless [canStart] would allow it). Returns true when started. */
    fun start(step: Step, survived: Boolean, onDemand: Boolean): Boolean {
        if (!canStart(step, survived, onDemand)) return false
        started = step
        return true
    }

    /** Mark the in-flight step complete. No-op when nothing is in flight. */
    fun complete(step: Step) {
        if (started != step) return
        started = null
        completed.add(step)
    }

    /** The next step to run, or null when done / cancelled / one in flight. */
    fun next(): Step? {
        if (cancelled || started != null) return null
        return Step.values().firstOrNull { it !in completed }
    }

    /** True when every step completed. */
    fun isDone(): Boolean = Step.values().all { it in completed }

    /** Cancel: the in-flight step finishes, no next step starts. */
    fun cancel() {
        cancelled = true
    }

    fun isCancelled(): Boolean = cancelled

    fun inFlight(): Step? = started

    /** Compact diagnostics state: "waiting survival" / "running: match" / "done" / "cancelled". */
    fun diag(): String = when {
        cancelled -> "cancelled"
        isDone() -> "done"
        started != null -> "running: ${started!!.name.lowercase()}"
        completed.isEmpty() -> "waiting survival"
        else -> "waiting: ${next()?.name?.lowercase() ?: "done"}"
    }

    companion object {
        /**
         * The first-frame rule, as a plain predicate for the sim
         * and for code review: the first frame NEVER needs a full
         * parse, a match recompute, or discovery — slim rows only.
         * Any caller asking "may this heavy step run before first
         * frame + survival?" gets false unless it is on-demand.
         */
        fun heavyStepAllowedBeforeSurvival(onDemand: Boolean): Boolean = onDemand
    }
}
