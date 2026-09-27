package com.chronicle.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.theme.pressScale

enum class SurfaceLevel { Transparent, Subtle, Frosted, Selected, Elevated }

/**
 * The frosted-glass primitive: translucent fill, hairline light stroke, a
 * whisper of top-edge highlight — never a shadow. Fill and stroke tween when
 * [level] changes.
 */
@Composable
fun GlassSurface(
    level: SurfaceLevel,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.md),
    onClick: (() -> Unit)? = null,
    role: Role? = if (onClick != null) Role.Button else null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = Chron.palette
    val spec = tween<Color>(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing)

    val targetFill = when (level) {
        SurfaceLevel.Transparent -> Color.Transparent
        SurfaceLevel.Subtle -> p.surfaceSubtle
        SurfaceLevel.Frosted -> p.surfaceFrosted
        SurfaceLevel.Selected -> p.surfaceSelected
        SurfaceLevel.Elevated -> p.surfaceElevated
    }
    val targetStroke = when (level) {
        SurfaceLevel.Frosted, SurfaceLevel.Elevated -> p.surfaceFrostedStroke
        SurfaceLevel.Selected -> p.surfaceSelectedStroke
        else -> Color.Transparent
    }
    val fill by animateColorAsState(targetFill, spec, label = "glassFill")
    val stroke by animateColorAsState(targetStroke, spec, label = "glassStroke")
    val highlight = p.surfaceFrostedStroke

    val interaction = remember { MutableInteractionSource() }

    var m = modifier
        .clip(shape)
        .drawBehind {
            drawRect(fill)
            if (level == SurfaceLevel.Frosted || level == SurfaceLevel.Elevated) {
                // Top-edge sheen — light catching the glass.
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to highlight.copy(alpha = highlight.alpha * 0.5f),
                            0.35f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    ),
                )
            }
        }
        .border(1.dp, stroke, shape)

    if (onClick != null) {
        m = m
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = p.textPrimary),
                role = role,
                onClick = onClick,
            )
    }

    Box(modifier = m, contentAlignment = contentAlignment, content = content)
}
