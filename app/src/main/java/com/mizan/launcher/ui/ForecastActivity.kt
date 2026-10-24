package com.mizan.launcher.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mizan.launcher.core.ForecastLogic
import com.mizan.launcher.core.Themes
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen weather forecast (v1.4) — opened by pressing OK on the
 * status-bar weather widget.
 *
 * Top: the next 24 hours as a horizontally-scrolling strip, starting
 * at the current hour. Below: the 7-day list (day, condition, hi/lo,
 * precipitation probability where the API provided it). All values
 * come from the same Open-Meteo fetch/cache as the widget (hourly +
 * daily requested in the same call); units and 12/24h follow the
 * profile. D-pad UP/DOWN scrolls the page; BACK returns home.
 *
 * Fail-open: no cached data yet (or an empty series) shows a calm
 * "loading — check your connection" placeholder and triggers a fetch;
 * this screen never crashes on missing weather.
 */
class ForecastActivity : Activity() {

    private lateinit var prefs: Prefs
    private var pageScroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        render()
        maybeFetch()
    }

    /** Page scrolling is manual: the page has no focusable children. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val sv = pageScroll
            if (sv != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { sv.smoothScrollBy(0, Ui.dp(this, 160)); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { sv.smoothScrollBy(0, -Ui.dp(this, 160)); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { sv.smoothScrollBy(Ui.dp(this, 160), 0); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { sv.smoothScrollBy(-Ui.dp(this, 160), 0); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Fetch when the cache is missing or stale (same 30-minute TTL as home). */
    private fun maybeFetch() {
        val stale = System.currentTimeMillis() - prefs.lastWeatherFetch > 30 * 60 * 1000
        val snap = Weather.decodeSnapshot(prefs.weatherData)
        if (!stale && snap != null && snap.hourly.isNotEmpty()) return
        Weather.fetch(prefs.weatherLat, prefs.weatherLon, prefs.weatherUnits) { fresh ->
            runOnUiThread {
                try {
                    if (fresh != null) {
                        prefs.cachedWeather = fresh.text
                        prefs.weatherData = Weather.encodeSnapshot(fresh)
                        prefs.lastWeatherFetch = System.currentTimeMillis()
                        render()
                    }
                } catch (e: Exception) {
                    // The placeholder stays; nothing else to do.
                }
            }
        }
    }

    /**
     * v3.0: every forecast render runs behind this guard (the
     * IptvActivity rule): an escape is recorded and replaced by a
     * worded line, never a black screen.
     */
    private fun render() {
        try {
            renderInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "forecastRender", e)
            try {
                val line = TextView(this)
                line.text = "The forecast hit a snag — press Back and try again."
                line.setTextColor(0xFFFFFFFF.toInt())
                line.textSize = 17f
                line.setPadding(
                    Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48)
                )
                setContentView(line)
            } catch (ignored: Throwable) {
                // Leave whatever is on screen rather than crash.
            }
        }
    }

    private fun renderInner() {
        val preset = Themes.preset(prefs.themeId)
        val textColour = preset.text
        val subColour = preset.subText
        val accent = if (preset.dynamic && prefs.accentOverride != 0) prefs.accentOverride else preset.accent
        val units = prefs.weatherUnits
        val use24h = prefs.layout.statusBar.clock24h

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(preset.bgTop, preset.bgBottom)
        )
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 30), Ui.dp(this, 48), Ui.dp(this, 20))

        val title = TextView(this)
        title.text = "Weather forecast"
        title.setTextColor(accent); title.textSize = 30f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        root.addView(title)

        val snap = Weather.decodeSnapshot(prefs.weatherData)
        if (snap == null || (snap.hourly.isEmpty() && snap.dailyFull.isEmpty())) {
            val placeholder = TextView(this)
            placeholder.text = "\nWeather is loading — check your connection.\n" +
                "The forecast appears here once the first reading arrives."
            placeholder.setTextColor(subColour); placeholder.textSize = 20f
            root.addView(placeholder)
            setContentView(root)
            pageScroll = null
            return
        }

        val sub = TextView(this)
        sub.text = snap.text.ifEmpty {
            "${ForecastLogic.tempText(snap.temp, units)} ${ForecastLogic.conditionLabel(snap.code)}"
        } + "  ·  ${prefs.weatherLat}, ${prefs.weatherLon}"
        sub.setTextColor(subColour); sub.textSize = 18f
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14))
        root.addView(sub)

        val scroll = ScrollView(this)
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        pageScroll = scroll

        // ---- hourly strip: next 24 hours from the current hour ----
        if (snap.hourly.isNotEmpty()) {
            page.addView(sectionLabel("Next 24 hours", accent))
            val nowKey = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date())
            val startIdx = ForecastLogic.currentHourIndex(snap.hourly.map { it.timeIso }, nowKey)
            val window = ForecastLogic.hourWindow(snap.hourly, if (startIdx >= 0) startIdx else 0)
            val strip = LinearLayout(this)
            strip.orientation = LinearLayout.HORIZONTAL
            for (point in window) {
                val row = ForecastLogic.hourRow(point, units, use24h)
                strip.addView(hourCell(row.label, row.temp, row.condition, textColour, subColour, accent))
            }
            val hscroll = HorizontalScrollView(this)
            hscroll.isHorizontalScrollBarEnabled = false
            hscroll.addView(strip)
            page.addView(hscroll)
        }

        // ---- 7-day list ----
        if (snap.dailyFull.isNotEmpty()) {
            page.addView(sectionLabel("7 days", accent))
            for (day in snap.dailyFull) {
                val row = ForecastLogic.dayRow(day, units)
                page.addView(dayRowView(row.label, row.condition, row.precip, row.hiLo, textColour, subColour))
            }
        }

        setContentView(root)
    }

    private fun sectionLabel(text: String, colour: Int): TextView {
        // v1.6: same small-caps section style as the home screen.
        val tv = TextView(this)
        tv.text = text.uppercase()
        tv.setTextColor(colour)
        tv.textSize = com.mizan.launcher.core.Design.SECTION_HEADER_SP
        tv.letterSpacing = com.mizan.launcher.core.Design.SECTION_HEADER_TRACKING
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8))
        return tv
    }

    /** One hour cell: time, big temperature, short condition under it. */
    private fun hourCell(label: String, temp: String, condition: String, text: Int, sub: Int, accent: Int): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0)
        cell.layoutParams = lp
        cell.background = Ui.tileBackground(accent, false, 12, this)

        val l = TextView(this); l.text = label; l.setTextColor(sub); l.textSize = 15f; l.gravity = Gravity.CENTER
        val t = TextView(this); t.text = temp; t.setTextColor(text); t.textSize = 24f; t.gravity = Gravity.CENTER
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        val c = TextView(this); c.text = condition; c.setTextColor(sub); c.textSize = 13f; c.gravity = Gravity.CENTER
        cell.addView(l); cell.addView(t); cell.addView(c)
        return cell
    }

    /** One day row: day + condition on the left, precip + hi/lo on the right. */
    private fun dayRowView(label: String, condition: String, precip: String?, hiLo: String, text: Int, sub: Int): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 9), Ui.dp(this, 6), Ui.dp(this, 9))

        val left = TextView(this)
        left.text = "$label — $condition"
        left.setTextColor(text); left.textSize = 20f
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        if (precip != null) {
            val p = TextView(this)
            p.text = precip
            p.setTextColor(sub); p.textSize = 18f
            p.setPadding(0, 0, Ui.dp(this, 18), 0)
            row.addView(p)
        }
        val hl = TextView(this)
        hl.text = hiLo
        hl.setTextColor(text); hl.textSize = 20f
        row.addView(hl)
        return row
    }
}
