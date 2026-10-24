package com.chronicle.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chronicle.app.R
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.mind.hasUnmeteredNetwork
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.mind.OPENROUTER_FREE_MODELS
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.ChoicePill
import com.chronicle.app.ui.design.FrostedTextField
import com.chronicle.app.ui.design.GhostButton
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.InkPalette
import com.chronicle.app.ui.design.LocalPalette
import com.chronicle.app.ui.design.OptionCard
import com.chronicle.app.ui.design.PaperPalette
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.SceneKey
import com.chronicle.app.ui.design.ScenePreview
import com.chronicle.app.ui.design.ScenePreviewSplit
import com.chronicle.app.ui.design.SoftSwitch
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.design.primaryGradient
import com.chronicle.app.ui.nav.Routes
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.SettingsUiState
import com.chronicle.app.ui.vm.SettingsViewModel
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val panelEnter = fadeIn(tween(ChronicleMotion.ENTER, easing = ChronicleMotion.EnterEasing)) +
    expandVertically(tween(ChronicleMotion.ENTER, easing = ChronicleMotion.EnterEasing))
private val panelExit = fadeOut(tween(ChronicleMotion.EXIT, easing = ChronicleMotion.ExitEasing)) +
    shrinkVertically(tween(ChronicleMotion.EXIT, easing = ChronicleMotion.ExitEasing))

// ————— Appearance —————

@Composable
fun SettingsAppearancePage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(title = "Appearance", onBack = onBack) {
        Text(
            text = "Ink is for the evening; Paper for morning light.",
            style = Chron.type.serifItalic,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x6))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            ThemeTile(
                label = "Ink",
                dark = true,
                selected = state.themeMode == ThemeMode.INK,
                onClick = { viewModel.setThemeMode(ThemeMode.INK) },
            )
            ThemeTile(
                label = "Paper",
                dark = false,
                selected = state.themeMode == ThemeMode.PAPER,
                onClick = { viewModel.setThemeMode(ThemeMode.PAPER) },
            )
            ThemeTile(
                label = "Follow the day",
                dark = null,
                selected = state.themeMode == ThemeMode.SYSTEM,
                onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
            )
        }
    }
}

/**
 * One large preview tile: a still of the real TODAY_IDLE scene in the
 * tile's palette, a tiny serif greeting and a small pill — a truthful
 * miniature of the theme. [dark] null paints the Paper/Ink diagonal split.
 */
@Composable
private fun ThemeTile(
    label: String,
    dark: Boolean?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    val accent = Chron.palette.accent
    // The tile previews with its own palette — labels read as they would live.
    val tilePalette = if (dark == false) PaperPalette else InkPalette
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(shape)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = tilePalette.textPrimary),
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
        ) {
            if (dark == null) {
                ScenePreviewSplit(
                    key = SceneKey.TODAY_IDLE,
                    first = false, second = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ScenePreview(
                    key = SceneKey.TODAY_IDLE,
                    dark = dark,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CompositionLocalProvider(LocalPalette provides tilePalette) {
                Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                    Text(
                        text = if (dark == true) "Good evening." else "Good morning.",
                        style = Chron.type.serifItalic.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                        ),
                        color = tilePalette.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    // A small pill, as the capture affordance would sit.
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(percent = Radius.pill))
                            .drawBehind { drawRect(primaryGradient()) },
                    )
                }
            }
            // Selected ring + check.
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(1.5.dp, accent, shape),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .drawBehind { drawCircle(accent) },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = Chron.type.control,
            color = if (selected) Chron.palette.textPrimary else Chron.palette.textSecondary,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

// ————— Daily nudge —————

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsNudgePage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The switch state is already written; prompt is best-effort. */ }

    val toggleNudge: (Boolean) -> Unit = { on ->
        // Both fields go through the atomic setter — a quick toggle+time
        // pick can never split a stale pair into storage (FE-11).
        viewModel.setDailyNudge(on, state.dailyNudgeMinutes)
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SettingsPage(title = "Daily nudge", onBack = onBack) {
        GlassSurface(
            level = SurfaceLevel.Frosted,
            shape = RoundedCornerShape(Radius.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(Spacing.x4),
            ) {
                Text(
                    text = "A gentle reminder to write, once a day.",
                    style = Chron.type.body,
                    color = Chron.palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.x4))
                SoftSwitch(
                    checked = state.dailyNudgeEnabled,
                    onChange = toggleNudge,
                )
            }
        }
        AnimatedVisibility(
            visible = state.dailyNudgeEnabled,
            enter = panelEnter,
            exit = panelExit,
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.x3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    7 * 60 to "07:00", 12 * 60 + 30 to "12:30",
                    18 * 60 to "18:00", 20 * 60 to "20:00", 21 * 60 + 30 to "21:30",
                ).forEach { (mins, label) ->
                    ChoicePill(
                        label = label,
                        selected = state.dailyNudgeMinutes == mins,
                        onClick = {
                            viewModel.setDailyNudge(state.dailyNudgeEnabled, mins)
                        },
                        textStyle = Chron.type.numeric,
                    )
                }
            }
        }
    }
}

// ————— The Ear —————

@Composable
fun SettingsEarPage(viewModel: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(title = stringResource(R.string.voice_to_text), onBack = onBack) {
        Text(
            text = stringResource(R.string.voice_to_text_description),
            style = Chron.type.serifItalic,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x6))
        OptionCard(
            title = stringResource(R.string.phone_voice_typing),
            subtitle = stringResource(R.string.settings_built_into_android_nothing_to_download),
            meta = if (state.systemSttAvailable) {
                stringResource(R.string.voice_no_download)
            } else {
                stringResource(R.string.voice_unavailable)
            },
            selected = state.sttEngine == SttEngine.SYSTEM,
            onClick = { viewModel.setSttEngine(SttEngine.SYSTEM) },
        )
        Spacer(Modifier.height(Spacing.x3))
        Column {
            OptionCard(
                title = stringResource(R.string.better_voice_typing),
                subtitle = stringResource(R.string.better_voice_description),
                // Measured archive size (HEAD-verified) — ModelKind.nominalBytes.
                meta = stringResource(R.string.voice_download_size, ModelKind.PARAKEET_STT.nominalBytes / 1_000_000),
                selected = state.sttEngine == SttEngine.PARAKEET,
                onClick = { viewModel.setSttEngine(SttEngine.PARAKEET) },
            )
            AnimatedVisibility(
                visible = state.sttEngine == SttEngine.PARAKEET,
                enter = panelEnter,
                exit = panelExit,
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.x3))
                    ModelStatusRow(
                        kind = ModelKind.PARAKEET_STT,
                        state = state.models[ModelKind.PARAKEET_STT.packId] ?: ModelState.NotInstalled,
                        onOpen = { onOpen(Routes.settingsModel(ModelKind.PARAKEET_STT)) },
                    )
                    if (state.models[ModelKind.PARAKEET_STT.packId] !is ModelState.Installed) {
                        Spacer(Modifier.height(Spacing.x2))
                        Text(
                            text = stringResource(R.string.phone_voice_until_ready),
                            style = Chron.type.meta,
                            color = Chron.palette.textTertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMindPage(viewModel: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup by (viewModel.smartSetup?.state ?: kotlinx.coroutines.flow.MutableStateFlow(
        com.chronicle.app.mind.SmartSetupState())).collectAsStateWithLifecycle()
    val ram = viewModel.smartSetup?.totalRam ?: 0L
    var licenceModel by remember { mutableStateOf<com.chronicle.app.mind.SmartModel?>(null) }
    var deleting by remember { mutableStateOf<com.chronicle.app.mind.SmartModel?>(null) }
    licenceModel?.let { model ->
        HfLicenceDialog(model, viewModel, onDismiss = { licenceModel = null })
    }
    deleting?.let { model ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { deleting = null }) {
            GlassSurface(level = SurfaceLevel.Elevated, shape = RoundedCornerShape(Radius.md)) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.delete_model_question, model.displayName), style = Chron.type.headline)
                    Text(stringResource(R.string.delete_model_help), style = Chron.type.body)
                    GhostButton(stringResource(R.string.delete_model), {
                        viewModel.deleteSmartModel(model.id); deleting = null
                    })
                    GhostButton(stringResource(R.string.cancel_download), { deleting = null })
                }
            }
        }
    }
    SettingsPage(title = stringResource(R.string.smart_todos), onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wifi_only), style = Chron.type.control, modifier = Modifier.weight(1f))
            SoftSwitch(checked = !state.allowMobileData,
                onChange = { viewModel.smartSetup?.setMobileData(!it) })
        }
        Spacer(Modifier.height(Spacing.x3))
        com.chronicle.app.mind.SmartModels.all.forEach { model ->
            val supported = model.supportedBy(ram)
            val status = state.models[model.packId] ?: ModelState.NotInstalled
            val selected = state.mindBackend in listOf(MindBackend.AUTO, MindBackend.ON_DEVICE) &&
                state.selectedSmartModelId == model.id
            OptionCard(
                title = model.displayName,
                subtitle = smartModelDescription(model.id),
                meta = if (supported) stringResource(R.string.offline)
                    else stringResource(R.string.more_memory_needed),
                selected = selected, enabled = supported,
                onClick = {
                    viewModel.selectSmartModel(model.id)
                    if (model.gated && status != ModelState.Installed) licenceModel = model
                },
            )
            if (supported && selected) {
                Spacer(Modifier.height(Spacing.x2))
                val controller = viewModel.smartSetup
                if (controller != null) {
                    val shown = if (setup.phase == com.chronicle.app.mind.SmartSetupPhase.HIDDEN)
                        setup.copy(phase = com.chronicle.app.mind.SmartSetupPhase.READY) else setup
                    SmartSetupCard(shown, {
                        if (model.gated && status != ModelState.Installed) licenceModel = model
                        else controller.download()
                    }, null, controller::pause, controller::cancel, controller::useMobileData,
                        showReason = true)
                }
            }
            if (status == ModelState.Installed) {
                GhostButton(stringResource(R.string.delete_model), { deleting = model })
            }
            Spacer(Modifier.height(Spacing.x3))
        }
        MindCard(
            backend = MindBackend.QUIET_PARSER,
            title = stringResource(R.string.basic),
            subtitle = stringResource(R.string.basic_description),
            meta = stringResource(R.string.offline), state = state, viewModel = viewModel,
        )
    }
}

@Composable
private fun smartModelDescription(id: String): String = stringResource(when (id) {
    "llama3_2_1b" -> R.string.llama_1b_description
    "llama3_2_3b" -> R.string.llama_3b_description
    else -> R.string.qwen_description
})

// ————— The Mind —————

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAdvancedPage(viewModel: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(title = stringResource(R.string.advanced), onBack = onBack) {
        if (com.chronicle.app.BuildConfig.DEBUG) {
            GhostButton(label = stringResource(R.string.test_smart_mode),
                onClick = { onOpen(Routes.SETTINGS_SMART_TEST) })
            Spacer(Modifier.height(Spacing.x4))
        }
        if (state.hfSignedIn) {
            GhostButton(stringResource(R.string.hf_sign_out), { viewModel.signOutHf() })
            Text(stringResource(R.string.hf_sign_out_help), style = Chron.type.meta)
        }
        Text(
            text = stringResource(R.string.advanced_help),
            style = Chron.type.serifItalic,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x6))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            MindCard(
                backend = MindBackend.QUIET_PARSER,
                title = stringResource(R.string.basic),
                subtitle = stringResource(R.string.basic_description_short),
                meta = stringResource(R.string.settings_on_this_phone_no_internet_nothing_leaves),
                state = state,
                viewModel = viewModel,
            )
            MindCard(
                backend = MindBackend.ON_DEVICE,
                title = stringResource(R.string.custom_model),
                // Bring-your-own bundle — typical Llama 3.2 3B .task builds run
                // ≈ 2–3 GB; the model page shows the real size from Content-Length.
                subtitle = stringResource(R.string.custom_model_description),
                meta = stringResource(R.string.settings_on_this_phone_no_internet_nothing_leaves),
                state = state,
                viewModel = viewModel,
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.x3))
                    ModelStatusRow(
                        kind = ModelKind.LOCAL_LLM,
                        state = state.models[ModelKind.LOCAL_LLM.packId] ?: ModelState.NotInstalled,
                        onOpen = { onOpen(Routes.settingsModel(ModelKind.LOCAL_LLM)) },
                    )
                    if (state.models[ModelKind.LOCAL_LLM.packId] !is ModelState.Installed) {
                        Spacer(Modifier.height(Spacing.x2))
                        Text(
                            text = stringResource(R.string.basic_until_ready),
                            style = Chron.type.meta,
                            color = Chron.palette.textTertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            MindCard(
                backend = MindBackend.OPENROUTER,
                title = stringResource(R.string.settings_openrouter),
                subtitle = stringResource(R.string.settings_free_cloud_models_needs_an_api_key),
                meta = stringResource(R.string.settings_openrouter_s_servers_needs_internet_entry_text_is_sent),
                state = state,
                viewModel = viewModel,
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.x3))
                    SecretField(
                        value = state.openRouterKey,
                        onChange = { viewModel.setOpenRouter(it, state.openRouterModel) },
                        placeholder = stringResource(R.string.settings_openrouter_api_key_sk_or),
                    )
                    Spacer(Modifier.height(Spacing.x3))
                    Text(
                        text = stringResource(R.string.settings_free_models),
                        style = Chron.type.meta,
                        color = Chron.palette.textTertiary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.height(Spacing.x2))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OPENROUTER_FREE_MODELS.forEach { model ->
                            ChoicePill(
                                label = model.removeSuffix(":free"),
                                selected = state.openRouterModel == model,
                                onClick = {
                                    viewModel.setOpenRouter(state.openRouterKey, model)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.x3))
                    CustomModelField(
                        current = state.openRouterModel,
                        onCommit = { viewModel.setOpenRouter(state.openRouterKey, it) },
                    )
                    if (state.openRouterKey.isBlank()) {
                        Spacer(Modifier.height(Spacing.x2))
                        Text(
                            text = stringResource(R.string.basic_without_key),
                            style = Chron.type.meta,
                            color = Chron.palette.textTertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            MindCard(
                backend = MindBackend.CUSTOM,
                title = stringResource(R.string.settings_custom_endpoint),
                subtitle = stringResource(R.string.settings_any_openai_compatible_server),
                meta = stringResource(R.string.settings_your_endpoint_needs_internet_entry_text_is_sent),
                state = state,
                viewModel = viewModel,
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.x3))
                    FrostedTextField(
                        value = state.customBaseUrl,
                        onChange = {
                            viewModel.setCustomEndpoint(
                                it.trim(), state.customModel, state.customKey,
                            )
                        },
                        placeholder = stringResource(R.string.settings_base_url_https_api_openai_com_v1),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(Spacing.x2))
                    Text(
                        text = stringResource(R.string.settings_https_required_cleartext_endpoints_are_refused),
                        style = Chron.type.meta,
                        color = Chron.palette.textTertiary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.height(Spacing.x3))
                    FrostedTextField(
                        value = state.customModel,
                        onChange = {
                            viewModel.setCustomEndpoint(state.customBaseUrl, it, state.customKey)
                        },
                        placeholder = stringResource(R.string.settings_model_gpt_4o_mini),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(Spacing.x3))
                    SecretField(
                        value = state.customKey,
                        onChange = {
                            viewModel.setCustomEndpoint(state.customBaseUrl, state.customModel, it)
                        },
                        placeholder = stringResource(R.string.settings_api_key_optional_for_some_endpoints),
                    )
                    if (state.customBaseUrl.isBlank() || state.customModel.isBlank()) {
                        Spacer(Modifier.height(Spacing.x2))
                        Text(
                            text = stringResource(R.string.basic_without_endpoint),
                            style = Chron.type.meta,
                            color = Chron.palette.textTertiary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MindCard(
    backend: MindBackend,
    title: String,
    subtitle: String,
    meta: String,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    reveal: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column {
        OptionCard(
            title = title,
            subtitle = subtitle,
            meta = meta,
            selected = state.mindBackend == backend,
            onClick = { viewModel.setMindBackend(backend) },
        )
        if (reveal != null) {
            AnimatedVisibility(
                visible = state.mindBackend == backend,
                enter = panelEnter,
                exit = panelExit,
            ) {
                Column(content = reveal)
            }
        }
    }
}

private val MODEL_ID_REGEX = Regex("[a-z0-9._/:-]+")

/**
 * Free-entry OpenRouter model id — the chips cover the free tier, this
 * field reaches the rest of the catalogue. A draft is only committed once
 * it looks like a slug (vendor/model[:tag]); while it's malformed the hint
 * explains the shape rather than saving noise.
 */
@Composable
private fun CustomModelField(current: String, onCommit: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val clean = draft.trim()
    val valid = clean.matches(MODEL_ID_REGEX)
    FrostedTextField(
        value = draft,
        onChange = {
            draft = it
            val candidate = it.trim()
            if (candidate.matches(MODEL_ID_REGEX)) onCommit(candidate)
        },
        placeholder = stringResource(R.string.settings_custom_model_id_vendor_model_tag),
        singleLine = true,
    )
    Spacer(Modifier.height(Spacing.x2))
    Text(
        text = when {
            draft.isNotBlank() && !valid ->
                "A model id is lowercase letters, digits and - . _ / : — like vendor/model."
            current.isNotBlank() && current !in OPENROUTER_FREE_MODELS ->
                "Using $current."
            else ->
                "Or type any OpenRouter model id — the chips are only the free ones."
        },
        style = Chron.type.meta,
        color = Chron.palette.textTertiary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** A masked field with a Show/Hide toggle. */
@Composable
internal fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    var reveal by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FrostedTextField(
            value = value,
            onChange = onChange,
            placeholder = placeholder,
            singleLine = true,
            secret = !reveal,
            modifier = Modifier.weight(1f),
        )
        GhostButton(
            label = if (reveal) "Hide" else "Show",
            onClick = { reveal = !reveal },
        )
    }
}
