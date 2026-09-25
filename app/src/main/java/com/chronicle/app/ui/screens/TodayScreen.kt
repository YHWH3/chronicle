package com.chronicle.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import androidx.core.content.ContextCompat
import com.chronicle.app.domain.EntryKind
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.ui.components.AccentAction
import com.chronicle.app.ui.components.ChronicleIcon
import com.chronicle.app.ui.components.Hairline
import com.chronicle.app.ui.components.MetaText
import com.chronicle.app.ui.components.RecordButton
import com.chronicle.app.ui.components.Waveform
import com.chronicle.app.ui.theme.ChronicleExtras
import com.chronicle.app.ui.theme.SerifItalic
import com.chronicle.app.ui.vm.CaptureMode
import com.chronicle.app.ui.vm.RecordingState
import com.chronicle.app.ui.vm.TodayUiState
import com.chronicle.app.ui.vm.TodayViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onMicPermissionResult(granted)
        if (granted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.toggleRecording()
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // A save that scheduled reminders is the moment to ask for notifications.
    LaunchedEffect(state.confirmation) {
        if (state.confirmation?.contains("reminder") == true &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaText(
                text = LocalDate.now()
                    .format(DateTimeFormatter.ofPattern("EEE · d MMM", Locale.ENGLISH)),
            )
            Spacer(Modifier.weight(1f))
            ChronicleIcon(
                R.drawable.ic_gear,
                "Details",
                tint = ChronicleExtras.faint,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(greeting(), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "What happened today?",
            style = SerifItalic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))

        CaptureArea(
            state = state,
            onSpeak = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onStop = { viewModel.toggleRecording() },
            onCancel = { viewModel.cancelRecording() },
            onMode = { viewModel.setMode(it) },
            onDraft = { viewModel.setDraft(it) },
            onKeep = { viewModel.saveDraft() },
        )

        AnimatedVisibility(visible = state.confirmation != null, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(20.dp))
                MetaText(text = state.confirmation.orEmpty(), color = MaterialTheme.colorScheme.primary)
            }
        }

        if (state.todaysEntries.isNotEmpty()) {
            Spacer(Modifier.height(40.dp))
            LabelledSection(label = "Earlier today")
            Spacer(Modifier.height(12.dp))
            state.todaysEntries.take(3).forEachIndexed { i, entry ->
                EntryRowCompact(entry)
                if (i < minOf(2, state.todaysEntries.size - 1)) Hairline(Modifier.padding(vertical = 12.dp))
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun LabelledSection(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MetaText(text = label)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).height(1.dp).background(ChronicleExtras.hairline))
    }
}

@Composable
private fun CaptureArea(
    state: TodayUiState,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onMode: (CaptureMode) -> Unit,
    onDraft: (String) -> Unit,
    onKeep: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.recording == RecordingState.RECORDING) {
            Waveform(amplitudes = state.amplitudes, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(20.dp))
            MetaText(text = formatElapsed(state.elapsedMs), color = MaterialTheme.colorScheme.primary, large = true)
            if (state.liveTranscript.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = state.liveTranscript,
                    style = SerifItalic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))
            RecordButton(recording = true, onClick = onStop)
            Spacer(Modifier.height(14.dp))
            MetaText(text = "Tap to keep")
            Spacer(Modifier.height(6.dp))
            MetaText(
                text = "Discard",
                modifier = Modifier.clickable(onClick = onCancel).padding(8.dp),
            )
        } else if (state.processing) {
            Spacer(Modifier.height(12.dp))
            MetaText(text = "Reading your words…", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
        } else {
            ModeSwitch(mode = state.mode, onMode = onMode)
            Spacer(Modifier.height(32.dp))
            when (state.mode) {
                CaptureMode.SPEAK -> {
                    RecordButton(recording = false, onClick = onSpeak)
                    Spacer(Modifier.height(16.dp))
                    MetaText(text = "Tap · speak your day")
                }
                CaptureMode.WRITE -> {
                    WriteField(
                        draft = state.draftText,
                        saving = state.saving,
                        onDraft = onDraft,
                        onKeep = onKeep,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: CaptureMode, onMode: (CaptureMode) -> Unit) {
    Row {
        CaptureMode.entries.forEach { m ->
            val selected = m == mode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onMode(m) },
            ) {
                MetaText(
                    text = if (m == CaptureMode.SPEAK) "Speak" else "Write",
                    color = if (selected) MaterialTheme.colorScheme.onSurface else ChronicleExtras.faint,
                    large = true,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(72.dp)
                        .height(1.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.hairline,
                        ),
                )
            }
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun WriteField(
    draft: String,
    saving: Boolean,
    onDraft: (String) -> Unit,
    onKeep: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            "Begin anywhere. The day will shape itself.",
                            style = SerifItalic,
                            color = ChronicleExtras.faint,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        Hairline()
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AccentAction(label = "Keep", onClick = onKeep, enabled = draft.isNotBlank() && !saving)
        }
    }
}

@Composable
private fun EntryRowCompact(entry: JournalEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MetaText(
            text = entry.createdAt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)),
            color = ChronicleExtras.faint,
        )
        Spacer(Modifier.width(16.dp))
        if (entry.kind == EntryKind.VOICE) {
            ChronicleIcon(R.drawable.ic_mic, null, tint = ChronicleExtras.faint, modifier = Modifier.width(14.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = entry.transcript.ifBlank { "A voice note" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

private fun greeting(): String {
    val hour = LocalDateTime.now().hour
    return when {
        hour < 5 -> "A quiet hour."
        hour < 12 -> "Good morning."
        hour < 17 -> "Good afternoon."
        else -> "Good evening."
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
