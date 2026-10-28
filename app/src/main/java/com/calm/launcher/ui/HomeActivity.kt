package com.calm.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.calm.launcher.core.AppEntry
import com.calm.launcher.core.HomeModel
import com.calm.launcher.data.AppCatalog
import com.calm.launcher.data.Store

/**
 * The whole launcher screen: a clock, then three rows of apps.
 * D-pad moves between tiles; OK opens; long-press (or the menu key)
 * adds or removes a favorite. Back does nothing on the home screen.
 */
class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var clockView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(48))
        }
        clockView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 40f
            setOnClickListener { toggleClockFormat() }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        apps = AppCatalog.load(this)
        rebuild()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    /** The launcher is home; there is nothing above it to go back to. */
    override fun onBackPressed() {}

    private fun rebuild() {
        content.removeAllViews()
        content.addView(clockView)
        updateClock()

        val favorites = HomeModel.favorites(store.favorites, apps)
        val recent = HomeModel.recent(store.recent, apps, favorites.map { it.pkg }.toSet())
        content.addView(sectionTitle("Favorites"))
        content.addView(row(favorites, "Long-press any app to add it here."))
        content.addView(sectionTitle("Recently opened"))
        content.addView(row(recent, "Apps you open will appear here."))
        content.addView(sectionTitle("All apps"))
        content.addView(row(HomeModel.allApps(apps), "No launchable apps found."))
    }

    private fun updateClock() {
        val now = java.util.Calendar.getInstance()
        clockView.text = HomeModel.clockText(
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            store.clock24h
        )
    }

    private fun toggleClockFormat() {
        store.clock24h = !store.clock24h
        updateClock()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(SECONDARY_TEXT)
        textSize = 15f
        letterSpacing = 0.12f
        setPadding(0, dp(32), 0, dp(12))
    }

    /** A horizontal row of tiles, or a one-line hint when it is empty. */
    private fun row(items: List<AppEntry>, emptyHint: String): View {
        if (items.isEmpty()) {
            return TextView(this).apply {
                text = emptyHint
                setTextColor(SECONDARY_TEXT)
                textSize = 16f
            }
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        items.forEach { line.addView(tile(it)) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(line)
        }
    }

    private fun tile(app: AppEntry): View {
        val width = dp(168)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(16), 0)
            }
            isFocusable = true
            isClickable = true
            background = tileBackground(focused = false)
        }
        val icon = ImageView(this).apply {
            setImageDrawable(try { packageManager.getApplicationIcon(app.pkg) } catch (e: Exception) { null })
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        val label = TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(icon)
        box.addView(label)

        box.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            val s = if (hasFocus) FOCUS_SCALE else 1f
            v.animate().scaleX(s).scaleY(s)
                .setDuration(FOCUS_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            v.background = tileBackground(focused = hasFocus)
            v.elevation = if (hasFocus) dp(10).toFloat() else 0f
        }
        box.setOnClickListener { launch(app) }
        box.setOnLongClickListener { toggleFavorite(app); true }
        return box
    }

    private fun tileBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (focused) {
            setColor(FOCUS_FILL)
            setStroke(dp(3), ACCENT)
        } else {
            setColor(TILE_FILL)
        }
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(app.category)
            .setComponent(ComponentName(app.pkg, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            store.recent = HomeModel.touchRecent(store.recent, app.pkg)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFavorite(app: AppEntry) {
        val updated = HomeModel.toggleFavorite(store.favorites, app.pkg)
        if (updated == null) {
            Toast.makeText(this, "Favorites are full (${HomeModel.MAX_FAVORITES})", Toast.LENGTH_SHORT).show()
            return
        }
        store.favorites = updated
        Toast.makeText(
            this,
            if (app.pkg in updated) "Added ${app.label} to favorites" else "Removed ${app.label} from favorites",
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF101418.toInt()
        const val TILE_FILL = 0x14FFFFFF
        const val FOCUS_FILL = 0x38000000
        const val ACCENT = 0xFF7FB8FF.toInt()
        const val SECONDARY_TEXT = 0xFF9AA4B0.toInt()
        const val FOCUS_SCALE = 1.06f
        const val FOCUS_MS = 160L
    }
}
