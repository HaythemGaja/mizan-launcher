package com.mizan.launcher.core

/**
 * The disk-first IPTV store (v2.4, spec §24) — pure decisions and
 * codecs, no Android imports; data/IptvStoreFiles owns the files.
 *
 * WHY THIS EXISTS: up to v2.3.2 the whole catalogue lived as giant
 * JSON strings inside SharedPreferences. Android loads the entire
 * prefs file into process memory permanently, so a real panel
 * (tens of thousands of entries, tens of MB of JSON) bloated the
 * heap from the moment the process started, and every screen then
 * re-parsed whole documents into object graphs — the black
 * screens survived the v2.3.2 view caps because the problem was
 * never only the views. v2.4 moves the catalogue to files:
 *
 * - filesDir/iptv/live.jsonl / vod.jsonl / series.jsonl — one
 *   compact line per item (the |-separated codec below, only the
 *   fields the UI / player / matcher actually use).
 * - filesDir/iptv/epg.jsonl — one line per retained programme
 *   (already window-pruned at ingest, see IptvEpg.inGuideWindow).
 * - filesDir/iptv/index.json — the small index: totals, per-kind
 *   byte sizes, the category list WITH per-category counts, the
 *   fetched-at stamp and the source fingerprint. Counts and
 *   categories are all the UI needs synchronously; items are read
 *   back in slices (see [sliceLive] / [sliceVods] / [sliceSeries])
 *   on worker threads, one matching window at a time.
 *
 * The ingest streams network responses into TEMP files and swaps
 * them into place only when every attempted catalogue endpoint
 * succeeded ([shouldSwapCatalogue]) — a half-finished refresh
 * never replaces a whole old store.
 */
object IptvStore {

    const val FILE_LIVE = "live.jsonl"
    const val FILE_VOD = "vod.jsonl"
    const val FILE_SERIES = "series.jsonl"
    const val FILE_EPG = "epg.jsonl"
    const val FILE_INDEX = "index.json"

    /** Series plots are detail-screen text; a store line keeps at most this much. */
    const val PLOT_CAP = 1000

    // ------------------------------------------------------------ line codecs
    //
    // One item per line, fields |-separated under IptvLogic.esc —
    // the same codec discipline as favorites/resume, chosen over
    // per-line JSON because a scan decodes a line with a split,
    // not a parser: cheap enough to walk a 50k-line file on a
    // worker thread looking for one window.

    fun encodeLive(ch: IptvLogic.LiveChannel): String = listOf(
        ch.id.toString(), ch.name, ch.icon, ch.categoryId, ch.categoryName,
        ch.epgId, ch.source, ch.directUrl, ch.tvArchive.toString(),
        ch.archiveDays.toString()
    ).joinToString("|") { IptvLogic.esc(it) }

    fun decodeLive(line: String): IptvLogic.LiveChannel? {
        if (line.isBlank()) return null
        val f = fields(line, 10)
        val id = f[0].toLongOrNull() ?: return null
        if (id <= 0L || f[1].isEmpty()) return null
        return IptvLogic.LiveChannel(
            id = id, name = f[1], icon = f[2], categoryId = f[3],
            categoryName = f[4], epgId = f[5],
            source = f[6].ifEmpty { IptvLogic.SRC_XTREAM }, directUrl = f[7],
            tvArchive = f[8].toIntOrNull() ?: 0, archiveDays = f[9].toIntOrNull() ?: 0
        )
    }

    fun encodeVod(v: IptvLogic.VodItem): String = listOf(
        v.id.toString(), v.name, v.icon, v.categoryId, v.categoryName,
        v.ext, v.rating, v.year.toString()
    ).joinToString("|") { IptvLogic.esc(it) }

    fun decodeVod(line: String): IptvLogic.VodItem? {
        if (line.isBlank()) return null
        val f = fields(line, 8)
        val id = f[0].toLongOrNull() ?: return null
        if (id <= 0L || f[1].isEmpty()) return null
        return IptvLogic.VodItem(
            id = id, name = f[1], icon = f[2], categoryId = f[3],
            categoryName = f[4], ext = f[5], rating = f[6],
            year = f[7].toIntOrNull() ?: 0
        )
    }

    fun encodeSeries(s: IptvLogic.SeriesItem): String = listOf(
        s.id.toString(), s.name, s.cover, s.categoryId, s.categoryName,
        s.rating, s.plot.take(PLOT_CAP), s.year.toString()
    ).joinToString("|") { IptvLogic.esc(it) }

    fun decodeSeries(line: String): IptvLogic.SeriesItem? {
        if (line.isBlank()) return null
        val f = fields(line, 8)
        val id = f[0].toLongOrNull() ?: return null
        if (id <= 0L || f[1].isEmpty()) return null
        return IptvLogic.SeriesItem(
            id = id, name = f[1], cover = f[2], categoryId = f[3],
            categoryName = f[4], rating = f[5], plot = f[6],
            year = f[7].toIntOrNull() ?: 0
        )
    }

    fun encodeEpg(p: IptvEpg.Programme): String = listOf(
        p.channelId, p.startMs.toString(), p.stopMs.toString(), p.title
    ).joinToString("|") { IptvLogic.esc(it) }

    fun decodeEpg(line: String): IptvEpg.Programme? {
        if (line.isBlank()) return null
        val f = fields(line, 4)
        val start = f[1].toLongOrNull() ?: return null
        val stop = f[2].toLongOrNull() ?: return null
        if (f[0].isEmpty() || stop <= start) return null
        return IptvEpg.Programme(f[0], start, stop, f[3])
    }

    private fun fields(line: String, count: Int): List<String> {
        val parts = line.split('|')
        return List(count) { i -> if (i < parts.size) IptvLogic.unesc(parts[i]) else "" }
    }

    // ------------------------------------------------------------ index

    /** One category + how many items of its kind the store holds. */
    data class CatCount(
        val id: String, val name: String, val source: String,
        val kind: String, val count: Int
    ) {
        val key: String get() = "$source:$kind:$id"
        fun toCategory(): IptvLogic.Category = IptvLogic.Category(id, name, source, kind)
    }

    /**
     * The store index — everything a screen needs BEFORE any item
     * is read: totals for the "showing N of M" lines, categories
     * (chips + the lock set), byte sizes for diagnostics, and the
     * freshness/fingerprint stamps. Small by construction: a few
     * hundred categories at most, ≈120 bytes each.
     */
    data class Index(
        val fetchedAt: Long,
        val fingerprint: Int,
        val liveCount: Int,
        val vodCount: Int,
        val seriesCount: Int,
        val liveBytes: Long,
        val vodBytes: Long,
        val seriesBytes: Long,
        val epgChannels: Int,
        val epgAt: Long,
        val cats: List<CatCount>
    ) {
        val hasData: Boolean get() = liveCount > 0 || vodCount > 0 || seriesCount > 0
        val storeBytes: Long get() = liveBytes + vodBytes + seriesBytes
        fun countOf(kind: String): Int = when (kind) {
            IptvLogic.KIND_LIVE -> liveCount
            IptvLogic.KIND_VOD -> vodCount
            IptvLogic.KIND_SERIES -> seriesCount
            else -> 0
        }

        fun categories(kind: String): List<IptvLogic.Category> =
            cats.filter { it.kind == kind }.map { it.toCategory() }

        /** Items in one category (0 when the category is unknown). */
        fun countForCat(catKey: String): Int =
            cats.firstOrNull { it.key == catKey }?.count ?: 0
    }

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    fun encodeIndex(idx: Index): String {
        val sb = StringBuilder()
        sb.append("{\"v\":1")
            .append(",\"fetchedAt\":").append(idx.fetchedAt)
            .append(",\"fp\":").append(idx.fingerprint)
            .append(",\"live\":").append(idx.liveCount)
            .append(",\"vod\":").append(idx.vodCount)
            .append(",\"series\":").append(idx.seriesCount)
            .append(",\"liveBytes\":").append(idx.liveBytes)
            .append(",\"vodBytes\":").append(idx.vodBytes)
            .append(",\"seriesBytes\":").append(idx.seriesBytes)
            .append(",\"epgChannels\":").append(idx.epgChannels)
            .append(",\"epgAt\":").append(idx.epgAt)
            .append(",\"cats\":[")
        for ((i, c) in idx.cats.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(jsonEsc(c.id))
                .append("\",\"n\":\"").append(jsonEsc(c.name))
                .append("\",\"s\":\"").append(jsonEsc(c.source))
                .append("\",\"kind\":\"").append(jsonEsc(c.kind))
                .append("\",\"c\":").append(c.count)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeIndex(json: String?): Index? {
        val root = JsonMini.parse(json ?: return null) as? Map<String, Any?> ?: return null
        fun num(key: String): Long = IptvLogic.longOf(root[key])
        val catsRaw = root["cats"] as? List<*> ?: emptyList<Any?>()
        val cats = ArrayList<CatCount>()
        for (raw in catsRaw) {
            val m = raw as? Map<String, Any?> ?: continue
            val kind = IptvLogic.strOf(m["kind"])
            val name = IptvLogic.strOf(m["n"])
            if (name.isEmpty() || kind.isEmpty()) continue
            cats.add(
                CatCount(
                    id = IptvLogic.strOf(m["id"]), name = name,
                    source = IptvLogic.strOf(m["s"]).ifEmpty { IptvLogic.SRC_XTREAM },
                    kind = kind, count = IptvLogic.intOf(m["c"])
                )
            )
        }
        return Index(
            fetchedAt = num("fetchedAt"), fingerprint = num("fp").toInt(),
            liveCount = num("live").toInt(), vodCount = num("vod").toInt(),
            seriesCount = num("series").toInt(),
            liveBytes = num("liveBytes"), vodBytes = num("vodBytes"),
            seriesBytes = num("seriesBytes"),
            epgChannels = num("epgChannels").toInt(), epgAt = num("epgAt"),
            cats = cats
        )
    }

    // ------------------------------------------------------------ slices

    /** One read window: the items to render + the TRUE total they were drawn from. */
    data class Slice<T>(val items: List<T>, val total: Int)

    /**
     * Scan discipline (the heart of v2.4): walk the line sequence
     * once, decode only lines whose cheap fields match the filter,
     * collect exactly the [offset, offset+limit) window of matches,
     * and count matches as the total. When the caller already
     * knows the total (from the index) and there is no free-text
     * query, the scan STOPS as soon as the window is full — a
     * first-page read of a 50k-line file touches ~200 lines.
     * Excluded categories and queries always scan fully (their
     * totals are not in the index).
     */
    private fun <T> scan(
        lines: Sequence<String>,
        decode: (String) -> T?,
        catKeyOf: (T) -> String,
        nameOf: (T) -> String,
        catKey: String?,
        query: String?,
        excludeCats: Set<String>,
        offset: Int,
        limit: Int,
        knownTotal: Int
    ): Slice<T> {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val items = ArrayList<T>()
        var matched = 0
        val canStopEarly = q == null && knownTotal >= 0
        val wantFrom = offset.coerceAtLeast(0)
        val wantTo = wantFrom + limit.coerceAtLeast(0)
        for (line in lines) {
            if (line.isBlank()) continue
            // A line decode is a split + unescape (no parser), so
            // filtering on the decoded item stays cheap even when
            // the scan runs to the end of a large file.
            val item = decode(line) ?: continue
            val ck = catKeyOf(item)
            if (catKey != null && ck != catKey) continue
            if (ck in excludeCats) continue
            if (q != null && !nameOf(item).lowercase().contains(q)) continue
            if (matched >= wantFrom && matched < wantTo) items.add(item)
            matched++
            if (canStopEarly && items.size >= limit.coerceAtLeast(0) && matched >= wantTo) break
        }
        return Slice(items, if (knownTotal >= 0 && q == null) knownTotal else matched)
    }

    fun sliceLive(
        lines: Sequence<String>, catKey: String?, query: String?,
        excludeCats: Set<String>, offset: Int, limit: Int, knownTotal: Int
    ): Slice<IptvLogic.LiveChannel> = scan(
        lines, ::decodeLive, { it.catKey }, { it.name },
        catKey, query, excludeCats, offset, limit, knownTotal
    )

    fun sliceVods(
        lines: Sequence<String>, catKey: String?, query: String?,
        excludeCats: Set<String>, offset: Int, limit: Int, knownTotal: Int
    ): Slice<IptvLogic.VodItem> = scan(
        lines, ::decodeVod, { it.catKey }, { it.name },
        catKey, query, excludeCats, offset, limit, knownTotal
    )

    fun sliceSeries(
        lines: Sequence<String>, catKey: String?, query: String?,
        excludeCats: Set<String>, offset: Int, limit: Int, knownTotal: Int
    ): Slice<IptvLogic.SeriesItem> = scan(
        lines, ::decodeSeries, { it.catKey }, { it.name },
        catKey, query, excludeCats, offset, limit, knownTotal
    )

    // ------------------------------------------------------------ lookups + tuples

    /** First live channel with this (source, id) — early exit, worker threads only. */
    fun findLive(lines: Sequence<String>, source: String, id: Long): IptvLogic.LiveChannel? {
        for (line in lines) {
            val ch = decodeLive(line) ?: continue
            if (ch.source == source && ch.id == id) return ch
        }
        return null
    }

    fun findVod(lines: Sequence<String>, id: Long): IptvLogic.VodItem? {
        for (line in lines) {
            val v = decodeVod(line) ?: continue
            if (v.id == id) return v
        }
        return null
    }

    fun findSeries(lines: Sequence<String>, id: Long): IptvLogic.SeriesItem? {
        for (line in lines) {
            val s = decodeSeries(line) ?: continue
            if (s.id == id) return s
        }
        return null
    }

    /** The lightweight match tuple (id, title, year) — all WatchMatch ever retains. */
    data class Tuple(val id: Long, val name: String, val year: Int)

    fun seriesTuples(lines: Sequence<String>): List<Tuple> {
        val out = ArrayList<Tuple>()
        for (line in lines) {
            val s = decodeSeries(line) ?: continue
            out.add(Tuple(s.id, s.name, s.year))
        }
        return out
    }

    fun vodTuples(lines: Sequence<String>): List<Tuple> {
        val out = ArrayList<Tuple>()
        for (line in lines) {
            val v = decodeVod(line) ?: continue
            out.add(Tuple(v.id, v.name, v.year))
        }
        return out
    }

    // ------------------------------------------------------------ ingest decisions

    /** One attempted endpoint's outcome, as the ingest records it. */
    data class EndpointResult(val attempted: Boolean, val ok: Boolean)

    /**
     * The swap rule: the new store replaces the old ONLY when
     * every catalogue endpoint that was attempted succeeded. One
     * failure mid-ingest → the old store stays whole (the failure
     * is classified + the error class recorded instead). EPG is
     * deliberately NOT part of this decision — the guide is
     * auxiliary and its file swaps on its own success.
     */
    fun shouldSwapCatalogue(results: List<EndpointResult>): Boolean =
        results.any { it.attempted } && results.none { it.attempted && !it.ok }

    /** Human store size for diagnostics ("store 12.4 MB" / "store 812 KB" / "store 0 B"). */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> "store 0 B"
        bytes < 1024L -> "store $bytes B"
        bytes < 1024L * 1024L -> "store ${bytes / 1024L} KB"
        else -> {
            val mb = bytes / (1024.0 * 1024.0)
            "store " + (if (mb >= 10.0) {
                String.format(java.util.Locale.US, "%.0f", mb)
            } else {
                String.format(java.util.Locale.US, "%.1f", mb)
            }) + " MB"
        }
    }
}
