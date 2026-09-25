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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.data.ThemeMode
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
