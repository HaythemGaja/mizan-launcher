package com.mizan.launcher.core

/**
 * "Play in…" candidate filtering (v3.1, ask 40) — pure, so the
 * sim pins it without PackageManager.
 *
 * The picker lists every installed activity that handles
 * ACTION_VIEW video intents EXCEPT Sreeb itself (handing a stream back
 * to our own player is a no-op choice). Candidates sort by label
 * so the list is stable across devices. No package name is ever
 * hardcoded here or anywhere in the feature: if an app (IPTV
 * Smarters Pro, Reezn, anything else) does not register for
 * video intents, it simply does not appear — that is reported
 * honestly, never faked.
 */
object PlayInLogic {

    data class Candidate(val pkg: String, val label: String)

    /** Drop Sreeb itself; sort by label (case-insensitive). */
    fun filter(candidates: List<Candidate>, ownPkg: String): List<Candidate> =
        candidates.filter { it.pkg != ownPkg && it.pkg.isNotBlank() }
            .sortedBy { it.label.lowercase() }

    /** True when the picker has anything to show. */
    fun hasHandler(filtered: List<Candidate>): Boolean = filtered.isNotEmpty()
}
