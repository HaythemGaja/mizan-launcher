package com.mizan.launcher

import android.app.Application
import com.mizan.launcher.data.DiagLog
import com.mizan.launcher.data.IptvClient

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
    }
}
