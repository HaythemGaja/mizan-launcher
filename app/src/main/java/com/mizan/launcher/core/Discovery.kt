package com.mizan.launcher.core

/**
 * Discovery logic (v2.7) — pure decisions, no Android imports.
 *
 * The user's three focus rows (directive 2026-10-23): Up Next
 * (already the tracker's), Coming Soon, and Trending — plus the
 * self-reliance shift: with the launcher's own TMDB key set,
 * Sreeb computes Coming Soon, Trending and For You ITSELF
 * (data/DiscoveryData orchestrates the fetches through the v2.6
 * proxy→direct funnel); the tracker stays the library of record
 * and the mark-watched sink, and its snapshots remain the
 * fallback whenever the launcher has nothing computed.
 *
 * This object owns everything testable about that:
 * - TMDB trending payload parsing (tv / movie / all),
 * - tv/movie details "facts" parsing (next-episode air date,
 *   release date, genres, rating) for Coming Soon + For You,
 * - Coming Soon shaping: strictly-parsed future dates only,
 *   sorted ascending, capped, with the date chip text,
 * - the For You engine — a deterministic port of the tracker web
 *   app's computeForYou (genre affinity weighted by completion,
 *   seeds by weight × rating, /recommendations per seed +
 *   /discover over the top affinity genres, same reason strings,
 *   same score shape, same pick shape). Two deliberate
 *   differences: no shuffle (the row is stable between visits)
 *   and discover page 1 (freshness comes from the 24 h cache,
 *   not from random paging),
 * - cache TTL decisions (fake-clock testable),
 * - the `discovery:` diagnostics line.
 */
object Discovery {

    // ------------------------------------------------------------ trending

    /** One TMDB trending entry (tv, movie, or mixed from /all). */
    data class TrendItem(
        val id: Long,
        val type: String,
        val title: String,
        val poster: String,
        val backdrop: String,
        val voteAverage: Double,
        /** 4-digit year from the release/air date; "" when unknown. */
        val year: String,
        val overview: String
    ) {
        fun key(): String = "$type|$id"
    }

    private fun num(v: Any?): Long =
        (v as? Number)?.toLong() ?: (v as? String)?.trim()?.toLongOrNull() ?: 0L
    private fun str(o: Map<String, Any?>, k: String): String = o[k].asStr() ?: ""

    /**
     * Parse a trending payload. [forcedType] is "tv"/"movie" for
     * the per-type endpoints (their results may omit media_type);
     * null for trending/all, where each result's media_type rules
     * and persons are dropped. Order is TMDB's, preserved.
     */
    fun parseTrending(json: String?, forcedType: String?): List<TrendItem> {
        val o = JsonMini.parse(json).asObj() ?: return emptyList()
        val out = ArrayList<TrendItem>()
        for (raw in o["results"].asArr() ?: emptyList()) {
            val r = raw.asObj() ?: continue
            val type = when {
                forcedType != null -> forcedType
                str(r, "media_type") == "movie" -> "movie"
                str(r, "media_type") == "tv" -> "tv"
                else -> continue
            }
            val id = num(r["id"])
            val title = if (type == "movie") str(r, "title") else str(r, "name")
            if (id <= 0 || title.isBlank()) continue
            val date = if (type == "movie") str(r, "release_date") else str(r, "first_air_date")
            out.add(
                TrendItem(
                    id = id,
                    type = type,
                    title = title,
                    poster = str(r, "poster_path"),
                    backdrop = str(r, "backdrop_path"),
                    voteAverage = (r["vote_average"] as? Number)?.toDouble() ?: 0.0,
                    year = if (date.length >= 4) date.substring(0, 4) else "",
                    overview = str(r, "overview")
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------ details facts

    /** The facts one tv/<id> payload carries for discovery. */
    data class TvFacts(
        /** next_episode_to_air.air_date (yyyy-MM-dd); "" when none. */
        val nextDateIso: String,
        val nextSeason: Int,
        val nextEpisode: Int,
        val poster: String,
        val voteAverage: Double,
        val genreIds: List<Int>
    )

    fun parseTvFacts(json: String?): TvFacts? {
        val o = JsonMini.parse(json).asObj() ?: return null
        if (num(o["id"]) <= 0) return null
        val next = o["next_episode_to_air"].asObj()
        return TvFacts(
            nextDateIso = next?.let { str(it, "air_date") } ?: "",
            nextSeason = next?.let { num(it["season_number"]).toInt() } ?: 0,
            nextEpisode = next?.let { num(it["episode_number"]).toInt() } ?: 0,
            poster = str(o, "poster_path"),
            voteAverage = (o["vote_average"] as? Number)?.toDouble() ?: 0.0,
            genreIds = genreIdsOf(o)
        )
    }

    /** The facts one movie/<id> payload carries for discovery. */
    data class MovieFacts(
        val releaseDate: String,
        val poster: String,
        val voteAverage: Double,
        val genreIds: List<Int>
    )

    fun parseMovieFacts(json: String?): MovieFacts? {
        val o = JsonMini.parse(json).asObj() ?: return null
        if (num(o["id"]) <= 0) return null
        return MovieFacts(
            releaseDate = str(o, "release_date"),
            poster = str(o, "poster_path"),
            voteAverage = (o["vote_average"] as? Number)?.toDouble() ?: 0.0,
            genreIds = genreIdsOf(o)
        )
    }

    /** Details payloads carry genres as objects; results carry genre_ids. */
    private fun genreIdsOf(o: Map<String, Any?>): List<Int> {
        val fromObjects = o["genres"].asArr()
            ?.mapNotNull { (it.asObj())?.let { g -> num(g["id"]).toInt() } }
            ?.filter { it > 0 }
        if (!fromObjects.isNullOrEmpty()) return fromObjects
        return o["genre_ids"].asArr()
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?.filter { it > 0 }
            ?: emptyList()
    }

    // ------------------------------------------------------------ coming soon

    /** One tracked show's Coming Soon input (data layer resolved). */
    data class ShowSoon(
        val tmdbId: Long,
        val title: String,
        val poster: String,
        val nextDateIso: String,
        val nextSeason: Int,
        val nextEpisode: Int
    )

    /** One tracked movie's Coming Soon input (data layer resolved). */
    data class MovieSoon(
        val tmdbId: Long,
        val title: String,
        val poster: String,
        val releaseDate: String
    )

    /** One Coming Soon card. [sub] = "S2 E5" for shows, "" for movies. */
    data class SoonItem(
        val tmdbId: Long,
        val type: String,
        val title: String,
        val poster: String,
        val dateIso: String,
        val sub: String
    ) {
        fun key(): String = "$type|$tmdbId"
    }

    const val SOON_CAP = 20

    /**
     * Strict date check: TrackerLogic's parser accepts any
     * d in 1..31 (its countdown only labels), but Coming Soon must
     * not invent dates — a candidate date must round-trip through
     * the civil conversion (Feb 30 parses as Mar 2 there; here it
     * is simply rejected).
     */
    fun strictEpochDay(iso: String): Long? {
        val day = TrackerLogic.isoEpochDay(iso) ?: return null
        return if (civilIso(day) == iso.trim()) day else null
    }

    /** Epoch days → yyyy-MM-dd (Hinnant's civil_from_days, the inverse of TrackerLogic's). */
    fun civilIso(epochDay: Long): String {
        val z = epochDay + 719468L
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        fun p2(v: Long): String = v.toString().padStart(2, '0')
        return "${year.toString().padStart(4, '0')}-${p2(m)}-${p2(d)}"
    }

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /** "Oct 30" for a strict yyyy-MM-dd date; "" when unparseable. */
    fun monthDay(iso: String): String {
        if (strictEpochDay(iso) == null) return ""
        val p = iso.trim().split("-")
        val m = p.getOrNull(1)?.toIntOrNull() ?: return ""
        val d = p.getOrNull(2)?.toIntOrNull() ?: return ""
        if (m !in 1..12) return ""
        return "${MONTHS[m - 1]} $d"
    }

    /**
     * The Coming Soon date chip: today → "today", +1 → "tomorrow",
     * +2..30 → "in N days", further → the date ("Oct 30"). Past or
     * unparseable → "" (such items never reach the row anyway).
     */
    fun soonChip(dateIso: String, todayIso: String): String {
        val date = strictEpochDay(dateIso) ?: return ""
        val today = strictEpochDay(todayIso) ?: return ""
        val diff = date - today
        return when {
            diff < 0L -> ""
            diff == 0L -> "today"
            diff == 1L -> "tomorrow"
            diff <= 30L -> "in $diff days"
            else -> monthDay(dateIso)
        }
    }

    /**
     * Shape the Coming Soon row: shows with a future (or today's)
     * next-episode air date + movies with a future (or today's)
     * release date, strictly parsed, sorted by date ascending
     * (title breaks ties), capped at [SOON_CAP]. A show whose
     * next_episode_to_air is null arrives with an empty date and
     * is excluded — no guessing from season data.
     */
    fun shapeComingSoon(
        shows: List<ShowSoon>,
        movies: List<MovieSoon>,
        todayIso: String
    ): List<SoonItem> {
        val today = strictEpochDay(todayIso) ?: return emptyList()
        val out = ArrayList<Pair<Long, SoonItem>>()
        for (s in shows) {
            val day = strictEpochDay(s.nextDateIso) ?: continue
            if (day < today) continue
            val sub = if (s.nextSeason > 0 && s.nextEpisode > 0) {
                "S${s.nextSeason} E${s.nextEpisode}"
            } else {
                ""
            }
            out.add(day to SoonItem(s.tmdbId, "tv", s.title, s.poster, s.nextDateIso, sub))
        }
        for (m in movies) {
            val day = strictEpochDay(m.releaseDate) ?: continue
            if (day < today) continue
            out.add(day to SoonItem(m.tmdbId, "movie", m.title, m.poster, m.releaseDate, ""))
        }
        return out
            .sortedWith(compareBy<Pair<Long, SoonItem>> { it.first }.thenBy { it.second.title.lowercase() })
            .take(SOON_CAP)
            .map { it.second }
    }

    // ------------------------------------------------------------ for you engine

    /**
     * Genre id → name, the tracker engine's table (its
     * computeForYou GENRE_NAMES), kept identical so launcher picks
     * read exactly like tracker picks.
     */
    val GENRE_NAMES: Map<Int, String> = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
        80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
        14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
        10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
        10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
        10767 to "Talk", 10768 to "War & Politics"
    )

    fun genreNames(ids: List<Int>, cap: Int = 3): List<String> =
        ids.mapNotNull { GENRE_NAMES[it] }.distinct().take(cap.coerceAtLeast(0))

    /** Tracker fyWeight for TV: 0.5 base, full weight at 20+ watched episodes. */
    fun tvSeedWeight(watchedCount: Int): Double =
        0.5 + minOf(1.0, watchedCount.coerceAtLeast(0) / 20.0)

    /** Tracker fyWeight for movies: watched = full, unwatched = 0.4. */
    fun movieSeedWeight(watched: Boolean): Double = if (watched) 1.0 else 0.4

    /** One tracked title as For You engine input (facts already resolved). */
    data class SeedInput(
        val type: String,
        val id: Long,
        val title: String,
        val weight: Double,
        val voteAverage: Double,
        val genreIds: List<Int>
    ) {
        fun key(): String = "$type|$id"
        /** Seed ranking value: weight × rating (rating defaults to 6, as in the tracker). */
        fun rank(): Double = weight * (if (voteAverage > 0) voteAverage else 6.0)
    }

    /**
     * The seeds: top [cap] titles by weight × rating among those
     * with weight > 0.4 (the tracker's gate). Deterministic where
     * the tracker shuffles its top 8: rank order, title breaks ties.
     */
    fun pickSeeds(inputs: List<SeedInput>, cap: Int = 5): List<SeedInput> =
        inputs.filter { it.weight > 0.4 }
            .sortedWith(
                compareByDescending<SeedInput> { it.rank() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.key() }
            )
            .take(cap.coerceAtLeast(0))

    /** Genre affinity: each title's weight spread over its genres. */
    fun affinity(inputs: List<SeedInput>): Map<Int, Double> {
        val out = LinkedHashMap<Int, Double>()
        for (input in inputs) {
            for (g in input.genreIds) {
                out[g] = (out[g] ?: 0.0) + input.weight
            }
        }
        return out
    }

    /** The top [n] affinity genre ids (affinity desc, id asc on ties). */
    fun topGenres(aff: Map<Int, Double>, n: Int = 3): List<Int> =
        aff.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .take(n.coerceAtLeast(0))
            .map { it.key }

    /** One TMDB list result (recommendations / discover) as engine input. */
    data class FyCandidate(
        val id: Long,
        val title: String,
        val poster: String,
        val voteAverage: Double,
        val genreIds: List<Int>
    )

    fun parseCandidates(json: String?, type: String): List<FyCandidate> {
        val o = JsonMini.parse(json).asObj() ?: return emptyList()
        val out = ArrayList<FyCandidate>()
        for (raw in o["results"].asArr() ?: emptyList()) {
            val r = raw.asObj() ?: continue
            val id = num(r["id"])
            val title = if (type == "movie") str(r, "title") else str(r, "name")
            if (id <= 0 || title.isBlank()) continue
            out.add(
                FyCandidate(
                    id = id,
                    title = title,
                    poster = str(r, "poster_path"),
                    voteAverage = (r["vote_average"] as? Number)?.toDouble() ?: 0.0,
                    genreIds = r["genre_ids"].asArr()
                        ?.mapNotNull { (it as? Number)?.toInt() }
                        ?.filter { it > 0 }
                        ?: emptyList()
                )
            )
        }
        return out
    }

    /**
     * Recommendation picks for one seed (tracker step (a)): the
     * first 4 results with a poster, reason "Because you watched
     * <seed>", score = seed weight × 10 + the candidate's rating.
     */
    fun recPicks(seed: SeedInput, results: List<FyCandidate>): List<TrackerLogic.ForYouPick> {
        val out = ArrayList<TrackerLogic.ForYouPick>()
        for (cand in results) {
            if (out.size >= 4) break
            if (cand.poster.isBlank()) continue
            val names = genreNames(cand.genreIds, 3)
            out.add(
                TrackerLogic.ForYouPick(
                    id = cand.id,
                    type = seed.type,
                    title = cand.title,
                    poster = cand.poster,
                    voteAverage = cand.voteAverage,
                    reason = "Because you watched ${seed.title}",
                    whyGenres = names.ifEmpty { listOf("Similar to ${seed.title}") },
                    score = seed.weight * 10 + cand.voteAverage
                )
            )
        }
        return out
    }

    /**
     * Discover picks for one type (tracker step (b)): the first 6
     * results with a poster. Reason names the representative
     * tracked title for the candidate's first top genre when one
     * exists ("Because you watched X"), else "Matches your taste:
     * …". Score = the candidate's affinity sum × 2 + its rating.
     */
    fun discoverPicks(
        type: String,
        results: List<FyCandidate>,
        topGenreIds: List<Int>,
        aff: Map<Int, Double>,
        repTitleFor: (Int) -> String?
    ): List<TrackerLogic.ForYouPick> {
        val out = ArrayList<TrackerLogic.ForYouPick>()
        val topSet = topGenreIds.toSet()
        for (cand in results) {
            if (out.size >= 6) break
            if (cand.poster.isBlank()) continue
            val gids = cand.genreIds.filter { it in topSet }
            val names = genreNames(gids, 4)
            val affSum = gids.sumOf { aff[it] ?: 0.0 }
            val rep = gids.firstOrNull()?.let { repTitleFor(it) }
            val reason = when {
                rep != null -> "Because you watched $rep"
                names.isNotEmpty() -> "Matches your taste: ${names.take(2).joinToString(", ")}"
                else -> "Matches your taste: top picks"
            }
            out.add(
                TrackerLogic.ForYouPick(
                    id = cand.id,
                    type = type,
                    title = cand.title,
                    poster = cand.poster,
                    voteAverage = cand.voteAverage,
                    reason = reason,
                    whyGenres = names.ifEmpty { listOf("Trending in your genres") },
                    score = affSum * 2 + cand.voteAverage
                )
            )
        }
        return out
    }

    /**
     * Merge pick lists (recommendations first, then discover, as
     * the tracker adds them): dedupe by type+id (first wins),
     * exclude [excludeKeys] (already-tracked + watched movies —
     * the caller composes the set), sort by score descending
     * (stable — equal scores keep add order), cap [cap].
     */
    fun mergePicks(
        lists: List<List<TrackerLogic.ForYouPick>>,
        excludeKeys: Set<String>,
        cap: Int = 20
    ): List<TrackerLogic.ForYouPick> {
        val seen = HashSet<String>()
        val out = ArrayList<TrackerLogic.ForYouPick>()
        for (list in lists) {
            for (pick in list) {
                if (pick.key() in excludeKeys) continue
                if (!seen.add(pick.key())) continue
                out.add(pick)
            }
        }
        return out.sortedByDescending { it.score }.take(cap.coerceAtLeast(0))
    }

    /**
     * The discover path for one type over the top genres — built
     * exactly like the tracker engine's params (vote-sorted,
     * vote_average ≥ 6.5, vote_count ≥ 30, page 1). NOTE: the path
     * starts with discover/, which the TRACKER proxy's allowlist
     * rejects (bad_path) — the v2.6 funnel then falls through to
     * the direct client (whose allowlist includes discover/), so
     * this only ever resolves when a launcher key is set — which
     * is exactly when the launcher computes For You at all.
     */
    fun discoverPath(type: String, genreIds: List<Int>): String {
        val g = genreIds.joinToString(",")
        return "discover/${if (type == "movie") "movie" else "tv"}" +
            "?with_genres=$g&sort_by=vote_average.desc" +
            "&vote_average.gte=6.5&vote_count.gte=30&include_adult=false&page=1"
    }

    /** Recommendations path for one seed (proxy-allowlisted: tv/ or movie/). */
    fun recommendationsPath(seed: SeedInput): String =
        "${if (seed.type == "movie") "movie" else "tv"}/${seed.id}/recommendations"

    /** Encode picks in the /foryou contract shape (data layer file cache). */
    fun encodePicks(picks: List<TrackerLogic.ForYouPick>): String {
        fun esc(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            return sb.toString()
        }
        return picks.joinToString(",", "[", "]") { p ->
            val genres = p.whyGenres.joinToString(",") { "\"${esc(it)}\"" }
            "{\"id\":${p.id},\"type\":\"${p.type}\",\"title\":\"${esc(p.title)}\"," +
                "\"poster_path\":\"${esc(p.poster)}\",\"vote_average\":${p.voteAverage}," +
                "\"reason\":\"${esc(p.reason)}\",\"whyGenres\":[$genres],\"score\":${p.score}}"
        }
    }

    // ------------------------------------------------------------ iptv trending play

    /**
     * The first playable episode of a matched series for the IPTV
     * home's Trending row: the first season ≥ 1 with episodes, its
     * first episode. (Season 0 "Specials" never auto-plays.)
     */
    fun firstEpisode(info: IptvLogic.SeriesInfo): Pair<Int, Int>? {
        val season = info.seasons
            .filter { it.number >= 1 && it.episodes.isNotEmpty() }
            .minByOrNull { it.number }
            ?: return null
        val ep = season.episodes.filter { it.num > 0 }.minByOrNull { it.num } ?: return null
        return season.number to ep.num
    }

    // ------------------------------------------------------------ cache + diagnostics

    /** Trending payloads refresh at most this often. */
    const val TRENDING_TTL_MS = 12L * 3600L * 1000L

    /** Per-title details payloads (next air date, genres, rating). */
    const val DETAILS_TTL_MS = 24L * 3600L * 1000L

    /** The computed For You set refreshes at most this often. */
    const val FORYOU_TTL_MS = 24L * 3600L * 1000L

    /** A cache entry is fresh when it exists and is younger than [ttlMs]. */
    fun isFresh(fetchedAt: Long, now: Long, ttlMs: Long): Boolean =
        fetchedAt > 0L && now - fetchedAt < ttlMs

    /**
     * The `discovery:` diagnostics line — which engine supplied
     * the rows and how many each holds. Counts and a source word
     * only: no titles, no key material, no URLs.
     */
    fun diagText(source: String, soon: Int, trend: Int, foryou: Int): String =
        "source $source · soon $soon · trend $trend · foryou $foryou"

    /**
     * The honest source word for the `discovery:` line (v2.9,
     * ask H.30). Before v2.9 the word was derived from whether a
     * launcher TMDB key was SET — so it claimed "launcher" even
     * when every fetch had failed and the rows on screen were
     * the tracker's snapshots. Now it is derived from what the
     * last worker pass ACTUALLY supplied:
     *
     * - "launcher" — the launcher supplied the discovery rows
     *   (its computed For You is live, or it holds
     *   launcher-computed Soon/Trending sets) and the tracker's
     *   For You mirror is not what is rendered.
     * - "launcher+tracker" — launcher sets are live AND the
     *   rendered For You is the tracker's mirror (no launcher
     *   compute behind it).
     * - "tracker" — nothing launcher-computed is live; the
     *   tracker snapshots are the source.
     */
    fun diagSource(
        forYouComputed: Boolean,
        forYouFromTracker: Boolean,
        launcherSetsLive: Boolean
    ): String = when {
        forYouComputed -> "launcher"
        launcherSetsLive && forYouFromTracker -> "launcher+tracker"
        launcherSetsLive -> "launcher"
        else -> "tracker"
    }
}
