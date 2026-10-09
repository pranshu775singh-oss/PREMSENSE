package com.ojas.permsense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary = BlueGrey40,
    onSecondary = Color.White,
    secondaryContainer = BlueGrey90,
    onSecondaryContainer = Blue10,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = BlueGrey40,
    error = ErrorLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    secondary = BlueGrey80,
    onSecondary = Blue20,
    secondaryContainer = Blue30,
    onSecondaryContainer = BlueGrey90,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = BlueGrey80,
    error = ErrorDark,
    onError = Color(0xFF601410),
)

/** Background and text colours for the three levels of a finding. Contrast is checked in the unit tests. */
data class SeverityColors(
    val criticalContainer: Color,
    val onCritical: Color,
    val attentionContainer: Color,
    val onAttention: Color,
    val infoContainer: Color,
    val onInfo: Color,
) {
    fun container(severity: Int): Color =
        when {
            severity >= 3 -> criticalContainer
            severity == 2 -> attentionContainer
            else -> infoContainer
        }

    fun content(severity: Int): Color =
        when {
            severity >= 3 -> onCritical
            severity == 2 -> onAttention
            else -> onInfo
        }
}

val LightSeverity = SeverityColors(
    criticalContainer = Color(0xFFFDE7E5), onCritical = Color(0xFF8C1D18),
    attentionContainer = Color(0xFFFFF1D6), onAttention = Color(0xFF6B4A00),
    infoContainer = Color(0xFFE3F0FA), onInfo = Color(0xFF0B4A6F),
)

val DarkSeverity = SeverityColors(
    criticalContainer = Color(0xFF4A1512), onCritical = Color(0xFFFFDAD6),
    attentionContainer = Color(0xFF4A3500), onAttention = Color(0xFFFFE0A0),
    infoContainer = Color(0xFF0F3A55), onInfo = Color(0xFFCDE5F6),
)

val LocalSeverityColors = staticCompositionLocalOf { LightSeverity }

@Composable
fun PermSenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Fixed colours, not the phone's wallpaper colours: the severity colours have to keep their
    // meaning and their contrast whatever the wallpaper is.
    CompositionLocalProvider(LocalSeverityColors provides (if (darkTheme) DarkSeverity else LightSeverity)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content,
        )
    }
}
