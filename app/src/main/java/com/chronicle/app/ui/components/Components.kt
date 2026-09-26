package com.chronicle.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.ui.theme.ChronicleExtras
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.theme.MetaStyle
import com.chronicle.app.ui.theme.MetaStyleLarge

/** The quiet machinery voice — mono, tracked, uppercase. */
@Composable
fun MetaText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ChronicleExtras.faint,
    large: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        style = if (large) MetaStyleLarge else MetaStyle,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = ChronicleExtras.hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun ChronicleIcon(
    @DrawableRes res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Icon(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier.size(20.dp),
        tint = tint,
    )
}

/** Text action in the accent — the only chromatic affordance in the app. */
@Composable
fun AccentAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text = label.uppercase(),
        style = MetaStyleLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Big circular record control: hairline ring, ink dot, springs on press. */
@Composable
fun RecordButton(
    recording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val ringScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            recording -> 1.04f
            else -> 1f
        },
        animationSpec = if (pressed) {
            ChronicleMotion.pressSpring
        } else {
            spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy)
        },
        label = "ring",
    )
    // Glyph crossfades mic ⇄ stop.
    Box(
        modifier = modifier
            .size(96.dp)
            .graphicsLayer { scaleX = ringScale; scaleY = ringScale }
            .clip(CircleShape)
            .border(1.dp, ChronicleExtras.hairline, CircleShape)
            .clickable(
                interactionSource = press,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(
            targetState = recording,
            animationSpec = tween(ChronicleMotion.MICRO),
            label = "recGlyph",
        ) { isRecording ->
            if (isRecording) {
                ChronicleIcon(R.drawable.ic_stop, "Stop", tint = MaterialTheme.colorScheme.primary)
            } else {
                ChronicleIcon(R.drawable.ic_mic, "Record", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Live amplitude bars — a quiet oscilloscope. */
@Composable
fun Waveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    barWidth: Dp = 3.dp,
    gap: Dp = 3.dp,
    maxHeight: Dp = 44.dp,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(maxHeight)) {
        val total = amplitudes.size
        val barPx = barWidth.toPx()
        val gapPx = gap.toPx()
        val mid = size.height / 2f
        amplitudes.forEachIndexed { i, amp ->
            val h = (amp * size.height).coerceAtLeast(2f)
            val x = i * (barPx + gapPx)
            drawRoundRect(
                brush = SolidColor(barColor.copy(alpha = 0.35f + 0.65f * amp)),
                topLeft = Offset(x, mid - h / 2f),
                size = Size(barPx, h),
                cornerRadius = CornerRadius(barPx / 2f),
            )
        }
    }
}

/** Screen header: meta kicker above a serif headline — the editorial masthead. */
@Composable
fun Masthead(kicker: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        MetaText(text = kicker, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(text = title, style = MaterialTheme.typography.displayMedium)
    }
}

/** Thin editorial rule with a centered mono label — separates chapters of UI. */
@Composable
fun LabelledRule(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(ChronicleExtras.hairline))
        MetaText(text = label, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(ChronicleExtras.hairline))
    }
}
