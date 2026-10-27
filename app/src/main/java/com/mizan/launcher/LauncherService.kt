package com.mizan.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The launcher's own accessibility service — DISABLED by default.
 *
 * Android only runs an accessibility service the user has personally
 * switched on in system Accessibility settings, so the opt-in lives
 * with the user, not with this app. This is the companion to the
 * Home watcher (WatcherService): the watcher is passive (it notices
 * when the stock home screen appears and reopens Sreeb), while this
 * service is active — it performs system actions (Back, Home,
 * Recents, notifications, quick settings) on the launcher's behalf
 * when launcher code asks it to, via [performAction].
 *
 * Privacy posture, same as the watcher: the config declares
 * canRetrieveWindowContent=false, so the service can see WHICH
 * window is showing, never what is on it, and it sends nothing
 * anywhere. It performs no gestures (canPerformGestures is off);
 * it only fires the system global actions listed in [performAction].
 * Everything is wrapped in try/catch — a failure here can never
 * crash or loop the launcher.
 */
class LauncherService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling: this service exists to perform global
        // actions on request, not to monitor windows (the watcher
        // already does that). Window-state events are declared so
        // the service stays eligible for future launcher features
        // without a config change.
    }

    override fun onInterrupt() {
        // Nothing to clean up: no state beyond the instance pointer.
    }

    companion object {
        @Volatile
        private var instance: LauncherService? = null

        /**
         * Perform a system global action (GLOBAL_ACTION_BACK,
         * GLOBAL_ACTION_HOME, GLOBAL_ACTION_RECENTS,
         * GLOBAL_ACTION_NOTIFICATIONS, GLOBAL_ACTION_QUICK_SETTINGS,
         * GLOBAL_ACTION_POWER_DIALOG, ...). Returns false when the
         * service is not currently enabled/connected — callers must
         * fail open (the action simply does not happen).
         */
        fun performAction(action: Int): Boolean {
            return try {
                instance?.performGlobalAction(action) ?: false
            } catch (e: Exception) {
                false
            }
        }

        /** True when this service is currently connected (enabled by the user). */
        fun isConnected(): Boolean = instance != null
    }
}
