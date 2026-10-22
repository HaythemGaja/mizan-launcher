package com.mizan.launcher.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.PlayItem
import com.mizan.launcher.data.PlayQueue
import com.mizan.launcher.data.Prefs

/**
 * The IPTV player (v2.2, spec §20.5) — platform VideoView, zero new
 * dependencies. Full-screen; OK = pause/play, ←/→ = seek ∓30 s
 * (seekable kinds only: VOD, episodes, timeshift), ↑/↓ = zap
 * previous/next channel while Live (the PlayQueue the launching
 * surface set), hold-OK or Menu = the player menu (Watch from
 * start when catch-up applies, Open in external player), Back =
 * exit.
 *
 * Resume positions are saved per profile (core/IptvLogic store)
 * every few seconds and on exit, and cleared near the end; a saved
 * position is applied once the stream is prepared.
 *
 * Playback is HLS-FIRST: VideoView/MediaPlayer handles .m3u8 on
 * Android TV; a server that only serves raw .ts will error here —
 * the error state offers "Open in external player" (ACTION_VIEW on
 * the stream URL) as the fail-open, exactly like a failed stream.
 *
 * Picture-in-picture (scope addition 4): the manifest declares
 * supportsPictureInPicture; pressing Home mid-playback
 * (onUserLeaveHint) tries to enter PiP (16:9) instead of just
 * leaving. Device-dependent — a refusal is caught and normal
 * behaviour continues.
 */
class PlayerActivity : Activity() {

    companion object {
        private const val X_KIND = "kind"
        private const val X_SOURCE = "source"
        private const val X_ID = "id"
        private const val X_TITLE = "title"
        private const val X_ICON = "icon"
        private const val X_META = "meta"
        private const val X_EXT = "ext"
        private const val X_CATKEY = "catkey"
        private const val X_DIRECT = "direct"
        private const val X_SERIES = "series_id"
        private const val X_SHIFT_START = "shift_start"
        private const val X_SHIFT_DUR = "shift_dur"
        private const val X_ARCHIVE = "tv_archive"
        private const val X_EPGID = "epg_id"
        // v2.3: the Watch auto-mark token (tracker coordinates).
        private const val X_MARK_ID = "mark_tmdb_id"
        private const val X_MARK_TYPE = "mark_type"
        private const val X_MARK_S = "mark_season"
        private const val X_MARK_E = "mark_episode"

        /** Launch intent for one item; the zap queue is set separately via PlayQueue. */
        fun intent(ctx: Context, item: PlayItem): Intent {
            val i = Intent(ctx, PlayerActivity::class.java)
            i.putExtra(X_KIND, item.kind)
            i.putExtra(X_SOURCE, item.source)
            i.putExtra(X_ID, item.id)
            i.putExtra(X_TITLE, item.title)
            i.putExtra(X_ICON, item.icon)
            i.putExtra(X_META, item.meta)
            i.putExtra(X_EXT, item.ext)
            i.putExtra(X_CATKEY, item.catKey)
            i.putExtra(X_DIRECT, item.directUrl)
            i.putExtra(X_SERIES, item.seriesId)
            i.putExtra(X_SHIFT_START, item.shiftStartMs)
            i.putExtra(X_SHIFT_DUR, item.shiftDurationMin)
            i.putExtra(X_ARCHIVE, item.tvArchive)
            i.putExtra(X_EPGID, item.epgId)
            i.putExtra(X_MARK_ID, item.markTmdbId)
            i.putExtra(X_MARK_TYPE, item.markType)
            i.putExtra(X_MARK_S, item.markSeason)
            i.putExtra(X_MARK_E, item.markEpisode)
            return i
        }

        fun itemFrom(intent: Intent): PlayItem = PlayItem(
            kind = intent.getStringExtra(X_KIND) ?: IptvLogic.KIND_LIVE,
            source = intent.getStringExtra(X_SOURCE) ?: IptvLogic.SRC_XTREAM,
            id = intent.getStringExtra(X_ID) ?: "",
            title = intent.getStringExtra(X_TITLE) ?: "",
            icon = intent.getStringExtra(X_ICON) ?: "",
            meta = intent.getStringExtra(X_META) ?: "",
            ext = intent.getStringExtra(X_EXT) ?: "",
            catKey = intent.getStringExtra(X_CATKEY) ?: "",
            directUrl = intent.getStringExtra(X_DIRECT) ?: "",
            seriesId = intent.getLongExtra(X_SERIES, 0L),
            shiftStartMs = intent.getLongExtra(X_SHIFT_START, 0L),
            shiftDurationMin = intent.getIntExtra(X_SHIFT_DUR, 0),
            tvArchive = intent.getIntExtra(X_ARCHIVE, 0),
            epgId = intent.getStringExtra(X_EPGID) ?: "",
            markTmdbId = intent.getLongExtra(X_MARK_ID, 0L),
            markType = intent.getStringExtra(X_MARK_TYPE) ?: "",
            markSeason = intent.getIntExtra(X_MARK_S, 0),
            markEpisode = intent.getIntExtra(X_MARK_E, 0)
        )
    }

    private lateinit var prefs: Prefs
    private lateinit var video: VideoView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var hintView: TextView
    private lateinit var overlay: LinearLayout
    private var menuPanel: LinearLayout? = null
    private var errorPanel: LinearLayout? = null

    private var item: PlayItem? = null
    private var currentUrl: String? = null
    private var pendingSeekMs: Long = 0L
    private var prepared = false
    /** v2.3: the Watch auto-mark fires at most once per played item. */
    private var markFired = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val saveTick = object : Runnable {
        override fun run() {
            maybeAutoMark(ended = false)
            saveResume()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        video = VideoView(this)
        video.keepScreenOn = true
        root.addView(video, FrameLayout.LayoutParams(-1, -1))

        overlay = LinearLayout(this)
        overlay.orientation = LinearLayout.VERTICAL
        overlay.setPadding(Ui.dp(this, 40), Ui.dp(this, 28), Ui.dp(this, 40), Ui.dp(this, 24))
        titleView = TextView(this)
        titleView.setTextColor(0xFFFFFFFF.toInt())
        titleView.textSize = 24f
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        overlay.addView(titleView)
        metaView = TextView(this)
        metaView.setTextColor(0xFFB3B3B3.toInt())
        metaView.textSize = 15f
        overlay.addView(metaView)
        val spacer = View(this)
        overlay.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        hintView = TextView(this)
        hintView.setTextColor(0xCCFFFFFF.toInt())
        hintView.textSize = 14f
        hintView.gravity = Gravity.CENTER
        overlay.addView(hintView)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        video.setOnPreparedListener {
            prepared = true
            if (pendingSeekMs > 0L) {
                try { video.seekTo(pendingSeekMs.toInt()) } catch (e: Exception) {
                    // A stream that refuses the seek still plays from the start.
                }
                pendingSeekMs = 0L
            }
            video.start()
        }
        video.setOnCompletionListener {
            maybeAutoMark(ended = true)
            clearResume()
        }
        video.setOnErrorListener { _, _, _ ->
            showError()
            true
        }

        val launched = itemFrom(intent)
        // Adopt the queue when the launching surface set one and it
        // points at this same item; otherwise play standalone.
        val queued = PlayQueue.current()
        startItem(if (queued != null && queued.id == launched.id && queued.kind == launched.kind) queued else launched)
        handler.postDelayed(saveTick, 5_000L)
    }

    override fun onPause() {
        super.onPause()
        maybeAutoMark(ended = false)
        saveResume()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(hideOverlay)
        saveResume()
        super.onDestroy()
    }

    // ------------------------------------------------------------ playback

    private fun startItem(newItem: PlayItem) {
        saveResume() // for the item being replaced (zap), if any
        item = newItem
        markFired = false
        prepared = false
        hidePanels()
        titleView.text = newItem.title
        metaView.text = newItem.meta
        hintView.text = when {
            newItem.seekable -> "OK pause/play · ←/→ seek 30s · hold OK for menu · Back exits"
            PlayQueue.items.size > 1 -> "OK pause/play · ↑/↓ channel · hold OK for menu · Back exits"
            else -> "OK pause/play · hold OK for menu · Back exits"
        }
        showOverlayBriefly()
        val url = IptvClient.playUrl(this, newItem)
        currentUrl = url
        if (url == null) {
            Toast.makeText(this, "This source isn't set up — check Settings › IPTV", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pendingSeekMs = if (newItem.seekable) {
            IptvLogic.resumeFor(prefs.iptvResumeList(), newItem.resumeKey)?.posMs ?: 0L
        } else {
            0L
        }
        try {
            video.setVideoURI(Uri.parse(url))
            video.requestFocus()
        } catch (e: Exception) {
            Prefs.noteError(this, "iptvPlay", RuntimeException(e.javaClass.simpleName))
            showError()
        }
    }

    private fun saveResume() {
        val cur = item ?: return
        // Timeshift streams are seekable but never resume-stored:
        // a catch-up position is meaningless once the programme ends.
        if (!cur.seekable || cur.kind == IptvClient.KIND_SHIFT || !prepared) return
        try {
            val pos = video.currentPosition.toLong()
            val dur = video.duration.toLong()
            val entry = IptvLogic.ResumeEntry(
                key = cur.resumeKey, kind = cur.kind, id = cur.id,
                title = cur.title, icon = cur.icon, meta = cur.meta,
                posMs = pos, durMs = dur, updatedAt = System.currentTimeMillis(),
                catKey = cur.catKey, ext = cur.ext, directUrl = cur.directUrl
            )
            prefs.setIptvResumeList(IptvLogic.withResume(prefs.iptvResumeList(), entry))
        } catch (e: Exception) {
            // Resume is best-effort bookkeeping; never disturb playback.
        }
    }

    /**
     * v2.3 Watch auto-mark: when a token-carrying play ends or
     * crosses ≥90% (the decision is core/WatchMatch's), hand the
     * item to the completion handler data/WatchPlay registered —
     * it queues the tracker's mark_watched. The player itself
     * knows nothing about the tracker; tokenless (plain IPTV)
     * plays short-circuit here, and each item fires at most once.
     */
    private fun maybeAutoMark(ended: Boolean) {
        val cur = item ?: return
        if (markFired || !cur.hasMarkToken) return
        try {
            val pos = try { video.currentPosition.toLong() } catch (e: Exception) { 0L }
            val dur = try { video.duration.toLong() } catch (e: Exception) { 0L }
            if (WatchMatch.shouldAutoMark(true, false, ended, pos, dur)) {
                markFired = true
                PlayQueue.completionHandler?.invoke(this, cur)
            }
        } catch (e: Exception) {
            // Auto-mark is bookkeeping; never disturb playback.
        }
    }

    private fun clearResume() {
        val cur = item ?: return
        if (!cur.seekable) return
        try {
            prefs.setIptvResumeList(IptvLogic.withoutResume(prefs.iptvResumeList(), cur.resumeKey))
        } catch (e: Exception) {
            // Bookkeeping only.
        }
    }

    // ------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        showOverlayBriefly()
        val cur = item
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (menuPanel != null || errorPanel != null) return false // panels handle their own focus
                if (event.isLongPress) {
                    showMenu()
                    return true
                }
                if (event.repeatCount == 0) {
                    if (video.isPlaying) video.pause() else video.start()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (menuPanel == null && errorPanel == null) showMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (cur != null && cur.seekable && prepared) {
                    video.seekTo(((video.currentPosition - 30_000).coerceAtLeast(0)))
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cur != null && cur.seekable && prepared) {
                    val dur = video.duration
                    val target = video.currentPosition + 30_000
                    video.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cur != null && cur.kind == IptvLogic.KIND_LIVE && PlayQueue.items.size > 1) {
                    PlayQueue.move(-1)?.let { startItem(it) }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cur != null && cur.kind == IptvLogic.KIND_LIVE && PlayQueue.items.size > 1) {
                    PlayQueue.move(1)?.let { startItem(it) }
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (menuPanel != null || errorPanel != null) {
                    hidePanels()
                    video.requestFocus()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ PiP

    /**
     * Home pressed during playback: enter picture-in-picture
     * instead of just leaving (scope addition 4). Fail-open: any
     * refusal (device, OEM, policy) falls back to normal leave
     * behaviour, and playback simply continues/pauses as usual.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            if (::video.isInitialized && video.isPlaying) {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        } catch (e: Exception) {
            // PiP refused — nothing to do; leaving works as before.
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            overlay.visibility = View.GONE
            hidePanels()
        }
    }

    // ------------------------------------------------------------ menu + error panels

    private fun showOverlayBriefly() {
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 3_500L)
    }

    private fun panelContainer(): LinearLayout {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xB3000000.toInt())
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(Ui.dp(this, 28), Ui.dp(this, 22), Ui.dp(this, 28), Ui.dp(this, 22))
        card.background = Ui.tileBackground(Design.NETFLIX_RED, false, 16, this)
        val lp = FrameLayout.LayoutParams(Ui.dp(this, 460), -2)
        lp.gravity = Gravity.CENTER
        root.addView(card, lp)
        (window.decorView as? android.view.ViewGroup)?.addView(
            root, android.view.ViewGroup.LayoutParams(-1, -1)
        )
        // Track the card's parent so hidePanels can remove it.
        card.tag = root
        return card
    }

    private fun panelRow(card: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val row = TextView(this)
        row.text = label
        row.setTextColor(0xFFFFFFFF.toInt())
        row.textSize = 18f
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        row.background = Ui.tileBackground(Design.NETFLIX_RED, false, 10, this)
        row.setOnFocusChangeListener { v, hasFocus ->
            v.background = Ui.tileBackground(Design.NETFLIX_RED, hasFocus, 10, this)
        }
        row.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        card.addView(row, lp)
        return row
    }

    private fun hidePanels() {
        for (panel in listOf(menuPanel, errorPanel)) {
            val root = panel?.tag as? View
            (root?.parent as? android.view.ViewGroup)?.removeView(root)
        }
        menuPanel = null
        errorPanel = null
    }

    /** The catch-up shift item for the current channel, or null when it doesn't apply. */
    private fun catchUpItem(): PlayItem? {
        val cur = item ?: return null
        if (cur.kind != IptvLogic.KIND_LIVE || cur.source != IptvLogic.SRC_XTREAM) return null
        if (cur.tvArchive <= 0) return null
        val channelId = cur.epgId.ifEmpty { cur.id }
        val programmes = IptvClient.epgIndex(this)[channelId] ?: return null
        val (now, _) = IptvEpg.nowNext(programmes, System.currentTimeMillis())
        if (now == null) return null
        val durationMin = ((now.stopMs - now.startMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return cur.copy(
            kind = IptvClient.KIND_SHIFT,
            title = cur.title + " — from the start",
            meta = now.title,
            shiftStartMs = now.startMs,
            shiftDurationMin = durationMin
        )
    }

    private fun showMenu() {
        hidePanels()
        val card = panelContainer()
        menuPanel = card
        val heading = TextView(this)
        heading.text = item?.title ?: "Options"
        heading.setTextColor(0xFFFFFFFF.toInt())
        heading.textSize = 19f
        heading.setTypeface(heading.typeface, Typeface.BOLD)
        heading.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8))
        card.addView(heading)
        var first: View? = null
        val shift = catchUpItem()
        if (shift != null) {
            first = panelRow(card, "⏮ Watch from start") {
                hidePanels()
                startItem(shift)
            }
        }
        val external = panelRow(card, "↗ Open in external player") { openExternal() }
        if (first == null) first = external
        panelRow(card, "✕ Close") {
            hidePanels()
            video.requestFocus()
        }
        first.requestFocus()
    }

    private fun showError() {
        hidePanels()
        val card = panelContainer()
        errorPanel = card
        val heading = TextView(this)
        heading.text = "Couldn't play this stream"
        heading.setTextColor(0xFFFFFFFF.toInt())
        heading.textSize = 19f
        heading.setTypeface(heading.typeface, Typeface.BOLD)
        heading.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 4))
        card.addView(heading)
        val note = TextView(this)
        note.text = "The TV's built-in player handles HLS (.m3u8) streams. " +
            "A server that only serves raw .ts needs an external player."
        note.setTextColor(0xFFB3B3B3.toInt())
        note.textSize = 14f
        note.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8))
        card.addView(note)
        val external = panelRow(card, "↗ Open in external player") { openExternal() }
        panelRow(card, "✕ Close") {
            hidePanels()
            finish()
        }
        external.requestFocus()
        Toast.makeText(this, "Couldn't play this stream", Toast.LENGTH_SHORT).show()
    }

    private fun openExternal() {
        val url = currentUrl
        hidePanels()
        if (url == null) {
            finish()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
            startActivity(intent)
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No external player found on this TV", Toast.LENGTH_LONG).show()
            video.requestFocus()
        } catch (e: Exception) {
            Prefs.noteError(this, "iptvExternal", RuntimeException(e.javaClass.simpleName))
            Toast.makeText(this, "Couldn't open an external player", Toast.LENGTH_LONG).show()
        }
    }
}
