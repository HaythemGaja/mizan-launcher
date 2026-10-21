package com.mizan.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri

/** One Continue Watching entry from TvContract WatchNext. */
data class WatchItem(val title: String, val intentUri: String?)

/**
 * TV-specific data: HDMI inputs and Continue Watching.
 * Every call is guarded — on a device without a tuner/input framework or
 * without WatchNext permission these return empty lists and the rows hide
 * (fail-open), instead of crashing the launcher.
 */
object TvData {

    /** All TV inputs the device reports (HDMI, tuner, etc.). Empty on boxes without any. */
    fun listInputs(ctx: Context): List<TvInputInfo> {
        return try {
            val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return emptyList()
            tim.tvInputList ?: emptyList()
        } catch (e: Exception) {
            Prefs.noteError(ctx, "listInputs", e)
            emptyList()
        }
    }

    fun inputLabel(ctx: Context, info: TvInputInfo): String {
        return try {
            info.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() }
                ?: when (info.type) {
                    TvInputInfo.TYPE_HDMI -> "HDMI"
                    TvInputInfo.TYPE_TUNER -> "TV Tuner"
                    TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
                    TvInputInfo.TYPE_OTHER -> "Input"
                    else -> "Input"
                }
        } catch (e: Exception) {
            "Input"
        }
    }

    /**
     * Switch to an input. There is no single public "switch input" intent,
     * so this is best-effort, in order:
     *  1. an activity in the input's own package whose class name suggests
     *     it handles inputs/HDMI/live TV,
     *  2. the input's setup intent,
     *  3. give up (caller toasts) — never crash.
     */
    fun launchInput(ctx: Context, info: TvInputInfo): Boolean {
        try {
            val pm = ctx.packageManager
            val pkg = info.serviceInfo.packageName
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val candidates = try { pm.queryIntentActivities(query, 0) } catch (e: Exception) { emptyList() }
            for (ri in candidates) {
                if (ri.activityInfo.packageName != pkg) continue
                val cls = ri.activityInfo.name.lowercase()
                if (cls.contains("input") || cls.contains("hdmi") || cls.contains("tuner") || cls.contains("livetv")) {
                    val intent = Intent().setComponent(ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return true
                }
            }
            val setup = try { info.createSetupIntent() } catch (e: Exception) { null }
            if (setup != null) {
                setup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(setup)
                return true
            }
        } catch (e: Exception) {
            Prefs.noteError(ctx, "launchInput", e)
        }
        return false
    }

    /** Best-effort WatchNext (Continue Watching) rows from TvContract. */
    fun watchNext(ctx: Context): List<WatchItem> {
        val out = mutableListOf<WatchItem>()
        try {
            val uri: Uri = TvContract.WatchNextPrograms.CONTENT_URI
            val projection = arrayOf(
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI
            )
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val titleIdx = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE)
                val uriIdx = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI)
                while (c.moveToNext()) {
                    val title = if (titleIdx >= 0) c.getString(titleIdx) else null
                    val intentUri = if (uriIdx >= 0) c.getString(uriIdx) else null
                    if (!title.isNullOrBlank()) out.add(WatchItem(title, intentUri))
                    if (out.size >= 20) break
                }
            }
        } catch (e: Exception) {
            Prefs.noteError(ctx, "watchNext", e)
            return emptyList()
        }
        return out
    }

    fun launchWatchItem(ctx: Context, item: WatchItem): Boolean {
        val uri = item.intentUri ?: return false
        return try {
            val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Prefs.noteError(ctx, "launchWatchItem", e)
            false
        }
    }
}
