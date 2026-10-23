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
        DiagLog.install(this)
        IptvClient.ensureStore(this)
    }
}
