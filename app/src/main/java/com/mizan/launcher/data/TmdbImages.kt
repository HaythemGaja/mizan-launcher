package com.mizan.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Image loading for posters, backdrops and IPTV logos (v1.8;
 * v2.4 memory discipline): plain HttpURLConnection on a named
 * background thread, result posted back to the main thread, every
 * failure fail-open — the ImageView keeps whatever it shows.
 *
 * v2.4: two memory fixes, both learned from the IPTV catalogue —
 * IPTV cards load FULL panel URLs through here, and panel posters
 * arrive at full resolution:
 *
 * - DOWNSAMPLED DECODE: bitmaps are decoded with inSampleSize so
 *   the longest side lands near the caller's [maxDim] (cards pass
 *   the default 512; the hero passes 1024). A 2000×3000 poster no
 *   longer becomes a 24 MB bitmap to fill a 300 px card.
 * - BYTE-BOUNDED CACHE: the LRU is capped by total bitmap BYTES
 *   (24 MB), not by entry count — 48 full-size posters could
 *   previously pin hundreds of MB on a TV box.
 */
object TmdbImages {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MizanLauncher/1.8"
    private const val MAX_DOWNLOAD_BYTES = 6 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

    /** Default decode bound (longest side, px) for card-sized images. */
    const val DEFAULT_MAX_DIM = 512

    /** Decode bound for the full-screen hero backdrop. */
    const val HERO_MAX_DIM = 1024

    /** Full TMDB image URL for a raw path ("/abc.jpg") at [size] ("w500", "w780"). */
    fun url(size: String, path: String): String = "https://image.tmdb.org/t/p/$size$path"

    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var cacheBytes = 0
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * v3.15 (startup stall): the loader used to spawn ONE NAMED THREAD
     * PER IMAGE (`thread { ... }`). A home build asks for 100+ posters
     * while the main thread is laying out, and `Thread.start()` is
     * serialised VM work — the watchdog caught the main thread sitting
     * inside nativeCreate/Thread.start for seconds, once per card.
     *
     * One small pool now serves every image. The queue is LIFO: the
     * newest request is the row the user is looking at, so it wins over
     * rows composed earlier. Duplicate requests for a URL already in
     * flight are dropped (the ImageView tag check makes that safe).
     */
    private val imageQueue = LinkedBlockingDeque<Runnable>()
    private val inFlight = HashSet<String>()
    private val threadNo = AtomicInteger(0)

    private val pool = object : ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, imageQueue,
        { r ->
            Thread(r, "mizan-tmdb-img-" + threadNo.incrementAndGet()).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    ) {
        override fun execute(command: Runnable) {
            // LIFO: newest first.
            imageQueue.offerFirst(command)
        }
    }.apply { allowCoreThreadTimeOut(true) }

    private fun cacheKey(url: String, maxDim: Int): String = "$url@$maxDim"

    /** The cached bitmap for [url] at the default size bucket, or null (never blocks). */
    fun cached(url: String): Bitmap? = cached(url, DEFAULT_MAX_DIM)

    /** The cached bitmap for [url] decoded for [maxDim], or null (never blocks). */
    fun cached(url: String, maxDim: Int): Bitmap? =
        synchronized(lock) { cache[cacheKey(url, maxDim)] }

    /**
     * Load [url] into [target]: cache hit applies immediately;
     * otherwise a background download applies on completion — but
     * only if the view still wants this URL (its tag), so recycled
     * row cards never flash the wrong poster. [onApplied] fires on
     * the main thread right after the bitmap is set; it never
     * fires on failure (fail-open).
     */
    fun load(
        url: String,
        target: ImageView,
        maxDim: Int = DEFAULT_MAX_DIM,
        onApplied: (() -> Unit)? = null
    ) {
        if (url.isBlank()) return
        cached(url, maxDim)?.let {
            target.setImageBitmap(it)
            onApplied?.invoke()
            return
        }
        target.tag = url
        val key = cacheKey(url, maxDim)
        synchronized(lock) {
            // Already queued/downloading: the tag above is enough, this
            // request would only duplicate the network work.
            if (!inFlight.add(key)) return
        }
        pool.execute {
            try {
                val bmp = download(url, maxDim)
                if (bmp != null) {
                    synchronized(lock) { putCached(key, bmp) }
                    mainHandler.post {
                        if (target.tag == url) {
                            target.setImageBitmap(bmp)
                            onApplied?.invoke()
                        }
                    }
                }
            } finally {
                synchronized(lock) { inFlight.remove(key) }
            }
        }
    }

    /** Insert under the byte budget, evicting least-recently-used entries. Caller holds [lock]. */
    private fun putCached(key: String, bmp: Bitmap) {
        cache.remove(key)?.let { cacheBytes -= it.byteCount }
        cache[key] = bmp
        cacheBytes += bmp.byteCount
        while (cacheBytes > MAX_CACHE_BYTES && cache.size > 1) {
            val eldest = cache.entries.firstOrNull() ?: break
            cacheBytes -= eldest.value.byteCount
            cache.remove(eldest.key)
        }
    }

    private fun download(url: String, maxDim: Int): Bitmap? {
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
                    if (total > MAX_DOWNLOAD_BYTES) return null
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            // Two-pass decode: bounds first, then a sampled decode
            // whose longest side lands within ~2× of maxDim.
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim &&
                bounds.outHeight / (sample * 2) >= maxDim
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
