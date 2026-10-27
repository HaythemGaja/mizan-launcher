package com.mizan.launcher.app

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.Themes
import com.mizan.launcher.data.PrefsStore
import com.mizan.launcher.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screensaver (MAP §1.14): dim clock (64sp, 75% alpha) + date
 * line over the theme gradient dimmed to half brightness (OLED
 * burn-in protection); clock ticks every 30 s; non-interactive,
 * fullscreen. The ui layer ships no dream content of its own, so
 * this service renders the MAP §1.14 composition directly from
 * the current theme preset (same gradient + dimming rule as the
 * reference implementation).
 */
class MizanDreamService : DreamService() {

    private var clockView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val preset = try {
            val prefs = PrefsStore(this)
            Themes.preset(prefs.theme())
        } catch (e: Throwable) {
            Themes.preset("apple_black")
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(dim(preset.bgTop), dim(preset.bgBottom))
        )

        clockView = TextView(this)
        clockView?.setTextColor(preset.text)
        clockView?.alpha = 0.75f
        clockView?.textSize = 64f
        clockView?.gravity = Gravity.CENTER
        root.addView(clockView)

        val date = TextView(this)
        date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        date.setTextColor(preset.subText)
        date.alpha = 0.6f
        date.textSize = 22f
        date.gravity = Gravity.CENTER
        date.setPadding(0, Ui.dp(this, 8), 0, 0)
        root.addView(date)

        setContentView(root)
        updateClock()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        handler.removeCallbacks(tick)
    }

    private fun updateClock() {
        try {
            clockView?.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
        } catch (e: Throwable) {
            // Cosmetic only.
        }
    }

    private fun dim(c: Int): Int {
        fun half(v: Int) = (v * 0.5f).toInt()
        return (0xFF shl 24) or
            (half((c shr 16) and 0xFF) shl 16) or
            (half((c shr 8) and 0xFF) shl 8) or
            half(c and 0xFF)
    }
}
