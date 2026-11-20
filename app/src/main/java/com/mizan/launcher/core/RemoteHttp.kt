package com.mizan.launcher.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded HTTP framing for the LAN remote. Content-Length counts UTF-8 bytes. */
object RemoteHttp {
    const val MAX_BODY = 8192
    const val MAX_HEADERS = 8192
    class Invalid(val status: Int, val error: String) : Exception(error)

    fun readBody(input: InputStream): String {
        var headerBytes = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw Invalid(400, "truncated_request")
                if (++headerBytes > MAX_HEADERS) throw Invalid(431, "headers_too_large")
                if (next == 10) {
                    val raw = bytes.toByteArray()
                    if (raw.isEmpty() || raw.last() != 13.toByte()) throw Invalid(400, "bad_request")
                    return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
                }
                if (next == 0 || next > 127) throw Invalid(400, "bad_header")
                bytes.write(next)
            }
        }
        val request = line().split(' ')
        if (request.size != 3 || request[2] !in listOf("HTTP/1.0", "HTTP/1.1"))
            throw Invalid(400, "bad_request")
        if (request[0] != "POST" || request[1] != "/v1/command") throw Invalid(404, "not_found")
        var length: Int? = null
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) throw Invalid(400, "bad_header")
            val name = header.substring(0, colon)
            if (name.any { !it.isLetterOrDigit() && it != '-' }) throw Invalid(400, "bad_header")
            val value = header.substring(colon + 1).trim()
            if (name.equals("Transfer-Encoding", true)) throw Invalid(400, "unsupported_encoding")
            if (name.equals("Content-Length", true)) {
                if (length != null || value.isEmpty() || value.any { it !in '0'..'9' })
                    throw Invalid(400, "bad_length")
                val n = value.toLongOrNull() ?: throw Invalid(413, "body_too_large")
                if (n > MAX_BODY) throw Invalid(413, "body_too_large")
                length = n.toInt()
            }
        }
        val size = length ?: throw Invalid(411, "length_required")
        val body = ByteArray(size)
        var received = 0
        while (received < size) {
            val n = input.read(body, received, size - received)
            if (n <= 0) throw Invalid(400, "truncated_body")
            received += n
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (e: Exception) { throw Invalid(400, "bad_utf8") }
    }
}
