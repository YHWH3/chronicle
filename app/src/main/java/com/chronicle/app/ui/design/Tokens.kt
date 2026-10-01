package com.chronicle.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ————— Palette: dusk-mist atmospheres, frosted glass, lavender accent. —————

@Immutable
data class ChroniclePalette(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val peach: Color,
    val warning: Color,
    val surfaceSubtle: Color,
    val surfaceFrosted: Color,
    val surfaceFrostedStroke: Color,
    val surfaceSelected: Color,
    val surfaceSelectedStroke: Color,
    val surfaceElevated: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val PaperPalette = ChroniclePalette(
    textPrimary = Color(0xFF1F1D22),
    textSecondary = Color(0xFF5E5A63),
    textTertiary = Color(0xFF867F8B),
    accent = Color(0xFF7F6CC9),
    accentSoft = Color(0xFFD9CFF6),
    peach = Color(0xFFF3C7AE),
    warning = Color(0xFFB0604F),
    surfaceSubtle = Color.White.copy(alpha = 0.30f),
    surfaceFrosted = Color.White.copy(alpha = 0.55f),
    surfaceFrostedStroke = Color.White.copy(alpha = 0.75f),
    surfaceSelected = Color(0xFFDCD3F7).copy(alpha = 0.80f),
    surfaceSelectedStroke = Color(0xFFB9ABEA).copy(alpha = 0.85f),
    surfaceElevated = Color.White.copy(alpha = 0.78f),
    scrim = Color(0xFFF4EFEA).copy(alpha = 0.85f),
    isDark = false,
)

val InkPalette = ChroniclePalette(
    textPrimary = Color(0xFFF2EEF0),
    textSecondary = Color(0xFFB8B2BD),
    textTertiary = Color(0xFF9A95A4),
    accent = Color(0xFFC9BCF5),
    accentSoft = Color(0xFF3E3858),
    peach = Color(0xFFE9B89C),
    warning = Color(0xFFE39A8C),
    surfaceSubtle = Color.White.copy(alpha = 0.05f),
    surfaceFrosted = Color.White.copy(alpha = 0.09f),
    surfaceFrostedStroke = Color.White.copy(alpha = 0.14f),
    surfaceSelected = Color(0xFF9E8FE0).copy(alpha = 0.30f),
    surfaceSelectedStroke = Color(0xFFB7A9F0).copy(alpha = 0.45f),
    surfaceElevated = Color(0xFF2B2930).copy(alpha = 0.82f),
    scrim = Color(0xFF1C1B20).copy(alpha = 0.85f),
    isDark = true,
)

// The one chromatic moment: lavender → peach, identical in both themes.
val GradientStart = Color(0xFFCDBEF5)
val GradientEnd = Color(0xFFF5CAB4)
val OnGradient = Color(0xFF1F1D22)

fun primaryGradient(): Brush = Brush.horizontalGradient(listOf(GradientStart, GradientEnd))
fun verticalPrimaryGradient(): Brush = Brush.verticalGradient(listOf(GradientStart, GradientEnd))

val LocalPalette = staticCompositionLocalOf { PaperPalette }

/** Design-token entry point: `Chron.palette`, `Chron.type`. */
object Chron {
    val palette: ChroniclePalette @Composable get() = LocalPalette.current
    val type: ChronicleType @Composable get() = LocalChronicleType.current
}

object Spacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x10 = 40.dp
    val x14 = 56.dp
    val screenMargin = 24.dp
}

object Radius {
    val xs = 10.dp
    val sm = 16.dp
    val md = 22.dp
    val lg = 30.dp
    const val pill = 50 // percent, for RoundedCornerShape(percent)
}
