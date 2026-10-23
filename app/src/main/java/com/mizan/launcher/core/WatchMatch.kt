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
 * Matching follows THE REPO METHOD (v2.7, user directive
 * 2026-10-23 — the matcher from his own IPTV web player,
 * iptv-player/index.html): a title's key is its lowercase with
 * EVERY character outside [a-z0-9] stripped — one squashed token.
 * Tier 1 is exact key equality; tier 2 is the first candidate
 * whose key CONTAINS the target key. The one adaptation the
 * catalogue's size forces (31,948 VOD + 15,528 series on-device;
 * the v2.4/v2.5 ANRs came from this exact area): keys are computed
 * ONCE per candidate into an index on the worker thread, the
 * exact tier is a hash lookup, and the contains tier scans only
 * precomputed strings — with a deterministic order inside it
 * (shortest key first, then catalogue order; a candidate whose
 * known year disagrees with the title's known year is
 * deprioritized within the tier). The [Match.possible] flag is
 * kept when the contains tier produced multiple DISTINCT titles,
 * or a tier never resolved cleanly — the UI never pretends a
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
     * The repo squash (v2.7): lowercase, then strip EVERY character
     * that is not [a-z0-9] — spaces and punctuation vanish entirely,
     * so a title becomes ONE token ("Grey's Anatomy" and "Greys
     * Anatomy" both squash to "greysanatomy"; "The Office (US)"
     * squashes to "theofficeus" — articles and suffixes are NOT
     * special-cased; that is the repo method, kept verbatim). There
     * is deliberately NO year stripping in the key: "1917" squashes
     * to "1917", and a catalogue name like "Dune 2021 1080p"
     * squashes to "dune20211080p" — which the contains tier still
     * finds for the target "dune". Both sides of a match go through
     * this exact function.
     */
    fun squash(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw.lowercase(Locale.ROOT)) {
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
        }
        return sb.toString()
    }

    /** The match key for a title — since v2.7 this IS [squash]. */
    fun normalizeKey(raw: String): String = squash(raw)

    /**
     * (squash key, effective year) for one side: a known year beats
     * the title's own token. The year never enters the key — it is
     * used ONLY to order candidates inside a tier.
     */
    fun keyOf(title: String, knownYear: Int): Pair<String, Int> {
        val (_, extracted) = splitYear(title)
        return Pair(squash(title), if (knownYear > 0) knownYear else extracted)
    }

    // ------------------------------------------------------------ matching

    /** One tracker title to match (year 0 = unknown). */
    data class TrackerTitle(val tmdbId: Long, val type: String, val title: String, val year: Int)

    /** One IPTV catalogue entry a tracker title can match (year 0 = unknown). */
    data class Candidate(val iptvId: Long, val name: String, val year: Int)

    /**
     * The match for one tracker title. [exact] = tier 1 (squash
     * equality) resolved to one candidate (uniquely, or a year
     * broke the tie with no conflict); [possible] = the best
     * available guess (a contains-tier hit over multiple distinct
     * titles, an unresolved same-key ambiguity, or a year
     * conflict) — surfaced to the user, never hidden.
     */
    data class Match(
        val tmdbId: Long,
        val type: String,
        val iptvId: Long,
        val exact: Boolean,
        val possible: Boolean
    )

    /**
     * A candidate with its squash key computed ONCE (v2.5,
     * retained by v2.7). The pre-v2.5 matcher re-normalized EVERY
     * candidate for EVERY tracker title, so a match run cost
     * O(titles × candidates) full normalizations against a
     * catalogue of tens of thousands — the 2026-10-22 dropbox ANR
     * caught exactly that on the main thread (normalizeKey under
     * matchOne under computeMatches). Now: candidates are keyed
     * once per run, the exact tier is a hash lookup, and the
     * contains tier scans precomputed squash strings only — no
     * string is ever squashed twice in a run.
     */
    class KeyedCandidate(val candidate: Candidate, val key: String, val year: Int)

    /** Key every candidate once (one [keyOf] per candidate). */
    fun keyCandidates(candidates: List<Candidate>): List<KeyedCandidate> =
        candidates.map { c ->
            val (key, year) = keyOf(c.name, c.year)
            KeyedCandidate(c, key, year)
        }

    /**
     * The exact-tier index over keyed candidates: squash key →
     * candidates, in catalogue order. Empty keys are excluded —
     * they can never exact-match (a title's key is non-empty by
     * the time tiers are consulted) nor contains-match (an empty
     * candidate key contains nothing).
     */
    fun exactIndex(keyed: List<KeyedCandidate>): Map<String, List<KeyedCandidate>> {
        val map = HashMap<String, ArrayList<KeyedCandidate>>()
        for (k in keyed) {
            if (k.key.isEmpty()) continue
            map.getOrPut(k.key) { ArrayList() }.add(k)
        }
        return map
    }

    /** Within-tier ordering penalty: a known-year disagreement sorts last. */
    private fun yearPenalty(candYear: Int, titleYear: Int): Int =
        if (titleYear > 0 && candYear > 0 && candYear != titleYear) 1 else 0

    /**
     * Repo-method matching for one title against PRE-KEYED
     * candidates; null when no candidate's key equals or contains
     * the target key.
     *
     * Tier 1 (exact): candidates whose squash EQUALS the target
     * squash. A known year on both sides narrows the pool to the
     * agreeing candidates; the first in catalogue order is chosen.
     * The match is [Match.exact] when the pool resolved to one
     * candidate with no year conflict, else the first candidate
     * flagged possible.
     *
     * Tier 2 (contains): candidates whose squash CONTAINS the
     * target squash (the repo's one-directional includes — a
     * catalogue name carrying quality tags / years / suffixes
     * still finds its clean TMDB title). Order inside the tier is
     * deterministic: year-agreeing candidates first, then the
     * SHORTEST squash (the least-decorated name), then catalogue
     * order. The match is flagged possible when the tier held
     * multiple DISTINCT titles or the chosen candidate's known
     * year conflicts with the title's.
     */
    fun matchOneKeyed(
        t: TrackerTitle,
        keyed: List<KeyedCandidate>,
        exact: Map<String, List<KeyedCandidate>>
    ): Match? {
        if (keyed.isEmpty()) return null
        val (tKey, tYear) = keyOf(t.title, t.year)
        if (tKey.isEmpty()) return null
        val exactTier = exact[tKey] ?: emptyList()
        if (exactTier.isNotEmpty()) {
            val yearHits = if (tYear > 0) exactTier.filter { it.year == tYear } else emptyList()
            val pool = if (yearHits.isNotEmpty()) yearHits else exactTier
            val chosen = pool.first()
            val yearConflict = tYear > 0 && chosen.year > 0 && chosen.year != tYear
            val resolved = pool.size == 1 && !yearConflict
            return Match(t.tmdbId, t.type, chosen.candidate.iptvId, resolved, !resolved)
        }
        val containing = keyed.filter { it.key.contains(tKey) }
        if (containing.isEmpty()) return null
        val distinctTitles = containing.map { it.key }.distinct().size
        // sortedWith is stable: equal (penalty, length) pairs keep
        // catalogue order — the tier's final tie-break.
        val chosen = containing.sortedWith(
            compareBy<KeyedCandidate> { yearPenalty(it.year, tYear) }
                .thenBy { it.key.length }
        ).first()
        val yearConflict = tYear > 0 && chosen.year > 0 && chosen.year != tYear
        val possible = distinctTitles > 1 || yearConflict
        return Match(t.tmdbId, t.type, chosen.candidate.iptvId, false, possible)
    }

    /** Repo-method matching for one title; null when nothing matches. */
    fun matchOne(t: TrackerTitle, candidates: List<Candidate>): Match? {
        if (candidates.isEmpty()) return null
        val keyed = keyCandidates(candidates)
        return matchOneKeyed(t, keyed, exactIndex(keyed))
    }

    /**
     * Match every tracker title against one catalogue, keyed by
     * tmdbId. The catalogue is keyed + indexed ONCE for the whole
     * run (see [KeyedCandidate]) — the cost is O(candidates) key
     * computations plus one hash lookup per title on the exact
     * tier; the contains tier scans precomputed squash strings.
     */
    fun matchAll(tracker: List<TrackerTitle>, candidates: List<Candidate>): Map<Long, Match> {
        val out = LinkedHashMap<Long, Match>()
        if (tracker.isEmpty() || candidates.isEmpty()) return out
        val keyed = keyCandidates(candidates)
        val exact = exactIndex(keyed)
        for (t in tracker) {
            matchOneKeyed(t, keyed, exact)?.let { out[t.tmdbId] = it }
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
