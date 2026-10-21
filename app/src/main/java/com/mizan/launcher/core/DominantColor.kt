package com.mizan.launcher.core

/**
 * Monet-style dominant colour extraction — our own algorithm, pure Kotlin so
 * it is unit-tested on synthetic pixel arrays in the headless sim.
 *
 * Method: histogram over 4-bit-per-channel buckets (16x16x16), ignoring
 * near-black and near-white pixels (they carry no accent information), then
 * return the average of the pixels in the winning bucket.
 */
object DominantColor {

    /**
     * @param pixels ARGB ints (e.g. from a downscaled bitmap).
     * @param fallback returned when no usable pixel exists.
     * @return dominant colour, always opaque.
     */
    fun extract(pixels: IntArray, fallback: Int): Int {
        if (pixels.isEmpty()) return opaque(fallback)
        val buckets = HashMap<Int, LongArray>() // key -> [count, sumR, sumG, sumB]
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 24) continue          // near-black: no accent signal
            if (min > 232) continue         // near-white: no accent signal
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val acc = buckets.getOrPut(key) { LongArray(4) }
            acc[0] = acc[0] + 1
            acc[1] = acc[1] + r
            acc[2] = acc[2] + g
            acc[3] = acc[3] + b
        }
        var best: LongArray? = null
        for ((_, acc) in buckets) {
            val cur = best
            if (cur == null || acc[0] > cur[0]) best = acc
        }
        val b = best ?: return opaque(fallback)
        val n = b[0].coerceAtLeast(1)
        return opaque(
            (0xFF shl 24) or
                ((b[1] / n).toInt() shl 16) or
                ((b[2] / n).toInt() shl 8) or
                (b[3] / n).toInt()
        )
    }

    private fun opaque(c: Int): Int = (c or (0xFF shl 24))
}
