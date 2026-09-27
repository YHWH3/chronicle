package com.chronicle.app.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.ui.theme.ChronicleMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Amplitude bars tinted lavender → peach across the width. */
@Composable
fun GradientWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    // Idle breathing so the strip never looks dead.
    val idle = amplitudes.all { it < 0.08f }
    val ambient = rememberInfiniteTransition(label = "waveAmbient")
    val t by ambient.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(ChronicleMotion.AMBIENT_MS, easing = ChronicleMotion.StandardEasing),
            RepeatMode.Restart,
        ),
        label = "waveT",
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        val count = max(amplitudes.size, 36)
        val gap = 5.dp.toPx()
        val bar = ((size.width - gap * (count - 1)) / count).coerceIn(2.dp.toPx(), 5.dp.toPx())
        val total = count * bar + (count - 1) * gap
        val startX = (size.width - total) / 2f
        val mid = size.height / 2f
        for (i in 0 until count) {
            val raw = amplitudes.getOrElse(i) { 0f }
            val amp = if (idle) {
                0.30f + 0.18f * sin(t + i * 0.45f)
            } else {
                (raw * 2.2f).coerceAtLeast(0.12f)
            }
            val frac = i.toFloat() / (count - 1)
            val color = lerpColor(GradientStart, GradientEnd, frac)
            val h = (amp * size.height).coerceIn(3.dp.toPx(), size.height)
            drawRoundRect(
                brush = SolidColor(color.copy(alpha = 0.35f + 0.65f * amp.coerceIn(0f, 1f))),
                topLeft = Offset(startX + i * (bar + gap), mid - h / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

/** The processing sphere — a glassy gradient orb that breathes. */
@Composable
fun Orb(size: Dp, modifier: Modifier = Modifier) {
    val ambient = rememberInfiniteTransition(label = "orb")
    val breath by ambient.animateFloat(
        initialValue = 0.97f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            tween(ChronicleMotion.AMBIENT_MS / 2, easing = ChronicleMotion.StandardEasing),
            RepeatMode.Reverse,
        ),
        label = "orbBreath",
    )
    val angle by ambient.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(ChronicleMotion.AMBIENT_MS * 2, easing = ChronicleMotion.StandardEasing),
            RepeatMode.Restart,
        ),
        label = "orbHue",
    )
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { scaleX = breath; scaleY = breath },
    ) {
        val r = this.size.minDimension / 2f
        val c = center
        // Outer halo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GradientStart.copy(alpha = 0.30f), Color.Transparent),
                center = c, radius = r * 1.35f,
            ),
            radius = r * 1.35f, center = c,
        )
        // Body: lavender → peach, center wandering slowly.
        val gcx = c.x + r * 0.18f * cos(angle)
        val gcy = c.y + r * 0.18f * sin(angle)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GradientEnd, GradientStart),
                center = Offset(gcx - r * 0.25f, gcy - r * 0.25f), radius = r * 1.6f,
            ),
            radius = r, center = c,
        )
        // White sheen, upper-left.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.75f), Color.Transparent),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.38f), radius = r * 0.55f,
            ),
            radius = r * 0.55f, center = Offset(c.x - r * 0.35f, c.y - r * 0.38f),
        )
        // Rim light.
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = r - 1.dp.toPx(), center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )
    }
}

/** 4dp progress track — determinate fill or indeterminate shimmer. */
@Composable
fun ProgressTrack(progress: Float?, modifier: Modifier = Modifier) {
    val p = Chron.palette
    val track = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(p.surfaceFrosted, track),
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(primaryGradient(), track),
            )
        } else {
            val t = rememberInfiniteTransition(label = "track")
            val x by t.animateFloat(
                initialValue = -0.3f, targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    tween(1400, easing = ChronicleMotion.StandardEasing),
                    RepeatMode.Restart,
                ),
                label = "trackX",
            )
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, GradientStart, GradientEnd, Color.Transparent),
                        startX = x * size.width - size.width * 0.3f,
                        endX = x * size.width + size.width * 0.3f,
                    ),
                )
            }
        }
    }
}

@Immutable
data class Step(
    val label: String,
    val state: State,
) {
    enum class State { Pending, Active, Done }
}

/** Vertical checklist that animates Pending → Active → Done. */
@Composable
fun StepList(steps: List<Step>, modifier: Modifier = Modifier) {
    val p = Chron.palette
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepIndicator(step.state)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = step.label,
                    style = Chron.type.body,
                    color = when (step.state) {
                        Step.State.Done -> p.textSecondary
                        Step.State.Active -> p.textPrimary
                        Step.State.Pending -> p.textTertiary
                    },
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(state: Step.State) {
    val p = Chron.palette
    val ambient = rememberInfiniteTransition(label = "step")
    val pulse by ambient.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = ChronicleMotion.StandardEasing),
            RepeatMode.Reverse,
        ),
        label = "stepPulse",
    )
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        when (state) {
            Step.State.Done -> {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(p.accentSoft, CircleShape)
                        .border(1.dp, p.accent.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = p.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Step.State.Active -> {
                Box(
                    Modifier
                        .size(22.dp)
                        .graphicsLayer { alpha = pulse }
                        .border(2.dp, p.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(6.dp).background(p.accent, CircleShape))
                }
            }
            Step.State.Pending -> {
                Box(
                    Modifier
                        .size(22.dp)
                        .border(1.5.dp, p.textTertiary.copy(alpha = 0.45f), CircleShape),
                )
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
