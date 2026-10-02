package com.chronicle.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.chronicle.app.R
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.ChoicePill
import com.chronicle.app.ui.design.CircleCheck
import com.chronicle.app.ui.design.FrostedTextField
import com.chronicle.app.ui.design.GhostButton
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.Hairline
import com.chronicle.app.ui.design.LargeActionCircle
import com.chronicle.app.ui.design.LocalBottomBarPadding
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.design.SwipeToDeleteRow
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.ReminderChoice
import com.chronicle.app.ui.vm.TasksViewModel
import com.chronicle.app.ui.vm.asLocalDateTime
import com.chronicle.app.ui.vm.toEpochMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(viewModel: TasksViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val p = Chron.palette
    val type = Chron.type
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fieldFocused by remember { mutableStateOf(false) }
    // Rows mid-completion: checked look now, move to Done after the beat.
    var completing by remember { mutableStateOf(setOf<Long>()) }
    // The intention open in the edit sheet, if any.
    var editing by remember { mutableStateOf<TaskEntity?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun submit() {
        if (state.draft.isBlank()) return
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
    }

    fun toggle(task: TaskEntity) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (task.done) {
            viewModel.toggleDone(task)
        } else if (task.id !in completing) {
            // Let the check fill and the title strike through first.
            completing = completing + task.id
            scope.launch {
                delay(350)
                completing = completing - task.id
                viewModel.toggleDone(task)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(
            start = Spacing.screenMargin, end = Spacing.screenMargin,
            top = Spacing.x4, bottom = LocalBottomBarPadding.current,
        ),
    ) {
        item {
            Text(
                text = "Intentions",
                style = type.title,
                color = p.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "What you mean to do.",
                style = type.serifItalic,
                color = p.textSecondary,
            )
            Spacer(Modifier.height(Spacing.x6))
        }

        // ————— Add —————
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FrostedTextField(
                    value = state.draft,
                    onChange = viewModel::setDraft,
                    placeholder = "Add an intention…",
                    singleLine = true,
                    minHeight = 52.dp,
                    onFocusChange = { fieldFocused = it },
                    imeAction = ImeAction.Done,
                    onImeAction = { submit() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                LargeActionCircle(
                    icon = R.drawable.ic_plus,
                    contentDescription = "Add intention",
                    onClick = { submit() },
                    size = 48.dp,
                    enabled = state.draft.isNotBlank() && !state.adding,
                )
            }
            AnimatedVisibility(
                visible = fieldFocused || state.draft.isNotBlank(),
                enter = fadeIn(tween(ChronicleMotion.MICRO)) +
                    expandVertically(tween(ChronicleMotion.MICRO)),
                exit = fadeOut(tween(ChronicleMotion.MICRO)) +
                    shrinkVertically(tween(ChronicleMotion.MICRO)),
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReminderChoice.entries.forEach { choice ->
                        ChoicePill(
                            label = choice.label,
                            selected = choice == state.draftReminder,
                            onClick = {
                                viewModel.setReminderChoice(
                                    if (choice == state.draftReminder) ReminderChoice.NONE else choice,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.open.isEmpty() && state.done.isEmpty()) {
            item {
                Spacer(Modifier.height(80.dp))
                Text(
                    text = "Nothing you mean to do, yet.",
                    style = type.headline,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Say \u201Cremind me to…\u201D on Today, or add one here.",
                    style = type.body,
                    color = p.textSecondary,
                )
            }
        }

        // ————— Open: one frosted surface, hairlines between rows —————
        if (state.open.isNotEmpty()) {
            item(key = "open-card") {
                Column(Modifier.animateItem()) {
                    Spacer(Modifier.height(Spacing.x8))
                    GlassSurface(
                        level = SurfaceLevel.Frosted,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            state.open.forEachIndexed { index, task ->
                                // Keyed by the stable task id — the swipe
                                // state must follow the task, not the row's
                                // position, or a delete leaves another row
                                // holding a half-swiped state.
                                key(task.id) {
                                    SwipeToDeleteRow(
                                        onDelete = { viewModel.delete(task) },
                                    ) {
                                        IntentionRow(
                                            task = task,
                                            visuallyDone = task.id in completing,
                                            onToggle = { toggle(task) },
                                            onDelete = { viewModel.delete(task) },
                                            onEdit = { editing = task },
                                        )
                                    }
                                }
                                if (index < state.open.lastIndex) Hairline()
                            }
                        }
                    }
                }
            }
        }

        // ————— Done: floating label, rows plainly on the mist —————
        if (state.done.isNotEmpty()) {
            item(key = "done-label") {
                Text(
                    text = "Done",
                    style = type.section,
                    color = p.textSecondary,
                    modifier = Modifier
                        .animateItem()
                        .padding(top = Spacing.x8, bottom = Spacing.x2),
                )
            }
            items(state.done, key = { "done-${it.id}" }) { task ->
                // Same stable-id keying inside the keyed lazy item — the
                // item key scopes the row, this scopes the swipe state.
                key(task.id) {
                    SwipeToDeleteRow(
                        onDelete = { viewModel.delete(task) },
                        modifier = Modifier.animateItem(),
                    ) {
                        IntentionRow(
                            task = task,
                            visuallyDone = true,
                            onToggle = { toggle(task) },
                            onDelete = { viewModel.delete(task) },
                            onEdit = { editing = task },
                        )
                    }
                }
            }
        }
    }

    editing?.let { task ->
        EditIntentionDialog(
            task = task,
            onDismiss = { editing = null },
            onSave = { title, remindAt ->
                viewModel.updateTask(task.id, title, remindAt)
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IntentionRow(
    task: TaskEntity,
    visuallyDone: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val p = Chron.palette
    val type = Chron.type
    val done = task.done || visuallyDone
    // Past its reminder time and still open — quiet signal, no alarm colour.
    val overdue = !done && task.remindAt != null &&
        task.remindAt < System.currentTimeMillis()
    val titleColor by animateColorAsState(
        targetValue = if (done) p.textTertiary else p.textPrimary,
        animationSpec = tween(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing),
        label = "title",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Edit intention") { onEdit(); true },
                    CustomAccessibilityAction("Delete intention") { onDelete(); true },
                )
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = p.textPrimary),
                onClick = onEdit,
                onLongClick = onEdit,
            )
            .padding(horizontal = Spacing.x4, vertical = 8.dp),
    ) {
        CircleCheck(
            checked = done,
            contentDescription = if (done) "Mark not done" else "Mark done",
            onToggle = onToggle,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = type.body,
                color = titleColor,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            val meta = buildList {
                task.remindAt?.let { add(formatReminder(it)) }
                if (overdue) add("overdue")
                if (task.sourceEntryId != null) add("From your journal")
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.remindAt != null) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clock),
                            contentDescription = null,
                            tint = if (overdue) p.warning else p.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = meta.joinToString(" · "),
                        style = type.meta,
                        color = if (overdue) p.warning else p.textTertiary,
                    )
                }
            }
        }
    }
}

/**
 * The edit sheet — same frosted-dialog pattern as the model-delete
 * confirmation. Title plus a reminder pick; leaving the pills untouched
 * keeps the stored reminder, "No reminder" clears it.
 */
@Composable
private fun EditIntentionDialog(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, remindAt: Long?) -> Unit,
) {
    val p = Chron.palette
    val type = Chron.type
    var title by remember { mutableStateOf(task.title) }
    // Null = pills untouched — the stored remindAt survives the save.
    // An explicit "No reminder" pick resolves to null and clears it.
    var reminderPick by remember { mutableStateOf<ReminderChoice?>(null) }
    fun resolvedRemindAt(): Long? {
        val pick = reminderPick ?: return task.remindAt
        return pick.resolve(LocalDateTime.now())?.toEpochMs()
    }
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            level = SurfaceLevel.Elevated,
            shape = RoundedCornerShape(Radius.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.x6)) {
                Text(
                    text = "Edit intention",
                    style = type.headline,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(Spacing.x4))
                FrostedTextField(
                    value = title,
                    onChange = { title = it },
                    placeholder = "Intention",
                    singleLine = true,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (title.isNotBlank()) {
                            onSave(title, resolvedRemindAt())
                        }
                    },
                )
                if (reminderPick == null && task.remindAt != null) {
                    Spacer(Modifier.height(Spacing.x3))
                    Text(
                        text = "Reminder — " + formatReminder(task.remindAt),
                        style = type.meta,
                        color = p.textTertiary,
                    )
                }
                Spacer(Modifier.height(Spacing.x3))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReminderChoice.entries.forEach { choice ->
                        ChoicePill(
                            label = choice.label,
                            selected = choice == reminderPick,
                            onClick = {
                                reminderPick =
                                    if (reminderPick == choice) null else choice
                            },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.x6))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GhostButton(label = "Cancel", onClick = onDismiss)
                    GhostButton(
                        label = "Save",
                        onClick = {
                            if (title.isBlank()) return@GhostButton
                            onSave(title, resolvedRemindAt())
                        },
                        color = p.accent,
                    )
                }
            }
        }
    }
}

private fun formatReminder(ms: Long): String {
    val at = ms.asLocalDateTime()
    val today = LocalDate.now()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (at.toLocalDate()) {
        today -> "Today · $time"
        today.plusDays(1) -> "Tomorrow · $time"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH))
    }
}
