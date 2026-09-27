package com.chronicle.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chronicle.app.R
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.ui.components.AccentAction
import com.chronicle.app.ui.components.ChronicleIcon
import com.chronicle.app.ui.components.Hairline
import com.chronicle.app.ui.components.Masthead
import com.chronicle.app.ui.components.MetaText
import com.chronicle.app.ui.theme.ChronicleExtras
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.ReminderChoice
import com.chronicle.app.ui.vm.TasksViewModel
import com.chronicle.app.ui.vm.asLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TasksScreen(viewModel: TasksViewModel) {
    val state by viewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 28.dp, end = 28.dp, top = 20.dp, bottom = com.chronicle.app.ui.design.LocalBottomBarPadding.current,
        ),
    ) {
        item {
            Masthead(kicker = "Pulled from your words", title = "Intentions")
            Spacer(Modifier.height(24.dp))
            NewTaskRow(
                draft = state.draft,
                choice = state.draftReminder,
                adding = state.adding,
                onDraft = viewModel::setDraft,
                onChoice = viewModel::setReminderChoice,
                onAdd = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    // Ask for notifications at the moment a reminder is being set.
                    if (state.draftReminder != ReminderChoice.NONE &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.addTask()
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.open.isEmpty() && state.done.isEmpty()) {
            item {
                Spacer(Modifier.height(60.dp))
                Text(
                    "Nothing to hold you to yet.",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                MetaText("Say \"remind me to…\" while recording, or write it here")
            }
        }

        items(state.open, key = { it.id }) { task ->
            TaskRow(
                modifier = Modifier.animateItem(),
                task = task,
                onToggle = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.toggleDone(task)
                },
                onDelete = { viewModel.delete(task) },
            )
            Hairline()
        }

        if (state.done.isNotEmpty()) {
            item {
                Spacer(Modifier.height(28.dp))
                MetaText("Done")
                Spacer(Modifier.height(10.dp))
            }
            items(state.done, key = { "done-${it.id}" }) { task ->
                TaskRow(
                    modifier = Modifier.animateItem(),
                    task = task,
                    onToggle = { viewModel.toggleDone(task) },
                    onDelete = { viewModel.delete(task) },
                )
                Hairline()
            }
        }
    }
}

@Composable
private fun NewTaskRow(
    draft: String,
    choice: ReminderChoice,
    adding: Boolean,
    onDraft: (String) -> Unit,
    onChoice: (ReminderChoice) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            "An intention, plainly put…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ChronicleExtras.faint,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            ReminderChoice.entries.forEach { c ->
                val selected = c == choice
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .border(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.hairline,
                            MaterialTheme.shapes.small,
                        )
                        .selectable(selected = selected) {
                            onChoice(if (selected) ReminderChoice.NONE else c)
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    MetaText(
                        text = c.label,
                        color = if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Hairline()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AccentAction(label = "Set", onClick = onAdd, enabled = draft.isNotBlank() && !adding)
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        // The ring that completes.
        val ringColor by animateColorAsState(
            targetValue = if (task.done) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
            animationSpec = tween(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing),
            label = "ring",
        )
        val ringFill by animateColorAsState(
            targetValue = if (task.done) MaterialTheme.colorScheme.primary
            else androidx.compose.ui.graphics.Color.Transparent,
            animationSpec = tween(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing),
            label = "ringFill",
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .border(1.dp, ringColor, CircleShape)
                .background(ringFill)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = task.done,
                enter = androidx.compose.animation.scaleIn(
                    tween(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing),
                    initialScale = 0.5f,
                ) + fadeIn(tween(ChronicleMotion.MICRO)),
                exit = fadeOut(tween(ChronicleMotion.MICRO)),
            ) {
                ChronicleIcon(
                    R.drawable.ic_check, "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val titleColor by animateColorAsState(
                targetValue = if (task.done) ChronicleExtras.faint else MaterialTheme.colorScheme.onSurface,
                animationSpec = tween(ChronicleMotion.ENTER, easing = ChronicleMotion.StandardEasing),
                label = "title",
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
            task.remindAt?.let { ms ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChronicleIcon(
                        R.drawable.ic_bell, null,
                        tint = if (task.done) ChronicleExtras.faint else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    MetaText(
                        text = ms.asLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)),
                        color = if (task.done) ChronicleExtras.faint else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        ChronicleIcon(
            R.drawable.ic_close, "Remove",
            tint = ChronicleExtras.faint,
            modifier = Modifier
                .size(16.dp)
                .minimumInteractiveComponentSize()
                .clickable(onClick = onDelete),
        )
    }
}
