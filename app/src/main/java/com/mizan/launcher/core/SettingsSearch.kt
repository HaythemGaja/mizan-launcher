package com.mizan.launcher.core

import java.text.Normalizer
import java.util.Locale

/** Local category index: no account details, preferences or network requests. */
object SettingsSearch {
    data class Entry(val id: String, val title: String, val keywords: String)

    val entries = listOf(
        Entry("home", "Home & launcher", "default setup accessibility watcher speed memory ram"),
        Entry("look", "Look & wallpaper", "appearance theme background color colour accent icons shape size columns reddit bing"),
        Entry("homescreen", "Home screen", "rows apps hidden folders favorites favourites recent most used style clean tv watch"),
        Entry("sortsections", "Sort sections", "reorder move hide sections rows order"),
        Entry("statusbar", "Status bar", "clock date time weather quick tiles sticky"),
        Entry("weather", "Weather", "location latitude longitude units temperature celsius fahrenheit forecast"),
        Entry("lockprofiles", "Lock & profiles", "security privacy parental pin pattern password guest family app locks unlock grace"),
        Entry("nowplaying", "Now playing", "music media sessions notification access playback"),
        Entry("tracker", "Media tracker", "library shows movies tmdb watched progress episodes"),
        Entry("iptv", "IPTV", "live television playlist m3u xtream epg channels account guide"),
        Entry("data", "Data & about", "backup export import restore safe mode diagnostics version errors reset"),
        Entry("phoneremote", "Phone remote", "pair pairing code wifi wi-fi lan network companion control mobile")
    )

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    /** All words must match; names rank before keyword-only matches. Stable category order. */
    fun find(query: String): List<Entry> {
        val words = normalize(query.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return entries
        return entries.filter { entry ->
            val searchable = normalize("${entry.title} ${entry.keywords}")
            words.all { it in searchable }
        }.sortedBy { entry -> if (words.all { it in normalize(entry.title) }) 0 else 1 }
    }
}
