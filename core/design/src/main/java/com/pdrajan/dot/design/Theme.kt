package com.pdrajan.dot.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Nothing red. Used sparingly: selection, active states, the one thing that matters on screen. */
val NothingRed = Color(0xFFD71921)

/** Colours the Material scheme has no slot for. */
@Immutable
data class DotExtraColors(
    val accent: Color,
    val dots: Color,
    val scrim: Color,
    val thumbnailPlaceholder: Color,
    val isDark: Boolean,
)

val LocalDotColors = staticCompositionLocalOf {
    DotExtraColors(NothingRed, Color(0x22FFFFFF), Color(0x99000000), Color(0xFF1A1A1A), isDark = true)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE5262E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0B0D),
    onPrimaryContainer = Color(0xFFFFDAD7),
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFE5262E),
    onTertiary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    surfaceBright = Color(0xFF2C2C2C),
    surfaceDim = Color.Black,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
    error = Color(0xFFFF5449),
    onError = Color.Black,
    scrim = Color.Black,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD7),
    onPrimaryContainer = Color(0xFF410004),
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color.Black,
    tertiary = NothingRed,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE0E0E0),
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    outline = Color(0xFFC6C6C6),
    outlineVariant = Color(0xFFE4E4E4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    scrim = Color.Black,
)

private val DotShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DotTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val extra = if (dark) {
        DotExtraColors(Color(0xFFE5262E), Color(0x1FFFFFFF), Color(0xB3000000), Color(0xFF161616), isDark = true)
    } else {
        DotExtraColors(NothingRed, Color(0x1A000000), Color(0x80000000), Color(0xFFEDEDED), isDark = false)
    }
    CompositionLocalProvider(LocalDotColors provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = DotTypography,
            shapes = DotShapes,
            content = content,
        )
    }
}

object DotTheme {
    val extra: DotExtraColors
        @Composable get() = LocalDotColors.current
}
