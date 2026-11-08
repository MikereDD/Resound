package com.typezero.resound.core.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded strict JSON parser for release manifests. Host tests use Maven org.json while Android
 * provides its own implementation. Only their object/array containers are used: shared syntax,
 * decoded-key uniqueness and numeric types never depend on either platform's text tokenizer.
 */
internal object StrictJson {
    private const val MAX_DEPTH = 64

    /** Parses exactly one strict JSON object, rejecting trailing content and invalid Unicode. */
    fun requireObject(text: String): JSONObject {
        val parser = Parser(text)
        parser.skipWhitespace()
        val root = parser.requireRootObject()
        parser.skipWhitespace()
        parser.requireEnd()
        return root
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in WS) pos++
        }

        fun requireEnd() {
            if (pos != text.length) fail("Trailing content")
        }

        fun requireRootObject(): JSONObject {
            expect('{')
            return objectBody(1)
        }

        private fun objectBody(depth: Int): JSONObject {
            requireDepth(depth)
            val out = JSONObject()
            skipWhitespace()
            if (consume('}')) return out
            val keys = HashSet<String>()
            while (true) {
                skipWhitespace()
                val key = string()
                if (!keys.add(key)) fail("Duplicate JSON key")
                skipWhitespace()
                expect(':')
                out.put(key, value(depth + 1))
                skipWhitespace()
                if (consume('}')) return out
                expect(',')
            }
        }

        private fun arrayBody(depth: Int): JSONArray {
            requireDepth(depth)
            val out = JSONArray()
            skipWhitespace()
            if (consume(']')) return out
            while (true) {
                out.put(value(depth + 1))
                skipWhitespace()
                if (consume(']')) return out
                expect(',')
            }
        }

        private fun value(depth: Int): Any {
            requireDepth(depth)
            skipWhitespace()
            return when {
                consume('{') -> objectBody(depth)
                consume('[') -> arrayBody(depth)
                consume('"') -> stringBody()
                consume('t') -> { literalTail("rue"); true }
                consume('f') -> { literalTail("alse"); false }
                consume('n') -> { literalTail("ull"); JSONObject.NULL }
                else -> number()
            }
        }

        private fun string(): String {
            expect('"')
            return stringBody()
        }

        private fun stringBody(): String {
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> {
                        if (c < ' ') fail("control character in string")
                        if (c in '\uD800'..'\uDBFF') {
                            val low = peek()
                            if (low == null || low !in '\uDC00'..'\uDFFF') fail("Invalid unicode surrogate")
                            out.append(c).append(low)
                            pos++
                            continue
                        }
                        if (c in '\uDC00'..'\uDFFF') fail("Invalid unicode surrogate")
                        out.append(c)
                    }
                }
            }
        }

        private fun escape(): String {
            if (pos >= text.length) fail("unterminated escape")
            return when (text[pos++]) {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> unicodeEscape()
                else -> fail("invalid escape")
            }
        }

        private fun unicodeEscape(): String {
            val first = hex4()
            if (first in 0xD800..0xDBFF) {
                if (pos + 1 >= text.length || text[pos] != '\\' || text[pos + 1] != 'u') {
                    fail("Invalid unicode surrogate")
                }
                pos += 2
                val second = hex4()
                if (second !in 0xDC00..0xDFFF) fail("Invalid unicode surrogate")
                return String(charArrayOf(first.toChar(), second.toChar()))
            }
            if (first in 0xDC00..0xDFFF) fail("Invalid unicode surrogate")
            return first.toChar().toString()
        }

        private fun hex4(): Int {
            var value = 0
            repeat(4) {
                if (pos >= text.length) fail("short unicode escape")
                val digit = when (val c = text[pos++]) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> fail("invalid unicode escape")
                }
                value = value * 16 + digit
            }
            return value
        }

        private fun number(): Number {
            val start = pos
            consume('-')
            when {
                consume('0') -> if (peek()?.let { it in '0'..'9' } == true) fail("leading zero in number")
                else -> {
                    if (!digitOneToNine()) fail("number")
                    while (digit()) Unit
                }
            }
            if (consume('.')) {
                if (!digit()) fail("number fraction")
                while (digit()) Unit
            }
            if (consume('e') || consume('E')) {
                consume('+') || consume('-')
                if (!digit()) fail("number exponent")
                while (digit()) Unit
            }
            val token = text.substring(start, pos)
            // Preserve the existing JVM schema rule: decimal/exponent tokens and negative zero
            // must not be narrowed to integer types by Android's more permissive tokenizer.
            if ('.' in token || 'e' in token || 'E' in token || token == "-0") {
                return token.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("number out of range")
            }
            val integer = token.toLongOrNull() ?: fail("number out of range")
            return if (integer in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) integer.toInt() else integer
        }

        private fun literalTail(tail: String) {
            if (!text.regionMatches(pos, tail, 0, tail.length)) fail("literal")
            pos += tail.length
        }

        private fun digitOneToNine(): Boolean {
            if (pos < text.length && text[pos] in '1'..'9') {
                pos++
                return true
            }
            return false
        }

        private fun digit(): Boolean {
            if (pos < text.length && text[pos] in '0'..'9') {
                pos++
                return true
            }
            return false
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun expect(c: Char) {
            if (!consume(c)) fail("expected '$c'")
        }

        private fun consume(c: Char): Boolean {
            if (pos < text.length && text[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun requireDepth(depth: Int) {
            if (depth > MAX_DEPTH) fail("JSON is too deeply nested")
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Invalid strict JSON at offset $pos: $message")

        private companion object {
            val WS = setOf(' ', '\t', '\r', '\n')
        }
    }
}
