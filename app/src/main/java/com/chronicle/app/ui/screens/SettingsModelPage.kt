package com.chronicle.app.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.chronicle.app.R
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.FrostedTextField
import com.chronicle.app.ui.design.GhostButton
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.Orb
import com.chronicle.app.ui.design.PrimaryPill
import com.chronicle.app.ui.design.ProgressTrack
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.SecondaryPill
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.SettingsViewModel
import kotlin.math.roundToLong

/** The model detail page — download, unpack, ready, delete. */
@Composable
fun SettingsModelPage(
    viewModel: SettingsViewModel,
    kind: ModelKind,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val modelState = state.models[kind] ?: ModelState.NotInstalled
    val url = when (kind) {
        ModelKind.PARAKEET_STT -> state.parakeetUrl
        ModelKind.LOCAL_LLM -> state.llmUrl
    }
    val nominalMb = when (kind) {
        ModelKind.PARAKEET_STT -> 250L
        ModelKind.LOCAL_LLM -> 2048L
    }
    val nominalSize = when (kind) {
        ModelKind.PARAKEET_STT -> "250 MB"
        ModelKind.LOCAL_LLM -> "2 GB"
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var sourceOpen by remember { mutableStateOf(false) }

    SettingsPage(title = kind.title.substringBefore(" · "), onBack = onBack) {
        Text(
            text = when (kind) {
                ModelKind.PARAKEET_STT -> "On-device speech transcription."
                ModelKind.LOCAL_LLM -> "An on-device mind that reads your entries."
            },
            style = Chron.type.serifItalic,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x6))
        Text(
            text = "≈ $nominalSize",
            style = Chron.type.numeric.copy(fontSize = 26.sp, lineHeight = 32.sp),
            color = Chron.palette.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Runs entirely on this phone",
            style = Chron.type.meta,
            color = Chron.palette.textTertiary,
        )
        Spacer(Modifier.height(Spacing.x8))

        when (val s = modelState) {
            is ModelState.NotInstalled -> {
                PrimaryPill(
                    label = "Download · $nominalSize",
                    onClick = { viewModel.installModel(kind) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Best on Wi-Fi.",
                    style = Chron.type.meta,
                    color = Chron.palette.textTertiary,
                )
            }

            is ModelState.Downloading -> DownloadingBody(
                state = s,
                nominalMb = nominalMb,
                onCancel = { viewModel.cancelModelDownload(kind) },
            )

            is ModelState.Extracting -> {
                ProgressTrack(progress = null)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Unpacking…",
                    style = Chron.type.body,
                    color = Chron.palette.textSecondary,
                )
            }

            is ModelState.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Orb(size = 30.dp)
                    }
                    Spacer(Modifier.width(Spacing.x3))
                    Column {
                        Text(
                            text = "Ready · on this phone",
                            style = Chron.type.control,
                            color = Chron.palette.textPrimary,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.x4))
                GhostButton(
                    label = "Delete",
                    onClick = { confirmDelete = true },
                    color = Chron.palette.warning,
                )
            }

            is ModelState.Failed -> {
                Text(
                    text = s.reason,
                    style = Chron.type.body,
                    color = Chron.palette.warning,
                )
                Spacer(Modifier.height(Spacing.x4))
                SecondaryPill(
                    label = "Try again",
                    onClick = { viewModel.installModel(kind) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.x8))

        // ————— Download source —————
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = "Download source",
                style = Chron.type.section,
                color = Chron.palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                label = if (sourceOpen) "Hide" else "Edit",
                onClick = { sourceOpen = !sourceOpen },
            )
        }
        AnimatedVisibility(
            visible = sourceOpen,
            enter = fadeIn(tween(ChronicleMotion.ENTER)) +
                expandVertically(tween(ChronicleMotion.ENTER)),
            exit = fadeOut(tween(ChronicleMotion.EXIT)) +
                shrinkVertically(tween(ChronicleMotion.EXIT)),
        ) {
            Column {
                FrostedTextField(
                    value = url,
                    onChange = {
                        when (kind) {
                            ModelKind.PARAKEET_STT ->
                                viewModel.setModelUrls(it, state.llmUrl)
                            ModelKind.LOCAL_LLM ->
                                viewModel.setModelUrls(state.parakeetUrl, it)
                        }
                    },
                    placeholder = "Model download URL",
                    singleLine = true,
                )
                if (kind == ModelKind.LOCAL_LLM) {
                    Spacer(Modifier.height(Spacing.x2))
                    Text(
                        text = "The Llama download is licence-gated — accept Meta's " +
                            "licence on huggingface.co, then paste the .task link here.",
                        style = Chron.type.meta,
                        color = Chron.palette.textTertiary,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            GlassSurface(
                level = SurfaceLevel.Elevated,
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.x6)) {
                    Text(
                        text = "Delete ${kind.title.substringBefore(" · ")}?",
                        style = Chron.type.headline,
                        color = Chron.palette.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "You can download it again.",
                        style = Chron.type.body,
                        color = Chron.palette.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.x6))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        GhostButton(label = "Keep", onClick = { confirmDelete = false })
                        GhostButton(
                            label = "Delete",
                            onClick = {
                                confirmDelete = false
                                viewModel.deleteModel(kind)
                            },
                            color = Chron.palette.warning,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingBody(
    state: ModelState.Downloading,
    nominalMb: Long,
    onCancel: () -> Unit,
) {
    // Rolling rate samples for the ETA — shown only once the estimate is stable.
    // Progress emits per buffer read, so cap by time spacing, not count —
    // 40 raw emissions can span <2.5s on a fast link and the ETA would
    // never become visible.
    val samples = remember { mutableStateListOf<Pair<Long, Long>>() }
    LaunchedEffect(state.doneBytes, state.progress) {
        val now = SystemClock.elapsedRealtime()
        if (samples.isEmpty() || now - samples.last().first >= 300) {
            samples += now to state.doneBytes
        }
        while (samples.size > 40) samples.removeAt(0)
    }
    val eta = remember(state.doneBytes, state.progress) {
        computeEta(samples, state)
    }

    val shown by animateFloatAsState(
        targetValue = if (state.progress >= 0f) state.progress.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(ChronicleMotion.MICRO),
        label = "dlProgress",
    )
    ProgressTrack(progress = if (state.progress >= 0f) shown else null)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (state.progress >= 0f) {
                "${(state.progress * 100).toInt()}%"
            } else {
                "Downloading…"
            },
            style = Chron.type.numeric,
            color = Chron.palette.textPrimary,
        )
        Spacer(Modifier.width(Spacing.x3))
        Text(
            text = "${state.doneBytes / 1_000_000} / $nominalMb MB",
            style = Chron.type.numeric,
            color = Chron.palette.textTertiary,
        )
        eta?.let {
            Spacer(Modifier.width(Spacing.x3))
            Text(
                text = it,
                style = Chron.type.numeric,
                color = Chron.palette.textTertiary,
            )
        }
    }
    Spacer(Modifier.height(Spacing.x4))
    SecondaryPill(label = "Cancel", onClick = onCancel)
}

/**
 * Estimate remaining time from observed throughput — never guessed. Returns
 * null until we have ≥2.5s of samples and a positive rate; null forever when
 * the server won't report a total size (progress < 0).
 */
private fun computeEta(
    samples: List<Pair<Long, Long>>,
    state: ModelState.Downloading,
): String? {
    if (state.progress <= 0f || samples.size < 3) return null
    val first = samples.first()
    val last = samples.last()
    val spanMs = last.first - first.first
    if (spanMs < 2500) return null
    val rate = (last.second - first.second).toFloat() / spanMs // bytes per ms
    if (rate <= 0f) return null
    val total = state.doneBytes / state.progress
    val remainingMs = ((total - state.doneBytes) / rate).roundToLong()
    val minutes = remainingMs / 60_000
    return when {
        remainingMs < 45_000 -> "under a minute left"
        else -> "≈ ${minutes + 1} min left"
    }
}


