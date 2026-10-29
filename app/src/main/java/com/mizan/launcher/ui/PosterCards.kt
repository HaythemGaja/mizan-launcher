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
 * The portrait 2:3 poster card for the v2.1 screens (Library, TMDB
 * search) — the same visual language as the Tracker view's cards
 * (§18.4): poster renders COMPLETE (v3.7: fit-complete, never
 * cropped — a true 2:3 poster fills this 2:3 frame
 * exactly either way, but off-ratio art is never cropped
 * any more; see MainActivity.portraitCard's v3.7 note),
 * rounded
 * corners, focus lift), title + meta BELOW the card, never overlaid.
 * An optional progress bar sits under the meta line ([progress] in
 * 0..1; negative = no bar). The focusable frame carries [tag] so
 * callers can restore focus across rebuilds.
 */
object PosterCards {

    /** Cards that fit across [screenWidthPx] at the profile's size settings. */
    /**
     * v3.16: how many poster columns actually FIT.
     *
     * The old formula sized one tile for the profile's [Prefs.columns]
     * and divided the usable width by that cell — which only works while
     * the tile's width is a pure function of the column count. It is
     * not: poster width is derived from tile HEIGHT, which the app-size
     * key also moves, so a 6-column profile on a 1080p panel produced
     * cells that summed past the right edge and the last poster was
     * clipped by the screen (the grid's known "cut off column" look).
     *
     * Walking down from the profile's wish until a row genuinely fits
     * makes the grid self-correcting at any size, panel or font scale.
     */
    fun columns(activity: Activity, prefs: Prefs): Int {
        val dm = activity.resources.displayMetrics
        val margin = Ui.dp(activity, 5)
        val usable = dm.widthPixels - Ui.dp(activity, 96)
        var n = prefs.columns.coerceIn(1, 10)
        while (n > 1) {
            val pm = TileLayout.posterMetrics(dm.widthPixels, dm.density, prefs.appSize, n)
            val cell = pm.tileWidthPx + margin * 2
            if (cell > 0 && n * cell <= usable) return n
            n--
        }
        return 1
    }

    fun build(
        activity: Activity,
        prefs: Prefs,
        columns: Int = prefs.columns,
        accent: Int,
        posterPath: String,
        title: String,
        meta: String,
        line2: String? = null,
        glyph: String,
        tag: String? = null,
        progress: Float = -1f,
        onFocus: (() -> Unit)? = null,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null
    ): View {
        val dm = activity.resources.displayMetrics
        val pm = TileLayout.posterMetrics(dm.widthPixels, dm.density, prefs.appSize, columns)
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(pm.tileWidthPx, -2)
        val margin = Ui.dp(activity, 5)
        colLp.setMargins(margin, margin, margin, margin)
        col.layoutParams = colLp

        val frame = FrameLayout(activity)
        frame.isFocusable = true
        frame.isClickable = true
        if (tag != null) frame.tag = tag
        frame.layoutParams = LinearLayout.LayoutParams(pm.tileWidthPx, pm.tileHeightPx)
        frame.background = Ui.tileBackground(accent, false, 16, activity)
        if (posterPath.isNotBlank()) {
            val iv = ImageView(activity)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            Ui.roundClip(iv, 16, activity)
            frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
            TmdbImages.load(TmdbImages.url("w342", posterPath), iv)
        } else {
            val g = TextView(activity)
            g.text = glyph
            g.setTextColor(0xFFFFFFFF.toInt())
            g.textSize = 44f
            g.gravity = Gravity.CENTER
            frame.addView(g, FrameLayout.LayoutParams(-1, -1))
        }
        Ui.focusLift(frame, accent, 16) { hasFocus -> if (hasFocus) onFocus?.invoke() }
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
            s.maxLines = 2
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
        if (progress >= 0f) {
            val barBg = FrameLayout(activity)
            barBg.setBackgroundColor(0xFF404040.toInt())
            val blp = LinearLayout.LayoutParams(-1, Ui.dp(activity, 4))
            blp.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 5), Ui.dp(activity, 2), 0)
            barBg.layoutParams = blp
            val fill = View(activity)
            fill.setBackgroundColor(com.mizan.launcher.core.Design.NETFLIX_RED)
            val fillW = (pm.tileWidthPx * progress.coerceIn(0f, 1f)).toInt()
            fill.layoutParams = FrameLayout.LayoutParams(fillW, -1)
            barBg.addView(fill)
            col.addView(barBg)
        }
        return col
    }
}
