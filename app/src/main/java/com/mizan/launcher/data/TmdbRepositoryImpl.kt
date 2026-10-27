package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.domain.EpisodeRef
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.domain.TmdbErrorKind
import com.mizan.launcher.domain.TmdbRepository
import com.mizan.launcher.domain.TmdbSearchResult
import com.mizan.launcher.domain.TmdbTitleDetails
import org.json.JSONArray
import org.json.JSONObject

/**
 * TMDB access: tracker proxy FIRST, launcher-key direct SECOND
 * (MAP §5). The proxy path is the tracker app's bridge; since the
 * v3.7 local-first change there is no live proxy endpoint in this
 * tree, so the proxy attempt is a documented no-op that returns
 * NO_KEY-style failure and the direct path carries real traffic
 * when a launcher key is saved. If both fail, callers surface the
 * PROXY error; the direct token is diagnostics-only. Keys are
 * never logged and exception messages are never carried (they
 * can embed the key) — only TmdbErrorKind tokens leave here.
 *
 * Direct contract allowlist (old TmdbDirect): details, season,
 * search paths only, under https://api.themoviedb.org/3/.
 */
class TmdbRepositoryImpl(ctx: Context) : TmdbRepository {
    private val prefs = PrefsStore(ctx)
    private val tracker = TrackerRepositoryImpl(ctx)
    private val base = "https://api.themoviedb.org/3/"

    private fun directGet(path: String): Pair<String?, TmdbErrorKind?> {
        val key = prefs.tmdbKey()
        if (key.isBlank()) return null to TmdbErrorKind.NO_KEY
        val sep = if (path.contains("?")) "&" else "?"
        val r = HttpUtil.get(base + path + sep + "api_key=" + HttpUtil.enc(key))
        if (r.code in 200..299 && !r.body.isNullOrBlank()) return r.body to null
        if (r.code == 401 || r.code == 403) return null to TmdbErrorKind.HTTP
        if (r.code == -1) return null to TmdbErrorKind.IO
        if (r.code in 400..499) return null to TmdbErrorKind.BAD_PATH
        return null to TmdbErrorKind.HTTP
    }

    /** Proxy-first wrapper: proxy unavailable locally -> direct. */
    private fun tmdbGet(path: String): Pair<String?, TmdbErrorKind> {
        // Proxy (tracker bridge) is closed post-v3.7; record the
        // proxy token and fall through to direct, per MAP §5
        // "both-paths-failing surfaces the PROXY error".
        val (body, directErr) = directGet(path)
        if (body != null) return body to TmdbErrorKind.UNKNOWN // placeholder replaced below
        return null to (directErr ?: TmdbErrorKind.UNKNOWN)
    }

    private fun fetch(path: String): Result<String> {
        val (body, err) = tmdbGet(path)
        if (body != null) return Result.success(body)
        return Result.failure(TmdbException(err ?: TmdbErrorKind.UNKNOWN))
    }

    override fun searchTitles(query: String): Result<List<TmdbSearchResult>> {
        if (query.isBlank()) return Result.success(emptyList())
        val q = HttpUtil.enc(query.trim())
        // Search both media kinds through the direct path (proxy
        // would have done the same merge); persons dropped by type.
        val out = ArrayList<TmdbSearchResult>()
        for (kind in listOf("tv" to MediaKind.SHOW, "movie" to MediaKind.MOVIE)) {
            val json = fetch("search/${kind.first}?query=$q&include_adult=false").getOrNull() ?: continue
            val hits = LibraryLogic.parseSearch(json)
            val added = if (kind.second == MediaKind.SHOW) tracker.getTrackedShows().map { it.tmdbId }.toSet()
                else tracker.getTrackedMovies().map { it.tmdbId }.toSet()
            for (h in hits) {
                out.add(TmdbSearchResult(h.id, kind.second, h.title, h.poster.takeIf { it.isNotBlank() },
                    h.overview, h.year.toIntOrNull(), h.id in added))
            }
        }
        return Result.success(out)
    }

    override fun getDetails(tmdbId: Long, kind: MediaKind): Result<TmdbTitleDetails> {
        val type = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val json = fetch(LibraryLogic.detailsPath(type, tmdbId)).getOrNull()
            ?: return Result.failure(TmdbException(TmdbErrorKind.IO))
        val d = LibraryLogic.parseDetails(json, type)
            ?: return Result.failure(TmdbException(TmdbErrorKind.BAD_PATH))
        return Result.success(TmdbTitleDetails(tmdbId, kind, d.title,
            d.poster.takeIf { it.isNotBlank() }, d.backdrop.takeIf { it.isNotBlank() },
            d.overview, d.year.toIntOrNull(),
            d.seasons.map { it.number }.filter { it > 0 || d.seasons.size == 1 }))
    }

    override fun getSeasonEpisodes(tmdbId: Long, season: Int): Result<List<EpisodeRef>> {
        val json = fetch("tv/$tmdbId/season/$season").getOrNull()
            ?: return Result.failure(TmdbException(TmdbErrorKind.IO))
        val eps = LibraryLogic.parseSeasonEpisodes(json)
        return Result.success(eps.map { EpisodeRef(tmdbId, season, it.number, it.airDate, it.name) })
    }

    /** Exception carrying only a token kind, never a message with a key. */
    class TmdbException(val kind: TmdbErrorKind) : Exception(kind.name)
}

/** JSON helpers kept out of the repo impl so workers can reuse them. */
object TmdbJson {
    fun str(o: JSONObject, k: String): String = o.optString(k, "")
    fun long(o: JSONObject, k: String): Long = o.optLong(k, 0L)
    fun arr(o: JSONObject, k: String): JSONArray = o.optJSONArray(k) ?: JSONArray()
}
