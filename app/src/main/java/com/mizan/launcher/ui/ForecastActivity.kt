package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** Forecast / weather (MAP §1.11): next 24h from current hour (wraps past midnight) + 7-day outlook. */
class ForecastActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(this, "Forecast"))
        setContentView(Ui.rootScrollView(this, col))
        Ui.runBg {
            val snap = try { UiWiring.forecast?.invoke() ?: UiWiring.weather?.getCached() } catch (e: Throwable) { null }
            Ui.runMain {
                if (snap == null) { col.addView(Ui.stateText(this, "No forecast data yet")); return@runMain }
                val units = snap.current?.units
                val unitSym = if (units == com.mizan.launcher.domain.WeatherUnits.CELSIUS) "°C" else "°F"
                val windSym = if (units == com.mizan.launcher.domain.WeatherUnits.CELSIUS) "km/h" else "mph"
                snap.current?.let { c ->
                    col.addView(Ui.text(this, "${c.temperature.toInt()}$unitSym · ${c.conditionGroup} · wind ${c.windSpeed.toInt()} $windSym", 24f, 0xFFFFFFFF.toInt(), true))
                }
                col.addView(Ui.text(this, "Next 24 hours", 20f, 0xFFFFFFFF.toInt(), true))
                snap.hourly.take(24).forEach { h ->
                    col.addView(Ui.text(this, "${h.hourLabel}: ${h.temperature.toInt()}$unitSym", 18f))
                }
                col.addView(Ui.text(this, "7-day outlook", 20f, 0xFFFFFFFF.toInt(), true))
                snap.daily.take(7).forEach { d ->
                    val precip = d.precipitation?.let { " · precip ${it.toInt()}%" } ?: ""
                    col.addView(Ui.text(this, "${d.weekdayAndDate}: ${d.conditionGroup} ${d.high.toInt()}°/${d.low.toInt()}°$precip", 18f))
                }
            }
        }
    }
}
