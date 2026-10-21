package com.mizan.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

/** Shared small UI helpers for the Views-based screens. */
object Ui {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Tile background (v1.6 design tokens): quiet at rest — flat 8%
     * white, NO border; focus = accent outline + a dark lift tint.
     * One treatment for every tile on every screen.
     */
    fun tileBackground(accent: Int, focused: Boolean, cornerDp: Int, ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(ctx, cornerDp).toFloat()
        if (focused) {
            d.setColor(com.mizan.launcher.core.Design.FOCUS_FILL)
            d.setStroke(dp(ctx, com.mizan.launcher.core.Design.FOCUS_STROKE_DP), accent)
        } else {
            d.setColor(com.mizan.launcher.core.Design.TILE_FILL)
        }
        return d
    }

    /**
     * Make a focusable tile lift on focus — the ONLY animation in the app
     * (tv-detox ethos). Also swaps its background to the focused style.
     * [onFocusExtra] runs after the lift handling (v1.2: tile name labels
     * and Settings focus tracking hook in here instead of replacing the
     * listener and losing the lift).
     */
    fun focusLift(view: View, accent: Int, cornerDp: Int, onFocusExtra: ((Boolean) -> Unit)? = null) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val s = com.mizan.launcher.core.Design.FOCUS_SCALE
            v.scaleX = if (hasFocus) s else 1f
            v.scaleY = if (hasFocus) s else 1f
            v.background = tileBackground(accent, hasFocus, cornerDp, v.context)
            v.elevation = if (hasFocus) dp(v.context, 8).toFloat() else 0f
            onFocusExtra?.invoke(hasFocus)
        }
    }

    /** A section title label (kept for the secondary screens). */
    fun sectionTitle(ctx: Context, text: String, colour: Int): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextColor(colour)
        tv.textSize = 20f
        tv.setPadding(0, dp(ctx, 18), 0, dp(ctx, 8))
        return tv
    }

    /**
     * The v1.6 section header used on the home screen (and available
     * to the other screens): a small-caps, letter-spaced label with a
     * thin accent rule filling the rest of the line — identical
     * everywhere, so rows read as organized groups instead of a pile.
     */
    fun sectionHeader(ctx: Context, text: String, colour: Int, accent: Int): View {
        val row = android.widget.LinearLayout(ctx)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val lp = android.widget.LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(ctx, com.mizan.launcher.core.Design.ROW_GAP_DP), 0, dp(ctx, 10))
        row.layoutParams = lp

        val tv = TextView(ctx)
        tv.text = text.uppercase()
        tv.setTextColor(colour)
        tv.textSize = com.mizan.launcher.core.Design.SECTION_HEADER_SP
        tv.letterSpacing = com.mizan.launcher.core.Design.SECTION_HEADER_TRACKING
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        row.addView(tv)

        val rule = View(ctx)
        val rlp = android.widget.LinearLayout.LayoutParams(0, dp(ctx, 2), 1f)
        rlp.setMargins(dp(ctx, 14), 0, 0, 0)
        rule.layoutParams = rlp
        rule.setBackgroundColor(
            (com.mizan.launcher.core.Design.SECTION_RULE_ALPHA shl 24) or (accent and 0x00FFFFFF)
        )
        row.addView(rule)
        return row
    }

    /** The unified wallpaper scrim (v1.6): one bottom-weighted gradient. */
    fun scrimDrawable(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            com.mizan.launcher.core.Design.scrimColors()
        )

    /**
     * App icon cropped/masked to the chosen shape.
     * shape: "circle" | "rounded" | "square". Returns a plain bitmap of the
     * icon if anything goes wrong (fail-open).
     */
    fun shapedIcon(icon: Drawable?, sizePx: Int, shape: String): Bitmap {
        val src = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c0 = Canvas(src)
        if (icon != null) {
            val old = RectF(icon.bounds)
            icon.setBounds(0, 0, sizePx, sizePx)
            icon.draw(c0)
            icon.bounds.set(old.left.toInt(), old.top.toInt(), old.right.toInt(), old.bottom.toInt())
        } else {
            val p = Paint().apply { color = 0xFF666666.toInt() }
            c0.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), p)
        }
        if (shape == "square") return src
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path()
        if (shape == "circle") {
            path.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CCW)
        } else {
            val r = sizePx * 0.22f
            path.addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, Path.Direction.CCW)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun drawableToBitmap(ctx: Context, bmp: Bitmap): BitmapDrawable = BitmapDrawable(ctx.resources, bmp)
}
