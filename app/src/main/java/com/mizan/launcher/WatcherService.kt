package com.mizan.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.mizan.launcher.data.HomeStatus
import com.mizan.launcher.data.Prefs

/**
 * OPTIONAL Home watcher, Monet-style — DISABLED by default.
 *
 * Android only runs an accessibility service the user has personally
 * switched on in system Accessibility settings, so the opt-in lives with
 * the user, not with this app. The service's config declares
 * canRetrieveWindowContent=false: it can see WHICH window is showing,
 * never what is on it, and it sends nothing anywhere.
 *
 * Behaviour (v1.1, strengthened):
 * - When a stock TV launcher window takes the screen, relaunch Mizan.
 * - Debounced: at most one relaunch per 2 seconds.
 * - Never a loop: if Mizan itself was foreground within the last
 *   second, skip (the stock window is just passing through).
 * - Screen-on / unlock recovery: on SCREEN_ON / USER_PRESENT, if Mizan
 *   is the default home, nudge it back to the front (standby sometimes
 *   leaves the stock launcher showing instead).
 * - Every relaunch is counted in diagnostics (count + last time).
 * Everything is wrapped in try/catch — a watcher failure can never
 * crash or loop the launcher.
 */
class WatcherService : AccessibilityService() {

    private var lastRelaunchAt: Long = 0
    private var lastOwnForegroundAt: Long = 0

    // v3.15 (boot storm): a cold launcher takes a moment to draw, and
    // while it does the stock launcher keeps re-taking the screen — the
    // flat 2 s debounce then relaunches us dozens of times in the first
    // seconds after boot (measured: 224). Consecutive relaunches now
    // back off 2s → 4s → 8s → 16s → 30s, and the ladder resets once
    // Mizan has actually stayed in front for 10 s.
    private var consecutiveRelaunches = 0
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
            Prefs.noteError(this, "watcherReceiver", e)
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
            // Mizan is foreground — remember it for the loop guard, and
            // once it has held the screen for 10 s, drop the backoff.
            val prev = lastOwnForegroundAt
            lastOwnForegroundAt = System.currentTimeMillis()
            if (prev > 0 && lastOwnForegroundAt - prev > 10_000) consecutiveRelaunches = 0
            return
        }
        if (pkg !in STOCK_LAUNCHERS) return
        relaunch("stock:$pkg")
    }

    /** After standby/unlock: if Mizan is default home, bring it back. */
    private fun recoverAfterScreenOn() {
        try {
            Prefs.noteScreenOnRecovery(this)
            if (!HomeStatus.isDefaultHome(this)) return
            relaunch("screenOn")
        } catch (e: Exception) {
            Prefs.noteError(this, "watcherScreenOn", e)
        }
    }

    private fun relaunch(reason: String) {
        val now = System.currentTimeMillis()
        // Loop guard: Mizan was just foreground — do not fight it.
        if (now - lastOwnForegroundAt < 1_000) return
        val ownPrefsAt = try {
            com.mizan.launcher.data.PrefsSafe.long(
                getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE),
                "own_foreground_at", 0
            )
        } catch (e: Exception) { 0 }
        if (now - ownPrefsAt < 1_000) return
        // Debounce with backoff while the stock launcher keeps winning.
        val gap = if (now - lastOwnForegroundAt > 10_000) {
            consecutiveRelaunches = 0
            2_000L
        } else {
            val step = consecutiveRelaunches.coerceIn(0, 4)
            (2_000L shl step).coerceAtMost(30_000L)
        }
        if (now - lastRelaunchAt < gap) return
        lastRelaunchAt = now
        consecutiveRelaunches = (consecutiveRelaunches + 1).coerceAtMost(5)
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            Prefs.noteWatcherRelaunch(this)
        } catch (e: Exception) {
            Prefs.noteError(this, "watcherRelaunch:$reason", e)
        }
    }

    override fun onInterrupt() {
        // Nothing to clean up: the watcher keeps no state beyond counters.
    }

    companion object {
        private val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher"
        )
    }
}
