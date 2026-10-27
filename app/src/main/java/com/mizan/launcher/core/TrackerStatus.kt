package com.mizan.launcher.core

/**
 * Tracker fetch state → words (v1.9) — pure logic, no Android imports.
 *
 * Why this exists: on Haythem's TV the tracker app worked (library
 * restored) but the launcher's Tracker view showed NOTHING, silently.
 * v1.8's TrackerClient swallowed every query failure into null, so
 * "tracker absent", "permission blocked" and "snapshots not yet
 * populated" were indistinguishable — the same silent-failure class
 * as the v1.5 Reddit bug, fixed then by a visible status line. This
 * object is that lesson applied to the tracker: data/TrackerClient
 * classifies every fetch into a [Kind], persists it, and BOTH the
 * Settings diagnostics `tracker:` line and the Tracker view's
 * no-data fallback line are worded from here, so the sim can pin
 * every mapping.
 *
 * The permission case is real, not theoretical: LAUNCHER_READ is
 * DEFINED by the tracker app. A launcher installed (or last updated)
 * before the tracker defined it may never have been granted it, and
 * every query then throws SecurityException. Updating/reinstalling
 * the launcher AFTER the tracker exists is what re-grants it.
 */
object TrackerStatus {

    /** The classified outcome of the last tracker fetch attempt. */
    enum class Kind {
        /** Fetched, and the snapshots hold data. */
        NONE,
        /** The tracker package is not on this device. */
        NOT_INSTALLED,
        /** Queries are refused — the launcher lacks LAUNCHER_READ. */
        PERMISSION,
        /** The provider answered, but every snapshot is empty. */
        EMPTY,
        /** The provider didn't answer and no better explanation fits. */
        OTHER
    }

    /**
     * Classify one fetch. Order matters: a permission refusal is the
     * most actionable fact and is reported even when a stale cache
     * still has data to show; "answered with data" is the only NONE.
     */
    fun classify(
        installed: Boolean,
        anyAnswered: Boolean,
        securityError: Boolean,
        hasData: Boolean
    ): Kind = when {
        !installed -> Kind.NOT_INSTALLED
        securityError -> Kind.PERMISSION
        anyAnswered && hasData -> Kind.NONE
        anyAnswered -> Kind.EMPTY
        else -> Kind.OTHER
    }

    /**
     * The Settings diagnostics line (the part after "tracker: ").
     * [timeText] is the snapshot `updated` stamp preformatted by the
     * caller ("2:41 PM"), or "" when there is no snapshot yet.
     * v2.1: the OK form carries PER-SECTION counts (up / cont / mov /
     * trk / foryou) so an empty section is provable from the line
     * alone — e.g. "mov 0" with a working Up Next means the tracker
     * isn't sending movies, not that the launcher filtered them.
     */
    fun diagText(
        kind: Kind,
        hasData: Boolean,
        upCount: Int,
        contCount: Int,
        movieCount: Int,
        trackedCount: Int,
        forYouCount: Int,
        timeText: String
    ): String = when (kind) {
        Kind.NOT_INSTALLED -> "not installed"
        Kind.PERMISSION -> "permission blocked — updating or reinstalling Sreeb fixes it"
        Kind.EMPTY -> "no data yet (open the tracker once)"
        Kind.OTHER -> "not answering — open the tracker once"
        Kind.NONE -> if (hasData) {
            "OK · up $upCount · cont $contCount · mov $movieCount · " +
                "trk $trackedCount · foryou $forYouCount" +
                if (timeText.isNotEmpty()) " · $timeText" else ""
        } else {
            "no data yet (open the tracker once)"
        }
    }

    /**
     * The Tracker view's quiet line when there is no data to show —
     * or null for "show no line at all" (tracker absent: the view is
     * just a plain home, exactly as in v1.8).
     */
    fun emptyLine(kind: Kind, installed: Boolean): String? = when {
        !installed || kind == Kind.NOT_INSTALLED -> null
        kind == Kind.PERMISSION ->
            "Tracker data is blocked — updating or reinstalling Sreeb fixes the permission"
        else -> "Open TV Shows & Movies Tracker once so it can refresh"
    }
}
