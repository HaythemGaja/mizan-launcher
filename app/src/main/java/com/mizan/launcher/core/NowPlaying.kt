package com.mizan.launcher.core

/**
 * Now Playing session → card mapping — pure logic, no Android imports
 * (v1.4). The Android side reads MediaSessionManager's active sessions
 * into [RawSession]s (numbers mirror android.media.session.PlaybackState
 * constants, duplicated here so this file stays Android-free and
 * sim-testable) and this mapping decides what becomes a card:
 * - sessions without a title are not cards (nothing to show),
 * - System UI's own session is not a card,
 * - one card per package (the first session for a package wins),
 * - the card exposes only the controls the session actually supports
 *   (its actions mask): play/pause toggle, next, previous.
 */
object NowPlayingLogic {

    // PlaybackState state values (android.media.session.PlaybackState).
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6

    // PlaybackState action bits (android.media.session.PlaybackState).
    const val ACTION_PAUSE = 2L
    const val ACTION_PLAY = 4L
    const val ACTION_SKIP_TO_PREVIOUS = 16L
    const val ACTION_SKIP_TO_NEXT = 32L
    const val ACTION_PLAY_PAUSE = 512L

    /** One active media session, reduced to plain data. */
    data class RawSession(
        val pkg: String,
        val title: String?,
        val artist: String?,
        /** PlaybackState state int. */
        val state: Int,
        /** PlaybackState actions bit mask. */
        val actions: Long
    )

    /** One Now Playing card. */
    data class Card(
        val pkg: String,
        val title: String,
        val artist: String,
        val playing: Boolean,
        val canToggle: Boolean,
        val canNext: Boolean,
        val canPrev: Boolean
    )

    fun cards(sessions: List<RawSession>): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (s in sessions) {
            val title = s.title?.trim().orEmpty()
            if (title.isEmpty()) continue
            if (s.pkg == "com.android.systemui") continue
            if (!seen.add(s.pkg)) continue
            val playing = s.state == STATE_PLAYING || s.state == STATE_BUFFERING
            val canToggle = (s.actions and ACTION_PLAY_PAUSE) != 0L ||
                (s.actions and ACTION_PLAY) != 0L ||
                (s.actions and ACTION_PAUSE) != 0L
            out.add(
                Card(
                    pkg = s.pkg,
                    title = title,
                    artist = s.artist?.trim().orEmpty(),
                    playing = playing,
                    canToggle = canToggle,
                    canNext = (s.actions and ACTION_SKIP_TO_NEXT) != 0L,
                    canPrev = (s.actions and ACTION_SKIP_TO_PREVIOUS) != 0L
                )
            )
        }
        return out
    }
}
