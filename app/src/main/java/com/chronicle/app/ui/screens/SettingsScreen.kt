package com.chronicle.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.mind.OPENROUTER_FREE_MODELS
import com.chronicle.app.ui.components.ChronicleIcon
import com.chronicle.app.ui.components.Hairline
import com.chronicle.app.ui.components.LabelledRule
import com.chronicle.app.ui.components.MetaText
import com.chronicle.app.ui.theme.ChronicleExtras
import com.chronicle.app.ui.theme.SerifItalic
import com.chronicle.app.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Status row re-reads on next composition. */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChronicleIcon(
                R.drawable.ic_back, "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(18.dp))
            MetaText("Details")
        }
        Spacer(Modifier.height(32.dp))
        Text("The quiet particulars", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(36.dp))

        LabelledRule("Appearance")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.INK to "Ink", ThemeMode.PAPER to "Paper", ThemeMode.SYSTEM to "Follow the day")
                .forEach { (mode, label) ->
                    val selected = state.themeMode == mode
                    Box(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .border(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.hairline,
                                MaterialTheme.shapes.small,
                            )
                            .clickable { viewModel.setThemeMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        MetaText(
                            text = label,
                            color = if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
                        )
                    }
                }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Ink is for the evening; Paper for morning light.",
            style = SerifItalic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(34.dp))
        LabelledRule("A daily nudge")
        Spacer(Modifier.height(18.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ask me about the day", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            QuietSwitch(
                checked = state.dailyNudgeEnabled,
                onChange = { on ->
                    viewModel.setDailyNudge(on)
                    if (on && Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
        if (state.dailyNudgeEnabled) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    7 * 60 to "07:00", 12 * 60 + 30 to "12:30",
                    18 * 60 to "18:00", 20 * 60 to "20:00", 21 * 60 + 30 to "21:30",
                ).forEach { (mins, label) ->
                    val selected = state.dailyNudgeMinutes == mins
                    Box(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .border(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.hairline,
                                MaterialTheme.shapes.small,
                            )
                            .clickable { viewModel.setDailyNudgeMinutes(mins) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        MetaText(
                            text = label,
                            color = if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(34.dp))
        LabelledRule("The ear — speech to text")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SttEngine.entries.forEach { engine ->
                ChoiceChip(
                    label = engine.title,
                    selected = state.sttEngine == engine,
                    onClick = { viewModel.setSttEngine(engine) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.sttEngine) {
                SttEngine.SYSTEM ->
                    "Android's recognizer, kept on-device. Nothing to download."
                SttEngine.PARAKEET ->
                    "NVIDIA's Parakeet — sharper ears, transcribes when the take ends."
            },
            style = SerifItalic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.sttEngine == SttEngine.PARAKEET) {
            Spacer(Modifier.height(16.dp))
            ModelRow(
                kind = ModelKind.PARAKEET_STT,
                state = state.models[ModelKind.PARAKEET_STT] ?: ModelState.NotInstalled,
                url = state.parakeetUrl,
                onUrl = { viewModel.setModelUrls(it, state.llmUrl) },
                onInstall = { viewModel.installModel(ModelKind.PARAKEET_STT) },
                onCancel = { viewModel.cancelModelDownload(ModelKind.PARAKEET_STT) },
                onDelete = { viewModel.deleteModel(ModelKind.PARAKEET_STT) },
            )
        }

        Spacer(Modifier.height(34.dp))
        LabelledRule("The mind — words into meaning")
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MindBackend.entries.toList().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { backend ->
                        ChoiceChip(
                            label = backend.title,
                            selected = state.mindBackend == backend,
                            onClick = { viewModel.setMindBackend(backend) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.mindBackend) {
                MindBackend.QUIET_PARSER ->
                    "A deterministic reader — fast, private, no model needed."
                MindBackend.ON_DEVICE ->
                    "Llama 3.2 3B runs entirely on this phone. Words never leave."
                MindBackend.OPENROUTER ->
                    "Cloud minds via OpenRouter — incl. free models. Words leave the phone."
                MindBackend.CUSTOM ->
                    "Any OpenAI-compatible endpoint — OpenAI, Groq, Together, Gemini-compat."
            },
            style = SerifItalic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state.mindBackend) {
            MindBackend.QUIET_PARSER -> {}
            MindBackend.ON_DEVICE -> {
                Spacer(Modifier.height(16.dp))
                ModelRow(
                    kind = ModelKind.LOCAL_LLM,
                    state = state.models[ModelKind.LOCAL_LLM] ?: ModelState.NotInstalled,
                    url = state.llmUrl,
                    onUrl = { viewModel.setModelUrls(state.parakeetUrl, it) },
                    onInstall = { viewModel.installModel(ModelKind.LOCAL_LLM) },
                    onCancel = { viewModel.cancelModelDownload(ModelKind.LOCAL_LLM) },
                    onDelete = { viewModel.deleteModel(ModelKind.LOCAL_LLM) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The Llama download is gated — accept Meta's licence on huggingface.co, " +
                        "then paste the .task link here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChronicleExtras.faint,
                )
            }
            MindBackend.OPENROUTER -> {
                Spacer(Modifier.height(16.dp))
                QuietField(
                    value = state.openRouterKey,
                    onChange = { viewModel.setOpenRouter(it, state.openRouterModel) },
                    placeholder = "OpenRouter API key (sk-or-…)",
                    secret = true,
                )
                Spacer(Modifier.height(14.dp))
                MetaText("Free models")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OPENROUTER_FREE_MODELS.forEach { model ->
                        ChoiceChip(
                            label = model.removeSuffix(":free"),
                            selected = state.openRouterModel == model,
                            onClick = {
                                viewModel.setOpenRouter(state.openRouterKey, model)
                            },
                        )
                    }
                }
            }
            MindBackend.CUSTOM -> {
                Spacer(Modifier.height(16.dp))
                QuietField(
                    value = state.customBaseUrl,
                    onChange = {
                        viewModel.setCustomEndpoint(it, state.customModel, state.customKey)
                    },
                    placeholder = "Base URL (https://api.openai.com/v1)",
                )
                Spacer(Modifier.height(12.dp))
                QuietField(
                    value = state.customModel,
                    onChange = {
                        viewModel.setCustomEndpoint(state.customBaseUrl, it, state.customKey)
                    },
                    placeholder = "Model (gpt-4o-mini, …)",
                )
                Spacer(Modifier.height(12.dp))
                QuietField(
                    value = state.customKey,
                    onChange = {
                        viewModel.setCustomEndpoint(state.customBaseUrl, state.customModel, it)
                    },
                    placeholder = "API key (optional for some endpoints)",
                    secret = true,
                )
            }
        }

        Spacer(Modifier.height(34.dp))
        LabelledRule("The principle")
        Spacer(Modifier.height(18.dp))
        Text(
            "Everything stays on this device. Voice notes, words, and the story they become. " +
                "No accounts, no feeds, nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        MetaText("Chronicle · 1.0")
        Spacer(Modifier.height(48.dp))
    }
}

/** A quiet toggle: hairline track, brass thumb — no switch chrome. */
@Composable
private fun QuietSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(28.dp)
            .clip(CircleShape)
            .border(
                1.dp,
                if (checked) MaterialTheme.colorScheme.primary else ChronicleExtras.hairline,
                CircleShape,
            )
            .clickable { onChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (checked) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
                ),
        )
    }
}

/** Small selectable chip — hairline border, brass when chosen. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.hairline,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        MetaText(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
        )
    }
}

/** A single-line hairline field — matching the write field's quietness. */
@Composable
private fun QuietField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column {
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = SerifItalic,
                            color = ChronicleExtras.faint,
                        )
                    }
                    inner()
                }
                Spacer(Modifier.height(8.dp))
                Hairline()
            }
        },
    )
}

/** A downloadable model pack: name, size, URL, progress, install/delete. */
@Composable
private fun ModelRow(
    kind: ModelKind,
    state: ModelState,
    url: String,
    onUrl: (String) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ChronicleExtras.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                MetaText(kind.sizeNote)
            }
            when (state) {
                is ModelState.Installed -> {
                    MetaText("Installed", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    MetaText(
                        "Delete",
                        color = ChronicleExtras.faint,
                        modifier = Modifier.clickable(onClick = onDelete),
                    )
                }
                is ModelState.Downloading -> {
                    MetaText(
                        if (state.progress >= 0) "${(state.progress * 100).toInt()}%"
                        else "${state.doneBytes / 1_000_000} MB",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    MetaText(
                        "Cancel",
                        color = ChronicleExtras.faint,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                }
                is ModelState.Extracting -> {
                    MetaText("Unpacking…", color = MaterialTheme.colorScheme.primary)
                }
                is ModelState.Failed -> {
                    MetaText(
                        "Retry",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onInstall),
                    )
                }
                is ModelState.NotInstalled -> {
                    MetaText(
                        "Get it",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onInstall),
                    )
                }
            }
        }
        if (state is ModelState.Downloading && state.progress >= 0) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ChronicleExtras.hairline),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (state is ModelState.Failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = ChronicleExtras.faint,
            )
        }
        if (state !is ModelState.Installed) {
            Spacer(Modifier.height(10.dp))
            QuietField(value = url, onChange = onUrl, placeholder = "Model download URL")
        }
    }
}
