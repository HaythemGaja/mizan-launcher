package com.mizan.launcher.core

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * v3.15 — the app's one date/time formatter cache.
 *
 * Why this exists: `SimpleDateFormat` looks cheap but its FIRST
 * `format()` in a process makes `DecimalFormatSymbols` initialise
 * currency data, and ICU then walks every region/currency pair on the
 * calling thread — seconds of work on TV-grade hardware. The
 * launcher's own watchdog caught it: "MAIN THREAD STUCK" with
 * `todayIso -> SimpleDateFormat.format -> ICUCurrencyMetaInfo` at the
 * top, during home composition, i.e. the 10–40 s cold start.
 *
 * `java.time`'s `DateTimeFormatter` formats the same patterns without
 * touching `DecimalFormat`, so the cost disappears. Patterns that only
 * `SimpleDateFormat` understands (ICU skeleton letters and the like)
 * still work: they fall back to a cached `SimpleDateFormat`, paying
 * that cost at most once.
 *
 * The cache is bounded by the number of distinct patterns the app
 * uses, which is a handful (status-bar clock/date, row timestamps).
 */
object TimeFmt {

    private val modern = HashMap<String, DateTimeFormatter?>()
    private val legacy = HashMap<String, SimpleDateFormat>()

    private fun key(pattern: String, locale: Locale) = pattern + "|" + locale.toLanguageTag()

    /** Formats [ms] with [pattern]; never throws, returns "" on failure. */
    @Synchronized
    fun format(pattern: String, locale: Locale, ms: Long): String {
        return try {
            val k = key(pattern, locale)
            val f = modern.getOrPut(k) {
                try {
                    DateTimeFormatter.ofPattern(pattern, locale)
                } catch (e: Exception) {
                    null
                }
            }
            if (f != null) {
                f.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
            } else {
                legacy.getOrPut(k) { SimpleDateFormat(pattern, locale) }.format(Date(ms))
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun format(pattern: String, ms: Long): String = format(pattern, Locale.getDefault(), ms)
}
