package com.calm.launcher.core

/** Built-in gradient wallpapers as ARGB stops, top to bottom. */
object Wallpapers {
    data class Preset(val name: String, val top: Int, val bottom: Int)

    val PRESETS: List<Preset> = listOf(
        Preset("Midnight", 0xFF0F2027.toInt(), 0xFF2C5364.toInt()),
        Preset("Slate", 0xFF232526.toInt(), 0xFF414345.toInt()),
        Preset("Ocean", 0xFF1A3A5C.toInt(), 0xFF2193B0.toInt()),
        Preset("Forest", 0xFF134E5E.toInt(), 0xFF2F6B4F.toInt()),
        Preset("Plum", 0xFF41295A.toInt(), 0xFF2F0743.toInt()),
        Preset("Ember", 0xFF3A1C1C.toInt(), 0xFFB13B2E.toInt())
    )

    /** A stored index mapped into range; anything unreadable is the first preset. */
    fun presetAt(raw: String?): Preset {
        val i = raw?.toIntOrNull() ?: 0
        return PRESETS[i.coerceIn(0, PRESETS.size - 1)]
    }
}
