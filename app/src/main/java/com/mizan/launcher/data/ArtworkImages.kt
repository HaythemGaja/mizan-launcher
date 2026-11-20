package com.mizan.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.mizan.launcher.ui.Ui
import java.io.File
import android.util.AtomicFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * v2.0: URL artwork for custom app icons. An image URL is downloaded
 * ONCE (background thread), validated as a decodable bitmap, and
 * cached at filesDir/artwork/<pkg>.png; afterwards the file is the
 * cache (plus a small in-memory LRU of shaped results, like
 * TmdbImages). Every failure is fail-open: the tile keeps the app's
 * real icon, and a failed download changes nothing in the store.
 */
object ArtworkImages {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MizanLauncher/2.0"
    private const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * Decoded-bitmap ceiling (v3.0): the byte cap above does NOT
     * bound decoded pixels — a small PNG can expand to gigapixels
     * and OOM the process mid-decode. Every decode in this object
     * goes through DecodeBounds with this longest-side cap (icon
     * art never displays larger than a few hundred px).
     */
    private const val MAX_DIM = 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val shapedCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?
        ): Boolean = size > 32
    }
    private val lock = Any()
    private val diskLock = Any()
    private val workers = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "mizan-artwork").apply { isDaemon = true }
    }

    private fun clearMemory(pkg: String) {
        synchronized(lock) {
            val it = shapedCache.keys.iterator()
            while (it.hasNext()) if (it.next().startsWith("$pkg|")) it.remove()
        }
    }

    private fun save(file: File, bitmap: Bitmap) = synchronized(diskLock) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            atomic.finishWrite(output)
        } catch (e: Throwable) {
            atomic.failWrite(output)
            throw e
        }
    }

    fun fileFor(ctx: Context, pkg: String): File {
        val safe = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(ctx.filesDir, "artwork"), "$safe.png")
    }

    /** Drop a package's disk + memory cache (reset / URL replaced). */
    fun invalidate(ctx: Context, pkg: String) {
        synchronized(diskLock) { AtomicFile(fileFor(ctx, pkg)).delete() }
        clearMemory(pkg)
    }

    /**
     * Download [url] for [pkg], validate it decodes, store it.
     * [onResult] fires on the main thread with success/failure.
     */
    fun fetchAndStore(ctx: Context, pkg: String, url: String, onResult: (Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        workers.execute {
            val ok = try {
                val bmp = download(url)
                if (bmp == null) {
                    false
                } else {
                    val file = fileFor(appCtx, pkg)
                    save(file, bmp)
                    clearMemory(pkg)
                    true
                }
            } catch (e: Throwable) {
                // Throwable, not Exception (v3.0): a decode Error on
                // this worker must still report failure, not die.
                false
            }
            mainHandler.post { onResult(ok) }
        }
    }

    /**
     * Show [pkg]'s URL artwork in [target]: the [fallback] bitmap (the
     * shaped real icon) is set immediately; when the cached/downloaded
     * image is ready it is swapped in — shaped identically — but only
     * if the view still wants this package (tag guard, like TmdbImages).
     * A missing/corrupt file leaves the fallback (fail-open).
     */
    fun load(
        ctx: Context,
        pkg: String,
        url: String,
        target: ImageView,
        sizePx: Int,
        shape: String,
        fallback: Bitmap
    ) {
        val dimension = sizePx.coerceIn(1, MAX_DIM)
        val key = "$pkg|$dimension|$shape|$url"
        target.tag = key
        synchronized(lock) { shapedCache[key] }?.let { target.setImageBitmap(it); return }
        target.setImageBitmap(fallback)
        val appCtx = ctx.applicationContext
        workers.execute {
            val raw: Bitmap? = try {
                val file = fileFor(appCtx, pkg)
                if (file.isFile) {
                    DecodeBounds.decodeFileBounded(file.absolutePath, MAX_DIM)
                } else {
                    val bmp = download(url)
                    if (bmp != null) {
                        save(file, bmp)
                    }
                    bmp
                }
            } catch (e: Throwable) {
                null
            }
            if (raw == null) return@execute
            // Centre-crop to a square, then apply the icon mask.
            val shaped = try {
                val side = minOf(raw.width, raw.height)
                if (side <= 0) return@execute
                val square = Bitmap.createBitmap(
                    raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side
                )
                val scaled = if (side == dimension) square
                else Bitmap.createScaledBitmap(square, dimension, dimension, true)
                Ui.maskToShape(scaled, shape)
            } catch (e: Throwable) { return@execute }
            synchronized(lock) { shapedCache[key] = shaped }
            mainHandler.post {
                if (target.tag == key) target.setImageBitmap(shaped)
            }
        }
    }

    private fun download(url: String): Bitmap? {
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
                    if (total > MAX_BYTES) return null
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            DecodeBounds.decodeBytesBounded(bytes, MAX_DIM)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
