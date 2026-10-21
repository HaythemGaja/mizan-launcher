package com.mizan.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Now Playing listener (v1.4) — a PASSIVE, system-bound service.
 *
 * Its only job is to exist: Android authorizes MediaSessionManager
 * session queries for the component the user enabled in Notification
 * Listener settings, so Mizan can read active media sessions (title,
 * artist, playback state, artwork) for the Now Playing home row.
 *
 * It reads NO notification content — both callbacks are deliberately
 * empty. It is NOT a foreground service: it never calls
 * startForeground/startForegroundService (there is no code path here
 * that could), holds no state, and does nothing when bound beyond
 * what the system does with every listener.
 */
class NowPlayingListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty — see class doc. Session data is pulled
        // on demand by data/NowPlayingData, never from notifications.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty — see class doc.
    }
}
