package com.calm.launcher.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.calm.launcher.core.IptvLogic
import com.calm.launcher.data.Store
import com.calm.launcher.data.XtreamClient

/**
 * Live TV browser in the Smarters layout: categories on the left, channels on the right.
 * OK on a channel opens the player; the player can zap through the same list.
 */
class IptvActivity : Activity() {

    private lateinit var store: Store
    private lateinit var categoryColumn: LinearLayout
    private lateinit var channelColumn: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())

    private var categories: List<IptvLogic.Category> = emptyList()
    private var channels: List<IptvLogic.LiveChannel> = emptyList()
    private var selectedCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        status = TextView(this).apply {
            setTextColor(SECONDARY)
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }
        categoryColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(this@IptvActivity).apply {
                addView(categoryColumn)
            }, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ScrollView(this@IptvActivity).apply {
                addView(channelColumn)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(24), 0, 0, 0)
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(40), dp(32), dp(40), dp(32))
            addView(TextView(this@IptvActivity).apply {
                text = "Live TV"
                setTextColor(Color.WHITE)
                textSize = 30f
            })
            addView(status)
            addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val account = XtreamClient.account(store.iptvServer, store.iptvUser, store.iptvPass)
        if (!account.isConfigured) {
            setStatus("No IPTV account yet. Add one in Settings → IPTV account.")
            return
        }
        setStatus("Loading channels…")
        Thread {
            val (cats, streams) = XtreamClient.loadLive(account)
            main.post {
                if (isFinishing || isDestroyed) return@post
                categories = cats
                channels = streams
                if (streams.isEmpty()) {
                    setStatus("Couldn't load channels. Check the account in Settings.")
                } else {
                    setStatus("${streams.size} channels")
                }
                selectedCategory = selectedCategory ?: cats.firstOrNull()?.id
                renderCategories()
                renderChannels()
            }
        }.start()
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun renderCategories() {
        categoryColumn.removeAllViews()
        val all = listOf(ALL_ID to "All channels") + categories.map { it.id to it.name }
        for ((id, name) in all) {
            categoryColumn.addView(row(name, selected = id == (selectedCategory ?: ALL_ID)) {
                selectedCategory = id
                renderCategories()
                renderChannels()
            })
        }
    }

    private fun renderChannels() {
        channelColumn.removeAllViews()
        val cat = selectedCategory
        val shown = if (cat == null || cat == ALL_ID) channels else channels.filter { it.categoryId == cat }
        // Large playlists stay responsive by showing a bounded window.
        val window = shown.take(MAX_ROWS)
        for (ch in window) {
            channelColumn.addView(row(ch.name, selected = false) { openPlayer(window, window.indexOf(ch)) })
        }
        if (shown.size > MAX_ROWS) {
            channelColumn.addView(TextView(this).apply {
                text = "Showing first $MAX_ROWS of ${shown.size}. Pick a category to narrow the list."
                setTextColor(SECONDARY)
            })
        }
    }

    private fun openPlayer(list: List<IptvLogic.LiveChannel>, index: Int) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_IDS, list.map { it.id }.toLongArray())
            .putExtra(PlayerActivity.EXTRA_NAMES, list.map { it.name }.toTypedArray())
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
        startActivity(intent)
    }

    private fun row(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 18f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6))
        }
        background = focusBackground(selected)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, hasFocus -> v.background = focusBackground(hasFocus || selected) }
        setOnClickListener { onClick() }
    }

    private fun focusBackground(lit: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        if (lit) {
            setColor(0x38000000)
            setStroke(dp(2), ACCENT)
        } else {
            setColor(0x14FFFFFF)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val ALL_ID = "__all__"
        const val MAX_ROWS = 300
        const val BACKGROUND = 0xFF101418.toInt()
        const val SECONDARY = 0xFF9AA4B0.toInt()
        const val ACCENT = 0xFF7FB8FF.toInt()
    }
}
