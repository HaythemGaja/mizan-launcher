package com.mizan.launcher.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.PlayerLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.PlayItem
import com.mizan.launcher.data.PlayQueue
import com.mizan.launcher.data.Prefs
import kotlin.concurrent.thread

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
 *
 * v2.6 control bar (spec §26): OK or any D-pad press during
 * playback ALSO summons a bottom control bar (the press keeps its
 * pre-v2.6 effect — OK still toggles pause, ←/→ still seek,
 * ↑/↓ still zap): title + progress + elapsed/total over focusable
 * buttons — Play/Pause, −10s/+30s, Prev/Next episode (episode
 * queues only), Aspect (Fit/Stretch/Zoom, persisted per profile),
 * Speed (PlaybackParams ladder), Audio / Subtitles track cyclers
 * (only when the stream really has a choice), PiP, External. The
 * bar auto-hides after ~5 s without input; while it is up, keys
 * belong to it. Every MediaPlayer call is guarded — a refused
 * option shows a note and playback never stops for it. Pure
 * decisions live in core/PlayerLogic; track enumeration runs on
 * a worker thread at prepare time.
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
    private lateinit var video: AspectVideoView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var hintView: TextView
    private lateinit var overlay: LinearLayout
    private var menuPanel: LinearLayout? = null
    private var errorPanel: LinearLayout? = null

    // v2.6 control bar (core/PlayerLogic owns the pure decisions).
    private lateinit var bar: LinearLayout
    private lateinit var barTitleView: TextView
    private lateinit var barTimeView: TextView
    private lateinit var barProgress: ProgressBar
    private lateinit var barButtons: LinearLayout
    private var barVisible = false
    private var btnPlayPause: TextView? = null
    private var btnAspect: TextView? = null
    private var btnSpeed: TextView? = null
    private var btnAudio: TextView? = null
    private var btnSubs: TextView? = null

    /** The MediaPlayer behind the VideoView (captured in onPrepared). */
    private var mp: MediaPlayer? = null
    /** Enumerated tracks for the current item; null = not enumerated yet. */
    private var trackCache: List<PlayerLogic.TrackDesc>? = null
    /** Chosen audio track index (player numbering); -1 = platform default. */
    private var audioSel: Int = -1
    /** The platform-selected audio track at enumeration time (label truth). */
    private var audioCurrent: Int = -1
    /** Chosen subtitle track index (player numbering); -1 = subtitles off. */
    private var subSel: Int = -1
    /** Session playback speed (the ladder lives in core/PlayerLogic). */
    private var speed: Float = 1.0f
    /** Aspect mode (core/PlayerLogic); persisted per profile via Prefs. */
    private var aspectMode: String = PlayerLogic.ASPECT_FIT

    private var item: PlayItem? = null
    private var currentUrl: String? = null
    private var pendingSeekMs: Long = 0L
    private var prepared = false
    /** v2.3: the Watch auto-mark fires at most once per played item. */
    private var markFired = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val hideBarRunnable = Runnable { hideBar() }
    private val barTick = object : Runnable {
        override fun run() {
            if (!barVisible) return
            updateProgress()
            btnPlayPause?.text = if (video.isPlaying) "⏸ Pause" else "▶ Play"
            handler.postDelayed(this, 500L)
        }
    }
    private val saveTick = object : Runnable {
        override fun run() {
            maybeAutoMark(ended = false)
            saveResume()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mizan.launcher.data.DiagLog.note("player opened")
        prefs = Prefs(this)
        // v3.0: the whole player build runs behind this guard — a
        // failure (bad launch data, view construction, a hostile
        // stream URL) closes the player back to the launcher with
        // the error noted, instead of killing the process from a
        // secondary screen.
        try {
            buildPlayer()
        } catch (e: Throwable) {
            Prefs.noteError(this, "playerCreate", e)
            finish()
        }
    }

    private fun buildPlayer() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        aspectMode = prefs.playerAspect
        video = AspectVideoView(this)
        video.keepScreenOn = true
        video.aspectMode = aspectMode
        val vlp = FrameLayout.LayoutParams(-1, -1)
        vlp.gravity = Gravity.CENTER
        root.addView(video, vlp)

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

        // v2.6 control bar: title + progress + time over a row of
        // focusable option buttons, docked at the bottom. Hidden
        // until a D-pad/OK press summons it (see onKeyDown).
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(0xD9000000.toInt())
        bar.setPadding(Ui.dp(this, 40), Ui.dp(this, 14), Ui.dp(this, 40), Ui.dp(this, 16))
        barTitleView = TextView(this)
        barTitleView.setTextColor(0xFFFFFFFF.toInt())
        barTitleView.textSize = 17f
        barTitleView.setTypeface(barTitleView.typeface, Typeface.BOLD)
        barTitleView.maxLines = 1
        barTitleView.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(barTitleView)
        val progRow = LinearLayout(this)
        progRow.orientation = LinearLayout.HORIZONTAL
        progRow.gravity = Gravity.CENTER_VERTICAL
        barProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        barProgress.max = 1000
        progRow.addView(barProgress, LinearLayout.LayoutParams(0, -2, 1f))
        barTimeView = TextView(this)
        barTimeView.setTextColor(0xFFB3B3B3.toInt())
        barTimeView.textSize = 14f
        val tmlp = LinearLayout.LayoutParams(-2, -2)
        tmlp.setMargins(Ui.dp(this, 12), 0, 0, 0)
        barTimeView.layoutParams = tmlp
        progRow.addView(barTimeView)
        val prlp = LinearLayout.LayoutParams(-1, -2)
        prlp.setMargins(0, Ui.dp(this, 8), 0, 0)
        progRow.layoutParams = prlp
        bar.addView(progRow)
        barButtons = LinearLayout(this)
        barButtons.orientation = LinearLayout.HORIZONTAL
        val btnScroll = HorizontalScrollView(this)
        btnScroll.isHorizontalScrollBarEnabled = false
        btnScroll.addView(barButtons)
        val bslp = LinearLayout.LayoutParams(-1, -2)
        bslp.setMargins(0, Ui.dp(this, 10), 0, 0)
        btnScroll.layoutParams = bslp
        bar.addView(btnScroll)
        bar.visibility = View.GONE
        val barLp = FrameLayout.LayoutParams(-1, -2)
        barLp.gravity = Gravity.BOTTOM
        root.addView(bar, barLp)

        setContentView(root)

        video.setOnPreparedListener { player ->
            prepared = true
            mp = player
            // VideoView hides the video size; the MediaPlayer has
            // it — feed the aspect-measuring view, now and if the
            // stream refines its size after prepare.
            try {
                video.setVideoSize(player.videoWidth, player.videoHeight)
                player.setOnVideoSizeChangedListener { _, w, h ->
                    video.setVideoSize(w, h)
                }
            } catch (e: Exception) {
                // Size is decoration for the aspect modes; Fit is
                // the stock behaviour when it stays unknown.
            }
            if (pendingSeekMs > 0L) {
                try { video.seekTo(pendingSeekMs.toInt()) } catch (e: Exception) {
                    // A stream that refuses the seek still plays from the start.
                }
                pendingSeekMs = 0L
            }
            // A speed chosen earlier in the session re-applies to
            // the new stream; a platform that refuses it drops the
            // session back to 1× rather than mislabelling the bar.
            if (speed != 1.0f && !applySpeed(speed)) speed = 1.0f
            enumerateTracks(player)
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
        handler.removeCallbacks(hideBarRunnable)
        handler.removeCallbacks(barTick)
        saveResume()
        super.onDestroy()
    }

    // ------------------------------------------------------------ playback

    private fun startItem(newItem: PlayItem) {
        saveResume() // for the item being replaced (zap), if any
        item = newItem
        markFired = false
        prepared = false
        // v2.6: per-item player state resets — the new stream gets
        // a fresh MediaPlayer, fresh tracks, default audio and
        // subtitles off. Speed + aspect are session/profile state
        // and carry over (speed re-applies in onPrepared).
        mp = null
        trackCache = null
        audioSel = -1
        audioCurrent = -1
        subSel = -1
        video.setVideoSize(0, 0)
        hideBar()
        hidePanels()
        titleView.text = newItem.title
        metaView.text = newItem.meta
        barTitleView.text = PlayerLogic.barTitle(newItem.title, newItem.meta)
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
        // v2.6: while the control bar is up, keys belong to it —
        // D-pad moves focus between its buttons, OK clicks the
        // focused button (handled by the views themselves), Back
        // dismisses the bar, Menu swaps it for the player menu.
        // Nothing here touches playback directly; returning super
        // lets the framework's focus traversal + button clicks run.
        if (barVisible) {
            pokeBar()
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideBar()
                    true
                }
                KeyEvent.KEYCODE_MENU -> {
                    hideBar()
                    showMenu()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
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
                    // The press both toggles (the pre-v2.6 behaviour)
                    // and summons the bar, focused on Play/Pause.
                    showBar()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (menuPanel == null && errorPanel == null) showMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (menuPanel == null && errorPanel == null) {
                    if (cur != null && cur.seekable && prepared) {
                        video.seekTo(((video.currentPosition - 30_000).coerceAtLeast(0)))
                    }
                    showBar()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (menuPanel == null && errorPanel == null) {
                    if (cur != null && cur.seekable && prepared) {
                        val dur = video.duration
                        val target = video.currentPosition + 30_000
                        video.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                    }
                    showBar()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (menuPanel == null && errorPanel == null) {
                    if (cur != null && cur.kind == IptvLogic.KIND_LIVE && PlayQueue.items.size > 1) {
                        PlayQueue.move(-1)?.let { startItem(it) }
                    }
                    showBar()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (menuPanel == null && errorPanel == null) {
                    if (cur != null && cur.kind == IptvLogic.KIND_LIVE && PlayQueue.items.size > 1) {
                        PlayQueue.move(1)?.let { startItem(it) }
                    }
                    showBar()
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
        enterPip()
    }

    /** Enter picture-in-picture (also the bar's PiP button). Fail-open. */
    private fun enterPip() {
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
            hideBar()
            hidePanels()
        }
    }

    // ------------------------------------------------------------ control bar (v2.6)

    private fun showNote(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // A note is decoration; it never disturbs playback.
        }
    }

    private fun showBar() {
        if (barVisible) {
            pokeBar()
            return
        }
        if (menuPanel != null || errorPanel != null) return
        barVisible = true
        // The bar supersedes the start-of-play overlay.
        overlay.visibility = View.GONE
        handler.removeCallbacks(hideOverlay)
        updateProgress()
        rebuildBarButtons()
        bar.visibility = View.VISIBLE
        (btnPlayPause ?: barButtons.getChildAt(0))?.requestFocus()
        handler.removeCallbacks(barTick)
        handler.postDelayed(barTick, 500L)
        pokeBar()
        // Tracks may still be unknown (bar summoned pre-prepare is
        // impossible — but a first show before enumeration lands
        // re-kicks it lazily; the cache fills the buttons in).
        if (trackCache == null) mp?.let { enumerateTracks(it) }
    }

    private fun hideBar() {
        if (!barVisible) return
        barVisible = false
        bar.visibility = View.GONE
        handler.removeCallbacks(barTick)
        handler.removeCallbacks(hideBarRunnable)
        if (menuPanel == null && errorPanel == null) {
            try { video.requestFocus() } catch (e: Exception) {
                // Focus is best-effort; keys keep working via the activity.
            }
        }
    }

    /** Restart the bar's ~5 s auto-hide countdown (any bar key/click). */
    private fun pokeBar() {
        handler.removeCallbacks(hideBarRunnable)
        handler.postDelayed(hideBarRunnable, 5_000L)
    }

    private fun updateProgress() {
        try {
            val pos = video.currentPosition.toLong()
            val dur = video.duration.toLong()
            barProgress.progress = PlayerLogic.progressPermille(pos, dur)
            barTimeView.text = PlayerLogic.timeLabel(pos, dur)
        } catch (e: Exception) {
            // Position reads are best-effort decoration.
        }
    }

    private fun barButton(label: String, tag: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.tag = tag
        b.setTextColor(0xFFFFFFFF.toInt())
        b.textSize = 15f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.isFocusable = true
        b.isClickable = true
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9))
        b.background = Ui.tileBackground(Design.NETFLIX_RED, false, 10, this)
        b.setOnFocusChangeListener { v, hasFocus ->
            v.background = Ui.tileBackground(Design.NETFLIX_RED, hasFocus, 10, this)
            if (hasFocus) pokeBar()
        }
        b.setOnClickListener {
            pokeBar()
            onClick()
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 8), 0)
        b.layoutParams = lp
        barButtons.addView(b)
        return b
    }

    private fun focusedBarTag(): String? = currentFocus?.tag as? String

    private fun findBarButton(tag: String): TextView? {
        for (i in 0 until barButtons.childCount) {
            val child = barButtons.getChildAt(i)
            if (child.tag == tag) return child as? TextView
        }
        return null
    }

    /**
     * (Re)build the bar's button row for the current item + track
     * cache. Conditional buttons follow core/PlayerLogic: seek
     * buttons only for seekable kinds, Prev/Next episode only with
     * queue neighbours, Audio only with >1 audio track, Subtitles
     * only with ≥1 text track. [focusTag] restores focus after a
     * rebuild (the track enumeration landing mid-show).
     */
    private fun rebuildBarButtons(focusTag: String? = null) {
        barButtons.removeAllViews()
        btnPlayPause = null
        btnAspect = null
        btnSpeed = null
        btnAudio = null
        btnSubs = null
        val cur = item ?: return
        btnPlayPause = barButton(
            if (video.isPlaying) "⏸ Pause" else "▶ Play", "playpause"
        ) { togglePlayPause() }
        if (cur.seekable) {
            barButton("⏪ −10s", "back10") { seekBy(-10_000L) }
            barButton("⏩ +30s", "fwd30") { seekBy(30_000L) }
        }
        if (PlayerLogic.hasEpisodeNeighbours(cur.kind, PlayQueue.items.size)) {
            barButton("⏮ Prev episode", "prev_ep") { stepEpisode(-1) }
            barButton("⏭ Next episode", "next_ep") { stepEpisode(1) }
        }
        btnAspect = barButton("Aspect: ${PlayerLogic.aspectLabel(aspectMode)}", "aspect") {
            cycleAspect()
        }
        btnSpeed = barButton("Speed: ${PlayerLogic.speedLabel(speed)}", "speed") {
            cycleSpeed()
        }
        val tracks = trackCache
        if (tracks != null && PlayerLogic.showAudioButton(tracks)) {
            btnAudio = barButton("Audio: ${audioLabel()}", "audio") { cycleAudio() }
        }
        if (tracks != null && PlayerLogic.showSubsButton(tracks)) {
            btnSubs = barButton("Subs: ${subsLabel()}", "subs") { cycleSubs() }
        }
        barButton("🖼 PiP", "pip") {
            hideBar()
            enterPip()
        }
        barButton("↗ External", "external") { openExternal() }
        if (focusTag != null) findBarButton(focusTag)?.requestFocus()
    }

    // ------------------------------------------------------------ bar actions

    private fun togglePlayPause() {
        try {
            if (video.isPlaying) video.pause() else video.start()
        } catch (e: Exception) {
            // A toggle against a dying stream must not kill the player.
        }
        btnPlayPause?.text = if (video.isPlaying) "⏸ Pause" else "▶ Play"
        updateProgress()
    }

    private fun seekBy(deltaMs: Long) {
        val cur = item ?: return
        if (!cur.seekable || !prepared) return
        try {
            val dur = video.duration.toLong()
            var target = video.currentPosition.toLong() + deltaMs
            if (target < 0L) target = 0L
            if (dur > 0L && target > dur) target = dur
            video.seekTo(target.toInt())
        } catch (e: Exception) {
            showNote("Seek isn't available on this stream")
        }
        updateProgress()
    }

    private fun stepEpisode(delta: Int) {
        hideBar()
        PlayQueue.move(delta)?.let { startItem(it) }
    }

    private fun cycleAspect() {
        aspectMode = PlayerLogic.nextAspect(aspectMode)
        try { prefs.playerAspect = aspectMode } catch (e: Exception) {
            // Persistence is best-effort; the mode still applies now.
        }
        video.aspectMode = aspectMode
        try { video.requestLayout() } catch (e: Exception) {
            // Layout will pick the mode up on the next measure anyway.
        }
        btnAspect?.text = "Aspect: ${PlayerLogic.aspectLabel(aspectMode)}"
    }

    private fun cycleSpeed() {
        val want = PlayerLogic.nextSpeed(speed)
        if (applySpeed(want)) {
            speed = want
        } else {
            // The platform rejected variable speed on this stream:
            // fall back to a truthful 1× instead of a lying label.
            speed = 1.0f
            applySpeed(1.0f)
            showNote("Speed not supported on this stream")
        }
        btnSpeed?.text = "Speed: ${PlayerLogic.speedLabel(speed)}"
    }

    /** Apply [value] through MediaPlayer PlaybackParams; false = refused. */
    private fun applySpeed(value: Float): Boolean {
        val player = mp ?: return false
        return try {
            val params = try {
                player.playbackParams
            } catch (e: Exception) {
                null
            } ?: PlaybackParams()
            player.playbackParams = params.setSpeed(value)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ tracks

    /**
     * Enumerate the prepared player's tracks on a worker thread
     * (getTrackInfo must never block the main thread), cache the
     * flattened list, and — if the bar is up — rebuild its buttons
     * with the Audio/Subtitles choices that now exist. The result
     * is dropped when a newer item has taken the player over.
     */
    private fun enumerateTracks(player: MediaPlayer) {
        thread(name = "mizan-player-tracks") {
            var descs: List<PlayerLogic.TrackDesc>? = null
            var selectedAudio = -1
            try {
                val infos = player.trackInfo
                if (infos != null) {
                    // TrackInfo carries no index of its own: the
                    // ARRAY position is the track index selectTrack
                    // expects, so it is copied across as the index.
                    descs = infos.mapIndexed { idx, ti ->
                        PlayerLogic.TrackDesc(
                            index = idx,
                            type = ti.trackType,
                            lang = try {
                                ti.language ?: ""
                            } catch (e: Exception) {
                                ""
                            }
                        )
                    }
                }
                try {
                    selectedAudio = player.getSelectedTrack(
                        MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
                    )
                } catch (e: Exception) {
                    // Label truth only; the cycle starts from the list head.
                }
            } catch (e: Exception) {
                descs = null
            }
            val result = descs
            val sel = selectedAudio
            handler.post {
                if (mp !== player) return@post // a newer item owns the player now
                trackCache = result
                if (sel >= 0 && audioSel < 0) audioCurrent = sel
                if (barVisible) rebuildBarButtons(focusedBarTag())
            }
        }
    }

    /** The effective audio track index (user choice, else platform default). */
    private fun currentAudioIndex(): Int = if (audioSel >= 0) audioSel else audioCurrent

    private fun audioLabel(): String {
        val tracks = trackCache ?: return "—"
        val audios = PlayerLogic.audioTracks(tracks)
        val pos = audios.indexOfFirst { it.index == currentAudioIndex() }
        if (pos < 0) return "Default"
        return PlayerLogic.trackLabel(audios[pos], pos)
    }

    private fun cycleAudio() {
        val player = mp ?: return
        val tracks = trackCache ?: return
        val audios = PlayerLogic.audioTracks(tracks)
        if (audios.size <= 1) return
        val curPos = audios.indexOfFirst { it.index == currentAudioIndex() }
        val nextPos = PlayerLogic.cycleIndex(curPos, audios.size)
        if (nextPos < 0) return
        val target = audios[nextPos]
        try {
            player.selectTrack(target.index)
            audioSel = target.index
            btnAudio?.text = "Audio: ${PlayerLogic.trackLabel(target, nextPos)}"
        } catch (e: Exception) {
            showNote("Audio track not available on this stream")
        }
    }

    private fun subsLabel(): String {
        if (subSel < 0) return "Off"
        val tracks = trackCache ?: return "Off"
        val texts = PlayerLogic.textTracks(tracks)
        val pos = texts.indexOfFirst { it.index == subSel }
        if (pos < 0) return "Off"
        return PlayerLogic.trackLabel(texts[pos], pos)
    }

    private fun cycleSubs() {
        val player = mp ?: return
        val tracks = trackCache ?: return
        val texts = PlayerLogic.textTracks(tracks)
        if (texts.isEmpty()) return
        val curPos = texts.indexOfFirst { it.index == subSel }
        val nextPos = PlayerLogic.cycleSubtitle(curPos, texts.size)
        try {
            if (nextPos < 0) {
                if (subSel >= 0) player.deselectTrack(subSel)
                subSel = -1
            } else {
                val target = texts[nextPos]
                if (subSel >= 0 && subSel != target.index) {
                    try { player.deselectTrack(subSel) } catch (e: Exception) {
                        // Selecting the new track supersedes on most players.
                    }
                }
                player.selectTrack(target.index)
                subSel = target.index
            }
            btnSubs?.text = "Subs: ${subsLabel()}"
        } catch (e: Exception) {
            showNote("Subtitles not available on this stream")
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
        hideBar()
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
        hideBar()
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

/**
 * A VideoView whose measured size follows the v2.6 aspect mode
 * (core/PlayerLogic.measuredSize owns the maths): Fit keeps the
 * stock letterbox, Stretch fills the screen, Zoom covers it and
 * lets the centred overflow crop. Stock VideoView always preserves
 * aspect, so the mode can only live in onMeasure — and VideoView
 * exposes no video-size getter, so the activity feeds the size in
 * from the MediaPlayer (onPrepared / onVideoSizeChanged).
 */
private class AspectVideoView(ctx: android.content.Context) : VideoView(ctx) {

    var aspectMode: String = PlayerLogic.ASPECT_FIT
    private var videoW: Int = 0
    private var videoH: Int = 0

    fun setVideoSize(w: Int, h: Int) {
        if (w == videoW && h == videoH) return
        videoW = w
        videoH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = PlayerLogic.measuredSize(
            aspectMode,
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
            videoW,
            videoH
        )
        setMeasuredDimension(w, h)
    }
}
