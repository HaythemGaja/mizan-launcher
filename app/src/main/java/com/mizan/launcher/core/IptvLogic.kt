package com.mizan.launcher.core

import java.net.URLEncoder

/**
 * IPTV core (v2.2, spec §20) — pure decisions, no Android imports.
 *
 * Models Haythem's own "Universal IPTV Player" web app as a native
 * data layer: an Xtream Codes account (server + username + password)
 * plus an optional M3U playlist (+ optional XMLTV URL). Everything
 * here is deterministic and sim-tested: server normalization, the
 * player_api.php URL shapes, the stream URL shapes (live / movie /
 * series episode / timeshift), the catalogue parsers (Xtream JSON
 * famously mixes strings and numbers for ids — both are accepted),
 * the M3U parser, the per-profile favorites + resume stores, the
 * cross-catalogue search and the IPTV home-row composition.
 *
 * CREDENTIAL HYGIENE: the account (and the M3U/EPG URLs, which may
 * embed credentials of their own) live ONLY in app prefs. The URL
 * builders below necessarily place the username/password in the
 * request path — that is the Xtream protocol — but the results are
 * used transiently for one HTTP call or one playback and are NEVER
 * written to diagnostics, logs, backups or any file (see
 * data/IptvClient, which sanitizes every error it records).
 */
object IptvLogic {

    const val SRC_XTREAM = "xtream"
    const val SRC_M3U = "m3u"

    const val KIND_LIVE = "live"
    const val KIND_VOD = "vod"
    const val KIND_SERIES = "series"
    const val KIND_EPISODE = "episode"

    // ------------------------------------------------------------ account

    data class Account(val server: String, val user: String, val pass: String) {
        val isConfigured: Boolean
            get() = server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    }

    /**
     * Normalize a typed server address: trim, drop trailing slashes,
     * add "http://" when no scheme was typed (his web player accepts
     * a bare host[:port] the same way). Case is preserved (paths on
     * some panels are case-sensitive); the scheme check is not.
     */
    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    fun account(server: String, user: String, pass: String): Account =
        Account(normalizeServer(server), user.trim(), pass)

    // ------------------------------------------------------------ URL building

    private fun encQuery(v: String): String =
        try { URLEncoder.encode(v, "UTF-8") } catch (e: Exception) { v }

    /** Path segment: escape only the characters that would break the path shape. */
    private fun pathSeg(v: String): String = v
        .replace("%", "%25").replace("/", "%2F").replace("?", "%3F")
        .replace("#", "%23").replace(" ", "%20")

    /**
     * player_api.php call: `{server}/player_api.php?username=..&password=..`
     * plus `&action=..` and any extra params (series_id, stream_id…).
     * A null action is the bare auth probe his player also makes.
     */
    fun apiUrl(account: Account, action: String?, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder()
        sb.append(account.server).append("/player_api.php?username=")
            .append(encQuery(account.user)).append("&password=").append(encQuery(account.pass))
        if (action != null) sb.append("&action=").append(encQuery(action))
        for ((k, v) in params) sb.append('&').append(encQuery(k)).append('=').append(encQuery(v))
        return sb.toString()
    }

    /** The account's XMLTV endpoint (credentials in the query — prefs-grade data). */
    fun xmltvUrl(account: Account): String =
        account.server + "/xmltv.php?username=" + encQuery(account.user) +
            "&password=" + encQuery(account.pass)

    /** Live: `{server}/live/{user}/{pass}/{streamId}.m3u8` — HLS, always. */
    fun liveUrl(account: Account, streamId: Long): String =
        account.server + "/live/" + pathSeg(account.user) + "/" + pathSeg(account.pass) +
            "/" + streamId + ".m3u8"

    /** VOD: `{server}/movie/{user}/{pass}/{id}.{container_extension}` (default mp4). */
    fun vodUrl(account: Account, id: Long, ext: String): String =
        account.server + "/movie/" + pathSeg(account.user) + "/" + pathSeg(account.pass) +
            "/" + id + "." + extOrDefault(ext, "mp4")

    /** Episode: `{server}/series/{user}/{pass}/{episodeId}.{ext}` (default mp4). */
    fun episodeUrl(account: Account, episodeId: String, ext: String): String =
        account.server + "/series/" + pathSeg(account.user) + "/" + pathSeg(account.pass) +
            "/" + pathSeg(episodeId) + "." + extOrDefault(ext, "mp4")

    /**
     * Catch-up / timeshift (v2.2 scope addition 5):
     * `{server}/timeshift/{user}/{pass}/{durationMin}/{startYmdHis}/{streamId}.ts`
     * where the start is the programme's absolute start formatted
     * yyyyMMddHHmmss in UTC. Panel conventions vary — this is the
     * standard Xtream shape and strictly best-effort (spec §20).
     */
    fun timeshiftUrl(account: Account, streamId: Long, startMs: Long, durationMin: Int): String =
        account.server + "/timeshift/" + pathSeg(account.user) + "/" + pathSeg(account.pass) +
            "/" + durationMin.coerceAtLeast(1) + "/" + IptvEpg.formatYmdHis(startMs) +
            "/" + streamId + ".ts"

    private fun extOrDefault(ext: String, fallback: String): String {
        val e = ext.trim().trimStart('.')
        return if (e.isEmpty() || e.contains('/') || e.contains(' ')) fallback else e
    }

    // ------------------------------------------------------------ models

    data class Category(val id: String, val name: String, val source: String, val kind: String) {
        /** Lock-store + filtering identity: "xtream:live:12", "m3u:vod:…". */
        val key: String get() = "$source:$kind:$id"
    }

    data class LiveChannel(
        val id: Long, val name: String, val icon: String,
        val categoryId: String, val categoryName: String,
        val epgId: String, val source: String, val directUrl: String,
        val tvArchive: Int, val archiveDays: Int
    ) {
        val catKey: String get() = "$source:${KIND_LIVE}:$categoryId"
        val key: String get() = "$source:${KIND_LIVE}:$id"
        val isXtream: Boolean get() = source == SRC_XTREAM
        /** Catch-up is possible only with the server's archive flag on. */
        val hasArchive: Boolean get() = isXtream && tvArchive > 0
    }

    data class VodItem(
        val id: Long, val name: String, val icon: String,
        val categoryId: String, val categoryName: String,
        val ext: String, val rating: String
    ) {
        val catKey: String get() = "$SRC_XTREAM:${KIND_VOD}:$categoryId"
        val key: String get() = "$SRC_XTREAM:${KIND_VOD}:$id"
    }

    data class SeriesItem(
        val id: Long, val name: String, val cover: String,
        val categoryId: String, val categoryName: String,
        val rating: String, val plot: String
    ) {
        val catKey: String get() = "$SRC_XTREAM:${KIND_SERIES}:$categoryId"
        val key: String get() = "$SRC_XTREAM:${KIND_SERIES}:$id"
    }

    data class Episode(
        val id: String, val title: String, val season: Int, val num: Int,
        val ext: String, val durationSecs: Int
    )

    data class Season(val number: Int, val episodes: List<Episode>)

    data class SeriesInfo(
        val seriesId: Long, val name: String, val cover: String,
        val plot: String, val seasons: List<Season>
    )

    /**
     * The merged catalogue snapshot the client caches: Xtream live /
     * VOD / series plus M3U channels folded into [live] and their
     * groups into [liveCats] (source-tagged; duplicates NOT merged).
     */
    data class Catalogues(
        val liveCats: List<Category> = emptyList(),
        val live: List<LiveChannel> = emptyList(),
        val vodCats: List<Category> = emptyList(),
        val vods: List<VodItem> = emptyList(),
        val seriesCats: List<Category> = emptyList(),
        val series: List<SeriesItem> = emptyList()
    ) {
        val hasData: Boolean
            get() = live.isNotEmpty() || vods.isNotEmpty() || series.isNotEmpty()
        val liveXtreamCount: Int get() = live.count { it.source == SRC_XTREAM }
        val liveM3uCount: Int get() = live.count { it.source == SRC_M3U }
    }

    // ------------------------------------------------------------ JSON helpers

    /** Xtream ids arrive as strings OR numbers; accept both. */
    fun strOf(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        is Boolean -> if (v) "1" else "0"
        else -> ""
    }

    fun longOf(v: Any?): Long = when (v) {
        null -> 0L
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    fun intOf(v: Any?): Int = longOf(v).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun asList(v: Any?): List<Map<String, Any?>> =
        (v as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    private fun nameById(cats: List<Category>): Map<String, String> =
        cats.associate { it.id to it.name }

    // ------------------------------------------------------------ Xtream parsers

    /** The auth probe: user_info.auth == 1 → true, == 0 → false, absent → null. */
    fun parseAuth(json: String?): Boolean? {
        val root = asMap(JsonMini.parse(json ?: return null))
        val info = asMap(root["user_info"])
        if (!info.containsKey("auth")) return null
        return longOf(info["auth"]) == 1L
    }

    fun parseCategories(json: String?, kind: String, source: String = SRC_XTREAM): List<Category> {
        val out = ArrayList<Category>()
        for (row in asList(JsonMini.parse(json ?: return emptyList()))) {
            val id = strOf(row["category_id"])
            val name = strOf(row["category_name"]).trim()
            if (name.isEmpty()) continue
            out.add(Category(id, name, source, kind))
        }
        return out
    }

    fun parseLive(json: String?, catNames: Map<String, String>): List<LiveChannel> {
        val out = ArrayList<LiveChannel>()
        for (row in asList(JsonMini.parse(json ?: return emptyList()))) {
            val id = longOf(row["stream_id"])
            val name = strOf(row["name"]).trim()
            if (id <= 0L || name.isEmpty()) continue
            val catId = strOf(row["category_id"])
            out.add(
                LiveChannel(
                    id = id, name = name, icon = strOf(row["stream_icon"]).trim(),
                    categoryId = catId, categoryName = catNames[catId] ?: "",
                    epgId = strOf(row["epg_channel_id"]).trim(),
                    source = SRC_XTREAM, directUrl = "",
                    tvArchive = intOf(row["tv_archive"]),
                    archiveDays = intOf(row["tv_archive_duration"])
                )
            )
        }
        return out
    }

    fun parseVods(json: String?, catNames: Map<String, String>): List<VodItem> {
        val out = ArrayList<VodItem>()
        for (row in asList(JsonMini.parse(json ?: return emptyList()))) {
            val id = longOf(row["stream_id"])
            val name = strOf(row["name"]).trim()
            if (id <= 0L || name.isEmpty()) continue
            val catId = strOf(row["category_id"])
            out.add(
                VodItem(
                    id = id, name = name, icon = strOf(row["stream_icon"]).trim(),
                    categoryId = catId, categoryName = catNames[catId] ?: "",
                    ext = strOf(row["container_extension"]).trim(),
                    rating = strOf(row["rating"]).trim()
                )
            )
        }
        return out
    }

    fun parseSeries(json: String?, catNames: Map<String, String>): List<SeriesItem> {
        val out = ArrayList<SeriesItem>()
        for (row in asList(JsonMini.parse(json ?: return emptyList()))) {
            val id = longOf(row["series_id"])
            val name = strOf(row["name"]).trim()
            if (id <= 0L || name.isEmpty()) continue
            val catId = strOf(row["category_id"])
            out.add(
                SeriesItem(
                    id = id, name = name, cover = strOf(row["cover"]).trim(),
                    categoryId = catId, categoryName = catNames[catId] ?: "",
                    rating = strOf(row["rating"]).trim(),
                    plot = strOf(row["plot"]).trim()
                )
            )
        }
        return out
    }

    /**
     * get_series_info: `info` carries name/cover/plot; `episodes` is
     * an object keyed by season number, each value an array of
     * episode objects (id, episode_num, title, container_extension,
     * info.duration_secs). Seasons sort numerically (keys are
     * strings — "10" must come after "2"), episodes by number.
     * Episodes with a blank id can never play and are skipped.
     */
    fun parseSeriesInfo(json: String?, seriesId: Long): SeriesInfo? {
        val root = asMap(JsonMini.parse(json ?: return null))
        if (root.isEmpty()) return null
        val info = asMap(root["info"])
        val bySeason = asMap(root["episodes"])
        val seasons = ArrayList<Season>()
        for ((seasonKey, rowsRaw) in bySeason) {
            val seasonNum = seasonKey.trim().toIntOrNull() ?: continue
            val eps = ArrayList<Episode>()
            for (row in asList(rowsRaw)) {
                val epId = strOf(row["id"]).trim()
                if (epId.isEmpty()) continue
                val epInfo = asMap(row["info"])
                eps.add(
                    Episode(
                        id = epId,
                        title = strOf(row["title"]).trim(),
                        season = seasonNum,
                        num = intOf(row["episode_num"]),
                        ext = strOf(row["container_extension"]).trim(),
                        durationSecs = intOf(epInfo["duration_secs"])
                    )
                )
            }
            if (eps.isNotEmpty()) {
                seasons.add(Season(seasonNum, eps.sortedWith(compareBy({ it.num }, { it.id }))))
            }
        }
        seasons.sortBy { it.number }
        return SeriesInfo(
            seriesId = seriesId,
            name = strOf(info["name"]).trim(),
            cover = strOf(info["cover"]).trim(),
            plot = strOf(info["plot"]).trim(),
            seasons = seasons
        )
    }

    // ------------------------------------------------------------ M3U parser

    /**
     * M3U playlist: `#EXTINF:-1 tvg-id=".." tvg-logo=".."
     * group-title="..",Display Name` followed by the stream URL on
     * the next non-directive line. Returns (categories, channels):
     * groups become [SRC_M3U] live categories in first-seen order
     * (a channel without a group lands in the "" category, named
     * "Playlist" by callers); duplicates are kept as-is.
     */
    fun parseM3u(text: String?): Pair<List<Category>, List<LiveChannel>> {
        if (text.isNullOrBlank()) return Pair(emptyList(), emptyList())
        val cats = ArrayList<Category>()
        val seenCats = HashSet<String>()
        val channels = ArrayList<LiveChannel>()
        var pendingName = ""
        var pendingIcon = ""
        var pendingEpg = ""
        var pendingGroup: String? = null
        fun flush(url: String) {
            val group = pendingGroup ?: ""
            if (group !in seenCats) {
                seenCats.add(group)
                cats.add(Category(group, group.ifEmpty { "Playlist" }, SRC_M3U, KIND_LIVE))
            }
            val name = pendingName.ifEmpty { url.substringAfterLast('/').ifEmpty { "Channel" } }
            channels.add(
                LiveChannel(
                    id = (url.hashCode().toLong() and 0x7FFFFFFFL).coerceAtLeast(1L),
                    name = name, icon = pendingIcon,
                    categoryId = group,
                    categoryName = group.ifEmpty { "Playlist" },
                    epgId = pendingEpg, source = SRC_M3U, directUrl = url,
                    tvArchive = 0, archiveDays = 0
                )
            )
            pendingName = ""; pendingIcon = ""; pendingEpg = ""; pendingGroup = null
        }
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                pendingName = extinfName(line)
                pendingIcon = extinfAttr(line, "tvg-logo")
                pendingEpg = extinfAttr(line, "tvg-id")
                pendingGroup = extinfAttr(line, "group-title")
            } else if (line.startsWith("#")) {
                continue // other directives (#EXTM3U, #EXTVLCOPT…) carry no channel
            } else {
                flush(line)
            }
        }
        return Pair(cats, channels)
    }

    /** Attribute value from an #EXTINF line: `key="value"` (quote-aware enough for playlists). */
    private fun extinfAttr(line: String, attr: String): String {
        val needle = "$attr=\""
        val start = line.indexOf(needle)
        if (start < 0) return ""
        val from = start + needle.length
        val end = line.indexOf('"', from)
        return if (end < 0) "" else line.substring(from, end).trim()
    }

    /** Display name: the text after the first comma that is OUTSIDE quotes. */
    private fun extinfName(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    // ------------------------------------------------------------ field codec

    /** Escape for the |-separated line codecs below: %, | and newline. */
    fun esc(s: String): String = s
        .replace("%", "%25").replace("|", "%7C")
        .replace("\n", "%0A").replace("\r", "%0D")

    fun unesc(s: String): String = s
        .replace("%0D", "\r").replace("%0A", "\n")
        .replace("%7C", "|").replace("%25", "%")

    private fun fields(line: String, count: Int): List<String> {
        val parts = line.split('|')
        return List(count) { i -> if (i < parts.size) unesc(parts[i]) else "" }
    }

    // ------------------------------------------------------------ favorites (per profile)

    /**
     * One favorite: any live channel (either source), movie or
     * series. Carries everything playback needs (ext / direct URL)
     * so a favorite still plays from a stale catalogue snapshot.
     */
    data class FavEntry(
        val kind: String, val source: String, val id: String,
        val name: String, val icon: String, val ext: String,
        val catKey: String, val directUrl: String
    ) {
        val key: String get() = "$source:$kind:$id"
    }

    fun favOf(channel: LiveChannel): FavEntry = FavEntry(
        KIND_LIVE, channel.source, channel.id.toString(), channel.name,
        channel.icon, "", channel.catKey, channel.directUrl
    )

    fun favOf(vod: VodItem): FavEntry = FavEntry(
        KIND_VOD, SRC_XTREAM, vod.id.toString(), vod.name,
        vod.icon, vod.ext, vod.catKey, ""
    )

    fun favOf(series: SeriesItem): FavEntry = FavEntry(
        KIND_SERIES, SRC_XTREAM, series.id.toString(), series.name,
        series.cover, "", series.catKey, ""
    )

    fun encodeFavorites(list: List<FavEntry>): String = list.joinToString("\n") { e ->
        listOf(e.kind, e.source, e.id, e.ext, e.catKey, e.name, e.icon, e.directUrl)
            .joinToString("|") { esc(it) }
    }

    fun decodeFavorites(raw: String?): List<FavEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<FavEntry>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val f = fields(line, 8)
            if (f[0] !in listOf(KIND_LIVE, KIND_VOD, KIND_SERIES) || f[2].isEmpty()) continue
            out.add(FavEntry(f[0], f[1], f[2], f[5], f[6], f[3], f[4], f[7]))
        }
        return out
    }

    fun isFavorite(list: List<FavEntry>, key: String): Boolean = list.any { it.key == key }

    /** Add (at the end — stored order is display order) or remove by identity key. */
    fun toggleFavorite(list: List<FavEntry>, entry: FavEntry): List<FavEntry> =
        if (isFavorite(list, entry.key)) list.filterNot { it.key == entry.key }
        else list + entry

    // ------------------------------------------------------------ resume store (per profile)

    /** Below this a position is not worth offering as a resume. */
    const val RESUME_MIN_MS = 5_000L

    /** Within this of the end (or past 95%) a title counts as finished. */
    const val END_MARGIN_MS = 15_000L

    const val RESUME_CAP = 50
    const val CONTINUE_CAP = 12

    data class ResumeEntry(
        val key: String, val kind: String, val id: String,
        val title: String, val icon: String, val meta: String,
        val posMs: Long, val durMs: Long, val updatedAt: Long,
        val catKey: String, val ext: String, val directUrl: String
    ) {
        val progress: Float
            get() = if (durMs > 0) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else -1f
    }

    fun resumeKey(kind: String, source: String, id: String): String = "$source:$kind:$id"

    fun shouldClear(posMs: Long, durMs: Long): Boolean {
        if (durMs <= 0L || posMs <= 0L) return false
        if (posMs >= durMs - END_MARGIN_MS) return true
        return posMs * 20L >= durMs * 19L // ≥ 95%
    }

    fun encodeResume(list: List<ResumeEntry>): String = list.joinToString("\n") { e ->
        listOf(
            e.key, e.kind, e.id, e.posMs.toString(), e.durMs.toString(),
            e.updatedAt.toString(), e.catKey, e.ext, e.directUrl, e.title, e.icon, e.meta
        ).joinToString("|") { esc(it) }
    }

    fun decodeResume(raw: String?): List<ResumeEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<ResumeEntry>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val f = fields(line, 12)
            if (f[0].isEmpty()) continue
            out.add(
                ResumeEntry(
                    key = f[0], kind = f[1], id = f[2], title = f[9], icon = f[10],
                    meta = f[11], posMs = f[3].toLongOrNull() ?: 0L,
                    durMs = f[4].toLongOrNull() ?: 0L,
                    updatedAt = f[5].toLongOrNull() ?: 0L,
                    catKey = f[6], ext = f[7], directUrl = f[8]
                )
            )
        }
        return out
    }

    /**
     * Record a position: replaces any entry with the same key; a
     * finished (or never-started) position REMOVES the entry.
     * Newest first, capped at [RESUME_CAP].
     */
    fun withResume(list: List<ResumeEntry>, entry: ResumeEntry): List<ResumeEntry> {
        val rest = list.filterNot { it.key == entry.key }
        if (entry.posMs <= 0L || shouldClear(entry.posMs, entry.durMs)) return rest
        return (listOf(entry) + rest).sortedByDescending { it.updatedAt }.take(RESUME_CAP)
    }

    fun withoutResume(list: List<ResumeEntry>, key: String): List<ResumeEntry> =
        list.filterNot { it.key == key }

    fun resumeFor(list: List<ResumeEntry>, key: String): ResumeEntry? =
        list.firstOrNull { it.key == key && it.posMs >= RESUME_MIN_MS && !shouldClear(it.posMs, it.durMs) }

    /** The Continue Watching list: movies + episodes only (live and
     * timeshift plays never resume), resumable, newest first, capped. */
    fun continueList(list: List<ResumeEntry>): List<ResumeEntry> =
        list.filter {
            (it.kind == KIND_VOD || it.kind == KIND_EPISODE) &&
                it.posMs >= RESUME_MIN_MS && !shouldClear(it.posMs, it.durMs)
        }
            .sortedByDescending { it.updatedAt }
            .take(CONTINUE_CAP)

    // ------------------------------------------------------------ search

    data class SearchResults(
        val live: List<LiveChannel>,
        val vods: List<VodItem>,
        val series: List<SeriesItem>
    ) {
        val isEmpty: Boolean get() = live.isEmpty() && vods.isEmpty() && series.isEmpty()
    }

    /** Case-insensitive substring match across all three catalogues; hidden categories excluded. */
    fun search(cats: Catalogues, query: String, hiddenCatKeys: Set<String>): SearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        return SearchResults(
            live = cats.live.filter { it.catKey !in hiddenCatKeys && it.name.lowercase().contains(q) },
            vods = cats.vods.filter { it.catKey !in hiddenCatKeys && it.name.lowercase().contains(q) },
            series = cats.series.filter { it.catKey !in hiddenCatKeys && it.name.lowercase().contains(q) }
        )
    }

    // ------------------------------------------------------------ EPG lookup

    /** A channel's programme list: by its EPG id, falling back to its numeric id as a string. */
    fun epgFor(index: Map<String, List<IptvEpg.Programme>>, channel: LiveChannel): List<IptvEpg.Programme> =
        index[channel.epgId]?.takeIf { it.isNotEmpty() }
            ?: index[channel.id.toString()]
            ?: emptyList()

    // ------------------------------------------------------------ home composition

    enum class RowKind { FAVORITES, LIVE, CONTINUE, MOVIES, SERIES }

    data class Card(
        val kind: String, val key: String, val id: String,
        val title: String, val image: String, val meta: String,
        val nowTitle: String, val progress: Float,
        val catKey: String, val source: String,
        val ext: String, val directUrl: String
    )

    data class HomeRow(val kind: RowKind, val title: String, val cards: List<Card>)

    const val ROW_CARD_CAP = 24
    const val LIVE_ROW_CAP = 3

    /**
     * The IPTV home, composed (spec §20.3): Favorites first, then
     * one row per live category (first [LIVE_ROW_CAP] non-empty,
     * visible ones), Continue Watching, Movies, Series. Items whose
     * category is hidden (pattern-locked, see IptvLocks) drop out;
     * rows left empty drop out. Live cards carry the EPG NOW title +
     * progress when [epg] has data for the channel. Pure and total:
     * no data in → no rows out (the view adds the empty-state line
     * and the app rows itself).
     */
    fun homeRows(
        cats: Catalogues,
        favorites: List<FavEntry>,
        resume: List<ResumeEntry>,
        hiddenCatKeys: Set<String>,
        epg: Map<String, List<IptvEpg.Programme>> = emptyMap(),
        nowMs: Long = 0L
    ): List<HomeRow> {
        val rows = ArrayList<HomeRow>()

        val favCards = favorites.filter { it.catKey !in hiddenCatKeys }.map { e ->
            Card(e.kind, e.key, e.id, e.name, e.icon, "", "", -1f, e.catKey, e.source, e.ext, e.directUrl)
        }
        if (favCards.isNotEmpty()) rows.add(HomeRow(RowKind.FAVORITES, "Favorites", favCards))

        val liveRows = ArrayList<HomeRow>()
        val grouped = HashSet<String>()
        for (cat in cats.liveCats) {
            if (cat.key in hiddenCatKeys) continue
            val channels = cats.live.filter { it.catKey == cat.key }
            if (channels.isEmpty()) continue
            grouped.add(cat.key)
            liveRows.add(
                HomeRow(RowKind.LIVE, cat.name, channels.take(ROW_CARD_CAP).map { ch ->
                    liveCard(ch, epg, nowMs)
                })
            )
            if (liveRows.size >= LIVE_ROW_CAP) break
        }
        if (liveRows.isEmpty()) {
            // No usable categories at all: one flat Live row with
            // whatever visible channels exist.
            val visible = cats.live.filter { it.catKey !in hiddenCatKeys }
            if (visible.isNotEmpty()) {
                liveRows.add(
                    HomeRow(RowKind.LIVE, "Live TV", visible.take(ROW_CARD_CAP).map { ch ->
                        liveCard(ch, epg, nowMs)
                    })
                )
            }
        } else {
            // Channels in categories beyond the cap (or ungrouped)
            // stay reachable from the browser; home keeps it light.
            val rest = cats.live.filter {
                it.catKey !in grouped && it.catKey !in hiddenCatKeys &&
                    cats.liveCats.none { c -> c.key == it.catKey }
            }
            if (rest.isNotEmpty() && liveRows.size < LIVE_ROW_CAP) {
                liveRows.add(
                    HomeRow(RowKind.LIVE, "More live", rest.take(ROW_CARD_CAP).map { ch ->
                        liveCard(ch, epg, nowMs)
                    })
                )
            }
        }
        rows.addAll(liveRows)

        val contCards = continueList(resume).filter { it.catKey !in hiddenCatKeys }.map { e ->
            Card(
                e.kind, e.key, e.id, e.title, e.icon, e.meta, "", e.progress,
                e.catKey, e.key.substringBefore(':'), e.ext, e.directUrl
            )
        }
        if (contCards.isNotEmpty()) rows.add(HomeRow(RowKind.CONTINUE, "Continue watching", contCards))

        val movieCards = cats.vods.filter { it.catKey !in hiddenCatKeys }.take(ROW_CARD_CAP).map { v ->
            Card(
                KIND_VOD, v.key, v.id.toString(), v.name, v.icon,
                if (v.rating.isNotEmpty()) "★ " + v.rating else "", "", -1f,
                v.catKey, SRC_XTREAM, v.ext, ""
            )
        }
        if (movieCards.isNotEmpty()) rows.add(HomeRow(RowKind.MOVIES, "Movies", movieCards))

        val seriesCards = cats.series.filter { it.catKey !in hiddenCatKeys }.take(ROW_CARD_CAP).map { s ->
            Card(
                KIND_SERIES, s.key, s.id.toString(), s.name, s.cover,
                if (s.rating.isNotEmpty()) "★ " + s.rating else "", "", -1f,
                s.catKey, SRC_XTREAM, "", ""
            )
        }
        if (seriesCards.isNotEmpty()) rows.add(HomeRow(RowKind.SERIES, "Series", seriesCards))

        return rows
    }

    private fun liveCard(
        ch: LiveChannel,
        epg: Map<String, List<IptvEpg.Programme>>,
        nowMs: Long
    ): Card {
        var nowTitle = ""
        var progress = -1f
        if (epg.isNotEmpty() && nowMs > 0L) {
            val (now, _) = IptvEpg.nowNext(epgFor(epg, ch), nowMs)
            if (now != null) {
                nowTitle = now.title
                progress = IptvEpg.progress(now, nowMs)
            }
        }
        return Card(
            KIND_LIVE, ch.key, ch.id.toString(), ch.name, ch.icon,
            ch.categoryName, nowTitle, progress, ch.catKey, ch.source, "", ch.directUrl
        )
    }
}
