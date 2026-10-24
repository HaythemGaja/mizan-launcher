package com.mizan.launcher.core

/**
 * v3.2: the fine-grained startup stage list behind the
 * diagnostics `startup stages:` line. The v3.1 photo proved a
 * 10.7 s first frame but could not name the stage that ate it —
 * first frame / home complete are only two points. Each stage
 * records its elapsed-ms delta from process start, capped so
 * the list stays one diagnostics line. Pure (no Android) so the
 * sim pins the cap/format; data/DiagLog owns the clock.
 */
class StartupStages(private val cap: Int = 32) {

    private val stages = ArrayList<Pair<String, Long>>()

    /** Record one stage (later same-name records replace the earlier one). */
    fun record(name: String, elapsedMs: Long) {
        stages.removeAll { it.first == name }
        stages.add(name to elapsedMs)
        while (stages.size > cap) stages.removeAt(0)
    }

    /** Compact diagnostics rendering: `name=ms, name=ms`. Empty when nothing recorded. */
    fun text(): String = stages.joinToString(", ") { "${it.first}=${it.second}ms" }

    fun size(): Int = stages.size

    fun names(): List<String> = stages.map { it.first }
}
