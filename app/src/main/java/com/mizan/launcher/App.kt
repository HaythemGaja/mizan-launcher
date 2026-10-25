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
        // v3.1 (ask 38): warm the home-cache mirror on its own
        // worker so a prefs wipe still leaves last-known tracker
        // data for the first home build of this process.
        com.mizan.launcher.data.TrackerClient.warmDiskMirror(this)
        // v3.4 (ask 49): start the two worker warms at process
        // start, not at the first home build — the tracker memo
        // parse (now slim-accelerated) and the discovery disk
        // hydration overlap process startup instead of queuing
        // behind the first frame. Both are single-flight, so the
        // home's own kicks below dedupe against these.
        try {
            com.mizan.launcher.data.TrackerClient.warmMemoAsync(this) { }
        } catch (e: Exception) {
            // Warm-up is an optimisation; the home warms on demand.
        }
        try {
            com.mizan.launcher.data.DiscoveryData.preload(this)
        } catch (e: Exception) {
            // As above.
        }
    }
}
