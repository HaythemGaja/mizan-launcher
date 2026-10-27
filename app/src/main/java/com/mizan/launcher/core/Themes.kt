package com.mizan.launcher.core

/**
 * Theme + wallpaper tables. Pure data and pure functions only — no Android
 * imports — so the headless sim can verify every preset and gradient.
 * Colours are ARGB ints. All colour values are our own.
 */

/** A built-in wallpaper gradient: [top] colour drawn to [bottom] colour, once. */
data class GradientDef(val name: String, val top: Int, val bottom: Int)

/** A full launcher theme preset. */
data class ThemePreset(
    val id: String,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val accent: Int,
    val text: Int,
    val subText: Int,
    /** Dynamic = accent is replaced at runtime by the wallpaper's dominant colour. */
    val dynamic: Boolean = false
)

object Themes {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** 14 built-in gradients, colours only (tv-detox ethos: no pictures, drawn once). */
    val GRADIENTS: List<GradientDef> = listOf(
        GradientDef("Dusk", argb(38, 44, 74), argb(16, 18, 32)),
        GradientDef("Midnight", argb(8, 12, 28), argb(2, 3, 8)),
        GradientDef("Plum", argb(62, 28, 72), argb(24, 10, 30)),
        GradientDef("Forest", argb(20, 58, 44), argb(8, 24, 18)),
        GradientDef("Graphite", argb(52, 54, 58), argb(18, 19, 22)),
        GradientDef("Ocean", argb(14, 62, 96), argb(5, 24, 42)),
        GradientDef("Ember", argb(96, 44, 22), argb(36, 14, 8)),
        GradientDef("Sunset", argb(214, 110, 62), argb(74, 32, 62)),
        GradientDef("Lagoon", argb(22, 128, 128), argb(10, 48, 66)),
        GradientDef("Aurora", argb(36, 110, 84), argb(16, 38, 74)),
        GradientDef("Coral", argb(198, 84, 78), argb(90, 30, 44)),
        GradientDef("Iris", argb(102, 84, 178), argb(40, 30, 84)),
        GradientDef("Meadow", argb(104, 154, 66), argb(34, 66, 30)),
        GradientDef("Gold", argb(178, 138, 52), argb(74, 54, 18))
    )

    /** Solid wallpaper colours offered in Settings. */
    val SOLIDS: List<Pair<String, Int>> = listOf(
        "Black" to argb(0, 0, 0),
        "Charcoal" to argb(28, 28, 30),
        "Navy" to argb(10, 18, 44),
        "Purple" to argb(51, 36, 96),
        "Burgundy" to argb(66, 14, 28),
        "Teal" to argb(8, 52, 54),
        "Slate" to argb(58, 66, 76),
        "Chocolate" to argb(52, 34, 22)
    )

    /** 8 named presets + Dynamic (Monet-style wallpaper accent). */
    val PRESETS: List<ThemePreset> = listOf(
        ThemePreset("apple_black", "Apple Black", argb(10, 10, 12), argb(0, 0, 0),
            argb(235, 235, 240), argb(245, 245, 247), argb(170, 170, 178)),
        ThemePreset("roku_purple", "Roku Purple", argb(82, 44, 140), argb(38, 18, 74),
            argb(178, 132, 232), argb(248, 244, 255), argb(205, 188, 235)),
        ThemePreset("midnight", "Midnight", argb(8, 12, 28), argb(2, 3, 8),
            argb(96, 150, 235), argb(238, 242, 250), argb(160, 172, 195)),
        ThemePreset("dusk", "Dusk", argb(38, 44, 74), argb(16, 18, 32),
            argb(235, 166, 92), argb(242, 240, 248), argb(175, 178, 198)),
        ThemePreset("plum", "Plum", argb(62, 28, 72), argb(24, 10, 30),
            argb(225, 140, 200), argb(250, 240, 250), argb(208, 178, 205)),
        ThemePreset("forest", "Forest", argb(20, 58, 44), argb(8, 24, 18),
            argb(120, 210, 150), argb(238, 248, 240), argb(170, 205, 180)),
        ThemePreset("ocean", "Ocean", argb(14, 62, 96), argb(5, 24, 42),
            argb(84, 190, 225), argb(238, 248, 252), argb(168, 200, 215)),
        ThemePreset("graphite", "Graphite", argb(52, 54, 58), argb(18, 19, 22),
            argb(200, 205, 215), argb(244, 245, 247), argb(178, 182, 190)),
        ThemePreset("dynamic", "Dynamic (wallpaper accent)", argb(24, 24, 28), argb(10, 10, 12),
            argb(150, 180, 235), argb(244, 245, 247), argb(178, 182, 190), dynamic = true)
    )

    fun preset(id: String): ThemePreset = PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]

    fun gradient(name: String): GradientDef = GRADIENTS.firstOrNull { it.name == name } ?: GRADIENTS[0]
}
