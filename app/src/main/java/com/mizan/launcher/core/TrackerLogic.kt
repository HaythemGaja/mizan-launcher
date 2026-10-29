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
     * A hero-panel candidate, unified from the Tracker sections
     * (v2.1 pool widening; v3.1: Movie Night removed). [source] is
     * the EXACT on-screen section name the candidate came from
     * ("Up Next", "Continue Watching", "For You") — the hero panel renders
     * it as a small source label above the title. Continue items
     * carry no episode coordinates and no air date (season/episode
     * 0, dateIso ""), so a mark-watched from a Continue-sourced hero
     * resolves its coordinates through the matching Up Next item
     * when one exists (see MainActivity). Movie heroes use the
     * movie mark path (season/episode 0, type "movie"); For You
     * heroes have nothing to mark — their actions are Details +
     * Add to my list.
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
        val fromContinue: Boolean,
        val source: String
    )

    /** Hero source labels — the exact section names, one per source. */
    const val SOURCE_UP_NEXT = "Up Next"

    // v2.9: the hero primary decision moved to
    // core/WatchMatch.heroPrimary — the v2.8 rule keyed on
    // "play RESOLVABLE" (already cached), which is exactly what
    // left a matched-but-unwarmed hero with no Play button; the
    // v2.9 rule keys on the MATCH and resolves on demand (ask
    // F.25). heroPrimaryAction is retired with it.
    const val SOURCE_CONTINUE = "Continue Watching"
    const val SOURCE_FOR_YOU = "For You"

    /** v3.1 (hero schedule): a tracked show's next episode already aired — the hero's first tier. */
    const val SOURCE_MISSED = "Missed"

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

    /**
     * A TMDB id from a snapshot value. v2.1: numeric STRINGS are
     * accepted too (defensive — a bridge that serializes ids as
     * strings must not silently empty a whole section; that failure
     * mode is exactly what the v2.1 empty-section investigation chased).
     */
    private fun num(v: Any?): Long =
        (v as? Number)?.toLong() ?: (v as? String)?.trim()?.toLongOrNull() ?: 0L
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
     * The v1.8 two-source pool, preserved EXACTLY (backdrop-first
     * Up Next + Continue) for the original callers/tests. The home
     * no longer uses this overload — it uses the schedule overload
     * below (v3.1 hero amendment).
     */
    fun heroCandidates(
        upNext: List<UpNextItem>,
        cont: List<ContinueItem>
    ): List<HeroItem> {
        fun UpNextItem.hero() = HeroItem(
            tmdbId, title, sub, dateIso, poster, backdrop, overview,
            type, season, episode, fromContinue = false, source = SOURCE_UP_NEXT
        )
        fun ContinueItem.hero() = HeroItem(
            tmdbId, title, sub, "", poster, backdrop, overview,
            type, season = 0, episode = 0, fromContinue = true, source = SOURCE_CONTINUE
        )
        return upNext.filter { it.backdrop.isNotBlank() }.map { it.hero() } +
            cont.filter { it.backdrop.isNotBlank() }.map { it.hero() } +
            upNext.filter { it.backdrop.isBlank() }.map { it.hero() } +
            cont.filter { it.backdrop.isBlank() }.map { it.hero() }
    }

    /**
     * Fill blank snapshot air dates for hero shaping (v3.1 hero
     * amendment, TMDB fallback): the tracker's /upnext snapshot
     * fields win whenever present; a blank date fills from the
     * discovery worker's last-known TMDB facts ([facts], keyed by
     * TMDB id — memory only, never a fetch). The fill is
     * conservative on purpose: a date fills only when the facts'
     * next episode MATCHES the item's episode coordinates, or
     * when the item carries no coordinates at all (then the
     * facts' coordinates fill too, as one consistent pair). A
     * date for a DIFFERENT episode never lands on this title —
     * the item keeps its blank date and sorts into the up-next
     * tier (unknown dates never empty the hero).
     */
    fun fillHeroDates(
        upNext: List<UpNextItem>,
        facts: Map<Long, Discovery.TvFacts>
    ): List<UpNextItem> = upNext.map { item ->
        if (item.dateIso.isNotBlank()) {
            item
        } else {
            val f = facts[item.tmdbId]
            if (f == null || f.nextDateIso.isBlank()) {
                item
            } else if (item.season <= 0 || item.episode <= 0) {
                item.copy(
                    dateIso = f.nextDateIso,
                    season = f.nextSeason, episode = f.nextEpisode
                )
            } else if (f.nextSeason == item.season && f.nextEpisode == item.episode) {
                item.copy(dateIso = f.nextDateIso)
            } else {
                item
            }
        }
    }

    /**
     * The hero cycle list (v3.1 hero amendment: the hero comes
     * from the SCHEDULE). Tiers, in order:
     * 1. Missed — the next unwatched episode already aired (air
     *    date strictly before [todayIso], device local date),
     *    most overdue first (earliest air date first).
     * 2. Up next — next episodes watchable now: air date == today,
     *    or unknown (blank). Dated items keep snapshot order;
     *    unknown dates sort last within the tier.
     * A show whose next episode has NOT aired yet (air date after
     * today) is NOT hero material — it stays in Coming Soon.
     * Dates compare lexicographically on trimmed yyyy-MM-dd
     * (chronological for well-formed ISO; the tracker emits
     * padded ISO, and a malformed date sorts as unknown-adjacent
     * rather than crashing the pool).
     *
     * No title in both hero and Coming Soon: a candidate is
     * dropped when [comingSoon] lists the SAME title with the
     * SAME episode coordinates (Coming Soon's sub carries
     * "SxEy"). A Coming Soon entry without coordinates cannot
     * prove sameness, so it never evicts a hero item — and a
     * behind-on-show case (hero S1E5, Coming Soon S1E8) keeps
     * its hero, because the episodes differ. Ties on the same
     * episode resolve to Coming Soon.
     *
     * If the schedule pool is entirely empty (no Up Next items
     * with a next episode — e.g. everything is future-dated),
     * the pool falls back to the previous behaviour: Continue
     * Watching (backdrop-first, v1.8 rule) then For You picks
     * with a known backdrop. The hero is never empty while the
     * library has content. Callers pass the ALREADY-FILTERED
     * section lists (marked-set / overlay filtering upstream).
     *
     * Labels: missed heroes read "Missed · SxEy · Mon D" (the
     * date chip, when the air date is known); up-next heroes
     * read "Up next · SxEy". Source labels are "Missed" /
     * "Up Next" respectively. A backdrop-less hero still renders
     * (over the page gradient, as the old movie heroes did) —
     * backdrop presence never reorders the schedule tiers.
     */
    fun heroCandidates(
        upNext: List<UpNextItem>,
        cont: List<ContinueItem>,
        picks: List<ForYouPick>,
        backdrops: Map<String, String>,
        todayIso: String,
        comingSoon: List<Discovery.SoonItem> = emptyList()
    ): List<HeroItem> {
        fun coordsOf(item: UpNextItem): String =
            if (item.season > 0 && item.episode > 0) {
                "S${item.season} E${item.episode}"
            } else {
                ""
            }
        fun UpNextItem.missedHero() = HeroItem(
            tmdbId = tmdbId, title = title,
            sub = listOfNotNull(
                "Missed",
                coordsOf(this).ifBlank { null },
                shortDate(dateIso).ifBlank { null }
            ).joinToString(" · "),
            dateIso = dateIso, poster = poster, backdrop = backdrop,
            overview = overview, type = type,
            season = season, episode = episode,
            fromContinue = false, source = SOURCE_MISSED
        )
        fun UpNextItem.upNextHero() = HeroItem(
            tmdbId = tmdbId, title = title,
            sub = listOfNotNull(
                "Up next",
                coordsOf(this).ifBlank { null }
            ).joinToString(" · "),
            dateIso = dateIso, poster = poster, backdrop = backdrop,
            overview = overview, type = type,
            season = season, episode = episode,
            fromContinue = false, source = SOURCE_UP_NEXT
        )
        fun ContinueItem.hero() = HeroItem(
            tmdbId, title, sub, "", poster, backdrop, overview,
            type, season = 0, episode = 0, fromContinue = true, source = SOURCE_CONTINUE
        )
        fun ForYouPick.hero(backdropPath: String) = HeroItem(
            tmdbId = id,
            title = title,
            sub = reason,
            dateIso = "",
            poster = poster,
            backdrop = backdropPath,
            overview = "",
            type = type,
            season = 0,
            episode = 0,
            fromContinue = false,
            source = SOURCE_FOR_YOU
        )
        val today = todayIso.trim()
        val todayDay = isoDays(today)
        val eligible = if (today.isBlank() || todayDay == null) {
            emptyList()
        } else {
            // v3.8: the old rule dropped an Up Next item from the
            // hero whenever Coming Soon listed the same show at
            // the same coordinates — regardless of date. On his
            // TV that emptied the hero: the only today item
            // (Tracker S4 E4, Oct 25) is also a Coming Soon item
            // (Coming Soon covers today + future), every other
            // Up Next item is future-dated (excluded from both
            // schedule tiers), and the fallback below was empty.
            // The exclusion's purpose is only to keep a FUTURE
            // teaser from doubling as the hero — a today twin is
            // not a teaser, it is today's episode, so it stays
            // eligible. A twin with a blank/unparseable date
            // also stays eligible (never drop a hero on a date
            // that cannot be proven future). Tier order is
            // unchanged: missed first, then today/blank.
            upNext.filter { item ->
                val se = coordsOf(item)
                if (se.isBlank()) return@filter true
                val futureTwin = comingSoon.any { soon ->
                    soon.tmdbId == item.tmdbId && soon.sub.isNotBlank() &&
                        soon.sub == se &&
                        (isoDays(soon.dateIso) ?: Long.MIN_VALUE) > todayDay
                }
                !futureTwin
            }
        }
        val missed = eligible
            .filter { it.dateIso.isNotBlank() && it.dateIso.trim() < today }
            .sortedBy { it.dateIso.trim() }
            .map { it.missedHero() }
        val upNextTier = eligible
            .filter { it.dateIso.isBlank() || it.dateIso.trim() == today }
            .sortedWith(compareBy<UpNextItem> { it.dateIso.isBlank() })
            .map { it.upNextHero() }
        val schedule = missed + upNextTier
        if (schedule.isNotEmpty()) return schedule
        // v3.8: the old fallback dropped every For You pick whose
        // backdrop was not in the backdrop cache (learned only
        // from details fetches) — so a home with picks but no
        // cached backdrops and an empty Continue list had NO
        // hero at all, even though the hero zone renders its
        // text + buttons fine over the wallpaper when the
        // backdrop is blank (applyHero fades the layer; no
        // backdrop is invented here, the field stays blank).
        val forYouHeroes = picks.map { pick ->
            pick.hero(backdrops[pick.key()].orEmpty())
        }
        return cont.filter { it.backdrop.isNotBlank() }.map { it.hero() } +
            cont.filter { it.backdrop.isBlank() }.map { it.hero() } +
            forYouHeroes
    }

    /** "Oct 20" for a yyyy-MM-dd date; "" when unparseable. */
    private fun shortDate(dateIso: String): String {
        val p = dateIso.trim().split("-")
        val m = p.getOrNull(1)?.toIntOrNull() ?: return ""
        val d = p.getOrNull(2)?.toIntOrNull() ?: return ""
        if (m !in 1..12 || d !in 1..31) return ""
        return "${MONTHS[m - 1]} $d"
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

    /**
     * Parse a strict yyyy-MM-dd date to epoch days; null when
     * malformed. v2.7: public as [isoEpochDay] — core/Discovery's
     * Coming Soon shaping applies the same strictness.
     */
    fun isoEpochDay(iso: String): Long? = isoDays(iso)

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
    /**
     * v3.16 "This week": the episodes of the tracked shows that air
     * from today through [days]-1 days ahead, earliest first — the one
     * view a media tracker is expected to have and this home lacked
     * (Up Next shows only the NEXT episode per show, Missed only the
     * past ones, so nothing answered "what's on this week?").
     *
     * Same guards as [missedRow]: a real coordinate, a parseable date,
     * one entry per (show, season, episode), deterministic order
     * (date, then title). A date outside the window — or an item with
     * no date — never enters the row. Pure: no Android, no clock of
     * its own, so the sim can pin it.
     */
    fun thisWeek(
        items: List<UpNextItem>,
        todayIso: String,
        days: Int = 7
    ): List<UpNextItem> {
        if (days <= 0) return emptyList()
        val today = isoDays(todayIso.trim()) ?: return emptyList()
        val last = today + (days - 1).toLong()
        val seen = HashSet<String>()
        return items.filter { item ->
            if (item.season <= 0 || item.episode <= 0) return@filter false
            val d = isoDays(item.dateIso.trim()) ?: return@filter false
            if (d < today || d > last) return@filter false
            seen.add(item.tmdbId.toString() + "|" + item.season + "|" + item.episode)
        }.sortedWith(
            compareBy<UpNextItem> { isoDays(it.dateIso.trim()) ?: Long.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
    }

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

    /**
     * One merged Up Next row item (v3.3, ask 47): Up Next +
     * Coming Soon are ONE section titled "Up Next". [upNext]
     * is non-null when the tracker has a next-episode item for
     * the show (the actionable card: play / mark watched); [soon]
     * is non-null when discovery lists a future airing for it.
     * Soon-only items (no tracker twin) carry [upNext] == null
     * and render as details-only cards, as Coming Soon did.
     */
    data class MergedUpNextItem(
        val tmdbId: Long,
        val title: String,
        val sub: String,
        val dateIso: String,
        val poster: String,
        val backdrop: String,
        val overview: String,
        val season: Int,
        val episode: Int,
        val type: String,
        val upNext: UpNextItem?,
        val soon: Discovery.SoonItem?
    )

    /**
     * Merge Up Next + Coming Soon into one section (v3.3, ask
     * 47 — the user: the two sections "do the same thing").
     * Dedupe is episode-precise, mirroring the v3.1 hero
     * exclusion: a Soon item is consumed by an Up Next item
     * only when it names the SAME show with the SAME episode
     * coordinates (Soon sub "S# E#" == the Up Next SxEy). A
     * coord-less pair never dedupes (sameness unproven — the
     * hero rule), and different coordinates (behind show:
     * Up Next S1E5, Soon S1E8) are different episodes, so both
     * stay. The Up Next card wins a deduped pair (it carries
     * the backdrop, the match/play path and mark-watched);
     * its date fills from the Soon twin only when blank (the
     * conservative fillHeroDates rule).
     * Tiers, mirroring the v3.1 hero schedule logic plus a
     * future tier at the end:
     * 1. Missed — Up Next items already aired, earliest first.
     * 2. Watchable — Up Next items airing today or dateless;
     *    dated keep snapshot order, dateless sort last.
     * 3. Future — everything dated after today (Soon-only
     *    items, today-airing Soon-only items first by date,
     *    and future Up Next items with no Soon twin),
     *    date ascending, title breaking ties.
     * The hero rule is UNCHANGED by this merge (missed →
     * up-next only; future stays out of the hero).
     */
    fun mergeUpNext(
        upNext: List<UpNextItem>,
        soon: List<Discovery.SoonItem>,
        todayIso: String
    ): List<MergedUpNextItem> {
        fun coordsOf(item: UpNextItem): String =
            if (item.season > 0 && item.episode > 0) {
                "S${item.season} E${item.episode}"
            } else {
                ""
            }
        fun coordsOfSoon(sub: String): Pair<Int, Int> {
            val parts = sub.trim().split(" ")
            if (parts.size != 2) return 0 to 0
            val s = parts[0].removePrefix("S").toIntOrNull() ?: return 0 to 0
            val e = parts[1].removePrefix("E").toIntOrNull() ?: return 0 to 0
            return if (s > 0 && e > 0) s to e else 0 to 0
        }
        val soonByShow = soon.groupBy { it.tmdbId }
        // Identity, not equals: a show and a movie can share a
        // numeric TMDB id, and two value-identical Soon cards
        // must not consume each other.
        val consumed =
            java.util.Collections.newSetFromMap<Discovery.SoonItem>(
                java.util.IdentityHashMap<Discovery.SoonItem, Boolean>()
            )
        val merged = ArrayList<MergedUpNextItem>()
        for (item in upNext) {
            val coords = coordsOf(item)
            val twin = if (coords.isBlank()) {
                null
            } else {
                soonByShow[item.tmdbId]?.firstOrNull { s ->
                    s !in consumed && s.sub == coords
                }
            }
            if (twin != null) consumed.add(twin)
            val date = item.dateIso.ifBlank { twin?.dateIso ?: "" }
            merged.add(
                MergedUpNextItem(
                    item.tmdbId, item.title, item.sub, date,
                    item.poster, item.backdrop, item.overview,
                    item.season, item.episode, item.type, item, twin
                )
            )
        }
        for (s in soon) {
            if (s in consumed) continue
            val (season, episode) = coordsOfSoon(s.sub)
            merged.add(
                MergedUpNextItem(
                    s.tmdbId, s.title, s.sub, s.dateIso,
                    s.poster, "", "", season, episode, s.type, null, s
                )
            )
        }
        val todayDay = isoDays(todayIso.trim())
        fun dayOf(iso: String): Long? = isoDays(iso.trim())
        val missed = merged.filter { m ->
            val d = dayOf(m.dateIso)
            m.upNext != null && d != null && todayDay != null && d < todayDay
        }.sortedBy { dayOf(it.dateIso) }
        val future = merged.filter { m ->
            if (m.upNext == null) {
                true
            } else {
                val d = dayOf(m.dateIso)
                todayDay != null && d != null && d > todayDay
            }
        }.sortedWith(
            compareBy<MergedUpNextItem> { dayOf(it.dateIso) ?: Long.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
        val watchable = merged.filter { m ->
            if (m.upNext == null) {
                false
            } else {
                val d = dayOf(m.dateIso)
                d == null || todayDay == null || d == todayDay
            }
        }.sortedWith(compareBy<MergedUpNextItem> { dayOf(it.dateIso) == null })
        return missed + watchable + future
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
     * v3.8 (poster backfill): the Up Next / Continue / Missed
     * cards are poster-first, but they can only show a poster
     * the item actually carries — a blank poster field falls
     * back to the backdrop still at the card, which is exactly
     * the "poster still bad in Up Next" report. The store and
     * slim codecs preserve whatever poster the snapshot had,
     * so a blank poster means the snapshot line itself had
     * none (a title whose poster was never learned from a
     * details fetch, or a line rewritten by the next-episode
     * refresh from a poster-less title line). The fix is a
     * fill, not a card change: a blank poster is replaced
     * from the launcher's own poster cache (TMDB poster
     * paths learned from earlier details fetches, keyed
     * type|tmdbId) — never invented, never fetched from the
     * tracker app, and a present poster is never overwritten.
     * An item with no cached poster keeps its blank field and
     * the card's backdrop fallback, unchanged.
     */
    fun fillUpNextPosters(
        items: List<UpNextItem>,
        posters: Map<String, String>
    ): List<UpNextItem> = items.map { item ->
        if (item.poster.isNotBlank()) item
        else {
            val cached = posters[LibraryLogic.posterKey(
                if (item.type == "movie") "movie" else "tv", item.tmdbId
            )].orEmpty()
            if (cached.isBlank()) item else item.copy(poster = cached)
        }
    }

    /** Same backfill for Continue items (same poster-first cards, same drop). */
    fun fillContinuePosters(
        items: List<ContinueItem>,
        posters: Map<String, String>
    ): List<ContinueItem> = items.map { item ->
        if (item.poster.isNotBlank()) item
        else {
            val cached = posters[LibraryLogic.posterKey(
                if (item.type == "movie") "movie" else "tv", item.tmdbId
            )].orEmpty()
            if (cached.isBlank()) item else item.copy(poster = cached)
        }
    }

    /**
     * v3.7 (ask: missed row): the Missed row's filter over the
     * missed worker's items (data/LibraryLocal writes
     * filesDir/tracker/missed.txt; the home reads it through
     * the store memo). Definition, implemented exactly:
     * passed episodes that aired in the past and are NOT
     * marked watched — for each tracked TV show, its next
     * unwatched episode whose air date is strictly before
     * today (device local date) — most overdue first
     * (earliest air date first), one card per show per
     * episode coordinate (episode-precise dedupe, the same
     * rule as merged Up Next). Future airings, today
     * airings, already-watched episodes, movies, and
     * coordinate-less items are all excluded. A title with
     * no usable air date does NOT enter this row (it stays
     * in Up Next's unknown-date tier). [isWatched] is the
     * caller-supplied watched predicate (home: marked set +
     * pending-overlay adds − overlay removes, because the
     * episode file is never opened on the home path; sim:
     * the episode-file set) — the worker already filtered
     * watched episodes when it wrote the file, so this is
     * the defense-in-depth pass for marks that landed
     * after the worker ran.
     */
    fun missedRow(
        items: List<UpNextItem>,
        isWatched: (UpNextItem) -> Boolean,
        todayIso: String
    ): List<UpNextItem> {
        val todayDay = isoDays(todayIso.trim()) ?: return emptyList()
        val seen = HashSet<String>()
        return items.filter { item ->
            if (item.type != "tv") return@filter false
            if (item.season <= 0 || item.episode <= 0) return@filter false
            val d = isoDays(item.dateIso.trim()) ?: return@filter false
            if (d >= todayDay) return@filter false
            if (isWatched(item)) return@filter false
            seen.add("${item.tmdbId}|${item.season}|${item.episode}")
        }.sortedWith(
            compareBy<UpNextItem> { isoDays(it.dateIso.trim()) ?: Long.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
    }

    /**
     * v3.7: one season's episode air dates, as the missed
     * worker reads them (TMDB season payloads, cached
     * per-title in filesDir/tracker/missed_cache/).
     */
    data class SeasonAirDates(
        val season: Int,
        /** (episode number, air date yyyy-MM-dd or "") in episode order. */
        val episodes: List<Pair<Int, String>>
    )

    /**
     * v3.7: the missed worker's per-show computation — the
     * first unwatched episode (in season, then episode
     * order) whose air date is strictly before today, or
     * null when the show has no passed unwatched episode.
     * Seasons scan ascending; specials (season 0) never
     * enter (the worker skips them at fetch time, same as
     * the tracker's continue computation). An unwatched
     * episode with a blank/unparseable date is skipped —
     * a date is never invented — and scanning continues
     * to the next unwatched episode, so one missing date
     * cannot hide a later passed episode. When the first
     * unwatched episode is future-dated, later episodes
     * of the same show cannot be past (airing is
     * sequential), so the scan still returns null for
     * that show in practice — the loop simply proves it
     * instead of assuming it.
     */
    fun firstMissedEpisode(
        watched: Set<String>,
        seasons: List<SeasonAirDates>,
        todayIso: String
    ): Triple<Int, Int, String>? {
        val todayDay = isoDays(todayIso.trim()) ?: return null
        for (season in seasons.sortedBy { it.season }) {
            if (season.season < 1) continue
            for ((episode, airDate) in season.episodes.sortedBy { it.first }) {
                if (episode < 1) continue
                if (LibraryLogic.epKey(season.season, episode) in watched) continue
                val d = isoDays(airDate.trim()) ?: continue
                if (d < todayDay) return Triple(season.season, episode, airDate.trim())
            }
        }
        return null
    }

    // ------------------------------------------------------------ v3.13: Want to Watch

    /**
     * v3.13: a tracked show counts as "want to watch" (interested
     * but not started) when it is planned AND has zero watched
     * episodes AND has no Continue entry (not in progress).
     * [continueIds] is the set of tmdbIds with a Continue item.
     * Count-only shows (watchedCount > 0, no set) are started,
     * never wanted — the count is the progress signal.
     */
    fun isWantToWatch(show: TrackedShow, continueIds: Set<Long>): Boolean =
        show.planned && show.watchedCount <= 0 && show.tmdbId !in continueIds

    /** v3.13: the want-to-watch tmdbIds of a tracked list. */
    fun wantToWatchIds(
        tracked: List<TrackedShow>,
        continueIds: Set<Long>
    ): Set<Long> = tracked
        .filter { isWantToWatch(it, continueIds) }
        .map { it.tmdbId }
        .toSet()

    /** v3.13: drop want-to-watch shows from a schedule list. */
    fun withoutWantToWatch(
        items: List<UpNextItem>,
        wantIds: Set<Long>
    ): List<UpNextItem> =
        if (wantIds.isEmpty()) items else items.filter { it.tmdbId !in wantIds }

    // ------------------------------------------------------------ v3.13: Missed grouping + dates

    /**
     * v3.13: one Missed entry per show, carrying ALL its passed
     * unwatched episodes — not just the earliest. [first*] is the
     * earliest episode in (season, episode) order, [last*] the
     * latest; [count] is the episode count. Dates are the
     * episodes' own air dates, never invented: a dateless
     * episode still counts, but contributes no date line.
     */
    data class MissedGroup(
        val tmdbId: Long,
        val title: String,
        val poster: String,
        val backdrop: String,
        val overview: String,
        val firstSeason: Int,
        val firstEpisode: Int,
        val firstDateIso: String,
        val lastSeason: Int,
        val lastEpisode: Int,
        val lastDateIso: String,
        val count: Int
    )

    /**
     * v3.13: group a missed-row list (already filtered by
     * [missedRow]) by show. Episodes sort by (season, episode);
     * groups sort most-overdue-first (earliest first air date,
     * dateless groups last, then title) — the same order the
     * flat row used. Groups with no usable episode are dropped;
     * an empty input yields an empty list (never a crash).
     */
    fun groupMissed(items: List<UpNextItem>): List<MissedGroup> {
        val byShow = LinkedHashMap<Long, MutableList<UpNextItem>>()
        for (item in items) {
            if (item.type != "tv" || item.season <= 0 || item.episode <= 0) continue
            byShow.getOrPut(item.tmdbId) { ArrayList() }.add(item)
        }
        return byShow.values.mapNotNull { eps ->
            val sorted = eps.sortedWith(
                compareBy<UpNextItem> { it.season }.thenBy { it.episode }
            )
            val first = sorted.firstOrNull() ?: return@mapNotNull null
            val last = sorted.last()
            MissedGroup(
                tmdbId = first.tmdbId, title = first.title,
                poster = first.poster, backdrop = first.backdrop,
                overview = first.overview,
                firstSeason = first.season, firstEpisode = first.episode,
                firstDateIso = first.dateIso,
                lastSeason = last.season, lastEpisode = last.episode,
                lastDateIso = last.dateIso,
                count = sorted.size
            )
        }.sortedWith(
            compareBy<MissedGroup> { isoDays(it.firstDateIso.trim()) ?: Long.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
    }

    /** v3.13: "S2 E3" for one episode, "S2 E3–E5" same-season, "S2 E3 – S3 E2" cross-season. */
    fun missedRangeLabel(group: MissedGroup): String {
        if (group.count <= 1) return "S${group.firstSeason} E${group.firstEpisode}"
        return if (group.firstSeason == group.lastSeason) {
            "S${group.firstSeason} E${group.firstEpisode}–E${group.lastEpisode}"
        } else {
            "S${group.firstSeason} E${group.firstEpisode} – S${group.lastSeason} E${group.lastEpisode}"
        }
    }

    /**
     * v3.13: "aired Oct 12 · 15 days ago" for a missed group's
     * first episode, computed from the device local date.
     * Returns "" when either date is unparseable or the air
     * date is not in the past — a date is never invented
     * and a future date never reads as missed.
     */
    fun missedDateLabel(firstDateIso: String, todayIso: String): String {
        val air = isoDays(firstDateIso.trim()) ?: return ""
        val today = isoDays(todayIso.trim()) ?: return ""
        val days = today - air
        if (days < 1L) return ""
        val whenText = shortDate(firstDateIso)
        val ago = if (days == 1L) "1 day ago" else "$days days ago"
        return if (whenText.isBlank()) ago else "aired $whenText · $ago"
    }

    /** v3.13: every passed unwatched episode of a show, in order — the missed worker's full scan. */
    fun allMissedEpisodes(
        watched: Set<String>,
        seasons: List<SeasonAirDates>,
        todayIso: String
    ): List<Triple<Int, Int, String>> {
        val todayDay = isoDays(todayIso.trim()) ?: return emptyList()
        val out = ArrayList<Triple<Int, Int, String>>()
        for (season in seasons.sortedBy { it.season }) {
            if (season.season < 1) continue
            for ((episode, airDate) in season.episodes.sortedBy { it.first }) {
                if (episode < 1) continue
                if (LibraryLogic.epKey(season.season, episode) in watched) continue
                val d = isoDays(airDate.trim()) ?: continue
                if (d < todayDay) out.add(Triple(season.season, episode, airDate.trim()))
            }
        }
        return out
    }

    // ------------------------------------------------------------ v3.13: For You rotation

    /**
     * v3.13: date-seeded rotation of a For You pick list. The
     * computed picks, seeds and scores are untouched — only
     * their display order changes, deterministically per day:
     * a Fisher–Yates shuffle seeded by the epoch day, so the
     * set order differs across days but is stable within a
     * day (no flicker on rebuild). Applied BEFORE the
     * cap-20 filter, so when the source holds more than 20
     * picks the displayed SUBSET changes too. Unparseable
     * today or a list of ≤ 1 returns the input unchanged.
     */
    fun rotateForYouPicks(
        picks: List<ForYouPick>,
        todayIso: String
    ): List<ForYouPick> {
        if (picks.size <= 1) return picks
        val day = isoDays(todayIso.trim()) ?: return picks
        val out = picks.toMutableList()
        var state = day * 6364136223846793005L + 1442695040888963407L
        for (i in out.size - 1 downTo 1) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val j = ((state ushr 33) % (i + 1)).toInt()
            val tmp = out[i]; out[i] = out[j]; out[j] = tmp
        }
        return out
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

    // ------------------------------------------------------------ for you (v2.0)

    /**
     * One /foryou pick (tracker ≥ 1.14.4; contract LAUNCHER_SYNC.md).
     * The pick shape is {id, type, title, poster_path, vote_average,
     * reason, whyGenres, score}: NO year and NO genre ids — [whyGenres]
     * already carries genre NAMES. [poster] is a TMDB path (leading
     * "/"), resolved through data/TmdbImages like every other poster.
     */
    data class ForYouPick(
        val id: Long,
        val type: String,
        val title: String,
        val poster: String,
        val voteAverage: Double,
        val reason: String,
        val whyGenres: List<String>,
        val score: Double
    ) {
        fun key(): String = "$type|$id"
    }

    fun parseForYou(json: String?): List<ForYouPick> {
        val arr = JsonMini.parse(json).asArr() ?: return emptyList()
        val out = ArrayList<ForYouPick>()
        for (raw in arr) {
            val o = raw.asObj() ?: continue
            val id = num(o["id"])
            val title = str(o, "title")
            if (id <= 0 || title.isBlank()) continue
            val genres = o["whyGenres"].asArr()
                ?.mapNotNull { it.asStr() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            out.add(
                ForYouPick(
                    id = id,
                    type = str(o, "type").ifBlank { "tv" },
                    title = title,
                    poster = str(o, "poster_path"),
                    voteAverage = (o["vote_average"] as? Number)?.toDouble() ?: 0.0,
                    reason = str(o, "reason"),
                    whyGenres = genres,
                    score = (o["score"] as? Number)?.toDouble() ?: 0.0
                )
            )
        }
        return out
    }

    /** "★ 7.4" for a pick's rating; null when there is no rating to show. */
    fun ratingText(voteAverage: Double): String? {
        if (voteAverage <= 0.0) return null
        val rounded = Math.round(voteAverage * 10) / 10.0
        return "★ " + String.format(java.util.Locale.US, "%.1f", rounded)
    }

    /**
     * One local For You overlay entry: the user tapped "Add to my list"
     * ([added] = true → an add_title action is queued with the tracker)
     * or "Not interested" ([added] = false → dismiss_foryou). The card
     * leaves the row immediately either way; [pruneForYouOverlay] drops
     * the entry once the tracker's snapshots have converged — the same
     * eventually-consistent pattern as the marked-set.
     */
    data class ForYouMark(
        val id: Long,
        val type: String,
        val added: Boolean,
        val at: Long
    ) {
        fun key(): String = "$type|$id"
    }

    fun encodeForYouOverlay(marks: List<ForYouMark>): String =
        marks.joinToString("\n") {
            "${it.id}|${it.type}|${if (it.added) "add" else "dismiss"}|${it.at}"
        }

    fun decodeForYouOverlay(raw: String?): List<ForYouMark> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<ForYouMark>()
        for (line in raw.split("\n")) {
            val p = line.trim().split("|")
            if (p.size != 4) continue
            val id = p[0].toLongOrNull() ?: continue
            val at = p[3].toLongOrNull() ?: continue
            if (id <= 0) continue
            out.add(
                ForYouMark(
                    id,
                    if (p[1] == "movie") "movie" else "tv",
                    p[2] == "add",
                    at
                )
            )
        }
        return out
    }

    /** Add (or refresh) one overlay entry; newest action wins per title. */
    fun withForYouMark(marks: List<ForYouMark>, mark: ForYouMark): List<ForYouMark> =
        marks.filter { it.key() != mark.key() } + mark

    /**
     * The For You row: snapshot picks minus anything already tracked
     * (by type+id, shows and movies), minus anything the local overlay
     * has added or dismissed, capped at [cap].
     */
    fun filterForYou(
        picks: List<ForYouPick>,
        tracked: List<TrackedShow>,
        movies: List<MovieItem>,
        overlay: List<ForYouMark>,
        cap: Int = 20
    ): List<ForYouPick> {
        val trackedKeys = HashSet<String>()
        for (show in tracked) trackedKeys.add("tv|${show.tmdbId}")
        for (movie in movies) trackedKeys.add("movie|${movie.tmdbId}")
        val overlayKeys = overlay.map { it.key() }.toHashSet()
        return picks.filter { it.key() !in trackedKeys && it.key() !in overlayKeys }
            .take(cap.coerceAtLeast(0))
    }

    /**
     * Convergence for the overlay, mirroring [pruneMarked]: only a
     * snapshot fresher than the mark may retire it.
     *  - an "add" retires when the title shows up in tracked/movies
     *    (the tracker applied add_title) OR when the pick leaves the
     *    /foryou snapshot (the mirror recomputed without it);
     *  - a "dismiss" retires when the pick leaves the /foryou snapshot.
     */
    fun pruneForYouOverlay(
        marks: List<ForYouMark>,
        picks: List<ForYouPick>,
        tracked: List<TrackedShow>,
        movies: List<MovieItem>,
        snapshotUpdated: Long
    ): List<ForYouMark> {
        if (marks.isEmpty()) return marks
        val pickKeys = picks.map { it.key() }.toHashSet()
        val trackedKeys = HashSet<String>()
        for (show in tracked) trackedKeys.add("tv|${show.tmdbId}")
        for (movie in movies) trackedKeys.add("movie|${movie.tmdbId}")
        return marks.filter { mark ->
            if (snapshotUpdated <= mark.at) {
                true // snapshot predates the action — nothing has converged yet
            } else if (mark.added) {
                mark.key() !in trackedKeys && mark.key() in pickKeys
            } else {
                mark.key() in pickKeys
            }
        }
    }
}
