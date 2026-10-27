package com.mizan.launcher.data

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Home/watcher/service status probes (MAP §2/§3.1, TANGLE-4
 * resolution: service probes are string component names, never
 * imports of app-layer service classes). Enabled-state probes
 * check Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES for the
 * service's flattened ComponentName (case-insensitive,
 * colon-split; false on any error). No in-app toggle exists for
 * either accessibility service.
 */
object HomeStatusProbe {
    private const val LAUNCHER_SERVICE = "com.mizan.launcher/.app.LauncherService"
    private const val WATCHER_SERVICE = "com.mizan.launcher/.app.WatcherService"
    private const val LISTENER = "com.mizan.launcher/.app.NowPlayingListener"

    private fun enabledServices(ctx: Context): List<String> = try {
        (Settings.Secure.getString(ctx.applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }
    } catch (e: Throwable) { emptyList() }

    fun isLauncherServiceEnabled(ctx: Context): Boolean =
        enabledServices(ctx).any { it.equals(LAUNCHER_SERVICE, ignoreCase = true) }
    fun isWatcherEnabled(ctx: Context): Boolean =
        enabledServices(ctx).any { it.equals(WATCHER_SERVICE, ignoreCase = true) }
    fun isListenerEnabled(ctx: Context): Boolean = try {
        val flat = Settings.Secure.getString(ctx.applicationContext.contentResolver,
            "enabled_notification_listeners") ?: ""
        flat.split(':').any { it.equals(LISTENER, ignoreCase = true) }
    } catch (e: Throwable) { false }

    fun isDefaultHome(ctx: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.applicationContext.packageManager.resolveActivity(intent, 0)
            ?.activityInfo?.packageName == ctx.applicationContext.packageName
    } catch (e: Throwable) { false }

    fun currentHomePackage(ctx: Context): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.applicationContext.packageManager.resolveActivity(intent, 0)
            ?.activityInfo?.packageName
    } catch (e: Throwable) { null }

    /** Stock TV launchers the watcher watches for (MAP §2). */
    val STOCK_LAUNCHERS = listOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
    )
    fun isStockLauncher(pkg: String?): Boolean = pkg in STOCK_LAUNCHERS
}
