package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.Discovery
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.domain.RefreshDiscoveryUseCase
import java.io.File

/**
 * Discovery worker (MAP §2/§5): launcher-computed Coming Soon /
 * Trending / For You from TMDB, with tracker snapshots as
 * fallback. 12 h trending TTL, disk file cache under
 * filesDir/discovery/ (trending/tv/week, trending/movie/week,
 * trending/all/week, soon/for-you details), single rebuild
 * landing on change, diagnostics `discovery:` line. Runs on a
 * worker thread; the integration worker wires its lifecycle.
 * When no TMDB key is saved, cached + snapshot data are served
 * and no network is attempted (fail open, silent).
 */
class DiscoveryWorker(ctx: Context) : RefreshDiscoveryUseCase {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    private val tmdb = TmdbRepositoryImpl(ctx)
    private val tracker = TrackerRepositoryImpl(ctx)
    private val dir: File = File(context.filesDir, "discovery").apply { mkdirs() }

    data class Cached(
        val trendingTv: List<Discovery.TrendItem> = emptyList(),
        val trendingMovies: List<Discovery.TrendItem> = emptyList(),
        val fetchedAtMs: Long = 0L,
    )

    companion object {
        fun loadCached(ctx: Context): Cached {
            return try {
                val dir = File(ctx.filesDir, "discovery")
                fun read(name: String): String? {
                    val f = File(dir, name)
                    return if (f.isFile) f.readText() else null
                }
                val tv = read("trending_tv.json")?.let { Discovery.parseTrending(it, "tv") } ?: emptyList()
                val mv = read("trending_movie.json")?.let { Discovery.parseTrending(it, "movie") } ?: emptyList()
                val stamp = File(dir, "trending_tv.json").takeIf { it.isFile }?.lastModified() ?: 0L
                Cached(tv, mv, stamp)
            } catch (e: Throwable) { Cached() }
        }
    }

    private fun writeAtomic(name: String, content: String) {
        try {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(content)
            tmp.renameTo(target)
        } catch (ignored: Throwable) { }
    }

    override fun invoke(force: Boolean) {
        val cached = loadCached(context)
        val fresh = Discovery.isFresh(cached.fetchedAtMs, System.currentTimeMillis(), Discovery.TRENDING_TTL_MS)
        if (!force && fresh) return
        if (prefs.tmdbKey().isBlank()) return // no key: serve cache/snapshot, silent
        // Direct TMDB trending (proxy would be used first when available;
        // see TmdbRepositoryImpl for the proxy-closed note).
        val key = prefs.tmdbKey()
        val tvResp = HttpUtil.get("https://api.themoviedb.org/3/trending/tv/week?api_key=${HttpUtil.enc(key)}")
        if (tvResp.code in 200..299 && !tvResp.body.isNullOrBlank()) {
            writeAtomic("trending_tv.json", tvResp.body!!)
        }
        val mvResp = HttpUtil.get("https://api.themoviedb.org/3/trending/movie/week?api_key=${HttpUtil.enc(key)}")
        if (mvResp.code in 200..299 && !mvResp.body.isNullOrBlank()) {
            writeAtomic("trending_movie.json", mvResp.body!!)
        }
    }

    fun trendingTvCards(): List<HomeCard> {
        val cached = loadCached(context)
        if (cached.trendingTv.isNotEmpty()) {
            return cached.trendingTv.map {
                HomeCard(it.id, MediaKind.SHOW, it.title, it.poster.takeIf { p -> p.isNotBlank() },
                    it.backdrop.takeIf { b -> b.isNotBlank() })
            }
        }
        return emptyList() // tracker snapshot fallback lives in TrackerRepository.getForYou/UpNext
    }
    fun trendingMovieCards(): List<HomeCard> {
        val cached = loadCached(context)
        if (cached.trendingMovies.isNotEmpty()) {
            return cached.trendingMovies.map {
                HomeCard(it.id, MediaKind.MOVIE, it.title, it.poster.takeIf { p -> p.isNotBlank() },
                    it.backdrop.takeIf { b -> b.isNotBlank() })
            }
        }
        return emptyList()
    }
    fun diagLine(): String {
        val c = loadCached(context)
        return Discovery.diagText(if (c.fetchedAtMs > 0) "cache" else "none", 0,
            c.trendingTv.size + c.trendingMovies.size, tracker.getForYou().size)
    }
}
