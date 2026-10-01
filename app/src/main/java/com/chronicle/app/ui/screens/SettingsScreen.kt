package com.chronicle.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chronicle.app.R
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.FrostedIconButton
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.Hairline
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.ui.nav.Routes
import com.chronicle.app.ui.vm.SettingsUiState
import com.chronicle.app.ui.vm.SettingsViewModel
import java.util.Locale

/** Shared page chrome: frosted back button, title, scrolling body. */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(
                PaddingValues(
                    start = Spacing.screenMargin, end = Spacing.screenMargin,
                    top = Spacing.x4, bottom = Spacing.x14,
                ),
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrostedIconButton(
                icon = R.drawable.ic_back,
                contentDescription = "Back",
                onClick = onBack,
            )
        }
        Spacer(Modifier.height(Spacing.x6))
        Text(
            text = title,
            style = Chron.type.title,
            color = Chron.palette.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.x6))
        content()
    }
}

/** One group of settings rows — a single frosted surface with hairlines. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * A settings navigation row: thin icon, title, one-line state summary,
 * chevron. 48dp+ target, merged semantics read as "Title, summary".
 */
@Composable
internal fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val p = Chron.palette
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = p.textPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = p.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.x4))
        Column(Modifier.weight(1f)) {
            Text(title, style = Chron.type.control, color = p.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                summary,
                style = Chron.type.meta,
                color = p.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = p.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Compact model-status row — opens the model's own page. Status wording is
 * the terse form of the model page's state copy.
 */
@Composable
internal fun ModelStatusRow(
    kind: ModelKind,
    state: ModelState,
    onOpen: () -> Unit,
) {
    val p = Chron.palette
    GlassSurface(
        level = SurfaceLevel.Frosted,
        shape = RoundedCornerShape(Radius.sm),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = kind.title.substringBefore(" · "),
                    style = Chron.type.controlSmall,
                    color = p.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (state) {
                        is ModelState.NotInstalled -> "Not installed"
                        is ModelState.Downloading ->
                            if (state.progress >= 0f) {
                                "Downloading ${(state.progress * 100).toInt()}%"
                            } else {
                                "Downloading…"
                            }
                        is ModelState.Extracting -> "Unpacking"
                        is ModelState.Installed -> "Ready"
                        is ModelState.Failed -> "Failed"
                    },
                    style = Chron.type.meta,
                    color = when (state) {
                        is ModelState.Installed -> p.accent
                        is ModelState.Failed -> p.warning
                        else -> p.textTertiary
                    },
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = p.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ————— Overview —————

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    SettingsPage(title = "Settings", onBack = onBack) {
        Text(
            text = "The quiet particulars.",
            style = Chron.type.serifItalic,
            color = Chron.palette.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x8))
        SettingsGroup {
            SettingsRow(
                icon = R.drawable.ic_contrast,
                title = "Appearance",
                summary = appearanceSummary(state.themeMode),
                onClick = { onOpen(Routes.SETTINGS_APPEARANCE) },
            )
            Hairline()
            SettingsRow(
                icon = R.drawable.ic_bell,
                title = "Daily nudge",
                summary = nudgeSummary(state),
                onClick = { onOpen(Routes.SETTINGS_NUDGE) },
            )
            Hairline()
            SettingsRow(
                icon = R.drawable.ic_mic,
                title = "The Ear",
                summary = earSummary(state),
                onClick = { onOpen(Routes.SETTINGS_EAR) },
            )
            Hairline()
            SettingsRow(
                icon = R.drawable.ic_sparkle,
                title = "The Mind",
                summary = state.effectiveMindLabel.ifBlank { "Quiet parser" },
                onClick = { onOpen(Routes.SETTINGS_MIND) },
            )
        }
        Spacer(Modifier.height(Spacing.x4))
        SettingsGroup {
            SettingsRow(
                icon = R.drawable.ic_lock,
                title = "Privacy",
                summary = privacySummary(state),
                onClick = { onOpen(Routes.SETTINGS_PRIVACY) },
            )
            Hairline()
            SettingsRow(
                icon = R.drawable.ic_info,
                title = "About",
                summary = appVersion(),
                onClick = { onOpen(Routes.SETTINGS_ABOUT) },
            )
        }
    }
}

internal fun appearanceSummary(mode: ThemeMode): String = when (mode) {
    ThemeMode.PAPER -> "Paper"
    ThemeMode.INK -> "Ink"
    ThemeMode.SYSTEM -> "Follows the day"
}

internal fun nudgeSummary(state: SettingsUiState): String =
    if (state.dailyNudgeEnabled) {
        "Every day at %02d:%02d".format(
            Locale.ENGLISH, state.dailyNudgeMinutes / 60, state.dailyNudgeMinutes % 60,
        )
    } else {
        "Off"
    }

internal fun earSummary(state: SettingsUiState): String = when (state.sttEngine) {
    SttEngine.SYSTEM ->
        if (state.systemSttAvailable) "Built into Android" else "Not available on this device"
    SttEngine.PARAKEET ->
        if (state.models[ModelKind.PARAKEET_STT] is ModelState.Installed) {
            "Parakeet · on this phone"
        } else {
            "Parakeet · using Android's recogniser"
        }
}

internal fun privacySummary(state: SettingsUiState): String =
    if (state.mindLeavesPhone) {
        when (state.mindBackend) {
            MindBackend.CUSTOM -> "Entry text goes to ${customHost(state.customBaseUrl)}"
            else -> "Entry text goes to OpenRouter"
        }
    } else {
        "Everything stays on this phone"
    }

internal fun customHost(url: String): String =
    runCatching { java.net.URI(url.trim()).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: url.trim().ifBlank { "your endpoint" }

@Composable
internal fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            "v" + context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("")
    }
}
