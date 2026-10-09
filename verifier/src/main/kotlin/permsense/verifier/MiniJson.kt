package permsense.verifier

/**
 * A strict JSON reader for the verifier. Its own code, not shared with the app.
 *
 * Strict on purpose: duplicate keys are rejected (a reader that quietly picks one of two values
 * can be fooled), numbers keep full 64-bit precision, and nothing but whitespace may follow the
 * value. Objects come back as Map<String, Any?>, arrays as List<Any?>, integers as Long,
 * other numbers as Double.
 */
class JsonProblem(message: String) : Exception(message)

class MiniJson(private val text: String) {

    private var pos = 0

    companion object {
        private const val MAX_DEPTH = 64

        fun parse(text: String): Any? {
            val reader = MiniJson(text)
            reader.space()
            val value = reader.value(0)
            reader.space()
            if (reader.pos != text.length) throw JsonProblem("unexpected content after the JSON value")
            return value
        }

        @Suppress("UNCHECKED_CAST")
        fun parseObject(text: String): Map<String, Any?> =
            parse(text) as? Map<String, Any?> ?: throw JsonProblem("expected a JSON object")
    }

    private fun space() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\n' || text[pos] == '\r' || text[pos] == '\t')) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw JsonProblem("nesting too deep")
        if (pos >= text.length) throw JsonProblem("unexpected end")
        return when (val c = text[pos]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            '-', in '0'..'9' -> num()
            else -> throw JsonProblem("unexpected character '$c' at $pos")
        }
    }

    private fun lit(word: String, result: Any?): Any? {
        if (!text.startsWith(word, pos)) throw JsonProblem("bad literal at $pos")
        pos += word.length
        return result
    }

    private fun obj(depth: Int): Map<String, Any?> {
        pos++
        val result = LinkedHashMap<String, Any?>()
        space()
        if (peek() == '}') { pos++; return result }
        while (true) {
            space()
            if (peek() != '"') throw JsonProblem("expected a key at $pos")
            val key = str()
            space()
            if (peek() != ':') throw JsonProblem("expected ':' at $pos")
            pos++
            space()
            val v = value(depth + 1)
            if (result.containsKey(key)) throw JsonProblem("duplicate key \"$key\"")
            result[key] = v
            space()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return result }
                else -> throw JsonProblem("expected ',' or '}' at $pos")
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        pos++
        val result = ArrayList<Any?>()
        space()
        if (peek() == ']') { pos++; return result }
        while (true) {
            space()
            result += value(depth + 1)
            space()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return result }
                else -> throw JsonProblem("expected ',' or ']' at $pos")
            }
        }
    }

    private fun str(): String {
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) throw JsonProblem("unterminated string")
            val c = text[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= text.length) throw JsonProblem("unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw JsonProblem("short \\u escape")
                            val n = text.substring(pos, pos + 4).toIntOrNull(16) ?: throw JsonProblem("bad \\u escape")
                            sb.append(n.toChar())
                            pos += 4
                        }
                        else -> throw JsonProblem("bad escape '\\$e'")
                    }
                }
                c < ' ' -> throw JsonProblem("raw control character in a string")
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Any {
        val start = pos
        if (peek() == '-') pos++
        if (peek() == '0') pos++
        else if (peek() in '1'..'9') { while (peek() in '0'..'9') pos++ }
        else throw JsonProblem("bad number at $start")
        var integral = true
        if (peek() == '.') {
            integral = false; pos++
            if (peek() !in '0'..'9') throw JsonProblem("bad number at $start")
            while (peek() in '0'..'9') pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false; pos++
            if (peek() == '+' || peek() == '-') pos++
            if (peek() !in '0'..'9') throw JsonProblem("bad number at $start")
            while (peek() in '0'..'9') pos++
        }
        val token = text.substring(start, pos)
        if (integral) token.toLongOrNull()?.let { return it }
        return token.toDoubleOrNull() ?: throw JsonProblem("bad number at $start")
    }
}
