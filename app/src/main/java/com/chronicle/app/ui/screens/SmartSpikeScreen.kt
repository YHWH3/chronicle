package com.chronicle.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chronicle.app.AppContainer
import com.chronicle.app.R
import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.mind.LocalLlmMind
import com.chronicle.app.mind.PromptFormat
import com.chronicle.app.mind.SmartModels
import com.chronicle.app.mind.SmartReply
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.GhostButton
import com.chronicle.app.ui.design.PrimaryPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.LocalDateTime

private val spikeInputs = listOf(
    "G1" to "remind me to call mum tomorrow at 5",
    "G2" to "I need to actually do some tasks , mainly to go to the bank and pick up the parcel",
    "G4" to "um so basically I gotta email Sarah about the invoice by Friday",
    "G12" to "I need to pay rent and then call the landlord about the leak",
    "G20" to "uh um",
)
private val modelInputs = listOf(
    "G23" to "had a long day. need to sleep early",
    "G24" to "I need to I mean I want to start running",
    "G25" to "remind me to call john tomorrow at 3",
)
private val modelExpected = mapOf(
    "G23" to "Sleep early → none",
    "G24" to "Start running → none",
    "G25" to "Call John → 2026-10-05T15:00",
)
private data class Check(val id: String, val reply: SmartReply?, val error: String? = null)

/** Registered and linked only in debug builds. No fallback masks a runtime failure. */
@Composable
fun SmartSpikeScreen(container: AppContainer, onBack: () -> Unit) {
    val prefs by container.prefsRepository.prefs.collectAsStateWithLifecycle(ChroniclePrefs())
    val installs by container.modelStore.states.collectAsStateWithLifecycle()
    val pack = if (prefs.selectedSmartModelId == "custom") SmartModels.CUSTOM_PACK
        else SmartModels.find(prefs.selectedSmartModelId)?.packId ?: SmartModels.all.first().packId
    val file = remember(pack, installs) { container.modelStore.localLlmFile(pack) }
    var running by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf(emptyList<Check>()) }
    var modelExamples by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(PromptFormat.PLAIN) }
    val scope = rememberCoroutineScope()
    SettingsPage(title = stringResource(R.string.test_smart_mode), onBack = onBack) {
        Text(stringResource(R.string.spike_help), style = Chron.type.body)
        Text(SmartModels.find(prefs.selectedSmartModelId)?.displayName
            ?: stringResource(R.string.custom_model), style = Chron.type.headline)
        if (file == null) Text(stringResource(R.string.spike_not_installed), style = Chron.type.body)
        Spacer(Modifier.height(16.dp))
        PrimaryPill(
            label = stringResource(if (running) R.string.spike_running else if (modelExamples) R.string.run_model_examples else R.string.spike_run),
            enabled = file != null && !running,
            onClick = {
                val modelFile = file ?: return@PrimaryPill
                running = true
                checks = emptyList()
                scope.launch {
                    // The diagnostic owns a temporary engine; release the production
                    // cache first so two native models never pin RAM simultaneously.
                    withContext(Dispatchers.Default) { container.llmEngineHolder.release() }
                    val engine = LocalLlmMind(container.appContextRef(), modelFile.absolutePath)
                    try {
                        for ((id, input) in if (modelExamples) modelInputs else spikeInputs) {
                            val check = try {
                                Check(id, engine.diagnose(input, LocalDateTime.of(2026, 10, 4, 16, 43), format))
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                Check(id, null, com.chronicle.app.core.sanitizeReason(t))
                            }
                            checks = checks + check
                        }
                    } finally {
                        engine.close()
                        running = false
                    }
                }
            },
        )
        GhostButton(label = stringResource(if (modelExamples) R.string.spike_run else R.string.model_checks),
            onClick = { if (!running) { modelExamples = !modelExamples; checks = emptyList() } })
        GhostButton(
            label = stringResource(if (format == PromptFormat.PLAIN) R.string.spike_format else R.string.spike_plain),
            onClick = { if (!running) format = if (format == PromptFormat.PLAIN) PromptFormat.CHAT_ML else PromptFormat.PLAIN },
        )
        SelectionContainer {
            Column {
                checks.forEach { check ->
                    Spacer(Modifier.height(20.dp))
                    val reply = check.reply
                    if (reply == null) {
                        Text(check.id, style = Chron.type.section)
                        Text(stringResource(R.string.spike_error, check.error.orEmpty()), color = Chron.palette.warning)
                    } else {
                        Text(stringResource(R.string.spike_result, check.id, reply.loadMs, reply.totalMs,
                            stringResource(if (reply.validJson) R.string.yes else R.string.no)),
                            style = Chron.type.controlSmall)
                        Text(stringResource(R.string.spike_raw), style = Chron.type.meta)
                        Text(reply.raw, style = Chron.type.bodySmall)
                        modelExpected[check.id]?.let { expected ->
                            Text(stringResource(R.string.spike_expected), style = Chron.type.meta)
                            Text(expected, style = Chron.type.bodySmall)
                        }
                        Text(stringResource(R.string.spike_tasks), style = Chron.type.meta)
                        Text(reply.parsed?.tasks?.joinToString("\n") { it.title + " → " + (it.remindAt ?: "none") }
                            ?.ifEmpty { stringResource(R.string.spike_none) }
                            ?: stringResource(R.string.spike_none), style = Chron.type.bodySmall)
                    }
                }
            }
        }
    }
}
