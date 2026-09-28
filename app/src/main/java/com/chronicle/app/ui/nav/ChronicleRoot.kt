package com.chronicle.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.chronicle.app.AppContainer
import com.chronicle.app.R
import com.chronicle.app.ui.design.AtmosphereHost
import com.chronicle.app.ui.design.FloatingNavBar
import com.chronicle.app.ui.design.LocalBottomBarPadding
import com.chronicle.app.ui.design.NavItem
import com.chronicle.app.ui.design.SceneEffect
import com.chronicle.app.ui.design.SceneKey
import com.chronicle.app.ui.screens.JournalScreen
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

object Routes {
    const val TODAY = "today"
    const val JOURNAL = "journal"
    const val STORY = "story"
    const val TASKS = "tasks"
    const val SETTINGS = "settings"
    const val CHAPTER = "chapter/{number}"
    fun chapter(number: Int) = "chapter/$number"
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
    Routes.SETTINGS -> SceneKey.SETTINGS
    else -> SceneKey.TODAY_IDLE
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
                                container.appContextRef(),
                                container.journalRepository,
                                container.taskRepository,
                                container.prefsRepository,
                                container.voiceRecorder,
                                container.wavRecorder,
                                container.transcriber,
                                container.parakeetTranscriber,
                                container.mindRepository,
                            )
                        })
                        TodayScreen(
                            vm,
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenJournal = { navController.navigateToTab(Routes.JOURNAL) },
                        )
                    }
                    composable(Routes.JOURNAL) {
                        val vm: JournalViewModel = viewModel(factory = vmFactory {
                            JournalViewModel(container.journalRepository, container.player)
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
                            TasksViewModel(container.appContextRef(), container.taskRepository)
                        })
                        TasksScreen(vm)
                    }
                    composable(Routes.SETTINGS) {
                        val vm: SettingsViewModel = viewModel(factory = vmFactory {
                            SettingsViewModel(
                                container.appContextRef(),
                                container.prefsRepository,
                                container.modelStore,
                            )
                        })
                        SettingsScreen(vm, onBack = { navController.popBackStack() })
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
        }
    }
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
