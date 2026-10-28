package com.calm.launcher.data

import android.content.Context
import android.content.Intent
import com.calm.launcher.core.AppEntry

/** Finds launchable apps. Never includes the launcher itself. */
object AppCatalog {

    private val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (category in categories) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val ai = info.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (!seen.add(ai.packageName)) continue
                out.add(AppEntry(ai.packageName, info.loadLabel(pm).toString(), ai.name, category))
            }
        }
        return out
    }
}
