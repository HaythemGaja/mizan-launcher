package com.mizan.launcher.core

import java.text.Normalizer
import java.util.Locale

/** Stable app search: exact labels, prefixes, then all-word matches. */
object AppSearch {
    data class Item(val pkg: String, val label: String)

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .trim().replace(Regex("\\s+"), " ")

    fun filter(apps: List<Item>, query: String): List<Item> {
        val q = normalize(query)
        if (q.isEmpty()) return apps
        val words = q.split(' ')
        val exact = ArrayList<Item>()
        val prefix = ArrayList<Item>()
        val matches = ArrayList<Item>()
        for (app in apps) {
            val label = normalize(app.label)
            when {
                label == q -> exact.add(app)
                label.startsWith(q) -> prefix.add(app)
                words.all { label.contains(it) } -> matches.add(app)
            }
        }
        return exact + prefix + matches
    }
}
