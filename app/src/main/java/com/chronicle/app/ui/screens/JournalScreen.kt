package com.chronicle.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.domain.EntryKind
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.domain.TranscriptStatus
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.GhostButton
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.Hairline
import com.chronicle.app.ui.design.LocalBottomBarPadding
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.design.TickTrack
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.JournalViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun JournalScreen(viewModel: JournalViewModel, onOpenToday: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var expandedId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = Spacing.screenMargin, end = Spacing.screenMargin,
            top = Spacing.x4, bottom = LocalBottomBarPadding.current,
        ),
    ) {
        item {
            Text(
                text = "Journal",
                style = Chron.type.title,
                color = Chron.palette.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.x6))
        }

        if (state.days.isEmpty()) {
            item {
                Spacer(Modifier.height(96.dp))
                Text(
                    text = "Your journal is waiting.",
                    style = Chron.type.headline,
                    color = Chron.palette.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Speak or write on Today — everything you keep lands here.",
                    style = Chron.type.body,
                    color = Chron.palette.textSecondary,
                )
                Spacer(Modifier.height(18.dp))
                GhostButton(
                    label = "Start on Today",
                    onClick = onOpenToday,
                )
            }
        }

        for (day in state.days) {
            item(key = "label-${day.day}") {
                Text(
                    text = dayLabel(day.day),
                    style = Chron.type.section,
                    color = Chron.palette.textSecondary,
                    modifier = Modifier
                        .animateItem()
                        .padding(top = Spacing.x8, bottom = Spacing.x3),
                )
            }
            // One shared frosted surface per day; hairlines between rows.
            item(key = "card-${day.day}") {
                GlassSurface(
                    level = SurfaceLevel.Frosted,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth().animateItem(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        day.entries.forEachIndexed { index, entry ->
                            val isPlaying = state.playingPath != null &&
                                state.playingPath == entry.audioPath
                            JournalEntryRow(
                                entry = entry,
                                expanded = expandedId == entry.id,
                                isPlaying = isPlaying,
                                progress = if (isPlaying) state.progress else 0f,
                                canRetranscribe = state.canRetranscribe,
                                transcribing = entry.id in state.transcribingIds,
                                onClick = {
                                    expandedId =
                                        if (expandedId == entry.id) null else entry.id
                                },
                                onPlay = { entry.audioPath?.let { viewModel.togglePlay(it) } },
                                onRetryTranscription = {
                                    viewModel.retryTranscription(entry)
                                },
                                onDelete = {
                                    expandedId = null
                                    viewModel.delete(entry)
                                },
                            )
                            if (index < day.entries.lastIndex) Hairline()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalEntryRow(
    entry: JournalEntry,
    expanded: Boolean,
    isPlaying: Boolean,
    progress: Float,
    canRetranscribe: Boolean,
    transcribing: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRetryTranscription: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = Chron.palette
    val type = Chron.type
    val rowTint by animateColorAsState(
        targetValue = if (isPlaying) p.surfaceSelected else p.surfaceSelected.copy(alpha = 0f),
        animationSpec = tween(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing),
        label = "rowTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .drawBehind { drawRect(rowTint) }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Delete entry") { onDelete(); true },
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = p.textPrimary),
                onClick = onClick,
            )
            .padding(horizontal = Spacing.x4, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.createdAt.format(
                    DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH),
                ),
                style = type.numeric,
                color = p.textTertiary,
                modifier = Modifier.width(46.dp),
            )
            when (entry.kind) {
                EntryKind.VOICE -> {
                    PlayPauseButton(isPlaying = isPlaying, onClick = onPlay)
                    Spacer(Modifier.width(12.dp))
                    TickTrack(
                        progress = progress,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formatDuration(entry.durationMs),
                        style = type.numeric,
                        color = p.textSecondary,
                    )
                }
                EntryKind.TEXT -> {
                    Text(
                        text = entry.transcript.ifBlank { "A written note" },
                        style = type.body,
                        color = p.textPrimary,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (entry.kind == EntryKind.VOICE && entry.transcript.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = entry.transcript,
                style = type.body,
                color = p.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 46.dp),
            )
        }

        // A take that kept its audio but lost its words says so quietly —
        // and offers a retry once the model that can read it is installed.
        // PENDING covers a retry orphaned by a process death.
        val needsTranscript = entry.kind == EntryKind.VOICE &&
            (entry.transcriptStatus == TranscriptStatus.FAILED ||
                entry.transcriptStatus == TranscriptStatus.PENDING)
        if (needsTranscript || transcribing) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (transcribing ||
                    entry.transcriptStatus == TranscriptStatus.PENDING
                ) {
                    "Transcribing…"
                } else {
                    "Transcription failed — audio kept."
                },
                style = type.meta,
                color = p.warning,
                modifier = Modifier.padding(start = 46.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End),
            ) {
                if (needsTranscript && canRetranscribe && entry.audioPath != null) {
                    GhostButton(
                        label = if (transcribing) "Transcribing…" else "Transcribe again",
                        onClick = { if (!transcribing) onRetryTranscription() },
                        color = p.accent,
                    )
                    Spacer(Modifier.width(Spacing.x4))
                }
                GhostButton(
                    label = "Delete",
                    onClick = onDelete,
                    color = p.warning,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val p = Chron.palette
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(p.surfaceFrosted)
                drawCircle(
                    color = p.surfaceFrostedStroke,
                    radius = size.minDimension / 2f - 1f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = p.textPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = null,
            tint = p.textPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE · d MMM", Locale.ENGLISH))
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
