package com.mizan.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.mizan.launcher.WatcherService

/**
 * Live status for the "Make Mizan your home screen" checklist:
 * is Mizan the resolved HOME activity, and is the watcher enabled?
 * Both checks are read-only and fail-open to false.
 */
object HomeStatus {

    /** True when Mizan is the activity Android resolves for MAIN+HOME. */
    fun isDefaultHome(ctx: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
            val resolved = ctx.packageManager.resolveActivity(intent, 0)
            resolved?.activityInfo?.packageName == ctx.packageName
        } catch (e: Exception) {
            false
        }
    }

    /** Package Android currently resolves for MAIN+HOME ("" if unknown). */
    fun currentHomePackage(ctx: Context): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** True when the user has switched our accessibility watcher on. */
    fun isWatcherEnabled(ctx: Context): Boolean {
        return try {
            val expected = ComponentName(ctx, WatcherService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    /** The exact ADB commands that set Mizan as default home (diagnostics detail). */
    fun adbCommands(): String =
        "adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.mizan.launcher\n" +
            "adb shell cmd package set-home-activity com.mizan.launcher/.MainActivity"
}
