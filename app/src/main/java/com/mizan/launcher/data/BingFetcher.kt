package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.BingWallpaper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Bing daily wallpaper networking + download, Android side (v1.4).
 * Parsing lives in core (pure, sim-tested); this class only moves
 * bytes, with exactly the Reddit fetcher's discipline: ~8 MB cap,
 * .part temp file, bitmap-decode validation, partials deleted.
 * Fail-open: any failure leaves [lastError] set and the current
 * wallpaper untouched.
 */
object BingFetcher {

    private const val USER_AGENT = "MizanLauncher/1.4 (Android TV launcher; wallpaper fetcher)"

    /** Last fetch error, shown in diagnostics. Empty when the last fetch worked. */
    @Volatile var lastError: String = ""

    /** Directory (app-private) holding the downloaded Bing image. */
    fun wallpaperDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "bing_wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Stable local filename for one day's image. */
    fun localFile(ctx: Context, dayKey: String): File =
        File(wallpaperDir(ctx), "bing_$dayKey.jpg")

    /**
     * Fetch today's image URL from the Bing archive and download it.
     * [onDone] runs on the background thread with the file, or null on
     * any failure (offline, HTTP error, malformed JSON, too large,
     * undecodable).
     */
    fun fetchToday(ctx: Context, dayKey: String, onDone: (File?) -> Unit) {
        thread(name = "mizan-bing-fetch") {
            var result: File? = null
            try {
                val json = httpGet(BingWallpaper.ARCHIVE_URL)
                val imageUrl = BingWallpaper.parseImageUrl(json)
                    ?: throw IllegalStateException("no image in Bing archive response")
                result = download(imageUrl, localFile(ctx, dayKey))
                lastError = if (result != null) "" else lastError
            } catch (e: Exception) {
                lastError = "bing: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            }
            try { onDone(result) } catch (e: Exception) {
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

    /**
     * Download [url] to [target], capped like the Reddit fetcher.
     * Returns the file, or null on any failure; partials are deleted.
     */
    private fun download(url: String, target: File): File? {
        if (target.exists() && target.length() > 0) return target
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
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
                            if (total > RedditFetcher.MAX_IMAGE_BYTES) {
                                throw IllegalStateException("image too large")
                            }
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
            lastError = "bing download: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            try { tmp.delete() } catch (ignored: Exception) {}
            null
        }
    }
}
