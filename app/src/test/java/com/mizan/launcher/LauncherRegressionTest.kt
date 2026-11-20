package com.mizan.launcher

import com.mizan.launcher.core.AppSearch
import com.mizan.launcher.core.JsonMini
import com.mizan.launcher.core.RemoteHttp
import com.mizan.launcher.core.RemoteProtocol
import com.mizan.launcher.data.DecodeBounds
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class LauncherRegressionTest {
    private fun apps(vararg labels: String) = labels.mapIndexed { i, label -> AppSearch.Item("app.$i", label) }
    private fun search(query: String, vararg labels: String) = AppSearch.filter(apps(*labels), query).map { it.label }

    @Test fun searchRanksExactPrefixAndContains() {
        assertEquals(listOf("You", "YouTube", "My YouTube"), search("you", "My YouTube", "YouTube", "You"))
    }
    @Test fun searchSupportsAccentsAndSeparateWords() {
        assertEquals(listOf("Café TV"), search("tv cafe", "Café TV", "Café Radio"))
        assertEquals(listOf("Café TV"), search("cafe", "Café TV"))
    }
    @Test fun searchKeepsStableOrderWithinEachRank() {
        assertEquals(listOf("YouTube", "YouCount", "My YouTube"), search("you", "My YouTube", "YouTube", "YouCount"))
        val input = apps("Z", "A")
        assertSame(input, AppSearch.filter(input, "  "))
    }
    @Test fun searchDoesNotDependOnTurkishDeviceCasing() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("IPTV"), search("iptv", "IPTV"))
        } finally { Locale.setDefault(old) }
    }
    @Test fun jsonRejectsNonJsonNumbers() {
        for (bad in listOf("01", "+1", ".5", "1.", "1e", "1e9999", "--2", "[01]")) {
            assertNull(bad, JsonMini.parse(bad))
        }
    }
    @Test fun jsonAcceptsValidNumbersAndEscapedUnicode() {
        assertEquals(-1250.0, JsonMini.parse("-1.25e+3"))
        assertEquals("café", JsonMini.parse("\"caf\\u00e9\""))
    }
    @Test fun jsonRejectsRawControlsTrailingGarbageAndDeepNesting() {
        assertNull(JsonMini.parse("\"a\nb\""))
        assertNull(JsonMini.parse("{} trailing"))
        assertNull(JsonMini.parse("[".repeat(5000) + "0" + "]".repeat(5000)))
        assertNull(JsonMini.parse("{\"a\":1,}"))
    }
    private fun request(body: ByteArray, extra: String = "", path: String = "/v1/command"): ByteArray =
        "POST $path HTTP/1.1\r\nContent-Length: ${body.size}\r\n$extra\r\n".toByteArray() + body
    private fun read(bytes: ByteArray) = RemoteHttp.readBody(ByteArrayInputStream(bytes))
    private fun rejects(status: Int, bytes: ByteArray) {
        try { read(bytes); fail("Request must be rejected") }
        catch (e: RemoteHttp.Invalid) { assertEquals(status, e.status) }
    }
    @Test fun remoteHttpCountsUtf8Bytes() {
        val body = "{\"title\":\"Café 📺\"}"
        assertEquals(body, read(request(body.toByteArray(Charsets.UTF_8))))
    }
    @Test fun remoteHttpReadsBodiesDeliveredInShortChunks() {
        val body = "{\"command\":\"status\"}"
        val input = object : ByteArrayInputStream(request(body.toByteArray())) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(1, length))
        }
        assertEquals(body, RemoteHttp.readBody(input))
    }
    @Test fun remoteHttpRequiresExactRoute() {
        rejects(404, request("{}".toByteArray(), path = "/v1/command-extra"))
    }
    @Test fun remoteHttpRejectsOversizedAndTruncatedBodies() {
        rejects(413, request(ByteArray(8193)))
        rejects(400, "POST /v1/command HTTP/1.1\r\nContent-Length: 5\r\n\r\n{}".toByteArray())
    }
    @Test fun remoteHttpRejectsDuplicateLengthsAndTransferEncoding() {
        rejects(400, request("{}".toByteArray(), "Content-Length: 2\r\n"))
        rejects(400, request("{}".toByteArray(), "Transfer-Encoding: chunked\r\n"))
    }
    @Test fun remoteHttpRejectsBadUtf8AndMissingLength() {
        rejects(400, request(byteArrayOf(0xC3.toByte(), 0x28)))
        rejects(411, "POST /v1/command HTTP/1.1\r\n\r\n".toByteArray())
    }
    @Test fun remoteHttpBoundsHeadersAndAcceptsBodyAtLimit() {
        rejects(431, request("{}".toByteArray(), "X-Long: ${"x".repeat(8192)}\r\n"))
        assertEquals(8192, read(request(ByteArray(8192) { 65 })).length)
    }
    @Test fun samplingCapsPanoramasAndCeilingRounding() {
        assertEquals(64, DecodeBounds.sampleSize(32000, 16, 512))
        assertEquals(2, DecodeBounds.sampleSize(1025, 1, 1024))
        assertEquals(1, DecodeBounds.sampleSize(128, 128, 512))
        assertEquals(1 shl 22, DecodeBounds.sampleSize(Int.MAX_VALUE, 1, 512))
    }
    @Test fun pairingCodesUseAsciiDecimalDigits() {
        assertTrue(RemoteProtocol.isValidPairingCode("123456"))
        assertFalse(RemoteProtocol.isValidPairingCode("١٢٣٤٥٦"))
        assertFalse(RemoteProtocol.isValidPairingCode("12345"))
    }
    @Test fun appLockSwitchControlsAllMembershipCombinations() {
        assertTrue(RemoteProtocol.appRequiresPrompt(true, true))
        assertFalse(RemoteProtocol.appRequiresPrompt(true, false))
        assertFalse(RemoteProtocol.appRequiresPrompt(false, true))
        assertFalse(RemoteProtocol.appRequiresPrompt(false, false))
    }
}
