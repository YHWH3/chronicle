package com.chronicle.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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

// ————— Palette: warm ivory & deep ink, one brass accent. —————

val Ivory = Color(0xFFFAF9F7)
val IvoryRaised = Color(0xFFFFFFFF)
val Ink = Color(0xFF1A1B1F)
val InkSecondary = Color(0xFF6B655A)
val InkFaint = Color(0xFFB5AFA5)
val Brass = Color(0xFFA87434)
val Hairline = Color(0xFFE8E4DD)

val InkCanvas = Color(0xFF1A1B1F)
val InkRaised = Color(0xFF23252B)
val PaperInk = Color(0xFFF0EDE8)
val PaperInkSecondary = Color(0xFFA39E94)
val PaperInkFaint = Color(0xFF5E5A53)
val BrassLight = Color(0xFFD4A574)
val HairlineDark = Color(0xFF2E3036)

private val LightColors = lightColorScheme(
    primary = Brass,
    onPrimary = IvoryRaised,
    primaryContainer = Ivory,
    onPrimaryContainer = Ink,
    secondary = InkSecondary,
    onSecondary = IvoryRaised,
    background = Ivory,
    onBackground = Ink,
    surface = Ivory,
    onSurface = Ink,
    surfaceVariant = IvoryRaised,
    onSurfaceVariant = InkSecondary,
    outline = Hairline,
    outlineVariant = Hairline,
    surfaceContainerLowest = Ivory,
    surfaceContainerLow = Ivory,
    surfaceContainer = IvoryRaised,
    surfaceContainerHigh = IvoryRaised,
    surfaceContainerHighest = IvoryRaised,
)

private val DarkColors = darkColorScheme(
    primary = BrassLight,
    onPrimary = InkCanvas,
    primaryContainer = InkRaised,
    onPrimaryContainer = PaperInk,
    secondary = PaperInkSecondary,
    onSecondary = InkCanvas,
    background = InkCanvas,
    onBackground = PaperInk,
    surface = InkCanvas,
    onSurface = PaperInk,
    surfaceVariant = InkRaised,
    onSurfaceVariant = PaperInkSecondary,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    surfaceContainerLowest = InkCanvas,
    surfaceContainerLow = InkCanvas,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkRaised,
    surfaceContainerHighest = InkRaised,
)

// ————— Custom tokens the M3 scheme doesn't carry. —————

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

    // Ink ⇄ Paper sweeps instead of hard-cutting: animate each token in place so
    // the tree (and NavHost) stays composed — a structural crossfade would reset nav.
    val targetScheme = if (dark) DarkColors else LightColors
    val targetExtras = if (dark) {
        Extras(
            hairline = HairlineDark,
            raised = InkRaised,
            faint = PaperInkFaint,
            accentSoft = BrassLight.copy(alpha = 0.14f),
        )
    } else {
        Extras(
            hairline = Hairline,
            raised = IvoryRaised,
            faint = InkFaint,
            accentSoft = Brass.copy(alpha = 0.10f),
        )
    }
    val scheme = targetScheme.copy(
        primary = animColor(targetScheme.primary),
        onPrimary = animColor(targetScheme.onPrimary),
        primaryContainer = animColor(targetScheme.primaryContainer),
        onPrimaryContainer = animColor(targetScheme.onPrimaryContainer),
        inversePrimary = animColor(targetScheme.inversePrimary),
        secondary = animColor(targetScheme.secondary),
        onSecondary = animColor(targetScheme.onSecondary),
        secondaryContainer = animColor(targetScheme.secondaryContainer),
        onSecondaryContainer = animColor(targetScheme.onSecondaryContainer),
        tertiary = animColor(targetScheme.tertiary),
        onTertiary = animColor(targetScheme.onTertiary),
        tertiaryContainer = animColor(targetScheme.tertiaryContainer),
        onTertiaryContainer = animColor(targetScheme.onTertiaryContainer),
        background = animColor(targetScheme.background),
        onBackground = animColor(targetScheme.onBackground),
        surface = animColor(targetScheme.surface),
        onSurface = animColor(targetScheme.onSurface),
        surfaceVariant = animColor(targetScheme.surfaceVariant),
        onSurfaceVariant = animColor(targetScheme.onSurfaceVariant),
        surfaceTint = animColor(targetScheme.surfaceTint),
        inverseSurface = animColor(targetScheme.inverseSurface),
        inverseOnSurface = animColor(targetScheme.inverseOnSurface),
        error = animColor(targetScheme.error),
        onError = animColor(targetScheme.onError),
        errorContainer = animColor(targetScheme.errorContainer),
        onErrorContainer = animColor(targetScheme.onErrorContainer),
        outline = animColor(targetScheme.outline),
        outlineVariant = animColor(targetScheme.outlineVariant),
        scrim = animColor(targetScheme.scrim),
        surfaceBright = animColor(targetScheme.surfaceBright),
        surfaceDim = animColor(targetScheme.surfaceDim),
        surfaceContainer = animColor(targetScheme.surfaceContainer),
        surfaceContainerHigh = animColor(targetScheme.surfaceContainerHigh),
        surfaceContainerHighest = animColor(targetScheme.surfaceContainerHighest),
        surfaceContainerLow = animColor(targetScheme.surfaceContainerLow),
        surfaceContainerLowest = animColor(targetScheme.surfaceContainerLowest),
    )
    val extras = Extras(
        hairline = animColor(targetExtras.hairline),
        raised = animColor(targetExtras.raised),
        faint = animColor(targetExtras.faint),
        accentSoft = animColor(targetExtras.accentSoft),
    )
    CompositionLocalProvider(LocalChronicleExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ChronicleTypography,
            shapes = ChronicleShapes,
            content = content,
        )
    }
}

private val ThemeColorSpec: FiniteAnimationSpec<Color> =
    tween(ChronicleMotion.THEME, easing = ChronicleMotion.StandardEasing)

@Composable
private fun animColor(target: Color) =
    animateColorAsState(targetValue = target, animationSpec = ThemeColorSpec, label = "themeColor").value
