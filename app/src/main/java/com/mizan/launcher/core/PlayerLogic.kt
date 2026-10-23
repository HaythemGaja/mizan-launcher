package com.mizan.launcher.core

/**
 * Player control-bar logic (v2.6, spec §26) — the pure half of
 * ui/PlayerActivity's D-pad control bar: aspect modes + the video
 * measure maths, the speed ladder, MediaPlayer track filtering /
 * cycling, and the progress/time text. Everything here is
 * Android-free so the sim can pin it; the activity only translates
 * between these values and the platform MediaPlayer/VideoView.
 *
 * Track type constants mirror android.media.MediaPlayer.TrackInfo
 * (VIDEO=1, AUDIO=2, TIMEDTEXT=3, SUBTITLE=4) so the activity can
 * copy getTrackInfo() output across verbatim.
 */
object PlayerLogic {

    // ------------------------------------------------------------ aspect

    const val ASPECT_FIT = "fit"
    const val ASPECT_STRETCH = "stretch"
    const val ASPECT_ZOOM = "zoom"

    /** Cycle order for the Aspect button: Fit → Stretch → Zoom → Fit. */
    val ASPECTS: List<String> = listOf(ASPECT_FIT, ASPECT_STRETCH, ASPECT_ZOOM)

    /** A stored/garbled aspect value always resolves to a real mode (Fit). */
    fun normalizeAspect(raw: String?): String =
        if (raw != null && raw in ASPECTS) raw else ASPECT_FIT

    fun aspectLabel(mode: String?): String = when (normalizeAspect(mode)) {
        ASPECT_STRETCH -> "Stretch"
        ASPECT_ZOOM -> "Zoom"
        else -> "Fit"
    }

    fun nextAspect(current: String?): String {
        val i = ASPECTS.indexOf(normalizeAspect(current))
        return ASPECTS[(i + 1) % ASPECTS.size]
    }

    /**
     * The measured size for a video of [videoW]×[videoH] inside a
     * container of [containerW]×[containerH] under [mode]:
     * Fit = largest aspect-true size inside the container;
     * Stretch = the container exactly (aspect ignored);
     * Zoom = smallest aspect-true size that COVERS the container
     * (overflow is centred + clipped by the parent — crop).
     * Unknown video size (0/negative, pre-prepare) → the container.
     */
    fun measuredSize(
        mode: String?,
        containerW: Int, containerH: Int,
        videoW: Int, videoH: Int
    ): Pair<Int, Int> {
        if (containerW <= 0 || containerH <= 0) return 0 to 0
        if (videoW <= 0 || videoH <= 0) return containerW to containerH
        return when (normalizeAspect(mode)) {
            ASPECT_STRETCH -> containerW to containerH
            ASPECT_ZOOM -> {
                val s = maxOf(
                    containerW.toDouble() / videoW,
                    containerH.toDouble() / videoH
                )
                scaled(videoW, videoH, s)
            }
            else -> {
                val s = minOf(
                    containerW.toDouble() / videoW,
                    containerH.toDouble() / videoH
                )
                scaled(videoW, videoH, s)
            }
        }
    }

    private fun scaled(w: Int, h: Int, s: Double): Pair<Int, Int> =
        maxOf(1, (w * s).toInt()) to maxOf(1, (h * s).toInt())

    // ------------------------------------------------------------ speed

    /** The Speed button's ladder, ascending; cycling wraps to the top. */
    val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** The rung nearest [current] (a garbled/off-ladder value snaps to it). */
    fun speedRung(current: Float): Int {
        var best = 2 // 1.0×
        var bestDiff = Float.MAX_VALUE
        for ((i, s) in SPEEDS.withIndex()) {
            val d = kotlin.math.abs(s - current)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    fun nextSpeed(current: Float): Float =
        SPEEDS[(speedRung(current) + 1) % SPEEDS.size]

    /** "1×", "1.25×", "0.5×" — trailing zeros trimmed. */
    fun speedLabel(speed: Float): String {
        val rung = SPEEDS[speedRung(speed)]
        val text = if (rung == rung.toInt().toFloat()) {
            rung.toInt().toString()
        } else {
            rung.toString()
        }
        return "$text×"
    }

    // ------------------------------------------------------------ tracks

    const val TRACK_TYPE_VIDEO = 1
    const val TRACK_TYPE_AUDIO = 2
    const val TRACK_TYPE_TIMEDTEXT = 3
    const val TRACK_TYPE_SUBTITLE = 4

    /** One MediaPlayer track, flattened (index = the player's own track index). */
    data class TrackDesc(val index: Int, val type: Int, val lang: String)

    fun isTextTrack(t: TrackDesc): Boolean =
        t.type == TRACK_TYPE_TIMEDTEXT || t.type == TRACK_TYPE_SUBTITLE

    /** Audio tracks only, in player-index order. */
    fun audioTracks(all: List<TrackDesc>): List<TrackDesc> =
        all.filter { it.type == TRACK_TYPE_AUDIO }.sortedBy { it.index }

    /** Subtitle/text tracks only (timed-text + subtitle), in player-index order. */
    fun textTracks(all: List<TrackDesc>): List<TrackDesc> =
        all.filter { isTextTrack(it) }.sortedBy { it.index }

    /** The Audio button shows only when a real choice exists (>1 audio track). */
    fun showAudioButton(all: List<TrackDesc>): Boolean = audioTracks(all).size > 1

    /** The Subtitles button shows when at least one text track exists. */
    fun showSubsButton(all: List<TrackDesc>): Boolean = textTracks(all).isNotEmpty()

    /**
     * A track's display name: its language when the player reports
     * one, else a 1-based ordinal within its own kind ("Track 2").
     */
    fun trackLabel(track: TrackDesc, ordinalInKind: Int): String =
        track.lang.trim().ifBlank { "Track ${ordinalInKind + 1}" }

    /** Wrap-around cycle over [count] entries; -1 (nothing) when there are none. */
    fun cycleIndex(current: Int, count: Int): Int {
        if (count <= 0) return -1
        val cur = if (current in 0 until count) current else -1
        return (cur + 1) % count
    }

    /**
     * Subtitle cycle over positions in the text-track list, where
     * -1 = Off: Off → first track → … → last track → Off.
     */
    fun cycleSubtitle(currentPos: Int, count: Int): Int {
        if (count <= 0) return -1
        val next = currentPos + 1
        return if (next >= count) -1 else next
    }

    // ------------------------------------------------------------ bar text + progress

    /**
     * The bar's title line: the item title, plus " · <meta>" when
     * the meta adds information (a Watch episode play's title is
     * already "Show · S1 E2" and its meta is the episode name, so
     * the composed line reads "Show · S1 E2 · Episode name").
     */
    fun barTitle(title: String, meta: String): String {
        val t = title.trim()
        val m = meta.trim()
        if (m.isEmpty() || t.contains(m)) return t
        if (t.isEmpty()) return m
        return "$t · $m"
    }

    /** "12:34" under an hour, "1:02:34" at/above it; negatives read 0:00. */
    fun formatClock(ms: Long): String {
        val totalSecs = (if (ms < 0L) 0L else ms) / 1000L
        val h = totalSecs / 3600L
        val m = (totalSecs % 3600L) / 60L
        val s = totalSecs % 60L
        return if (h > 0L) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }

    /** "12:34 / 45:00" for seekable streams; "Live" when duration is unknown. */
    fun timeLabel(posMs: Long, durMs: Long): String =
        if (durMs > 0L) "${formatClock(posMs)} / ${formatClock(durMs)}" else "Live"

    /** Progress in permille (0..1000) for a ProgressBar; unknown duration → 0. */
    fun progressPermille(posMs: Long, durMs: Long): Int {
        if (durMs <= 0L || posMs <= 0L) return 0
        return (posMs * 1000L / durMs).toInt().coerceIn(0, 1000)
    }

    /**
     * Prev/Next EPISODE buttons: only for episode plays whose
     * PlayQueue actually has neighbours (a lone episode play and
     * every live/VOD play hide them — live keeps ↑/↓ zap instead).
     */
    fun hasEpisodeNeighbours(kind: String, queueSize: Int): Boolean =
        kind == IptvLogic.KIND_EPISODE && queueSize > 1
}
