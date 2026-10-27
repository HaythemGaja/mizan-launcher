package com.mizan.launcher.core

/**
 * v2.0: custom app artwork (pure codec + the preset palette table).
 *
 * Per profile, each package may have one entry:
 *  - PRESET(index) — one of the 12 generated gradient tiles below
 *    (drawn by the UI layer with the app's initial letter);
 *  - URL — an image URL, downloaded once and cached on disk by
 *    data/ArtworkImages;
 *  - no entry — the app's real icon.
 *
 * Persisted one entry per line: `pkg<TAB>P<TAB>index` or
 * `pkg<TAB>U<TAB>escaped-url`. Decode is fail-open: a malformed line
 * is skipped, never fatal. Preset indices are coerced into range.
 */
object ArtworkStore {

    enum class Kind { PRESET, URL }

    data class Entry(val kind: Kind, val value: String)

    fun preset(index: Int): Entry =
        Entry(Kind.PRESET, presetIndex(index.toString()).toString())

    fun url(value: String): Entry = Entry(Kind.URL, value)

    // ----- the 12 presets: name + (top, bottom) gradient colours -----

    val PRESET_NAMES: List<String> = listOf(
        "Sunset", "Ocean", "Forest", "Plum", "Ember", "Lagoon",
        "Rose", "Slate", "Citrus", "Midnight", "Moss", "Sand"
    )

    private val PRESET_COLORS: List<Pair<Int, Int>> = listOf(
        0xFFF2994A.toInt() to 0xFFEB5757.toInt(),   // Sunset
        0xFF2193B0.toInt() to 0xFF6DD5ED.toInt(),   // Ocean
        0xFF134E5E.toInt() to 0xFF71B280.toInt(),   // Forest
        0xFF41295A.toInt() to 0xFF8E2DE2.toInt(),   // Plum
        0xFFCB2D3E.toInt() to 0xFFEF473A.toInt(),   // Ember
        0xFF1D976C.toInt() to 0xFF93F9B9.toInt(),   // Lagoon
        0xFFEE9CA7.toInt() to 0xFFFFDDE1.toInt(),   // Rose
        0xFF485563.toInt() to 0xFF29323C.toInt(),   // Slate
        0xFFF7971E.toInt() to 0xFFFFD200.toInt(),   // Citrus
        0xFF0F2027.toInt() to 0xFF2C5364.toInt(),   // Midnight
        0xFF5A3F37.toInt() to 0xFF6A8F3C.toInt(),   // Moss
        0xFFD1913C.toInt() to 0xFFFFD194.toInt()    // Sand
    )

    const val PRESET_COUNT = 12

    fun presetIndex(raw: String?): Int =
        (raw?.toIntOrNull() ?: 0).coerceIn(0, PRESET_COUNT - 1)

    fun presetColors(index: Int): Pair<Int, Int> =
        PRESET_COLORS[presetIndex(index.toString())]

    fun presetName(index: Int): String = PRESET_NAMES[presetIndex(index.toString())]

    // ----- codec -----

    fun encode(map: Map<String, Entry>): String {
        val sb = StringBuilder()
        for ((pkg, entry) in map.toSortedMap()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(pkg).append('\t')
            when (entry.kind) {
                Kind.PRESET -> sb.append('P').append('\t').append(presetIndex(entry.value))
                Kind.URL -> sb.append('U').append('\t').append(esc(entry.value))
            }
        }
        return sb.toString()
    }

    fun decode(raw: String?): Map<String, Entry> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        for (line in raw.split('\n')) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val pkg = parts[0]
            if (pkg.isBlank()) continue
            when (parts[1]) {
                "P" -> out[pkg] = preset(parts[2].toIntOrNull() ?: 0)
                "U" -> {
                    val u = unesc(parts[2])
                    if (u.isNotBlank()) out[pkg] = Entry(Kind.URL, u)
                }
            }
        }
        return out
    }

    /** [map] with [pkg]'s entry set (or removed when [entry] is null). */
    fun with(map: Map<String, Entry>, pkg: String, entry: Entry?): Map<String, Entry> {
        val out = LinkedHashMap(map)
        if (entry == null) out.remove(pkg) else out[pkg] = entry
        return out
    }

    private fun esc(s: String): String =
        s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A")

    private fun unesc(s: String): String =
        s.replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
}
