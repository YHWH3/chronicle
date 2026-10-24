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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chronicle.app.R
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.domain.EntryKind
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.media.RecordingService
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
import com.chronicle.app.ui.design.phoneTime
import com.chronicle.app.ui.vm.CaptureMode
import com.chronicle.app.ui.vm.KeptIntention
import com.chronicle.app.ui.vm.ProcessingStep
import com.chronicle.app.ui.vm.RecordingState
import com.chronicle.app.ui.vm.TodayUiState
import com.chronicle.app.ui.vm.TodayViewModel
import kotlinx.coroutines.flow.StateFlow
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
    smartSetup: com.chronicle.app.mind.SmartSetupController? = null,
    onOtherModels: () -> Unit = onOpenSettings,
    onOpenNote: (Long) -> Unit = { onOpenJournal() },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup by (smartSetup?.todayState ?: kotlinx.coroutines.flow.MutableStateFlow(
        com.chronicle.app.mind.SmartSetupState())).collectAsStateWithLifecycle()
    LaunchedEffect(smartSetup) { smartSetup?.onTodayOpened() }
    var editing by remember { mutableStateOf<KeptIntention?>(null) }
    editing?.let { task ->
        EditIntentionDialog(
            task = TaskEntity(id = task.taskId ?: 0L, title = task.title, createdAt = 0L),
            onDismiss = { editing = null },
            onSave = { title, at ->
                task.taskId?.let { viewModel.updateKeptTask(it, title, at) }
                editing = null
            },
            heading = stringResource(R.string.add_time),
        )
    }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val p = Chron.palette
    val type = Chron.type
    // Screen-height check needed before BoxWithConstraints — the cy morph
    // below reads it while the layout itself can't be consulted yet.
    val compactScreen = LocalConfiguration.current.screenHeightDp < 700

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

    // ————— Recording keep-alive + lifecycle (BE-13, FE-02) —————
    val recordingNow = state.recording == RecordingState.RECORDING
    // The FGS holds process + mic while the app is backgrounded or locked —
    // without it API 31+ delivers silence the moment the screen dims. The
    // effect only mirrors recording state; the recorder itself is untouched.
    LaunchedEffect(recordingNow) {
        if (recordingNow) RecordingService.start(context) else RecordingService.stop(context)
    }
    // Keep the screen on for the take; the flag clears when the screen or
    // the recording goes away.
    val view = LocalView.current
    DisposableEffect(recordingNow) {
        if (recordingNow) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Deep sleep past midnight defers the day-flow timer — re-read
            // the wall clock the moment we're visible again.
            if (event == Lifecycle.Event.ON_START) viewModel.refreshDate()
            // ON_STOP deliberately leaves the recorder alone: the FGS keeps
            // capture alive, and lifecycle-bound collection already pauses
            // the waveform reads under STARTED.
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            // On short screens 0.78 drops the stop circle onto the
            // stringResource(R.string.tap_to_keep) caption — ride a little higher.
            Phase.RECORDING -> if (compactScreen) 0.72f else 0.78f
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

        val imeVisible = WindowInsets.isImeVisible
        if (phase != Phase.IDLE) {
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
                contentDescription = stringResource(R.string.settings),
                onClick = onOpenSettings,
            )
        }


        }
        // Idle geometry follows measured content, font scale and system insets.
        if (phase == Phase.IDLE) {
            Column(
                Modifier.fillMaxSize().imePadding()
                    .padding(bottom = if (imeVisible) 12.dp else bottomPad),
            ) {
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
                contentDescription = stringResource(R.string.settings),
                onClick = onOpenSettings,
            )
        }


                if (!imeVisible) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin)) {
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
                    text = stringResource(R.string.today_question),
                    style = type.body,
                    color = p.textSecondary,
                )

                    }
                    Spacer(Modifier.height(Spacing.x4))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SegmentedPills(
                    options = listOf(stringResource(R.string.speak) to R.drawable.ic_mic, stringResource(R.string.write) to R.drawable.ic_pen),
                    selectedIndex = if (state.mode == CaptureMode.SPEAK) 0 else 1,
                    onSelect = { i ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setMode(if (i == 0) CaptureMode.SPEAK else CaptureMode.WRITE)
                    },
                    modifier = Modifier.width(250.dp),
                )

                    }
                }
                if (state.mode == CaptureMode.SPEAK) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Bound the card by the measured space left after the
                    // header. A long list must not consume the mic's hit area.
                    val maxCardHeight = (maxHeight - 96.dp).coerceIn(0.dp, 240.dp)
                    Column(Modifier.fillMaxSize()) {
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A tall note/card at large text sizes can leave less
                        // room than a circle plus caption. Hide the redundant
                        // caption instead of drawing it underneath the card.
                        val showCaption = maxHeight >= 100.dp
                        val size = (maxHeight - if (showCaption) 44.dp else 0.dp)
                            .coerceIn(48.dp, 148.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(size)) {
                                CaptureCircleIdle {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            if (showCaption) {
                                Spacer(Modifier.height(12.dp))
                                Text(stringResource(R.string.tap_to_talk), style = type.meta, color = p.textSecondary)
                            }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin)
                            .heightIn(max = maxCardHeight).verticalScroll(rememberScrollState()),
                    ) {
                        if (setup.phase != com.chronicle.app.mind.SmartSetupPhase.HIDDEN && smartSetup != null) {
                            Column {
                                if (state.todaysEntries.isNotEmpty()) {
                                    com.chronicle.app.ui.design.GhostButton(
                                        pluralStringResource(R.plurals.notes_today, state.todaysEntries.size, state.todaysEntries.size), onOpenJournal)
                                }
                                SmartSetupCard(setup, {
                                    if (com.chronicle.app.mind.SmartModels.find(setup.modelId)?.gated == true)
                                        onOtherModels()
                                    else smartSetup.download()
                                }, smartSetup::dismiss,
                                    smartSetup::pause, smartSetup::cancel, smartSetup::useMobileData, onOtherModels)
                            }
                        } else EarlierTodayCard(state.todaysEntries, true, onOpenJournal)
                    }
                    }
                    }
                } else {
                    if (!imeVisible) Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin)
                            .weight(1f),
                    ) {
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                FrostedTextField(
                    value = state.draftText,
                    onChange = { viewModel.setDraft(it) },
                    placeholder = stringResource(R.string.note_placeholder),
                    minHeight = 168.dp,
                )
                }
                Spacer(Modifier.height(Spacing.x4))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    PrimaryPill(
                        label = stringResource(R.string.keep),
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
            }
        }

        // ————— The morphing capture element —————
        if (phase != Phase.IDLE && phase != Phase.RECORDING && circleAlpha > 0.01f) {
            CaptureElement(
                phase = phase,
                circleAlpha = circleAlpha,
                circleSize = circleSize,
                widthPx = W,
                heightPx = H,
                cy = cy,
                compactHeight = compactHeight,
                amplitudes = viewModel.amplitudes,
                onRecord = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = { viewModel.toggleRecording() },
            )
        }

        // ————— Recording —————
        if (recA > 0.01f) {
            RecordingHud(
                state = state,
                amplitudes = viewModel.amplitudes,
                elapsedMs = viewModel.elapsedMs,
                alpha = recA,
                topOffset = H * 0.14f,
            )

            // Bottom control row: Discard left, stop circle (the morph)
            // centered with stringResource(R.string.tap_to_keep) beneath it, equal spacer right.
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
                        contentDescription = stringResource(R.string.discard),
                        onClick = { viewModel.cancelRecording() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.discard), style = type.meta, color = p.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Spacer matching the morphing circle's footprint.
                    Box(Modifier.size(88.dp)) {
                        CaptureCircleStop { viewModel.toggleRecording() }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.tap_to_keep), style = type.meta, color = p.textSecondary)
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
                        ProcessingStep.TRANSCRIBING -> stringResource(R.string.transcribing)
                        ProcessingStep.READING -> stringResource(R.string.finding_todos)
                        else -> stringResource(R.string.saving_note)
                    },
                    style = type.headline,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(28.dp))
                val voice = state.mode == CaptureMode.SPEAK
                val labels = if (voice) {
                    listOf(stringResource(R.string.transcribing_words), stringResource(R.string.saving_note_step), stringResource(R.string.finding_todos_step))
                } else {
                    listOf(stringResource(R.string.saving_note_step), stringResource(R.string.finding_todos_step))
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
                // Compact screens: start a touch higher and bound the column
                // above the pills so long kept-summaries scroll instead of
                // colliding with the buttons.
                val keptTop = H * (if (compactHeight) 0.33f else 0.37f)
                val buttonsOffset = bottomPad + 20.dp
                KeptBody(
                    kept = kept,
                    alpha = keptA,
                    topOffset = keptTop,
                    maxHeight = (with(density) { (H - keptTop).toDp() } - buttonsOffset - 72.dp)
                        .coerceAtLeast(64.dp),
                    buttonsOffset = buttonsOffset,
                    onViewEntry = {
                        viewModel.dismissKept()
                        onOpenNote(kept.entryId)
                    },
                    onAddAnother = { viewModel.dismissKept() },
                    onDismissSuggestion = { viewModel.dismissSuggestion(it) },
                    onAddSuggestion = { viewModel.acceptSuggestion(it) },
                    onAddTime = { editing = it },
                )
            }
        }
    }
}

// ————— Morphing circle faces —————

/**
 * The capture circle that morphs between phases. The 90ms amplitude flow is
 * collected HERE (FE-08) — the breathing scale recomposes this subtree only,
 * not the screen.
 */
@Composable
private fun CaptureElement(
    phase: Phase,
    circleAlpha: Float,
    circleSize: Dp,
    widthPx: Float,
    heightPx: Float,
    cy: Float,
    compactHeight: Boolean,
    amplitudes: StateFlow<List<Float>>,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    val amps by amplitudes.collectAsStateWithLifecycle()
    val sizePx = with(LocalDensity.current) { circleSize.toPx() }
    val breathAmp = if (phase == Phase.RECORDING && amps.isNotEmpty()) {
        amps.takeLast(12).average().toFloat()
    } else {
        0f
    }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (widthPx * 0.5f - sizePx / 2f).toInt(),
                    (heightPx * (if (phase == Phase.IDLE && compactHeight) 0.46f else cy)
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
                Phase.IDLE -> CaptureCircleIdle(onClick = onRecord)
                Phase.RECORDING -> CaptureCircleStop(onClick = onStop)
                Phase.PROCESSING -> Orb(size = circleSize)
                Phase.KEPT -> CaptureCircleKept()
            }
        }
    }
}

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
            contentDescription = stringResource(R.string.saved),
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
    amplitudes: StateFlow<List<Float>>,
    elapsedMs: StateFlow<Long>,
    alpha: Float,
    topOffset: Float,
) {
    val p = Chron.palette
    val type = Chron.type
    // FE-08 — the 90ms streams recompose this HUD only, not the screen.
    val amps by amplitudes.collectAsStateWithLifecycle()
    val elapsed by elapsedMs.collectAsStateWithLifecycle()
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
            text = stringResource(R.string.recording),
            style = type.meta,
            color = p.textSecondary,
            modifier = Modifier.alpha(pulse),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = formatElapsed(elapsed),
            style = type.timer,
            color = p.textPrimary,
        )
        Spacer(Modifier.height(18.dp))
        GradientWaveform(amplitudes = amps, height = 64.dp)
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
                        state.sttEngine == SttEngine.PARAKEET -> stringResource(R.string.transcribed_when_stopped)
                        !state.speechAvailable -> stringResource(R.string.saved_as_voice_note)
                        else -> stringResource(R.string.listening)
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
                    stringResource(R.string.no_notes_today),
                    style = type.body,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.today_empty_help),
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
                    stringResource(R.string.earlier_today),
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
                        text = phoneTime(entry.createdAt),
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
                        text = entry.transcript.ifBlank { stringResource(R.string.voice_note) },
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
    maxHeight: Dp,
    buttonsOffset: Dp,
    onViewEntry: () -> Unit,
    onAddAnother: () -> Unit,
    onDismissSuggestion: (Long) -> Unit,
    onAddSuggestion: (Long) -> Unit,
    onAddTime: (KeptIntention) -> Unit,
) {
    val p = Chron.palette
    val type = Chron.type
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenMargin)
            .graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * 48f }
            .offset { IntOffset(0, topOffset.toInt()) }
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.saved),
            style = type.display,
            color = p.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (kept.intentions.none { !it.suggested }) {
                stringResource(R.string.note_is_saved)
            } else {
                val count = kept.intentions.count { !it.suggested }
                val summary = pluralStringResource(R.plurals.todos_added, count, count)
                val at = kept.intentions.singleOrNull { !it.suggested }?.remindAt
                if (at == null) summary else summary + stringResource(R.string.reminder_suffix, phoneTime(at))
            },
            style = type.body,
            color = if (kept.intentions.any { !it.suggested }) p.accent else p.textSecondary,
        )
        if (kept.transcriptFailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.transcription_failed_audio_saved),
                style = type.meta,
                color = p.warning,
                textAlign = TextAlign.Center,
            )
        }
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
        val definite = kept.intentions.filter { !it.suggested }
        val suggestions = kept.intentions.filter { it.suggested }
        if (definite.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            definite.forEach { intention ->
                IntentionRow(intention, onAddTime = { onAddTime(intention) })
                Spacer(Modifier.height(8.dp))
            }
        }
        // Weak-intent parses (BE-10) sit apart under a quiet heading, each
        // with a dismiss affordance — they're kept, not committed to.
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.maybe_todos),
                style = type.meta,
                color = p.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            suggestions.forEach { intention ->
                IntentionRow(
                    intention = intention,
                    onAddTime = { onAddTime(intention) },
                    onAdd = intention.taskId?.let { id -> { onAddSuggestion(id) } },
                    onDismiss = intention.taskId?.let { id ->
                        { onDismissSuggestion(id) }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))

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
            label = stringResource(R.string.open_note),
            onClick = onViewEntry,
            modifier = Modifier.weight(1f),
        )
        PrimaryPill(
            label = stringResource(R.string.new_note),
            onClick = onAddAnother,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IntentionRow(
    intention: KeptIntention,
    onAddTime: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val p = Chron.palette
    val type = Chron.type
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.sm),
        onClick = if (intention.remindAt == null) onAddTime else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(
                    if (intention.remindAt != null) R.drawable.ic_bell else R.drawable.ic_check,
                ),
                contentDescription = null,
                tint = if (intention.suggested) p.textTertiary else p.accent,
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
            if (intention.remindAt != null) {
                Spacer(Modifier.width(10.dp))
                Text(formatRemind(intention.remindAt), style = type.meta, color = p.textTertiary)
            }
        }
        if (onAdd != null || onDismiss != null) {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                onAdd?.let { com.chronicle.app.ui.design.GhostButton(stringResource(R.string.add), it) }
                onDismiss?.let { com.chronicle.app.ui.design.GhostButton(stringResource(R.string.skip), it) }
            }
        }
        }
    }
}

@Composable
private fun formatRemind(at: LocalDateTime?): String {
    if (at == null) return ""
    val today = LocalDate.now()
    val time = phoneTime(at)
    return when (at.toLocalDate()) {
        today -> "Today · $time"
        today.plusDays(1) -> "Tomorrow · $time"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) + " · $time"
    }
}

@Composable
private fun greeting(): String {
    val hour = LocalDateTime.now().hour
    return when {
        hour < 5 -> stringResource(R.string.quiet_hour)
        hour < 12 -> stringResource(R.string.good_morning)
        hour < 17 -> stringResource(R.string.good_afternoon)
        else -> stringResource(R.string.good_evening)
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
