package com.ojas.permsense.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class JsonTest {

    private fun rejects(text: String) {
        try {
            Json.parse(text)
            fail("accepted: $text")
        } catch (_: JsonException) {
        }
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun readsEveryValueType() {
        val v = Json.parseObject("""{"s":"a","i":-12,"l":9007199254740993,"d":1.5,"e":1e3,"t":true,"f":false,"n":null,"a":[1,"x",[]],"o":{}}""")

        assertEquals("a", v["s"])
        assertEquals(-12L, v["i"])
        assertEquals(9007199254740993L, v["l"])
        assertEquals(1.5, v["d"])
        assertEquals(1000.0, v["e"])
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertTrue(v.containsKey("n"))
        assertNull(v["n"])
        assertEquals(listOf(1L, "x", emptyList<Any?>()), v["a"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
    }

    @Test
    fun largeIntegersKeepFullPrecision() {
        assertEquals(Long.MAX_VALUE, Json.parse(Long.MAX_VALUE.toString()))
        assertEquals(Long.MIN_VALUE, Json.parse(Long.MIN_VALUE.toString()))
        // Beyond a Long it falls back to a double rather than failing.
        assertTrue(Json.parse("123456789012345678901234567890") is Double)
    }

    @Test
    fun readsEscapesAndUnicode() {
        assertEquals("a\"b\\c/d\b\u000C\n\r\t", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""))
        assertEquals("A\u00e9\u4e2d", Json.parse("\"\\u0041\\u00e9\\u4e2d\""))
        assertEquals("\ud83d\ude00", Json.parse("\"\\ud83d\\ude00\""))
        assertEquals("\u00e9\u4e2d\ud83d\ude00", Json.parse("\"\u00e9\u4e2d\ud83d\ude00\""))
    }

    @Test
    fun toleratesWhitespaceEverywhereItIsAllowed() {
        assertEquals(mapOf("a" to listOf(1L, 2L)), Json.parse(" \n\t{ \"a\" : [ 1 , 2 ] }\r\n "))
    }

    @Test
    fun rejectsMalformedDocuments() {
        listOf(
            "", "   ", "{", "}", "[", "]", "{\"a\":}", "{\"a\" 1}", "{a:1}", "{'a':1}", "[1,]", "{\"a\":1,}",
            "[1 2]", "01", "-", "+1", "1.", ".5", "1e", "1e+", "--1", "\"abc", "\"a\\x\"", "\"\\u12\"", "\"\\u12G4\"",
            "tru", "nul", "True", "NaN", "Infinity", "[1] x", "{} {}", "\"a\nb\"", "\"tab\there\"",
        ).forEach { rejects(it) }
    }

    @Test
    fun rejectsDuplicateKeysBecauseTwoReadersCouldPickDifferentOnes() {
        rejects("""{"a":1,"a":2}""")
        rejects("""{"x":{"a":1,"a":1}}""")
    }

    @Test
    fun rejectsExcessiveNesting() {
        rejects("[".repeat(200) + "]".repeat(200))
        Json.parse("[".repeat(60) + "]".repeat(60))
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun outputIsCanonicalSortedKeysAndNoSpaces() {
        val text = Json.write(mapOf("b" to 1, "a" to mapOf("z" to true, "y" to null), "c" to listOf("x", 2.5)))

        assertEquals("""{"a":{"y":null,"z":true},"b":1,"c":["x",2.5]}""", text)
    }

    @Test
    fun theSameDataAlwaysProducesTheSameBytes() {
        val a = linkedMapOf<String, Any?>("one" to 1, "two" to 2, "three" to 3)
        val b = linkedMapOf<String, Any?>("three" to 3, "one" to 1, "two" to 2)

        assertEquals(Json.write(a), Json.write(b))
    }

    @Test
    fun escapesWhatMustBeEscapedAndNothingElse() {
        val text = Json.write("q\"b\\n\n\r\t\b\u000C\u0001\u007f\u00e9\u2028\u2029")

        assertEquals("\"q\\\"b\\\\n\\n\\r\\t\\b\\f\\u0001\u007f\u00e9\\u2028\\u2029\"", text)
    }

    @Test
    fun prettyOutputReadsBackTheSame() {
        val value = mapOf("a" to listOf(1, mapOf("b" to "c")), "d" to emptyList<Any>(), "e" to emptyMap<String, Any>())

        assertEquals(Json.parse(Json.write(value)), Json.parse(Json.writePretty(value)))
        assertTrue(Json.writePretty(value).contains("\n  "))
    }

    @Test
    fun refusesWhatJsonCannotRepresent() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Any(), mapOf(1 to 2))) {
            try {
                Json.write(bad)
                fail("wrote $bad")
            } catch (_: JsonException) {
            }
        }
    }

    @Test
    fun arbitraryDataRoundTrips() {
        val rng = Random(11)

        fun randomString(): String = buildString {
            repeat(rng.nextInt(12)) {
                append(listOf('a', 'Z', '"', '\\', '\n', '\u0001', '\u00e9', '\u4e2d', ' ', '/').random(rng))
            }
        }

        fun randomValue(depth: Int): Any? = when (rng.nextInt(if (depth > 3) 5 else 7)) {
            0 -> null
            1 -> rng.nextBoolean()
            2 -> rng.nextLong()
            3 -> randomString()
            4 -> rng.nextInt(-1000, 1000).toLong()
            5 -> List(rng.nextInt(5)) { randomValue(depth + 1) }
            else -> (0 until rng.nextInt(5)).associate { "k$it${randomString()}" to randomValue(depth + 1) }
        }

        repeat(500) {
            val value = randomValue(0)
            assertEquals(value, Json.parse(Json.write(value)))
            assertEquals(value, Json.parse(Json.writePretty(value)))
        }
    }

    // ------------------------------------------------------------------ typed access

    @Test
    fun typedAccessorsReadTheRightTypesAndComplainAboutTheWrongOnes() {
        val o = Json.parseObject("""{"s":"x","n":5,"b":true,"o":{"k":1},"a":[1],"z":null,"d":2.5,"whole":3.0}""")

        assertEquals("x", o.str("s"))
        assertEquals(5L, o.long("n"))
        assertEquals(5, o.int("n"))
        assertEquals(true, o.bool("b"))
        assertEquals(1L, o.obj("o").long("k"))
        assertEquals(listOf<Any?>(1L), o.arr("a"))
        assertNull(o.strOrNull("z"))
        assertNull(o.longOrNull("z"))
        assertEquals(2.5, o.double("d"), 0.0)
        assertEquals(3L, o.long("whole"))

        for (call in listOf<() -> Any?>(
            { o.str("n") }, { o.long("s") }, { o.long("d") }, { o.bool("s") },
            { o.obj("a") }, { o.arr("o") }, { o.str("missing") }, { o.int("n").toString(); o.long("missing") },
        )) {
            try {
                call()
                fail()
            } catch (_: JsonException) {
            }
        }
    }

    @Test
    fun anOutOfRangeIntIsRefused() {
        val o = Json.parseObject("""{"big":3000000000}""")

        try {
            o.int("big")
            fail()
        } catch (_: JsonException) {
        }
        assertFalse(o.long("big") < 0)
    }
}
