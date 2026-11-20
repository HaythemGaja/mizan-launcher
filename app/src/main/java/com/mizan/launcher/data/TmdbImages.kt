package com.mizan.launcher.data

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference
import java.util.concurrent.RejectedExecutionException

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
     * One small pool now serves every image.
     *
     * v3.17 (poster delivery): duplicate requests for a URL already
     * in flight used to be DROPPED — and completion posted the
     * bitmap only to the original target. When the home rebuilt
     * cold-shell → warm-home mid-download, the new card's request
     * was dropped and the bitmap went to a discarded view, so the
     * new card stayed blank forever. Every load() call now
     * registers its target as a waiter for the key; completion
     * applies the bitmap to EVERY waiter whose view tag still
     * equals the URL, then clears the list.
     */
    private val imageQueue = LinkedBlockingDeque<Runnable>(256)
    private val waiters = HashMap<String, MutableList<Pair<WeakReference<ImageView>, (() -> Unit)?>>>()
    private val threadNo = AtomicInteger(0)

    // NOTE (v3.16 fix): the first cut of this pool overrode execute()
    // to push the request at the HEAD of the deque (LIFO), which also
    // bypassed ThreadPoolExecutor's worker startup — the queue then
    // never drained and NO poster ever loaded. A plain executor, with
    // its workers prestarted, is the correct shape; FIFO ordering is
    // fine here because a home build enqueues rows top-down anyway.
    private val pool = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, imageQueue,
        { r ->
            Thread(r, "mizan-tmdb-img-" + threadNo.incrementAndGet()).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
        prestartAllCoreThreads()
    }

    private fun cacheKey(url: String, maxDim: Int): String = "$url@$maxDim"

    /** The cached bitmap for [url] at the default size bucket, or null (never blocks). */
    fun cached(url: String): Bitmap? = cached(url, DEFAULT_MAX_DIM)

    /** The cached bitmap for [url] decoded for [maxDim], or null (never blocks). */
    fun cached(url: String, maxDim: Int): Bitmap? =
        synchronized(lock) { cache[cacheKey(url, maxDim.coerceIn(1, HERO_MAX_DIM))] }

    /**
     * Load [url] into [target]: cache hit applies immediately;
     * otherwise a background download applies on completion to
     * every waiter for the URL — but only if the view still wants
     * this URL (its tag), so recycled row cards never flash the
     * wrong poster. [onApplied] fires on the main thread right
     * after the bitmap is set on its own view; it never fires on
     * failure (fail-open, no retry storm).
     */
    fun load(
        url: String,
        target: ImageView,
        maxDim: Int = DEFAULT_MAX_DIM,
        onApplied: (() -> Unit)? = null
    ) {
        val dimension = maxDim.coerceIn(1, HERO_MAX_DIM)
        val key = cacheKey(url, dimension)
        // Assign before even a cache hit: an older in-flight load must not
        // overwrite a newly cached image (including another size of this URL).
        target.tag = key
        if (url.isBlank()) return
        cached(url, dimension)?.let {
            target.setImageBitmap(it)
            onApplied?.invoke()
            return
        }
        val alreadyDownloading = synchronized(lock) {
            val existing = waiters[key]
            val list = existing ?: mutableListOf<Pair<WeakReference<ImageView>, (() -> Unit)?>>()
                .also { waiters[key] = it }
            list.removeAll { it.first.get() == null || it.first.get() === target }
            list.add(WeakReference(target) to onApplied)
            existing != null
        }
        if (alreadyDownloading) return
        try {
            pool.execute {
                val bmp = download(url, dimension)
                val served = synchronized(lock) {
                    if (bmp != null) putCached(key, bmp)
                    waiters.remove(key).orEmpty()
                }
                if (bmp != null) mainHandler.post {
                    for ((reference, cb) in served) {
                        val view = reference.get() ?: continue
                        if (view.tag == key) {
                            view.setImageBitmap(bmp)
                            cb?.invoke()
                        }
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            synchronized(lock) { waiters.remove(key) }
        }
    }

    /** Insert under the byte budget, evicting least-recently-used entries. Caller holds [lock]. */
    private fun putCached(key: String, bmp: Bitmap) {
        if (bmp.byteCount > MAX_CACHE_BYTES) return
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
        var conn: HttpURLConnection? = null
        return try {
            val address = URL(url)
            if (address.protocol != "http" && address.protocol != "https") return null
            conn = address.openConnection() as? HttpURLConnection ?: return null
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
            DecodeBounds.decodeBytesBounded(bytes, maxDim)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
