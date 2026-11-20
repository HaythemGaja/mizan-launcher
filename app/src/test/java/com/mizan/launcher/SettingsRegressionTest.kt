package com.mizan.launcher

import com.mizan.launcher.core.SettingsSearch
import com.mizan.launcher.core.SettingsBackup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class SettingsRegressionTest {
    @Test fun featureAliasesFindTheirPages() {
        assertEquals("lockprofiles", SettingsSearch.find("parental pin").single().id)
        assertEquals("data", SettingsSearch.find("backup").single().id)
        assertEquals("phoneremote", SettingsSearch.find("pair wifi").single().id)
        assertEquals("look", SettingsSearch.find("wallpaper").single().id)
    }
    @Test fun emptyAndUnknownQueriesAreUsable() {
        assertEquals(12, SettingsSearch.find(" \t ").size)
        assertTrue(SettingsSearch.find("unrecognized-xyz").isEmpty())
        assertEquals(12, SettingsSearch.entries.map { it.id }.distinct().size)
    }
    @Test fun searchIsLocaleIndependentAndIgnoresAccents() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("iptv", SettingsSearch.find("IPTV").single().id)
            assertEquals("look", SettingsSearch.find("thème").single().id)
        } finally { Locale.setDefault(old) }
    }
    private fun backup(profiles: String = "{\"1\":{}}", extra: String = "") =
        "{\"app\":\"mizan-launcher\",\"version\":1,\"profiles\":$profiles$extra}"
    @Test fun validatesEnvelopeBeforeAnyImport() {
        assertTrue(SettingsBackup.valid(backup()))
        assertFalse(SettingsBackup.valid("{\"app\":\"mizan-launcher\",\"safe_mode\":true}"))
        assertFalse(SettingsBackup.valid(backup("{}")))
        assertFalse(SettingsBackup.valid(backup("{\"99\":{}}")))
        assertFalse(SettingsBackup.valid(backup("{\"1\":null}")))
        assertFalse(SettingsBackup.valid(backup().replace("\"version\":1", "\"version\":2")))
    }
    @Test fun rejectsInvalidTypesAndFractionalIntegers() {
        assertFalse(SettingsBackup.valid(backup(extra = ",\"safe_mode\":\"true\"")))
        assertFalse(SettingsBackup.valid(backup("{\"1\":{\"columns\":3.5}}")))
        assertFalse(SettingsBackup.valid(backup("{\"1\":{\"locked\":[1]}}")))
        assertTrue(SettingsBackup.valid(backup("{\"1\":{\"columns\":6,\"locked\":[\"tv.app\"]}}")))
    }
    @Test fun boundsProviderInputAndReadsUtf8() {
        val text = backup("{\"1\":{\"profile_name\":\"Café\"}}")
        assertEquals(text, SettingsBackup.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        try {
            SettingsBackup.read(ByteArrayInputStream(ByteArray(SettingsBackup.MAX_BYTES + 1)))
            fail("Oversized documents must be rejected")
        } catch (expected: IllegalArgumentException) { }
    }
}
