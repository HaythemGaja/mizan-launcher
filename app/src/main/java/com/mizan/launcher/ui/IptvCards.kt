package com.mizan.launcher.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TmdbImages

/**
 * The IPTV card (v2.2) — PosterCards' visual language (poster fills
 * the card edge-to-edge, focus lift, title + meta BELOW the card, a
 * progress bar under the meta) with two differences: the image is a
 * FULL URL from the IPTV source (a channel logo / poster the panel
 * serves — loaded through TmdbImages' generic URL loader), and a
 * [wide] variant renders a 16:9-ish card with the logo fitted
 * inside (channel logos must never crop). [line2] carries the EPG
 * NOW title on live cards; [progress] is the through-programme or
 * resume fraction (-1 = no bar).
 */
object IptvCards {

    fun columns(activity: Activity, prefs: Prefs, wide: Boolean): Int {
        val dm = activity.resources.displayMetrics
        val m = if (wide) {
            TileLayout.cardMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        } else {
            TileLayout.posterMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        }
        val margin = Ui.dp(activity, 5)
        val cell = m.tileWidthPx + margin * 2
        if (cell <= 0) return 4
        val usable = dm.widthPixels - Ui.dp(activity, 96)
        return (usable / cell).coerceIn(1, 10)
    }

    fun build(
        activity: Activity,
        prefs: Prefs,
        accent: Int,
        imageUrl: String,
        title: String,
        meta: String,
        line2: String? = null,
        glyph: String,
        wide: Boolean = false,
        tag: String? = null,
        progress: Float = -1f,
        liveBadge: Boolean = false,
        onFocus: (() -> Unit)? = null,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null,
        // v3.4 (ask 52): home-row scale — the IPTV home passes
        // TileLayout.HOME_ROW_CARD_SCALE so its poster rows
        // shrink with the Tracker/Watch rows; the browser grids
        // keep the default 1f. Width and height scale together,
        // so poster aspect and the wide logo fit are unchanged.
        scale: Float = 1f
    ): View {
        val dm = activity.resources.displayMetrics
        val base = if (wide) {
            TileLayout.cardMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        } else {
            TileLayout.posterMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        }
        val m = if (scale == 1f) base else TileLayout.Metrics(
            (base.tileWidthPx * scale).toInt().coerceAtLeast(1),
            (base.tileHeightPx * scale).toInt().coerceAtLeast(1),
            base.iconPx
        )
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(m.tileWidthPx, -2)
        val margin = Ui.dp(activity, 5)
        colLp.setMargins(margin, margin, margin, margin)
        col.layoutParams = colLp

        val frame = FrameLayout(activity)
        frame.isFocusable = true
        frame.isClickable = true
        if (tag != null) frame.tag = tag
        frame.layoutParams = LinearLayout.LayoutParams(m.tileWidthPx, m.tileHeightPx)
        frame.background = Ui.tileBackground(accent, false, 16, activity)
        if (imageUrl.isNotBlank()) {
            val iv = ImageView(activity)
            // v3.7 (poster fix): FIT_CENTER on BOTH frames — the
            // wide frame already fit complete; the narrow
            // (poster) frame centre-cropped panel art of
            // any other aspect. A true 2:3 poster fills
            // the narrow frame exactly either way.
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            if (!wide) Ui.roundClip(iv, 16, activity)
            frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
            TmdbImages.load(imageUrl, iv)
        } else {
            val g = TextView(activity)
            g.text = glyph
            g.setTextColor(0xFFFFFFFF.toInt())
            g.textSize = 44f
            g.gravity = Gravity.CENTER
            frame.addView(g, FrameLayout.LayoutParams(-1, -1))
        }
        if (liveBadge) {
            // v2.8: the red LIVE pill, top-left inside the frame —
            // the Google-TV Live card language.
            val live = TextView(activity)
            live.text = "LIVE"
            live.setTextColor(0xFFFFFFFF.toInt())
            live.textSize = 11f
            live.setTypeface(live.typeface, Typeface.BOLD)
            val liveBg = android.graphics.drawable.GradientDrawable()
            liveBg.cornerRadius = Ui.dp(activity, 100).toFloat()
            liveBg.setColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            live.background = liveBg
            live.setPadding(
                Ui.dp(activity, 8), Ui.dp(activity, 3),
                Ui.dp(activity, 8), Ui.dp(activity, 3)
            )
            val liveLp = FrameLayout.LayoutParams(-2, -2)
            liveLp.gravity = Gravity.TOP or Gravity.START
            liveLp.setMargins(Ui.dp(activity, 6), Ui.dp(activity, 6), 0, 0)
            frame.addView(live, liveLp)
        }
        // v2.8: on wide cards the progress bar runs along the
        // frame's bottom edge (the through-programme window on
        // Live cards); portrait cards keep the bar under the meta.
        val barInFrame = wide && progress >= 0f
        if (barInFrame) {
            val track = View(activity)
            track.setBackgroundColor(0x66FFFFFF)
            val trackLp = FrameLayout.LayoutParams(-1, Ui.dp(activity, 4))
            trackLp.gravity = Gravity.BOTTOM
            frame.addView(track, trackLp)
            val fillW = (m.tileWidthPx * progress.coerceIn(0f, 1f)).toInt()
                .coerceAtLeast(Ui.dp(activity, 2))
            val fill = View(activity)
            fill.setBackgroundColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            val fillLp = FrameLayout.LayoutParams(fillW, Ui.dp(activity, 4))
            fillLp.gravity = Gravity.BOTTOM or Gravity.START
            frame.addView(fill, fillLp)
        }
        // v2.8: the shared focus treatment — a white border over
        // the frame while focused (the browser uses these cards
        // too, so both surfaces read the same).
        val focusBorder = View(activity)
        val borderBg = android.graphics.drawable.GradientDrawable()
        borderBg.cornerRadius = Ui.dp(activity, 16).toFloat()
        borderBg.setColor(0x00000000)
        borderBg.setStroke(Ui.dp(activity, 3), 0xFFFFFFFF.toInt())
        focusBorder.background = borderBg
        focusBorder.layoutParams = FrameLayout.LayoutParams(-1, -1)
        focusBorder.visibility = View.INVISIBLE
        focusBorder.isFocusable = false
        focusBorder.isClickable = false
        frame.addView(focusBorder)
        Ui.focusLift(frame, accent, 16) { hasFocus ->
            focusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) onFocus?.invoke()
        }
        frame.setOnClickListener { onClick() }
        if (onLong != null) frame.setOnLongClickListener { onLong(); true }
        col.addView(frame)

        val t = TextView(activity)
        t.text = title
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 15f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.maxLines = 2
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 6), Ui.dp(activity, 2), 0)
        t.layoutParams = tlp
        col.addView(t)

        if (meta.isNotBlank()) {
            val s = TextView(activity)
            s.text = meta
            s.setTextColor(0xFFB3B3B3.toInt())
            s.textSize = 13f
            s.maxLines = 1
            s.ellipsize = android.text.TextUtils.TruncateAt.END
            val slp = LinearLayout.LayoutParams(-1, -2)
            slp.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 1), Ui.dp(activity, 2), 0)
            s.layoutParams = slp
            col.addView(s)
        }
        if (!line2.isNullOrBlank()) {
            val s = TextView(activity)
            s.text = line2
            s.setTextColor(0xFF8C8C8C.toInt())
            s.textSize = 12f
            s.maxLines = 1
            s.ellipsize = android.text.TextUtils.TruncateAt.END
            val slp = LinearLayout.LayoutParams(-1, -2)
            slp.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 1), Ui.dp(activity, 2), 0)
            s.layoutParams = slp
            col.addView(s)
        }
        if (progress >= 0f && !barInFrame) {
            val barBg = FrameLayout(activity)
            barBg.setBackgroundColor(0xFF404040.toInt())
            val blp = LinearLayout.LayoutParams(-1, Ui.dp(activity, 4))
            blp.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 5), Ui.dp(activity, 2), 0)
            barBg.layoutParams = blp
            val fill = View(activity)
            fill.setBackgroundColor(accent)
            val fillW = (m.tileWidthPx * progress.coerceIn(0f, 1f)).toInt()
            fill.layoutParams = FrameLayout.LayoutParams(fillW, -1)
            barBg.addView(fill)
            col.addView(barBg)
        }
        return col
    }
}
