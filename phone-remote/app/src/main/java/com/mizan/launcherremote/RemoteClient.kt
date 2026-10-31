package com.mizan.launcherremote

/**
 * Phone side of the Sreeb remote v1 protocol (see PROTOCOL.md).
 * Pure Kotlin — no Android imports — so the request/response
 * logic is covered by the headless check in protocol-check/
 * without an emulator.
 *
 * The client NEVER invents a host: every URL is
 * http://<the IP the user typed>:47819/v1/command. There is
 * no discovery, no cloud, no account, and no other endpoint.
 * The pairing code goes in the JSON body of every request;
 * the TV (Sreeb v3.19+, core/RemoteProtocol) is the authority
 * on what is valid — this client only builds requests and
 * reads replies, and it never sees the arrow code back (the
 * TV reports lockSet true/false only).
 */
object RemoteClient {

    const val PORT = 47819

    data class ThemeOption(val id: String, val name: String)

    data class Status(
        val style: String,
        val themeId: String,
        val themeOptions: List<ThemeOption>,
        val appLock: Boolean,
        val folderLock: Boolean,
        val lockSet: Boolean,
        val libraryTitles: Int
    )

    sealed class Result {
        data class Ok(val status: Status) : Result()
        data class Err(val error: String) : Result()
    }

    fun url(ip: String): String = "http://$ip:$PORT/v1/command"

    private fun esc(s: String): String {
        val b = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        return b.toString()
    }

    /**
     * Build one request body. [params] values are Strings
     * (quoted) or Booleans (bare); anything else is refused
     * by the caller, never silently encoded.
     */
    fun requestJson(pairing: String, command: String, params: List<Pair<String, Any>> = emptyList()): String {
        val b = StringBuilder()
        b.append("{\"pairing\":\"").append(esc(pairing)).append("\",")
        b.append("\"command\":\"").append(esc(command)).append("\"")
        for ((k, v) in params) {
            b.append(",\"").append(esc(k)).append("\":")
            when (v) {
                is Boolean -> b.append(if (v) "true" else "false")
                is String -> b.append("\"").append(esc(v)).append("\"")
                else -> throw IllegalArgumentException("unsupported param type")
            }
        }
        b.append("}")
        return b.toString()
    }

    // ---- minimal JSON reader (objects, arrays, strings, numbers, booleans, null) ----

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Any? {
            skipWs()
            val v = readValue()
            skipWs()
            return v
        }
        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
        private fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("truncated")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }
        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal")
            i += word.length
        }
        private fun readObject(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected :")
                i++
                out[key] = readValue()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return out }
                throw IllegalArgumentException("bad object")
            }
        }
        private fun readArray(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return out }
                throw IllegalArgumentException("bad array")
            }
        }
        private fun readString(): String {
            if (s[i] != '"') throw IllegalArgumentException("expected string")
            i++
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return b.toString()
                if (c == '\\' && i < s.length) {
                    when (val e = s[i++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> {
                            val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                            b.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> b.append(e)
                    }
                } else b.append(c)
            }
            throw IllegalArgumentException("unterminated string")
        }
        private fun readNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number")
        }
    }

    /** Parse one reply. Never throws: garbage is Err("bad_reply"). */
    fun parseResponse(json: String): Result {
        return try {
            val obj = Parser(json).parse() as? Map<*, *> ?: return Result.Err("bad_reply")
            val ok = obj["ok"] ?: return Result.Err("bad_reply")
            if (ok != true) {
                return Result.Err((obj["error"] as? String) ?: "error")
            }
            val opts = ArrayList<ThemeOption>()
            val rawOpts = obj["themeOptions"] as? List<*>
            if (rawOpts != null) {
                for (o in rawOpts) {
                    val m = o as? Map<*, *> ?: continue
                    val id = m["id"] as? String ?: continue
                    val name = m["name"] as? String ?: id
                    opts.add(ThemeOption(id, name))
                }
            }
            Result.Ok(
                Status(
                    style = (obj["style"] as? String) ?: "",
                    themeId = (obj["themeId"] as? String) ?: "",
                    themeOptions = opts,
                    appLock = (obj["appLock"] as? Boolean) ?: true,
                    folderLock = (obj["folderLock"] as? Boolean) ?: true,
                    lockSet = (obj["lockSet"] as? Boolean) ?: false,
                    libraryTitles = ((obj["libraryTitles"] as? Number)?.toInt()) ?: 0
                )
            )
        } catch (e: Exception) {
            Result.Err("bad_reply")
        }
    }
}
