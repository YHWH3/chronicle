package com.chronicle.app.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.theme.ChronicleMotion

private val PillShape = RoundedCornerShape(percent = Radius.pill)

/**
 * A round outline check — fills with the accent on a spring when done.
 * 48dp touch target, Checkbox semantics.
 */
@Composable
fun CircleCheck(
    checked: Boolean,
    contentDescription: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Chron.palette
    val fill by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) p.accent else Color.Transparent,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "checkFill",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = 1.5.dp,
                    color = if (checked) p.accent else p.textTertiary.copy(alpha = 0.55f),
                    shape = CircleShape,
                )
                .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        ) {
            if (checked) {
                Icon(
                    painter = painterResource(com.chronicle.app.R.drawable.ic_check),
                    contentDescription = null,
                    tint = if (p.isDark) OnGradient else Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** Speak/Write-style segmented control — the selected capsule slides. */
@Composable
fun SegmentedPills(
    options: List<Pair<String, Int?>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Chron.palette
    val bias by animateFloatAsState(
        targetValue = if (options.size > 1) -1f + 2f * selectedIndex / (options.size - 1) else 0f,
        animationSpec = ChronicleMotion.floatSpring,
        label = "segBias",
    )
    Box(modifier.height(46.dp)) {
        // Sliding selected capsule.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / options.size)
                .align(BiasAlignment(bias, 0f)),
        ) {
            GlassSurface(
                level = SurfaceLevel.Selected,
                shape = PillShape,
                modifier = Modifier.fillMaxSize().padding(2.dp),
            ) {}
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (label, icon) ->
                val selected = i == selectedIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = p.textPrimary),
                            role = Role.Tab,
                            onClick = { onSelect(i) },
                        ),
                ) {
                    if (icon != null) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = if (selected) p.textPrimary else p.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        text = label,
                        style = Chron.type.control,
                        color = if (selected) p.textPrimary else p.textSecondary,
                    )
                }
            }
        }
    }
}

/** A standalone selectable pill — radio semantics for tests/a11y. */
@Composable
fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = Chron.type.controlSmall,
) {
    val p = Chron.palette
    GlassSurface(
        level = if (selected) SurfaceLevel.Selected else SurfaceLevel.Frosted,
        shape = PillShape,
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = p.textPrimary),
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = textStyle,
            color = if (selected) p.textPrimary else p.textSecondary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** Settings-style row card: leading radio, title/subtitle/meta, optional trailing. */
@Composable
fun OptionCard(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val p = Chron.palette
    GlassSurface(
        level = if (selected) SurfaceLevel.Selected else SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.md),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = p.textPrimary),
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x4),
        ) {
            // Leading radio.
            val ringColor = if (selected) p.accent else p.textTertiary
            Box(
                Modifier
                    .size(20.dp)
                    .border(1.5.dp, ringColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(Modifier.size(10.dp).background(p.accent, CircleShape))
                }
            }
            Spacer(Modifier.width(Spacing.x3))
            Column(Modifier.weight(1f)) {
                Text(title, style = Chron.type.control, color = p.textPrimary)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = Chron.type.meta, color = p.textSecondary)
                }
                if (meta != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = Chron.type.meta, color = p.textTertiary)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(Spacing.x3))
                trailing()
            }
        }
    }
}

/** A soft pill switch — no Material chrome. */
@Composable
fun SoftSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Chron.palette
    val track by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) p.accent else p.surfaceFrosted,
        animationSpec = androidx.compose.animation.core.tween(
            ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing,
        ),
        label = "switchTrack",
    )
    val bias by animateFloatAsState(
        targetValue = if (checked) 1f else -1f,
        animationSpec = ChronicleMotion.floatSpring,
        label = "switchThumb",
    )
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = p.textPrimary),
                role = Role.Switch,
                onValueChange = onChange,
            )
            .clip(PillShape)
            .background(track)
            .border(1.dp, p.surfaceFrostedStroke, PillShape)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .align(BiasAlignment(bias, 0f))
                .background(Color.White, CircleShape),
        )
    }
}
