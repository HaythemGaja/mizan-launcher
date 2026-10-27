package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvLocks
import com.mizan.launcher.core.IptvStore
import com.mizan.launcher.domain.EpgProgramme
import com.mizan.launcher.domain.IptvCatalogueStatus
import com.mizan.launcher.domain.IptvCategory
import com.mizan.launcher.domain.IptvChannel
import com.mizan.launcher.domain.IptvRepository
import com.mizan.launcher.domain.PlayItem
import com.mizan.launcher.domain.PlayKind
import com.mizan.launcher.domain.SeriesEpisode
import com.mizan.launcher.domain.SeriesItem
import com.mizan.launcher.domain.VodItem

/**
 * IPTV repository: Xtream + M3U sources kept UNMERGED (MAP §5) —
 * channels join Live rows but sources are never de-duplicated.
 * Refresh is on request only (never on home build); ingest swaps
 * atomically only when every attempted endpoint succeeded.
 * Favorites/resume/locks are per profile and survive source clears.
 *
 * Domain gap (reported): getLiveCategories() returns id→name
 * pairs with no source, so an Xtream and an M3U category sharing
 * a numeric id would collide. Mitigation: M3U category ids are
 * prefixed "m3u:" in that list and in getLiveChannels(); Xtream
 * ids pass through unchanged.
 */
class IptvRepositoryImpl(ctx: Context) : IptvRepository {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    private val disk = IptvStoreDisk(ctx)
    private val sessionStartMs = System.currentTimeMillis()
    @Volatile private var failureClass: String? = null
    // Lazily built only when the Details fallback / match lookup is
    // actually used; the engine reads the disk store directly, so
    // there is no repo→engine→repo cycle.
    private val matchEngine by lazy { WatchMatchEngine(context) }

    private fun hiddenCatKeys(): Set<String> {
        val cats = allCategories()
        return IptvLocks.hiddenKeys(cats, prefs.iptvLockedCats(), prefs.iptvUnlockStamps(),
            prefs.unlockGraceMin(), System.currentTimeMillis(), sessionStartMs,
            prefs.arrowPin().isNotBlank())
    }
    private fun allCategories(): List<IptvLogic.Category> {
        // Categories are re-derived from stored items (cat id+name),
        // since the old tree stored categories inside the catalogue
        // parse, not as a separate file.
        val out = LinkedHashMap<String, IptvLogic.Category>()
        for (c in disk.live()) out[c.catKey] = IptvLogic.Category(c.categoryId, c.categoryName, c.source, IptvLogic.KIND_LIVE)
        for (v in disk.vod()) out[v.catKey] = IptvLogic.Category(v.categoryId, v.categoryName, IptvLogic.SRC_XTREAM, IptvLogic.KIND_VOD)
        for (s in disk.series()) out[s.catKey] = IptvLogic.Category(s.categoryId, s.categoryName, IptvLogic.SRC_XTREAM, IptvLogic.KIND_SERIES)
        return out.values.toList()
    }
    private fun isHidden(catKey: String): Boolean = catKey in hiddenCatKeys()

    override fun getStatus(): IptvCatalogueStatus {
        val (l, v, s) = disk.counts()
        return IptvCatalogueStatus(l, v, s, disk.lastUpdateMs(), failureClass)
    }

    override fun refresh() {
        val results = ArrayList<IptvStore.EndpointResult>()
        val account = prefs.iptvAccount()
        var live: List<IptvLogic.LiveChannel>? = null
        var vod: List<IptvLogic.VodItem>? = null
        var series: List<IptvLogic.SeriesItem>? = null
        var epg: List<IptvEpg.Programme>? = null
        if (account.server.isNotBlank() && account.user.isNotBlank() && account.pass.isNotBlank()) {
            val catNames = HashMap<String, String>()
            fun fetch(action: String): String? {
                val r = HttpUtil.get(IptvLogic.apiUrl(account, action))
                results.add(IptvStore.EndpointResult(true, r.code in 200..299 && !r.body.isNullOrBlank()))
                return if (r.code in 200..299) r.body else null
            }
            val liveCats = fetch("get_live_categories")?.let { IptvLogic.parseCategories(it, IptvLogic.KIND_LIVE) } ?: emptyList()
            val vodCats = fetch("get_vod_categories")?.let { IptvLogic.parseCategories(it, IptvLogic.KIND_VOD) } ?: emptyList()
            val serCats = fetch("get_series_categories")?.let { IptvLogic.parseCategories(it, IptvLogic.KIND_SERIES) } ?: emptyList()
            for (c in liveCats + vodCats + serCats) catNames[c.id] = c.name
            live = fetch("get_live_streams")?.let { IptvLogic.parseLive(it, catNames) }
            vod = fetch("get_vod_streams")?.let { IptvLogic.parseVods(it, catNames) }
            series = fetch("get_series")?.let { IptvLogic.parseSeries(it, catNames) }
            val epgUrl = IptvLogic.xmltvUrl(account)
            val epgResp = HttpUtil.get(epgUrl)
            results.add(IptvStore.EndpointResult(true, epgResp.code in 200..299))
            epg = epgResp.body?.let { IptvEpg.parseXmltv(it).values.flatten()
                .filter { IptvEpg.inGuideWindow(it, System.currentTimeMillis()) } }
        }
        val m3uUrl = prefs.m3uUrl()
        var m3uChannels: List<IptvLogic.LiveChannel>? = null
        if (m3uUrl.isNotBlank()) {
            val r = HttpUtil.get(m3uUrl)
            results.add(IptvStore.EndpointResult(true, r.code in 200..299))
            if (r.code in 200..299 && !r.body.isNullOrBlank()) {
                val (cats, chans) = IptvLogic.parseM3u(r.body)
                m3uChannels = chans
            }
        }
        val epgUrl2 = prefs.epgUrl()
        if (epgUrl2.isNotBlank() && epg == null) {
            val r = HttpUtil.get(epgUrl2)
            results.add(IptvStore.EndpointResult(true, r.code in 200..299))
            if (r.code in 200..299) epg = r.body?.let { IptvEpg.parseXmltv(it).values.flatten() }
        }
        if (results.isEmpty()) return // nothing configured: no-op, not a failure
        if (!IptvStore.shouldSwapCatalogue(results)) {
            failureClass = "http"
            return
        }
        failureClass = null
        val allLive = ArrayList<IptvLogic.LiveChannel>()
        if (live != null) allLive.addAll(live)
        if (m3uChannels != null) allLive.addAll(m3uChannels)
        if (live != null || m3uChannels != null) disk.writeLive(allLive)
        if (vod != null) disk.writeVod(vod)
        if (series != null) disk.writeSeries(series)
        if (epg != null) disk.writeEpg(epg)
        val (lc, vc, sc) = disk.counts()
        disk.writeIndex(IptvStore.Index(System.currentTimeMillis(), 0, lc, vc, sc, 0L, 0L, 0L,
            epg?.map { it.channelId }?.toSet()?.size ?: 0, System.currentTimeMillis(), emptyList()))
    }

    private fun channelNow(channel: IptvLogic.LiveChannel): Pair<String?, Float?> {
        val progs = disk.epgFor(channel.epgId.ifBlank { channel.id.toString() })
        val now = System.currentTimeMillis()
        val (cur, _) = IptvEpg.nowNext(progs, now)
        return if (cur == null) null to null
        else cur.title to IptvEpg.progress(cur, now)
    }

    override fun getLiveChannels(categoryId: String?): List<IptvChannel> {
        val hidden = hiddenCatKeys()
        return disk.live().filter { ch ->
            if (hidden.contains(ch.catKey)) return@filter false
            when {
                categoryId == null -> true
                categoryId.startsWith("m3u:") ->
                    ch.source == IptvLogic.SRC_M3U && ch.categoryId == categoryId.removePrefix("m3u:")
                else -> ch.source == IptvLogic.SRC_XTREAM && ch.categoryId == categoryId
            }
        }.map { ch ->
            val (nowTitle, prog) = channelNow(ch)
            val url = ch.directUrl.ifBlank { IptvLogic.liveUrl(prefs.iptvAccount(), ch.id) }
            IptvChannel(ch.id.toString(), ch.name, ch.categoryId, ch.categoryName, url,
                ch.icon.takeIf { it.isNotBlank() }, nowTitle, prog,
                ch.tvArchive == 1, ch.source)
        }.take(IptvLogic.GRID_CAP * 10)
    }

    override fun getLiveCategories(): List<Pair<String, String>> {
        val hidden = hiddenCatKeys()
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (c in allCategories().filter { it.kind == IptvLogic.KIND_LIVE && it.key !in hidden }) {
            val id = if (c.source == IptvLogic.SRC_M3U) "m3u:${c.id}" else c.id
            if (seen.add(id)) out.add(id to c.name)
        }
        // Favorites first (MAP §1.7): categories holding favorites sort first.
        val favCats = prefs.iptvFavorites().map { it.catKey }.toSet()
        return out.sortedWith(compareBy<Pair<String, String>> { p ->
            val key = if (p.first.startsWith("m3u:")) "m3u:live:${p.first.removePrefix("m3u:")}"
                else "xtream:live:${p.first}"
            if (key in favCats) 0 else 1
        }.thenBy { it.second.lowercase() })
    }

    // Phase 2D canonical category list (domain gap (a)): the
    // source-qualified id is the same id getLiveCategories()
    // returns, now with its source attached — no collision.
    override fun getLiveCategoriesDetailed(): List<IptvCategory> {
        val hidden = hiddenCatKeys()
        val favCats = prefs.iptvFavorites().map { it.catKey }.toSet()
        val out = ArrayList<IptvCategory>()
        val seen = HashSet<String>()
        for (c in allCategories().filter { it.kind == IptvLogic.KIND_LIVE && it.key !in hidden }) {
            val id = if (c.source == IptvLogic.SRC_M3U) "m3u:${c.id}" else c.id
            if (seen.add(id)) out.add(IptvCategory(id, c.name, c.source))
        }
        return out.sortedWith(compareBy<IptvCategory> { c ->
            val key = if (c.id.startsWith("m3u:")) "m3u:live:${c.id.removePrefix("m3u:")}"
                else "xtream:live:${c.id}"
            if (key in favCats) 0 else 1
        }.thenBy { it.name.lowercase() })
    }

    // Phase 2D Details fallback lookup (UI gap (b)): the match
    // cache is recomputed here when empty, so a first Details
    // visit still resolves; worker thread only.
    override fun iptvSeriesForShow(tmdbId: Long): String? {
        return try {
            var m = matchEngine.matchFor(tmdbId)
            if (m == null) m = matchEngine.computeMatches()[tmdbId]
            if (m != null && m.type == "tv") m.iptvId.toString() else null
        } catch (e: Throwable) { null }
    }

    private fun resumeFor(key: String): IptvLogic.ResumeEntry? =
        prefs.iptvResume().firstOrNull { it.key == key }

    override fun getVod(categoryId: String?): List<VodItem> {
        val hidden = hiddenCatKeys()
        return disk.vod().filter { v ->
            !hidden.contains(v.catKey) && (categoryId == null || v.categoryId == categoryId)
        }.map { v ->
            val r = resumeFor(v.key)
            val pos = r?.posMs
            val finished = r != null && r.durMs > 0 &&
                (r.posMs >= r.durMs - 15_000L || r.posMs * 100L / r.durMs >= 95L)
            VodItem(v.id.toString(), v.name, v.categoryId,
                IptvLogic.vodUrl(prefs.iptvAccount(), v.id, v.ext.ifBlank { "mp4" }),
                v.ext.ifBlank { "mp4" }, v.rating.takeIf { it.isNotBlank() },
                pos?.takeIf { it >= IptvLogic.RESUME_MIN_MS }, finished)
        }
    }

    override fun getSeries(categoryId: String?): List<SeriesItem> {
        val hidden = hiddenCatKeys()
        return disk.series().filter { s ->
            !hidden.contains(s.catKey) && (categoryId == null || s.categoryId == categoryId)
        }.map { SeriesItem(it.id.toString(), it.name, it.categoryId, it.cover.takeIf { c -> c.isNotBlank() }) }
    }

    override fun getSeriesEpisodes(seriesId: String): List<SeriesEpisode> {
        val id = seriesId.toLongOrNull() ?: return emptyList()
        val json = disk.seriesInfo(id) ?: run {
            val account = prefs.iptvAccount()
            if (account.server.isBlank()) return emptyList()
            val r = HttpUtil.get(IptvLogic.apiUrl(account, "get_series_info",
                mapOf("series_id" to id.toString())))
            if (r.code in 200..299 && !r.body.isNullOrBlank()) {
                disk.writeSeriesInfo(id, r.body!!)
                r.body
            } else null
        } ?: return emptyList()
        val info = IptvLogic.parseSeriesInfo(json, id) ?: return emptyList()
        val out = ArrayList<SeriesEpisode>()
        for (season in info.seasons.sortedBy { it.number }) {
            for (ep in season.episodes.sortedBy { it.num }) {
                val key = IptvLogic.resumeKey(IptvLogic.KIND_EPISODE, IptvLogic.SRC_XTREAM, ep.id)
                val r = resumeFor(key)
                out.add(SeriesEpisode(ep.id, season.number, ep.num, ep.title,
                    IptvLogic.episodeUrl(prefs.iptvAccount(), ep.id, ep.ext.ifBlank { "mp4" }),
                    ep.durationSecs.toLong().takeIf { it > 0 }, "",
                    r?.posMs?.takeIf { it >= IptvLogic.RESUME_MIN_MS }))
            }
        }
        return out
    }

    override fun getGuide(channelId: String): List<EpgProgramme> {
        val stored = disk.epgFor(channelId)
        if (stored.isNotEmpty()) {
            return stored.sortedBy { it.startMs }.map {
                EpgProgramme(it.channelId, it.title, it.startMs, it.stopMs, "")
            }
        }
        // Short-EPG on demand (MAP §1.8): per-channel, times read as UTC.
        val account = prefs.iptvAccount()
        if (account.server.isBlank()) return emptyList()
        val sid = channelId.toLongOrNull() ?: return emptyList()
        val r = HttpUtil.get(IptvLogic.apiUrl(account, "get_short_epg",
            mapOf("stream_id" to sid.toString())))
        if (r.code !in 200..299 || r.body.isNullOrBlank()) return emptyList()
        return IptvEpg.parseShortEpg(r.body, channelId).map {
            EpgProgramme(it.channelId, it.title, it.startMs, it.stopMs, "")
        }
    }

    override fun search(query: String): Triple<List<IptvChannel>, List<VodItem>, List<SeriesItem>> {
        val q = query.lowercase()
        val chans = getLiveChannels(null).filter { it.name.lowercase().contains(q) }.take(IptvLogic.GRID_CAP)
        val vods = getVod(null).filter { it.name.lowercase().contains(q) }.take(IptvLogic.GRID_CAP)
        val sers = getSeries(null).filter { it.name.lowercase().contains(q) }.take(IptvLogic.GRID_CAP)
        return Triple(chans, vods, sers)
    }

    override fun getFavorites(): List<String> = prefs.iptvFavorites().map { it.key }
    override fun setFavorite(identityKey: String, favorite: Boolean) {
        val cur = prefs.iptvFavorites().toMutableList()
        if (favorite) {
            if (cur.none { it.key == identityKey }) {
                // Identity key alone cannot reconstruct name/icon; the
                // UI worker should pass full entries via setFavoritesFull.
                cur.add(IptvLogic.FavEntry("live", IptvLogic.SRC_XTREAM, identityKey, "", "", "", "", ""))
            }
        } else cur.removeAll { it.key == identityKey }
        prefs.setIptvFavorites(cur)
    }
    /** Full-entry favorite write (preferred by UI; keeps names for the tab). */
    fun setFavoritesFull(list: List<IptvLogic.FavEntry>) = prefs.setIptvFavorites(list)

    // Phase 2D domain full-entry overloads (data gap (b)): the
    // identity key is "source:kind:id"; name/title are stored so
    // the Favorites tab and resume rows keep display metadata
    // instead of empty strings.
    private fun splitKey(identityKey: String): Triple<String, String, String> {
        val parts = identityKey.split(":", limit = 3)
        return if (parts.size == 3) Triple(parts[0], parts[1], parts[2])
        else Triple(IptvLogic.SRC_XTREAM, IptvLogic.KIND_LIVE, identityKey)
    }

    override fun setFavoriteWithMeta(identityKey: String, name: String, favorite: Boolean) {
        val cur = prefs.iptvFavorites().toMutableList()
        cur.removeAll { it.key == identityKey }
        if (favorite) {
            val (source, kind, id) = splitKey(identityKey)
            cur.add(IptvLogic.FavEntry(kind, source, id, name, "", "", "", ""))
        }
        prefs.setIptvFavorites(cur)
    }

    override fun setResumeWithMeta(identityKey: String, positionMs: Long?, title: String) {
        val cur = prefs.iptvResume().toMutableList()
        cur.removeAll { it.key == identityKey }
        if (positionMs != null && positionMs >= IptvLogic.RESUME_MIN_MS) {
            val (source, kind, id) = splitKey(identityKey)
            cur.add(IptvLogic.ResumeEntry(identityKey, kind, id, title, "", "",
                positionMs, 0L, System.currentTimeMillis(), "", "", ""))
            while (cur.size > IptvLogic.RESUME_CAP) cur.removeAt(0)
        }
        prefs.setIptvResume(cur)
    }

    override fun getResume(identityKey: String): Long? =
        prefs.iptvResume().firstOrNull { it.key == identityKey }?.posMs
    override fun setResume(identityKey: String, positionMs: Long?) {
        val cur = prefs.iptvResume().toMutableList()
        cur.removeAll { it.key == identityKey }
        if (positionMs != null && positionMs >= IptvLogic.RESUME_MIN_MS) {
            // Kind/id cannot be recovered from the key alone in the
            // general case; entries written by PlaybackRepository
            // carry full metadata via setResumeFull.
            cur.add(IptvLogic.ResumeEntry(identityKey, "vod", identityKey, "", "", "", positionMs, 0L,
                System.currentTimeMillis(), "", "", ""))
            while (cur.size > IptvLogic.RESUME_CAP) cur.removeAt(0)
        }
        prefs.setIptvResume(cur)
    }
    fun setResumeFull(entry: IptvLogic.ResumeEntry) {
        val cur = prefs.iptvResume().toMutableList()
        cur.removeAll { it.key == entry.key }
        cur.add(entry)
        while (cur.size > IptvLogic.RESUME_CAP) cur.removeAt(0)
        prefs.setIptvResume(cur)
    }

    override fun getLockedCategories(): Set<String> = prefs.iptvLockedCats()
    override fun setCategoryLocked(categoryId: String, locked: Boolean) {
        val cur = prefs.iptvLockedCats().toMutableSet()
        if (locked) cur.add(categoryId) else cur.remove(categoryId)
        prefs.setIptvLockedCats(cur)
    }
    override fun isCategoryUnlocked(categoryId: String): Boolean {
        val cats = allCategories()
        val cat = cats.firstOrNull { it.key == categoryId || it.id == categoryId }
        if (cat == null) return categoryId !in prefs.iptvLockedCats()
        return !IptvLocks.isLocked(cat.key, cat.name, prefs.iptvLockedCats(),
            prefs.iptvUnlockStamps(), prefs.unlockGraceMin(), System.currentTimeMillis(),
            sessionStartMs, prefs.arrowPin().isNotBlank())
    }
    fun stampCategoryUnlock(categoryId: String) {
        val m = prefs.iptvUnlockStamps().toMutableMap()
        m[categoryId] = System.currentTimeMillis()
        prefs.setIptvUnlockStamps(m)
    }

    override fun playItemForChannel(channel: IptvChannel): PlayItem =
        PlayItem(PlayKind.LIVE, channel.id, channel.name, null, channel.streamUrl,
            getResume("xtream:live:${channel.id}"), null, null, null, null)
    override fun playItemForVod(vod: VodItem): PlayItem =
        PlayItem(PlayKind.VOD, vod.id, vod.name, null, vod.streamUrl, vod.resumeMs, null, null, null, null)
    override fun playItemForEpisode(episode: SeriesEpisode): PlayItem =
        PlayItem(PlayKind.SERIES_EPISODE, episode.id, episode.title, episode.title,
            episode.streamUrl, episode.resumeMs, null, null, episode.season, episode.episode)
}
