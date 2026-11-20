package com.mizan.launcher.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Validate the complete settings envelope before touching live preferences. */
object SettingsBackup {
    const val MAX_BYTES = 4 * 1024 * 1024

    fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BYTES) { "Backup exceeds the 4 MB limit" }
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }

    fun valid(text: String): Boolean {
        if (text.length > MAX_BYTES) return false
        val root = JsonMini.parse(text) as? Map<*, *> ?: return false
        if (root["app"] != "mizan-launcher" || root["version"] != 1.0) return false
        val profiles = root["profiles"] as? Map<*, *> ?: return false
        if (profiles.isEmpty() || profiles.size > ProfileSet.MAX_PROFILES) return false
        val globalBooleans = setOf("safe_mode", "arrow_lock_set", "guest_lock_settings", "lock_settings")
        val globalStrings = setOf("weather_lat", "weather_lon")
        if (!typesMatch(root, globalBooleans, globalStrings, setOf("current_profile", "guest_profile"))) return false
        val booleanKeys = setOf("reddit_nsfw", "home_hint_dismissed", "sticky_status_bar")
        val stringKeys = setOf("theme", "wall_kind", "wall_value", "icon_shape", "app_size", "layout",
            "reddit_subs", "reddit_rotation", "weather_units", "home_style", "profile_name",
            "tracker_sections", "artwork")
        return profiles.all { (key, raw) ->
            val id = (key as? String)?.toIntOrNull()
            val profile = raw as? Map<*, *>
            id != null && id in 1..ProfileSet.MAX_PROFILES && key == id.toString() &&
                profile != null && typesMatch(profile, booleanKeys, stringKeys, setOf("columns", "unlock_grace_min")) &&
                (!profile.containsKey("locked") || (profile["locked"] as? List<*>)?.all { it is String } == true)
        }
    }

    private fun typesMatch(map: Map<*, *>, booleans: Set<String>, strings: Set<String>, numbers: Set<String>): Boolean =
        map.all { (rawKey, value) ->
            val key = rawKey as? String ?: return@all false
            when (key) {
                in booleans -> value is Boolean
                in strings -> value is String
                in numbers -> value is Double && value.isFinite() && value % 1.0 == 0.0 &&
                    value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
                else -> true // Future optional fields can coexist with version 1.
            }
        }
}
