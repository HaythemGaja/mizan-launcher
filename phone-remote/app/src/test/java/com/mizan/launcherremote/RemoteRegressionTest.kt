package com.mizan.launcherremote

import org.junit.Assert.*
import org.junit.Test

class RemoteRegressionTest {
    private fun bad(text: String) = assertEquals(RemoteClient.Result.Err("bad_reply"), RemoteClient.parseResponse(text))
    @Test fun rejectsTrailingGarbage() { bad("{\"ok\":false,\"error\":\"bad_code\"} garbage") }
    @Test fun rejectsDeeplyNestedReplies() { bad("[".repeat(5000) + "0" + "]".repeat(5000)) }
    @Test fun rejectsRawControlsAndBrokenEscapes() {
        bad("{\"ok\":false,\"error\":\"line\nbreak\"}")
        bad("{\"ok\":false,\"error\":\"\\u12\"}")
        bad("{\"ok\":false,\"error\":\"\\q\"}")
    }
    @Test fun rejectsOversizedRepliesAndMalformedNumbers() {
        bad(" ".repeat(65537))
        bad("{\"ok\":false,\"count\":01}")
    }
    @Test fun parsesAnErrorWithUnicodeAndControlEscapes() {
        assertEquals(RemoteClient.Result.Err("café\b"),
            RemoteClient.parseResponse("{\"ok\":false,\"error\":\"caf\\u00e9\\b\"}"))
    }
}
