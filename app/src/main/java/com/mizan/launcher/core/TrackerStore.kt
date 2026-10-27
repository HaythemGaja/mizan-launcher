package com.mizan.launcher.core

/**
 * The indexed tracker store (v3.6, ask 53) — pure codecs, no
 * Android imports. This is the tracker side of the v2.4 IPTV
 * lesson, applied late: multi-megabyte tracker snapshot
 * documents must NOT live in SharedPreferences, because
 * Android loads the whole prefs file into process memory
 * permanently, and must NOT be parsed whole (JsonMini builds
 * a complete tree before any caller counts or filters) just
 * to show a row of titles.
 *
 * Layout (data/TrackerStoreDisk owns the files, under
 * filesDir/tracker/ beside filesDir/iptv/):
 *   titles.jsonl — one line per tracked title (the home /
 *     search / match list). Counts ride the line, so a row,
 *     a badge, or a match NEVER opens an episode file.
 *   upnext.txt / continue.txt / movies.txt / foryou.txt —
 *     one line per section item, same line codec, no
 *     episode sets. The win for these small lists is
 *     getting them out of prefs, not paging them — but
 *     they are read the same line-by-line way.
 *   episodes/tv_<tmdbId>.json — one small file per show:
 *     that show's watched set (SxE keys, the existing
 *     LibraryLogic.epKey format) + muted flag. Read ONLY
 *     on Details, Play, or a mark for that title.
 *   index.json — counts per section, provider updated
 *     stamp, fetch stamp, content fingerprint, episode
 *     file count. Small by construction.
 *
 * Codec discipline: the IptvLogic.esc pipe codec (a line
 * decodes with a split, not a parser), exactly like
 * IptvStore. Every writer must refuse credential-shaped
 * payloads via HomeCache.containsCredentialPattern —
 * titles/poster paths/ids/counts only, never keys, URLs
 * with credentials, or tracker backup data.
 *
 * Fingerprint rule (audit §3): the fingerprint is over
 * CONTENT (ids + watched counts + titles + the provider
 * updated stamp), never over stamps + raw lengths. Two
 * documents of the same length with different watched
 * counts MUST fingerprint differently, and a fetch that
 * changes nothing but fetchedAt MUST NOT invalidate the
 * store — fetchedAt is deliberately NOT an input.
 */
object TrackerStore {

    /** One tracked title, home-consumed fields only. */
    data class Title(
        val type: String,
        val tmdbId: Long,
        val title: String,
        val poster: String,
        val backdrop: String,
        val watchedCount: Int,
        val planned: Boolean,
        val muted: Boolean,
        val nextSeason: Int,
        val nextEpisode: Int
    )

    /** One show's episode file: watched set + muted flag. */
    data class EpisodeFile(
        val muted: Boolean,
        val watched: Set<String>
    )

    /** The small index: everything a screen needs before any item is read. */
    data class Index(
        val titles: Int,
        val upnext: Int,
        val continueCount: Int,
        val movies: Int,
        val foryou: Int,
        val updated: Long,
        val fetchedAt: Long,
        val fingerprint: String,
        val episodeFiles: Int
    ) {
        val hasData: Boolean get() = titles > 0 || upnext > 0 || movies > 0
    }

    // ------------------------------------------------------------ field codec

    private fun fields(line: String, count: Int): List<String> {
        val parts = line.split('|')
        return List(count) { i -> if (i < parts.size) IptvLogic.unesc(parts[i]) else "" }
    }

    private fun enc(vararg values: String): String =
        values.joinToString("|") { IptvLogic.esc(it) }

    private fun num(s: String): Long = s.toLongOrNull() ?: 0L
    private fun flag(s: String): Boolean = s == "1"

    /** Encode one tracked title. Null when the payload trips the credential tripwire. */
    fun encodeTitle(t: Title): String? {
        val line = enc(
            t.type, t.tmdbId.toString(), t.title, t.poster, t.backdrop,
            t.watchedCount.toString(), if (t.planned) "1" else "0",
            if (t.muted) "1" else "0", t.nextSeason.toString(), t.nextEpisode.toString()
        )
        return if (HomeCache.containsCredentialPattern(line)) null else line
    }

    fun decodeTitle(line: String): Title? {
        if (line.isBlank()) return null
        val f = fields(line, 10)
        val id = num(f[1])
        if (id <= 0L || f[2].isBlank()) return null
        return Title(
            type = if (f[0] == "movie") "movie" else "tv",
            tmdbId = id, title = f[2], poster = f[3], backdrop = f[4],
            watchedCount = num(f[5]).toInt().coerceAtLeast(0),
            planned = flag(f[6]), muted = flag(f[7]),
            nextSeason = num(f[8]).toInt().coerceAtLeast(0),
            nextEpisode = num(f[9]).toInt().coerceAtLeast(0)
        )
    }

    fun encodeUpNext(i: TrackerLogic.UpNextItem): String? {
        val line = enc(
            i.tmdbId.toString(), i.title, i.sub, i.dateIso, i.poster,
            i.backdrop, i.overview, i.season.toString(), i.episode.toString(), i.type
        )
        return if (HomeCache.containsCredentialPattern(line)) null else line
    }

    fun decodeUpNext(line: String): TrackerLogic.UpNextItem? {
        if (line.isBlank()) return null
        val f = fields(line, 10)
        val id = num(f[0])
        if (id <= 0L || f[1].isBlank()) return null
        return TrackerLogic.UpNextItem(
            tmdbId = id, title = f[1], sub = f[2], dateIso = f[3],
            poster = f[4], backdrop = f[5], overview = f[6],
            season = num(f[7]).toInt(), episode = num(f[8]).toInt(),
            type = f[9].ifBlank { "tv" }
        )
    }

    fun encodeContinue(i: TrackerLogic.ContinueItem): String? {
        val line = enc(
            i.tmdbId.toString(), i.title, i.sub, i.watched.toString(),
            i.total.toString(), i.poster, i.backdrop, i.overview, i.type
        )
        return if (HomeCache.containsCredentialPattern(line)) null else line
    }

    fun decodeContinue(line: String): TrackerLogic.ContinueItem? {
        if (line.isBlank()) return null
        val f = fields(line, 9)
        val id = num(f[0])
        if (id <= 0L || f[1].isBlank()) return null
        return TrackerLogic.ContinueItem(
            tmdbId = id, title = f[1], sub = f[2],
            watched = num(f[3]).toInt(), total = num(f[4]).toInt(),
            poster = f[5], backdrop = f[6], overview = f[7],
            type = f[8].ifBlank { "tv" }
        )
    }

    fun encodeMovie(i: TrackerLogic.MovieItem): String? {
        val line = enc(
            i.tmdbId.toString(), i.title, i.releaseDate,
            if (i.planned) "1" else "0", i.poster, if (i.watched) "1" else "0"
        )
        return if (HomeCache.containsCredentialPattern(line)) null else line
    }

    fun decodeMovie(line: String): TrackerLogic.MovieItem? {
        if (line.isBlank()) return null
        val f = fields(line, 6)
        val id = num(f[0])
        if (id <= 0L || f[1].isBlank()) return null
        return TrackerLogic.MovieItem(
            tmdbId = id, title = f[1], releaseDate = f[2],
            planned = flag(f[3]), poster = f[4], watched = flag(f[5])
        )
    }

    fun encodeForYou(p: TrackerLogic.ForYouPick): String? {
        val line = enc(
            p.id.toString(), p.type, p.title, p.poster,
            p.voteAverage.toString(), p.reason,
            p.whyGenres.joinToString("\u001F"), p.score.toString()
        )
        return if (HomeCache.containsCredentialPattern(line)) null else line
    }

    fun decodeForYou(line: String): TrackerLogic.ForYouPick? {
        if (line.isBlank()) return null
        val f = fields(line, 8)
        val id = num(f[0])
        if (id <= 0L || f[2].isBlank()) return null
        return TrackerLogic.ForYouPick(
            id = id, type = f[1].ifBlank { "tv" }, title = f[2], poster = f[3],
            voteAverage = f[4].toDoubleOrNull() ?: 0.0, reason = f[5],
            whyGenres = if (f[6].isBlank()) emptyList() else f[6].split("\u001F").filter { it.isNotBlank() },
            score = f[7].toDoubleOrNull() ?: 0.0
        )
    }

    // ------------------------------------------------------------ episode files (small JSON, one show)

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

    /** Encode one show's episode file. Null when the payload trips the credential tripwire. */
    fun encodeEpisode(e: EpisodeFile): String? {
        val watched = e.watched.sorted().joinToString(",") { "\"${jsonEsc(it)}\"" }
        val json = "{\"muted\":${if (e.muted) "true" else "false"},\"watched\":[$watched]}"
        return if (HomeCache.containsCredentialPattern(json)) null else json
    }

    /** Decode one show's episode file; malformed reads as an empty set (fail-open, per-title retry). */
    fun decodeEpisode(json: String?): EpisodeFile? {
        if (json.isNullOrBlank()) return null
        val o = JsonMini.parse(json).asObj() ?: return null
        val watched = o["watched"].asArr()
            ?.mapNotNull { it.asStr() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        return EpisodeFile(muted = o["muted"].asBool() ?: false, watched = watched)
    }

    // ------------------------------------------------------------ index (small JSON)

    fun encodeIndex(idx: Index): String {
        return "{\"titles\":${idx.titles},\"upnext\":${idx.upnext}," +
            "\"continue\":${idx.continueCount},\"movies\":${idx.movies}," +
            "\"foryou\":${idx.foryou},\"updated\":${idx.updated}," +
            "\"fetchedAt\":${idx.fetchedAt}," +
            "\"fingerprint\":\"${jsonEsc(idx.fingerprint)}\"," +
            "\"episodeFiles\":${idx.episodeFiles}}"
    }

    /** Decode the index; null on any malformed input (the caller falls back, never empties the home). */
    fun decodeIndex(json: String?): Index? {
        if (json.isNullOrBlank()) return null
        val o = JsonMini.parse(json).asObj() ?: return null
        fun n(k: String): Long = (o[k] as? Number)?.toLong() ?: 0L
        val fp = o["fingerprint"].asStr() ?: return null
        return Index(
            titles = n("titles").toInt(), upnext = n("upnext").toInt(),
            continueCount = n("continue").toInt(), movies = n("movies").toInt(),
            foryou = n("foryou").toInt(), updated = n("updated"),
            fetchedAt = n("fetchedAt"), fingerprint = fp,
            episodeFiles = n("episodeFiles").toInt()
        )
    }

    // ------------------------------------------------------------ fingerprint (content, not lengths)

    /**
     * FNV-1a 64-bit over the canonical content: the provider
     * updated stamp + every section's ids, titles and watched
     * counts in writer order. fetchedAt is NOT an input — a
     * fetch that changes nothing must not invalidate the
     * store. Same-length documents with different counts (or
     * different titles) fingerprint differently by
     * construction; the old stamps+lengths fingerprint could
     * not tell them apart.
     */
    fun fingerprint(
        updated: Long,
        titles: List<Title>,
        upNext: List<TrackerLogic.UpNextItem>,
        cont: List<TrackerLogic.ContinueItem>,
        movies: List<TrackerLogic.MovieItem>,
        forYou: List<TrackerLogic.ForYouPick>
    ): String {
        val sb = StringBuilder()
        sb.append("u=").append(updated).append(';')
        for (t in titles) {
            sb.append("t:").append(t.tmdbId).append(':').append(t.title)
                .append(':').append(t.watchedCount).append(':')
                .append(if (t.planned) 1 else 0).append(':')
                .append(if (t.muted) 1 else 0).append(';')
        }
        for (i in upNext) sb.append("n:").append(i.tmdbId).append(':').append(i.title).append(';')
        for (i in cont) sb.append("c:").append(i.tmdbId).append(':').append(i.title).append(':').append(i.watched).append(';')
        for (i in movies) sb.append("m:").append(i.tmdbId).append(':').append(i.title).append(':').append(if (i.watched) 1 else 0).append(';')
        for (p in forYou) sb.append("f:").append(p.id).append(':').append(p.title).append(';')
        var h = -3750763034362895579L
        for (c in sb.toString()) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h.toString(16).padStart(16, '0')
    }

    /**
     * v3.7: the missed worker's input fingerprint — TV
     * title ids + watched counts + muted/planned flags, in
     * writer order. When this moves, the missed row's input
     * moved (a mark landed, a title joined/left), so the
     * stamp-gated full missed recompute must re-run; when
     * it matches and the missed file is fresh, the worker
     * skips the TMDB season fetches entirely. Same FNV-1a
     * shape as [fingerprint]; fetchedAt is not an input.
     */
    fun missedFingerprint(titles: List<Title>): String {
        val sb = StringBuilder()
        for (t in titles) {
            if (t.type != "tv") continue
            sb.append("t:").append(t.tmdbId).append(':').append(t.watchedCount)
                .append(':').append(if (t.planned) 1 else 0).append(':')
                .append(if (t.muted) 1 else 0).append(';')
        }
        var h = -3750763034362895579L
        for (c in sb.toString()) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h.toString(16).padStart(16, '0')
    }

    // ------------------------------------------------------------ paged line reading

    /**
     * Read one page from a lazily-pulled line sequence: skip
     * [offset] lines, decode at most [limit] more, then stop
     * WITHOUT pulling the next line. Returns the decoded
     * items and the exact number of lines consumed (skip +
     * decoded + malformed-skipped, never more than
     * offset + limit when the sequence has that many lines).
     * The caller owns the sequence — a file-backed sequence
     * therefore reads only the window from disk.
     */
    fun <T> readPage(
        lines: Sequence<String>,
        offset: Int,
        limit: Int,
        decode: (String) -> T?
    ): Pair<List<T>, Int> {
        val out = ArrayList<T>()
        var consumed = 0
        val it = lines.iterator()
        var skipped = 0
        while (skipped < offset && it.hasNext()) {
            it.next()
            skipped++
            consumed++
        }
        while (out.size < limit && it.hasNext()) {
            val line = it.next()
            consumed++
            decode(line)?.let { out.add(it) }
        }
        return out to consumed
    }

    /** Prefs eviction gate (v3.6 step 8): all three must hold, or the prefs snapshot keys stay. */
    fun evictionAllowed(storeValid: Boolean, paintedFromStore: Boolean, fetchLandedToStore: Boolean): Boolean =
        storeValid && paintedFromStore && fetchLandedToStore

    /** Diagnostics line for the store (counts only — never titles, ids are counts, never URLs). */
    fun diagLine(index: Index?, fallback: String): String {
        if (index == null) return "absent · fallback: $fallback"
        return "${index.titles} titles · ${index.episodeFiles} episode files · " +
            "index ${index.updated} · fallback: $fallback"
    }
}
