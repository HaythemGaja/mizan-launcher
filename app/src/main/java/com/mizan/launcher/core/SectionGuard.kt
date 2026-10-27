package com.mizan.launcher.core

/**
 * Crash-contained composition policy (v3.0, spec §30) — the pure
 * half of the section guards MainActivity (and the other content
 * activities) build with.
 *
 * Before v3.0 the section loops caught Exception only and dropped
 * a failed section SILENTLY (an Error — an inflation OOM on real
 * catalogue data is the realistic case — escaped every catch and
 * killed the process), and a throw in the unguarded pre-section
 * composition left a cold start with no content view at all: the
 * repeating black screen. The policy now:
 *
 *  - every section builds inside a Throwable guard; a failure
 *    renders [tileText] in the section's place (never silence,
 *    never death) and is REPORTED at most once per section per
 *    [REPORT_WINDOW_MS] — rebuilds (resume, rotation of data)
 *    must not spam the diagnostics error line for a section that
 *    keeps failing the same way;
 *  - a whole-home render failure falls back per [fallbackFor]:
 *    the media homes (tracker/watch/iptv) fall back to the TV
 *    render (the v2.3.2 IPTV pattern, generalized), and a TV
 *    render failure falls back to the emergency root (notice +
 *    Settings entry + apps) so the window is NEVER empty.
 */
object SectionGuard {

    /** Minimum gap between two diagnostics reports of the same section failing. */
    const val REPORT_WINDOW_MS = 60_000L

    /** The error tile's line for a failed section. */
    fun tileText(sectionLabel: String): String =
        "⚠ $sectionLabel couldn't load — details in Settings › Diagnostics"

    /** True when a failure of this section should be (re-)reported now. */
    fun shouldReport(lastReportMs: Long, nowMs: Long): Boolean =
        lastReportMs <= 0L || nowMs - lastReportMs >= REPORT_WINDOW_MS
}

/** What a failed whole-home render falls back to (see [SectionGuard]). */
object HomeFallback {

    enum class Fallback { TV_RENDER, EMERGENCY_ROOT }

    /**
     * tracker/watch/iptv homes fall back to the plain TV render;
     * the TV render itself falls back to the emergency root.
     */
    fun fallbackFor(styleKey: String): Fallback = when (styleKey) {
        "tracker", "watch", "iptv" -> Fallback.TV_RENDER
        else -> Fallback.EMERGENCY_ROOT
    }
}
