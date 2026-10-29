package com.calm.launcher.core

import java.io.Reader

/**
 * Streaming JSON for the IPTV ingest (v2.4, spec §24) — pure
 * Kotlin over [java.io.Reader], no Android imports, so the sim
 * exercises the exact parser the ingest runs.
 *
 * WHY: the v2.2–v2.3 data layer downloaded each Xtream catalogue
 * endpoint as ONE string (up to 48 MB) and parsed it into a full
 * object graph — on a real panel (tens of thousands of entries)
 * that is the memory blow-up that blacked the TV out. This parser
 * reads a top-level JSON array ONE ELEMENT AT A TIME: the caller
 * maps each element row to a model and writes it to the disk
 * store before the next element is read, so peak memory is one
 * element, never the document.
 *
 * Value semantics mirror core/JsonMini exactly (objects →
 * LinkedHashMap, arrays → ArrayList, numbers → Double, strings /
 * booleans / null), and ingest rows flow through the same
 * IptvLogic row mappers the whole-document parsers use — the two
 * paths cannot disagree about what an element means.
 *
 * STRICTNESS: malformed input throws (IllegalArgumentException /
 * java.io.IOException from the reader). The ingest treats a throw
 * as a failed endpoint and keeps the previous store — silently
 * ingesting half a catalogue would be worse than keeping the old
 * one whole.
 *
 * v3.0: nesting inside an element is capped at JsonMini.MAX_DEPTH.
 * The reader recurses per container over panel-supplied data; an
 * unbounded hostile document died with StackOverflowError — an
 * Error the ingest's Exception catches could not stop. Past the
 * cap the reader throws IllegalArgumentException like any other
 * malformed input, and the endpoint simply fails (old store kept).
 */
object IptvStream {

    /** A one-char-pushback cursor over a Reader. */
    private class Cursor(private val r: Reader) {
        private var pushed: Int = NONE

        /** Current container nesting depth (v3.0 cap — see class doc). */
        var depth: Int = 0

        fun read(): Int {
            if (pushed != NONE) {
                val v = pushed
                pushed = NONE
                return v
            }
            return r.read()
        }

        fun peek(): Int {
            val c = read()
            if (c >= 0) pushed = c
            return c
        }

        fun skipWs() {
            while (true) {
                val c = peek()
                if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) {
                    read()
                } else {
                    return
                }
            }
        }

        companion object {
            const val NONE = -2
        }
    }

    /**
     * Stream a top-level JSON array from [reader]: each OBJECT
     * element is delivered to [onElement] as a row map before the
     * next element is read (non-object elements are consumed and
     * skipped — Xtream catalogue arrays are object arrays, and a
     * stray scalar must not abort an ingest). Returns the number
     * of object elements delivered. Throws on malformed input or
     * a non-array document.
     */
    fun streamArray(reader: Reader, onElement: (Map<String, Any?>) -> Unit): Int {
        val c = Cursor(reader)
        c.skipWs()
        if (c.read() != '['.code) throw IllegalArgumentException("not an array")
        var delivered = 0
        c.skipWs()
        if (c.peek() == ']'.code) {
            c.read()
            return 0
        }
        while (true) {
            c.skipWs()
            val v = readValue(c)
            if (v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                onElement(v as Map<String, Any?>)
                delivered++
            }
            c.skipWs()
            when (c.read()) {
                ','.code -> continue
                ']'.code -> return delivered
                else -> throw IllegalArgumentException("array separator")
            }
        }
    }

    private fun readValue(c: Cursor): Any? {
        c.skipWs()
        return when (c.peek()) {
            '{'.code -> readObject(c)
            '['.code -> readArray(c)
            '"'.code -> readString(c)
            't'.code -> { expect(c, "true"); true }
            'f'.code -> { expect(c, "false"); false }
            'n'.code -> { expect(c, "null"); null }
            else -> readNumber(c)
        }
    }

    private fun expect(c: Cursor, word: String) {
        for (ch in word) {
            if (c.read() != ch.code) throw IllegalArgumentException("literal")
        }
    }

    private fun readObject(c: Cursor): Map<String, Any?> {
        if (++c.depth > JsonMini.MAX_DEPTH) throw IllegalArgumentException("nesting")
        try {
            return readObjectInner(c)
        } finally {
            c.depth--
        }
    }

    private fun readObjectInner(c: Cursor): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        c.read() // '{'
        c.skipWs()
        if (c.peek() == '}'.code) {
            c.read()
            return out
        }
        while (true) {
            c.skipWs()
            val key = readString(c)
            c.skipWs()
            if (c.read() != ':'.code) throw IllegalArgumentException("colon")
            out[key] = readValue(c)
            c.skipWs()
            when (c.read()) {
                ','.code -> continue
                '}'.code -> return out
                else -> throw IllegalArgumentException("object separator")
            }
        }
    }

    private fun readArray(c: Cursor): List<Any?> {
        if (++c.depth > JsonMini.MAX_DEPTH) throw IllegalArgumentException("nesting")
        try {
            return readArrayInner(c)
        } finally {
            c.depth--
        }
    }

    private fun readArrayInner(c: Cursor): List<Any?> {
        val out = ArrayList<Any?>()
        c.read() // '['
        c.skipWs()
        if (c.peek() == ']'.code) {
            c.read()
            return out
        }
        while (true) {
            out.add(readValue(c))
            c.skipWs()
            when (c.read()) {
                ','.code -> continue
                ']'.code -> return out
                else -> throw IllegalArgumentException("array separator")
            }
        }
    }

    private fun readString(c: Cursor): String {
        if (c.read() != '"'.code) throw IllegalArgumentException("quote")
        val sb = StringBuilder()
        while (true) {
            val ch = c.read()
            if (ch < 0) throw IllegalArgumentException("unterminated string")
            when (ch) {
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    when (val e = c.read()) {
                        'n'.code -> sb.append('\n')
                        't'.code -> sb.append('\t')
                        'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        '/'.code -> sb.append('/')
                        '\\'.code -> sb.append('\\')
                        '"'.code -> sb.append('"')
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val h = c.read()
                                val digit = when (h) {
                                    in '0'.code..'9'.code -> h - '0'.code
                                    in 'a'.code..'f'.code -> h - 'a'.code + 10
                                    in 'A'.code..'F'.code -> h - 'A'.code + 10
                                    else -> throw IllegalArgumentException("unicode escape")
                                }
                                code = code * 16 + digit
                            }
                            sb.append(code.toChar())
                        }
                        else -> throw IllegalArgumentException("bad escape $e")
                    }
                }
                else -> sb.append(ch.toChar())
            }
        }
    }

    private fun readNumber(c: Cursor): Double {
        val sb = StringBuilder()
        while (true) {
            val ch = c.peek()
            if (ch == '-'.code || ch == '+'.code || ch == '.'.code ||
                ch == 'e'.code || ch == 'E'.code || ch in '0'.code..'9'.code
            ) {
                sb.append(c.read().toChar())
            } else {
                break
            }
        }
        if (sb.isEmpty()) throw IllegalArgumentException("number")
        return sb.toString().toDouble()
    }
}
