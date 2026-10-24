package com.chronicle.app.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.ui.theme.ChronicleMotion

/** Vertical space screens reserve above the floating bar. */
val LocalBottomBarPadding = staticCompositionLocalOf { 0.dp }

@androidx.compose.runtime.Immutable
data class NavItem(
    val route: String,
    @DrawableRes val icon: Int,
    val label: String,
)

/**
 * Frosted pill tab bar floating over the atmosphere. The selected capsule
 * slides between items; the whole bar slides away on push routes and while
 * the IME is up.
 */
@Composable
fun FloatingNavBar(
    items: List<NavItem>,
    currentRoute: String?,
    visible: Boolean,
    onSelect: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Chron.palette
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = spring(
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                dampingRatio = 0.7f,
            ),
        ) { it },
        exit = slideOutVertically(
            animationSpec = spring(
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                dampingRatio = 0.7f,
            ),
        ) { it } + fadeOut(tween(ChronicleMotion.MICRO)),
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            GlassSurface(
                level = SurfaceLevel.Elevated,
                shape = RoundedCornerShape(percent = Radius.pill),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    val selectedIndex = items.indexOfFirst { it.route == currentRoute }
                        .coerceAtLeast(0)
                    val bias by animateFloatAsState(
                        targetValue = -1f + 2f * selectedIndex / (items.size - 1).coerceAtLeast(1),
                        animationSpec = ChronicleMotion.floatSpring,
                        label = "navBias",
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / items.size)
                            .align(BiasAlignment(bias, 0f))
                            .padding(6.dp),
                    ) {
                        GlassSurface(
                            level = SurfaceLevel.Selected,
                            shape = RoundedCornerShape(percent = Radius.pill),
                            modifier = Modifier.fillMaxSize(),
                        ) {}
                    }
                    Row(Modifier.fillMaxSize()) {
                        items.forEach { item ->
                            val selected = item.route == currentRoute
                            val tint by animateColorAsState(
                                targetValue = if (selected) p.textPrimary else p.textTertiary,
                                animationSpec = tween(
                                    ChronicleMotion.MICRO,
                                    easing = ChronicleMotion.StandardEasing,
                                ),
                                label = "navTint",
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(percent = Radius.pill))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(color = p.textPrimary),
                                        role = Role.Tab,
                                        onClick = { onSelect(item) },
                                    ),
                            ) {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = item.label,
                                    tint = tint,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = item.label,
                                    style = Chron.type.meta.copy(fontSize = 11.sp),
                                    color = tint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
