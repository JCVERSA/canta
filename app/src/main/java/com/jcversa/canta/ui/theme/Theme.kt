package com.jcversa.canta.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * AMOLED dark + light, Material3.
 *
 * The dark scheme is *pure* black (`0xFF000000`) for both background and
 * surface — on an OLED panel that is the difference between "dark grey" and
 * actually unlit pixels. Cards and sheets use [surfaceVariant] rather than a
 * raised surface tone, because Material3's tonal elevation would lighten black
 * back up and defeat the point.
 */
private val AmoledDarkScheme = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    onPrimary = Color(0xFF0B0B0F),
    primaryContainer = Color(0xFF241B4A),
    onPrimaryContainer = Color(0xFFE7E1FF),
    secondary = Color(0xFF57D0C8),
    onSecondary = Color(0xFF00201E),
    tertiary = Color(0xFFFFB4A9),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF121216),
    onSurfaceVariant = Color(0xFFB9B9C6),
    surfaceContainer = Color(0xFF0A0A0D),
    surfaceContainerHigh = Color(0xFF14141A),
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF1E1E24),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF3A0A0A),
    onErrorContainer = Color(0xFFFFD9D6)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B3FE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1E1140),
    secondary = Color(0xFF1F7A74),
    background = Color(0xFFFBFBFF),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF0EFF6),
    onSurfaceVariant = Color(0xFF4A4A57),
    surfaceContainer = Color(0xFFF4F3FA),
    surfaceContainerHigh = Color(0xFFECEBF3),
    outline = Color(0xFFB9B8C6),
    error = Color(0xFFB3261E)
)

private val CantaTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun CantaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AmoledDarkScheme else LightScheme,
        typography = CantaTypography,
        content = content
    )
}
