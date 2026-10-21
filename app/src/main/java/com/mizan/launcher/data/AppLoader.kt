package com.mizan.launcher.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.mizan.launcher.core.AppCatalogue

/** One launchable TV app. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean
)

object AppLoader {

    /**
     * ALL launchable apps: leanback (TV) intent first, phone launcher
     * intent as fallback, merged by the pure core/AppCatalogue rules —
     * de-duplicated by package (leanback record wins), own package
     * excluded, sorted by label. The merge covers every launchable
     * app on the device, including system/preinstalled apps and apps
     * that are hidden or inside folders (visibility is a home-screen
     * concern, not a catalogue concern) — the Locked-apps manager and
     * the All Apps tab both depend on that completeness (v1.6).
     */
    fun loadApps(pm: PackageManager, ownPkg: String): List<AppEntry> {
        val records = ArrayList<AppCatalogue.Record>()
        val resolveByPkg = HashMap<String, android.content.pm.ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val pkg = ri.activityInfo.packageName
                    val ai = ri.activityInfo.applicationInfo
                    records.add(
                        AppCatalogue.Record(
                            pkg = pkg,
                            label = ri.loadLabel(pm)?.toString() ?: pkg,
                            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            leanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER
                        )
                    )
                    // First ResolveInfo per package (leanback pass
                    // runs first) supplies the icon, matching the
                    // catalogue's leanback-wins rule.
                    if (!resolveByPkg.containsKey(pkg)) resolveByPkg[pkg] = ri
                }
            } catch (e: Exception) {
                // Fail-open: one bad category must not lose the whole list.
            }
        }
        return AppCatalogue.merge(records, ownPkg).map { rec ->
            val ri = resolveByPkg[rec.pkg]
            AppEntry(
                pkg = rec.pkg,
                label = rec.label,
                icon = try { ri?.loadIcon(pm) } catch (e: Exception) { null },
                isSystem = rec.isSystem
            )
        }
    }

    /** Best launch intent for a package: leanback first, then plain launcher. */
    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
}
