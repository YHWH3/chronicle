package com.chronicle.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chronicle.app.R
import com.chronicle.app.data.SttEngine
import com.chronicle.app.domain.EntryKind
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.FrostedIconButton
import com.chronicle.app.ui.design.FrostedTextField
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.GradientWaveform
import com.chronicle.app.ui.design.LocalBottomBarPadding
import com.chronicle.app.ui.design.Orb
import com.chronicle.app.ui.design.PrimaryPill
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.SceneEffect
import com.chronicle.app.ui.design.SceneKey
import com.chronicle.app.ui.design.SecondaryPill
import com.chronicle.app.ui.design.SegmentedPills
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.Step
import com.chronicle.app.ui.design.StepList
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.CaptureMode
import com.chronicle.app.ui.vm.KeptIntention
import com.chronicle.app.ui.vm.ProcessingStep
import com.chronicle.app.ui.vm.RecordingState
import com.chronicle.app.ui.vm.TodayUiState
import com.chronicle.app.ui.vm.TodayViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Phase { IDLE, RECORDING, PROCESSING, KEPT }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val p = Chron.palette
    val type = Chron.type

    val phase = when {
        state.recording == RecordingState.RECORDING -> Phase.RECORDING
        state.processing -> Phase.PROCESSING
        state.kept != null -> Phase.KEPT
        else -> Phase.IDLE
    }
    SceneEffect(
        when (phase) {
            Phase.IDLE -> SceneKey.TODAY_IDLE
            Phase.RECORDING -> SceneKey.TODAY_RECORDING
            Phase.PROCESSING -> SceneKey.TODAY_PROCESSING
            Phase.KEPT -> SceneKey.TODAY_KEPT
        },
    )
    val transition = updateTransition(phase, label = "todayPhase")

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
    LaunchedEffect(state.kept) {
        if ((state.kept?.reminders ?: 0) > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(phase) {
        if (phase == Phase.KEPT) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // ————— Phase-driven animation values —————
    val spec = tween<Float>(ChronicleMotion.MORPH, easing = ChronicleMotion.StandardEasing)
    val idleA by transition.animateFloat({ spec }, label = "idleA") { if (it == Phase.IDLE) 1f else 0f }
    val recA by transition.animateFloat({ spec }, label = "recA") { if (it == Phase.RECORDING) 1f else 0f }
    val procA by transition.animateFloat({ spec }, label = "procA") { if (it == Phase.PROCESSING) 1f else 0f }
    val keptA by transition.animateFloat({ spec }, label = "keptA") { if (it == Phase.KEPT) 1f else 0f }
    val cy by transition.animateFloat(
        { tween(ChronicleMotion.MORPH, easing = ChronicleMotion.StandardEasing) },
        label = "cy",
    ) {
        when (it) {
            Phase.IDLE -> 0.52f
            Phase.RECORDING -> 0.78f
            Phase.PROCESSING -> 0.24f
            Phase.KEPT -> 0.24f
        }
    }
    // Write-mode idle has no capture circle.
    val writeIdleA by animateFloatAsState(
        targetValue = if (state.mode == CaptureMode.WRITE) 0f else 1f,
        animationSpec = tween(ChronicleMotion.ENTER),
        label = "writeIdleA",
    )
    val circleAlpha = when (phase) {
        Phase.IDLE -> idleA * writeIdleA
        Phase.RECORDING -> recA
        Phase.PROCESSING -> procA
        Phase.KEPT -> keptA
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val bottomPad = LocalBottomBarPadding.current
        // On 360x640dp-class screens the bottom card needs more room —
        // raise the capture circle a touch.
        val compactHeight = maxHeight < 700.dp

        // Circle size scales with the phone: 112–148dp idle.
        val idleSize = with(density) { (H * 0.20f).toDp().coerceIn(112.dp, 148.dp) }
        val circleSize by transition.animateDp(
            { tween(ChronicleMotion.MORPH, easing = ChronicleMotion.StandardEasing) },
            label = "circleSize",
        ) {
            when (it) {
                Phase.IDLE -> idleSize
                Phase.RECORDING -> 88.dp
                Phase.PROCESSING -> 112.dp
                Phase.KEPT -> 120.dp
            }
        }

        // ————— Header —————
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenMargin, vertical = Spacing.x4),
        ) {
            Text(
                text = LocalDate.now()
                    .format(DateTimeFormatter.ofPattern("EEE · d MMM", Locale.ENGLISH)),
                style = type.meta,
                color = p.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            FrostedIconButton(
                icon = R.drawable.ic_gear,
                contentDescription = "Open details",
                onClick = onOpenSettings,
            )
        }

        // ————— Greeting (fades + soft-blurs out once capture begins) —————
        if (idleA > 0.01f) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (H * 0.08f).toInt()) }
                    .padding(horizontal = Spacing.screenMargin)
                    .graphicsLayer {
                        alpha = idleA
                        translationY = (1f - idleA) * (-36f)
                    },
            ) {
                // Two explicit lines so the break never depends on wrapping.
                val words = greeting().split(" ")
                Text(
                    text = words.dropLast(1).joinToString(" "),
                    style = type.display,
                    color = p.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = words.last(),
                    style = type.display,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "What happened today?",
                    style = type.body,
                    color = p.textSecondary,
                )
            }
        }

        // ————— Speak/Write pills —————
        // Fade while the IME is up so they never sit on top of the field.
        val imeVisible = WindowInsets.isImeVisible
        val pillsImeA by animateFloatAsState(
            targetValue = if (imeVisible) 0f else 1f,
            animationSpec = tween(ChronicleMotion.EXIT),
            label = "pillsIme",
        )
        if (idleA > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenMargin)
                    .graphicsLayer {
                        alpha = idleA * pillsImeA
                        translationY = (1f - idleA) * (-24f)
                    }
                    .offset { IntOffset(0, (H * 0.285f).toInt()) },
                contentAlignment = Alignment.Center,
            ) {
                SegmentedPills(
                    options = listOf("Speak" to R.drawable.ic_mic, "Write" to R.drawable.ic_pen),
                    selectedIndex = if (state.mode == CaptureMode.SPEAK) 0 else 1,
                    onSelect = { i ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setMode(if (i == 0) CaptureMode.SPEAK else CaptureMode.WRITE)
                    },
                    modifier = Modifier.width(250.dp),
                )
            }
        }

        // ————— The morphing capture element —————
        if (circleAlpha > 0.01f) {
            val sizePx = with(density) { circleSize.toPx() }
            val breathAmp = if (phase == Phase.RECORDING && state.amplitudes.isNotEmpty()) {
                state.amplitudes.takeLast(12).average().toFloat()
            } else {
                0f
            }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (W * 0.5f - sizePx / 2f).toInt(),
                            (H * (if (phase == Phase.IDLE && compactHeight) 0.46f else cy)
                                - sizePx / 2f).toInt(),
                        )
                    }
                    .size(circleSize)
                    .graphicsLayer {
                        alpha = circleAlpha
                        val s = 1f + breathAmp * 0.06f
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = phase,
                    animationSpec = tween(ChronicleMotion.MICRO),
                    label = "captureGlyph",
                ) { ph ->
                    when (ph) {
                        Phase.IDLE -> CaptureCircleIdle(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        )
                        Phase.RECORDING -> CaptureCircleStop(onClick = { viewModel.toggleRecording() })
                        Phase.PROCESSING -> Orb(size = circleSize)
                        Phase.KEPT -> CaptureCircleKept()
                    }
                }
            }
        }

        // ————— Idle: caption under the circle —————
        if (idleA > 0.01f && state.mode == CaptureMode.SPEAK) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .alpha(idleA * writeIdleA)
                    .offset {
                        val circleCy = if (compactHeight) 0.46f else cy
                        IntOffset(0, (H * circleCy + with(density) { idleSize.toPx() } / 2 + 24).toInt())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Tap to speak your day",
                    style = type.meta,
                    color = p.textSecondary,
                )
            }
        }

        // ————— Idle: write field —————
        if (idleA > 0.01f && state.mode == CaptureMode.WRITE) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = bottomPad + 8.dp)
                    .alpha(idleA),
            ) {
                FrostedTextField(
                    value = state.draftText,
                    onChange = { viewModel.setDraft(it) },
                    placeholder = "Begin anywhere. The day will shape itself.",
                    minHeight = 168.dp,
                )
                Spacer(Modifier.height(Spacing.x4))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    PrimaryPill(
                        label = "Keep",
                        onClick = {
                            // Dismiss the IME before the processing morph starts.
                            focusManager.clearFocus()
                            keyboard?.hide()
                            viewModel.saveDraft()
                        },
                        enabled = state.draftText.isNotBlank(),
                        loading = state.saving,
                    )
                }
            }
        }

        // ————— Idle: earlier today card —————
        if (idleA > 0.01f) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = bottomPad + 8.dp)
                    .alpha(idleA),
            ) {
                EarlierTodayCard(
                    entries = state.todaysEntries,
                    visible = state.mode == CaptureMode.SPEAK,
                    onOpenJournal = onOpenJournal,
                )
            }
        }

        // ————— Recording —————
        if (recA > 0.01f) {
            RecordingHud(state = state, alpha = recA, topOffset = H * 0.14f)

            // Bottom control row: Discard left, stop circle (the morph)
            // centered with "Tap to keep" beneath it, equal spacer right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPad + 24.dp)
                    .alpha(recA),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(start = Spacing.x8)
                        .width(56.dp),
                ) {
                    FrostedIconButton(
                        icon = R.drawable.ic_close,
                        contentDescription = "Discard",
                        onClick = { viewModel.cancelRecording() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Discard", style = type.meta, color = p.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Spacer matching the morphing circle's footprint.
                    Spacer(Modifier.size(88.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to keep", style = type.meta, color = p.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                // Same footprint as the Discard column so the centre stays centred.
                Spacer(Modifier.padding(end = Spacing.x8).width(56.dp))
            }
        }

        // ————— Processing —————
        if (procA > 0.01f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenMargin)
                    .graphicsLayer { alpha = procA; translationY = (1f - procA) * 40f }
                    .offset { IntOffset(0, (H * 0.40f).toInt()) },
            ) {
                Text(
                    text = when (state.processingStep) {
                        ProcessingStep.TRANSCRIBING -> "Transcribing…"
                        ProcessingStep.READING -> "Looking for intentions…"
                        else -> "Keeping your words…"
                    },
                    style = type.headline,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(28.dp))
                val voice = state.mode == CaptureMode.SPEAK
                val labels = if (voice) {
                    listOf("Transcribing your words", "Saving to your journal", "Looking for intentions")
                } else {
                    listOf("Saving to your journal", "Looking for intentions")
                }
                val activeIndex = when (state.processingStep) {
                    ProcessingStep.TRANSCRIBING -> 0
                    ProcessingStep.SAVING -> if (voice) 1 else 0
                    ProcessingStep.READING -> if (voice) 2 else 1
                    null -> 0
                }
                StepList(
                    steps = labels.mapIndexed { i, label ->
                        Step(
                            label = label,
                            state = when {
                                i < activeIndex -> Step.State.Done
                                i == activeIndex -> Step.State.Active
                                else -> Step.State.Pending
                            },
                        )
                    },
                    modifier = Modifier.width(260.dp),
                )
            }
        }

        // ————— Kept —————
        if (keptA > 0.01f) {
            val kept = state.kept
            if (kept != null) {
                KeptBody(
                    kept = kept,
                    alpha = keptA,
                    topOffset = (H * 0.37f),
                    buttonsOffset = bottomPad + 20.dp,
                    onViewEntry = {
                        viewModel.dismissKept()
                        onOpenJournal()
                    },
                    onAddAnother = { viewModel.dismissKept() },
                )
            }
        }
    }
}

// ————— Morphing circle faces —————

@Composable
private fun CaptureCircleIdle(onClick: () -> Unit) {
    val p = Chron.palette
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .drawBehind {
                drawRect(p.surfaceFrosted)
                // Soft inner glow.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(p.accentSoft.copy(alpha = 0.55f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.42f,
                    ),
                    radius = size.minDimension * 0.42f,
                    center = center,
                )
                // Thin light stroke.
                drawCircle(
                    color = p.surfaceFrostedStroke,
                    radius = size.minDimension / 2f - 1f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(color = p.textPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Record" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_waveform),
            contentDescription = null,
            tint = p.textPrimary,
            modifier = Modifier.fillMaxSize(0.34f),
        )
    }
}

@Composable
private fun CaptureCircleStop(onClick: () -> Unit) {
    val p = Chron.palette
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .drawBehind { drawRect(com.chronicle.app.ui.design.primaryGradient()) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(color = p.textPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Stop" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_stop),
            contentDescription = null,
            tint = com.chronicle.app.ui.design.OnGradient,
            modifier = Modifier.fillMaxSize(0.34f),
        )
    }
}

@Composable
private fun CaptureCircleKept() {
    val p = Chron.palette
    val checkScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = ChronicleMotion.floatSpring,
        label = "checkIn",
    )
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .drawBehind {
                drawRect(p.surfaceFrosted)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(p.accentSoft.copy(alpha = 0.5f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.45f,
                    ),
                    radius = size.minDimension * 0.45f,
                    center = center,
                )
                drawCircle(
                    color = p.surfaceFrostedStroke,
                    radius = size.minDimension / 2f - 1f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = "Kept",
            tint = p.accent,
            modifier = Modifier
                .fillMaxSize(0.34f)
                .graphicsLayer { scaleX = checkScale; scaleY = checkScale },
        )
    }
}

// ————— Recording HUD —————

@Composable
private fun RecordingHud(
    state: TodayUiState,
    alpha: Float,
    topOffset: Float,
) {
    val p = Chron.palette
    val type = Chron.type
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "recPulse")
        .animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                tween(1100, easing = ChronicleMotion.StandardEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "recPulseA",
        )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenMargin)
            .graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * 40f }
            .offset { IntOffset(0, topOffset.toInt()) },
    ) {
        Text(
            text = "Recording…",
            style = type.meta,
            color = p.textSecondary,
            modifier = Modifier.alpha(pulse),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = formatElapsed(state.elapsedMs),
            style = type.timer,
            color = p.textPrimary,
        )
        Spacer(Modifier.height(18.dp))
        GradientWaveform(amplitudes = state.amplitudes, height = 64.dp)
        Spacer(Modifier.height(20.dp))
        TranscriptBubble(state = state)
    }
}

@Composable
private fun TranscriptBubble(state: TodayUiState) {
    val p = Chron.palette
    val type = Chron.type
    val scroll = rememberScrollState()
    if (state.liveTranscript.isNotBlank()) {
        LaunchedEffect(state.liveTranscript) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (state.liveTranscript.isNotBlank()) {
                Column(Modifier.heightIn(max = 108.dp).verticalScroll(scroll)) {
                    Text(
                        text = state.liveTranscript,
                        style = type.body,
                        color = p.textPrimary,
                    )
                }
            } else {
                Text(
                    text = when {
                        state.sttEngine == SttEngine.PARAKEET -> "Transcribed when you stop."
                        !state.speechAvailable -> "Kept as a voice note."
                        else -> "Listening…"
                    },
                    style = type.serifItalic,
                    color = p.textSecondary,
                )
            }
        }
    }
}

// ————— Earlier today —————

@Composable
private fun EarlierTodayCard(
    entries: List<JournalEntry>,
    visible: Boolean,
    onOpenJournal: () -> Unit,
) {
    val p = Chron.palette
    val type = Chron.type
    if (!visible) return
    if (entries.isEmpty()) {
        GlassSurface(
            level = SurfaceLevel.Subtle,
            shape = RoundedCornerShape(Radius.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "Nothing kept yet today.",
                    style = type.body,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Speak or write — Chronicle keeps it and pulls out anything you mean to do.",
                    style = type.bodySmall,
                    color = p.textSecondary,
                )
            }
        }
        return
    }
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.md),
        onClick = onOpenJournal,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Earlier today",
                    style = type.controlSmall,
                    color = p.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = p.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            entries.take(3).forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 5.dp),
                ) {
                    Text(
                        text = entry.createdAt.format(
                            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH),
                        ),
                        style = type.numeric,
                        color = p.textTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    if (entry.kind == EntryKind.VOICE) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = null,
                            tint = p.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = entry.transcript.ifBlank { "A voice note" },
                        style = type.body,
                        color = p.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ————— Kept —————

@Composable
private fun androidx.compose.foundation.layout.BoxScope.KeptBody(
    kept: com.chronicle.app.ui.vm.KeptSummary,
    alpha: Float,
    topOffset: Float,
    buttonsOffset: Dp,
    onViewEntry: () -> Unit,
    onAddAnother: () -> Unit,
) {
    val p = Chron.palette
    val type = Chron.type
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenMargin)
            .graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * 48f }
            .offset { IntOffset(0, topOffset.toInt()) },
    ) {
        Text(
            text = "Kept.",
            style = type.display,
            color = p.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = when (kept.reminders) {
                0 -> "Saved to your journal."
                1 -> "1 reminder set"
                else -> "${kept.reminders} reminders set"
            },
            style = type.body,
            color = if (kept.reminders > 0) p.accent else p.textSecondary,
        )
        if (!kept.reflection.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = kept.reflection,
                style = type.serifItalic,
                color = p.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (kept.intentions.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            kept.intentions.take(3).forEach { intention ->
                IntentionRow(intention)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Read by ${kept.engineLabel}",
            style = type.meta,
            color = p.textTertiary,
        )
    }

    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(horizontal = Spacing.screenMargin)
            .padding(bottom = buttonsOffset)
            .alpha(alpha),
    ) {
        SecondaryPill(
            label = "View entry",
            onClick = onViewEntry,
            modifier = Modifier.weight(1f),
        )
        PrimaryPill(
            label = "Add another",
            onClick = onAddAnother,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IntentionRow(intention: KeptIntention) {
    val p = Chron.palette
    val type = Chron.type
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (intention.remindAt != null) R.drawable.ic_bell else R.drawable.ic_check,
                ),
                contentDescription = null,
                tint = p.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = intention.title,
                style = type.body,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatRemind(intention.remindAt),
                style = type.meta,
                color = p.textTertiary,
            )
        }
    }
}

private fun formatRemind(at: LocalDateTime?): String {
    if (at == null) return "No reminder"
    val today = LocalDate.now()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (at.toLocalDate()) {
        today -> "Today · $time"
        today.plusDays(1) -> "Tomorrow · $time"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH))
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
