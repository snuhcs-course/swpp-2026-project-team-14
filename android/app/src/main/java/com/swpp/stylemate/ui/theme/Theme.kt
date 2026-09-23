package com.swpp.stylemate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette taken from the team's screen mockups.
val Terracotta = Color(0xFFC4552B)
val Cream = Color(0xFFF8F6F2)
val Sand = Color(0xFFF1ECE6)
val Ink = Color(0xFF1F1B18)
val Muted = Color(0xFF8A8580)
val ConfidenceHigh = Color(0xFF3F8F5A)
val ConfidenceMedium = Color(0xFFD9A13B)
val ConfidenceLow = Color(0xFFC4552B)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFF6DDD2),
    onSecondaryContainer = Color(0xFF8A3312),
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Muted,
    surfaceContainer = Color.White,
    outlineVariant = Color(0xFFE4DED7),
)

@Composable
fun StyleMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
