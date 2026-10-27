package com.mizan.launcher.domain

/**
 * Now Playing row: other apps' media sessions. Authorized by the
 * passive notification-listener grant; the listener reads NO
 * notification content and both of its callbacks stay empty.
 * System-ui and blank titles filtered; deduped by package, first
 * session wins; buffering reads as playing.
 */
enum class PlaybackState { PLAYING, PAUSED, BUFFERING, STOPPED }

data class NowPlayingItem(
    val packageName: String,
    val title: String,
    val artist: String = "",
    val state: PlaybackState,
    /** Play/pause control only when the session's action mask allows. */
    val canToggle: Boolean = false,
) {
    /** Buffering reads as playing for the row's state display. */
    val readsAsPlaying: Boolean
        get() = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
}

interface NowPlayingRepository {
    fun isListenerGranted(): Boolean
    fun getSessions(): List<NowPlayingItem>
    fun toggle(item: NowPlayingItem)
}
