package com.chronicle.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import kotlin.math.abs

/**
 * Swipe end-to-start to delete: slides [content] aside to reveal a
 * warning-coloured delete affordance. The affordance fades in with the
 * row's displacement and is fully invisible at rest, so rows that float
 * on the atmosphere or live inside a shared glass surface never leak it.
 * While displaced, the row gets a near-opaque underlay so the affordance
 * can't bleed through the glass — pass [foreground] only when a row
 * needs a resting fill of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val p = Chron.palette
    val state = rememberSwipeToDismissBoxState()
    var widthPx by remember { mutableFloatStateOf(0f) }
    // Offset in px: 0 at rest, grows as the row is dragged. progress is
    // unsuitable — it reports 1f once settled.
    val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
    val reveal =
        if (widthPx > 0f) (abs(offset) / (widthPx * 0.25f)).coerceIn(0f, 1f) else 0f
    // Near-opaque stand-in for the glass while the row is displaced.
    val dragFill = p.surfaceFrosted.compositeOver(p.scrim.copy(alpha = 1f))
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { widthPx = it.width.toFloat() },
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(reveal),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        tint = p.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Delete",
                        style = Chron.type.controlSmall,
                        color = p.warning,
                    )
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.sm))
                .drawBehind {
                    drawRect(if (abs(offset) > 0.5f) dragFill else foreground)
                },
        ) {
            content()
        }
    }
}
