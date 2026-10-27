package com.mizan.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mizan.launcher.core.LiveTvLogic
import java.io.File
import java.io.FileOutputStream

/**
 * System TV listings + tuner data (MAP §5, best-effort, guarded
 * at runtime). Continue Watching row via READ_TV_LISTINGS hides
 * without the permission/data; Live TV row hides when the system
 * reserves the tuner permission — diagnostics says why (state
 * string only, no programme titles beyond what the row shows).
 */
object TvData {
    data class ContinueCard(val title: String, val posterUri: String?)

    fun continueWatching(ctx: Context): List<ContinueCard> {
        return try {
            // TvContract.WatchNextPrograms requires READ_TV_LISTINGS;
            // without the grant the query throws SecurityException
            // and the row hides (fail open).
            val resolver = ctx.applicationContext.contentResolver
            val tvUri = android.net.Uri.parse("content://android.media.tv/watch_next_program")
            val cursor = resolver.query(tvUri, null, null, null, null) ?: return emptyList()
            val out = ArrayList<ContinueCard>()
            cursor.use { c ->
                val titleIdx = c.getColumnIndex("title")
                while (c.moveToNext()) {
                    val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
                    if (title.isNotBlank()) out.add(ContinueCard(title, null))
                }
            }
            out
        } catch (e: Throwable) { emptyList() }
    }
}

object LiveTvData {
    data class Channel(val id: Long, val name: String, val inputId: String)
    data class Result(val state: String, val channels: List<Channel>)

    fun channels(ctx: Context): Result {
        return try {
            val resolver = ctx.applicationContext.contentResolver
            val uri = android.net.Uri.parse("content://android.media.tv/channel")
            val cursor = resolver.query(uri, null, null, null, null)
                ?: return Result(LiveTvLogic.diagText(LiveTvLogic.State.NO_CHANNELS, 0), emptyList())
            val out = ArrayList<Channel>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val nameIdx = c.getColumnIndex("display_name")
                val inputIdx = c.getColumnIndex("input_id")
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    val input = if (inputIdx >= 0) c.getString(inputIdx) ?: "" else ""
                    if (name.isNotBlank()) out.add(Channel(id, name, input))
                }
            }
            val state = if (out.isEmpty()) LiveTvLogic.State.NO_CHANNELS else LiveTvLogic.State.OK
            Result(LiveTvLogic.diagText(state, out.size), out)
        } catch (e: Throwable) {
            Result(LiveTvLogic.diagText(LiveTvLogic.State.BLOCKED, 0), emptyList())
        }
    }
}

/**
 * Per-app artwork cache (MAP §4): filesDir/artwork/<pkg>.png,
 * downloaded from a custom image URL; decodes are downsampled
 * and byte-bounded (never full-size unbounded bitmaps).
 * Preset artwork needs no download (core/Artwork handles it).
 * dp conversion here is plain math (TANGLE-1: data never
 * imports ui.Ui).
 */
object ArtworkImages {
    fun dpToPx(density: Float, dp: Int): Int = (dp * density + 0.5f).toInt()

    fun download(ctx: Context, pkg: String, url: String): File? {
        return try {
            val dir = File(ctx.applicationContext.filesDir, "artwork").apply { mkdirs() }
            val out = File(dir, "$pkg.png")
            java.net.URL(url).openStream().use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Throwable) { null }
    }

    fun decodeBounded(file: File, maxPx: Int): Bitmap? {
        return try {
            if (!file.isFile || file.length() > 4_000_000) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Throwable) { null }
    }

    fun cachedFile(ctx: Context, pkg: String): File? {
        val f = File(ctx.applicationContext.filesDir, "artwork/$pkg.png")
        return if (f.isFile) f else null
    }
}
