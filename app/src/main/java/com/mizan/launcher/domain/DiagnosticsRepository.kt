package com.mizan.launcher.domain

/**
 * Diagnostics. Lines carry counts/state only — never titles, ids,
 * or key material. Credentials (IPTV account, M3U/EPG URLs, TMDB key)
 * live only in SharedPreferences and are never exported here.
 */
data class DiagnosticsSnapshot(
    /** Sanitized lines for Settings -> Data & about + Copy diagnostics. */
    val lines: List<String> = emptyList(),
    val startupMs: Long? = null,
    val coldStart: Boolean = false,
    /** Watcher relaunch count + last time (counts only). */
    val watcherRelaunches: Int = 0,
    val watcherLastAtMs: Long = 0,
    val screenOnRecoveries: Int = 0,
    val lastDeathDescription: String? = null, // stage only, no content
)

interface DiagnosticsRepository {
    fun snapshot(): DiagnosticsSnapshot
    fun recordBreadcrumb(label: String)
    fun noteError(message: String) // scrubbed before storing
    fun copyToClipboard(): String // returns the sanitized block
}

/**
 * Sreeb accessibility services (f26a5f6, MAP §8.2). Both off by
 * default; NO in-app toggle exists — opt-in lives in system
 * Accessibility settings only. Global actions only
 * (Back/Home/Recents/Notifications/Quick Settings/Power dialog);
 * canRetrieveWindowContent=false; no gestures; no-op event
 * handling; fail-open when disconnected.
 */
enum class GlobalAction {
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG,
}

interface GlobalActionsGateway {
    /** False when not enabled/connected — callers fail open. */
    fun isConnected(): Boolean
    fun perform(action: GlobalAction): Boolean
}

/** Watcher probes: stock-home relaunch + screen-on recovery state. */
interface HomeWatcherProbe {
    fun isWatcherEnabled(): Boolean
    fun isDefaultHome(): Boolean
    fun currentHomePackage(): String?
    fun relaunchCount(): Int
}
