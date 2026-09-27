package com.chronicle.app.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.theme.pressScale

private val PillShape = RoundedCornerShape(percent = Radius.pill)

/** Gradient call-to-action — the app's single chromatic affordance. */
@Composable
fun PrimaryPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val p = Chron.palette
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 52.dp)
            .sizeIn(minWidth = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interaction)
            .clip(PillShape)
            .drawBehind { drawRect(primaryGradient()) }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = p.textPrimary),
                role = Role.Button,
                enabled = enabled && !loading,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.x6, vertical = 14.dp),
    ) {
        if (loading) {
            BreathingDots(color = OnGradient)
        } else {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = OnGradient,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.x2))
            }
            Text(text = label, style = Chron.type.control, color = OnGradient)
        }
    }
}

/** Quiet secondary action on frosted glass. */
@Composable
fun SecondaryPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = PillShape,
        onClick = if (enabled) onClick else null,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Chron.type.control,
            color = Chron.palette.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.x6, vertical = 14.dp),
        )
    }
}

/** Text-only action with a proper touch target. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Chron.palette.textSecondary,
) {
    val p = Chron.palette
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = label,
        style = Chron.type.control,
        color = color,
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .clip(PillShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = p.textPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.x4, vertical = 14.dp),
    )
}

/** Frosted circle icon button — the standard chrome control. */
@Composable
fun FrostedIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    tint: Color = Chron.palette.textPrimary,
) {
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = CircleShape,
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The capture control — a gradient circle that breathes with the microphone.
 * [breath] (0..1) gently scales it while recording.
 */
@Composable
fun LargeActionCircle(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 88.dp,
    frosted: Boolean = false,
    breath: Float = 0f,
) {
    val p = Chron.palette
    val interaction = remember { MutableInteractionSource() }
    val scale = 1f + breath * 0.08f
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .drawBehind {
                if (frosted) {
                    drawRect(p.surfaceFrosted)
                } else {
                    drawRect(primaryGradient())
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = p.textPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (frosted) p.textPrimary else OnGradient,
                modifier = Modifier.size(size * 0.32f),
            )
        }
    }
}

/** Three dots breathing in sequence — the loading voice. */
@Composable
fun BreathingDots(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = i * 180, easing = ChronicleMotion.StandardEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(a)
                    .drawBehind { drawCircle(color) },
            )
        }
    }
}
