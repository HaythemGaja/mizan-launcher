package com.mizan.launcher.core

/**
 * Library + TMDB logic (v2.1) — pure decisions, no Android imports.
 *
 * v2.1 brings the tracker's daily-use features INTO the launcher:
 * a My Library grid over ALL tracked titles, in-launcher details
 * with season/episode checklists, and TMDB search + add — so the
 * tracker app itself is only needed for its Settings, TMDB key and
 * backup/restore. The tracker stays the single writer: every
 * launcher write is QUEUED through the provider's action calls
 * (contract: tvshows-movies-tracker/LAUNCHER_SYNC.md) and applied
 * by the tracker at its next boot.
 *
 * This object owns everything testable about that:
 * - the FULL /tracked parse (watched episode SETS + muted — the
 *   v1.8 TrackedShow keeps only a count, for search),
 * - the unified library list (shows + movies merged, progress
 *   derived from /continue + /upnext, poster fallbacks),
 * - TMDB payload parsing (details, season episodes, search) for
 *   the tracker's `tmdb_get` proxy — the first build to exercise
 *   it — plus its error-string mapping,
 * - the GENERALIZED pending-actions overlay: the v1.8 marked-set
 *   pattern grown up. Every queued write (episode toggles, season
 *   marks, unwatch, add, remove, mute, movie toggles) is mirrored
 *   here per profile and renders instantly; entries retire
 *   part-by-part only against a snapshot FRESHER than the action
 *   (the pruneMarked discipline), so nothing flickers back while
 *   the tracker hasn't applied the queue yet.
 */
object LibraryLogic {

    // ------------------------------------------------------------ raw tracked shows

    /** One /tracked show, full fidelity: watched episodes as "SxE" keys. */
    data class LibShow(
        val tmdbId: Long,
        val name: String,
        val muted: Boolean,
        val planned: Boolean,
        val watched: Set<String>
    )

    /** The watched-set key for one episode ("1x4"). */
    fun epKey(season: Int, episode: Int): String = "${season}x${episode}"

    /** A TMDB id from a payload value — numeric strings accepted (v2.1, as in TrackerLogic). */
    private fun num(v: Any?): Long =
        (v as? Number)?.toLong() ?: (v as? String)?.trim()?.toLongOrNull() ?: 0L
    private fun str(o: Map<String, Any?>, k: String): String = o[k].asStr() ?: ""

    fun parseLibShows(json: String?): List<LibShow> {
        val arr = JsonMini.parse(json).asArr() ?: return emptyList()
        val out = ArrayList<LibShow>()
        for (raw in arr) {
            val o = raw.asObj() ?: continue
            val id = num(o["tmdbId"])
            val name = str(o, "name")
            if (id <= 0 || name.isBlank()) continue
            val watched = o["watched"].asArr()
                ?.mapNotNull { it.asStr() }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
            out.add(
                LibShow(
                    tmdbId = id,
                    name = name,
                    muted = o["muted"].asBool() ?: false,
                    planned = o["planned"].asBool() ?: false,
                    watched = watched
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------ unified library

    /**
     * One card of the My Library grid (shows and movies unified,
     * overlay already applied). [totalCount] 0 = unknown (no /continue
     * entry) → no progress bar. [nextSeason]/[nextEpisode] 0 = no
     * known next episode. [pending] = added from the launcher, not
     * yet in the tracker's snapshots.
     */
    data class LibItem(
        val type: String,
        val tmdbId: Long,
        val title: String,
        val poster: String,
        /** Movie release date (yyyy-MM-dd) or "" — shows carry "". */
        val date: String,
        val planned: Boolean,
        val muted: Boolean,
        val watchedCount: Int,
        val totalCount: Int,
        val nextSeason: Int,
        val nextEpisode: Int,
        val caughtUp: Boolean,
        val movieWatched: Boolean,
        val pending: Boolean
    ) {
        fun key(): String = "$type|$tmdbId"
    }

    /**
     * The "next" coordinates hidden in a /continue `sub` string
     * ("Next S1 E4 · 3 of 20 watched" → 1 to 4). /upnext carries the
     * numbers natively and is preferred; this is the fallback for
     * shows with a Continue entry but no Up Next entry. "✓ All
     * caught up" and anything unparseable yield null.
     */
    fun parseNextFromSub(sub: String): Pair<Int, Int>? {
        if (!sub.startsWith("Next ")) return null
        var i = 5
        while (i < sub.length && sub[i] != 'S') i++
        if (i >= sub.length) return null
        i++
        val s0 = i
        while (i < sub.length && sub[i].isDigit()) i++
        if (i == s0) return null
        val season = sub.substring(s0, i).toIntOrNull() ?: return null
        while (i < sub.length && (sub[i] == ' ' || sub[i] == '·')) i++
        if (i >= sub.length || sub[i] != 'E') return null
        i++
        val e0 = i
        while (i < sub.length && sub[i].isDigit()) i++
        if (i == e0) return null
        val episode = sub.substring(e0, i).toIntOrNull() ?: return null
        if (season <= 0 || episode <= 0) return null
        return season to episode
    }

    /**
     * Build the library grid: /tracked shows + /movies merged, the
     * pending overlay applied (removed hidden, added appended as
     * pending, mute/movie/episode state overridden), progress from
     * /continue + /upnext, posters from the snapshots first, then
     * the poster cache (paths learned from earlier tmdb_get detail
     * fetches — /tracked itself carries no poster).
     *
     * Sort (documented choice, spec §19): the /tracked payload has
     * NO timestamps, so "recently progressed first" is approximated
     * by the tracker's own ordering signals —
     *   tier 0: in-progress shows, in /continue snapshot order;
     *   tier 1: shows with an Up Next entry, in /upnext (air-date) order;
     *   tier 2: everything else (incl. planned + pending), A–Z;
     *   tier 3: caught-up shows + watched movies, A–Z.
     */
    fun buildLibrary(
        shows: List<LibShow>,
        movies: List<TrackerLogic.MovieItem>,
        cont: List<TrackerLogic.ContinueItem>,
        upNext: List<TrackerLogic.UpNextItem>,
        overlay: List<PendingTitle>,
        posterCache: Map<String, String>
    ): List<LibItem> {
        val overlayByKey = overlay.associateBy { it.key() }
        val contById = cont.associateBy { it.tmdbId }
        val contOrder = cont.withIndex().associate { it.value.tmdbId to it.index }
        val upById = upNext.associateBy { it.tmdbId }
        val upOrder = upNext.withIndex().associate { it.value.tmdbId to it.index }
        val items = ArrayList<LibItem>()
        val seen = HashSet<String>()

        for (show in shows) {
            val key = "tv|${show.tmdbId}"
            val entry = overlayByKey[key]
            if (entry?.removed == true) continue
            seen.add(key)
            val watched = effectiveWatched(show, entry)
            val c = contById[show.tmdbId]
            val u = upById[show.tmdbId]
            val caughtUp = c?.sub?.startsWith("✓") == true
            var next = u?.let { it.season to it.episode }
                ?: c?.let { parseNextFromSub(it.sub) }
                ?: (0 to 0)
            // The known next episode was marked watched right here
            // (pending overlay) — the true next is the tracker's to
            // compute at convergence; claim none until then.
            if (next.first > 0 && epKey(next.first, next.second) in watched) {
                next = 0 to 0
            }
            val poster = firstPoster(
                c?.poster ?: "", u?.poster ?: "", posterCache[key] ?: ""
            )
            items.add(
                LibItem(
                    type = "tv",
                    tmdbId = show.tmdbId,
                    title = show.name,
                    poster = poster,
                    date = "",
                    planned = show.planned,
                    muted = entry?.muted ?: show.muted,
                    watchedCount = watched.size,
                    totalCount = c?.total ?: 0,
                    nextSeason = next.first,
                    nextEpisode = next.second,
                    caughtUp = caughtUp,
                    movieWatched = false,
                    pending = false
                )
            )
        }
        for (movie in movies) {
            val key = "movie|${movie.tmdbId}"
            val entry = overlayByKey[key]
            if (entry?.removed == true) continue
            seen.add(key)
            items.add(
                LibItem(
                    type = "movie",
                    tmdbId = movie.tmdbId,
                    title = movie.title,
                    poster = firstPoster(movie.poster, posterCache[key] ?: ""),
                    date = movie.releaseDate,
                    planned = movie.planned,
                    muted = entry?.muted ?: false,
                    watchedCount = 0,
                    totalCount = 0,
                    nextSeason = 0,
                    nextEpisode = 0,
                    caughtUp = false,
                    movieWatched = entry?.movieWatched ?: movie.watched,
                    pending = false
                )
            )
        }
        // Overlay-added titles the tracker hasn't mirrored yet.
        for (entry in overlay) {
            if (entry.removed || entry.addedTitle == null) continue
            if (entry.key() in seen) continue
            seen.add(entry.key())
            items.add(
                LibItem(
                    type = entry.type,
                    tmdbId = entry.tmdbId,
                    title = entry.addedTitle,
                    poster = firstPoster(entry.addedPoster, posterCache[entry.key()] ?: ""),
                    date = entry.addedDate,
                    planned = true,
                    muted = entry.muted ?: false,
                    watchedCount = 0,
                    totalCount = 0,
                    nextSeason = 0,
                    nextEpisode = 0,
                    caughtUp = false,
                    movieWatched = entry.movieWatched ?: false,
                    pending = true
                )
            )
        }

        fun tier(item: LibItem): Int = when {
            item.pending -> 2
            item.type == "tv" && item.tmdbId in contOrder && !item.caughtUp -> 0
            item.type == "tv" && item.tmdbId in upOrder -> 1
            item.type == "tv" && item.caughtUp -> 3
            item.type == "movie" && item.movieWatched -> 3
            else -> 2
        }
        fun tierKey(item: LibItem): Int = when (tier(item)) {
            0 -> contOrder[item.tmdbId] ?: 0
            1 -> upOrder[item.tmdbId] ?: 0
            else -> 0
        }
        return items.sortedWith(
            compareBy<LibItem> { tier(it) }
                .thenBy { tierKey(it) }
                .thenBy { it.title.lowercase() }
        )
    }

    private fun firstPoster(vararg paths: String): String =
        paths.firstOrNull { it.isNotBlank() } ?: ""

    /** Watched fraction for the progress bar; negative = no bar. */
    fun progressFraction(item: LibItem): Float {
        if (item.type != "tv" || item.totalCount <= 0) return -1f
        return (item.watchedCount.toFloat() / item.totalCount.toFloat())
            .coerceIn(0f, 1f)
    }

    /** The line under a library card's title. */
    fun progressLine(item: LibItem): String {
        if (item.pending) return "Pending — adds when the tracker next opens"
        if (item.type == "movie") {
            return when {
                item.movieWatched -> "✓ Watched"
                item.planned -> "Planned"
                item.date.length >= 4 -> item.date.substring(0, 4)
                else -> "Movie"
            }
        }
        return when {
            item.caughtUp -> "✓ All caught up"
            item.nextSeason > 0 && item.totalCount > 0 ->
                "Next S${item.nextSeason} E${item.nextEpisode} · " +
                    "${item.watchedCount} of ${item.totalCount} watched"
            item.nextSeason > 0 ->
                "Next S${item.nextSeason} E${item.nextEpisode} · " +
                    "${item.watchedCount} watched"
            item.watchedCount > 0 && item.totalCount > 0 ->
                "${item.watchedCount} of ${item.totalCount} watched"
            item.watchedCount > 0 ->
                if (item.watchedCount == 1) "1 episode watched"
                else "${item.watchedCount} episodes watched"
            item.planned -> "Planned"
            else -> "Not started"
        }
    }

    // ------------------------------------------------------------ pending overlay

    /**
     * One title's pending (queued, not yet converged) writes — the
     * generalized overlay. [at] is the newest action's time; only a
     * snapshot with `updated > at` may retire any part (see
     * [prunePending]). Episode sets hold "SxE" keys; the newest
     * toggle of an episode wins (it leaves the opposite set).
     */
    data class PendingTitle(
        val type: String,
        val tmdbId: Long,
        val at: Long,
        val addedTitle: String? = null,
        val addedPoster: String = "",
        val addedDate: String = "",
        val removed: Boolean = false,
        val muted: Boolean? = null,
        val movieWatched: Boolean? = null,
        val watchedAdd: Set<String> = emptySet(),
        val watchedRemove: Set<String> = emptySet()
    ) {
        fun key(): String = "$type|$tmdbId"

        /** True when nothing is pending anymore (safe to drop). */
        fun isSettled(): Boolean =
            addedTitle == null && !removed && muted == null &&
                movieWatched == null && watchedAdd.isEmpty() && watchedRemove.isEmpty()
    }

    fun overlayEntry(overlay: List<PendingTitle>, type: String, tmdbId: Long): PendingTitle? =
        overlay.firstOrNull { it.type == type && it.tmdbId == tmdbId }

    /** A show's effective watched set: snapshot ∪ adds − removes. */
    fun effectiveWatched(show: LibShow, entry: PendingTitle?): Set<String> {
        if (entry == null) return show.watched
        return (show.watched + entry.watchedAdd) - entry.watchedRemove
    }

    private fun esc(s: String): String = s
        .replace("%", "%25").replace("|", "%7C")
        .replace("\n", "%0A").replace("\r", "%0D")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                when (s.substring(i + 1, i + 3)) {
                    "25" -> { sb.append('%'); i += 3; continue }
                    "7C" -> { sb.append('|'); i += 3; continue }
                    "0A" -> { sb.append('\n'); i += 3; continue }
                    "0D" -> { sb.append('\r'); i += 3; continue }
                }
            }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }

    /**
     * Encode the overlay for prefs (one title per line):
     * type|id|at|flags|title|poster|date|adds-csv|removes-csv where
     * flags is 4 chars: removed(0/1), added(0/1), muted(-/0/1),
     * movieWatched(-/0/1). Free-text fields are percent-escaped.
     */
    fun encodePending(entries: List<PendingTitle>): String =
        entries.joinToString("\n") { e ->
            val flags = buildString {
                append(if (e.removed) '1' else '0')
                append(if (e.addedTitle != null) '1' else '0')
                append(
                    when (e.muted) {
                        null -> '-'; true -> '1'; false -> '0'
                    }
                )
                append(
                    when (e.movieWatched) {
                        null -> '-'; true -> '1'; false -> '0'
                    }
                )
            }
            listOf(
                e.type, e.tmdbId.toString(), e.at.toString(), flags,
                esc(e.addedTitle ?: ""), esc(e.addedPoster), esc(e.addedDate),
                e.watchedAdd.joinToString(","), e.watchedRemove.joinToString(",")
            ).joinToString("|")
        }

    /** Decode the overlay; malformed lines are skipped (fail-open). */
    fun decodePending(raw: String?): List<PendingTitle> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<PendingTitle>()
        for (line in raw.split("\n")) {
            val p = line.trim().split("|")
            if (p.size != 9) continue
            val id = p[1].toLongOrNull() ?: continue
            val at = p[2].toLongOrNull() ?: continue
            if (id <= 0) continue
            val flags = p[3]
            if (flags.length != 4) continue
            fun flag(c: Char): Boolean? = when (c) {
                '1' -> true; '0' -> false; else -> null
            }
            out.add(
                PendingTitle(
                    type = if (p[0] == "movie") "movie" else "tv",
                    tmdbId = id,
                    at = at,
                    addedTitle = if (flags[1] == '1') unesc(p[4]) else null,
                    addedPoster = unesc(p[5]),
                    addedDate = unesc(p[6]),
                    removed = flags[0] == '1',
                    muted = flag(flags[2]),
                    movieWatched = flag(flags[3]),
                    watchedAdd = p[7].split(",").filter { it.isNotBlank() }.toSet(),
                    watchedRemove = p[8].split(",").filter { it.isNotBlank() }.toSet()
                )
            )
        }
        return out
    }

    private fun withEntry(
        overlay: List<PendingTitle>,
        type: String,
        tmdbId: Long,
        at: Long,
        change: (PendingTitle) -> PendingTitle
    ): List<PendingTitle> {
        val base = overlayEntry(overlay, type, tmdbId)
            ?: PendingTitle(if (type == "movie") "movie" else "tv", tmdbId, at)
        val next = change(base).copy(at = at)
        val rest = overlay.filter { it.key() != base.key() }
        return if (next.isSettled()) rest else rest + next
    }

    /** Record an episode toggle (newest toggle of an episode wins). */
    fun withEpisodeToggle(
        overlay: List<PendingTitle>,
        tmdbId: Long,
        season: Int,
        episode: Int,
        watched: Boolean,
        at: Long
    ): List<PendingTitle> {
        val key = epKey(season, episode)
        return withEntry(overlay, "tv", tmdbId, at) { e ->
            if (watched) {
                e.copy(
                    watchedAdd = e.watchedAdd + key,
                    watchedRemove = e.watchedRemove - key
                )
            } else {
                e.copy(
                    watchedAdd = e.watchedAdd - key,
                    watchedRemove = e.watchedRemove + key
                )
            }
        }
    }

    /** Record a whole-season mark (every listed episode watched). */
    fun withSeasonMark(
        overlay: List<PendingTitle>,
        tmdbId: Long,
        season: Int,
        episodes: List<Int>,
        at: Long
    ): List<PendingTitle> {
        val keys = episodes.filter { it > 0 }.map { epKey(season, it) }.toSet()
        if (keys.isEmpty()) return overlay
        return withEntry(overlay, "tv", tmdbId, at) { e ->
            e.copy(
                watchedAdd = e.watchedAdd + keys,
                watchedRemove = e.watchedRemove - keys
            )
        }
    }

    /** Record a movie watched toggle. */
    fun withMovieToggle(
        overlay: List<PendingTitle>,
        tmdbId: Long,
        watched: Boolean,
        at: Long
    ): List<PendingTitle> =
        withEntry(overlay, "movie", tmdbId, at) { it.copy(movieWatched = watched) }

    /** Record a mute toggle (either type). */
    fun withMuted(
        overlay: List<PendingTitle>,
        type: String,
        tmdbId: Long,
        muted: Boolean,
        at: Long
    ): List<PendingTitle> =
        withEntry(overlay, type, tmdbId, at) { it.copy(muted = muted) }

    /** Record an add (also cancels a pending remove of the same title). */
    fun withAdded(
        overlay: List<PendingTitle>,
        type: String,
        tmdbId: Long,
        title: String,
        poster: String,
        releaseDate: String,
        at: Long
    ): List<PendingTitle> =
        withEntry(overlay, type, tmdbId, at) {
            it.copy(
                addedTitle = title, addedPoster = poster,
                addedDate = releaseDate, removed = false
            )
        }

    /** Record a remove (the title hides everywhere immediately). */
    fun withRemoved(
        overlay: List<PendingTitle>,
        type: String,
        tmdbId: Long,
        at: Long
    ): List<PendingTitle> =
        withEntry(overlay, type, tmdbId, at) { it.copy(removed = true) }

    /**
     * Convergence, mirroring pruneMarked: only a snapshot with
     * [snapshotUpdated] NEWER than an entry's [PendingTitle.at] may
     * retire anything, and each part retires independently when the
     * snapshot proves the tracker applied it:
     *  - add: the title appears in tracked/movies;
     *  - remove: the title is gone from tracked/movies (an entry
     *    holding both add+remove settles whole — the remove
     *    cancelled the add before either reached the tracker);
     *  - episode add: the episode is in the snapshot's watched set;
     *  - episode remove: the episode is NOT in the watched set;
     *  - mute / movie-watched: the snapshot flag equals the desire.
     * Parts that haven't converged stay pending (and stay rendered).
     */
    fun prunePending(
        overlay: List<PendingTitle>,
        shows: List<LibShow>,
        movies: List<TrackerLogic.MovieItem>,
        snapshotUpdated: Long
    ): List<PendingTitle> {
        if (overlay.isEmpty()) return overlay
        val showById = shows.associateBy { it.tmdbId }
        val movieById = movies.associateBy { it.tmdbId }
        return overlay.mapNotNull { e ->
            if (snapshotUpdated <= e.at) {
                e // snapshot predates the action — nothing proven yet
            } else {
                val present = if (e.type == "movie") {
                    movieById.containsKey(e.tmdbId)
                } else {
                    showById.containsKey(e.tmdbId)
                }
                if (e.removed) {
                    // Gone from the snapshot → the remove landed (and
                    // any paired add is moot). Still present → keep.
                    if (!present) null else e
                } else {
                    var next = e
                    if (next.addedTitle != null && present) {
                        next = next.copy(
                            addedTitle = null, addedPoster = "", addedDate = ""
                        )
                    }
                    if (e.type == "tv") {
                        val snapWatched = showById[e.tmdbId]?.watched ?: emptySet()
                        val adds = next.watchedAdd.filter { it !in snapWatched }.toSet()
                        val rems = next.watchedRemove.filter { it in snapWatched }.toSet()
                        next = next.copy(watchedAdd = adds, watchedRemove = rems)
                        val snapMuted = showById[e.tmdbId]?.muted
                        if (next.muted != null && snapMuted == next.muted) {
                            next = next.copy(muted = null)
                        }
                    } else {
                        val movie = movieById[e.tmdbId]
                        if (next.movieWatched != null && movie != null &&
                            movie.watched == next.movieWatched
                        ) {
                            next = next.copy(movieWatched = null)
                        }
                        // /movies carries no muted flag to converge a
                        // movie mute against — the watchlist flag the
                        // drain sets is not mirrored back. A movie
                        // mute retires only with its entry (or never
                        // blocks anything: the overlay value equals
                        // what the user last chose either way).
                    }
                    if (next.isSettled()) null else next
                }
            }
        }
    }

    // ------------------------------------------------------------ poster cache

    /** Poster-cache key ("tv|123") — same shape as the overlay keys. */
    fun posterKey(type: String, tmdbId: Long): String = "$type|$tmdbId"

    /**
     * The poster cache: TMDB poster paths learned from tmdb_get
     * detail fetches, keyed [posterKey]. /tracked carries no poster,
     * so this is how library cards for shows outside /continue and
     * /upnext get their art. Lines: "type|id|/path.jpg".
     */
    fun encodePosterCache(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}|${it.value}" }

    fun decodePosterCache(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in raw.split("\n")) {
            val p = line.trim().split("|")
            if (p.size != 3) continue
            val id = p[1].toLongOrNull() ?: continue
            if (id <= 0 || p[2].isBlank()) continue
            out[posterKey(if (p[0] == "movie") "movie" else "tv", id)] = p[2]
        }
        return out
    }

    /**
     * The same key→path codec, reused for the BACKDROP cache: TMDB
     * backdrop paths learned from the same details fetches. The
     * hero pool consults it for For You picks (the /foryou shape
     * carries no backdrop of its own).
     */
    fun encodePathCache(map: Map<String, String>): String = encodePosterCache(map)

    fun decodePathCache(raw: String?): Map<String, String> = decodePosterCache(raw)

    // ------------------------------------------------------------ TMDB payloads

    /** One season of a TV details payload. */
    data class SeasonInfo(
        val number: Int,
        val episodeCount: Int,
        val name: String
    )

    /** A title's TMDB details (tv/<id> or movie/<id> via tmdb_get). */
    data class TitleDetails(
        val tmdbId: Long,
        val type: String,
        val title: String,
        val overview: String,
        val voteAverage: Double,
        val poster: String,
        val backdrop: String,
        /** 4-digit year from the release/air date; "" when unknown. */
        val year: String,
        val status: String,
        /** TV only: seasons ascending (Specials = 0 first when present). */
        val seasons: List<SeasonInfo>
    )

    fun parseDetails(json: String?, type: String): TitleDetails? {
        val o = JsonMini.parse(json).asObj() ?: return null
        val id = num(o["id"])
        if (id <= 0) return null
        val isMovie = type == "movie"
        val title = if (isMovie) str(o, "title") else str(o, "name")
        if (title.isBlank()) return null
        val date = if (isMovie) str(o, "release_date") else str(o, "first_air_date")
        val seasons = ArrayList<SeasonInfo>()
        if (!isMovie) {
            for (raw in o["seasons"].asArr() ?: emptyList()) {
                val s = raw.asObj() ?: continue
                val count = num(s["episode_count"]).toInt()
                if (count <= 0) continue
                seasons.add(
                    SeasonInfo(
                        number = num(s["season_number"]).toInt(),
                        episodeCount = count,
                        name = str(s, "name")
                    )
                )
            }
            seasons.sortBy { it.number }
        }
        return TitleDetails(
            tmdbId = id,
            type = if (isMovie) "movie" else "tv",
            title = title,
            overview = str(o, "overview"),
            voteAverage = (o["vote_average"] as? Number)?.toDouble() ?: 0.0,
            poster = str(o, "poster_path"),
            backdrop = str(o, "backdrop_path"),
            year = if (date.length >= 4) date.substring(0, 4) else "",
            status = str(o, "status"),
            seasons = seasons
        )
    }

    /** One episode of a tv/<id>/season/<n> payload. */
    data class EpisodeInfo(
        val number: Int,
        val name: String,
        val airDate: String,
        val overview: String
    )

    /** Episodes of one season, ascending by episode number. */
    fun parseSeasonEpisodes(json: String?): List<EpisodeInfo> {
        val o = JsonMini.parse(json).asObj() ?: return emptyList()
        val out = ArrayList<EpisodeInfo>()
        for (raw in o["episodes"].asArr() ?: emptyList()) {
            val e = raw.asObj() ?: continue
            val n = num(e["episode_number"]).toInt()
            if (n <= 0) continue
            out.add(
                EpisodeInfo(
                    number = n,
                    name = str(e, "name"),
                    airDate = str(e, "air_date"),
                    overview = str(e, "overview")
                )
            )
        }
        return out.sortedBy { it.number }
    }

    /**
     * The mark_season `episodes` extra: a CSV of episode numbers —
     * authoritative per the contract (the drain quickWatches exactly
     * these). Sorted, deduped, positive numbers only.
     */
    fun seasonCsv(episodes: List<EpisodeInfo>): String =
        episodes.map { it.number }.filter { it > 0 }.distinct().sorted()
            .joinToString(",")

    /** One TMDB search result (search/multi; persons filtered out). */
    data class SearchHit(
        val id: Long,
        val type: String,
        val title: String,
        val poster: String,
        val voteAverage: Double,
        val year: String,
        val overview: String
    )

    fun parseSearch(json: String?): List<SearchHit> {
        val o = JsonMini.parse(json).asObj() ?: return emptyList()
        val out = ArrayList<SearchHit>()
        for (raw in o["results"].asArr() ?: emptyList()) {
            val r = raw.asObj() ?: continue
            val media = str(r, "media_type")
            if (media != "tv" && media != "movie") continue
            val id = num(r["id"])
            val title = if (media == "movie") str(r, "title") else str(r, "name")
            if (id <= 0 || title.isBlank()) continue
            val date = if (media == "movie") {
                str(r, "release_date")
            } else {
                str(r, "first_air_date")
            }
            out.add(
                SearchHit(
                    id = id,
                    type = media,
                    title = title,
                    poster = str(r, "poster_path"),
                    voteAverage = (r["vote_average"] as? Number)?.toDouble() ?: 0.0,
                    year = if (date.length >= 4) date.substring(0, 4) else "",
                    overview = str(r, "overview")
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------ TMDB paths + errors

    /** RFC 3986 percent-encoding for one query value (space → %20). */
    fun encodeQueryComponent(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    /** tmdb_get path for a details fetch (allowlist: tv/ or movie/). */
    fun detailsPath(type: String, tmdbId: Long): String =
        "${if (type == "movie") "movie" else "tv"}/$tmdbId"

    /** tmdb_get path for one season's episodes (allowlist: tv/). */
    fun seasonPath(tmdbId: Long, season: Int): String =
        "tv/$tmdbId/season/$season"

    /** tmdb_get path for a multi search (allowlist: search/). */
    fun searchPath(query: String): String =
        "search/multi?query=${encodeQueryComponent(query.trim())}"

    /** The classified tmdb_get failure (contract error strings). */
    enum class TmdbError { NONE, NO_KEY, BAD_PATH, HTTP, IO, UNKNOWN }

    fun tmdbErrorKind(raw: String?): TmdbError = when {
        raw.isNullOrBlank() -> TmdbError.NONE
        raw == "no_key" -> TmdbError.NO_KEY
        raw == "bad_path" -> TmdbError.BAD_PATH
        raw == "io" -> TmdbError.IO
        raw.startsWith("http_") -> TmdbError.HTTP
        else -> TmdbError.UNKNOWN
    }

    /** The HTTP status inside an "http_<code>" error; 0 otherwise. */
    fun tmdbHttpCode(raw: String?): Int =
        if (tmdbErrorKind(raw) == TmdbError.HTTP) {
            raw!!.removePrefix("http_").toIntOrNull() ?: 0
        } else {
            0
        }

    /** Whether re-trying the same call could plausibly succeed. */
    fun tmdbRetryable(raw: String?): Boolean = when (tmdbErrorKind(raw)) {
        TmdbError.HTTP, TmdbError.IO, TmdbError.UNKNOWN -> true
        else -> false
    }

    /** The user-facing line for a tmdb_get failure state. */
    fun tmdbUserLine(raw: String?): String = when (tmdbErrorKind(raw)) {
        TmdbError.NONE -> ""
        TmdbError.NO_KEY ->
            "No TMDB key yet — add your TMDB key in the tracker app, then try again."
        TmdbError.BAD_PATH ->
            "Something's wrong on our side (bad request path) — it's noted in Diagnostics."
        TmdbError.HTTP ->
            "TMDB answered with an error (HTTP ${tmdbHttpCode(raw)}). Try again in a bit."
        TmdbError.IO ->
            "Couldn't reach TMDB — check the TV's connection, then try again."
        TmdbError.UNKNOWN ->
            "TMDB lookup failed. Try again in a bit."
    }

    /**
     * The Settings diagnostics `tmdb:` wording for the last recorded
     * tmdb_get outcome ([stored] = "ok", a contract error string, or
     * null when no call has run yet on this install).
     */
    fun tmdbDiagText(stored: String?): String = when {
        stored == null -> "not used yet"
        stored == "ok" -> "ok"
        else -> when (tmdbErrorKind(stored)) {
            TmdbError.NO_KEY -> "no key in tracker"
            TmdbError.BAD_PATH -> "bad path (bug)"
            TmdbError.HTTP -> "http ${tmdbHttpCode(stored)}"
            TmdbError.IO -> "network error"
            else -> "error: $stored"
        }
    }
}
