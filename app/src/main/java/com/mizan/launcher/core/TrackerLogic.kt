package com.mizan.launcher.core

/**
 * Tracker view logic (v1.8) — pure decisions, no Android imports.
 *
 * The Tracker home style renders data read from TVShowsMoviesTracker
 * through its launcher sync bridge (contract:
 * tvshows-movies-tracker/LAUNCHER_SYNC.md — provider authority
 * com.haythem.tvtracker.launcherdata, paths /upnext /continue
 * /movies /tracked, snapshots as JSON strings). This object owns
 * everything testable about that view:
 * - parsing the four snapshots (via [JsonMini] — org.json is a JVM
 *   stub in the sim, and every parse here is fail-open),
 * - hero selection + cycling,
 * - countdown labels from ISO dates,
 * - the Continue Watching merge with TvContract cards,
 * - Movie night ordering,
 * - the local marked-set (optimistic mark-watched + convergence),
 * - tracker search matching.
 *
 * The tracker is the single writer of watch progress: the launcher
 * only QUEUES mark-watched actions (data/TrackerClient.call) and
 * hides the marked item locally until a fresher snapshot shows the
 * tracker has converged (see [pruneMarked]).
 */
object TrackerLogic {

    // ------------------------------------------------------------ models

    /** One /upnext item: the next unwatched episode of a tracked show. */
    data class UpNextItem(
        val tmdbId: Long,
        val title: String,
        val sub: String,
        /** ISO air date (yyyy-MM-dd) from the snapshot's `sort` field; "" if absent. */
        val dateIso: String,
        val poster: String,
        val backdrop: String,
        val overview: String,
        val season: Int,
        val episode: Int,
        val type: String
    )

    /** One /continue item: a show in progress (no episode coordinates). */
    data class ContinueItem(
        val tmdbId: Long,
        val title: String,
        val sub: String,
        val watched: Int,
        val total: Int,
        val poster: String,
        val backdrop: String,
        val overview: String,
        val type: String
    )

    /** One /movies item: a tracked movie (v1.14.1 payload). */
    data class MovieItem(
        val tmdbId: Long,
        val title: String,
        /** ISO release date (yyyy-MM-dd); "" when the tracker has none. */
        val releaseDate: String,
        val planned: Boolean,
        val poster: String,
        val watched: Boolean
    )

    /** One /tracked show: name + how many episodes are watched. */
    data class TrackedShow(
        val tmdbId: Long,
        val name: String,
        val watchedCount: Int,
        val planned: Boolean
    )

    /**
     * A hero-panel candidate, unified from Up Next and Continue
     * items. Continue items carry no episode coordinates and no air
     * date (season/episode 0, dateIso ""), so a mark-watched from a
     * Continue-sourced hero resolves its coordinates through the
     * matching Up Next item when one exists (see MainActivity).
     */
    data class HeroItem(
        val tmdbId: Long,
        val title: String,
        val sub: String,
        val dateIso: String,
        val poster: String,
        val backdrop: String,
        val overview: String,
        val type: String,
        val season: Int,
        val episode: Int,
        val fromContinue: Boolean
    )

    /**
     * One locally-marked item: the user tapped "Mark watched" in the
     * launcher and the action is queued with the tracker. Entries
     * live per profile; [pruneMarked] drops an entry once a snapshot
     * fresher than [markedAt] no longer contains the item (the
     * tracker applied the action and its snapshots converged).
     */
    data class MarkedEntry(
        val tmdbId: Long,
        val type: String,
        val season: Int,
        val episode: Int,
        val markedAt: Long
    ) {
        fun key(): String = markedKey(tmdbId, type, season, episode)
    }

    fun markedKey(tmdbId: Long, type: String, season: Int, episode: Int): String =
        "$tmdbId|$type|$season|$episode"

    /** One card of the merged Continue Watching row. */
    data class ContinueCard(
        val title: String,
        /** The tracker item, or null when this is a system (TvContract) card. */
        val tracker: ContinueItem?,
        /** Index into the caller's system list when [tracker] is null; -1 otherwise. */
        val systemIndex: Int
    )

    /** One "In your tracker" search hit. */
    data class TrackerHit(
        val tmdbId: Long,
        val type: String,
        val title: String,
        val detail: String
    )

    // ------------------------------------------------------------ parsing

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
    private fun str(o: Map<String, Any?>, k: String): String = o[k].asStr() ?: ""

    fun parseUpNext(json: String?): List<UpNextItem> {
        val arr = JsonMini.parse(json).asArr() ?: return emptyList()
        val out = ArrayList<UpNextItem>()
        for (raw in arr) {
            val o = raw.asObj() ?: continue
            val id = num(o["tmdbId"])
            val title = str(o, "title")
            if (id <= 0 || title.isBlank()) continue
            out.add(
                UpNextItem(
                    tmdbId = id,
                    title = title,
                    sub = str(o, "sub"),
                    dateIso = str(o, "sort"),
                    poster = str(o, "poster"),
                    backdrop = str(o, "backdrop"),
                    overview = str(o, "overview"),
                    season = num(o["season"]).toInt(),
                    episode = num(o["episode"]).toInt(),
                    type = str(o, "type").ifBlank { "tv" }
                )
            )
        }
        return out
    }

    fun parseContinue(json: String?): List<ContinueItem> {
        val arr = JsonMini.parse(json).asArr() ?: return emptyList()
        val out = ArrayList<ContinueItem>()
        for (raw in arr) {
            val o = raw.asObj() ?: continue
            val id = num(o["tmdbId"])
            val title = str(o, "title")
            if (id <= 0 || title.isBlank()) continue
            out.add(
                ContinueItem(
                    tmdbId = id,
                    title = title,
                    sub = str(o, "sub"),
                    watched = num(o["watched"]).toInt(),
                    total = num(o["total"]).toInt(),
                    poster = str(o, "poster"),
                    backdrop = str(o, "backdrop"),
                    overview = str(o, "overview"),
                    type = str(o, "type").ifBlank { "tv" }
                )
            )
        }
        return out
    }

    fun parseMovies(json: String?): List<MovieItem> {
        val arr = JsonMini.parse(json).asArr() ?: return emptyList()
        val out = ArrayList<MovieItem>()
        for (raw in arr) {
            val o = raw.asObj() ?: continue
            val id = num(o["tmdbId"])
            val title = str(o, "title")
            if (id <= 0 || title.isBlank()) continue
            out.add(
                MovieItem(
                    tmdbId = id,
                    title = title,
                    releaseDate = str(o, "releaseDate"),
                    planned = o["planned"].asBool() ?: false,
                    poster = str(o, "poster"),
                    watched = o["watched"].asBool() ?: false
                )
            )
        }
        return out
    }

    fun parseTrackedShows(json: String?): List<TrackedShow> {
        val arr = JsonMini.parse(json).asArr() ?: return emptyList()
        val out = ArrayList<TrackedShow>()
        for (raw in arr) {
            val o = raw.asObj() ?: continue
            val id = num(o["tmdbId"])
            val name = str(o, "name")
            if (id <= 0 || name.isBlank()) continue
            out.add(
                TrackedShow(
                    tmdbId = id,
                    name = name,
                    watchedCount = o["watched"].asArr()?.size ?: 0,
                    planned = o["planned"].asBool() ?: false
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------ hero

    /**
     * The hero cycle list, in priority order: Up Next items WITH a
     * backdrop, then Continue items WITH a backdrop, then the
     * remaining Up Next items, then the remaining Continue items.
     * Index 0 is therefore exactly the brief's featured pick: first
     * Up Next item with a backdrop, else first Continue item with a
     * backdrop, else the first Up Next item.
     */
    fun heroCandidates(
        upNext: List<UpNextItem>,
        cont: List<ContinueItem>
    ): List<HeroItem> {
        fun UpNextItem.hero() = HeroItem(
            tmdbId, title, sub, dateIso, poster, backdrop, overview,
            type, season, episode, fromContinue = false
        )
        fun ContinueItem.hero() = HeroItem(
            tmdbId, title, sub, "", poster, backdrop, overview,
            type, season = 0, episode = 0, fromContinue = true
        )
        return upNext.filter { it.backdrop.isNotBlank() }.map { it.hero() } +
            cont.filter { it.backdrop.isNotBlank() }.map { it.hero() } +
            upNext.filter { it.backdrop.isBlank() }.map { it.hero() } +
            cont.filter { it.backdrop.isBlank() }.map { it.hero() }
    }

    /** The hero at [index] in the cycle (wraps in both directions). */
    fun selectHero(candidates: List<HeroItem>, index: Int): HeroItem? {
        if (candidates.isEmpty()) return null
        val i = ((index % candidates.size) + candidates.size) % candidates.size
        return candidates[i]
    }

    // ------------------------------------------------------------ dates

    /** Days since the epoch for a civil date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = Math.floorDiv(yy, 400)
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** Parse a strict yyyy-MM-dd date to epoch days; null when malformed. */
    private fun isoDays(iso: String): Long? {
        val parts = iso.trim().split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y !in 1970..9999 || m !in 1..12 || d !in 1..31) return null
        return daysFromCivil(y, m, d)
    }

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /**
     * The countdown label for an air/release date relative to today:
     * past → "Aired", today → "Today", tomorrow → "Tomorrow", within
     * 30 days → "in N d", further out → the date itself ("Nov 14").
     * An empty or unparseable date yields "" (no label).
     */
    fun countdownLabel(dateIso: String, todayIso: String): String {
        val date = isoDays(dateIso) ?: return ""
        val today = isoDays(todayIso) ?: return ""
        val diff = date - today
        return when {
            diff < 0L -> "Aired"
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff <= 30L -> "in $diff d"
            else -> {
                val p = dateIso.trim().split("-")
                val m = p.getOrNull(1)?.toIntOrNull() ?: return dateIso
                val d = p.getOrNull(2)?.toIntOrNull() ?: return dateIso
                if (m !in 1..12) dateIso else "${MONTHS[m - 1]} $d"
            }
        }
    }

    /** How many Up Next items air today (the hero pill's first number). */
    fun airingTodayCount(upNext: List<UpNextItem>, todayIso: String): Int =
        upNext.count { it.dateIso.trim() == todayIso.trim() && todayIso.isNotBlank() }

    /**
     * The hero pill: "N airing today · M unwatched" — the airing part
     * appears only when something airs today, the unwatched part is
     * the Up Next count. "" when neither number exists.
     */
    fun pillText(airingToday: Int, unwatched: Int): String {
        val parts = ArrayList<String>()
        if (airingToday > 0) {
            parts.add(if (airingToday == 1) "1 airing today" else "$airingToday airing today")
        }
        if (unwatched > 0) {
            parts.add(if (unwatched == 1) "1 unwatched" else "$unwatched unwatched")
        }
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------ continue merge

    /** Title identity for dedupe: lowercase, alphanumerics only. */
    fun normalizeTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Merge tracker Continue items with the system's TvContract
     * titles: tracker cards first (snapshot order), then the system
     * cards whose normalized title matches NO tracker card (the
     * tracker card wins a title tie — it carries progress data).
     * System entries are consumed at most once, in caller order.
     */
    fun mergeContinue(
        tracker: List<ContinueItem>,
        systemTitles: List<String>
    ): List<ContinueCard> {
        val out = ArrayList<ContinueCard>()
        val trackerTitles = tracker.map { normalizeTitle(it.title) }.toHashSet()
        for (item in tracker) out.add(ContinueCard(item.title, item, -1))
        val seenSystem = HashSet<String>()
        for ((i, title) in systemTitles.withIndex()) {
            val norm = normalizeTitle(title)
            if (norm.isEmpty() || norm in trackerTitles || !seenSystem.add(norm)) continue
            out.add(ContinueCard(title, null, i))
        }
        return out
    }

    // ------------------------------------------------------------ movies

    /**
     * Movie night order: planned (plan-to-watch) first, then the
     * rest — each group by release date, unknown dates last. Watched
     * movies and locally-marked movies are EXCLUDED — unless that
     * would empty the row, in which case watched movies come back
     * (same order, marked ones still excluded: the user just tapped
     * those away in this launcher).
     */
    fun orderMovies(movies: List<MovieItem>, marked: List<MarkedEntry>): List<MovieItem> {
        val markedIds = marked.filter { it.type == "movie" }.map { it.tmdbId }.toHashSet()
        val unmarked = movies.filter { it.tmdbId !in markedIds }
        val byPlanThenDate = compareBy<MovieItem>(
            { if (it.planned) 0 else 1 },
            { it.releaseDate.ifBlank { "9999-99-99" } },
            { it.title.lowercase() }
        )
        val fresh = unmarked.filter { !it.watched }.sortedWith(byPlanThenDate)
        if (fresh.isNotEmpty()) return fresh
        return unmarked.sortedWith(byPlanThenDate)
    }

    // ------------------------------------------------------------ marked-set

    /** Encode the marked-set for prefs storage (one entry per line). */
    fun encodeMarked(entries: List<MarkedEntry>): String =
        entries.joinToString("\n") {
            "${it.tmdbId}|${it.type}|${it.season}|${it.episode}|${it.markedAt}"
        }

    /** Decode the marked-set; malformed lines are skipped (fail-open). */
    fun decodeMarked(raw: String?): List<MarkedEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<MarkedEntry>()
        for (line in raw.split("\n")) {
            val p = line.trim().split("|")
            if (p.size != 5) continue
            val id = p[0].toLongOrNull() ?: continue
            val season = p[2].toIntOrNull() ?: continue
            val episode = p[3].toIntOrNull() ?: continue
            val at = p[4].toLongOrNull() ?: continue
            if (id <= 0) continue
            out.add(MarkedEntry(id, if (p[1] == "movie") "movie" else "tv", season, episode, at))
        }
        return out
    }

    /** Add (or refresh) one marked entry; newest mark wins per item. */
    fun withMarked(entries: List<MarkedEntry>, entry: MarkedEntry): List<MarkedEntry> =
        entries.filter { it.key() != entry.key() } + entry

    /** Up Next minus locally-marked episodes (optimistic hide). */
    fun filterUpNext(items: List<UpNextItem>, marked: List<MarkedEntry>): List<UpNextItem> {
        if (marked.isEmpty()) return items
        val keys = marked.map { it.key() }.toHashSet()
        return items.filter {
            markedKey(it.tmdbId, it.type, it.season, it.episode) !in keys
        }
    }

    /**
     * Continue minus shows with a locally-marked episode. Continue
     * items carry no episode coordinates, so any marked entry for the
     * show hides its (now stale) card until the snapshot converges.
     */
    fun filterContinue(items: List<ContinueItem>, marked: List<MarkedEntry>): List<ContinueItem> {
        if (marked.isEmpty()) return items
        val ids = marked.filter { it.type == "tv" }.map { it.tmdbId }.toHashSet()
        return items.filter { it.tmdbId !in ids }
    }

    /**
     * Drop marked entries the tracker has converged past: an entry is
     * dropped when the snapshot's [snapshotUpdated] is NEWER than the
     * mark AND the raw snapshot no longer contains the item — for TV,
     * the episode is gone from Up Next (it was marked watched, so the
     * next episode took its place); for movies, the movie is flagged
     * watched or has left the list. Entries whose snapshot is not
     * fresher than the mark are kept untouched.
     */
    fun pruneMarked(
        marked: List<MarkedEntry>,
        upNextRaw: List<UpNextItem>,
        moviesRaw: List<MovieItem>,
        snapshotUpdated: Long
    ): List<MarkedEntry> {
        if (marked.isEmpty() || snapshotUpdated <= 0L) return marked
        return marked.filter { entry ->
            if (entry.markedAt >= snapshotUpdated) {
                true // snapshot predates the mark — nothing proven yet
            } else if (entry.type == "movie") {
                val movie = moviesRaw.firstOrNull { it.tmdbId == entry.tmdbId }
                movie != null && !movie.watched // still unwatched → keep hiding
            } else {
                upNextRaw.any {
                    it.tmdbId == entry.tmdbId && it.season == entry.season &&
                        it.episode == entry.episode
                }
            }
        }
    }

    // ------------------------------------------------------------ search

    /**
     * "In your tracker" matches for [query]: substring match on the
     * title, prefix matches first (the AppSearch rule), stable order
     * inside each group — shows before movies, as supplied. An empty
     * query returns nothing (the section exists only while typing).
     * Show detail counts watched episodes from /tracked; movie detail
     * is the fixed "Movie · tracked".
     */
    fun searchTracked(
        shows: List<TrackedShow>,
        movies: List<MovieItem>,
        query: String
    ): List<TrackerHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val prefix = ArrayList<TrackerHit>()
        val substring = ArrayList<TrackerHit>()
        fun offer(hit: TrackerHit, title: String) {
            val t = title.lowercase()
            when {
                t.startsWith(q) -> prefix.add(hit)
                t.contains(q) -> substring.add(hit)
            }
        }
        for (show in shows) {
            val detail = if (show.watchedCount == 1) {
                "1 episode watched"
            } else {
                "${show.watchedCount} episodes watched"
            }
            offer(TrackerHit(show.tmdbId, "tv", show.name, detail), show.name)
        }
        for (movie in movies) {
            offer(TrackerHit(movie.tmdbId, "movie", movie.title, "Movie · tracked"), movie.title)
        }
        return prefix + substring
    }
}
