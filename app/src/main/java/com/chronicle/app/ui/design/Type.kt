package com.chronicle.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chronicle.app.ui.theme.Fraunces
import com.chronicle.app.ui.theme.Inter
import com.chronicle.app.ui.theme.Mono

/**
 * Type roles for the redesign — Fraunces carries the emotional voice,
 * Inter the interface, JetBrains Mono the numbers.
 */
@Immutable
data class ChronicleType(
    val display: TextStyle,      // greeting, "Kept."
    val title: TextStyle,        // screen titles
    val headline: TextStyle,     // card heroes, processing title
    val section: TextStyle,      // day groups
    val body: TextStyle,
    val bodyLarge: TextStyle,
    val bodySmall: TextStyle,
    val control: TextStyle,      // buttons
    val controlSmall: TextStyle,
    val meta: TextStyle,         // captions, subtitles (sentence case, Inter)
    val numeric: TextStyle,      // timestamps, durations
    val timer: TextStyle,        // the big recording clock
    val serifItalic: TextStyle,  // placeholders, reflections
)

val ChronicleTypeRoles = ChronicleType(
    display = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.Normal,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp,
    ),
    title = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.Normal,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp,
    ),
    headline = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.Normal,
        fontSize = 24.sp, lineHeight = 30.sp,
    ),
    section = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.Medium,
        fontSize = 19.sp, lineHeight = 24.sp,
    ),
    body = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp,
    ),
    control = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    controlSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    meta = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    numeric = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
    timer = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum",
    ),
    serifItalic = TextStyle(
        fontFamily = Fraunces, fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 24.sp,
    ),
)

val LocalChronicleType = staticCompositionLocalOf { ChronicleTypeRoles }
