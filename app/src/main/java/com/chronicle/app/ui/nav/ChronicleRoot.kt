package com.chronicle.app.ui.nav

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.chronicle.app.AppContainer
import com.chronicle.app.R
import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.ui.design.AtmosphereHost
import com.chronicle.app.ui.design.Chron
import com.chronicle.app.ui.design.FloatingNavBar
import com.chronicle.app.ui.design.GlassSurface
import com.chronicle.app.ui.design.LocalBottomBarPadding
import com.chronicle.app.ui.design.NavItem
import com.chronicle.app.ui.design.Radius
import com.chronicle.app.ui.design.SceneEffect
import com.chronicle.app.ui.design.SceneKey
import com.chronicle.app.ui.design.Spacing
import com.chronicle.app.ui.design.SurfaceLevel
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.ui.screens.JournalScreen
import com.chronicle.app.ui.screens.SettingsAboutPage
import com.chronicle.app.ui.screens.SettingsAppearancePage
import com.chronicle.app.ui.screens.SettingsEarPage
import com.chronicle.app.ui.screens.SettingsMindPage
import com.chronicle.app.ui.screens.SettingsModelPage
import com.chronicle.app.ui.screens.SettingsNudgePage
import com.chronicle.app.ui.screens.SettingsPrivacyPage
import com.chronicle.app.ui.screens.SettingsScreen
import com.chronicle.app.ui.screens.StoryChapterScreen
import com.chronicle.app.ui.screens.StoryScreen
import com.chronicle.app.ui.screens.TasksScreen
import com.chronicle.app.ui.screens.TodayScreen
import com.chronicle.app.ui.theme.ChronicleMotion
import com.chronicle.app.ui.vm.JournalViewModel
import com.chronicle.app.ui.vm.SettingsViewModel
import com.chronicle.app.ui.vm.StoryViewModel
import com.chronicle.app.ui.vm.TasksViewModel
import com.chronicle.app.ui.vm.TodayViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val TODAY = "today"
    const val JOURNAL = "journal"
    const val STORY = "story"
    const val TASKS = "tasks"
    const val SETTINGS_GRAPH = "settings_root"
    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_NUDGE = "settings/nudge"
    const val SETTINGS_EAR = "settings/ear"
    const val SETTINGS_MIND = "settings/mind"
    const val SETTINGS_MODEL = "settings/model/{kind}"
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_ABOUT = "settings/about"
    const val CHAPTER = "chapter/{number}"
    fun chapter(number: Int) = "chapter/$number"
    fun settingsModel(kind: com.chronicle.app.mind.ModelKind) = "settings/model/${kind.name}"
}

private val DESTINATIONS = listOf(
    NavItem(Routes.TODAY, R.drawable.ic_pen, "Today"),
    NavItem(Routes.JOURNAL, R.drawable.ic_book, "Journal"),
    NavItem(Routes.STORY, R.drawable.ic_bookmark, "Story"),
    NavItem(Routes.TASKS, R.drawable.ic_check_circle, "Intentions"),
)

private val TAB_ROUTES = DESTINATIONS.map { it.route }
private fun androidx.navigation.NavDestination.isTab() = route in TAB_ROUTES

private fun sceneForRoute(route: String?): SceneKey = when (route) {
    Routes.JOURNAL -> SceneKey.JOURNAL
    Routes.STORY, Routes.CHAPTER -> SceneKey.STORY
    Routes.TASKS -> SceneKey.INTENTIONS
    else -> if (route?.startsWith("settings") == true) SceneKey.SETTINGS else SceneKey.TODAY_IDLE
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChronicleRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTabRoute = currentRoute in TAB_ROUTES
    val imeVisible = WindowInsets.isImeVisible
    val haptics = LocalHapticFeedback.current

    AtmosphereHost {
        // Route drives the base scene; Today overrides per capture phase.
        SceneEffect(sceneForRoute(currentRoute))

        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBottomBarPadding provides 96.dp) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.TODAY,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (initialState.destination.isTab() && targetState.destination.isTab()) {
                            fadeIn(tween(ChronicleMotion.SCREEN))
                        } else {
                            fadeIn(tween(ChronicleMotion.SCREEN)) +
                                slideInHorizontally(tween(ChronicleMotion.SCREEN)) { it / 16 }
                        }
                    },
                    exitTransition = {
                        fadeOut(tween(ChronicleMotion.SCREEN))
                    },
                    popEnterTransition = {
                        if (initialState.destination.isTab() && targetState.destination.isTab()) {
                            fadeIn(tween(ChronicleMotion.SCREEN))
                        } else {
                            fadeIn(tween(ChronicleMotion.SCREEN)) +
                                slideInHorizontally(tween(ChronicleMotion.SCREEN)) { -it / 16 }
                        }
                    },
                    popExitTransition = {
                        if (initialState.destination.isTab() && targetState.destination.isTab()) {
                            fadeOut(tween(ChronicleMotion.SCREEN))
                        } else {
                            fadeOut(tween(ChronicleMotion.SCREEN)) +
                                slideOutHorizontally(tween(ChronicleMotion.SCREEN)) { it / 16 }
                        }
                    },
                ) {
                    composable(Routes.TODAY) {
                        val vm: TodayViewModel = viewModel(factory = vmFactory {
                            TodayViewModel(
                                container.journalRepository,
                                container.taskRepository,
                                container.prefsRepository,
                                container.voiceRecorder,
                                container.wavRecorder,
                                container.transcriber,
                                container.parakeetTranscriber,
                                container.mindRepository,
                                container.reminderGateway,
                                container.uiMessages,
                            )
                        })
                        TodayScreen(
                            vm,
                            onOpenSettings = { navController.navigate(Routes.SETTINGS_GRAPH) },
                            onOpenJournal = { navController.navigateToTab(Routes.JOURNAL) },
                        )
                    }
                    composable(Routes.JOURNAL) {
                        val vm: JournalViewModel = viewModel(factory = vmFactory {
                            JournalViewModel(
                                container.journalRepository,
                                container.player,
                                container.parakeetTranscriber,
                                container.uiMessages,
                            )
                        })
                        JournalScreen(
                            vm,
                            onOpenToday = { navController.navigateToTab(Routes.TODAY) },
                        )
                    }
                    composable(Routes.STORY) {
                        val vm: StoryViewModel = viewModel(factory = vmFactory {
                            StoryViewModel(container.journalRepository)
                        })
                        StoryScreen(vm, onOpenChapter = { navController.navigate(Routes.chapter(it)) })
                    }
                    composable(Routes.TASKS) {
                        val vm: TasksViewModel = viewModel(factory = vmFactory {
                            TasksViewModel(
                                container.taskRepository,
                                container.reminderGateway,
                                container.uiMessages,
                            )
                        })
                        TasksScreen(vm)
                    }
                    // One VM for the whole settings graph — scoped to the
                    // graph's back-stack entry, so sub-pages share it.
                    navigation(
                        startDestination = Routes.SETTINGS,
                        route = Routes.SETTINGS_GRAPH,
                    ) {
                        composable(Routes.SETTINGS) { entry ->
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsScreen(
                                vm,
                                onBack = { navController.popBackStack() },
                                onOpen = { navController.navigate(it) },
                            )
                        }
                        composable(Routes.SETTINGS_APPEARANCE) { entry ->
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsAppearancePage(vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.SETTINGS_NUDGE) { entry ->
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsNudgePage(vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.SETTINGS_EAR) { entry ->
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsEarPage(
                                vm,
                                onBack = { navController.popBackStack() },
                                onOpen = { navController.navigate(it) },
                            )
                        }
                        composable(Routes.SETTINGS_MIND) { entry ->
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsMindPage(
                                vm,
                                onBack = { navController.popBackStack() },
                                onOpen = { navController.navigate(it) },
                            )
                        }
                        composable(
                            Routes.SETTINGS_MODEL,
                            arguments = listOf(
                                navArgument("kind") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            val kind = entry.arguments?.getString("kind")
                                ?.let { runCatching { ModelKind.valueOf(it) }.getOrNull() }
                                ?: ModelKind.PARAKEET_STT
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsModelPage(vm, kind, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.SETTINGS_PRIVACY) { entry ->
                            val vm = settingsViewModel(navController, entry, container)
                            SettingsPrivacyPage(vm, onBack = { navController.popBackStack() })
                        }
                        composable(Routes.SETTINGS_ABOUT) {
                            SettingsAboutPage(onBack = { navController.popBackStack() })
                        }
                    }
                    composable(
                        Routes.CHAPTER,
                        arguments = listOf(navArgument("number") { type = NavType.IntType }),
                    ) { entry ->
                        val number = entry.arguments?.getInt("number") ?: 1
                        val vm: StoryViewModel = viewModel(factory = vmFactory {
                            StoryViewModel(container.journalRepository)
                        })
                        StoryChapterScreen(vm, number, onBack = { navController.popBackStack() })
                    }
                }
            }

            FloatingNavBar(
                items = DESTINATIONS,
                currentRoute = currentRoute,
                visible = onTabRoute && !imeVisible,
                onSelect = { item ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (item.route != currentRoute) {
                        navController.navigateToTab(item.route)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            UiMessageHost(container.uiMessages)
        }
    }
}

/**
 * The single surface for one-shot [UiMessage]s — a frosted pill riding above
 * the nav bar, warning-tinted, auto-dismissing. Repeated identical messages
 * inside a short window collapse into one; a distinct message replaces the
 * current one and restarts the countdown.
 */
@Composable
private fun BoxScope.UiMessageHost(bus: UiMessageBus) {
    val context = LocalContext.current
    var current by remember { mutableStateOf<UiMessage?>(null) }
    // Keep the last message so the exit fade doesn't render an empty pill.
    var lastShown by remember { mutableStateOf<UiMessage?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Pause collection while STOPPED — a message emitted in the background
    // shouldn't pop a stale pill minutes later (FE-08).
    LaunchedEffect(bus, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                var lastText: String? = null
                var lastAt = 0L
                var dismiss: Job? = null
                bus.messages.collect { msg ->
                    val now = System.currentTimeMillis()
                    if (msg.text == lastText && now - lastAt < 6_000) return@collect
                    lastText = msg.text
                    lastAt = now
                    dismiss?.cancel()
                    dismiss = launch {
                        current = msg
                        lastShown = msg
                        delay(4_200)
                        current = null
                    }
                }
            } finally {
                current = null
            }
        }
    }
    AnimatedVisibility(
        visible = current != null,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = Spacing.screenMargin)
            .padding(bottom = LocalBottomBarPadding.current + 12.dp),
        enter = fadeIn(tween(ChronicleMotion.MICRO)) +
            slideInVertically(tween(ChronicleMotion.MICRO)) { it / 4 },
        exit = fadeOut(tween(ChronicleMotion.MICRO)),
    ) {
        lastShown?.let { msg ->
            GlassSurface(
                level = SurfaceLevel.Frosted,
                shape = RoundedCornerShape(percent = Radius.pill),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        start = 18.dp,
                        end = if (msg.actionLabel != null) 8.dp else 18.dp,
                        top = 11.dp,
                        bottom = 11.dp,
                    ),
                ) {
                    Text(
                        text = msg.text,
                        style = Chron.type.bodySmall,
                        color = Chron.palette.warning,
                    )
                    msg.actionLabel?.let { label ->
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = label,
                            style = Chron.type.controlSmall,
                            color = Chron.palette.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .clickable {
                                    val action = msg.onAction
                                    when {
                                        action != null -> action()
                                        msg.opensAppSettings -> context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts(
                                                    "package",
                                                    context.packageName,
                                                    null,
                                                ),
                                            ),
                                        )
                                    }
                                    current = null
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The single SettingsViewModel shared by the settings nav graph — scoped to
 * the graph's back-stack entry so every sub-page sees the same instance.
 */
@Composable
private fun settingsViewModel(
    navController: NavHostController,
    entry: androidx.navigation.NavBackStackEntry,
    container: AppContainer,
): SettingsViewModel {
    val graphEntry = remember(entry) {
        navController.getBackStackEntry(Routes.SETTINGS_GRAPH)
    }
    return viewModel(
        viewModelStoreOwner = graphEntry,
        factory = vmFactory {
            SettingsViewModel(
                container.prefsRepository,
                container.modelStore,
                container.mindRepository,
                container.transcriber,
                container.reminderGateway,
            )
        },
    )
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.TODAY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private inline fun <reified VM : ViewModel> vmFactory(
    noinline creator: () -> VM,
): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer { creator() }
}
