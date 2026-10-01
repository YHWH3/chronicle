package com.chronicle.app.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.SttEngine
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.vm.SettingsUiState
import com.chronicle.app.ui.vm.SettingsViewModel

// ————— Privacy —————

@Composable
fun SettingsPrivacyPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    SettingsPage(title = "Privacy", onBack = onBack) {
        Text(
            text = if (state.mindLeavesPhone) {
                "One thing leaves this phone."
            } else {
                "Everything stays on this phone."
            },
            style = Chron.type.headline,
            color = Chron.palette.textPrimary,
        )
        Spacer(Modifier.height(Spacing.x8))

        PrivacyItem(
            label = "Your voice",
            body = voiceLine(state),
        )
        PrivacyItem(
            label = "Your entries",
            body = if (state.mindLeavesPhone) {
                "Written words live in the journal database on this phone. " +
                    "With a cloud mind configured, an entry's text is also sent " +
                    "to that service each time you save."
            } else {
                "Written words are stored only on this phone, in the journal database."
            },
        )
        PrivacyItem(
            label = "Finding intentions",
            body = mindLine(state),
        )
        PrivacyItem(
            label = "Your keys",
            body = "API keys for cloud minds are stored on this phone, " +
                "encrypted via Android Keystore.",
        )
        PrivacyItem(
            label = "Downloads",
            body = "Model packs are fetched from their source URLs — only when you tap Download.",
        )
    }
}

@Composable
private fun PrivacyItem(label: String, body: String) {
    Text(
        text = label,
        style = Chron.type.section,
        color = Chron.palette.textSecondary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = body,
        style = Chron.type.body,
        color = Chron.palette.textPrimary,
        modifier = Modifier.padding(bottom = Spacing.x6),
    )
}

private fun voiceLine(state: SettingsUiState): String {
    val parakeetReady = state.models[ModelKind.PARAKEET_STT] is ModelState.Installed
    return when {
        state.sttEngine == SttEngine.PARAKEET && parakeetReady ->
            "Recorded on this phone and transcribed by Parakeet, entirely on-device. " +
                "Audio never leaves."
        state.systemSttAvailable ->
            "Recorded on this phone and transcribed by Android's on-device recogniser. " +
                "On-device recognition is requested — a strong preference, " +
                "not an absolute guarantee."
        else ->
            "Recorded on this phone. No on-device recogniser is available, " +
                "so notes are kept as audio until one is."
    }
}

private fun mindLine(state: SettingsUiState): String = when (state.mindBackend) {
    MindBackend.QUIET_PARSER ->
        "The Quiet parser reads them on this phone. No model, no network."
    MindBackend.ON_DEVICE ->
        if (state.models[ModelKind.LOCAL_LLM] is ModelState.Installed) {
            "Llama 3.2 3B reads them on this phone. Nothing is sent."
        } else {
            "The on-device model is selected but isn't installed, " +
                "so the Quiet parser runs on this phone instead."
        }
    MindBackend.OPENROUTER ->
        if (state.openRouterKey.isBlank()) {
            "OpenRouter is selected but has no API key, " +
                "so the Quiet parser runs on this phone instead."
        } else {
            "Entry text is sent to OpenRouter " +
                "(model ${state.openRouterModel.removeSuffix(":free")}) when you save. " +
                "If it fails or takes more than 20 seconds, " +
                "the Quiet parser reads them here instead."
        }
    MindBackend.CUSTOM ->
        if (state.customBaseUrl.isBlank() || state.customModel.isBlank()) {
            "A custom endpoint is selected but has no URL or model, " +
                "so the Quiet parser runs on this phone instead."
        } else {
            "Entry text is sent to ${customHost(state.customBaseUrl)} when you save — " +
                "HTTPS only. If it fails or takes more than 20 seconds, " +
                "the Quiet parser reads them here instead."
        }
}

// ————— About —————

@Composable
fun SettingsAboutPage(onBack: () -> Unit) {
    SettingsPage(title = "About", onBack = onBack) {
        Text(
            text = "Chronicle",
            style = Chron.type.display,
            color = Chron.palette.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = appVersion(),
            style = Chron.type.numeric,
            color = Chron.palette.textTertiary,
        )
        Spacer(Modifier.height(Spacing.x8))
        Text(
            text = "A quiet journal. Speak or write what happened today; " +
                "Chronicle keeps it on your phone, finds the things you meant " +
                "to do, and each week writes your days back as a story.",
            style = Chron.type.body,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x8))
        Text(
            text = "Type",
            style = Chron.type.section,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Fraunces, Inter and JetBrains Mono — under the SIL Open Font License.",
            style = Chron.type.body,
            color = Chron.palette.textTertiary,
        )
        Spacer(Modifier.height(Spacing.x6))
        Text(
            text = "Built with",
            style = Chron.type.section,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "sherpa-onnx · MediaPipe Tasks · Room · Jetpack Compose · " +
                "DataStore · kotlinx.coroutines · commons-compress",
            style = Chron.type.body,
            color = Chron.palette.textTertiary,
        )
    }
}
