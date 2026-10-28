package com.mizan.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.mizan.launcher.LauncherService
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

    /** True when the user has switched our launcher accessibility service on. */
    fun isLauncherServiceEnabled(ctx: Context): Boolean {
        return try {
            val expected = ComponentName(ctx, LauncherService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The exact ADB command that sets Sreeb as default home (Step 4 since v3.13).
     *
     * v2.3.1: set-home-activity LEADS — verified live on Haythem's
     * TV from ATV Tools (2026-10-22): BOTH role-command forms failed
     * there with the same NumberFormatException (the role shell
     * command mis-parses its flags against the package string on
     * that Google TV build — the command is simply broken there),
     * while set-home-activity returned "Success". The role form is
     * demoted to a warning so nobody wastes another session on it.
     */
    fun adbCommands(): String =
        "adb shell cmd package set-home-activity com.mizan.launcher/.MainActivity\n" +
            "(Skip the older “cmd role add-role-holder” form — it errors with a " +
            "NumberFormatException on some Google TV builds, this one works there.)"
}
