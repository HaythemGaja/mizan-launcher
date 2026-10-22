package com.mizan.launcher.core

import java.util.Locale

/**
 * Watch matching (v2.3, spec §21) — pure decisions, no Android
 * imports. The Watch home style links the Tracker view to the IPTV
 * engine: tracker titles are matched against the Xtream series /
 * VOD catalogues BY TITLE (the tracker speaks TMDB ids, Xtream
 * speaks its own — there is no shared id space), matched episodes
 * are resolved through get_series_info, and a completed Watch play
 * auto-marks the title watched.
 *
 * Matching is deliberately conservative and fully deterministic:
 * titles are normalized (lowercase, punctuation stripped, one
 * leading article dropped, whitespace collapsed, a standalone
 * year token extracted), an exact-normalized match beats the fuzzy
 * tier (word-boundary prefix or identical token set), a year on
 * both sides breaks ties, and anything ambiguous resolves to the
 * first candidate flagged "possible" — the UI never pretends a
 * guess is a certainty. Same-name remakes CAN mismatch; the
 * possible flag is how that surfaces.
 */
object WatchMatch {

    // ------------------------------------------------------------ normalization

    private val YEAR_RE = Regex("""(19|20)\d{2}""")

    /**
     * Split a standalone year out of a raw title: returns the title
     * without that token, plus the year (0 = none found). A year is
     * "standalone" when it is wrapped in () or [], is the title's
     * last token, or is its first token — a 4-digit number buried
     * mid-title ("The 100" has none; "Apollo 13" is not 4 digits)
     * is part of the name and stays.
     */
    fun splitYear(raw: String): Pair<String, Int> {
        val matches = YEAR_RE.findAll(raw).toList()
        if (matches.isEmpty()) return Pair(raw, 0)
        val trimmed = raw.trim()
        val chosen = matches.lastOrNull { m ->
            val before = if (m.range.first > 0) raw[m.range.first - 1] else ' '
            val after = if (m.range.last + 1 < raw.length) raw[m.range.last + 1] else ' '
            (before == '(' && after == ')') || (before == '[' && after == ']')
        } ?: matches.lastOrNull { m ->
            raw.substring(m.range.last + 1).trim().trimEnd(')', ']').isEmpty()
        } ?: matches.firstOrNull { m ->
            raw.substring(0, m.range.first).trim().trimStart('(', '[').isEmpty()
        } ?: return Pair(raw, 0)
        val year = chosen.value.toIntOrNull() ?: return Pair(raw, 0)
        var rest = raw.removeRange(chosen.range)
        rest = rest.replace("()", "").replace("[]", "").trim()
        return Pair(rest, year)
    }

    /**
     * The match key for a title: year token removed, lowercased,
     * every non letter/digit becomes a space, whitespace collapsed,
     * ONE leading article ("the"/"a"/"an") dropped. Both sides of a
     * match go through this exact function, so punctuation and
     * casing differences between TMDB and Xtream names vanish.
     */
    fun normalizeKey(raw: String): String {
        val (noYear, _) = splitYear(raw)
        val sb = StringBuilder()
        for (c in noYear.lowercase(Locale.ROOT)) {
            sb.append(if (c.isLetterOrDigit()) c else ' ')
        }
        val tokens = sb.toString().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val kept = if (tokens.size > 1 && tokens[0] in ARTICLES) tokens.drop(1) else tokens
        return kept.joinToString(" ")
    }

    private val ARTICLES = setOf("the", "a", "an")

    /** (key, effective year) for one side: a known year beats the title's own token. */
    fun keyOf(title: String, knownYear: Int): Pair<String, Int> {
        val (_, extracted) = splitYear(title)
        return Pair(normalizeKey(title), if (knownYear > 0) knownYear else extracted)
    }

    // ------------------------------------------------------------ matching

    /** One tracker title to match (year 0 = unknown). */
    data class TrackerTitle(val tmdbId: Long, val type: String, val title: String, val year: Int)

    /** One IPTV catalogue entry a tracker title can match (year 0 = unknown). */
    data class Candidate(val iptvId: Long, val name: String, val year: Int)

    /**
     * The match for one tracker title. [exact] = the normalized key
     * matched uniquely (or a year broke the tie); [possible] = the
     * best available guess (fuzzy tier, an unresolved ambiguity, or
     * a year conflict) — surfaced to the user, never hidden.
     */
    data class Match(
        val tmdbId: Long,
        val type: String,
        val iptvId: Long,
        val exact: Boolean,
        val possible: Boolean
    )

    /** Best-tier matching for one title; null when nothing is close. */
    fun matchOne(t: TrackerTitle, candidates: List<Candidate>): Match? {
        if (candidates.isEmpty()) return null
        val (tKey, tYear) = keyOf(t.title, t.year)
        if (tKey.isEmpty()) return null
        val keyed = candidates.map { c -> Triple(c, keyOf(c.name, c.year).first, keyOf(c.name, c.year).second) }
        val exactTier = keyed.filter { it.second == tKey }
        val fuzzyTier = if (exactTier.isEmpty()) {
            keyed.filter { (_, cKey, _) ->
                cKey.isNotEmpty() &&
                    (cKey.startsWith("$tKey ") || tKey.startsWith("$cKey ") ||
                        tokenSet(cKey) == tokenSet(tKey))
            }
        } else {
            emptyList()
        }
        val isExactTier = exactTier.isNotEmpty()
        val tier = if (isExactTier) exactTier else fuzzyTier
        if (tier.isEmpty()) return null
        val yearHits = if (tYear > 0) tier.filter { it.third == tYear } else emptyList()
        val pool = if (yearHits.isNotEmpty()) yearHits else tier
        val chosen = pool.first()
        val yearConflict = tYear > 0 && yearHits.isEmpty() && tier.any { it.third > 0 }
        val exact = isExactTier && pool.size == 1 && !yearConflict
        return Match(t.tmdbId, t.type, chosen.first.iptvId, exact, !exact)
    }

    private fun tokenSet(key: String): Set<String> = key.split(' ').filter { it.isNotEmpty() }.toSet()

    /** Match every tracker title against one catalogue, keyed by tmdbId. */
    fun matchAll(tracker: List<TrackerTitle>, candidates: List<Candidate>): Map<Long, Match> {
        val out = LinkedHashMap<Long, Match>()
        for (t in tracker) {
            matchOne(t, candidates)?.let { out[t.tmdbId] = it }
        }
        return out
    }

    // ------------------------------------------------------------ match cache

    /**
     * The cache fingerprint: catalogue counts + the catalogue fetch
     * time (a refresh re-matches), plus the tracker snapshot stamp
     * and a hash of the tracked ids (a changed library re-matches).
     */
    fun fingerprint(
        seriesCount: Int,
        vodCount: Int,
        iptvFetchedAt: Long,
        trackerUpdated: Long,
        trackerKeyHash: Int
    ): String = "$seriesCount/$vodCount@$iptvFetchedAt#$trackerUpdated:$trackerKeyHash"

    /** One line per match: "tmdbId|type|iptvId|exact|possible" (flags 1/0). Ids only — never titles, never URLs. */
    fun encodeMatches(matches: Collection<Match>): String =
        matches.joinToString("\n") {
            "${it.tmdbId}|${it.type}|${it.iptvId}|${if (it.exact) 1 else 0}|${if (it.possible) 1 else 0}"
        }

    /** Decode the match cache; malformed lines are skipped (fail-open). */
    fun decodeMatches(raw: String?): List<Match> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Match>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val f = line.split('|')
            if (f.size < 5) continue
            val tmdbId = f[0].toLongOrNull() ?: continue
            val iptvId = f[2].toLongOrNull() ?: continue
            if (tmdbId <= 0 || iptvId <= 0) continue
            out.add(Match(tmdbId, f[1], iptvId, f[3] == "1", f[4] == "1"))
        }
        return out
    }

    // ------------------------------------------------------------ episode resolution

    /** The IPTV episode for one tracker (season, episode), or null when the panel doesn't have it. */
    fun resolveEpisode(info: IptvLogic.SeriesInfo, season: Int, episode: Int): IptvLogic.Episode? =
        info.seasons.firstOrNull { it.number == season }
            ?.episodes?.firstOrNull { it.num == episode }

    // ------------------------------------------------------------ auto-mark

    /** A Watch play counts as watched-through at this fraction (the brief's ≥90%). */
    fun watchedThrough(posMs: Long, durMs: Long): Boolean =
        durMs > 0L && posMs > 0L && posMs * 10L >= durMs * 9L

    /**
     * The auto-mark decision (pure; the player supplies the facts):
     * fire ONCE per play, only for token-carrying (Watch) plays,
     * when playback ended or the position reached ≥90%. A partial
     * watch never fires; a non-Watch play (no token) never fires.
     */
    fun shouldAutoMark(
        hasToken: Boolean,
        alreadyFired: Boolean,
        ended: Boolean,
        posMs: Long,
        durMs: Long
    ): Boolean {
        if (!hasToken || alreadyFired) return false
        if (ended) return true
        return watchedThrough(posMs, durMs)
    }

    // ------------------------------------------------------------ UI model

    /** The poster-corner badge every matched card carries. */
    const val BADGE_TEXT = "▶ IPTV"

    fun cardBadge(matched: Boolean): String? = if (matched) BADGE_TEXT else null

    /** Hero primary label for a resolved Watch item: episodes named, movies plain. */
    fun heroPlayLabel(type: String, season: Int, episode: Int): String =
        if (type == "movie" || season <= 0 || episode <= 0) "▶ Play" else "▶ Play S$season E$episode"

    // ------------------------------------------------------------ diagnostics

    /** Watch coverage counts, for the `watch:` diagnostics line. */
    data class Stats(
        val showsMatched: Int,
        val showsTotal: Int,
        val moviesMatched: Int,
        val moviesTotal: Int,
        val episodesResolved: Int
    )

    fun diagText(s: Stats): String =
        "shows ${s.showsMatched}/${s.showsTotal} · movies ${s.moviesMatched}/${s.moviesTotal}" +
            " · episodes resolved ${s.episodesResolved}"
}
