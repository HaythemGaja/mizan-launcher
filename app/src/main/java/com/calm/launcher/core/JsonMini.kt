package com.calm.launcher.core

/**
 * Minimal JSON reader — pure Kotlin, no Android imports, no dependencies.
 *
 * Why not org.json? org.json exists on Android, but its JVM copy is a
 * stub (every method throws), so code built on it cannot be verified in
 * the headless sim. This reader covers the JSON the launcher consumes
 * (Reddit listing responses, its own backup format checks in tests):
 * objects, arrays, strings with escapes, numbers, booleans, null.
 *
 * Values come back as: Map<String, Any?> / List<Any?> / String /
 * Double / Boolean / null. Any malformed input returns null from
 * [parse] instead of throwing (fail-open).
 *
 * v3.0: nesting is capped at [MAX_DEPTH]. The reader recurses per
 * container, and every document this parser touches is network-fed
 * (tracker snapshots) — a hostile deeply-nested
 * payload recursed until StackOverflowError, an Error that escapes
 * every `catch (e: Exception)` in the parse paths and kills the
 * thread (on the main thread: the process). Past the cap the reader
 * throws IllegalArgumentException, which [parse] converts to null
 * like any malformed input. Real documents nest well under 20.
 */
object JsonMini {

    /** Maximum container nesting depth accepted (see class doc). */
    const val MAX_DEPTH = 64

    fun parse(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        val r = Reader(text)
        return try {
            r.skipWs()
            val v = r.readValue()
            r.skipWs()
            if (r.atEnd()) v else null // trailing garbage = malformed
        } catch (e: Exception) {
            null
        }
    }

    private class Reader(private val s: String) {
        private var i = 0
        private var depth = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun readValue(): Any? {
            if (i >= s.length) throw IllegalArgumentException("eof")
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
            if (!s.regionMatches(i, word, 0, word.length)) throw IllegalArgumentException("literal")
            i += word.length
        }

        private fun readObject(): Map<String, Any?> {
            if (++depth > MAX_DEPTH) throw IllegalArgumentException("nesting")
            try {
                return readObjectInner()
            } finally {
                depth--
            }
        }

        private fun readObjectInner(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // '{'
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("colon")
                i++
                skipWs()
                out[key] = readValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("eof in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("object separator")
                }
            }
        }

        private fun readArray(): List<Any?> {
            if (++depth > MAX_DEPTH) throw IllegalArgumentException("nesting")
            try {
                return readArrayInner()
            } finally {
                depth--
            }
        }

        private fun readArrayInner(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // '['
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("eof in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("array separator")
                }
            }
        }

        private fun readString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("quote")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when (c) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) throw IllegalArgumentException("escape eof")
                        when (s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            '/' -> sb.append('/')
                            '\\' -> sb.append('\\')
                            '"' -> sb.append('"')
                            'u' -> {
                                if (i + 4 >= s.length) throw IllegalArgumentException("unicode escape")
                                val hex = s.substring(i + 1, i + 5)
                                // v3.15: strict hex digits only ("\u-001" is malformed).
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                                    throw IllegalArgumentException("unicode escape")
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readNumber(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            if (start == i) throw IllegalArgumentException("number")
            return s.substring(start, i).toDouble()
        }
    }
}

/** Typed accessors over [JsonMini] values — all fail-open to null/default. */
@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): List<Any?>? = this as? List<Any?>
fun Any?.asStr(): String? = this as? String
fun Any?.asBool(): Boolean? = this as? Boolean
fun Any?.asInt(): Int? = (this as? Number)?.toInt()
