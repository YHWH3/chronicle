package com.chronicle.app.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.chronicle.app.ui.theme.ChronicleMotion
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The atmosphere: a soft, misty landscape painted behind everything. Scenes
 * morph in place — every color and float tweens — so navigation never resets.
 */

@Immutable
data class Scene(
    val skyTop: Color,
    val skyBottom: Color,
    val glow: Color,
    /** Glow center as fractions of canvas size. */
    val glowCenter: Offset,
    /** Glow radius as a fraction of the max canvas dimension. */
    val glowRadius: Float,
    /** Ridge fills back → front (2–3 entries). */
    val ridgeColors: List<Color>,
    /** Fraction of height where the ridge band sits. */
    val ridgeBaseline: Float,
    val fog: Color,
    /** Swap-in point for real photography later — draws ContentScale.Crop + scrim. */
    @DrawableRes val image: Int? = null,
)

enum class SceneKey {
    TODAY_IDLE, TODAY_RECORDING, TODAY_PROCESSING, TODAY_KEPT,
    JOURNAL, STORY, INTENTIONS, SETTINGS, NUDGE, TECH,
}

fun scene(key: SceneKey, dark: Boolean): Scene = when (key) {
    SceneKey.TODAY_IDLE -> if (dark) Scene(
        skyTop = Color(0xFF25242B), skyBottom = Color(0xFF463C45),
        glow = Color(0xFFD9A488).copy(alpha = 0.35f),
        glowCenter = Offset(0.70f, 0.25f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFF2E2B33), Color(0xFF1D1C22)),
        ridgeBaseline = 0.72f, fog = Color(0xFF463C45),
    ) else Scene(
        skyTop = Color(0xFFE6DFE3), skyBottom = Color(0xFFF2E5DB),
        glow = Color(0xFFF7D3BC),
        glowCenter = Offset(0.78f, 0.22f), glowRadius = 0.55f,
        ridgeColors = listOf(
            Color(0xFFC3B6C0).copy(alpha = 0.55f),
            Color(0xFFB2A5B2).copy(alpha = 0.70f),
        ),
        ridgeBaseline = 0.72f, fog = Color(0xFFF2E5DB),
    )
    SceneKey.TODAY_RECORDING -> if (dark) Scene(
        skyTop = Color(0xFF2A2330), skyBottom = Color(0xFF3D2F3D),
        glow = Color(0xFFE3A98E).copy(alpha = 0.30f),
        glowCenter = Offset(0.50f, 0.35f), glowRadius = 0.70f,
        ridgeColors = listOf(Color(0xFF332C38), Color(0xFF242028)),
        ridgeBaseline = 0.74f, fog = Color(0xFF3D2F3D),
    ) else Scene(
        skyTop = Color(0xFFF4E6E0), skyBottom = Color(0xFFEFDDE6),
        glow = Color(0xFFF9CFB8),
        glowCenter = Offset(0.50f, 0.35f), glowRadius = 0.70f,
        ridgeColors = listOf(
            Color(0xFFD9C6CF).copy(alpha = 0.40f),
            Color(0xFFCDB9C4).copy(alpha = 0.55f),
        ),
        ridgeBaseline = 0.74f, fog = Color(0xFFEFDDE6),
    )
    SceneKey.TODAY_PROCESSING -> if (dark) Scene(
        skyTop = Color(0xFF221F2E), skyBottom = Color(0xFF35293A),
        glow = Color(0xFF9E8FE0).copy(alpha = 0.35f),
        glowCenter = Offset(0.50f, 0.20f), glowRadius = 0.60f,
        ridgeColors = listOf(Color(0xFF2C2734), Color(0xFF1E1B26)),
        ridgeBaseline = 0.72f, fog = Color(0xFF35293A),
    ) else Scene(
        skyTop = Color(0xFFE9E0F2), skyBottom = Color(0xFFF3E1E6),
        glow = Color(0xFFCBBDF3),
        glowCenter = Offset(0.50f, 0.20f), glowRadius = 0.60f,
        ridgeColors = listOf(
            Color(0xFFCFC3DA).copy(alpha = 0.50f),
            Color(0xFFC4B5D2).copy(alpha = 0.60f),
        ),
        ridgeBaseline = 0.72f, fog = Color(0xFFF3E1E6),
    )
    SceneKey.TODAY_KEPT -> if (dark) Scene(
        skyTop = Color(0xFF262129), skyBottom = Color(0xFF3A3036),
        glow = Color(0xFFC99A8A).copy(alpha = 0.30f),
        glowCenter = Offset(0.50f, 0.25f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFF2E282F), Color(0xFF201C23)),
        ridgeBaseline = 0.72f, fog = Color(0xFF3A3036),
    ) else Scene(
        skyTop = Color(0xFFE4DBE2), skyBottom = Color(0xFFEAD9D6),
        glow = Color(0xFFEFC6B4),
        glowCenter = Offset(0.50f, 0.25f), glowRadius = 0.55f,
        ridgeColors = listOf(
            Color(0xFFC7B7BC).copy(alpha = 0.50f),
            Color(0xFFB8A8AF).copy(alpha = 0.65f),
        ),
        ridgeBaseline = 0.72f, fog = Color(0xFFEAD9D6),
    )
    SceneKey.JOURNAL -> if (dark) Scene(
        skyTop = Color(0xFF1E1D22), skyBottom = Color(0xFF28262C),
        glow = Color(0xFFBBA98F).copy(alpha = 0.18f),
        glowCenter = Offset(0.50f, 0.18f), glowRadius = 0.60f,
        ridgeColors = listOf(
            Color(0xFF242228).copy(alpha = 0.60f),
            Color(0xFF1A191F).copy(alpha = 0.80f),
        ),
        ridgeBaseline = 0.78f, fog = Color(0xFF28262C),
    ) else Scene(
        skyTop = Color(0xFFF3EFEA), skyBottom = Color(0xFFECE6E3),
        glow = Color(0xFFF5DECB).copy(alpha = 0.55f),
        glowCenter = Offset(0.50f, 0.18f), glowRadius = 0.60f,
        ridgeColors = listOf(Color(0xFFD9D2CC).copy(alpha = 0.35f)),
        ridgeBaseline = 0.78f, fog = Color(0xFFECE6E3),
    )
    SceneKey.STORY -> if (dark) Scene(
        skyTop = Color(0xFF22202C), skyBottom = Color(0xFF38303F),
        glow = Color(0xFFAE9CD8).copy(alpha = 0.25f),
        glowCenter = Offset(0.60f, 0.22f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFF2B2736), Color(0xFF1E1B27)),
        ridgeBaseline = 0.70f, fog = Color(0xFF38303F),
    ) else Scene(
        skyTop = Color(0xFFE2DCE6), skyBottom = Color(0xFFEDE0DA),
        glow = Color(0xFFEBD0C8).copy(alpha = 0.70f),
        glowCenter = Offset(0.60f, 0.22f), glowRadius = 0.55f,
        ridgeColors = listOf(
            Color(0xFFBFAEC0).copy(alpha = 0.55f),
            Color(0xFFAF9EB2).copy(alpha = 0.70f),
        ),
        ridgeBaseline = 0.70f, fog = Color(0xFFEDE0DA),
    )
    SceneKey.INTENTIONS -> if (dark) Scene(
        skyTop = Color(0xFF232124), skyBottom = Color(0xFF352E2E),
        glow = Color(0xFFDCA98C).copy(alpha = 0.22f),
        glowCenter = Offset(0.50f, 0.24f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFF2C282B), Color(0xFF201D20)),
        ridgeBaseline = 0.74f, fog = Color(0xFF352E2E),
    ) else Scene(
        skyTop = Color(0xFFEFE7E1), skyBottom = Color(0xFFEFE0DA),
        glow = Color(0xFFF4D8C4).copy(alpha = 0.65f),
        glowCenter = Offset(0.50f, 0.24f), glowRadius = 0.55f,
        ridgeColors = listOf(
            Color(0xFFD5C7BE).copy(alpha = 0.40f),
            Color(0xFFC8B8AF).copy(alpha = 0.55f),
        ),
        ridgeBaseline = 0.74f, fog = Color(0xFFEFE0DA),
    )
    SceneKey.SETTINGS -> if (dark) Scene(
        skyTop = Color(0xFF1F1F24), skyBottom = Color(0xFF2A2A31),
        glow = Color(0xFF9FA8BD).copy(alpha = 0.16f),
        glowCenter = Offset(0.50f, 0.20f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFF26262D), Color(0xFF1B1B21)),
        ridgeBaseline = 0.78f, fog = Color(0xFF2A2A31),
    ) else Scene(
        skyTop = Color(0xFFE9E7E6), skyBottom = Color(0xFFE5E1DE),
        glow = Color(0xFFEAE2DA).copy(alpha = 0.70f),
        glowCenter = Offset(0.50f, 0.20f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFFD3CFCD).copy(alpha = 0.35f)),
        ridgeBaseline = 0.78f, fog = Color(0xFFE5E1DE),
    )
    SceneKey.NUDGE -> if (dark) Scene(
        skyTop = Color(0xFF2A2330), skyBottom = Color(0xFF453039),
        glow = Color(0xFFE8A98B).copy(alpha = 0.35f),
        glowCenter = Offset(0.55f, 0.30f), glowRadius = 0.60f,
        ridgeColors = listOf(Color(0xFF352B38), Color(0xFF241F2B), Color(0xFF181620)),
        ridgeBaseline = 0.66f, fog = Color(0xFF453039),
    ) else Scene(
        skyTop = Color(0xFFF0DED2), skyBottom = Color(0xFFEFDCD5),
        glow = Color(0xFFF9CCAF),
        glowCenter = Offset(0.55f, 0.30f), glowRadius = 0.60f,
        ridgeColors = listOf(
            Color(0xFFC9B2B4).copy(alpha = 0.50f),
            Color(0xFFB7A1A8).copy(alpha = 0.65f),
            Color(0xFFA28F99).copy(alpha = 0.80f),
        ),
        ridgeBaseline = 0.66f, fog = Color(0xFFEFDCD5),
    )
    SceneKey.TECH -> if (dark) Scene(
        skyTop = Color(0xFF1C1F26), skyBottom = Color(0xFF2A2A33),
        glow = Color(0xFF8FA3C0).copy(alpha = 0.18f),
        glowCenter = Offset(0.50f, 0.22f), glowRadius = 0.55f,
        ridgeColors = listOf(Color(0xFF252830), Color(0xFF1A1C23)),
        ridgeBaseline = 0.74f, fog = Color(0xFF2A2A33),
    ) else Scene(
        skyTop = Color(0xFFDDE1E8), skyBottom = Color(0xFFE8E4EA),
        glow = Color(0xFFD8DEEA).copy(alpha = 0.80f),
        glowCenter = Offset(0.50f, 0.22f), glowRadius = 0.55f,
        ridgeColors = listOf(
            Color(0xFFBEC3CE).copy(alpha = 0.40f),
            Color(0xFFAFB4C2).copy(alpha = 0.55f),
        ),
        ridgeBaseline = 0.74f, fog = Color(0xFFE8E4EA),
    )
}

/** Holds the scene key screens drive via [SceneEffect]. */
val LocalAtmosphere = staticCompositionLocalOf<MutableState<SceneKey>> {
    error("LocalAtmosphere used outside AtmosphereHost")
}

/** Sets the atmosphere scene while this composable is in the tree. */
@Composable
fun SceneEffect(key: SceneKey) {
    val atmosphere = LocalAtmosphere.current
    LaunchedEffect(key) { atmosphere.value = key }
}

/**
 * Root background host: paints the current scene behind [content] and tweens
 * every scene parameter on change — no crossfade, the landscape itself morphs.
 */
@Composable
fun AtmosphereHost(content: @Composable () -> Unit) {
    val palette = Chron.palette
    val key = remember { mutableStateOf(SceneKey.TODAY_IDLE) }
    val target = scene(key.value, palette.isDark)

    // Animate every parameter in place.
    val spec = tween<Color>(ChronicleMotion.MORPH, easing = ChronicleMotion.StandardEasing)
    val skyTop by animateColorAsState(target.skyTop, spec, label = "skyTop")
    val skyBottom by animateColorAsState(target.skyBottom, spec, label = "skyBottom")
    val glow by animateColorAsState(target.glow, spec, label = "glow")
    val fog by animateColorAsState(target.fog, spec, label = "fog")
    val ridges = (0 until 3).map { i ->
        animateColorAsState(
            targetValue = target.ridgeColors.getOrElse(i) { Color.Transparent },
            animationSpec = spec, label = "ridge$i",
        ).value
    }
    val fSpec = tween<Float>(ChronicleMotion.MORPH, easing = ChronicleMotion.StandardEasing)
    val glowCx by animateFloatAsState(target.glowCenter.x, fSpec, label = "glowCx")
    val glowCy by animateFloatAsState(target.glowCenter.y, fSpec, label = "glowCy")
    val glowR by animateFloatAsState(target.glowRadius, fSpec, label = "glowR")
    val baseline by animateFloatAsState(target.ridgeBaseline, fSpec, label = "baseline")

    // Slow glow drift — the scene breathes even while still.
    val ambient = rememberInfiniteTransition(label = "ambient")
    val drift by ambient.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(ChronicleMotion.AMBIENT_MS, easing = ChronicleMotion.StandardEasing),
            RepeatMode.Restart,
        ),
        label = "glowDrift",
    )

    val imagePainter: Painter? = target.image?.let { painterResource(it) }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (imagePainter != null) {
                drawImageCover(imagePainter)
                drawRect(Brush.verticalGradient(listOf(palette.scrim, palette.scrim)))
            } else {
                drawAtmosphere(
                    skyTop = skyTop, skyBottom = skyBottom,
                    glow = glow,
                    glowCenter = Offset(
                        glowCx + 0.02f * sin(drift),
                        glowCy + 0.012f * sin(drift * 0.7f + 1.3f),
                    ),
                    glowRadius = glowR,
                    ridges = ridges, baseline = baseline,
                    fog = fog, dark = palette.isDark,
                )
            }
        }
        CompositionLocalProvider(LocalAtmosphere provides key) {
            content()
        }
    }
}

private fun DrawScope.drawAtmosphere(
    skyTop: Color, skyBottom: Color,
    glow: Color, glowCenter: Offset, glowRadius: Float,
    ridges: List<Color>, baseline: Float,
    fog: Color, dark: Boolean,
) {
    val w = size.width
    val h = size.height

    // Sky.
    drawRect(Brush.verticalGradient(listOf(skyTop, skyBottom)))

    // Large soft glow.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glow, glow.copy(alpha = 0f)),
            center = Offset(glowCenter.x * w, glowCenter.y * h),
            radius = (glowRadius * max(w, h)).coerceAtLeast(1f),
        ),
        radius = glowRadius * max(w, h),
        center = Offset(glowCenter.x * w, glowCenter.y * h),
    )

    // Ridges: back → front, each a low-frequency sine silhouette softened
    // into mist — low contrast against the sky, feathered top edge, and a
    // gradient that dissolves into fog toward the base.
    val n = ridges.count { it.alpha > 0f }
    ridges.forEachIndexed { i, raw ->
        if (raw.alpha <= 0f) return@forEachIndexed
        // Center the band on `baseline`; front ridges sit lower and move more.
        val base = h * (baseline - (n - 1) * 0.022f + i * 0.045f)
        val a1 = h * (0.026f + 0.009f * i)
        val a2 = h * (0.012f + 0.005f * i)
        val k1 = 1.2f + 0.55f * i
        val k2 = 2.6f + 0.9f * i
        val p1 = i * 1.7f + 0.4f
        val p2 = i * 2.9f + 1.1f
        val path = Path()
        path.moveTo(0f, h)
        val steps = 72
        for (s in 0..steps) {
            val x = w * s / steps
            val y = base -
                a1 * sin(2 * PI.toFloat() * k1 * x / w + p1) -
                a2 * sin(2 * PI.toFloat() * k2 * x / w + p2)
            path.lineTo(x, y)
        }
        path.lineTo(w, h)
        path.close()

        // Pull the ridge toward the sky so it reads as mist, not a cutout.
        val color = lerp(raw, skyBottom, 0.30f)
        val top = base - a1 - a2 - h * 0.015f
        // Feather the top edge: faint passes shifted upward, outermost first.
        val passes = listOf(h * 0.011f to 0.18f, h * 0.005f to 0.40f, 0f to 1f)
        for ((lift, alphaScale) in passes) {
            translate(top = -lift) {
                clipPath(path) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to color.copy(alpha = color.alpha * alphaScale),
                                0.40f to lerp(color, fog, 0.55f)
                                    .copy(alpha = color.alpha * alphaScale),
                                1.00f to fog.copy(alpha = fog.alpha * alphaScale),
                            ),
                            startY = top,
                            endY = h,
                        ),
                    )
                }
            }
        }
    }

    // Fog band lifting off the ridge floor — ridges dissolve into it.
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to fog.copy(alpha = 0f),
                1.0f to fog.copy(alpha = 0.6f),
            ),
            startY = h * 0.58f,
            endY = h,
        ),
    )

    // Very soft vignette top & bottom for legibility.
    val v = if (dark) 0.20f else 0.09f
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color.Black.copy(alpha = v),
                0.24f to Color.Transparent,
                0.80f to Color.Transparent,
                1.00f to Color.Black.copy(alpha = v),
            ),
        ),
    )
}

/** Draws a painter ContentScale.Crop over the whole canvas. */
private fun DrawScope.drawImageCover(painter: Painter) {
    val src = painter.intrinsicSize
    if (src.width <= 0f || src.height <= 0f) return
    val scale = max(size.width / src.width, size.height / src.height)
    val dw = src.width * scale
    val dh = src.height * scale
    translate(
        left = (size.width - dw) / 2f,
        top = (size.height - dh) / 2f,
    ) {
        with(painter) { draw(size = Size(dw, dh)) }
    }
}
