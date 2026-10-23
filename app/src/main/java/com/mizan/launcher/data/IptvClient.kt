package com.mizan.launcher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvM3uStream
import com.mizan.launcher.core.IptvStatus
import com.mizan.launcher.core.IptvStore
import com.mizan.launcher.core.IptvStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * IPTV data layer (v2.4, spec §24) — the native counterpart of
 * Haythem's "Universal IPTV Player" web app. Two sources:
 *
 * - Xtream Codes: player_api.php on his server (auth probe + the
 *   six catalogue calls + get_series_info / get_short_epg on
 *   demand), mirroring the calls his web player makes.
 * - M3U: a playlist URL (+ optional XMLTV URL), parsed by
 *   core/IptvM3uStream.
 *
 * THE v2.4 STORE (the reason for this rewrite): up to v2.3.2 the
 * catalogues lived as giant JSON strings in SharedPreferences —
 * the whole prefs file sits in process memory permanently, so a
 * real panel's tens of MB of JSON bloated the heap from launch
 * and every screen re-parsed whole documents. Now:
 *
 * - INGEST (one background thread, on request only) streams each
 *   endpoint element-by-element (core/IptvStream) into compact
 *   .jsonl files under filesDir/iptv/ plus a small index.json
 *   (totals, per-category counts, byte sizes, stamps — see
 *   core/IptvStore). Temp files swap into place ONLY when every
 *   attempted catalogue endpoint succeeded (core swap rule); the
 *   UI keeps serving the old store throughout.
 * - READS are slices: [sliceBlocking]/[sliceAsync] scan a kind
 *   file on a worker thread and return one window of items; the
 *   index serves counts + categories synchronously.
 * - REFRESH IS ON REQUEST ONLY: saving the account in
 *   Settings › IPTV, or tapping Refresh there / in the browser.
 *   Nothing fetches on open, resume or home build any more.
 *
 * CREDENTIAL HYGIENE unchanged: account values and the M3U/EPG
 * URLs live only in prefs; request URLs embed the Xtream
 * credentials by protocol, are built transiently, and are NEVER
 * logged — every error recorded is sanitized to the exception's
 * class name only. A source fingerprint (hash of server+user+
 * URLs — never the password) stamps the index: change the account
 * and the old account's store stops showing immediately.
 */
object IptvClient {

    private const val MAX_CATALOGUE_BYTES = 48L * 1024L * 1024L
    private const val MAX_EPG_BYTES = 64L * 1024L * 1024L
    private const val MAX_AUTH_BYTES = 1024L * 1024L

    /** Categories kept per kind from the categories calls (real panels stay far under). */
    private const val MAX_CATEGORIES = 2000

    /** Programmes retained per channel in the in-memory EPG index. */
    private const val EPG_INDEX_CAP_PER_CHANNEL = 16

    /** EPG index read horizon: programmes starting within the next 6h (the guide shows now/next). */
    private const val EPG_READ_HORIZON_MS = 6L * 60L * 60L * 1000L

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MizanLauncher/2.4"

    // Small prefs keys (state + diagnostics bookkeeping only — the
    // catalogue documents themselves NEVER live here any more).
    private const val K_STATE = "iptv_state"
    private const val K_LAST_ERROR = "iptv_last_error"
    private const val K_STORE_V2 = "iptv_store_v2"

    /** The giant pre-v2.4 prefs documents, purged by [ensureStore]. */
    private val LEGACY_KEYS = listOf(
        "iptv_cache_live_cats", "iptv_cache_live",
        "iptv_cache_vod_cats", "iptv_cache_vods",
        "iptv_cache_series_cats", "iptv_cache_series",
        "iptv_cache_m3u", "iptv_cache_epg_xtream", "iptv_cache_epg_m3u",
        "iptv_epg_xtream_at", "iptv_epg_m3u_at", "iptv_epg_channels",
        "iptv_source_fp", "iptv_cache_fetched"
    )

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

    // ------------------------------------------------------------ store files

    private fun storeDir(ctx: Context): File = File(ctx.filesDir, "iptv")

    private fun storeFile(ctx: Context, name: String): File = File(storeDir(ctx), name)

    private fun fileFor(kind: String): String = when (kind) {
        IptvLogic.KIND_VOD -> IptvStore.FILE_VOD
        IptvLogic.KIND_SERIES -> IptvStore.FILE_SERIES
        else -> IptvStore.FILE_LIVE
    }

    /**
     * First-v2.4-run migration: DELETE the giant legacy catalogue
     * documents from SharedPreferences (the prefs map — and its
     * permanent heap — shrinks at the apply). Conversion is
     * deliberately NOT attempted: re-parsing the very strings
     * that OOM would risk the same blow-up on the migration path;
     * the store repopulates on the first Refresh instead.
     * Credentials, favorites, resume, locks and the series-info
     * cache are small and stay. Idempotent; runs inline (removals
     * only — no giant value is ever read here).
     */
    @Volatile
    private var storeReady = false

    fun ensureStore(ctx: Context) {
        if (storeReady) return
        synchronized(this) {
            if (storeReady) return
            try {
                val p = prefs(ctx)
                val done = try {
                    p.getBoolean(K_STORE_V2, false)
                } catch (e: Exception) {
                    false
                }
                if (!done) {
                    val ed = p.edit()
                    for (k in LEGACY_KEYS) ed.remove(k)
                    ed.putBoolean(K_STORE_V2, true)
                    ed.apply()
                }
            } catch (e: Exception) {
                // Migration trouble must never block the store.
            }
            storeReady = true
        }
    }

    /** Delete the whole store (Settings › Clear sources): files + memos; prefs untouched. */
    fun clearStore(ctx: Context) {
        try {
            val dir = storeDir(ctx)
            dir.listFiles()?.forEach { f ->
                try { f.delete() } catch (e: Exception) {
                    // Best-effort per file.
                }
            }
        } catch (e: Exception) {
            // Nothing else to do — the fingerprint guard covers leftovers.
        }
        indexMemo = null
        indexMemoStamp = ""
        tuplesMemo = null
        tuplesStamp = Long.MIN_VALUE
        epgIndexCache = null
    }

    // ------------------------------------------------------------ index / snapshot

    @Volatile
    private var indexMemo: IptvStore.Index? = null

    @Volatile
    private var indexMemoStamp: String = ""

    /**
     * The store index, memoized on the file's (mtime, length).
     * The index is small by construction (categories only), so
     * reading it is the one synchronous disk read the UI performs;
     * the stamp check keeps repeat reads to a stat.
     */
    private fun loadIndex(ctx: Context): IptvStore.Index? {
        val f = storeFile(ctx, IptvStore.FILE_INDEX)
        if (!f.isFile) {
            indexMemo = null
            indexMemoStamp = ""
            return null
        }
        val stamp = f.lastModified().toString() + ":" + f.length()
        if (stamp == indexMemoStamp) return indexMemo
        val parsed = try {
            IptvStore.decodeIndex(f.readText())
        } catch (e: Exception) {
            null
        }
        indexMemo = parsed
        indexMemoStamp = stamp
        return parsed
    }

    /** The index, valid only while it belongs to the CURRENT sources (fingerprint). */
    fun storeIndex(ctx: Context): IptvStore.Index? {
        ensureStore(ctx)
        val idx = loadIndex(ctx) ?: return null
        return if (idx.fingerprint == fingerprint(ctx)) idx else null
    }

    /** A cheap store generation stamp (fetchedAt ^ fingerprint) for caller-side memos. */
    fun storeStamp(ctx: Context): Long =
        storeIndex(ctx)?.let { it.fetchedAt * 31L + it.fingerprint.toLong() } ?: 0L

    /**
     * What the UI needs synchronously: the categories per kind
     * (from the index) + the index itself for counts/stamps.
     * Items are NEVER in here — they come from slices.
     */
    data class Snapshot(
        val index: IptvStore.Index?,
        val liveCats: List<IptvLogic.Category>,
        val vodCats: List<IptvLogic.Category>,
        val seriesCats: List<IptvLogic.Category>
    ) {
        val hasData: Boolean get() = index?.hasData == true
        val fetchedAt: Long get() = index?.fetchedAt ?: 0L
        fun allCats(): List<IptvLogic.Category> = liveCats + vodCats + seriesCats
        fun categories(kind: String): List<IptvLogic.Category> = when (kind) {
            IptvLogic.KIND_VOD -> vodCats
            IptvLogic.KIND_SERIES -> seriesCats
            else -> liveCats
        }
    }

    fun snapshot(ctx: Context): Snapshot {
        val idx = storeIndex(ctx)
        if (idx == null) {
            return Snapshot(null, emptyList(), emptyList(), emptyList())
        }
        return Snapshot(
            index = idx,
            liveCats = idx.categories(IptvLogic.KIND_LIVE),
            vodCats = idx.categories(IptvLogic.KIND_VOD),
            seriesCats = idx.categories(IptvLogic.KIND_SERIES)
        )
    }

    fun fetchedAt(ctx: Context): Long = storeIndex(ctx)?.fetchedAt ?: 0L

    fun epgChannelCount(ctx: Context): Int = storeIndex(ctx)?.epgChannels ?: 0

    /** On-disk store size in bytes (index counts; 0 before the first ingest). */
    fun storeBytes(ctx: Context): Long = storeIndex(ctx)?.storeBytes ?: 0L

    // ------------------------------------------------------------ slices

    /** One window of store items for one kind + the true total behind it. */
    data class SlicePage(
        val kind: String,
        val live: List<IptvLogic.LiveChannel> = emptyList(),
        val vods: List<IptvLogic.VodItem> = emptyList(),
        val series: List<IptvLogic.SeriesItem> = emptyList(),
        val total: Int = 0
    )

    /**
     * The known total for a slice request, or -1 when the scan
     * must count for itself (any free-text query). Unqueried reads
     * take their totals from the index — including per-category
     * and hidden-category-adjusted totals — which is what lets the
     * scanner stop as soon as the window is full.
     */
    private fun knownTotal(
        idx: IptvStore.Index, kind: String, catKey: String?,
        query: String?, excludeCats: Set<String>
    ): Int {
        if (!query.isNullOrBlank()) return -1
        if (catKey != null) return idx.countForCat(catKey)
        val base = idx.countOf(kind)
        if (excludeCats.isEmpty()) return base
        var hidden = 0
        for (c in idx.cats) {
            if (c.kind == kind && c.key in excludeCats) hidden += c.count
        }
        return (base - hidden).coerceAtLeast(0)
    }

    /**
     * Read one window of a kind file. WORKER THREADS ONLY (this
     * scans a file). Fail-open: no store / no file / any I/O
     * trouble → an empty page.
     */
    fun sliceBlocking(
        ctx: Context,
        kind: String,
        catKey: String? = null,
        query: String? = null,
        excludeCats: Set<String> = emptySet(),
        offset: Int = 0,
        limit: Int = IptvLogic.GRID_CAP
    ): SlicePage {
        return try {
            val idx = storeIndex(ctx) ?: return SlicePage(kind)
            val f = storeFile(ctx, fileFor(kind))
            if (!f.isFile) return SlicePage(kind)
            val total = knownTotal(idx, kind, catKey, query, excludeCats)
            f.bufferedReader().use { reader ->
                val lines = reader.lineSequence()
                when (kind) {
                    IptvLogic.KIND_VOD -> {
                        val s = IptvStore.sliceVods(lines, catKey, query, excludeCats, offset, limit, total)
                        SlicePage(kind, vods = s.items, total = s.total)
                    }
                    IptvLogic.KIND_SERIES -> {
                        val s = IptvStore.sliceSeries(lines, catKey, query, excludeCats, offset, limit, total)
                        SlicePage(kind, series = s.items, total = s.total)
                    }
                    else -> {
                        val s = IptvStore.sliceLive(lines, catKey, query, excludeCats, offset, limit, total)
                        SlicePage(kind, live = s.items, total = s.total)
                    }
                }
            }
        } catch (e: Exception) {
            SlicePage(kind)
        }
    }

    /** [sliceBlocking] on a worker thread; the page arrives on the main thread. */
    fun sliceAsync(
        ctx: Context,
        kind: String,
        catKey: String? = null,
        query: String? = null,
        excludeCats: Set<String> = emptySet(),
        offset: Int = 0,
        limit: Int = IptvLogic.GRID_CAP,
        onPage: (SlicePage) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-iptv-slice") {
            val page = sliceBlocking(appCtx, kind, catKey, query, excludeCats, offset, limit)
            Handler(Looper.getMainLooper()).post {
                try { onPage(page) } catch (e: Exception) {
                    // A dead caller's update must never kill the poster.
                }
            }
        }
    }

    // ------------------------------------------------------------ lookups + tuples

    /** First live channel with this (source, id). WORKER THREADS ONLY; null = absent. */
    fun findLiveBlocking(ctx: Context, source: String, id: Long): IptvLogic.LiveChannel? {
        return try {
            if (storeIndex(ctx) == null) return null
            val f = storeFile(ctx, IptvStore.FILE_LIVE)
            if (!f.isFile) return null
            f.bufferedReader().use { reader ->
                IptvStore.findLive(reader.lineSequence(), source, id)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** First movie with this id. WORKER THREADS ONLY; null = absent. */
    fun findVodBlocking(ctx: Context, id: Long): IptvLogic.VodItem? {
        return try {
            if (storeIndex(ctx) == null) return null
            val f = storeFile(ctx, IptvStore.FILE_VOD)
            if (!f.isFile) return null
            f.bufferedReader().use { reader ->
                IptvStore.findVod(reader.lineSequence(), id)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** First series with this id. WORKER THREADS ONLY; null = absent. */
    fun findSeriesBlocking(ctx: Context, id: Long): IptvLogic.SeriesItem? {
        return try {
            if (storeIndex(ctx) == null) return null
            val f = storeFile(ctx, IptvStore.FILE_SERIES)
            if (!f.isFile) return null
            f.bufferedReader().use { reader ->
                IptvStore.findSeries(reader.lineSequence(), id)
            }
        } catch (e: Exception) {
            null
        }
    }

    @Volatile
    private var tuplesMemo: Pair<List<IptvStore.Tuple>, List<IptvStore.Tuple>>? = null

    @Volatile
    private var tuplesStamp: Long = Long.MIN_VALUE

    /**
     * The (series, vod) match tuples if they are already loaded
     * for the current store generation, else null. WatchMatch
     * retains ONLY these (id, title, year) — never item objects.
     */
    fun tuplesIfLoaded(ctx: Context): Pair<List<IptvStore.Tuple>, List<IptvStore.Tuple>>? {
        val stamp = storeStamp(ctx)
        if (stamp == 0L) return null
        val memo = tuplesMemo
        return if (memo != null && tuplesStamp == stamp) memo else null
    }

    /** Load the match tuples on a worker thread (memoized per store generation). */
    fun tuplesAsync(
        ctx: Context,
        onReady: (List<IptvStore.Tuple>, List<IptvStore.Tuple>) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        tuplesIfLoaded(appCtx)?.let { (series, vods) ->
            Handler(Looper.getMainLooper()).post {
                try { onReady(series, vods) } catch (e: Exception) {
                    // Dead caller — nothing to update.
                }
            }
            return
        }
        thread(name = "mizan-iptv-tuples") {
            var series: List<IptvStore.Tuple> = emptyList()
            var vods: List<IptvStore.Tuple> = emptyList()
            try {
                val sf = storeFile(appCtx, IptvStore.FILE_SERIES)
                if (sf.isFile) {
                    series = sf.bufferedReader().use { r ->
                        IptvStore.seriesTuples(r.lineSequence())
                    }
                }
                val vf = storeFile(appCtx, IptvStore.FILE_VOD)
                if (vf.isFile) {
                    vods = vf.bufferedReader().use { r ->
                        IptvStore.vodTuples(r.lineSequence())
                    }
                }
            } catch (e: Exception) {
                // Tuples are an optimization for matching; empty is safe.
            }
            val stamp = storeStamp(appCtx)
            if (stamp != 0L) {
                tuplesMemo = Pair(series, vods)
                tuplesStamp = stamp
            }
            Handler(Looper.getMainLooper()).post {
                try { onReady(series, vods) } catch (e: Exception) {
                    // Dead caller — nothing to update.
                }
            }
        }
    }

    // ------------------------------------------------------------ status + last error

    /** The persisted fetch classification (derived before the first ingest completes). */
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
            snapshot(ctx).hasData -> IptvStatus.Kind.NONE
            else -> IptvStatus.Kind.EMPTY
        }
    }

    private fun saveKind(ctx: Context, kind: IptvStatus.Kind) {
        try {
            prefs(ctx).edit().putString(K_STATE, kind.name).apply()
        } catch (e: Exception) {
            // Diagnostics bookkeeping must never break an ingest.
        }
    }

    /**
     * The most recent transport failure's exception SIMPLE CLASS
     * NAME (v2.3.1) — recorded by the HTTP helpers, persisted per
     * ingest so the `iptv:` line can name WHY the panel is not
     * answering. Class name ONLY: an exception's message can embed
     * the request URL (credentials).
     */
    @Volatile
    private var lastHttpErrorClass: String = ""

    @Volatile
    private var lastHttpCode: Int = 0

    private fun saveLastError(ctx: Context) {
        try {
            prefs(ctx).edit().putString(K_LAST_ERROR, lastHttpErrorClass).apply()
        } catch (e: Exception) {
            // Diagnostics bookkeeping must never break an ingest.
        }
    }

    /** The recorded failure class for the diagnostics line; "" when none. */
    fun lastErrorClass(ctx: Context): String {
        val stored = try {
            prefs(ctx).getString(K_LAST_ERROR, null)
        } catch (e: Exception) {
            null
        }
        return stored ?: lastHttpErrorClass
    }

    // ------------------------------------------------------------ HTTP

    /** (httpCode, body) — body only on 2xx; code -1 = transport failure. Never throws, never logs. */
    private fun httpGet(url: String, maxBytes: Long): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            // Transport answered (any HTTP status): the panel IS
            // reachable, so no transport failure stands recorded.
            lastHttpErrorClass = ""
            lastHttpCode = code
            if (code !in 200..299) {
                Pair(code, null)
            } else {
                val bytes = conn.inputStream.use { stream ->
                    val counting = CountingInputStream(stream, maxBytes)
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = counting.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                Pair(code, String(bytes, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            lastHttpErrorClass = e.javaClass.simpleName
            Pair(-1, null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {
                // Nothing to clean up beyond this.
            }
        }
    }

    /** Thrown when a response outgrows its byte budget; the simple name is the diagnostic. */
    private class StoreTooLargeException : java.io.IOException("store response too large")

    /** An InputStream that fails the read once [maxBytes] is exceeded. */
    private class CountingInputStream(
        private val inner: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(inner) {
        private var total = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) {
                total++
                if (total > maxBytes) throw StoreTooLargeException()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                total += n
                if (total > maxBytes) throw StoreTooLargeException()
            }
            return n
        }
    }

    /**
     * Stream one URL's body into [consume] (the input stream,
     * byte-capped). Returns (answered, ok): answered = the server
     * responded at all; ok = 2xx AND the consumer finished without
     * throwing. Transport/parse failures land in
     * [lastHttpErrorClass] (class name only).
     */
    private fun httpStream(
        url: String,
        maxBytes: Long,
        consume: (InputStream) -> Unit
    ): Pair<Boolean, Boolean> {
        var conn: HttpURLConnection? = null
        var answered = false
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            answered = true
            lastHttpErrorClass = ""
            lastHttpCode = code
            if (code !in 200..299) {
                Pair(true, false)
            } else {
                CountingInputStream(conn.inputStream, maxBytes).use { stream ->
                    consume(stream)
                }
                Pair(true, true)
            }
        } catch (e: Exception) {
            lastHttpErrorClass = e.javaClass.simpleName
            Pair(answered, false)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {
                // Nothing to clean up beyond this.
            }
        }
    }

    /** Sanitized error for diagnostics: the exception TYPE only — messages can carry URLs. */
    private fun sanitized(e: Exception): Exception =
        RuntimeException(e.javaClass.simpleName)

    // ------------------------------------------------------------ ingest (refresh on request)

    private val ingestLock = Any()

    @Volatile
    private var ingestActive = false

    @Volatile
    private var ingestAgain = false

    @Volatile
    private var progressEndpoint: String = ""

    @Volatile
    private var progressItems: Int = 0

    @Volatile
    private var lastProgressPostMs: Long = 0L

    /** One-shot completion/progress callbacks, drained on the main thread. */
    private val pendingCallbacks = ArrayList<() -> Unit>()

    fun isIngesting(): Boolean = ingestActive

    /** "Updating IPTV — Movies · 12,340 items so far" while an ingest runs; "" otherwise. */
    fun ingestProgressText(): String =
        if (!ingestActive) ""
        else "Updating IPTV — $progressEndpoint · $progressItems items so far"

    /**
     * Refresh the store — THE ONLY way data loads (v2.4): the
     * user saving the account in Settings › IPTV, or tapping
     * Refresh there / in the browser. Nothing calls this on open,
     * resume or home build any more. If an ingest is already
     * running, exactly one more run is queued (an account edit
     * mid-ingest lands). [onChanged] fires on the main thread on
     * progress ticks (throttled) and once at completion; the UI
     * keeps serving the old store throughout the ingest.
     * [force] is retained for source compatibility with the old
     * signature — every call is already an explicit request.
     */
    fun refreshAsync(ctx: Context, force: Boolean = false, onChanged: () -> Unit) {
        val appCtx = ctx.applicationContext
        ensureStore(appCtx)
        synchronized(ingestLock) {
            pendingCallbacks.add(onChanged)
            if (ingestActive) {
                ingestAgain = true
                return
            }
            ingestActive = true
        }
        thread(name = "mizan-iptv-ingest") { runIngestLoop(appCtx) }
    }

    private fun fireCallbacks(drain: Boolean) {
        val cbs: List<() -> Unit> = synchronized(ingestLock) {
            if (pendingCallbacks.isEmpty()) return
            val copy = ArrayList(pendingCallbacks)
            if (drain) pendingCallbacks.clear()
            copy
        }
        Handler(Looper.getMainLooper()).post {
            for (cb in cbs) {
                try { cb() } catch (e: Exception) {
                    // A caller's rebuild must never kill the poster.
                }
            }
        }
    }

    private fun updateProgress(endpoint: String, items: Int) {
        progressEndpoint = endpoint
        progressItems = items
        val now = System.currentTimeMillis()
        if (now - lastProgressPostMs >= 2_000L) {
            lastProgressPostMs = now
            fireCallbacks(drain = false)
        }
    }

    private fun runIngestLoop(appCtx: Context) {
        try {
            while (true) {
                try {
                    ingestOnce(appCtx)
                } catch (e: Exception) {
                    lastHttpErrorClass = e.javaClass.simpleName
                    saveLastError(appCtx)
                    Prefs.noteError(appCtx, "iptvIngest", sanitized(e))
                }
                synchronized(ingestLock) {
                    if (!ingestAgain) {
                        ingestActive = false
                        return
                    }
                    ingestAgain = false
                }
            }
        } finally {
            synchronized(ingestLock) { ingestActive = false }
            progressEndpoint = ""
            progressItems = 0
            fireCallbacks(drain = true)
        }
    }

    private fun tmpFile(dir: File, name: String): File = File(dir, "$name.tmp")

    /** Rename tmp over dest (same directory); fall back to copy+delete. */
    private fun swapFile(tmp: File, dest: File): Boolean {
        return try {
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) {
                true
            } else {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun ingestOnce(appCtx: Context) {
        val acct = account(appCtx)
        val m3u = m3uUrl(appCtx)
        val epgM = epgUrl(appCtx)
        val fp = fingerprint(appCtx)
        if (!acct.isConfigured && m3u.isEmpty()) {
            saveKind(appCtx, IptvStatus.Kind.NOT_CONFIGURED)
            return
        }
        val dir = storeDir(appCtx)
        try { dir.mkdirs() } catch (e: Exception) {
            // The swap will fail below and classify; nothing to do here.
        }
        val oldIndex = loadIndex(appCtx)?.takeIf { it.fingerprint == fp }

        val liveTmp = tmpFile(dir, IptvStore.FILE_LIVE)
        val vodTmp = tmpFile(dir, IptvStore.FILE_VOD)
        val seriesTmp = tmpFile(dir, IptvStore.FILE_SERIES)
        val epgTmp = tmpFile(dir, IptvStore.FILE_EPG)
        val indexTmp = tmpFile(dir, IptvStore.FILE_INDEX)
        for (t in listOf(liveTmp, vodTmp, seriesTmp, epgTmp, indexTmp)) {
            try { if (t.exists()) t.delete() } catch (e: Exception) {
                // A stale tmp is overwritten by its writer anyway.
            }
        }

        var anyAnswered = false
        var authFailed = false
        var totalItems = 0
        val results = ArrayList<IptvStore.EndpointResult>()
        val catMeta = LinkedHashMap<String, IptvLogic.Category>()
        val catCounts = HashMap<String, Int>()
        var liveCount = 0
        var vodCount = 0
        var seriesCount = 0

        fun noteItem(catKey: String) {
            catCounts[catKey] = (catCounts[catKey] ?: 0) + 1
            totalItems++
        }

        val liveWriter = liveTmp.bufferedWriter()
        val vodWriter = vodTmp.bufferedWriter()
        val seriesWriter = seriesTmp.bufferedWriter()
        try {
            // ---------------- Xtream catalogue
            if (acct.isConfigured) {
                updateProgress("Connecting", 0)
                val (authCode, authBody) = httpGet(
                    IptvLogic.apiUrl(acct, null), MAX_AUTH_BYTES
                )
                if (authCode > 0) anyAnswered = true
                if (authCode == 401 || authCode == 403) authFailed = true
                if (IptvLogic.parseAuth(authBody) == false) authFailed = true
                if (authFailed) {
                    results.add(IptvStore.EndpointResult(attempted = true, ok = false))
                } else {
                    // Categories first (item rows resolve names from them).
                    val catNames = HashMap<String, Map<String, String>>()
                    val catCalls = listOf(
                        IptvLogic.KIND_LIVE to ("get_live_categories" to "Live categories"),
                        IptvLogic.KIND_VOD to ("get_vod_categories" to "Movie categories"),
                        IptvLogic.KIND_SERIES to ("get_series_categories" to "Series categories")
                    )
                    for ((kind, call) in catCalls) {
                        updateProgress(call.second, totalItems)
                        val list = ArrayList<IptvLogic.Category>()
                        val (answered, ok) = httpStream(
                            IptvLogic.apiUrl(acct, call.first), MAX_CATALOGUE_BYTES
                        ) { stream ->
                            IptvStream.streamArray(stream.bufferedReader(Charsets.UTF_8)) { row ->
                                IptvLogic.categoryFromRow(row, kind)?.let { cat ->
                                    if (list.size < MAX_CATEGORIES) list.add(cat)
                                }
                            }
                        }
                        if (answered) anyAnswered = true
                        if (lastHttpCode == 401 || lastHttpCode == 403) authFailed = true
                        results.add(IptvStore.EndpointResult(attempted = true, ok = ok))
                        if (ok) {
                            for (cat in list) catMeta[cat.key] = cat
                            catNames[kind] = list.associate { it.id to it.name }
                        }
                    }
                    // Live streams.
                    updateProgress("Live channels", totalItems)
                    val (ansLive, okLive) = httpStream(
                        IptvLogic.apiUrl(acct, "get_live_streams"), MAX_CATALOGUE_BYTES
                    ) { stream ->
                        IptvStream.streamArray(stream.bufferedReader(Charsets.UTF_8)) { row ->
                            IptvLogic.liveFromRow(
                                row, catNames[IptvLogic.KIND_LIVE] ?: emptyMap()
                            )?.let { ch ->
                                liveWriter.write(IptvStore.encodeLive(ch))
                                liveWriter.newLine()
                                liveCount++
                                noteItem(ch.catKey)
                                if (liveCount % 500 == 0) {
                                    updateProgress("Live channels", totalItems)
                                }
                            }
                        }
                    }
                    if (ansLive) anyAnswered = true
                    if (lastHttpCode == 401 || lastHttpCode == 403) authFailed = true
                    results.add(IptvStore.EndpointResult(attempted = true, ok = okLive))
                    // VOD streams.
                    updateProgress("Movies", totalItems)
                    val (ansVod, okVod) = httpStream(
                        IptvLogic.apiUrl(acct, "get_vod_streams"), MAX_CATALOGUE_BYTES
                    ) { stream ->
                        IptvStream.streamArray(stream.bufferedReader(Charsets.UTF_8)) { row ->
                            IptvLogic.vodFromRow(
                                row, catNames[IptvLogic.KIND_VOD] ?: emptyMap()
                            )?.let { v ->
                                vodWriter.write(IptvStore.encodeVod(v))
                                vodWriter.newLine()
                                vodCount++
                                noteItem(v.catKey)
                                if (vodCount % 500 == 0) {
                                    updateProgress("Movies", totalItems)
                                }
                            }
                        }
                    }
                    if (ansVod) anyAnswered = true
                    if (lastHttpCode == 401 || lastHttpCode == 403) authFailed = true
                    results.add(IptvStore.EndpointResult(attempted = true, ok = okVod))
                    // Series.
                    updateProgress("Series", totalItems)
                    val (ansSeries, okSeries) = httpStream(
                        IptvLogic.apiUrl(acct, "get_series"), MAX_CATALOGUE_BYTES
                    ) { stream ->
                        IptvStream.streamArray(stream.bufferedReader(Charsets.UTF_8)) { row ->
                            IptvLogic.seriesFromRow(
                                row, catNames[IptvLogic.KIND_SERIES] ?: emptyMap()
                            )?.let { s ->
                                seriesWriter.write(IptvStore.encodeSeries(s))
                                seriesWriter.newLine()
                                seriesCount++
                                noteItem(s.catKey)
                                if (seriesCount % 500 == 0) {
                                    updateProgress("Series", totalItems)
                                }
                            }
                        }
                    }
                    if (ansSeries) anyAnswered = true
                    if (lastHttpCode == 401 || lastHttpCode == 403) authFailed = true
                    results.add(IptvStore.EndpointResult(attempted = true, ok = okSeries))
                }
            }

            // ---------------- M3U playlist (appends to the live file)
            if (m3u.isNotEmpty()) {
                updateProgress("Playlist", totalItems)
                val streamer = IptvM3uStream()
                val (ansM3u, okM3u) = httpStream(m3u, MAX_CATALOGUE_BYTES) { stream ->
                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            streamer.feed(line)?.let { ch ->
                                liveWriter.write(IptvStore.encodeLive(ch))
                                liveWriter.newLine()
                                liveCount++
                                noteItem(ch.catKey)
                                if (liveCount % 500 == 0) {
                                    updateProgress("Playlist", totalItems)
                                }
                            }
                        }
                    }
                }
                if (ansM3u) anyAnswered = true
                results.add(IptvStore.EndpointResult(attempted = true, ok = okM3u))
                if (okM3u) {
                    for (cat in streamer.categories) catMeta[cat.key] = cat
                }
            }
        } finally {
            try { liveWriter.close() } catch (e: Exception) {
                // Close failure surfaces through file state at swap time.
            }
            try { vodWriter.close() } catch (e: Exception) {
                // As above.
            }
            try { seriesWriter.close() } catch (e: Exception) {
                // As above.
            }
        }

        val swap = IptvStore.shouldSwapCatalogue(results)
        if (!swap) {
            for (t in listOf(liveTmp, vodTmp, seriesTmp, epgTmp, indexTmp)) {
                try { if (t.exists()) t.delete() } catch (e: Exception) {
                    // Left for the next ingest's cleanup.
                }
            }
            if (results.any { it.attempted && !it.ok }) {
                Prefs.noteError(
                    appCtx, "iptvIngest",
                    RuntimeException(lastHttpErrorClass.ifEmpty { "FetchFailed" })
                )
            }
            val kind = IptvStatus.classify(
                anyConfigured = true,
                anyAnswered = anyAnswered,
                authFailed = authFailed,
                hasData = oldIndex?.hasData == true
            )
            saveKind(appCtx, kind)
            saveLastError(appCtx)
            return
        }

        // ---------------- EPG (only onto a swapped catalogue; its
        // own success swaps its own file — the guide is auxiliary).
        var epgChannels = if (oldIndex != null) oldIndex.epgChannels else 0
        var epgAt = if (oldIndex != null) oldIndex.epgAt else 0L
        val epgKeys = HashSet<String>()
        try {
            if (liveTmp.isFile) {
                liveTmp.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val ch = IptvStore.decodeLive(line) ?: continue
                        epgKeys.add(ch.epgId.ifEmpty { ch.id.toString() })
                    }
                }
            }
        } catch (e: Exception) {
            // No key set → no programmes retained; the guide degrades to names.
        }
        if (epgKeys.isNotEmpty()) {
            updateProgress("Programme guide", totalItems)
            val seenChannels = HashSet<String>()
            var epgOk = false
            val epgWriter = epgTmp.bufferedWriter()
            try {
                val now = System.currentTimeMillis()
                fun consumeEpg(url: String): Boolean {
                    val (_, ok) = httpStream(url, MAX_EPG_BYTES) { stream ->
                        IptvEpg.streamXmltv(stream.bufferedReader(Charsets.UTF_8)) { p ->
                            if (p.channelId in epgKeys && IptvEpg.inGuideWindow(p, now)) {
                                epgWriter.write(IptvStore.encodeEpg(p))
                                epgWriter.newLine()
                                seenChannels.add(p.channelId)
                            }
                        }
                    }
                    return ok
                }
                if (acct.isConfigured) {
                    if (consumeEpg(IptvLogic.xmltvUrl(acct))) epgOk = true
                }
                if (epgM.isNotEmpty()) {
                    if (consumeEpg(epgM)) epgOk = true
                }
            } finally {
                try { epgWriter.close() } catch (e: Exception) {
                    // As above.
                }
            }
            if (epgOk) {
                if (swapFile(epgTmp, storeFile(appCtx, IptvStore.FILE_EPG))) {
                    epgChannels = seenChannels.size
                    epgAt = System.currentTimeMillis()
                }
            } else {
                try { if (epgTmp.exists()) epgTmp.delete() } catch (e: Exception) {
                    // Left for the next ingest's cleanup.
                }
            }
        }

        // ---------------- swap the catalogue + write the index
        swapFile(liveTmp, storeFile(appCtx, IptvStore.FILE_LIVE))
        swapFile(vodTmp, storeFile(appCtx, IptvStore.FILE_VOD))
        swapFile(seriesTmp, storeFile(appCtx, IptvStore.FILE_SERIES))
        val cats = catMeta.values.map { cat ->
            IptvStore.CatCount(
                id = cat.id, name = cat.name, source = cat.source,
                kind = cat.kind, count = catCounts[cat.key] ?: 0
            )
        }
        val newIndex = IptvStore.Index(
            fetchedAt = System.currentTimeMillis(),
            fingerprint = fp,
            liveCount = liveCount, vodCount = vodCount, seriesCount = seriesCount,
            liveBytes = storeFile(appCtx, IptvStore.FILE_LIVE).length(),
            vodBytes = storeFile(appCtx, IptvStore.FILE_VOD).length(),
            seriesBytes = storeFile(appCtx, IptvStore.FILE_SERIES).length(),
            epgChannels = epgChannels, epgAt = epgAt,
            cats = cats
        )
        try {
            indexTmp.writeText(IptvStore.encodeIndex(newIndex))
            swapFile(indexTmp, storeFile(appCtx, IptvStore.FILE_INDEX))
        } catch (e: Exception) {
            Prefs.noteError(appCtx, "iptvIndex", sanitized(e))
        }
        indexMemo = null
        indexMemoStamp = ""
        tuplesMemo = null
        tuplesStamp = Long.MIN_VALUE
        epgIndexCache = null

        val kind = IptvStatus.classify(
            anyConfigured = true,
            anyAnswered = anyAnswered,
            authFailed = authFailed,
            hasData = newIndex.hasData
        )
        saveKind(appCtx, kind)
        saveLastError(appCtx)
        updateProgress("Done", totalItems)
    }

    // ------------------------------------------------------------ EPG index

    @Volatile
    private var epgIndexCache: Map<String, List<IptvEpg.Programme>>? = null

    @Volatile
    private var epgIndexBuilding = false

    /**
     * The parsed EPG index (channel id → programmes), built on a
     * background thread from the store's epg.jsonl (already
     * window-pruned at ingest; the build additionally drops
     * fully-past programmes and anything beyond the read horizon,
     * and caps each channel's list — see
     * [EPG_INDEX_CAP_PER_CHANNEL]). Returns the last built index
     * or an empty map while building. Fail-open: no EPG → empty
     * map → cards and the guide simply show nothing extra.
     */
    fun epgIndex(ctx: Context, onBuilt: (() -> Unit)? = null): Map<String, List<IptvEpg.Programme>> {
        try {
            epgIndexCache?.let { return it }
        } catch (e: Exception) {
            return emptyMap()
        }
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

    /** (Re)read the store's EPG file into the in-memory index. Fail-open end to end. */
    private fun rebuildEpgIndex(ctx: Context) {
        try {
            if (storeIndex(ctx) == null) {
                epgIndexCache = emptyMap()
                return
            }
            val f = storeFile(ctx, IptvStore.FILE_EPG)
            if (!f.isFile) {
                epgIndexCache = emptyMap()
                return
            }
            val now = System.currentTimeMillis()
            val byChannel = HashMap<String, ArrayList<IptvEpg.Programme>>()
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = IptvStore.decodeEpg(line) ?: continue
                    if (p.stopMs <= now - 3_600_000L) continue
                    if (p.startMs >= now + EPG_READ_HORIZON_MS) continue
                    val list = byChannel.getOrPut(p.channelId) { ArrayList() }
                    if (list.size < EPG_INDEX_CAP_PER_CHANNEL) list.add(p)
                }
            }
            val out = HashMap<String, List<IptvEpg.Programme>>(byChannel.size)
            for ((k, v) in byChannel) out[k] = v.sortedBy { it.startMs }
            epgIndexCache = out
        } catch (e: Exception) {
            epgIndexCache = emptyMap()
            Prefs.noteError(ctx, "iptvEpg", sanitized(e))
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

    // ------------------------------------------------------------ series info cache

    /**
     * Cached get_series_info bodies live in prefs under a key that
     * embeds the source fingerprint — change the account and the
     * old panel's series info stops resolving immediately (the
     * same guard the store index uses). Bodies are the panel's own
     * JSON (ids/titles/extensions) — no credentials inside. These
     * are per-series documents (small, one show's episodes), not
     * catalogue data, so prefs remains the right home for them.
     */
    private fun seriesInfoKey(ctx: Context, seriesId: Long): String =
        "iptv_series_info_" + fingerprint(ctx) + "_" + seriesId

    /** The cached series info for [seriesId], parsed; null when never fetched. Never touches the network. */
    fun cachedSeriesInfo(ctx: Context, seriesId: Long): IptvLogic.SeriesInfo? {
        val raw = try {
            prefs(ctx).getString(seriesInfoKey(ctx, seriesId), null)
        } catch (e: Exception) {
            null
        }
        return IptvLogic.parseSeriesInfo(raw, seriesId)
    }

    /**
     * get_series_info with the cache in front: cache hit → the
     * callback fires (on the main thread) at once; miss → a
     * background fetch, the body is cached, then the callback
     * fires with the parse (null on any failure — auth, HTTP,
     * transport, malformed). Duplicate concurrent fetches for the
     * same series are harmless (the store is idempotent).
     */
    fun fetchSeriesInfoAsync(
        ctx: Context,
        seriesId: Long,
        onResult: (IptvLogic.SeriesInfo?) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        cachedSeriesInfo(appCtx, seriesId)?.let { info ->
            Handler(Looper.getMainLooper()).post {
                try { onResult(info) } catch (e: Exception) {
                    // A caller's UI update must never kill the poster.
                }
            }
            return
        }
        thread(name = "mizan-iptv-series-info") {
            var parsed: IptvLogic.SeriesInfo? = null
            try {
                val (json, _) = fetchSeriesInfoBlocking(appCtx, seriesId)
                if (json != null) {
                    try {
                        prefs(appCtx).edit()
                            .putString(seriesInfoKey(appCtx, seriesId), json).apply()
                    } catch (e: Exception) {
                        // Caching is best-effort; the fetch result still counts.
                    }
                    parsed = IptvLogic.parseSeriesInfo(json, seriesId)
                }
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "iptvSeriesInfo", sanitized(e))
            }
            Handler(Looper.getMainLooper()).post {
                try { onResult(parsed) } catch (e: Exception) {
                    // A caller's UI update must never kill the poster.
                }
            }
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
    val epgId: String = "",
    /**
     * v2.3 Watch auto-mark token: the TRACKER coordinates of this
     * play (TMDB id + type + season/episode), set only by
     * Watch-initiated plays. When the play completes (ended or
     * ≥90% watched), the player hands the item to
     * [PlayQueue.completionHandler], which queues the tracker's
     * mark_watched. Plain IPTV plays leave these empty and never
     * auto-mark. Ids only — no credentials, nothing sensitive.
     */
    val markTmdbId: Long = 0L,
    val markType: String = "",
    val markSeason: Int = 0,
    val markEpisode: Int = 0
) {
    /** True when this play carries tracker coordinates to auto-mark on completion. */
    val hasMarkToken: Boolean get() = markTmdbId > 0L
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

    /**
     * v2.3: invoked by the player (with itself as the Activity)
     * when a token-carrying play completes — see [PlayItem]'s
     * mark fields. Set by data/WatchPlay on every Watch launch;
     * the handler itself ignores tokenless items, so a later plain
     * IPTV play can never auto-mark by accident.
     */
    @Volatile
    var completionHandler: ((android.app.Activity, PlayItem) -> Unit)? = null

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
