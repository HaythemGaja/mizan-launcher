package com.mizan.launcher.core

/**
 * IPTV fetch outcome → the `iptv:` diagnostics line + the
 * empty-state sentence (v2.2, spec §20.2) — pure, no Android
 * imports; mirrors core/TrackerStatus so the two lines read the
 * same way down the Diagnostics screen.
 *
 * The precedence is ordered by what the user can actually DO
 * about the state:
 *
 * - NOT_CONFIGURED: no source set up → the Settings pointer.
 * - OK: any source returned data (data beats an auth failure on
 *   the OTHER source — the working source still shows).
 * - AUTH: the panel answered but rejected the login.
 * - EMPTY: sources answered, catalogues empty.
 * - ERROR: nothing answered at all.
 * - NONE: never classified (no fetch has completed yet).
 *
 * HYGIENE: the line carries counts and state ONLY — never the
 * server, username, password, or the M3U/EPG URLs.
 */
object IptvStatus {

    enum class Kind { NONE, NOT_CONFIGURED, AUTH, EMPTY, ERROR, OK }

    fun classify(
        anyConfigured: Boolean,
        anyAnswered: Boolean,
        authFailed: Boolean,
        hasData: Boolean
    ): Kind = when {
        !anyConfigured -> Kind.NOT_CONFIGURED
        hasData -> Kind.OK
        authFailed -> Kind.AUTH
        anyAnswered -> Kind.EMPTY
        else -> Kind.ERROR
    }

    /**
     * The `iptv:` diagnostics line (counts + last-success time; no
     * credentials, ever). [errorClass] (v2.3.1) is the sanitized
     * failure class recorded by data/IptvClient — the exception's
     * SIMPLE CLASS NAME only, never a message (messages can carry
     * URLs). It renders only in the ERROR state, parenthesized
     * right after "not answering", so a dead panel says WHY it is
     * not answering: "not answering (UnknownHostException) — …".
     * Empty = nothing recorded = no parenthetical.
     */
    fun diagText(
        kind: Kind,
        live: Int,
        vod: Int,
        series: Int,
        favorites: Int,
        epgChannels: Int,
        timeText: String,
        errorClass: String = "",
        storeSizeText: String = ""
    ): String = when (kind) {
        Kind.NONE -> "not checked yet"
        Kind.NOT_CONFIGURED -> "not configured (Settings › IPTV)"
        Kind.AUTH -> "login failed — check server, username and password in Settings › IPTV" +
            countsSuffix(live, vod, series, favorites, epgChannels, timeText, storeSizeText)
        Kind.EMPTY -> "connected, but the catalogues are empty" +
            countsSuffix(live, vod, series, favorites, epgChannels, timeText, storeSizeText)
        Kind.ERROR -> "not answering" +
            (if (errorClass.isNotEmpty()) " ($errorClass)" else "") +
            " — check the server address and your network" +
            countsSuffix(live, vod, series, favorites, epgChannels, timeText, storeSizeText)
        Kind.OK -> "OK" + countsSuffix(live, vod, series, favorites, epgChannels, timeText, storeSizeText)
    }

    private fun countsSuffix(
        live: Int, vod: Int, series: Int, favorites: Int, epgChannels: Int,
        timeText: String, storeSizeText: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append(" · live ").append(live)
            .append(" · vod ").append(vod)
            .append(" · series ").append(series)
            .append(" · fav ").append(favorites)
        if (epgChannels > 0) sb.append(" · epg ").append(epgChannels)
        // v2.4: the on-disk store size (counts come from the store
        // index now, and the size is the honest scale of the data).
        if (storeSizeText.isNotEmpty()) sb.append(" · ").append(storeSizeText)
        if (timeText.isNotEmpty()) sb.append(" · ").append(timeText)
        return sb.toString()
    }

    /**
     * The one-line empty state for the IPTV home / browser, or null
     * when content should simply render (OK) / nothing has been
     * classified yet (NONE shows the generic add-account line via
     * the NOT_CONFIGURED wording only when truly unconfigured —
     * callers pass NONE through as the "loading" case themselves).
     */
    fun emptyLine(kind: Kind): String? = when (kind) {
        Kind.NOT_CONFIGURED -> "Add your IPTV account in Settings › IPTV"
        Kind.AUTH -> "IPTV login failed — check the account in Settings › IPTV"
        Kind.EMPTY -> "Your IPTV catalogues are empty"
        Kind.ERROR -> "IPTV isn't answering — check the server address and your network"
        Kind.OK, Kind.NONE -> null
    }
}
