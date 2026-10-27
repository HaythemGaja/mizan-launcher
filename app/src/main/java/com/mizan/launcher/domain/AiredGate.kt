package com.mizan.launcher.domain

import java.time.LocalDate

/**
 * Single owner of the unaired-episode gate (f26a5f6, MAP §8.1).
 * Previously this rule was duplicated in DetailsActivity and
 * TrackerActions; Phase 2 must call this one implementation from
 * every call site (episode rows, episode click/long-press menus,
 * season marking, season-button counts).
 *
 * Rule, exactly:
 * - blank air date (unknown) -> aired (fail open, never invent a date)
 * - malformed air date (not yyyy-MM-dd) -> aired (fail open)
 * - well-formed date -> aired iff date <= the device's local today;
 *   an episode airing TODAY counts as aired.
 * ISO yyyy-MM-dd strings compare chronologically as plain strings,
 * so the comparison below is exact for well-formed dates.
 *
 * Scope note (MAP §8.1/§11): f26a5f6 gated ONLY Details episode rows
 * and season marking. Whether the gate extends to Up Next / Continue /
 * Missed card play, Library next-episode marking, or Watch auto-mark
 * is an open product decision — do not extend it silently.
 */
object AiredGate {

    private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun hasAired(airDate: String?): Boolean {
        val trimmed = airDate?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        if (!DATE_PATTERN.matches(trimmed)) return true
        return trimmed <= LocalDate.now().toString()
    }

    /** True when the episode exists but has not aired yet. */
    fun isUnaired(airDate: String?): Boolean = !hasAired(airDate)

    /**
     * Toast text for an unaired, unwatched episode. The blank-date
     * branch is unreachable in practice (blank = aired) but kept so
     * callers never have to special-case it.
     */
    fun unairedMessage(airDate: String?): String {
        val trimmed = airDate?.trim().orEmpty()
        return if (trimmed.isEmpty()) "Hasn't aired yet"
        else "Hasn't aired yet — airs $trimmed"
    }

    /** Season-marking filter: keep only episodes that have aired. */
    fun airedOnly(episodes: List<EpisodeRef>): List<EpisodeRef> =
        episodes.filter { hasAired(it.airDate) }

    /** Season-button remaining count: unwatched AND aired only. */
    fun remainingAiredCount(
        episodes: List<EpisodeRef>,
        isWatched: (EpisodeRef) -> Boolean,
    ): Int = episodes.count { !isWatched(it) && hasAired(it.airDate) }
}
