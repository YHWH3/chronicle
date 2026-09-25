package com.chronicle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.domain.EntryKind
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.ui.components.ChronicleIcon
import com.chronicle.app.ui.components.Hairline
import com.chronicle.app.ui.components.Masthead
import com.chronicle.app.ui.components.MetaText
import com.chronicle.app.ui.theme.ChronicleExtras
import com.chronicle.app.ui.vm.JournalViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun JournalScreen(viewModel: JournalViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 28.dp, end = 28.dp, top = 20.dp, bottom = 48.dp,
        ),
    ) {
        item {
            Masthead(kicker = "The record", title = "Journal")
            Spacer(Modifier.height(8.dp))
        }

        if (state.days.isEmpty()) {
            item {
                Spacer(Modifier.height(80.dp))
                Text(
                    "The pages are blank for now.",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                MetaText("Speak or write your first entry on Today")
            }
        }

        for (day in state.days) {
            item(key = "day-${day.day}") {
                Spacer(Modifier.height(30.dp))
                Text(
                    text = dayLabel(day.day),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                MetaText(text = day.day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)))
                Spacer(Modifier.height(10.dp))
                Hairline()
            }
            items(day.entries, key = { it.id }) { entry ->
                EntryRow(
                    entry = entry,
                    isPlaying = state.playingPath == entry.audioPath,
                    progress = if (state.playingPath == entry.audioPath) state.progress else 0f,
                    onPlay = { entry.audioPath?.let { viewModel.togglePlay(it) } },
                    onDelete = { viewModel.delete(entry) },
                )
                Hairline()
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: JournalEntry,
    isPlaying: Boolean,
    progress: Float,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaText(
                text = entry.createdAt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            ChronicleIcon(
                res = if (entry.kind == EntryKind.VOICE) R.drawable.ic_mic else R.drawable.ic_pen,
                contentDescription = null,
                tint = ChronicleExtras.faint,
                modifier = Modifier.size(14.dp),
            )
            if (entry.kind == EntryKind.VOICE && entry.durationMs > 0) {
                Spacer(Modifier.width(8.dp))
                MetaText(
                    text = "${entry.durationMs / 1000}s",
                    color = ChronicleExtras.faint,
                )
            }
            Spacer(Modifier.weight(1f))
            ChronicleIcon(
                res = R.drawable.ic_delete,
                contentDescription = "Remove",
                tint = ChronicleExtras.faint,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onDelete),
            )
        }

        if (entry.transcript.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = entry.transcript,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (entry.audioPath != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.primary
                            else ChronicleExtras.raised,
                        )
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    ChronicleIcon(
                        res = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(ChronicleExtras.hairline),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
    }
}
