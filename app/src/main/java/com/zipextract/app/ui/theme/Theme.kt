package com.zipextract.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF0F766E)
private val TealDark = Color(0xFF115E59)
private val Mint = Color(0xFF99F6E4)
private val Sand = Color(0xFFF8FAFC)
private val Ink = Color(0xFF0F172A)
private val Slate = Color(0xFF334155)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = TealDark,
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Slate,
    error = Color(0xFFB91C1C),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = TealDark,
    primaryContainer = TealDark,
    onPrimaryContainer = Mint,
    secondary = Color(0xFF67E8F9),
    onSecondary = Color(0xFF083344),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF3B2D5C),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

private val AppTypography = androidx.compose.material3.Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun FileNestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

@Deprecated("Use FileNestTheme", ReplaceWith("FileNestTheme(darkTheme, content)"))
@Composable
fun ZipExtractTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    FileNestTheme(darkTheme = darkTheme, content = content)
}
