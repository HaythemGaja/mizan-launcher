package com.mizan.launcher.ui

import android.content.Context
import com.mizan.launcher.core.ArtworkStore

/**
 * Artwork picker: per-app custom artwork — 12 presets (names + colours,
 * indices coerced) or an image URL; garbage decodes empty.
 * Preset names/colours come from core/ArtworkStore where available;
 * the picker itself only passes preset index / URL through UiWiring.apps
 * artwork fields (data layer persists via ArtworkStore codec).
 */
object ArtworkPicker {
    val PRESET_NAMES: List<String> = (0 until 12).map { ArtworkStore.presetName(it) }
    fun show(ctx: Context, packageName: String, currentPreset: Int?, onPick: (Int?, String?) -> Unit) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        PRESET_NAMES.forEachIndexed { i, name ->
            items += (if (currentPreset == i) "✓ $name" else name) to { onPick(i, null) }
        }
        items += "Image URL…" to {
            KeyboardDialog.show(ctx, "Artwork image URL", maxLen = 300) { url -> onPick(null, url.ifBlank { null }) }
        }
        items += "Use app icon" to { onPick(null, null) }
        Ui.menu(ctx, "Artwork for $packageName", items)
    }
    /** Coerce a preset index into range, garbage -> null (empty). */
    fun coercePreset(raw: Int?): Int? = if (raw == null || raw !in 0 until 12) null else raw
    @Suppress("UNUSED_PARAMETER")
    fun storeRef(): String = ArtworkStore::class.java.simpleName
}
