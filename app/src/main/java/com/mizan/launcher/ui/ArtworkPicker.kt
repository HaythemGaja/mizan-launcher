package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.mizan.launcher.core.ArtworkStore
import com.mizan.launcher.data.ArtworkImages
import com.mizan.launcher.data.Prefs

/**
 * v2.0: the "Custom artwork…" dialog shared by every app menu (home,
 * All Apps, Search). Choices: one of the 12 generated presets,
 * "Image from URL…" (typed on the D-pad keyboard, downloaded +
 * validated before anything is stored), and — when the app already
 * has custom artwork — "Reset to app icon". All changes write through
 * Prefs (per profile) and call [onChanged] so the caller rebuilds.
 */
object ArtworkPicker {

    fun show(
        activity: Activity,
        prefs: Prefs,
        accent: Int,
        pkg: String,
        label: String,
        onChanged: () -> Unit
    ) {
        val current = prefs.artworkMap()[pkg]
        val labels = ArrayList<String>()
        val presetCount = ArtworkStore.PRESET_COUNT
        for (i in 0 until presetCount) {
            val mark = if (current?.kind == ArtworkStore.Kind.PRESET &&
                ArtworkStore.presetIndex(current.value) == i
            ) " ✓" else ""
            labels.add("Preset: ${ArtworkStore.presetName(i)}$mark")
        }
        labels.add("Image from URL…")
        val urlIndex = labels.size - 1
        val resetIndex = if (current != null) {
            labels.add("Reset to app icon")
            labels.size - 1
        } else -1

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Artwork — $label")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which in 0 until presetCount -> {
                        prefs.setArtwork(pkg, ArtworkStore.preset(which))
                        onChanged()
                    }
                    which == urlIndex -> {
                        val initial = if (current?.kind == ArtworkStore.Kind.URL) {
                            current.value
                        } else {
                            "https://"
                        }
                        KeyboardDialog.show(
                            activity,
                            "Artwork image URL",
                            accent,
                            initial,
                            maxLen = 400,
                            symbols = true
                        ) { url ->
                            val clean = url.trim()
                            if (clean.isBlank() ||
                                !(clean.startsWith("http://") || clean.startsWith("https://"))
                            ) {
                                Toast.makeText(
                                    activity, "That doesn't look like an image URL",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    activity, "Downloading artwork…", Toast.LENGTH_SHORT
                                ).show()
                                ArtworkImages.fetchAndStore(activity, pkg, clean) { ok ->
                                    if (ok) {
                                        prefs.setArtwork(pkg, ArtworkStore.url(clean))
                                        onChanged()
                                    } else {
                                        Toast.makeText(
                                            activity,
                                            "Couldn't download that image — artwork unchanged",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                    which == resetIndex -> {
                        prefs.setArtwork(pkg, null)
                        ArtworkImages.invalidate(activity, pkg)
                        onChanged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }
}
