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

    // ------------------------------------------------------------ key-only streaming match (v3.5, spec §35)
    //
    // The v2.5 matcher keyed the catalogue into KeyedCandidate
    // objects that still carried the full Candidate (name string)
    // — and data/IptvClient memoized the 47k raw Tuples FOREVER,
    // so a match run held tuples + keyed + index at once, and the
    // tuples never left. The key-only path drops the name at key
    // time: a catalogue entry survives ONLY as (iptvId, squash
    // key, year) — the match never needs the name again, and the
    // list is transient (the caller's worker-local), never
    // memoized. Same tiers, same ordering, same flags as
    // matchOneKeyed; the sim pins matchAllKeyed == matchAll over
    // shared fixtures (including the repo 'Preacher'->'Reacher'
    // contains case).
    //
    // A catalogue entry reduced to its match key at ingest — the
    // raw name is dropped immediately and is NOT retained here.
    data class KeyOnly(val iptvId: Long, val key: String, val year: Int)

    /** Key one catalogue entry, dropping its name (id + squash + year only). */
    fun keyOnlyOf(id: Long, name: String, year: Int): KeyOnly {
        val (key, effYear) = keyOf(name, year)
        return KeyOnly(id, key, effYear)
    }

    /** The exact-tier index over key-only entries (same rule as [exactIndex]: empty keys excluded). */
    fun exactIndexKeys(keyed: List<KeyOnly>): Map<String, List<KeyOnly>> {
        val map = HashMap<String, ArrayList<KeyOnly>>()
        for (k in keyed) {
            if (k.key.isEmpty()) continue
            map.getOrPut(k.key) { ArrayList() }.add(k)
        }
        return map
    }

    /**
     * Repo-method matching for one title against PRE-KEYED
     * key-only entries — the exact mirror of [matchOneKeyed]:
     * tier 1 exact (year-narrowed pool, first in catalogue order,
     * possible unless uniquely resolved with no year conflict),
     * tier 2 contains (year-agreeing first, then shortest squash,
     * then catalogue order — stable sort; possible when multiple
     * distinct titles or a year conflict).
     */
    fun matchOneKeyOnly(
        t: TrackerTitle,
        keyed: List<KeyOnly>,
        exact: Map<String, List<KeyOnly>>
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
            return Match(t.tmdbId, t.type, chosen.iptvId, resolved, !resolved)
        }
        val containing = keyed.filter { it.key.contains(tKey) }
        if (containing.isEmpty()) return null
        val distinctTitles = containing.map { it.key }.distinct().size
        // sortedWith is stable: equal (penalty, length) pairs keep
        // catalogue order — the tier's final tie-break.
        val chosen = containing.sortedWith(
            compareBy<KeyOnly> { yearPenalty(it.year, tYear) }
                .thenBy { it.key.length }
        ).first()
        val yearConflict = tYear > 0 && chosen.year > 0 && chosen.year != tYear
        val possible = distinctTitles > 1 || yearConflict
        return Match(t.tmdbId, t.type, chosen.iptvId, false, possible)
    }

    /** Match every tracker title against a key-only catalogue (transient; never memoized). */
    fun matchAllKeyed(tracker: List<TrackerTitle>, keyed: List<KeyOnly>): Map<Long, Match> {
        val out = LinkedHashMap<Long, Match>()
        if (tracker.isEmpty() || keyed.isEmpty()) return out
        val exact = exactIndexKeys(keyed)
        for (t in tracker) {
            matchOneKeyOnly(t, keyed, exact)?.let { out[t.tmdbId] = it }
        }
        return out
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

    // ------------------------------------------------------------ streamed match (v3.6, ask 53)
    //
    // The v3.5 key-only path still materialises the whole
    // catalogue as a List<KeyOnly> before matching — 47k
    // objects, transient but concurrent with the tracker
    // parse. The streamed path below matches ONE visible
    // title at a time over a re-openable line sequence
    // (a file-backed catalogue-key scan): the exact tier
    // collects only exact candidates, the contains tier
    // collects only containing candidates, and nothing
    // else is retained. The sequence is never turned into
    // a list — a caller that needs a second pass re-opens
    // it (the factory), which is exactly what a file scan
    // does. Tiers, ordering and flags are the same code
    // shape as matchOneKeyOnly, and the sim pins
    // matchStreamed == matchAllKeyed over shared fixtures,
    // including the repo 'Preacher'->'Reacher' case.
    // A trigram index is NOT built here (audit Phase 2,
    // optional, post-survival only) — Phase 1 bounds the
    // contains scan to visible titles, it does not pretend
    // to eliminate it.

    /** Match one title over a re-openable key-only sequence (exact pass, then contains pass if needed). */
    fun matchOneStreamed(
        t: TrackerTitle,
        keyFactory: () -> Sequence<KeyOnly>
    ): Match? {
        val (tKey, tYear) = keyOf(t.title, t.year)
        if (tKey.isEmpty()) return null
        val exactTier = ArrayList<KeyOnly>()
        for (k in keyFactory()) {
            if (k.key.isEmpty()) continue
            if (k.key == tKey) exactTier.add(k)
        }
        if (exactTier.isNotEmpty()) {
            val yearHits = if (tYear > 0) exactTier.filter { it.year == tYear } else emptyList()
            val pool = if (yearHits.isNotEmpty()) yearHits else exactTier
            val chosen = pool.first()
            val yearConflict = tYear > 0 && chosen.year > 0 && chosen.year != tYear
            val resolved = pool.size == 1 && !yearConflict
            return Match(t.tmdbId, t.type, chosen.iptvId, resolved, !resolved)
        }
        val containing = ArrayList<KeyOnly>()
        val distinct = HashSet<String>()
        for (k in keyFactory()) {
            if (k.key.isEmpty()) continue
            if (k.key.contains(tKey)) {
                containing.add(k)
                distinct.add(k.key)
            }
        }
        if (containing.isEmpty()) return null
        // sortedWith is stable: equal (penalty, length) pairs keep
        // catalogue order — the tier's final tie-break.
        val chosen = containing.sortedWith(
            compareBy<KeyOnly> { yearPenalty(it.year, tYear) }
                .thenBy { it.key.length }
        ).first()
        val yearConflict = tYear > 0 && chosen.year > 0 && chosen.year != tYear
        val possible = distinct.size > 1 || yearConflict
        return Match(t.tmdbId, t.type, chosen.iptvId, false, possible)
    }

    /** Match visible titles only, each over the streamed catalogue (never a materialised key list). */
    fun matchStreamed(
        tracker: List<TrackerTitle>,
        keyFactory: () -> Sequence<KeyOnly>
    ): Map<Long, Match> {
        val out = LinkedHashMap<Long, Match>()
        for (t in tracker) {
            matchOneStreamed(t, keyFactory)?.let { out[t.tmdbId] = it }
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

    // ------------------------------------------------------------ play routing (v2.9)

    /**
     * What a press on a title does (ask F.25, v2.9): a title
     * MATCHED to the IPTV catalogue PLAYS — the match alone is
     * the gate, never whether a resolution happens to be cached
     * yet (the v2.8 hero checked the series-info cache at build
     * time, so a matched-but-unwarmed show rendered Details as
     * its primary and no Play button existed at all). An
     * unmatched title opens its in-launcher Details. "Open in
     * Tracker" is not a press outcome anywhere — menus only.
     */
    enum class PressAction { PLAY, DETAILS }

    /** The press routing for one title: matched → PLAY, unmatched → DETAILS. */
    fun pressAction(matched: Boolean): PressAction =
        if (matched) PressAction.PLAY else PressAction.DETAILS

    /** The hero's primary button for one hero item (label + routing). */
    data class HeroPrimary(val label: String, val action: PressAction)

    /**
     * The hero primary decision (v2.9): a matched hero leads with
     * ▶ Play (episode-named for shows) whether or not its stream
     * is resolved yet — the press resolves on demand (see
     * [ResolvePhase]); an unmatched hero leads with Details.
     */
    fun heroPrimary(matched: Boolean, type: String, season: Int, episode: Int): HeroPrimary =
        if (matched) {
            HeroPrimary(heroPlayLabel(type, season, episode), PressAction.PLAY)
        } else {
            HeroPrimary("ⓘ Details", PressAction.DETAILS)
        }

    /**
     * The on-demand resolution flow behind a Play press on a
     * matched-but-unresolved title (ask F.25: "resolving the
     * episode on demand … with a visible finding-stream state,
     * then playing"). The activity owns the views; this machine
     * owns the states so the sim can pin the flow:
     *
     *   IDLE --PRESS--> FINDING --FOUND--> PLAYING
     *     ^               |
     *     +---NOT_FOUND---+  (button restored, details fallback)
     *
     * A PRESS while FINDING is ignored (no double resolution);
     * PLAYING is terminal for the press (the player is opening).
     */
    enum class ResolvePhase { IDLE, FINDING, PLAYING, FAILED }

    /** Events that move the resolution flow. */
    enum class ResolveEvent { PRESS, FOUND, NOT_FOUND }

    /** The visible button label for a phase (the primary's own label returns on FAILED). */
    fun resolveLabel(phase: ResolvePhase, primaryLabel: String): String =
        if (phase == ResolvePhase.FINDING) "Finding stream…" else primaryLabel

    /** One transition of the resolution flow; unknown pairs hold their phase. */
    fun resolvePhase(phase: ResolvePhase, event: ResolveEvent): ResolvePhase =
        when (phase to event) {
            ResolvePhase.IDLE to ResolveEvent.PRESS -> ResolvePhase.FINDING
            ResolvePhase.FAILED to ResolveEvent.PRESS -> ResolvePhase.FINDING
            ResolvePhase.FINDING to ResolveEvent.FOUND -> ResolvePhase.PLAYING
            ResolvePhase.FINDING to ResolveEvent.NOT_FOUND -> ResolvePhase.FAILED
            else -> phase
        }

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
