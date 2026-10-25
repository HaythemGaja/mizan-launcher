package com.mizan.launcher.core

/**
 * The slim tracker cache (v3.4, ask 49): the fast-to-load twin of
 * the tracker snapshots.
 *
 * Why: a crash wipes the in-memory parsed memo, so the next open
 * re-parses the multi-megabyte tracker snapshot documents (the
 * /tracked document carries full watched-episode sets) before any
 * tracker row can render — that re-parse is a large share of the
 * ~67 s cold-start data readiness on Haythem's TV. The slim file
 * holds ONLY the fields the home consumes (titles, posters,
 * counts, coordinates — never episode sets, never raw blobs), so
 * it parses in milliseconds. The home paints from it on a cold
 * start; workers still re-parse the full documents behind it and
 * swap fresh data in when they land (the slim never replaces the
 * full parse — it only bridges the gap until it lands).
 *
 * Fingerprint rule: the slim is valid only while the raw inputs
 * are unchanged — (updated stamp, fetchedAt stamp, and the five
 * raw document lengths). Every provider fetch refreshes fetchedAt,
 * so a content change without a stamp change cannot arrive
 * through the fetch path; a hand-edited prefs file could serve
 * one stale slim until the next fetch, which fail-opens to the
 * full parse (the home is never wronger than its last fetch).
 *
 * Safety: size-capped (refused past [MAX_BYTES]); versioned
 * (a version mismatch reads as absent); credential-tripwired
 * (encode refuses any payload matching HomeCache's patterns —
 * slim fields never contain credentials, so a match means an
 * upstream bug, and refusing is free); any decode failure reads
 * as absent and the caller falls through to the full parse.
 */
object TrackerSlim {

    /**
     * v3.8: VERSION 2 — the slim file now carries the Missed
     * row's items ("miss", same UpNext shape as "up"). v1 files
     * are REJECTED (decode returns null), not upgraded in
     * place: a v1 slim has no missed field, so serving it
     * would keep the v3.7 bug alive (home paints from the
     * slim, tMissed = empty, no Missed row — while
     * missed.txt holds 12). Rejection falls through to the
     * full store parse, which carries missed, and the next
     * slim write is v2 with missed included.
     */
    const val VERSION = 2

    /** Slim documents past this are refused (the real one is tens of KB). */
    const val MAX_BYTES = 512 * 1024

    /** The decoded slim payload (lists only — stamps ride alongside). */
    data class Data(
        val upNext: List<TrackerLogic.UpNextItem>,
        val cont: List<TrackerLogic.ContinueItem>,
        val movies: List<TrackerLogic.MovieItem>,
        val tracked: List<TrackerLogic.TrackedShow>,
        val forYou: List<TrackerLogic.ForYouPick>,
        /** v3.8: the Missed row's items (missed.txt, UpNext shape). */
        val missed: List<TrackerLogic.UpNextItem> = emptyList(),
        val updated: Long,
        val forYouUpdated: Long,
        val fetchedAt: Long
    )

    /** Cheap validity key over the raw inputs (stamps + lengths, no parse). */
    fun fingerprint(
        updated: Long,
        fetchedAt: Long,
        upLen: Int,
        contLen: Int,
        movLen: Int,
        trkLen: Int,
        fyLen: Int
    ): String = "$updated|$fetchedAt|$upLen|$contLen|$movLen|$trkLen|$fyLen"

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    private fun q(s: String): String = "\"" + esc(s) + "\""

    /**
     * Serialize the parsed lists. Returns null when the payload
     * trips the credential rule (never expected — slim fields
     * carry no credentials; a trip means an upstream bug).
     */
    fun encode(
        upNext: List<TrackerLogic.UpNextItem>,
        cont: List<TrackerLogic.ContinueItem>,
        movies: List<TrackerLogic.MovieItem>,
        tracked: List<TrackerLogic.TrackedShow>,
        forYou: List<TrackerLogic.ForYouPick>,
        updated: Long,
        forYouUpdated: Long,
        fetchedAt: Long,
        fingerprint: String,
        missed: List<TrackerLogic.UpNextItem> = emptyList()
    ): String? {
        val sb = StringBuilder()
        sb.append("{\"v\":").append(VERSION)
        sb.append(",\"fp\":").append(q(fingerprint))
        sb.append(",\"updated\":").append(updated)
        sb.append(",\"fyUpdated\":").append(forYouUpdated)
        sb.append(",\"fetchedAt\":").append(fetchedAt)
        sb.append(",\"up\":[")
        var first = true
        for (it in upNext) {
            if (!first) sb.append(",")
            first = false
            sb.append("{\"tmdbId\":").append(it.tmdbId)
            sb.append(",\"title\":").append(q(it.title))
            sb.append(",\"sub\":").append(q(it.sub))
            sb.append(",\"sort\":").append(q(it.dateIso))
            sb.append(",\"poster\":").append(q(it.poster))
            sb.append(",\"backdrop\":").append(q(it.backdrop))
            sb.append(",\"overview\":").append(q(it.overview))
            sb.append(",\"season\":").append(it.season)
            sb.append(",\"episode\":").append(it.episode)
            sb.append(",\"type\":").append(q(it.type)).append("}")
        }
        sb.append("],\"cont\":[")
        first = true
        for (it in cont) {
            if (!first) sb.append(",")
            first = false
            sb.append("{\"tmdbId\":").append(it.tmdbId)
            sb.append(",\"title\":").append(q(it.title))
            sb.append(",\"sub\":").append(q(it.sub))
            sb.append(",\"watched\":").append(it.watched)
            sb.append(",\"total\":").append(it.total)
            sb.append(",\"poster\":").append(q(it.poster))
            sb.append(",\"backdrop\":").append(q(it.backdrop))
            sb.append(",\"overview\":").append(q(it.overview))
            sb.append(",\"type\":").append(q(it.type)).append("}")
        }
        sb.append("],\"mov\":[")
        first = true
        for (it in movies) {
            if (!first) sb.append(",")
            first = false
            sb.append("{\"tmdbId\":").append(it.tmdbId)
            sb.append(",\"title\":").append(q(it.title))
            sb.append(",\"releaseDate\":").append(q(it.releaseDate))
            sb.append(",\"planned\":").append(it.planned)
            sb.append(",\"poster\":").append(q(it.poster))
            sb.append(",\"watched\":").append(it.watched).append("}")
        }
        sb.append("],\"trk\":[")
        first = true
        for (it in tracked) {
            if (!first) sb.append(",")
            first = false
            sb.append("{\"tmdbId\":").append(it.tmdbId)
            sb.append(",\"name\":").append(q(it.name))
            sb.append(",\"watchedCount\":").append(it.watchedCount)
            sb.append(",\"planned\":").append(it.planned).append("}")
        }
        sb.append("],\"miss\":[")
        var mFirst = true
        for (it in missed) {
            if (!mFirst) sb.append(",")
            mFirst = false
            sb.append("{\"tmdbId\":").append(it.tmdbId)
            sb.append(",\"title\":").append(q(it.title))
            sb.append(",\"sub\":").append(q(it.sub))
            sb.append(",\"sort\":").append(q(it.dateIso))
            sb.append(",\"poster\":").append(q(it.poster))
            sb.append(",\"backdrop\":").append(q(it.backdrop))
            sb.append(",\"overview\":").append(q(it.overview))
            sb.append(",\"season\":").append(it.season)
            sb.append(",\"episode\":").append(it.episode)
            sb.append(",\"type\":").append(q(it.type)).append("}")
        }
        sb.append("],\"fy\":[")
        first = true
        for (it in forYou) {
            if (!first) sb.append(",")
            first = false
            sb.append("{\"id\":").append(it.id)
            sb.append(",\"type\":").append(q(it.type))
            sb.append(",\"title\":").append(q(it.title))
            sb.append(",\"poster_path\":").append(q(it.poster))
            sb.append(",\"vote_average\":").append(it.voteAverage)
            sb.append(",\"reason\":").append(q(it.reason))
            sb.append(",\"whyGenres\":[")
            var gFirst = true
            for (g in it.whyGenres) {
                if (!gFirst) sb.append(",")
                gFirst = false
                sb.append(q(g))
            }
            sb.append("],\"score\":").append(it.score).append("}")
        }
        sb.append("]}")
        val out = sb.toString()
        if (HomeCache.containsCredentialPattern(out)) return null
        return out
    }

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
    private fun dbl(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0
    private fun str(o: Map<String, Any?>, k: String): String = o[k].asStr() ?: ""
    private fun bool(v: Any?): Boolean = v.asBool() ?: false

    /**
     * Decode a slim document. Returns null on any failure: oversize,
     * version mismatch, fingerprint mismatch, malformed JSON. The
     * caller treats null as "no slim" and falls through to the
     * full parse — a bad slim can never empty the home.
     */
    fun decode(json: String?, expectedFingerprint: String): Data? {
        if (json == null || json.length > MAX_BYTES) return null
        return try {
            val root = JsonMini.parse(json).asObj() ?: return null
            if (num(root["v"]) != VERSION.toLong()) return null
            if (str(root, "fp") != expectedFingerprint) return null
            val upNext = ArrayList<TrackerLogic.UpNextItem>()
            for (raw in root["up"].asArr() ?: emptyList()) {
                val o = raw.asObj() ?: continue
                val id = num(o["tmdbId"])
                val title = str(o, "title")
                if (id <= 0L || title.isBlank()) continue
                upNext.add(
                    TrackerLogic.UpNextItem(
                        tmdbId = id, title = title, sub = str(o, "sub"),
                        dateIso = str(o, "sort"), poster = str(o, "poster"),
                        backdrop = str(o, "backdrop"), overview = str(o, "overview"),
                        season = num(o["season"]).toInt(),
                        episode = num(o["episode"]).toInt(),
                        type = str(o, "type").ifBlank { "tv" }
                    )
                )
            }
            val cont = ArrayList<TrackerLogic.ContinueItem>()
            for (raw in root["cont"].asArr() ?: emptyList()) {
                val o = raw.asObj() ?: continue
                val id = num(o["tmdbId"])
                val title = str(o, "title")
                if (id <= 0L || title.isBlank()) continue
                cont.add(
                    TrackerLogic.ContinueItem(
                        tmdbId = id, title = title, sub = str(o, "sub"),
                        watched = num(o["watched"]).toInt(),
                        total = num(o["total"]).toInt(),
                        poster = str(o, "poster"), backdrop = str(o, "backdrop"),
                        overview = str(o, "overview"),
                        type = str(o, "type").ifBlank { "tv" }
                    )
                )
            }
            val movies = ArrayList<TrackerLogic.MovieItem>()
            for (raw in root["mov"].asArr() ?: emptyList()) {
                val o = raw.asObj() ?: continue
                val id = num(o["tmdbId"])
                val title = str(o, "title")
                if (id <= 0L || title.isBlank()) continue
                movies.add(
                    TrackerLogic.MovieItem(
                        tmdbId = id, title = title,
                        releaseDate = str(o, "releaseDate"),
                        planned = bool(o["planned"]), poster = str(o, "poster"),
                        watched = bool(o["watched"])
                    )
                )
            }
            val tracked = ArrayList<TrackerLogic.TrackedShow>()
            for (raw in root["trk"].asArr() ?: emptyList()) {
                val o = raw.asObj() ?: continue
                val id = num(o["tmdbId"])
                val name = str(o, "name")
                if (id <= 0L || name.isBlank()) continue
                tracked.add(
                    TrackerLogic.TrackedShow(
                        tmdbId = id, name = name,
                        watchedCount = num(o["watchedCount"]).toInt(),
                        planned = bool(o["planned"])
                    )
                )
            }
            // v3.8: a v2 file without "miss" (written by a caller
            // that did not pass missed) decodes to an empty
            // Missed list — the row then waits for the next
            // full parse / missed landing, exactly like the
            // other sections on a partial slim.
            val missed = ArrayList<TrackerLogic.UpNextItem>()
            for (raw in root["miss"].asArr() ?: emptyList()) {
                val o = raw.asObj() ?: continue
                val id = num(o["tmdbId"])
                val title = str(o, "title")
                if (id <= 0L || title.isBlank()) continue
                missed.add(
                    TrackerLogic.UpNextItem(
                        tmdbId = id, title = title, sub = str(o, "sub"),
                        dateIso = str(o, "sort"), poster = str(o, "poster"),
                        backdrop = str(o, "backdrop"), overview = str(o, "overview"),
                        season = num(o["season"]).toInt(),
                        episode = num(o["episode"]).toInt(),
                        type = str(o, "type").ifBlank { "tv" }
                    )
                )
            }
            val forYou = ArrayList<TrackerLogic.ForYouPick>()
            for (raw in root["fy"].asArr() ?: emptyList()) {
                val o = raw.asObj() ?: continue
                val id = num(o["id"])
                val title = str(o, "title")
                if (id <= 0L || title.isBlank()) continue
                forYou.add(
                    TrackerLogic.ForYouPick(
                        id = id, type = str(o, "type").ifBlank { "tv" },
                        title = title, poster = str(o, "poster_path"),
                        voteAverage = dbl(o["vote_average"]),
                        reason = str(o, "reason"),
                        whyGenres = o["whyGenres"].asArr()
                            ?.mapNotNull { it.asStr() }?.filter { it.isNotBlank() }
                            ?: emptyList(),
                        score = dbl(o["score"])
                    )
                )
            }
            Data(
                upNext = upNext, cont = cont, movies = movies,
                tracked = tracked, forYou = forYou, missed = missed,
                updated = num(root["updated"]),
                forYouUpdated = num(root["fyUpdated"]),
                fetchedAt = num(root["fetchedAt"])
            )
        } catch (e: Exception) {
            null
        }
    }
}
