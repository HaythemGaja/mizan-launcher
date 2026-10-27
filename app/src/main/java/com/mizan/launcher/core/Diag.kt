package com.mizan.launcher.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-diagnostics logic (v2.5, spec §25) — the pure half of the
 * app's freeze/crash self-reporting. data/DiagLog owns the
 * Android plumbing (the default uncaught-exception handler, the
 * watchdog thread, the files under filesDir); everything
 * DECIDABLE lives here so the sim can pin it: watchdog staleness,
 * the last-action breadcrumb ring, and the bounded shaping of
 * the two log files (event caps + tails).
 *
 * Why this exists: Haythem's TV ANRs (reason=6, input dispatch
 * timeout) leave NOTHING in the Android crash buffer, and the
 * system ANR traces are permission-denied to the shell user —
 * from the outside the app just "goes black". From this build
 * the app records its own freezes (main-thread stack at the
 * stall) and crashes, and Settings › Data & about shows the
 * tails, so the next report carries its own evidence.
 */
object DiagWatchdog {

    /** A heartbeat older than this (ms) means the main thread is stuck. */
    const val STALE_MS = 4_000L

    /**
     * Pure staleness test (fake-clock friendly): [lastBeatMs] is
     * when the main looper last answered a heartbeat post; a beat
     * of 0 (never answered) reads stale once [nowMs] passes the
     * threshold.
     */
    fun isStale(lastBeatMs: Long, nowMs: Long, thresholdMs: Long = STALE_MS): Boolean =
        nowMs - lastBeatMs > thresholdMs
}

/**
 * The last-action breadcrumb ring: fixed capacity, oldest
 * dropped. [note] sites are the meaningful user actions
 * (activity opened, page opened, card OK/play tapped, refresh
 * tapped, ingest started/finished) — after a freeze or crash,
 * the ring says what the user did just before it.
 */
class BreadcrumbRing(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 10
    }

    private val entries = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun add(atMs: Long, label: String) {
        val clean = label.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        entries.addLast(atMs to clean)
        while (entries.size > capacity) entries.removeFirst()
    }

    /** (epochMs, label) pairs, oldest first. */
    @Synchronized
    fun snapshot(): List<Pair<Long, String>> = ArrayList(entries)

    /** One "HH:mm:ss label" line per entry, oldest first. */
    fun lines(): List<String> = snapshot().map { (at, label) -> "${clock(at)} $label" }

    /** The ring as one text block — the persisted form ([restore] reads it back). */
    fun encode(): String = snapshot().joinToString("\n") { (at, label) -> "$at|$label" }

    /**
     * Rebuild from [encode] output (the previous process's trail,
     * restored at install so a freeze's context survives the
     * process death it causes). Malformed lines are skipped.
     */
    fun restore(text: String?) {
        if (text.isNullOrBlank()) return
        val parsed = ArrayList<Pair<Long, String>>()
        for (line in text.split('\n')) {
            val bar = line.indexOf('|')
            if (bar <= 0) continue
            val at = line.substring(0, bar).toLongOrNull() ?: continue
            val label = line.substring(bar + 1).trim()
            if (label.isEmpty()) continue
            parsed.add(at to label)
        }
        synchronized(this) {
            entries.clear()
            for (e in parsed.takeLast(capacity)) entries.addLast(e)
        }
    }

    private fun clock(atMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMs))
}

/** Bounded shaping of the two diagnostics log files. */
object DiagLogs {

    /** Events inside a log file are separated by a line holding only this marker. */
    const val EVENT_SEPARATOR = "=========="

    /** Hard character ceiling for one log file (whole events drop first). */
    const val MAX_CHARS = 48_000

    /** The events in a log text, oldest first; blank segments ignored. */
    fun splitEvents(text: String): List<String> =
        text.split(EVENT_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Append [event] to a log's text, keeping only the newest
     * [maxEvents] events and at most [maxChars] characters (the
     * single newest event always survives, even if oversized —
     * a truncated stack would be worse than a long file).
     */
    fun appendEvent(existing: String, event: String, maxEvents: Int, maxChars: Int = MAX_CHARS): String {
        val events = splitEvents(existing).toMutableList()
        val clean = event.trim()
        if (clean.isNotEmpty()) events.add(clean)
        var kept = events.takeLast(maxEvents.coerceAtLeast(1))
        while (kept.size > 1 && kept.joinToString("\n$EVENT_SEPARATOR\n").length > maxChars) {
            kept = kept.drop(1)
        }
        return kept.joinToString("\n$EVENT_SEPARATOR\n") + "\n"
    }

    /** How many events a log text holds. */
    fun eventCount(text: String): Int = splitEvents(text).size

    /** The last [maxLines] lines of a log text — the diagnostics tail. */
    fun tailLines(text: String, maxLines: Int): String {
        if (text.isBlank() || maxLines <= 0) return ""
        val lines = text.trimEnd('\n').split('\n')
        return lines.takeLast(maxLines).joinToString("\n")
    }
}

/**
 * Cold-start timing (v2.8, spec §28) — the pure shaping of the one
 * diagnostics line that reports how long the black-screen wait was:
 * "startup: first frame X ms · full Y ms". Times are milliseconds
 * from process start (App.onCreate entry) to (a) the first frame of
 * the staged shell being drawn and (b) the fully-warmed home's
 * frame. A negative value means "not recorded" (this process never
 * cold-started the home, or the probe never fired) and renders as
 * an em dash — the line is always present so its absence is never
 * ambiguous. Fake-clock friendly: the sim pins the exact shapes.
 */
object DiagStartup {

    fun line(firstFrameMs: Long, fullMs: Long): String {
        val first = if (firstFrameMs >= 0L) "$firstFrameMs ms" else "—"
        val full = if (fullMs >= 0L) "$fullMs ms" else "—"
        return "startup: first frame $first · full $full"
    }
}

/**
 * Error-message scrubbing (v2.9, spec §29) — asks E.19/H.29:
 * credentials and credential-bearing URLs must never reach
 * diagnostics or logs. The diagnostics "Last error" line persists
 * an exception's class + message (data/Prefs.noteError), and
 * network exception messages CAN embed the request URL — an
 * Xtream stream/API URL carries the account's user + password in
 * its path, and a TMDB URL carries the api_key. IPTV call sites
 * already sanitize to the class name, but the sink now scrubs
 * EVERY message as the structural guarantee: any URL is replaced
 * wholesale, any key=/password=/pass= value is redacted, and the
 * result is length-capped. Pure + sim-pinned.
 */
object DiagScrub {

    private val URL_RE = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    private val SECRET_PARAM_RE =
        Regex("""(?i)(api_key|apikey|password|passwd|pass|token)=[^\s&]+""")

    /** The scrubbed, capped form of an exception message (null/blank → ""). */
    fun message(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var out = URL_RE.replace(raw) { "‹url›" }
        out = SECRET_PARAM_RE.replace(out) { m -> m.value.substringBefore('=') + "=‹redacted›" }
        out = out.replace(Regex("""\s+"""), " ").trim()
        return if (out.length > 160) out.substring(0, 160) else out
    }
}
