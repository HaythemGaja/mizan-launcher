package com.mizan.launcher.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mizan.launcher.core.PlayerLogic
import com.mizan.launcher.domain.PlayItem
import com.mizan.launcher.domain.PlayKind

/**
 * Player UI (MAP §1.9). Plays Live / VOD / Series episodes from a PlayItem
 * (kind + ids + URL + resume + queue neighbours); PiP declared — Home
 * during playback tries PiP, devices that refuse fall back normally.
 * Control bar: title line composed once, clock m:ss / h:mm:ss (negatives
 * clamp), elapsed/total or "Live", progress permille (unknowns 0).
 * Transport: play/pause, ±seek, Prev/Next (queue neighbours only).
 * Aspect Fit → Stretch → Zoom → Fit; speed ladder cycles and wraps
 * (2x → 0.5x, off-ladder snaps to nearest rung, labels trim trailing zeros).
 * Audio cycle (audio only, >1 track) and subtitle cycle (Off → tracks → Off,
 * ≥1 text track). Resume saved on pause/destroy, restored on reopen,
 * hidden below threshold, cleared when finished (near end / ≥95%).
 * Watch auto-mark: once on ended (even at low position) or at exactly 90%
 * (not 89%); never twice, never tokenless, never on a partial watch.
 * No foreground service anywhere. Error screen on failure, never a silent
 * black frame. MediaPlayer is used here because the manual build has no
 * media3 dependency; the control-bar / auto-mark / resume contracts above
 * are the same regardless of the underlying player.
 */
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"; const val EXTRA_URL = "url"; const val EXTRA_KIND = "kind"
        const val EXTRA_MARK = "mark_token"; const val EXTRA_TMDB = "tmdb_id"
        const val EXTRA_SEASON = "season"; const val EXTRA_EPISODE = "episode"
    }
    private var player: MediaPlayer? = null
    private var item: PlayItem? = null
    private var autoMarked = false
    private var aspect = "fit"
    private var speed = 1f
    private var durationMs = 0L
    // Phase 2D track cycling state (core PlayerLogic rules over the
    // MediaPlayer track list; buttons appear only when a choice exists).
    private var trackDescs: List<PlayerLogic.TrackDesc> = emptyList()
    private var audioPos = -1 // position in the audio-track list, -1 = player default
    private var subPos = -1 // position in the text-track list, -1 = Off
    private lateinit var timeLabel: TextView
    private lateinit var seek: SeekBar
    private var positionMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val kind = try { PlayKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "LIVE") } catch (e: Throwable) { PlayKind.LIVE }
        item = PlayItem(kind, intent.getStringExtra(EXTRA_ID) ?: "", title, null, url,
            libraryMarkToken = intent.getStringExtra(EXTRA_MARK)?.ifBlank { null },
            tmdbId = if (intent.hasExtra(EXTRA_TMDB)) intent.getLongExtra(EXTRA_TMDB, 0L) else null,
            season = if (intent.hasExtra(EXTRA_SEASON)) intent.getIntExtra(EXTRA_SEASON, 0) else null,
            episode = if (intent.hasExtra(EXTRA_EPISODE)) intent.getIntExtra(EXTRA_EPISODE, 0) else null)
        aspect = UiWiring.playback?.getAspect() ?: "fit"
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val surface = SurfaceView(this); surface.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 400))
        col.addView(surface)
        timeLabel = Ui.text(this, PlayerLogic.barTitle(title, ""), 20f, 0xFFFFFFFF.toInt(), true)
        col.addView(timeLabel)
        seek = SeekBar(this); seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) player?.seekTo((durationMs * p / 1000).toInt())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(seek)
        val controls = LinearLayout(this); controls.orientation = LinearLayout.HORIZONTAL
        controls.addView(Ui.button(this, "⏮ Prev") { prevNext(-1) })
        controls.addView(Ui.button(this, "⏯ Play/Pause") { toggle() })
        controls.addView(Ui.button(this, "⏭ Next") { prevNext(1) })
        controls.addView(Ui.button(this, "−30s") { seekBy(-30_000) })
        controls.addView(Ui.button(this, "+30s") { seekBy(30_000) })
        controls.addView(Ui.button(this, "Aspect: ${PlayerLogic.aspectLabel(aspect)}") {
            aspect = PlayerLogic.nextAspect(aspect); UiWiring.playback?.setAspect(aspect); recreate()
        })
        controls.addView(Ui.button(this, "Speed: ${PlayerLogic.speedLabel(speed)}") {
            speed = PlayerLogic.nextSpeed(speed)
            if (Build.VERSION.SDK_INT >= 23) { try { val pp = player?.playbackParams; if (pp != null) player?.playbackParams = pp.setSpeed(speed) } catch (e: Throwable) {} }
            recreate()
        })
        controls.addView(Ui.button(this, "Audio") { cycleAudio() })
        controls.addView(Ui.button(this, "Subtitles") { cycleSubtitles() })
        controls.addView(Ui.button(this, "Play in…") { PlayIn.show(this, url) })
        col.addView(controls)
        setContentView(Ui.rootScrollView(this, col))
        if (url.isBlank()) { showError("No stream URL"); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(this@PlayerActivity, Uri.parse(url))
                setDisplay(surface.holder)
                setOnPreparedListener {
                    // Capture the MediaPlayer track list for the cycle buttons.
                    try {
                        // TrackInfo carries no index getter; the array
                        // position IS the selectTrack index.
                        trackDescs = trackInfo?.mapIndexed { idx, ti ->
                            PlayerLogic.TrackDesc(idx, ti.trackType,
                                try { ti.language ?: "" } catch (e: Throwable) { "" })
                        } ?: emptyList()
                    } catch (e: Throwable) { trackDescs = emptyList() }
                    durationMs = duration.toLong()
                    val resume = item?.resumeMs
                    // Resume hidden below threshold (10s); restored on reopen.
                    if (resume != null && resume > 10_000) seekTo(resume.toInt())
                    start()
                    if (Build.VERSION.SDK_INT >= 23) { try { val pp = playbackParams; if (pp != null) playbackParams = pp.setSpeed(speed) } catch (e: Throwable) {} }
                    tick()
                }
                setOnCompletionListener { maybeAutoMark(ended = true); }
                setOnErrorListener { _, _, _ -> showError("Playback failed"); true }
                prepareAsync()
            }
        } catch (e: Throwable) { showError("Playback failed") }
    }
    private fun tick() {
        val p = player ?: return
        try {
            positionMs = p.currentPosition.toLong()
            durationMs = if (p.duration > 0) p.duration.toLong() else durationMs
            val label = if (durationMs > 0) PlayerLogic.timeLabel(positionMs, durationMs) else "Live"
            timeLabel.text = PlayerLogic.barTitle(item?.title ?: "", label)
            seek.progress = PlayerLogic.progressPermille(positionMs, durationMs)
            if (durationMs > 0) {
                val pct = positionMs * 100 / durationMs
                if (pct == 90L) maybeAutoMark(ended = false)
            }
            // Poll while playing (Handler-free simple loop via postDelayed on the view).
            timeLabel.postDelayed({ if (!isFinishing) tick() }, 1000)
        } catch (e: Throwable) { }
    }
    private fun maybeAutoMark(ended: Boolean) {
        if (autoMarked) return
        val it = item ?: return
        if (it.libraryMarkToken.isNullOrBlank()) return // never tokenless
        val playback = UiWiring.playback ?: return
        // Exactly 90% or ended (even at low position); never on a partial watch.
        val at90 = durationMs > 0 && positionMs * 100 / durationMs == 90L
        if (!ended && !at90) return
        autoMarked = true
        Ui.runBg { playback.maybeAutoMark(it, positionMs, durationMs, ended) }
    }
    private fun toggle() { val p = player ?: return; if (p.isPlaying) p.pause() else p.start() }
    private fun seekBy(deltaMs: Int) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, if (p.duration > 0) p.duration else Int.MAX_VALUE)
        p.seekTo(target)
    }
    // Phase 2D track cycles (core PlayerLogic rules): audio cycles
    // only over audio tracks (button needs >1); subtitles cycle
    // Off → tracks → Off (button needs ≥1 text track). The
    // MediaPlayer backend supplies the TrackDesc list; without it
    // both buttons report honestly instead of cycling nothing.
    private fun cycleAudio() {
        val p = player ?: return
        val audios = PlayerLogic.audioTracks(trackDescs)
        if (!PlayerLogic.showAudioButton(trackDescs)) { Ui.toast(this, "No alternate audio track"); return }
        audioPos = PlayerLogic.cycleIndex(audioPos, audios.size)
        try { p.selectTrack(audios[audioPos].index) } catch (e: Throwable) { }
        Ui.toast(this, "Audio: ${PlayerLogic.trackLabel(audios[audioPos], audioPos)}")
    }
    private fun cycleSubtitles() {
        val p = player ?: return
        val texts = PlayerLogic.textTracks(trackDescs)
        if (!PlayerLogic.showSubsButton(trackDescs)) { Ui.toast(this, "No subtitle track"); return }
        val prev = subPos
        subPos = PlayerLogic.cycleSubtitle(subPos, texts.size)
        try {
            if (prev >= 0) p.deselectTrack(texts[prev].index)
            if (subPos >= 0) p.selectTrack(texts[subPos].index)
        } catch (e: Throwable) { }
        Ui.toast(this, if (subPos < 0) "Subtitles: Off" else "Subtitles: ${PlayerLogic.trackLabel(texts[subPos], subPos)}")
    }
    private fun prevNext(dir: Int) {
        // Queue neighbours only, via the PlaybackRepository queue
        // contract; the data implementation returns null (a PlayItem
        // carries no series id), so this honestly reports no neighbour.
        val it = item
        val queue = if (it != null) try { UiWiring.playback?.queueFor(it) } catch (e: Throwable) { null } else null
        val neighbour = when (dir) {
            -1 -> queue?.previous()
            else -> queue?.next()
        }
        if (neighbour != null) Ui.openPlayer(this, neighbour)
        else Ui.toast(this, "No queue neighbour")
    }
    private fun showError(msg: String) {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, msg, 22f, 0xFFFFFFFF.toInt(), true))
        col.addView(Ui.button(this, "Back") { finish() })
        setContentView(col)
    }
    override fun onPause() {
        super.onPause()
        try {
            positionMs = player?.currentPosition?.toLong() ?: positionMs
            if (positionMs > 10_000) {
                val finished = durationMs > 0 && positionMs * 100 / durationMs >= 95
                if (finished) UiWiring.playback?.clearResume(item?.id ?: "") else UiWiring.playback?.saveResume(item?.id ?: "", positionMs)
            }
        } catch (e: Throwable) {}
        player?.pause()
    }
    override fun onDestroy() {
        try { player?.release() } catch (e: Throwable) {}
        player = null; super.onDestroy()
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // PiP declared: Home during playback tries PiP; devices that refuse fall back normally.
        if (Build.VERSION.SDK_INT >= 26) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (e: Throwable) {}
        }
    }
}
