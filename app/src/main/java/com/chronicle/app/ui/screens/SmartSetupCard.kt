package com.chronicle.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.mind.SmartSetupPhase
import com.chronicle.app.mind.SmartSetupState
import com.chronicle.app.ui.design.*

/** Shared state copy on Today and the selected-model Settings row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartSetupCard(
    state: SmartSetupState,
    onDownload: () -> Unit,
    onDismiss: (() -> Unit)?,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onUseMobile: () -> Unit,
    onOtherModels: (() -> Unit)? = null,
    showReason: Boolean = false,
) {
    if (state.phase == SmartSetupPhase.HIDDEN) return
    GlassSurface(level = SurfaceLevel.Frosted, shape = RoundedCornerShape(Radius.md),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(when (state.phase) {
                SmartSetupPhase.OFFER -> R.string.get_smart_todos
                SmartSetupPhase.WAITING -> R.string.waiting_wifi
                SmartSetupPhase.DOWNLOADING -> R.string.model_progress
                SmartSetupPhase.SETTING_UP -> R.string.almost_ready
                SmartSetupPhase.READY -> R.string.smart_on
                else -> R.string.smart_failed
            }, state.progress), style = Chron.type.control, color = Chron.palette.textPrimary)
            when (state.phase) {
                SmartSetupPhase.OFFER -> {
                    Text(stringResource(when (state.modelId) {
                        "llama3_2_1b" -> R.string.llama_1b_description
                        "llama3_2_3b" -> R.string.llama_3b_description
                        else -> R.string.smart_offer_body
                    }), style = Chron.type.bodySmall,
                        color = Chron.palette.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryPill(stringResource(R.string.download), onDownload)
                        onDismiss?.let { GhostButton(stringResource(R.string.not_now), it) }
                    }
                }
                SmartSetupPhase.WAITING -> {
                    GhostButton(stringResource(R.string.use_mobile_data), onUseMobile)
                    GhostButton(stringResource(R.string.cancel_download), onCancel)
                }
                SmartSetupPhase.DOWNLOADING -> {
                    ProgressTrack(state.progress / 100f)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GhostButton(stringResource(R.string.pause), onPause)
                        GhostButton(stringResource(R.string.cancel_download), onCancel)
                    }
                }
                SmartSetupPhase.FAILED -> {
                    if (showReason) Text(state.reason.orEmpty(), style = Chron.type.meta, color = Chron.palette.warning)
                    GhostButton(stringResource(R.string.try_again), onDownload)
                }
                SmartSetupPhase.SETTING_UP -> ProgressTrack(null)
                else -> {}
            }
            onOtherModels?.let { GhostButton(stringResource(R.string.other_models), it) }
        }
    }
}
