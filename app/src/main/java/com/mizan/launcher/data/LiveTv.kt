package com.mizan.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.mizan.launcher.core.LiveTvLogic
import kotlin.concurrent.thread

/**
 * v2.0: Live TV, strictly best-effort. READ_TV_LISTINGS is a
 * signature/system-reserved permission on many TVs, so the usual
 * outcome of the TvContract.Channels query is a SecurityException —
 * that is a SUPPORTED result here: core/LiveTvLogic classifies it as
 * BLOCKED, the row stays hidden, and the diagnostics `live tv:` line
 * says why. Nothing in this file can crash the launcher.
 *
 * The query runs on a background thread into a volatile cache; the
 * home renders whatever the cache holds and refreshes on resume.
 */
object LiveTv {

    data class Result(
        val state: LiveTvLogic.State,
        val channels: List<LiveTvLogic.Channel>
    )

    @Volatile
    private var cache: Result? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logoCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?
        ): Boolean = size > 32
    }
    private val logoLock = Any()

    fun cached(): Result? = cache

    /** Kick a background refresh; [onDone] runs on the main thread. */
    fun refreshAsync(ctx: Context, onDone: (Result) -> Unit = {}) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-livetv") {
            val result = load(appCtx)
            cache = result
            mainHandler.post {
                try { onDone(result) } catch (e: Exception) {
                    // A caller's rebuild must never kill the refresh.
                }
            }
        }
    }

    /** The synchronous probe (background threads + tests of patience only). */
    fun load(ctx: Context): Result {
        val hasLiveTv = try {
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV) ||
                ctx.getSystemService(Context.TV_INPUT_SERVICE) != null
        } catch (e: Exception) {
            false
        }
        var securityBlocked = false
        var queryFailed = false
        val channels = ArrayList<LiveTvLogic.Channel>()
        try {
            val projection = arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_INPUT_ID
            )
            ctx.contentResolver.query(
                TvContract.Channels.CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContract.Channels._ID)
                val nameIdx = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                val inputIdx = c.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                    if (id < 0) continue
                    val name = (if (nameIdx >= 0) c.getString(nameIdx) else null)
                        ?.takeIf { it.isNotBlank() } ?: "Channel"
                    val inputId = (if (inputIdx >= 0) c.getString(inputIdx) else null) ?: ""
                    channels.add(LiveTvLogic.Channel(id, name, inputId))
                }
            }
        } catch (e: SecurityException) {
            securityBlocked = true
        } catch (e: Exception) {
            queryFailed = true
        }
        return Result(
            LiveTvLogic.classify(hasLiveTv, securityBlocked, queryFailed, channels.size),
            channels
        )
    }

    /** The diagnostics line's value; triggers a first probe if needed. */
    fun diagLine(ctx: Context): String {
        val c = cache
        if (c == null) {
            refreshAsync(ctx)
            return "not checked yet"
        }
        return LiveTvLogic.diagText(c.state, c.channels.size)
    }

    /** The tune intent for a channel (the platform TV app resolves it). */
    fun channelIntent(channel: LiveTvLogic.Channel): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(channel.id))

    /**
     * Load a channel logo into [target] (guarded throughout: a missing
     * logo URI, an unreadable stream, or a recycled view all leave the
     * view showing its generated initial tile).
     */
    fun loadLogo(ctx: Context, channel: LiveTvLogic.Channel, target: ImageView) {
        val key = "logo:${channel.id}"
        synchronized(logoLock) { logoCache[key] }?.let { target.setImageBitmap(it); return }
        target.tag = key
        val appCtx = ctx.applicationContext
        thread(name = "mizan-livetv-logo") {
            val bmp = try {
                // The (inputId, channelId) overload is not in the
                // public SDK stub; the channel-id form resolves the
                // same logo through the TvProvider.
                val uri = TvContract.buildChannelLogoUri(channel.id)
                appCtx.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                null
            } ?: return@thread
            synchronized(logoLock) { logoCache[key] = bmp }
            mainHandler.post {
                if (target.tag == key) target.setImageBitmap(bmp)
            }
        }
    }
}
