package com.mizan.launcher.core

/**
 * v2.0: Live TV state mapping (pure). The Android side (data/LiveTv)
 * gathers the raw facts — does the device expose live TV at all, did
 * the TvContract channel query throw SecurityException (the usual
 * outcome: READ_TV_LISTINGS is signature/system-reserved on many TVs),
 * did it fail some other way, how many channels came back — and this
 * file classifies them into the one state the UI and diagnostics use.
 *
 * The shipped behaviour when the platform denies everything is: row
 * hidden, diagnostics explains. There is no crash path.
 */
object LiveTvLogic {

    enum class State { OK, BLOCKED, NO_CHANNELS, NOT_SUPPORTED }

    data class Channel(val id: Long, val name: String, val inputId: String)

    fun classify(
        hasLiveTv: Boolean,
        securityBlocked: Boolean,
        queryFailed: Boolean,
        channelCount: Int
    ): State = when {
        !hasLiveTv -> State.NOT_SUPPORTED
        securityBlocked -> State.BLOCKED
        queryFailed -> State.NOT_SUPPORTED
        channelCount > 0 -> State.OK
        else -> State.NO_CHANNELS
    }

    /** The `live tv:` diagnostics line's value. */
    fun diagText(state: State, channelCount: Int): String = when (state) {
        State.OK -> "$channelCount channels"
        State.BLOCKED -> "blocked (permission)"
        State.NO_CHANNELS -> "no tuner channels"
        State.NOT_SUPPORTED -> "not supported"
    }
}
