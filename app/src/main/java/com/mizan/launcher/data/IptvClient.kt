package com.mizan.launcher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * IPTV data layer (v2.2, spec §20) — the native counterpart of
 * Haythem's "Universal IPTV Player" web app. Two sources:
 *
 * - Xtream Codes: player_api.php on his server (auth probe + the
 *   six catalogue calls + get_series_info / get_short_epg on
 *   demand), mirroring the calls his web player makes.
 * - M3U: a playlist URL (+ optional XMLTV URL), parsed by
 *   core/IptvLogic.parseM3u.
 *
 * Everything is FAIL-OPEN and follows the TrackerClient discipline:
 * background fetch only, last-good cache in prefs, snapshots parsed
 * from the cache (never the network) on the UI path, the refresh
 * throttle earned ONLY by a fetch that returned data, and every
 * outcome classified (core/IptvStatus) into the `iptv:` diagnostics
 * line — counts + state, never credentials.
 *
 * CREDENTIAL HYGIENE: account values and the M3U/EPG URLs live only
 * in prefs (see Prefs). Request URLs embed the Xtream credentials
 * by protocol; they are built transiently and NEVER logged — every
 * error recorded here is sanitized to the exception's class name
 * only, because a network exception's message can contain the URL.
 * A source fingerprint (a hash of server+user+URLs — never the
 * password) guards the cache: change the account and the old
 * account's catalogues stop showing immediately.
 */
object IptvClient {

    /** Catalogue refresh throttle (earned by data, like the tracker). */
    const val REFRESH_MIN_AGE_MS = 300_000L

    /** EPG staleness window: XMLTV is re-fetched at most this often. */
    const val EPG_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    private const val MAX_CATALOGUE_BYTES = 48 * 1024 * 1024
    private const val MAX_EPG_BYTES = 64 * 1024 * 1024

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MizanLauncher/2.2"

    // Cache keys (global — the sources are device-level, like the tracker's).
    private const val K_LIVE_CATS = "iptv_cache_live_cats"
    private const val K_LIVE = "iptv_cache_live"
    private const val K_VOD_CATS = "iptv_cache_vod_cats"
    private const val K_VODS = "iptv_cache_vods"
    private const val K_SERIES_CATS = "iptv_cache_series_cats"
    private const val K_SERIES = "iptv_cache_series"
    private const val K_M3U = "iptv_cache_m3u"
    private const val K_EPG_X = "iptv_cache_epg_xtream"
    private const val K_EPG_M = "iptv_cache_epg_m3u"
    private const val K_EPG_X_AT = "iptv_epg_xtream_at"
    private const val K_EPG_M_AT = "iptv_epg_m3u_at"
    private const val K_EPG_CHANNELS = "iptv_epg_channels"
    private const val K_FETCHED = "iptv_cache_fetched"
    private const val K_STATE = "iptv_state"
    private const val K_LAST_OK = "iptv_last_ok"
    private const val K_FINGERPRINT = "iptv_source_fp"

    /** Process session start — the "session" half of the category-lock grace rule. */
    val sessionStartMs: Long = System.currentTimeMillis()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ sources

    fun account(ctx: Context): IptvLogic.Account = Prefs(ctx).iptvAccount()

    fun m3uUrl(ctx: Context): String = Prefs(ctx).iptvM3uUrl.trim()

    fun epgUrl(ctx: Context): String = Prefs(ctx).iptvEpgUrl.trim()

    fun anyConfigured(ctx: Context): Boolean =
        account(ctx).isConfigured || m3uUrl(ctx).isNotEmpty()

    /** Hash of the source identity (server, user, URLs — NEVER the password). */
    private fun fingerprint(ctx: Context): Int {
        val a = account(ctx)
        return (a.server + "|" + a.user + "|" + m3uUrl(ctx) + "|" + epgUrl(ctx)).hashCode()
    }

    private fun cacheUsable(ctx: Context): Boolean =
        try {
            prefs(ctx).getInt(K_FINGERPRINT, Int.MIN_VALUE) == fingerprint(ctx)
        } catch (e: Exception) {
            false
        }

    // ------------------------------------------------------------ snapshots

    /** The last-good catalogues, parsed from the cache. Never touches the network. */
    fun cached(ctx: Context): IptvLogic.Catalogues {
        return try {
            if (!cacheUsable(ctx)) return IptvLogic.Catalogues()
            val p = prefs(ctx)
            val liveCatsX = IptvLogic.parseCategories(p.getString(K_LIVE_CATS, null), IptvLogic.KIND_LIVE)
            val vodCats = IptvLogic.parseCategories(p.getString(K_VOD_CATS, null), IptvLogic.KIND_VOD)
            val seriesCats = IptvLogic.parseCategories(p.getString(K_SERIES_CATS, null), IptvLogic.KIND_SERIES)
            val (m3uCats, m3uLive) = IptvLogic.parseM3u(p.getString(K_M3U, null))
            IptvLogic.Catalogues(
                liveCats = liveCatsX + m3uCats,
                live = IptvLogic.parseLive(p.getString(K_LIVE, null), liveCatsX.associate { it.id to it.name }) + m3uLive,
                vodCats = vodCats,
                vods = IptvLogic.parseVods(p.getString(K_VODS, null), vodCats.associate { it.id to it.name }),
                seriesCats = seriesCats,
                series = IptvLogic.parseSeries(p.getString(K_SERIES, null), seriesCats.associate { it.id to it.name })
            )
        } catch (e: Exception) {
            IptvLogic.Catalogues()
        }
    }

    fun fetchedAt(ctx: Context): Long =
        try { prefs(ctx).getLong(K_FETCHED, 0L) } catch (e: Exception) { 0L }

    fun epgChannelCount(ctx: Context): Int =
        try { prefs(ctx).getInt(K_EPG_CHANNELS, 0) } catch (e: Exception) { 0 }

    // ------------------------------------------------------------ status

    /** The persisted fetch classification (derived before the first fetch completes). */
    fun status(ctx: Context): IptvStatus.Kind {
        val stored = try { prefs(ctx).getString(K_STATE, null) } catch (e: Exception) { null }
        if (stored != null) {
            try {
                return IptvStatus.Kind.valueOf(stored)
            } catch (e: Exception) {
                // fall through to the derived state
            }
        }
        return when {
            !anyConfigured(ctx) -> IptvStatus.Kind.NOT_CONFIGURED
            cached(ctx).hasData -> IptvStatus.Kind.NONE
            else -> IptvStatus.Kind.EMPTY
        }
    }

    private fun saveKind(ctx: Context, kind: IptvStatus.Kind) {
        try {
            prefs(ctx).edit().putString(K_STATE, kind.name).apply()
        } catch (e: Exception) {
            // Diagnostics bookkeeping must never break a fetch.
        }
    }

    // ------------------------------------------------------------ HTTP

    /** (httpCode, body) — body only on 2xx; code -1 = transport failure. Never throws, never logs. */
    private fun httpGet(url: String, maxBytes: Int): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) {
                Pair(code, null)
            } else {
                val bytes = conn.inputStream.use { stream ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return Pair(code, null)
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                Pair(code, String(bytes, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Pair(-1, null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {
                // Nothing to clean up beyond this.
            }
        }
    }

    /** Sanitized error for diagnostics: the exception TYPE only — messages can carry URLs. */
    private fun sanitized(e: Exception): Exception =
        RuntimeException(e.javaClass.simpleName)

    // ------------------------------------------------------------ refresh

    @Volatile
    private var inFlight = false

    /**
     * Refresh catalogues (+ stale EPG) on a background thread.
     * Throttle: after a fetch WITH data, catalogue calls wait
     * [REFRESH_MIN_AGE_MS]; EPG waits [EPG_MAX_AGE_MS]; errors and
     * empty results never throttle — every call retries. [onChanged]
     * fires on the main thread when snapshots or state changed.
     */
    fun refreshAsync(ctx: Context, force: Boolean = false, onChanged: () -> Unit) {
        val appCtx = ctx.applicationContext
        if (inFlight) return
        val now = System.currentTimeMillis()
        val p = try { prefs(appCtx) } catch (e: Exception) { return }
        val lastOk = try { p.getLong(K_LAST_OK, 0L) } catch (e: Exception) { 0L }
        val catalogueFresh = !force && lastOk > 0 && now - lastOk < REFRESH_MIN_AGE_MS &&
            cacheUsable(appCtx)
        val epgStale = epgStale(appCtx, now)
        if (catalogueFresh && !epgStale) return
        val kindBefore = status(appCtx)
        val before = snapshotRaw(appCtx)
        inFlight = true
        thread(name = "mizan-iptv-fetch") {
            try {
                val acct = account(appCtx)
                val m3u = m3uUrl(appCtx)
                val epgM = epgUrl(appCtx)
                var anyAnswered = false
                var authFailed = false
                val editor = prefs(appCtx).edit()
                editor.putInt(K_FINGERPRINT, fingerprint(appCtx))
                editor.putLong(K_FETCHED, System.currentTimeMillis())

                if (!catalogueFresh) {
                    if (acct.isConfigured) {
                        val (authCode, authBody) = httpGet(
                            IptvLogic.apiUrl(acct, null), MAX_CATALOGUE_BYTES
                        )
                        if (authCode > 0) anyAnswered = true
                        if (authCode == 401 || authCode == 403) authFailed = true
                        val auth = IptvLogic.parseAuth(authBody)
                        if (auth == false) authFailed = true
                        if (!authFailed) {
                            val calls: List<Pair<String, String>> = listOf(
                                K_LIVE_CATS to "get_live_categories",
                                K_LIVE to "get_live_streams",
                                K_VOD_CATS to "get_vod_categories",
                                K_VODS to "get_vod_streams",
                                K_SERIES_CATS to "get_series_categories",
                                K_SERIES to "get_series"
                            )
                            for ((key, action) in calls) {
                                val (code, body) = httpGet(
                                    IptvLogic.apiUrl(acct, action), MAX_CATALOGUE_BYTES
                                )
                                if (code > 0) anyAnswered = true
                                if (code == 401 || code == 403) authFailed = true
                                if (body != null) editor.putString(key, body)
                            }
                        }
                    }
                    if (m3u.isNotEmpty()) {
                        val (code, body) = httpGet(m3u, MAX_CATALOGUE_BYTES)
                        if (code > 0) anyAnswered = true
                        if (body != null) editor.putString(K_M3U, body)
                    }
                }

                // EPG (staleness-windowed, independent of the catalogue throttle).
                var epgChanged = false
                if (acct.isConfigured && epgStaleFor(appCtx, K_EPG_X_AT, now)) {
                    val (code, body) = httpGet(IptvLogic.xmltvUrl(acct), MAX_EPG_BYTES)
                    if (code > 0) anyAnswered = true
                    if (body != null) {
                        editor.putString(K_EPG_X, body)
                        editor.putLong(K_EPG_X_AT, System.currentTimeMillis())
                        epgChanged = true
                    }
                }
                if (epgM.isNotEmpty() && epgStaleFor(appCtx, K_EPG_M_AT, now)) {
                    val (code, body) = httpGet(epgM, MAX_EPG_BYTES)
                    if (code > 0) anyAnswered = true
                    if (body != null) {
                        editor.putString(K_EPG_M, body)
                        editor.putLong(K_EPG_M_AT, System.currentTimeMillis())
                        epgChanged = true
                    }
                }
                editor.apply()
                if (epgChanged) rebuildEpgIndex(appCtx)

                val snap = cached(appCtx)
                val kind = IptvStatus.classify(
                    anyConfigured = acct.isConfigured || m3u.isNotEmpty(),
                    anyAnswered = anyAnswered,
                    authFailed = authFailed,
                    hasData = snap.hasData
                )
                saveKind(appCtx, kind)
                if (snap.hasData) {
                    try {
                        prefs(appCtx).edit()
                            .putLong(K_LAST_OK, System.currentTimeMillis()).apply()
                    } catch (e: Exception) {
                        // Bookkeeping only.
                    }
                }
                if (snapshotRaw(appCtx) != before || kind != kindBefore || epgChanged) {
                    Handler(Looper.getMainLooper()).post {
                        try { onChanged() } catch (e: Exception) {
                            // A caller's rebuild must never kill the fetch thread's poster.
                        }
                    }
                }
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "iptvFetch", sanitized(e))
            } finally {
                inFlight = false
            }
        }
    }

    private fun snapshotRaw(ctx: Context): List<String?> {
        val p = prefs(ctx)
        return listOf(
            p.getString(K_LIVE_CATS, null), p.getString(K_LIVE, null),
            p.getString(K_VOD_CATS, null), p.getString(K_VODS, null),
            p.getString(K_SERIES_CATS, null), p.getString(K_SERIES, null),
            p.getString(K_M3U, null)
        )
    }

    private fun epgStaleFor(ctx: Context, key: String, now: Long): Boolean {
        val at = try { prefs(ctx).getLong(key, 0L) } catch (e: Exception) { 0L }
        return at <= 0L || now - at >= EPG_MAX_AGE_MS
    }

    private fun epgStale(ctx: Context, now: Long): Boolean {
        if (!cacheUsable(ctx)) return true
        val acct = account(ctx)
        if (acct.isConfigured && epgStaleFor(ctx, K_EPG_X_AT, now)) return true
        if (epgUrl(ctx).isNotEmpty() && epgStaleFor(ctx, K_EPG_M_AT, now)) return true
        return false
    }

    // ------------------------------------------------------------ EPG index

    @Volatile
    private var epgIndexCache: Map<String, List<IptvEpg.Programme>>? = null

    @Volatile
    private var epgIndexBuilding = false

    /**
     * The parsed EPG index (channel id → programmes). Built on a
     * background thread from the cached XMLTV documents (the M3U
     * document first, the Xtream one overlaid — Xtream wins a
     * shared channel id); returns the last built index or an empty
     * map while building. Fail-open: no EPG → empty map → cards and
     * the guide simply show nothing extra.
     */
    fun epgIndex(ctx: Context, onBuilt: (() -> Unit)? = null): Map<String, List<IptvEpg.Programme>> {
        epgIndexCache?.let { return it }
        if (!epgIndexBuilding) {
            epgIndexBuilding = true
            val appCtx = ctx.applicationContext
            thread(name = "mizan-iptv-epg") {
                try {
                    rebuildEpgIndex(appCtx)
                    if (onBuilt != null) {
                        Handler(Looper.getMainLooper()).post {
                            try { onBuilt() } catch (e: Exception) {
                                // A caller's rebuild must never kill the poster.
                            }
                        }
                    }
                } catch (e: Exception) {
                    Prefs.noteError(appCtx, "iptvEpg", sanitized(e))
                } finally {
                    epgIndexBuilding = false
                }
            }
        }
        return epgIndexCache ?: emptyMap()
    }

    /** (Re)parse the cached XMLTV documents into the in-memory index + channel count. */
    private fun rebuildEpgIndex(ctx: Context) {
        if (!cacheUsable(ctx)) {
            epgIndexCache = emptyMap()
            return
        }
        val p = prefs(ctx)
        val merged = LinkedHashMap<String, List<IptvEpg.Programme>>()
        merged.putAll(IptvEpg.parseXmltv(p.getString(K_EPG_M, null)))
        merged.putAll(IptvEpg.parseXmltv(p.getString(K_EPG_X, null)))
        epgIndexCache = merged
        try {
            p.edit().putInt(K_EPG_CHANNELS, merged.size).apply()
        } catch (e: Exception) {
            // Count is diagnostics only.
        }
    }

    /** Drop the in-memory EPG index (account changed / cleared). */
    fun invalidateEpg() {
        epgIndexCache = null
    }

    // ------------------------------------------------------------ on-demand calls

    /**
     * get_series_info, synchronously (call from a background
     * thread): (json, error) where error is null on success, else
     * "not_configured" / "auth" / "http_<code>" / "io". Error
     * strings are fixed tokens — never server text, never URLs.
     */
    fun fetchSeriesInfoBlocking(ctx: Context, seriesId: Long): Pair<String?, String?> {
        val acct = account(ctx)
        if (!acct.isConfigured) return Pair(null, "not_configured")
        val (code, body) = httpGet(
            IptvLogic.apiUrl(acct, "get_series_info", mapOf("series_id" to seriesId.toString())),
            MAX_CATALOGUE_BYTES
        )
        return when {
            body != null -> Pair(body, null)
            code == 401 || code == 403 -> Pair(null, "auth")
            code > 0 -> Pair(null, "http_$code")
            else -> Pair(null, "io")
        }
    }

    /** get_short_epg for one channel (background thread): raw JSON or null. */
    fun fetchShortEpgBlocking(ctx: Context, streamId: Long): String? {
        val acct = account(ctx)
        if (!acct.isConfigured) return null
        val (_, body) = httpGet(
            IptvLogic.apiUrl(acct, "get_short_epg", mapOf("stream_id" to streamId.toString())),
            MAX_CATALOGUE_BYTES
        )
        return body
    }

    // ------------------------------------------------------------ playback

    /**
     * Resolve a queued item to its stream URL (transient — the URL
     * embeds credentials for Xtream kinds; it goes to the player
     * and nowhere else). Null when the source is gone/unconfigured.
     */
    fun playUrl(ctx: Context, item: PlayItem): String? {
        if (item.directUrl.isNotEmpty()) return item.directUrl
        val acct = account(ctx)
        if (!acct.isConfigured) return null
        return when (item.kind) {
            IptvLogic.KIND_LIVE -> item.id.toLongOrNull()?.let { IptvLogic.liveUrl(acct, it) }
            IptvLogic.KIND_VOD -> item.id.toLongOrNull()?.let { IptvLogic.vodUrl(acct, it, item.ext) }
            IptvLogic.KIND_EPISODE -> IptvLogic.episodeUrl(acct, item.id, item.ext)
            KIND_SHIFT -> item.id.toLongOrNull()?.let {
                IptvLogic.timeshiftUrl(acct, it, item.shiftStartMs, item.shiftDurationMin)
            }
            else -> null
        }
    }

    /** The timeshift pseudo-kind for [PlayItem] (catch-up streams). */
    const val KIND_SHIFT = "shift"
}

/**
 * One playable item handed to ui/PlayerActivity. Xtream items carry
 * only ids — the URL is resolved by [IptvClient.playUrl] at play
 * time (credentials never travel in intents or files). M3U items
 * carry their direct URL (it IS the stream address; it lives in
 * prefs-grade stores only, like the playlist URL itself).
 */
data class PlayItem(
    val kind: String,
    val source: String,
    val id: String,
    val title: String,
    val icon: String = "",
    val meta: String = "",
    val ext: String = "",
    val catKey: String = "",
    val directUrl: String = "",
    val seriesId: Long = 0L,
    val shiftStartMs: Long = 0L,
    val shiftDurationMin: Int = 0,
    /** Xtream live only: the channel's tv_archive flag + EPG id (catch-up, §20.10). */
    val tvArchive: Int = 0,
    val epgId: String = ""
) {
    /** Resume-store identity (live/shift items never resume). */
    val resumeKey: String get() = IptvLogic.resumeKey(kind, source, id)
    val seekable: Boolean
        get() = kind == IptvLogic.KIND_VOD || kind == IptvLogic.KIND_EPISODE ||
            kind == IptvClient.KIND_SHIFT
}

/**
 * The live zap list for the player (previous/next channel): set by
 * whichever surface started playback, read by PlayerActivity.
 * In-process only — if the process restarted, the player falls
 * back to the single item it was launched with.
 */
object PlayQueue {

    @Volatile
    var items: List<PlayItem> = emptyList()

    @Volatile
    var index: Int = 0

    fun set(list: List<PlayItem>, startIndex: Int) {
        items = list
        index = if (list.isEmpty()) 0 else startIndex.coerceIn(0, list.size - 1)
    }

    fun current(): PlayItem? = items.getOrNull(index)

    /** Move by [delta] with wrap-around; null when the queue is empty. */
    fun move(delta: Int): PlayItem? {
        if (items.isEmpty()) return null
        index = ((index + delta) % items.size + items.size) % items.size
        return items[index]
    }
}
