package com.mizan.launcher.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.mizan.launcher.data.HomeStatusProbe
import com.mizan.launcher.data.PrefsStore

/**
 * "Sreeb launcher watcher" accessibility service (MAP §2/§8.2;
 * renamed in f26a5f6 from "Sreeb watcher", behaviour unchanged).
 * OFF by default; no in-app toggle.
 *
 * - Passive: when a stock TV launcher window takes the screen,
 *   relaunch Sreeb. Stock launchers watched:
 *   com.google.android.apps.tv.launcherx,
 *   com.google.android.tvlauncher,
 *   com.google.android.leanbacklauncher.
 * - Debounce: at most one relaunch per 2 seconds.
 * - Loop guard: if Sreeb itself was foreground within the last
 *   second, skip — checked both in-memory and via the
 *   own_foreground_at pref (the MainActivity stamps it too).
 * - Screen-on / unlock recovery: on SCREEN_ON / USER_PRESENT,
 *   if Sreeb is the default home, nudge it front (counted as a
 *   screen-on recovery in diagnostics).
 * - Every relaunch is counted in diagnostics (count + last time,
 *   via PrefsStore). Window-state events only,
 *   canRetrieveWindowContent=false, sends nothing anywhere.
 * - Everything try/catch-wrapped: a watcher failure can never
 *   crash or loop the launcher.
 */
class WatcherService : AccessibilityService() {

    private var lastRelaunchAt: Long = 0
    private var lastOwnForegroundAt: Long = 0
    private var screenReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> recoverAfterScreenOn()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(receiver, filter)
            screenReceiver = receiver
        } catch (e: Exception) {
            noteError("watcherReceiver")
        }
    }

    override fun onDestroy() {
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {
            // Already gone — nothing to clean up.
        }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            lastOwnForegroundAt = System.currentTimeMillis()
            return
        }
        if (!HomeStatusProbe.isStockLauncher(pkg)) return
        relaunch("stock:$pkg")
    }

    private fun recoverAfterScreenOn() {
        try {
            // Screen-on recovery count (diagnostics, counts only).
            try { PrefsStore(this).noteScreenOnRecovery() } catch (ignored: Throwable) { }
            if (!HomeStatusProbe.isDefaultHome(this)) return
            relaunch("screenOn")
        } catch (e: Exception) {
            noteError("watcherScreenOn")
        }
    }

    private fun relaunch(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastOwnForegroundAt < 1_000) return
        val ownPrefsAt = try { PrefsStore(this).ownForegroundAt() } catch (e: Exception) { 0 }
        if (now - ownPrefsAt < 1_000) return
        if (now - lastRelaunchAt < 2_000) return
        lastRelaunchAt = now
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            PrefsStore(this).noteWatcherRelaunch(now)
        } catch (e: Exception) {
            noteError("watcherRelaunch:$reason")
        }
    }

    private fun noteError(tag: String) {
        try {
            App.diagnostics?.noteError(tag)
        } catch (ignored: Throwable) { }
    }

    override fun onInterrupt() {
        // No state beyond counters.
    }
}
