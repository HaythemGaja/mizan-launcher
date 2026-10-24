package com.mizan.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import com.mizan.launcher.core.DominantColor
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.WeatherLogic

/**
 * Computes the Dynamic theme's accent: the dominant colour of the current
 * wallpaper. Works for every wallpaper kind — image wallpapers are decoded
 * (downscaled), gradient/solid/theme wallpapers are rendered to a small
 * bitmap first. Result is stored in Prefs.accentOverride. Fail-open: on any
 * error the stored value is left alone and the preset accent is used.
 */
object AccentUtil {

    /** Longest-side ceiling for decoded wallpaper images (v3.0, DecodeBounds). */
    private const val WALLPAPER_MAX_DIM = 2048

    @Volatile
    private var recomputeInFlight = false

    /**
     * [recompute] off the caller's thread (v2.5): for image and
     * Reddit wallpapers the compute opens a content-resolver
     * stream and decodes a bitmap, and it used to be called
     * synchronously from rebuild paths and click handlers on the
     * main thread (MainActivity.resolveColours on EVERY rebuild
     * while no override was stored, the Bing apply path, a dozen
     * Settings pickers). Single-flight; [onDone] runs on the main
     * thread only when the stored accent actually changed, so
     * callers rebuild exactly when the new accent matters.
     * Callers that are ALREADY on a background thread (the
     * wallpaper rotator) keep using the synchronous [recompute].
     */
    fun recomputeAsync(ctx: Context, prefs: Prefs, onDone: () -> Unit = {}) {
        synchronized(this) {
            if (recomputeInFlight) return
            recomputeInFlight = true
        }
        val appCtx = ctx.applicationContext
        val before = try { prefs.accentOverride } catch (e: Exception) { 0 }
        kotlin.concurrent.thread(name = "mizan-accent") {
            try {
                recompute(appCtx, prefs)
            } catch (e: Exception) {
                // recompute already fails open; this is belt-and-braces.
            }
            val changed = (try { prefs.accentOverride } catch (e: Exception) { 0 }) != before
            synchronized(this) { recomputeInFlight = false }
            if (changed) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { onDone() } catch (e: Exception) {
                        // A dead caller's rebuild must never kill the poster.
                    }
                }
            }
        }
    }

    fun recompute(ctx: Context, prefs: Prefs) {
        try {
            val bmp = wallpaperBitmap(ctx, prefs) ?: return
            val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
            val pixels = IntArray(32 * 32)
            small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            val preset = Themes.preset(prefs.themeId)
            val colour = DominantColor.extract(pixels, preset.accent)
            prefs.accentOverride = colour
            if (small !== bmp) small.recycle()
            bmp.recycle()
        } catch (e: Exception) {
            Prefs.noteError(ctx, "accentRecompute", e)
        }
    }

    /** The wallpaper as a bitmap (used for accent extraction), or null. */
    fun wallpaperBitmap(ctx: Context, prefs: Prefs): Bitmap? {
        return when (prefs.wallpaperKind) {
            "image" -> loadImage(ctx, prefs.wallpaperValue)
            // Reddit wallpapers are downloaded files in app-private storage;
            // wallpaperValue is the absolute file path (v1.1).
            "reddit" -> loadImageFile(prefs.wallpaperValue)
            // Weather background (v1.2): rendered from the cached
            // snapshot's palette at thumbnail size; null when there's no
            // weather data, so the preset accent is used (fail-open).
            "weather" -> {
                val snap = Weather.decodeSnapshot(prefs.weatherData)
                val cond = snap?.let { WeatherLogic.chooseCondition(it.code, it.isDay, it.dailyCodes) }
                if (cond != null) {
                    WeatherArt.render(WeatherLogic.paletteFor(cond), 64, 36)
                } else {
                    null
                }
            }
            "gradient" -> {
                val g = Themes.gradient(prefs.wallpaperValue)
                renderGradient(g.top, g.bottom)
            }
            "solid" -> {
                val c = prefs.wallpaperValue.toIntOrNull() ?: Themes.SOLIDS[0].second
                renderGradient(c, c)
            }
            else -> {
                val p = Themes.preset(prefs.themeId)
                renderGradient(p.bgTop, p.bgBottom)
            }
        }
    }

    /**
     * Decode a user image wallpaper, downscaled; null on any failure.
     * v3.0: the fixed inSampleSize=4 became a bounds-based sample via
     * DecodeBounds — a 4 was not a bound (a 20000px source still
     * decoded to 5000px ≈ 100 MB). The stream is opened twice
     * (bounds pass, then the sampled decode).
     */
    fun loadImage(ctx: Context, uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = DecodeBounds.sampleSize(
                        bounds.outWidth, bounds.outHeight, WALLPAPER_MAX_DIM
                    )
                }
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        } catch (e: Throwable) {
            Prefs.noteError(ctx, "loadWallpaperImage", e)
            null
        }
    }

    /** Decode a wallpaper from a plain file path, downscaled; null on failure. */
    fun loadImageFile(path: String): Bitmap? {
        if (path.isBlank()) return null
        return DecodeBounds.decodeFileBounded(path, WALLPAPER_MAX_DIM)
    }

    private fun renderGradient(top: Int, bottom: Int): Bitmap {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        for (y in 0 until 32) {
            val f = y / 31f
            paint.color = blend(top, bottom, f)
            canvas.drawLine(0f, y.toFloat(), 32f, y.toFloat(), paint)
        }
        return bmp
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return (0xFF shl 24) or
            (ch((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
            (ch((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or
            ch(a and 0xFF, b and 0xFF)
    }
}
