package com.mizan.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.mizan.launcher.core.WeatherPalette

/**
 * Draws the weather background (v1.2): the condition's gradient palette
 * plus one simple motif — sun/moon disc, cloud bands, fog bands, rain
 * streaks, a storm bolt, snow dots. Drawn ONCE onto a bitmap when the
 * home screen is built (or at 64x36 for accent extraction); there is
 * no animation and no downloaded imagery. Deterministic: the same
 * palette always draws the same picture (motif pseudo-randomness uses
 * a fixed seed pattern, not Random).
 */
object WeatherArt {

    fun render(palette: WeatherPalette, width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Vertical gradient: palette top at the top, bottom at the bottom.
        for (y in 0 until h) {
            paint.color = blend(palette.top, palette.bottom, y / (h - 1).coerceAtLeast(1).toFloat())
            canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), paint)
        }

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
        val softWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); alpha = 70
        }
        val cx = w * 0.80f
        val cy = h * 0.24f
        val r = minOf(w, h) * 0.13f

        when (palette.motif) {
            "sun" -> {
                // Glow ring + solid disc.
                accentPaint.alpha = 70
                canvas.drawCircle(cx, cy, r * 1.55f, accentPaint)
                accentPaint.alpha = 235
                canvas.drawCircle(cx, cy, r, accentPaint)
            }
            "moon" -> {
                accentPaint.alpha = 235
                canvas.drawCircle(cx, cy, r, accentPaint)
                // Bite taken out by an offset disc in the sky's top colour
                // (slightly lifted so it reads as a crescent, not a hole).
                val bite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.top }
                canvas.drawCircle(cx + r * 0.45f, cy - r * 0.30f, r * 0.82f, bite)
            }
            "partly" -> {
                accentPaint.alpha = 200
                canvas.drawCircle(cx, cy, r * 0.8f, accentPaint)
                drawClouds(canvas, softWhite, w, h, cy + r * 0.7f)
            }
            "cloud" -> drawClouds(canvas, softWhite, w, h, h * 0.20f)
            "fog" -> {
                softWhite.alpha = 90
                var i = 0
                while (i < 5) {
                    val y = h * (0.30f + i * 0.13f)
                    canvas.drawRect(w * 0.06f, y, w * 0.94f, y + h * 0.045f, softWhite)
                    i++
                }
            }
            "rain" -> {
                drawClouds(canvas, softWhite, w, h, h * 0.18f)
                val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.accent; alpha = 160; strokeWidth = (w * 0.004f).coerceAtLeast(1f)
                }
                var i = 0
                while (i < 26) {
                    val x = ((i * 73) % 100) / 100f * w
                    val y = h * 0.38f + ((i * 37) % 45) / 100f * h
                    canvas.drawLine(x, y, x - w * 0.02f, y + h * 0.10f, streak)
                    i++
                }
            }
            "storm" -> {
                drawClouds(canvas, softWhite, w, h, h * 0.18f)
                // Simple lightning polygon under the cloud band.
                val bolt = Path()
                val bx = w * 0.46f
                val by = h * 0.36f
                val bw = w * 0.07f
                val bh = h * 0.30f
                bolt.moveTo(bx + bw * 0.55f, by)
                bolt.lineTo(bx, by + bh * 0.55f)
                bolt.lineTo(bx + bw * 0.38f, by + bh * 0.55f)
                bolt.lineTo(bx + bw * 0.22f, by + bh)
                bolt.lineTo(bx + bw, by + bh * 0.42f)
                bolt.lineTo(bx + bw * 0.60f, by + bh * 0.42f)
                bolt.close()
                accentPaint.alpha = 235
                canvas.drawPath(bolt, accentPaint)
            }
            "snow" -> {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt(); alpha = 200
                }
                var i = 0
                while (i < 60) {
                    val x = ((i * 61) % 100) / 100f * w
                    val y = ((i * 43) % 100) / 100f * h
                    canvas.drawCircle(x, y, (minOf(w, h) * 0.008f).coerceAtLeast(1f), dot)
                    i++
                }
            }
            else -> {
                // Unknown motif: plain gradient (fail-open).
            }
        }
        return bmp
    }

    /** A row of overlapping soft cloud ellipses around baseline [baseY]. */
    private fun drawClouds(canvas: Canvas, paint: Paint, w: Int, h: Int, baseY: Float) {
        val unit = minOf(w, h)
        val blobs = listOf(
            Triple(0.24f, 0f, 0.16f), Triple(0.40f, -0.03f, 0.20f), Triple(0.58f, 0f, 0.17f),
            Triple(0.74f, -0.02f, 0.15f), Triple(0.47f, 0.05f, 0.22f)
        )
        for ((fx, fy, fr) in blobs) {
            canvas.drawCircle(w * fx, baseY + h * fy, unit * fr, paint)
        }
        canvas.drawRect(0f, baseY, w.toFloat(), baseY + unit * 0.10f, paint)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return (0xFF shl 24) or
            (ch((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
            (ch((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or
            ch(a and 0xFF, b and 0xFF)
    }
}
