// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared app palette; garment colours, photos and AR measurement overlays are data, not branding. */
object StyleMateColors {
    val Canvas = Color.White
    val Ink = Color.Black
    val Muted = Color(0xFF5D5D5D)
    val Border = Color(0xFFDDDDDD)
    val Subtle = Color(0xFFF5F5F5)
    val Pressed = Color(0xFFF4F4F4)
    val Focus = Color(0xFF375FFF)
    val Error = Color(0xFFB3261E)
    val ErrorBackground = Color(0xFFFCEEEE)
    val Disabled = Color(0xFFECECEC)
    val DisabledInk = Color(0xFF666666)
}

val ConfidenceHigh = Color(0xFF24603B)
val ConfidenceMedium = StyleMateColors.Muted
val ConfidenceLow = StyleMateColors.Error

private val LightColors = lightColorScheme(
    primary = StyleMateColors.Ink,
    onPrimary = StyleMateColors.Canvas,
    primaryContainer = StyleMateColors.Ink,
    onPrimaryContainer = StyleMateColors.Canvas,
    inversePrimary = StyleMateColors.Canvas,
    secondary = StyleMateColors.Ink,
    onSecondary = StyleMateColors.Canvas,
    secondaryContainer = StyleMateColors.Pressed,
    onSecondaryContainer = StyleMateColors.Ink,
    tertiary = StyleMateColors.Ink,
    onTertiary = StyleMateColors.Canvas,
    tertiaryContainer = StyleMateColors.Subtle,
    onTertiaryContainer = StyleMateColors.Ink,
    background = StyleMateColors.Canvas,
    onBackground = StyleMateColors.Ink,
    surface = StyleMateColors.Canvas,
    onSurface = StyleMateColors.Ink,
    surfaceVariant = StyleMateColors.Subtle,
    onSurfaceVariant = StyleMateColors.Muted,
    surfaceContainer = StyleMateColors.Canvas,
    surfaceContainerLowest = StyleMateColors.Canvas,
    surfaceContainerLow = StyleMateColors.Canvas,
    surfaceContainerHigh = StyleMateColors.Canvas,
    surfaceContainerHighest = StyleMateColors.Subtle,
    surfaceBright = StyleMateColors.Canvas,
    surfaceDim = StyleMateColors.Pressed,
    surfaceTint = StyleMateColors.Ink,
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = StyleMateColors.Canvas,
    outline = StyleMateColors.DisabledInk,
    outlineVariant = StyleMateColors.Border,
    error = StyleMateColors.Error,
    onError = StyleMateColors.Canvas,
    errorContainer = StyleMateColors.ErrorBackground,
    onErrorContainer = StyleMateColors.Error,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

/** Keep dark system-bar icons visible on our white canvas even when the device uses dark mode. */
fun ComponentActivity.enableStyleMateEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(AndroidColor.WHITE, AndroidColor.WHITE),
    )
}

@Composable
fun StyleMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, shapes = AppShapes, content = content)
}
