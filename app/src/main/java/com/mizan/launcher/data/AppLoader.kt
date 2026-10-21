package com.mizan.launcher.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** One launchable TV app. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean
)

object AppLoader {

    /**
     * All launchable apps: leanback (TV) intent first, phone launcher intent
     * as fallback, de-duplicated by package, sorted by label. Own package is
     * excluded (a launcher doesn't list itself as an app).
     */
    fun loadApps(pm: PackageManager, ownPkg: String): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == ownPkg || byPkg.containsKey(pkg)) continue
                    val ai = ri.activityInfo.applicationInfo
                    byPkg[pkg] = AppEntry(
                        pkg = pkg,
                        label = ri.loadLabel(pm)?.toString() ?: pkg,
                        icon = try { ri.loadIcon(pm) } catch (e: Exception) { null },
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
            } catch (e: Exception) {
                // Fail-open: one bad category must not lose the whole list.
            }
        }
        return byPkg.values.sortedBy { it.label.lowercase() }
    }

    /** Best launch intent for a package: leanback first, then plain launcher. */
    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
}
