package com.mizan.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * TMDB image loading for the Tracker view (v1.8): posters (w500) and
 * hero backdrops (w780) from image.tmdb.org. Follows the project's
 * service-free pattern (RedditFetcher/BingFetcher): plain
 * HttpURLConnection on a named background thread, result posted back
 * to the main thread. An in-memory LRU cache (no disk) keeps row
 * scrolling cheap; every failure is fail-open — the ImageView simply
 * keeps whatever it already shows (the theme gradient behind it).
 */
object TmdbImages {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MizanLauncher/1.8"
    private const val MAX_BYTES = 6 * 1024 * 1024
    private const val MAX_ENTRIES = 48

    /** Full TMDB image URL for a raw path ("/abc.jpg") at [size] ("w500", "w780"). */
    fun url(size: String, path: String): String = "https://image.tmdb.org/t/p/$size$path"

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?
        ): Boolean = size > MAX_ENTRIES
    }
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The cached bitmap for [url], or null (never blocks). */
    fun cached(url: String): Bitmap? = synchronized(lock) { cache[url] }

    /**
     * Load [url] into [target]: cache hit applies immediately;
     * otherwise a background download applies on completion — but
     * only if the view still wants this URL (its tag), so recycled
     * row cards never flash the wrong poster. [onApplied] (v2.0, for
     * the hero crossfade) fires on the main thread right after the
     * bitmap is set; it never fires on failure (fail-open: the view
     * keeps what it shows).
     */
    fun load(url: String, target: ImageView, onApplied: (() -> Unit)? = null) {
        if (url.isBlank()) return
        cached(url)?.let {
            target.setImageBitmap(it)
            onApplied?.invoke()
            return
        }
        target.tag = url
        thread(name = "mizan-tmdb-img") {
            val bmp = download(url) ?: return@thread
            synchronized(lock) { cache[url] = bmp }
            mainHandler.post {
                if (target.tag == url) {
                    target.setImageBitmap(bmp)
                    onApplied?.invoke()
                }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { stream ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
