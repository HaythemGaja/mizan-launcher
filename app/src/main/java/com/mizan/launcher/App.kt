package com.mizan.launcher

import android.app.Application
import com.mizan.launcher.data.DiagLog
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.StartupHealthStore

/**
 * Process entry (v2.5, spec §25). Runs before ANY activity,
 * service or receiver in the process:
 *
 *  - installs the self-diagnostics (data/DiagLog: crash handler,
 *    main-thread watchdog, breadcrumbs), so even a freeze during
 *    the first activity's onCreate leaves evidence;
 *  - kicks the v2.4 legacy-prefs purge onto its background
 *    thread (data/IptvClient.ensureStore). The purge thread is
 *    also the process's first SharedPreferences touch, so the
 *    (possibly giant) pre-v2.4 prefs file is parsed and shrunk
 *    OFF the main thread instead of inside whichever activity
 *    happens to read a setting first.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // v2.8: the cold-start clock starts HERE, before anything
        // else — every startup milestone (and the diagnostics'
        // `startup:` line) is a delta from this moment.
        DiagLog.markStartupStart()
        DiagLog.install(this)
        // v3.0: begin this process's startup for the startup-
        // health machine (core/StartupHealth): the persisted
        // uncleared counter increments NOW (commit(), before any
        // composition can die), and the safe-shell decision for
        // this launch is fixed here — MainActivity reads it via
        // StartupHealthStore.safeShellArmed. Runs AFTER the crash
        // handler installs so a failure inside beginLaunch itself
        // is still evidence, never a silent death.
        StartupHealthStore.beginLaunch(this)
        // v2.8: start the SharedPreferences file load NOW. The
        // framework loads the (large — it carries the tracker
        // snapshot documents) prefs file on a background thread
        // from the first getSharedPreferences call, and every
        // getter blocks until it lands; touching it here lets the
        // load overlap process startup instead of MainActivity's
        // first read. No value is read — the touch is the point.
        try {
            getSharedPreferences("mizan_launcher", MODE_PRIVATE)
        } catch (e: Exception) {
            // A prefs hiccup must never block process start.
        }
        IptvClient.ensureStore(this)
        // v3.1 (ask 38): warm the home-cache mirror metadata on
        // its own worker so a prefs wipe still leaves last-known
        // tracker data for the first home build of this process.
        // v3.5: metadata only (section -> length) — the old shape
        // read all five documents into memory here, duplicating
        // the prefs copies before the first frame.
        com.mizan.launcher.data.TrackerClient.warmDiskMirror(this)
        // v3.5 (spec §35): the ONLY process-start data warm is the
        // SLIM warm — a <= 512 KB read + small parse on its worker
        // that lets the first frame paint tracker rows. The v3.4
        // shape also kicked the full memo parse + the discovery
        // hydration here, stacking them on the open path beside
        // each other; both now belong to the cold chain, which
        // runs them sequentially AFTER first frame + survival.
        // A slim miss simply leaves the memo empty (shell rows
        // until the chain lands) — it never parses.
        try {
            com.mizan.launcher.data.TrackerClient.warmSlimAsync(this) { }
        } catch (e: Exception) {
            // Warm-up is an optimisation; the chain warms on survival.
        }
        // v3.9: the store warm as a head start, beside the
        // slim — on his TV the slim is absent (v1 rejected,
        // v2 never written by the store path), so the slim
        // kick alone left every fresh process memo-less
        // until the cold chain's survival + steps. Store
        // line reads are bounded like the slim's; both
        // kicks are single-flight and neither parses JSON.
        // If the slim hits first, the store warm's install
        // replaces it with the record (a superset); if the
        // store lands first, the slim kick refuses to
        // clobber it (v3.8 guard).
        try {
            com.mizan.launcher.data.TrackerClient.warmStoreMemoAsync(this) { }
        } catch (e: Exception) {
            // Warm-up is an optimisation; the chain warms on survival.
        }
    }

    /**
     * v3.5 (spec §35): the death evidence tap. Application
     * implements ComponentCallbacks2, so the system delivers
     * memory-pressure warnings here BEFORE it kills the process.
     * Every warning is committed to the tiny death prefs with the
     * last startup stage + whether the first frame drew — the
     * next launch's `last death:` line reads it. No warning
     * before a kill is itself evidence: the line then says
     * "unknown" instead of inventing a cause.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            val stage = com.mizan.launcher.data.DiagLog.lastStage() ?: "unknown stage"
            val afterFirst = com.mizan.launcher.data.DiagLog.firstFrameRecorded()
            com.mizan.launcher.data.DeathStore.record(this, level, stage, afterFirst)
            DiagLog.note("trim: level=$level stage=$stage afterFirst=$afterFirst")
        } catch (e: Exception) {
            // Evidence only; never break the trim callback.
        }
    }
}
