package com.calm.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.calm.launcher.core.ArrowPin
import com.calm.launcher.core.Wallpapers
import com.calm.launcher.data.Store

/**
 * Settings: clock format, wallpaper, profiles, and the arrow lock.
 * Folders and per-app locks are managed from the home screen's long-press menus.
 */
class SettingsActivity : Activity() {

    private lateinit var store: Store
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(48))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        list.addView(title("Settings"))

        list.addView(row("Clock", if (store.clock24h) "24-hour" else "12-hour") {
            store.clock24h = !store.clock24h
            rebuild()
        })

        val preset = Wallpapers.presetAt(store.wallpaperIndex.toString())
        list.addView(row("Wallpaper", preset.name) { pickWallpaper() })

        val profileNo = store.currentProfile
        list.addView(row("Profile", "Profile $profileNo of ${store.profiles.size}") { profileMenu() })

        val hasPattern = store.arrowPattern.isNotEmpty()
        list.addView(row("Arrow lock", if (hasPattern) "Set — locks need it to open" else "Not set") {
            if (!hasPattern) {
                Dialogs.setPattern(this, store) { toast("Arrow lock set"); rebuild() }
            } else {
                lockMenu()
            }
        })
    }

    private fun pickWallpaper() {
        val names = Wallpapers.PRESETS.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Wallpaper")
            .setItems(names) { _, which ->
                store.wallpaperIndex = which
                rebuild()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun profileMenu() {
        val ids = store.profiles
        val labels = ids.map { "Profile $it" + if (it == store.currentProfile) "  (current)" else "" } +
            listOf("Add profile", "Delete current profile")
        AlertDialog.Builder(this)
            .setTitle("Profiles")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < ids.size -> {
                        store.currentProfile = ids[which]
                        toast("Switched to Profile ${ids[which]}")
                        rebuild()
                    }
                    which == ids.size -> {
                        val id = store.addProfile()
                        if (id == null) {
                            toast("All ${com.calm.launcher.core.Profiles.MAX} profiles already exist")
                        } else {
                            store.currentProfile = id
                            toast("Profile $id added — set it up from here")
                            rebuild()
                        }
                    }
                    else -> {
                        val current = store.currentProfile
                        if (ids.size <= 1) {
                            toast("Can't delete the only profile")
                        } else {
                            Dialogs.confirm(this, "Delete Profile $current?",
                                "Removes this profile's favorites, recents, folders, locks and wallpaper. Other profiles are not touched.",
                                "Delete") {
                                store.deleteProfile(current)
                                toast("Profile $current deleted")
                                rebuild()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun lockMenu() {
        AlertDialog.Builder(this)
            .setTitle("Arrow lock")
            .setItems(arrayOf("Change pattern", "Remove lock")) { _, which ->
                // Both need the current pattern first, so nobody can change it at the TV.
                Dialogs.arrowPattern(this, "Current pattern", "Enter the current pattern") { entered ->
                    if (!ArrowPin.matches(store.arrowPattern, entered)) {
                        toast("Wrong pattern")
                        return@arrowPattern
                    }
                    if (which == 0) {
                        Dialogs.setPattern(this, store) { toast("Pattern changed"); rebuild() }
                    } else {
                        store.arrowPattern = ""
                        store.locked = emptyList()
                        toast("Arrow lock removed — all locks cleared")
                        rebuild()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun row(label: String, value: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "$label\n$value"
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(dp(20), dp(16), dp(20), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        }
        background = focusBackground(false)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, hasFocus -> v.background = focusBackground(hasFocus) }
        setOnClickListener { onClick() }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 32f
        gravity = Gravity.START
        setPadding(0, 0, 0, dp(24))
    }

    private fun focusBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (focused) {
            setColor(0x38000000)
            setStroke(dp(3), 0xFF7FB8FF.toInt())
        } else {
            setColor(0x14FFFFFF)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF101418.toInt()
    }
}
