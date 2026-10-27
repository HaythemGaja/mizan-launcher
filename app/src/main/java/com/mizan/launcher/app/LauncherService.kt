package com.mizan.launcher.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.mizan.launcher.data.GlobalActionsGatewayStub
import com.mizan.launcher.domain.GlobalAction
import com.mizan.launcher.domain.GlobalActionsGateway

/**
 * "Sreeb launcher" accessibility service (MAP §8.2, f26a5f6).
 * OFF by default; there is NO in-app toggle — opt-in lives only
 * in system Accessibility settings (Android only runs a service
 * the user personally switched on).
 *
 * Contract, exactly:
 * - Config (res/xml/launcher_config.xml): window-state events
 *   only, feedbackGeneric, notificationTimeout=100,
 *   canRetrieveWindowContent=false (sees WHICH window is
 *   showing, never what is on it), NO gestures.
 * - onAccessibilityEvent is intentionally a no-op: this service
 *   does NOT monitor windows (the watcher does that).
 * - performAction fires a system global action via the connected
 *   instance (Back / Home / Recents / Notifications / Quick
 *   Settings / Power dialog); returns false when not
 *   enabled/connected — callers MUST fail open.
 * - isConnected = the static instance is non-null (set on
 *   onServiceConnected, cleared on onUnbind/onDestroy).
 * - Everything try/catch-wrapped; sends nothing anywhere; reads
 *   no content. The live GlobalActionsGateway delegate is
 *   registered here (data gap (g)); until then the stub in the
 *   data layer reports disconnected and perform() fails open.
 */
class LauncherService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try { GlobalActionsGatewayStub.delegate = gateway } catch (ignored: Throwable) { }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) instance = null
        try {
            if (GlobalActionsGatewayStub.delegate === gateway) {
                GlobalActionsGatewayStub.delegate = null
            }
        } catch (ignored: Throwable) { }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        try {
            if (GlobalActionsGatewayStub.delegate === gateway) {
                GlobalActionsGatewayStub.delegate = null
            }
        } catch (ignored: Throwable) { }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op by design (see class doc).
    }

    override fun onInterrupt() {
        // Nothing to clean up: no state beyond the instance pointer.
    }

    private val gateway = object : GlobalActionsGateway {
        override fun isConnected(): Boolean = instance != null
        override fun perform(action: GlobalAction): Boolean {
            val androidAction = when (action) {
                GlobalAction.BACK -> GLOBAL_ACTION_BACK
                GlobalAction.HOME -> GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
                GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                GlobalAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                GlobalAction.POWER_DIALOG -> GLOBAL_ACTION_POWER_DIALOG
            }
            return performAction(androidAction)
        }
    }

    companion object {
        @Volatile
        private var instance: LauncherService? = null

        fun performAction(action: Int): Boolean {
            return try {
                instance?.performGlobalAction(action) ?: false
            } catch (e: Exception) {
                false
            }
        }

        fun isConnected(): Boolean = instance != null
    }
}
