package com.ojas.permsense

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ojas.permsense.ui.theme.Blue10
import com.ojas.permsense.ui.theme.Blue20
import com.ojas.permsense.ui.theme.Blue30
import com.ojas.permsense.ui.theme.Blue40
import com.ojas.permsense.ui.theme.Blue80
import com.ojas.permsense.ui.theme.Blue90
import com.ojas.permsense.ui.theme.DarkBackground
import com.ojas.permsense.ui.theme.DarkOnSurface
import com.ojas.permsense.ui.theme.DarkSeverity
import com.ojas.permsense.ui.theme.LightBackground
import com.ojas.permsense.ui.theme.LightOnSurface
import com.ojas.permsense.ui.theme.LightSeverity
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** WCAG 2.x: normal text needs a contrast ratio of at least 4.5 to 1. */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertReadable(name: String, background: Color, text: Color) {
        val ratio = contrast(background, text)
        assertTrue("$name has contrast %.2f, needs 4.5".format(ratio), ratio >= 4.5)
    }

    @Test
    fun everySeverityLevelIsReadableInLightAndDark() {
        for ((theme, colors) in listOf("light" to LightSeverity, "dark" to DarkSeverity)) {
            for (severity in 1..3) {
                assertReadable("$theme severity $severity", colors.container(severity), colors.content(severity))
            }
        }
    }

    @Test
    fun theBodyTextIsReadableOnTheBackground() {
        assertReadable("light body", LightBackground, LightOnSurface)
        assertReadable("dark body", DarkBackground, DarkOnSurface)
    }

    @Test
    fun buttonsAreReadable() {
        assertReadable("light primary button", Blue40, Color.White)
        assertReadable("dark primary button", Blue80, Blue20)
        assertReadable("light primary container", Blue90, Blue10)
        assertReadable("dark primary container", Blue30, Blue90)
    }

    @Test
    fun theThreeLevelsDoNotShareAColour() {
        for (colors in listOf(LightSeverity, DarkSeverity)) {
            val containers = (1..3).map { colors.container(it) }
            assertTrue(containers.toSet().size == 3)
        }
    }
}
