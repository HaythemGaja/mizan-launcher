package com.mizan.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.Prefs

/**
 * Boot receiver. Deliberately boring: when Mizan is the default home,
 * Android starts MainActivity itself — the receiver only refreshes the
 * app-list cache timestamp and records that a boot happened.
 * There is NO foreground service in this app, and nothing here starts one.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            // Touch the package manager once so the first Home press is warm.
            AppLoader.loadApps(context.packageManager, context.packageName)
            context.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_boot_at", System.currentTimeMillis())
                .apply()
            // v1.1: if Mizan is the default home, nudge it to the front
            // after boot (some TVs sit on a stock screen after an update
            // reboot). Background activity starts are restricted, so this
            // is strictly best-effort and fails open silently.
            if (com.mizan.launcher.data.HomeStatus.isDefaultHome(context)) {
                try {
                    val home = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(home)
                } catch (ignored: Exception) {
                    // Android blocked the background start; the first Home
                    // press will land on Mizan anyway — it is the default.
                }
            }
        } catch (e: Exception) {
            Prefs.noteError(context, "bootReceiver", e)
        }
    }
}
