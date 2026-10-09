package com.ojas.permsense.json

/**
 * A small JSON reader and writer.
 *
 * Written here rather than taken from a library for three reasons: it behaves identically on
 * Android and on the JVM (the platform's org.json is not the same code as the JVM one), it is
 * strict (duplicate keys are rejected, because a verifier that silently picks one of two values
 * can be fooled), and its output is canonical (object keys sorted, no insignificant whitespace),
 * so the same data always produces the same bytes.
 *
 * Values are: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean and null.
 */
class JsonException(message: String, val position: Int = -1) :
    Exception(if (position >= 0) "$message (at $position)" else message)

object Json {

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.value(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw JsonException("Unexpected content after the JSON value", parser.position)
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Expected a JSON object")

    /** Canonical form: keys sorted, no extra whitespace. */
    fun write(value: Any?): String = StringBuilder().also { write(it, value, null, 0) }.toString()

    /** Same content, indented for people. Keys are still sorted. */
    fun writePretty(value: Any?): String = StringBuilder().also { write(it, value, "  ", 0) }.toString()

    // -------------------------------------------------------------------------------- writer

    private fun write(out: StringBuilder, value: Any?, indent: String?, depth: Int) {

        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append((value as Number).toLong())
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is String -> string(out, value)
            is Map<*, *> -> writeObject(out, value, indent, depth)
            is Iterable<*> -> writeArray(out, value.toList(), indent, depth)
            is Array<*> -> writeArray(out, value.toList(), indent, depth)
            else -> throw JsonException("Cannot write a ${value::class.java.simpleName} as JSON")
        }
    }

    private fun number(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            throw JsonException("JSON cannot represent $value")
        }
        return value.toString()
    }

    private fun writeObject(out: StringBuilder, map: Map<*, *>, indent: String?, depth: Int) {

        if (map.isEmpty()) {
            out.append("{}")
            return
        }

        val keys = map.keys.map {
            it as? String ?: throw JsonException("JSON object keys must be strings")
        }.sorted()

        out.append('{')
        keys.forEachIndexed { i, key ->
            if (i > 0) out.append(',')
            newline(out, indent, depth + 1)
            string(out, key)
            out.append(':')
            if (indent != null) out.append(' ')
            write(out, map[key], indent, depth + 1)
        }
        newline(out, indent, depth)
        out.append('}')
    }

    private fun writeArray(out: StringBuilder, list: List<*>, indent: String?, depth: Int) {

        if (list.isEmpty()) {
            out.append("[]")
            return
        }

        out.append('[')
        list.forEachIndexed { i, item ->
            if (i > 0) out.append(',')
            newline(out, indent, depth + 1)
            write(out, item, indent, depth + 1)
        }
        newline(out, indent, depth)
        out.append(']')
    }

    private fun newline(out: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        out.append('\n')
        repeat(depth) { out.append(indent) }
    }

    private fun string(out: StringBuilder, value: String) {

        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                // Control characters, and the two separators that are legal JSON but not legal
                // JavaScript, are escaped so the output is safe to embed anywhere.
                c < ' ' || c == ' ' || c == ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    // -------------------------------------------------------------------------------- reader

    private class Parser(private val text: String) {

        var position = 0
            private set

        fun atEnd() = position >= text.length

        fun skipWhitespace() {
            while (position < text.length) {
                val c = text[position]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') position++ else break
            }
        }

        fun value(depth: Int): Any? {

            if (depth > MAX_DEPTH) throw JsonException("Nesting is too deep", position)
            if (atEnd()) throw JsonException("Unexpected end of input", position)

            return when (val c = text[position]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> throw JsonException("Unexpected character '$c'", position)
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!text.startsWith(word, position)) {
                throw JsonException("Invalid literal", position)
            }
            position += word.length
            return result
        }

        private fun obj(depth: Int): Map<String, Any?> {

            position++ // {
            val result = LinkedHashMap<String, Any?>()

            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }

            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("Expected an object key", position)
                val key = string()

                skipWhitespace()
                if (peek() != ':') throw JsonException("Expected ':'", position)
                position++

                skipWhitespace()
                val value = value(depth + 1)

                if (result.containsKey(key)) {
                    throw JsonException("Duplicate key \"$key\"", position)
                }
                result[key] = value

                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> throw JsonException("Expected ',' or '}'", position)
                }
            }
        }

        private fun array(depth: Int): List<Any?> {

            position++ // [
            val result = ArrayList<Any?>()

            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }

            while (true) {
                skipWhitespace()
                result += value(depth + 1)

                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> throw JsonException("Expected ',' or ']'", position)
                }
            }
        }

        private fun string(): String {

            position++ // opening quote
            val out = StringBuilder()

            while (true) {
                if (atEnd()) throw JsonException("Unterminated string", position)
                val c = text[position++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape", position)
                        when (val e = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("Short \\u escape", position)
                                val hex = text.substring(position, position + 4)
                                out.append(
                                    hex.toIntOrNull(16)?.toChar()
                                        ?: throw JsonException("Invalid \\u escape", position)
                                )
                                position += 4
                            }
                            else -> throw JsonException("Invalid escape '\\$e'", position)
                        }
                    }
                    c < ' ' -> throw JsonException("Unescaped control character in string", position)
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {

            val start = position
            if (peek() == '-') position++

            if (peek() == '0') {
                position++
            } else if (peek() in '1'..'9') {
                while (peek() in '0'..'9') position++
            } else {
                throw JsonException("Invalid number", start)
            }

            var integral = true

            if (peek() == '.') {
                integral = false
                position++
                if (peek() !in '0'..'9') throw JsonException("Invalid number", start)
                while (peek() in '0'..'9') position++
            }

            if (peek() == 'e' || peek() == 'E') {
                integral = false
                position++
                if (peek() == '+' || peek() == '-') position++
                if (peek() !in '0'..'9') throw JsonException("Invalid number", start)
                while (peek() in '0'..'9') position++
            }

            val token = text.substring(start, position)

            if (integral) {
                token.toLongOrNull()?.let { return it }
            }
            return token.toDoubleOrNull() ?: throw JsonException("Invalid number", start)
        }

        private fun peek(): Char = if (position < text.length) text[position] else '\u0000'
    }
}

// ------------------------------------------------------------------------------ typed access

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: throw JsonException("\"$key\" must be an object")

fun Map<String, Any?>.arr(key: String): List<Any?> =
    this[key] as? List<Any?> ?: throw JsonException("\"$key\" must be an array")

fun Map<String, Any?>.str(key: String): String =
    this[key] as? String ?: throw JsonException("\"$key\" must be a string")

fun Map<String, Any?>.strOrNull(key: String): String? =
    when (val v = this[key]) {
        null -> null
        is String -> v
        else -> throw JsonException("\"$key\" must be a string or null")
    }

fun Map<String, Any?>.long(key: String): Long =
    longOrNull(key) ?: throw JsonException("\"$key\" must be an integer")

fun Map<String, Any?>.longOrNull(key: String): Long? =
    when (val v = this[key]) {
        null -> null
        is Long -> v
        is Double -> if (v == Math.floor(v) && Math.abs(v) < 9.0e15) v.toLong() else throw JsonException("\"$key\" must be an integer")
        else -> throw JsonException("\"$key\" must be an integer")
    }

fun Map<String, Any?>.int(key: String): Int {
    val v = long(key)
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw JsonException("\"$key\" is out of range")
    return v.toInt()
}

fun Map<String, Any?>.bool(key: String): Boolean =
    this[key] as? Boolean ?: throw JsonException("\"$key\" must be true or false")

fun Map<String, Any?>.double(key: String): Double =
    when (val v = this[key]) {
        is Long -> v.toDouble()
        is Double -> v
        else -> throw JsonException("\"$key\" must be a number")
    }
