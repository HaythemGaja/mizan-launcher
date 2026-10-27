package com.mizan.launcher.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App-layer image loader (Phase 2D, UI gap (f)): poster + wallpaper
 * fetching for the UI layer's UiWiring.imageLoader hook. No image
 * dependency exists in the manual build, so this is plain
 * HttpURLConnection + BitmapFactory with the same safety rules as
 * the data layer's artwork funnel: bounded decodes (downsampled to
 * ≤512 px), a bounded in-memory cache, worker-thread fetch, main
 * -thread set, and a URL tag guard so a recycled view never shows
 * a stale image. Failures are silent (the placeholder stands) —
 * a missing poster is never a crash or a black tile.
 */
object ImageLoader {
    private val cache = LruCache<String, Bitmap>(8 * 1024 * 1024)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String) {
        view.setTag(url)
        cache.get(url)?.let { bmp ->
            if (view.getTag() == url) view.setImageBitmap(bmp)
            return
        }
        Thread {
            val bmp = fetch(url) ?: return@Thread
            cache.put(url, bmp)
            main.post { if (view.getTag() == url) view.setImageBitmap(bmp) }
        }.start()
    }

    /** Wallpaper-sized fetch with a result callback (main thread). */
    fun loadBitmap(url: String, onResult: (Bitmap?) -> Unit) {
        cache.get(url)?.let { onResult(it); return }
        Thread {
            val bmp = fetch(url)
            if (bmp != null) cache.put(url, bmp)
            main.post { onResult(bmp) }
        }.start()
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            if (url.startsWith("file://") || File(url).isFile) {
                decodeBounded(BitmapFactory.decodeFile(url.removePrefix("file://")))
            } else {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 15000
                    setRequestProperty("User-Agent", "Sreeb/3.9")
                }
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes().take(4_000_000).toByteArray() }
                decodeBounded(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        } catch (e: Throwable) { null }
    }

    private fun decodeBounded(src: Bitmap?): Bitmap? {
        if (src == null) return null
        if (src.width <= 512 && src.height <= 512) return src
        val scale = maxOf(src.width / 512f, src.height / 512f)
        val w = (src.width / scale).toInt().coerceAtLeast(1)
        val h = (src.height / scale).toInt().coerceAtLeast(1)
        return try { Bitmap.createScaledBitmap(src, w, h, true) } catch (e: Throwable) { src }
    }
}
