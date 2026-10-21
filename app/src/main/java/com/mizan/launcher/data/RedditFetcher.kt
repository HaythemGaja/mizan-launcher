package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.RedditImage
import com.mizan.launcher.core.RedditWallpapers
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Reddit wallpaper networking + download, Android side.
 * Parsing/validation lives in core (pure, sim-tested); this class only
 * moves bytes. Everything is fail-open: any failure returns null / an
 * empty result plus a human-readable error for diagnostics, and the
 * current wallpaper is never touched by a failed fetch.
 */
object RedditFetcher {

    private const val USER_AGENT = "MizanLauncher/1.1 (Android TV launcher; wallpaper fetcher)"
    const val MAX_IMAGE_BYTES: Long = 8L * 1024 * 1024

    /** Last fetch error, shown in diagnostics. Empty when the last fetch worked. */
    @Volatile var lastError: String = ""

    /**
     * Fetch top-of-week listings for [subreddits] on a background thread.
     * [onDone] runs on that background thread with the merged candidates
     * (possibly empty) — callers hop to the UI thread themselves.
     */
    fun fetch(subreddits: List<String>, allowNsfw: Boolean, onDone: (List<RedditImage>) -> Unit) {
        thread(name = "mizan-reddit-fetch") {
            val all = ArrayList<RedditImage>()
            var firstError = ""
            for (sub in subreddits) {
                try {
                    val text = httpGet(RedditWallpapers.listingUrl(sub))
                    val images = RedditWallpapers.parseListing(text, RedditWallpapers.cleanSubreddit(sub), allowNsfw)
                    if (images.isEmpty() && firstError.isEmpty()) {
                        firstError = "r/$sub: no usable images in listing"
                    }
                    all.addAll(images)
                } catch (e: Exception) {
                    if (firstError.isEmpty()) firstError = "r/$sub: ${e.javaClass.simpleName}: ${e.message ?: ""}"
                }
            }
            // De-duplicate by URL, keep first occurrence order.
            val seen = HashSet<String>()
            val merged = all.filter { seen.add(it.url) }
            lastError = if (merged.isEmpty()) firstError else ""
            try { onDone(merged) } catch (e: Exception) {
                // Caller's callback must never kill the fetch thread noisily.
            }
        }
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Directory (app-private) holding downloaded Reddit wallpapers. */
    fun wallpaperDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "reddit_wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Stable local filename for a candidate URL. */
    fun localFileName(item: RedditImage): String {
        val ext = when {
            item.url.lowercase().substringBefore('?').endsWith(".png") -> "png"
            else -> "jpg"
        }
        return "reddit_" + Integer.toHexString(item.url.hashCode()) + "." + ext
    }

    fun localFile(ctx: Context, item: RedditImage): File = File(wallpaperDir(ctx), localFileName(item))

    fun isDownloaded(ctx: Context, item: RedditImage): Boolean {
        val f = localFile(ctx, item)
        return f.exists() && f.length() > 0
    }

    /**
     * Download [item]'s image into app-private storage, capped at
     * [MAX_IMAGE_BYTES]. Returns the file, or null on any failure
     * (offline, HTTP error, too large, undecodable). Partial files are
     * deleted so a broken download never becomes a broken wallpaper.
     */
    fun download(ctx: Context, item: RedditImage): File? {
        val target = localFile(ctx, item)
        if (target.exists() && target.length() > 0) return target
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            val conn = URL(item.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) throw IllegalStateException("image too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            // Must decode as a bitmap, else it's not a wallpaper.
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IllegalStateException("not an image")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (e: Exception) {
            lastError = "download: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            try { tmp.delete() } catch (ignored: Exception) {}
            null
        }
    }
}
