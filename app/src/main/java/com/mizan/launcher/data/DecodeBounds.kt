package com.mizan.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The ONE place image decodes are bounded (v3.0, spec §30).
 *
 * A byte cap on a download does NOT bound the decoded bitmap: a
 * small compressed image can expand to gigapixels (a 10000×10000
 * PNG is a 400 MB bitmap) and OOM-kill the process mid-decode —
 * an Error no surrounding `catch (e: Exception)` stops. Before
 * v3.0, TmdbImages downsampled properly but ArtworkImages decoded
 * raw bytes and files unbounded, and AccentUtil used a fixed
 * sample of 4 regardless of source size.
 *
 * Every decode through here is two-pass: bounds first
 * (inJustDecodeBounds allocates nothing), then a sampled decode
 * whose longest side is guaranteed ≤ [maxDim]. All failures read
 * as null — callers already fail open to their fallback art.
 */
object DecodeBounds {

    /** Power-of-two sample bringing the longest side to ≤ [maxDim] (≥1). */
    fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var s = 1
        while (maxOf(width, height) / s > maxDim) s *= 2
        return s
    }

    /** Decode [bytes] with the longest side capped at [maxDim]; null on any failure. */
    fun decodeBytesBounded(bytes: ByteArray, maxDim: Int): Bitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Throwable) {
            null
        }

    /** Decode the file at [path] with the longest side capped at [maxDim]; null on any failure. */
    fun decodeFileBounded(path: String, maxDim: Int): Bitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                }
                BitmapFactory.decodeFile(path, opts)
            }
        } catch (e: Throwable) {
            null
        }
}
