package com.mizan.launcher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mizan.launcher.data.HomeStatusProbe
import com.mizan.launcher.data.PrefsStore

/**
 * Boot receiver (MAP §2). Deliberately boring: on
 * BOOT_COMPLETED only, it warms the app-list cache (one
 * PackageManager pass, so the first Home press is warm),
 * records last_boot_at, and (best-effort, fails open) brings
 * Sreeb front if it is the default home. It starts NO service
 * — there is no foreground service in this app, and nothing
 * here starts one. Any other broadcast action is ignored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            // Touch the package manager once so the first Home press is warm.
            try {
                com.mizan.launcher.data.DataFactory.apps(context).getAllApps()
            } catch (ignored: Throwable) { }
            PrefsStore(context).setLastBootAt(System.currentTimeMillis())
            // Best-effort nudge to front when Sreeb is the default
            // home (background activity starts may be blocked; the
            // first Home press lands on Sreeb anyway).
            if (HomeStatusProbe.isDefaultHome(context)) {
                try {
                    val home = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(home)
                } catch (ignored: Exception) {
                    // Android blocked the background start — fail open.
                }
            }
        } catch (e: Exception) {
            try { App.diagnostics?.noteError("bootReceiver") } catch (ignored: Throwable) { }
        }
    }
}
