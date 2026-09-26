package com.chronicle.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

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
}
