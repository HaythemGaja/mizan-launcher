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
 * v3.7: the tracker app is GONE from this story — the
 * launcher IS the tracker now (data/LibraryLocal writes
 * the launcher-owned store directly; the pending overlay
 * is still the instant-render layer, but the store, not
 * a tracker drain, is what it converges against — see
 * prunePendingLocal). The paragraph above is history,
 * kept so the v2.1 reasoning stays readable; every live
 * write path below is local.
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

    /**
     * One /tracked show, full fidelity: watched episodes as
     * "SxE" keys. v3.6 (ask 53): [watchedCount] is the
     * count-only form — a grid built from the tracker
     * store's title lines carries the count without the
     * set (watched = empty, watchedCount = the title
     * line's count). [count] is the one accessor grid
     * code uses; Details still reads the full set from
     * that title's episode file, so checklist code keeps
     * using [watched] directly. A count-only show must
     * never be treated as "watched nothing" by a set
     * check — callers that need set membership must go
     * through Details' episode-file read.
     */
    data class LibShow(
        val tmdbId: Long,
        val name: String,
        val muted: Boolean,
        val planned: Boolean,
        val watched: Set<String>,
        val watchedCount: Int = watched.size
    ) {
        /** True when this show carries a count but no set (store title line, not an episode file). */
        val isCountOnly: Boolean get() = watched.isEmpty() && watchedCount > 0
        /** The watched-episode count, from the set or the count-only field. */
        fun count(): Int = watchedCount
    }

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
            // (pending overlay) — the true next is the
            // next-episode refresh's to compute at
            // convergence (data/LibraryLocal); claim none
            // until then.
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
                    // v3.6: the count is the EFFECTIVE count —
                    // full-set shows use the effective set's
                    // size (overlay adds/removes applied, as
                    // before); count-only shows have no set to
                    // size, so the title-line count is adjusted
                    // by the still-pending adds/removes
                    // (fail-open: un-converged adds count, a
                    // remove of an unknown set subtracts one).
                    watchedCount = if (show.isCountOnly) {
                        (show.count() +
                            (entry?.watchedAdd?.size ?: 0) -
                            (entry?.watchedRemove?.size ?: 0))
                            .coerceAtLeast(0)
                    } else watched.size,
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
        // Overlay-added titles the store hasn't landed yet.
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
        if (item.pending) return "Pending — adding to your library…"
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

    /**
     * v3.9: this show's pending watched adds/removes only
     * (watchedAdd/watchedRemove are bare "SxE" episode keys
     * with NO show id in them). Callers must scope the
     * lookup by tmdbId — the old home code folded EVERY
     * show's adds into one set, so a pending mark for show
     * A at S2 E3 filtered show B's missed S2 E3 as
     * "watched" too (cross-show contamination). Returns
     * (adds, removes), both empty when the show has no
     * pending entry.
     */
    fun pendingWatchedFor(overlay: List<PendingTitle>, tmdbId: Long): Pair<Set<String>, Set<String>> {
        val entry = overlayEntry(overlay, "tv", tmdbId) ?: return emptySet<String>() to emptySet()
        return entry.watchedAdd to entry.watchedRemove
    }

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
     * snapshot proves the write landed (v3.7: the "snapshot" is
     * the launcher-owned store — see prunePendingLocal for the
     * store path, which passes Long.MAX_VALUE because the store
     * is current by construction):
     *  - add: the title appears in tracked/movies;
     *  - remove: the title is gone from tracked/movies (an entry
     *    holding both add+remove settles whole — the remove
     *    cancelled the add before either reached the store);
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
                        // v3.6: a count-only show (store title
                        // line, no episode set) cannot prove
                        // episode convergence — its adds/removes
                        // stay pending (fail-open), while mute
                        // convergence below still applies, since
                        // the muted flag rides the title line.
                        val show = showById[e.tmdbId]
                        if (show?.isCountOnly != true) {
                            val snapWatched = show?.watched ?: emptySet()
                            val adds = next.watchedAdd.filter { it !in snapWatched }.toSet()
                            val rems = next.watchedRemove.filter { it in snapWatched }.toSet()
                            next = next.copy(watchedAdd = adds, watchedRemove = rems)
                        }
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

    /**
     * v3.7: the store-aware prune — the same convergence
     * as [prunePending], but against the launcher-owned
     * store, which IS current by construction (local
     * writes land in the store within seconds). The old
     * prune's stamp gate (`snapshotUpdated > at`) can
     * never fire on the store path: the store's import
     * stamp predates every local action, so passing it
     * would keep every local write pending forever (and
     * the marked-set analogue would hide Continue cards
     * forever). Passing Long.MAX_VALUE forces the else
     * branch: a part retires exactly when the store
     * proves it (episode in the episode file, movie flag
     * equal, title present/absent, muted equal). Callers
     * use this ONLY with store-backed shows/movies
     * (data/TrackerStoreDisk.libraryShows + readMovies);
     * the fallback path keeps the stamp-gated prune.
     */
    fun prunePendingLocal(
        overlay: List<PendingTitle>,
        shows: List<LibShow>,
        movies: List<TrackerLogic.MovieItem>
    ): List<PendingTitle> = prunePending(overlay, shows, movies, Long.MAX_VALUE)

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

    // ------------------------------------------------------------ IPTV episode fallback (v2.6)

    /**
     * v2.6: shape an Xtream get_series_info document into the
     * details screen's season list — the fallback episode source
     * when TMDB is unreachable. Seasons ascend by number; seasons
     * with no usable episodes drop out; episode counts come from
     * the shaped episode lists so chips and lists always agree.
     */
    fun iptvSeasonInfos(info: IptvLogic.SeriesInfo): List<SeasonInfo> =
        info.seasons
            .map { SeasonInfo(it.number, iptvEpisodeCount(it), "") }
            .filter { it.episodeCount > 0 }
            .sortedBy { it.number }

    /** Usable episodes in one IPTV season (positive, deduped numbers). */
    private fun iptvEpisodeCount(season: IptvLogic.Season): Int =
        season.episodes.map { it.num }.filter { it > 0 }.distinct().size

    /**
     * v2.6: one IPTV season shaped as details [EpisodeInfo]s —
     * ascending, deduped by episode number (a panel that lists an
     * episode twice must not fork the SxEy watched keys), the
     * Xtream episode title when it has one, else "Episode N".
     * Air date / overview don't exist on the IPTV side — blank.
     */
    fun iptvEpisodes(info: IptvLogic.SeriesInfo, season: Int): List<EpisodeInfo> {
        val s = info.seasons.firstOrNull { it.number == season } ?: return emptyList()
        return s.episodes
            .filter { it.num > 0 }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .map { ep ->
                EpisodeInfo(
                    number = ep.num,
                    name = ep.title.trim().ifBlank { "Episode ${ep.num}" },
                    airDate = "",
                    overview = ""
                )
            }
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
        // v2.6: tracker ≥1.14.5 refines transport failures to
        // "io:<exception class>" (io:UnknownHostException, …). The
        // colon form is the same IO bucket — prefix match, never
        // an exact-string compare that would break on the suffix.
        raw == "io" || raw.startsWith("io:") -> TmdbError.IO
        raw.startsWith("http_") -> TmdbError.HTTP
        else -> TmdbError.UNKNOWN
    }

    /**
     * v2.6: the raw provider token for display — the trimmed
     * contract error string itself ("io:UnknownHostException",
     * "no_key", "http_401"), "" when there is no error to name.
     * Rendered in parentheses after the human line so a report
     * always carries the machine-readable cause.
     */
    fun tmdbErrorToken(raw: String?): String {
        val t = raw?.trim().orEmpty()
        return if (t.isEmpty() || tmdbErrorKind(t) == TmdbError.NONE) "" else t
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

    /**
     * The user-facing line for a tmdb_get failure state. v2.6: the
     * human text is unchanged, with the raw provider token
     * appended in parentheses ("… then try again.
     * (io:UnknownHostException)") wherever the caller renders it —
     * details, search and diagnostics all flow through here or
     * [tmdbDiagText].
     */
    fun tmdbUserLine(raw: String?): String {
        val line = when (tmdbErrorKind(raw)) {
            TmdbError.NONE -> ""
            TmdbError.NO_KEY ->
                "No TMDB key yet — add it in Sreeb Settings › Data & about, then try again."
            TmdbError.BAD_PATH ->
                "Something's wrong on our side (bad request path) — it's noted in Diagnostics."
            TmdbError.HTTP ->
                "TMDB answered with an error (HTTP ${tmdbHttpCode(raw)}). Try again in a bit."
            TmdbError.IO ->
                "Couldn't reach TMDB — check the TV's connection, then try again."
            TmdbError.UNKNOWN ->
                "TMDB lookup failed. Try again in a bit."
        }
        if (line.isEmpty()) return ""
        val token = tmdbErrorToken(raw)
        return if (token.isEmpty()) line else "$line ($token)"
    }

    /**
     * The Settings diagnostics `tmdb:` wording for the last recorded
     * TMDB outcome. [stored] = "ok" (tracker proxy), "ok_direct"
     * (the launcher's own key), a proxy contract error string, or
     * null when no call has run yet on this install. [direct] =
     * the direct path's note when the proxy failed: "not_set" (no
     * launcher key), its own error token, or blank when the direct
     * path never had to run. The human wording stays; the raw
     * token rides in parentheses — "network error
     * (io:UnknownHostException)" names DNS vs TLS vs timeout at a
     * glance; with tracker ≤1.14.4 the token is the bare "io".
     * Neither value can contain key material by construction.
     */
    fun tmdbDiagText(stored: String?, direct: String?): String {
        if (stored == null) return "not used yet"
        val base = when {
            stored == "ok" -> "OK via tracker"
            stored == "ok_direct" -> "OK via launcher key"
            else -> when (tmdbErrorKind(stored)) {
                TmdbError.NO_KEY -> "no launcher key set (no_key)"
                TmdbError.BAD_PATH -> "bad path (bad_path)"
                TmdbError.HTTP -> "http ${tmdbHttpCode(stored)} (${tmdbErrorToken(stored)})"
                TmdbError.IO -> "network error (${tmdbErrorToken(stored)})"
                else -> "error: $stored"
            }
        }
        val d = direct?.trim().orEmpty()
        return when {
            d.isEmpty() -> base
            d == "not_set" -> "$base · launcher key: not set"
            else -> "$base · direct: $d"
        }
    }

    // ------------------------------------------------------------ TMDB resolution order (v2.6)

    /** The composed result of one TMDB resolution (proxy → direct). */
    data class TmdbResolution(
        val json: String?,
        /** The error to surface when both paths failed — always the PROXY's. */
        val error: String?,
        /** True when [json] came from the launcher-key direct path. */
        val viaDirect: Boolean,
        /** The direct path's own error token (diagnostics only). */
        val directError: String?
    )

    /**
     * The TMDB resolution order, pure: (a) the tracker proxy's
     * answer stands when it succeeded; (b) on ANY proxy error, the
     * direct path runs — but only when a launcher key is set
     * ([direct] is not even invoked otherwise). Both failing
     * surfaces the PROXY error verbatim (its token is the briefed
     * diagnostic) and keeps the direct error alongside for the
     * diagnostics line. A throwing direct path degrades to its
     * class-name token, exactly like a returned failure.
     */
    fun resolveTmdb(
        proxyJson: String?,
        proxyError: String?,
        directKeySet: Boolean,
        direct: () -> Pair<String?, String?>
    ): TmdbResolution {
        if (proxyJson != null) {
            return TmdbResolution(proxyJson, null, viaDirect = false, directError = null)
        }
        val perr = proxyError ?: "io"
        if (!directKeySet) {
            return TmdbResolution(null, perr, viaDirect = false, directError = null)
        }
        val (dJson, dErr) = try {
            direct()
        } catch (e: Exception) {
            null to tmdbDirectError(e)
        }
        return if (dJson != null) {
            TmdbResolution(dJson, null, viaDirect = true, directError = null)
        } else {
            TmdbResolution(null, perr, viaDirect = false, directError = dErr ?: "io")
        }
    }

    /**
     * The direct client's transport-error token: the exception's
     * simple class name ONLY ("io:UnknownHostException") — never
     * the message, which can embed the request URL (and its key).
     */
    fun tmdbDirectError(e: Exception): String = "io:" + e.javaClass.simpleName

    /** The direct client's HTTP-error token (the provider contract's shape). */
    fun tmdbDirectHttpError(code: Int): String = "http_$code"
}
