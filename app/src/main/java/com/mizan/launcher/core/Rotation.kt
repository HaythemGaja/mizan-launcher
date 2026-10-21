package com.mizan.launcher.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wallpaper auto-rotation + Bing daily source — pure logic (v1.4).
 *
 * Rotation is deliberately SERVICE-FREE: there is no timer anywhere.
 * The home screen checks "is a rotation due?" on resume and on the
 * status-bar clock tick, using the persisted last-rotation timestamp.
 * If the TV sat off for a week, the first check rotates once — exactly
 * the behaviour a TV wallpaper wants.
 */
object RotationLogic {

    /** Stored prefs keys for the rotation interval. */
    const val OFF = "off"
    const val EVERY_15_MIN = "15m"
    const val EVERY_1_HOUR = "1h"
    const val EVERY_6_HOURS = "6h"
    const val DAILY = "daily"

    /** Picker order + labels for Settings. */
    val KEYS: List<String> = listOf(OFF, EVERY_15_MIN, EVERY_1_HOUR, EVERY_6_HOURS, DAILY)

    fun labelFor(key: String): String = when (key) {
        EVERY_15_MIN -> "Every 15 minutes"
        EVERY_1_HOUR -> "Every hour"
        EVERY_6_HOURS -> "Every 6 hours"
        DAILY -> "Daily"
        else -> "Off"
    }

    /** Interval length in ms; 0 for Off (and for unknown keys — fail-open to Off). */
    fun intervalMillis(key: String): Long = when (key) {
        EVERY_15_MIN -> 15L * 60 * 1000
        EVERY_1_HOUR -> 60L * 60 * 1000
        EVERY_6_HOURS -> 6L * 60L * 60 * 1000
        DAILY -> 24L * 60 * 60 * 1000
        else -> 0L
    }

    /**
     * Is a rotation due? Due exactly AT the boundary (now - last ==
     * interval). Never rotated before (last <= 0) counts as due, so
     * turning rotation on starts it. Off is never due.
     */
    fun isDue(nowMillis: Long, lastMillis: Long, intervalKey: String): Boolean {
        val interval = intervalMillis(intervalKey)
        if (interval <= 0L) return false
        if (lastMillis <= 0L) return true
        return nowMillis - lastMillis >= interval
    }

    /**
     * The next candidate index, wrapping at the end of the list.
     * Returns -1 when there is nothing to rotate to (empty list).
     * A stale/out-of-range current index wraps cleanly to a valid one.
     */
    fun nextIndex(current: Int, size: Int): Int {
        if (size <= 0) return -1
        val c = if (current < 0 || current >= size) -1 else current
        return (c + 1) % size
    }
}

/**
 * Bing daily wallpaper: the HPImageArchive JSON's first image.
 * Parsed with the mini JSON reader (org.json is a JVM stub, so it
 * could not be sim-tested — same rule as the Reddit parser).
 */
object BingWallpaper {

    const val ARCHIVE_URL =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US"

    /**
     * Absolute URL of today's Bing image, or null when the JSON is
     * malformed or carries no usable image (fail-open). The archive's
     * `url` field is a site-relative path ("/th?id=...") and is made
     * absolute against bing.com; an already-absolute URL passes through.
     */
    fun parseImageUrl(jsonText: String?): String? {
        val root = JsonMini.parse(jsonText).asObj() ?: return null
        val images = root["images"].asArr() ?: return null
        val first = images.firstOrNull().asObj() ?: return null
        val raw = first["url"].asStr()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "https://www.bing.com$raw"
            else -> "https://www.bing.com/$raw"
        }
    }

    /**
     * Cache key for the Bing day: the local calendar date "yyyy-MM-dd".
     * A new fetch happens only when this differs from the stored key,
     * so the wallpaper changes with the calendar day, not on a timer.
     */
    fun dayKey(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
}
