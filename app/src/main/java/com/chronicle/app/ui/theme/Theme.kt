package com.chronicle.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.R
import com.chronicle.app.ui.design.ChroniclePalette
import com.chronicle.app.ui.design.InkPalette
import com.chronicle.app.ui.design.LocalPalette
import com.chronicle.app.ui.design.OnGradient
import com.chronicle.app.ui.design.PaperPalette

// ————— Legacy tokens kept for the untouched screens. —————

val Ivory = Color(0xFFFAF9F7)
val IvoryRaised = Color(0xFFFFFFFF)
val Ink = Color(0xFF1A1B1F)
val InkSecondary = Color(0xFF6B655A)
val InkFaint = Color(0xFFB5AFA5)
val Brass = Color(0xFFA87434)
val Hairline = Color(0xFFE8E4DD)

/** Custom tokens the M3 scheme doesn't carry — now derived from the palette. */
@Immutable
data class Extras(
    val hairline: Color,
    val raised: Color,
    val faint: Color,
    val accentSoft: Color,
)

val LocalChronicleExtras = staticCompositionLocalOf {
    Extras(Hairline, IvoryRaised, InkFaint, Brass.copy(alpha = 0.12f))
}

val ChronicleExtras: Extras @Composable get() = LocalChronicleExtras.current

// ————— Typography: Fraunces display · Inter body · JetBrains Mono metadata. —————

val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_medium, FontWeight.Medium),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

val ChronicleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-0.8).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.Medium,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp,
    ),
)

/** Metadata voice: mono, tracked out, uppercase — the app's quiet machinery. */
val MetaStyle = TextStyle(
    fontFamily = Mono, fontWeight = FontWeight.Medium,
    fontSize = 10.5.sp, letterSpacing = 1.6.sp,
)

val MetaStyleLarge = TextStyle(
    fontFamily = Mono, fontWeight = FontWeight.Medium,
    fontSize = 12.sp, letterSpacing = 1.8.sp,
)

val SerifItalic = TextStyle(
    fontFamily = Fraunces, fontWeight = FontWeight.Normal,
    fontStyle = FontStyle.Italic, fontSize = 17.sp, lineHeight = 26.sp,
)

val ChronicleShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
)

private val ThemeColorSpec: FiniteAnimationSpec<Color> =
    tween(ChronicleMotion.THEME, easing = ChronicleMotion.StandardEasing)

@Composable
private fun animColor(target: Color) =
    animateColorAsState(targetValue = target, animationSpec = ThemeColorSpec, label = "themeColor").value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChronicleTheme(mode: com.chronicle.app.data.ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        com.chronicle.app.data.ThemeMode.PAPER -> false
        com.chronicle.app.data.ThemeMode.INK -> true
        com.chronicle.app.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // Keep system-bar glyphs legible whichever way the theme leans.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    // Ink ⇄ Paper sweeps instead of hard-cutting: animate each palette token in
    // place so the tree (and NavHost) stays composed — a structural crossfade
    // would reset navigation.
    val target = if (dark) InkPalette else PaperPalette
    val palette = ChroniclePalette(
        textPrimary = animColor(target.textPrimary),
        textSecondary = animColor(target.textSecondary),
        textTertiary = animColor(target.textTertiary),
        accent = animColor(target.accent),
        accentSoft = animColor(target.accentSoft),
        peach = animColor(target.peach),
        warning = animColor(target.warning),
        surfaceSubtle = animColor(target.surfaceSubtle),
        surfaceFrosted = animColor(target.surfaceFrosted),
        surfaceFrostedStroke = animColor(target.surfaceFrostedStroke),
        surfaceSelected = animColor(target.surfaceSelected),
        surfaceSelectedStroke = animColor(target.surfaceSelectedStroke),
        surfaceElevated = animColor(target.surfaceElevated),
        scrim = animColor(target.scrim),
        isDark = dark,
    )

    // MaterialTheme stays wired underneath — text selection, cursors and the
    // untouched screens read the colorScheme, which the palette now drives.
    // background/surface stay transparent so the atmosphere shows through.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = palette.accent,
        onPrimary = OnGradient,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.textPrimary,
        secondary = palette.textSecondary,
        onSecondary = palette.textPrimary,
        background = Color.Transparent,
        onBackground = palette.textPrimary,
        surface = Color.Transparent,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceFrosted,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.surfaceFrostedStroke,
        outlineVariant = palette.surfaceFrostedStroke,
        error = palette.warning,
        onError = OnGradient,
        scrim = palette.scrim,
        surfaceContainerLowest = Color.Transparent,
        surfaceContainerLow = palette.surfaceSubtle,
        surfaceContainer = palette.surfaceFrosted,
        surfaceContainerHigh = palette.surfaceElevated,
        surfaceContainerHighest = palette.surfaceElevated,
    )

    val extras = Extras(
        hairline = palette.textPrimary.copy(alpha = 0.12f),
        raised = palette.surfaceElevated,
        faint = palette.textTertiary,
        accentSoft = palette.accentSoft,
    )

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalChronicleExtras provides extras,
        LocalRippleConfiguration provides RippleConfiguration(
            color = palette.textPrimary,
            rippleAlpha = RippleAlpha(0.08f, 0.08f, 0.08f, 0.08f),
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ChronicleTypography,
            shapes = ChronicleShapes,
            content = content,
        )
    }
}
