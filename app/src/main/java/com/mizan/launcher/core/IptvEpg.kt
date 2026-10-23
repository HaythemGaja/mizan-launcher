package com.mizan.launcher.core

/**
 * IPTV programme guide core (v2.2, spec §20.7) — pure, no Android
 * imports. Two sources feed one index (channel id → programmes
 * sorted by start):
 *
 * - XMLTV (the Xtream panel's xmltv.php, or the M3U source's own
 *   XMLTV URL): parsed with a tolerant scanner — attribute order,
 *   extra elements and entities included; programme times carry
 *   their own UTC offset ("20241022193000 +0200"), converted to
 *   absolute epoch milliseconds with a civil-date algorithm (no
 *   locale, no timezone database — the offset in the file rules).
 * - get_short_epg (per-channel, on demand): titles arrive
 *   base64-encoded and times as "yyyy-MM-dd HH:mm:ss"; panels serve
 *   those in server-local time, which the client cannot know — they
 *   are read as UTC and the guide treats short-EPG data as
 *   best-effort (spec §20.7).
 *
 * Fail-open: malformed input yields whatever parsed cleanly,
 * never an exception.
 */
object IptvEpg {

    data class Programme(
        val channelId: String,
        val startMs: Long,
        val stopMs: Long,
        val title: String
    )

    // ------------------------------------------------------------ civil date math

    /** Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm). */
    fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = if (yy >= 0) yy / 400 else (yy - 399) / 400
        val yoe = yy - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** (year, month, day) for days since 1970-01-01. */
    fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468L
        val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4 - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val year = if (m <= 2L) y + 1L else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }

    /** Format an epoch instant as yyyyMMddHHmmss in UTC (timeshift URLs). */
    fun formatYmdHis(epochMs: Long): String {
        val totalSecs = Math.floorDiv(epochMs, 1000L)
        val days = Math.floorDiv(totalSecs, 86400L)
        val secsOfDay = Math.floorMod(totalSecs, 86400L)
        val (y, m, d) = civilFromDays(days)
        val hh = (secsOfDay / 3600L).toInt()
        val mm = ((secsOfDay % 3600L) / 60L).toInt()
        val ss = (secsOfDay % 60L).toInt()
        fun p2(v: Int) = v.toString().padStart(2, '0')
        return y.toString().padStart(4, '0') + p2(m) + p2(d) + p2(hh) + p2(mm) + p2(ss)
    }

    /**
     * Parse an XMLTV timestamp: at least yyyyMMddHHmmss digits,
     * optionally followed by a space and a ±HHMM offset. A missing
     * offset reads as UTC. Returns epoch ms, or -1 when unusable.
     */
    fun parseXmltvTime(raw: String): Long {
        val s = raw.trim()
        if (s.length < 14) return -1L
        val digits = s.substring(0, 14)
        if (!digits.all { it.isDigit() }) return -1L
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hh = digits.substring(8, 10).toInt()
        val mm = digits.substring(10, 12).toInt()
        val ss = digits.substring(12, 14).toInt()
        if (month !in 1..12 || day !in 1..31 || hh > 23 || mm > 59 || ss > 60) return -1L
        var offsetSecs = 0L
        val rest = s.substring(14).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val oh = rest.substring(1, 3).toIntOrNull() ?: return -1L
            val om = rest.substring(3, 5).toIntOrNull() ?: return -1L
            offsetSecs = (oh * 3600L + om * 60L) * if (rest[0] == '-') -1L else 1L
        }
        val local = daysFromCivil(year, month, day) * 86400L + hh * 3600L + mm * 60L + ss
        return (local - offsetSecs) * 1000L
    }

    /** Parse "yyyy-MM-dd HH:mm:ss" as UTC (short-EPG listings). -1 when unusable. */
    fun parseListingTime(raw: String): Long {
        val s = raw.trim()
        // 2024-10-22 19:30:00
        if (s.length < 19) return -1L
        val y = s.substring(0, 4).toIntOrNull() ?: return -1L
        val mo = s.substring(5, 7).toIntOrNull() ?: return -1L
        val d = s.substring(8, 10).toIntOrNull() ?: return -1L
        val hh = s.substring(11, 13).toIntOrNull() ?: return -1L
        val mm = s.substring(14, 16).toIntOrNull() ?: return -1L
        val ss = s.substring(17, 19).toIntOrNull() ?: return -1L
        if (mo !in 1..12 || d !in 1..31 || hh > 23 || mm > 59 || ss > 60) return -1L
        return (daysFromCivil(y, mo, d) * 86400L + hh * 3600L + mm * 60L + ss) * 1000L
    }

    // ------------------------------------------------------------ XMLTV

    /** Unescape the five XML entities + numeric character references. */
    fun xmlUnescape(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 1)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    when {
                        ent == "amp" -> { sb.append('&'); i = semi + 1; continue }
                        ent == "lt" -> { sb.append('<'); i = semi + 1; continue }
                        ent == "gt" -> { sb.append('>'); i = semi + 1; continue }
                        ent == "quot" -> { sb.append('"'); i = semi + 1; continue }
                        ent == "apos" -> { sb.append('\''); i = semi + 1; continue }
                        ent.startsWith("#x") || ent.startsWith("#X") -> {
                            val code = ent.substring(2).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i = semi + 1; continue }
                        }
                        ent.startsWith("#") -> {
                            val code = ent.substring(1).toIntOrNull()
                            if (code != null) { sb.append(code.toChar()); i = semi + 1; continue }
                        }
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun attrOf(tag: String, name: String): String {
        val needle = "$name=\""
        var start = tag.indexOf(needle)
        if (start < 0) {
            val needleS = "$name='"
            start = tag.indexOf(needleS)
            if (start < 0) return ""
            val from = start + needleS.length
            val end = tag.indexOf('\'', from)
            return if (end < 0) "" else tag.substring(from, end)
        }
        val from = start + needle.length
        val end = tag.indexOf('"', from)
        return if (end < 0) "" else tag.substring(from, end)
    }

    /**
     * Parse an XMLTV document into channel id → programmes sorted
     * by start. A tolerant scanner (no DOM): each `<programme …>`
     * tag contributes channel/start/stop; the FIRST `<title>` in
     * its body is the title. Entries with unusable times or an
     * empty channel are skipped; CDATA titles are unwrapped.
     */
    fun parseXmltv(xml: String?): Map<String, List<Programme>> {
        if (xml.isNullOrBlank()) return emptyMap()
        val byChannel = LinkedHashMap<String, MutableList<Programme>>()
        var cursor = 0
        while (true) {
            val tagStart = xml.indexOf("<programme", cursor)
            if (tagStart < 0) break
            val tagEnd = xml.indexOf('>', tagStart)
            if (tagEnd < 0) break
            val close = xml.indexOf("</programme>", tagStart)
            // An unclosed programme contributes its tag only (no
            // body, hence no title) — the scanner's original rule.
            val segmentEnd = if (close < 0) tagEnd + 1 else close + 12
            programmeFromSegment(xml.substring(tagStart, segmentEnd))?.let { p ->
                byChannel.getOrPut(p.channelId) { ArrayList() }.add(p)
            }
            cursor = if (close < 0) tagEnd + 1 else close + 12
        }
        val out = LinkedHashMap<String, List<Programme>>()
        for ((k, v) in byChannel) out[k] = v.sortedBy { it.startMs }
        return out
    }

    /**
     * One `<programme …>…</programme>` segment → its [Programme],
     * or null when the segment is unusable (empty channel,
     * unparseable times, stop ≤ start). The FIRST `<title>` in the
     * body is the title; CDATA titles are unwrapped; entities are
     * unescaped. Shared by the document parser above and the v2.4
     * streaming reader below, so both read a programme identically.
     */
    fun programmeFromSegment(segment: String): Programme? {
        val tagEnd = segment.indexOf('>')
        if (tagEnd < 0) return null
        val tag = segment.substring(0, tagEnd)
        val channel = attrOf(tag, "channel").trim()
        val start = parseXmltvTime(attrOf(tag, "start"))
        val stop = parseXmltvTime(attrOf(tag, "stop"))
        if (channel.isEmpty() || start < 0 || stop <= start) return null
        var title = ""
        val tStart = segment.indexOf("<title", tagEnd)
        if (tStart >= 0) {
            val tOpen = segment.indexOf('>', tStart)
            val tClose = segment.indexOf("</title>", tOpen)
            if (tOpen >= 0 && tClose > tOpen) {
                title = segment.substring(tOpen + 1, tClose).trim()
                    .removePrefix("<![CDATA[").removeSuffix("]]>").trim()
            }
        }
        return Programme(channel, start, stop, xmlUnescape(title))
    }

    /** Longest programme segment the streaming reader will buffer (bytes of text). */
    const val MAX_SEGMENT_CHARS = 256 * 1024

    /**
     * Stream an XMLTV document from [reader] one programme at a
     * time (v2.4): the document is consumed in chunks and only one
     * `<programme>` segment is ever held in memory (bounded by
     * [MAX_SEGMENT_CHARS] — a segment that overruns is dropped and
     * the scanner resynchronizes at the next programme). Each
     * usable programme is delivered to [onProgramme] as it is
     * read; returns the number delivered. I/O failures propagate
     * as IOException — the caller decides what a partial guide is
     * worth (the ingest keeps the old guide file).
     */
    fun streamXmltv(reader: java.io.Reader, onProgramme: (Programme) -> Unit): Int {
        var delivered = 0
        val chunk = CharArray(32 * 1024)
        val buf = StringBuilder()
        var inSegment = false
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            buf.append(chunk, 0, n)
            // Drain every complete segment (and resync point) the
            // buffer currently holds; keep only the live tail.
            while (true) {
                if (!inSegment) {
                    val start = buf.indexOf("<programme")
                    if (start < 0) {
                        // Keep a tail shorter than the needle so a
                        // tag straddling chunks is not missed.
                        if (buf.length > 10) buf.delete(0, buf.length - 10)
                        break
                    }
                    if (start > 0) buf.delete(0, start)
                    inSegment = true
                }
                val close = buf.indexOf("</programme>")
                if (close < 0) {
                    if (buf.length > MAX_SEGMENT_CHARS) {
                        // Pathological segment: drop it and resync.
                        buf.delete(0, buf.length - 10)
                        inSegment = false
                        continue
                    }
                    break // need more input
                }
                val segment = buf.substring(0, close + 12)
                buf.delete(0, close + 12)
                inSegment = false
                programmeFromSegment(segment)?.let {
                    onProgramme(it)
                    delivered++
                }
            }
        }
        return delivered
    }

    /**
     * The EPG retention window (v2.4): the store keeps a programme
     * only while it can still matter to the guide / catch-up —
     * it ends after [nowMs] − 1h and starts before [nowMs] + 24h.
     * A full XMLTV document covers days of listings for thousands
     * of channels; retaining all of it is the same unbounded-memory
     * mistake as the catalogue documents were.
     */
    fun inGuideWindow(p: Programme, nowMs: Long): Boolean =
        p.stopMs > nowMs - 3_600_000L && p.startMs < nowMs + 24L * 3_600_000L

    // ------------------------------------------------------------ short EPG (Xtream)

    /**
     * get_short_epg payload: `{epg_listings: [{title, start, end,
     * …}]}` — titles base64-encoded UTF-8, times "yyyy-MM-dd
     * HH:mm:ss" read as UTC. [channelId] is stamped onto every
     * programme. Undecodable titles fall back to the raw string.
     */
    fun parseShortEpg(json: String?, channelId: String): List<Programme> {
        val root = (JsonMini.parse(json ?: return emptyList()) as? Map<*, *>) ?: return emptyList()
        val listings = root["epg_listings"] as? List<*> ?: return emptyList()
        val out = ArrayList<Programme>()
        for (rowRaw in listings) {
            val row = rowRaw as? Map<*, *> ?: continue
            val start = parseListingTime(IptvLogic.strOf(row["start"]))
            val stop = parseListingTime(IptvLogic.strOf(row["end"]))
            if (start < 0 || stop <= start) continue
            val rawTitle = IptvLogic.strOf(row["title"])
            out.add(Programme(channelId, start, stop, decodeB64Title(rawTitle)))
        }
        return out.sortedBy { it.startMs }
    }

    private fun decodeB64Title(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return try {
            String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                String(java.util.Base64.getMimeDecoder().decode(s), Charsets.UTF_8)
            } catch (e2: Exception) {
                s
            }
        }
    }

    // ------------------------------------------------------------ now / next

    /**
     * The programme on at [nowMs] and the one after it, from a
     * start-sorted list: NOW contains the instant; NEXT is the
     * first programme starting at or after NOW's stop (or after
     * the instant itself when nothing is on).
     */
    fun nowNext(list: List<Programme>?, nowMs: Long): Pair<Programme?, Programme?> {
        if (list.isNullOrEmpty()) return Pair(null, null)
        var now: Programme? = null
        for (p in list) {
            if (p.startMs <= nowMs && nowMs < p.stopMs) { now = p; break }
            if (p.startMs > nowMs) break
        }
        val after = now?.stopMs ?: nowMs
        val next = list.firstOrNull { it.startMs >= after }
        return Pair(now, next)
    }

    /** Fraction of [p] elapsed at [nowMs], 0..1; -1 when the span is unusable. */
    fun progress(p: Programme, nowMs: Long): Float {
        val span = p.stopMs - p.startMs
        if (span <= 0L) return -1f
        return ((nowMs - p.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }
}
