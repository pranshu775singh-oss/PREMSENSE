package com.ojas.permsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English and Hindi must stay in step: a string added to one and forgotten in the other would show
 * up in the wrong language, or as a crash on a format argument, only on the phone of whoever uses
 * the other language. Checked here, where it costs nothing.
 */
class StringResourcesTest {

    private fun load(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))   // also proves well-formed
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val en = load("src/main/res/values/strings.xml")
    private val hi = load("src/main/res/values-hi/strings.xml")

    private val placeholder = Regex("""%\d\$[sd]""")
    private fun devanagari(s: String) = s.count { it in 'ऀ'..'ॿ' }

    /** Names, brands and the language's own name, which read the same in every language. */
    private val sameInBoth = setOf("app_name", "lang_en", "lang_hi", "onb_shizuku_title")

    @Test
    fun bothLanguagesHaveExactlyTheSameStrings() {
        assertEquals("only in English", emptySet<String>(), en.keys - hi.keys)
        assertEquals("only in Hindi", emptySet<String>(), hi.keys - en.keys)
    }

    @Test
    fun everyFormatArgumentIsCarriedOverAndNoneIsInvented() {
        for (key in en.keys) {
            assertEquals(
                "$key: placeholders differ",
                placeholder.findAll(en.getValue(key)).map { it.value }.sorted().toList(),
                placeholder.findAll(hi.getValue(key)).map { it.value }.sorted().toList(),
            )
        }
    }

    @Test
    fun noStringIsEmpty() {
        for ((k, v) in en) assertTrue("en $k", v.isNotBlank())
        for ((k, v) in hi) assertTrue("hi $k", v.isNotBlank())
    }

    @Test
    fun hindiIsWrittenInHindiAndEnglishIsNot() {
        for ((key, text) in hi) {
            if (key in sameInBoth) continue
            assertTrue("hi $key has no Devanagari: $text", devanagari(text) > 0)
        }
        for ((key, text) in en) {
            if (key == "lang_hi") continue          // the language's own name, in its own script
            assertEquals("en $key contains Devanagari", 0, devanagari(text))
        }
    }

    @Test
    fun translatedTextIsNotJustTheEnglishCopied() {
        val copied = en.filter { (k, v) -> k !in sameInBoth && hi[k] == v }.keys
        assertEquals("untranslated", emptySet<String>(), copied)
    }

    @Test
    fun nothingTellsAnOnlookerWhatPermSenseIsLookingFor() {
        // Strings that may show on screen next to a notification must stay neutral.
        val notificationStrings = listOf("channel_alerts", "channel_alerts_description", "channel_digest", "channel_digest_description")
        val banned = listOf("spy", "stalk", "surveil", "abuse", "malware", "hack", "threat", "जासूस", "निगरानी", "ख़तरा")

        for (key in notificationStrings) {
            for (text in listOf(en.getValue(key), hi.getValue(key))) {
                for (word in banned) assertFalse("$key: \"$word\" in \"$text\"", text.lowercase().contains(word))
            }
        }
    }

    @Test
    fun theLimitsAreStatedPlainly() {
        assertTrue(en.getValue("export_limits").contains("does not prove"))
        assertTrue(en.getValue("export_limits").contains("legal proceeding"))
        assertTrue(en.getValue("about_integrity").contains("does not prove"))
        assertTrue(en.getValue("about_legal").contains("no claim"))
        assertTrue(hi.getValue("export_limits").contains("साबित नहीं"))
    }
}
