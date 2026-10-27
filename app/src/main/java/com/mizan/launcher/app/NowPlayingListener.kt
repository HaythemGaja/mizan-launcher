package com.mizan.launcher.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Now Playing listener (MAP §2) — PASSIVE, system-bound.
 * Its only job is to exist: Android authorizes
 * MediaSessionManager session queries for the component the user
 * enabled in Notification Listener settings, so the Now Playing
 * home row can read active media sessions.
 *
 * It reads NO notification content — both callbacks are
 * deliberately empty. It is NOT a foreground service: it never
 * calls startForeground/startForegroundService, holds no state,
 * and does nothing when bound beyond what the system does with
 * every listener. Session data is pulled on demand by the data
 * layer, never from notifications.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty — see class doc.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty — see class doc.
    }
}
