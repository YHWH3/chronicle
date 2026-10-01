package com.chronicle.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shared motion tokens — M3 easing/duration trimmed to Chronicle's quiet voice:
 * brief, precise, never bouncy for content (a little life only on the record
 * ring). Enter = decelerate, exit = accelerate, state changes = standard.
 */
object ChronicleMotion {
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EnterEasing: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    const val MICRO = 150    // chip tints, icon swaps, presses
    const val ENTER = 250    // elements arriving
    const val NAV = 280      // screen transitions
    const val EXIT = 180     // elements leaving
    const val THEME = 350    // full-scheme crossfade
    const val MORPH = 420    // scene/capture-element morphs
    const val SCREEN = 320   // nav transitions
    const val AMBIENT_MS = 3600 // breathing loops (waveform, orb, glow)

    /** Soft landing for positional/state animations (no bounce). */
    val settleSpring = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioNoBouncy,
    )

    /** Snappy press feedback with a hint of life. */
    val pressSpring = spring<Float>(
        stiffness = Spring.StiffnessHigh,
        dampingRatio = Spring.DampingRatioMediumBouncy,
    )

    /** Slow drift for floating controls — selection pills, nav capsule, orb. */
    val floatSpring = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = 0.7f,
    )
}

/** Squash slightly while pressed — tactile without any shadow. */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressed: Float = 0.96f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = ChronicleMotion.pressSpring,
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
