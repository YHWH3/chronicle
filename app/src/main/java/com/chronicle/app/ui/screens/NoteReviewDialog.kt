package com.chronicle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chronicle.app.R
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.mind.DeterministicMind
import com.chronicle.app.ui.design.*
import com.chronicle.app.ui.vm.JournalViewModel
import com.chronicle.app.ui.vm.NoteOperation
import com.chronicle.app.ui.vm.NoteReview
import com.chronicle.app.ui.vm.toEpochMs
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteReviewDialog(review: NoteReview, viewModel: JournalViewModel) {
    var showOriginal by remember(review.entryId, review.operation) { mutableStateOf(false) }
    var editingIndex by remember(review.entryId, review.operation) { mutableStateOf<Int?>(null) }
    val cleanup = review.operation == NoteOperation.CLEANUP
    val p = Chron.palette
    Dialog(onDismissRequest = viewModel::dismissReview) {
        GlassSurface(level = SurfaceLevel.Elevated, shape = RoundedCornerShape(Radius.md),
            // Dense text needs an opaque reading plane; the surrounding app
            // remains glass, but note text must not bleed through this preview.
            modifier = Modifier.fillMaxWidth().imePadding()
                .background(p.surfaceElevated.copy(alpha = 1f), RoundedCornerShape(Radius.md))) {
            Column(Modifier.padding(Spacing.x4).heightIn(max = 560.dp)) {
                Text(stringResource(if (cleanup) R.string.note_cleanup_review else R.string.note_tasks_review),
                    style = Chron.type.headline, color = p.textPrimary, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.x3))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item {
                        Text(stringResource(if (cleanup) R.string.note_cleanup_help else R.string.note_task_reminder_help),
                            style = Chron.type.meta, color = p.textSecondary)
                        review.engineLabel?.let { label ->
                            Text(stringResource(R.string.note_processed_by, label), style = Chron.type.meta, color = p.textTertiary)
                        }
                        if (review.engineLabel == DeterministicMind.label || review.usedBasicFallback) {
                            Text(stringResource(if (cleanup) R.string.note_basic_cleanup else R.string.note_basic_tasks),
                                style = Chron.type.meta, color = p.warning)
                        }
                        if (review.busy) Text(stringResource(R.string.note_working), style = Chron.type.body, color = p.textSecondary)
                        review.errorRes?.let { Text(stringResource(it), style = Chron.type.meta, color = p.warning) }
                        if (!review.busy) {
                            GhostButton(stringResource(if (showOriginal) R.string.note_hide_original else R.string.note_show_original),
                                { showOriginal = !showOriginal })
                            if (showOriginal) Text(review.original, style = Chron.type.body, color = p.textSecondary)
                        }
                    }
                    if (!review.busy && cleanup && review.cleaned.isNotBlank()) {
                        item {
                            Text(stringResource(R.string.note_cleaned_draft), style = Chron.type.section, color = p.textSecondary)
                            FrostedTextField(review.cleaned, viewModel::editCleanup, stringResource(R.string.note_placeholder),
                                modifier = Modifier.heightIn(max = 260.dp))
                        }
                    }
                    if (!cleanup && !review.busy) {
                        if (review.tasks.isEmpty() && review.errorRes == null) item {
                            Text(stringResource(R.string.note_no_new_tasks), style = Chron.type.body, color = p.textSecondary)
                        }
                        itemsIndexed(review.tasks) { index, task ->
                            Column(Modifier.padding(vertical = Spacing.x3)) {
                                Text(task.title, style = Chron.type.body, color = p.textPrimary)
                                if (task.suggested) Text(stringResource(R.string.note_possible_task), style = Chron.type.meta, color = p.textTertiary)
                                task.remindAt?.let { at ->
                                    Text(stringResource(R.string.note_detected_time,
                                        at.format(DateTimeFormatter.ofPattern("EEE, d MMM")), phoneTime(at)),
                                        style = Chron.type.meta, color = p.textTertiary)
                                }
                                FlowRow(Modifier.fillMaxWidth()) {
                                    GhostButton(stringResource(R.string.note_add_task), { viewModel.addNoteTask(index, false) }, color = p.accent)
                                    if (task.remindAt?.let { it > LocalDateTime.now() } == true) {
                                        GhostButton(stringResource(R.string.note_add_reminder), { viewModel.addNoteTask(index, true) }, color = p.accent)
                                    }
                                    GhostButton(stringResource(R.string.note_edit_task), { editingIndex = index })
                                    GhostButton(stringResource(R.string.skip), { viewModel.skipNoteTask(index) })
                                }
                                Hairline()
                            }
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth()) {
                    GhostButton(stringResource(if (review.busy) R.string.note_cancel else R.string.note_close), viewModel::dismissReview)
                    if (cleanup && !review.busy && review.cleaned.isNotBlank()) {
                        GhostButton(stringResource(R.string.note_apply_cleanup), viewModel::applyCleanup, color = p.accent)
                    }
                }
            }
        }
    }
    editingIndex?.let { index ->
        review.tasks.getOrNull(index)?.let { task ->
            EditIntentionDialog(TaskEntity(title = task.title, createdAt = 0, remindAt = task.remindAt?.toEpochMs()),
                onDismiss = { editingIndex = null }, onSave = { title, time ->
                    viewModel.editNoteTask(index, title, time)
                    editingIndex = null
                }, heading = stringResource(R.string.note_edit_task))
        }
    }
}
