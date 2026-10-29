package com.calm.launcher.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.calm.launcher.core.IptvLogic
import com.calm.launcher.core.IptvEpg
import com.calm.launcher.data.EpgCache
import com.calm.launcher.data.Store
import com.calm.launcher.data.XtreamClient

/**
 * Fullscreen live playback. Up/Down (and channel keys) zap through the list
 * the browser passed in. OK shows or hides the channel banner. Back exits.
 */
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var banner: TextView
    private val main = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { banner.visibility = android.view.View.GONE }

    private var ids: LongArray = LongArray(0)
    private var names: Array<String> = emptyArray()
    private var index = 0
    private var account: IptvLogic.Account? = null
    private var epgIds: Array<String> = emptyArray()
    private val numberBuffer = StringBuilder()
    private val commitNumber = Runnable { commitTypedNumber() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ids = intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
        names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        epgIds = intent.getStringArrayExtra(EXTRA_EPG) ?: emptyArray()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, ids.size - 1))

        val store = Store(this)
        account = XtreamClient.account(store.iptvServer, store.iptvUser, store.iptvPass)

        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Can't play this channel", Toast.LENGTH_SHORT).show()
            }
        })

        val view = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            this.player = this@PlayerActivity.player
        }
        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            textSize = 22f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(20))
            visibility = android.view.View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        tune()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.playWhenReady = true
    }

    override fun onDestroy() {
        main.removeCallbacks(hideBanner)
        main.removeCallbacks(commitNumber)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showBanner(); true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(keyCode - KeyEvent.KEYCODE_0); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Google TV style number entry: digits build a channel number (1-based, as shown
     * on the banner). The channel tunes after a short pause, or at once when the
     * number can't grow any further.
     */
    private fun typeDigit(d: Int) {
        if (numberBuffer.length >= 4) numberBuffer.clear()
        numberBuffer.append(d)
        banner.text = "Channel ${numberBuffer}_"
        banner.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideBanner)
        main.removeCallbacks(commitNumber)
        val number = numberBuffer.toString().toIntOrNull() ?: 0
        if (number * 10 > ids.size) commitTypedNumber() else main.postDelayed(commitNumber, 1_500L)
    }

    private fun commitTypedNumber() {
        val number = numberBuffer.toString().toIntOrNull()
        numberBuffer.clear()
        if (number == null || number !in 1..ids.size) {
            Toast.makeText(this, "No channel $number", Toast.LENGTH_SHORT).show()
            showBanner()
            return
        }
        index = number - 1
        tune()
    }

    private fun zap(step: Int) {
        if (ids.isEmpty()) return
        index = (index + step + ids.size) % ids.size
        tune()
    }

    private fun tune() {
        val acc = account
        if (acc == null || !acc.isConfigured || ids.isEmpty()) {
            Toast.makeText(this, "No IPTV account configured", Toast.LENGTH_SHORT).show()
            return
        }
        player.setMediaItem(MediaItem.fromUri(IptvLogic.liveUrl(acc, ids[index])))
        player.prepare()
        player.playWhenReady = true
        showBanner()
    }

    private fun showBanner() {
        val name = names.getOrNull(index).orEmpty()
        val acc = account
        val channelEpgId = epgIds.getOrNull(index).orEmpty()
        val now = System.currentTimeMillis()
        val guide = if (acc != null) EpgCache.get(acc) { _ -> if (!isFinishing && !isDestroyed) showBanner() } else emptyMap()
        val (current, next) = IptvEpg.nowNext(guide[channelEpgId], now)
        val detail = buildString {
            if (current != null) append("  ·  Now: ${current.title}")
            if (next != null) append("   Next: ${next.title}")
        }
        banner.text = "${index + 1}   $name$detail"
        banner.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, 4_000L)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_NAMES = "names"
        const val EXTRA_EPG = "epg"
        const val EXTRA_INDEX = "index"
    }
}
