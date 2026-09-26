package com.chronicle.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.chronicle.app.ChronicleApp
import com.chronicle.app.R
import com.chronicle.app.ui.components.Hairline
import com.chronicle.app.ui.components.MetaText
import com.chronicle.app.ui.components.ChronicleIcon
import com.chronicle.app.ui.screens.JournalScreen
import com.chronicle.app.ui.screens.SettingsScreen
import com.chronicle.app.ui.screens.StoryChapterScreen
import com.chronicle.app.ui.screens.StoryScreen
import com.chronicle.app.ui.screens.TasksScreen
import com.chronicle.app.ui.screens.TodayScreen
import com.chronicle.app.ui.theme.ChronicleExtras
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

private data class Destination(val route: String, val icon: Int, val label: String)

private val DESTINATIONS = listOf(
    Destination(Routes.TODAY, R.drawable.ic_pen, "Today"),
    Destination(Routes.JOURNAL, R.drawable.ic_book, "Journal"),
    Destination(Routes.STORY, R.drawable.ic_bookmark, "Story"),
    Destination(Routes.TASKS, R.drawable.ic_check_circle, "Intentions"),
)

@Composable
fun ChronicleRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTabRoute = currentRoute in DESTINATIONS.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (onTabRoute) ChronicleBar(currentRoute, navController)
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = {
                fadeIn(tween(ChronicleMotion.NAV, easing = ChronicleMotion.EnterEasing)) +
                    slideInVertically(
                        tween(ChronicleMotion.NAV, easing = ChronicleMotion.EnterEasing),
                        initialOffsetY = { it / 40 },
                    )
            },
            exitTransition = {
                fadeOut(tween(ChronicleMotion.EXIT, easing = ChronicleMotion.ExitEasing)) +
                    slideOutVertically(
                        tween(ChronicleMotion.EXIT, easing = ChronicleMotion.ExitEasing),
                        targetOffsetY = { it / 60 },
                    )
            },
            popEnterTransition = {
                fadeIn(tween(ChronicleMotion.NAV, easing = ChronicleMotion.EnterEasing)) +
                    slideInVertically(
                        tween(ChronicleMotion.NAV, easing = ChronicleMotion.EnterEasing),
                        initialOffsetY = { it / 40 },
                    )
            },
            popExitTransition = {
                fadeOut(tween(ChronicleMotion.EXIT, easing = ChronicleMotion.ExitEasing)) +
                    slideOutVertically(
                        tween(ChronicleMotion.EXIT, easing = ChronicleMotion.ExitEasing),
                        targetOffsetY = { it / 60 },
                    )
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
                TodayScreen(vm, onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.JOURNAL) {
                val vm: JournalViewModel = viewModel(factory = vmFactory {
                    JournalViewModel(container.journalRepository, container.player)
                })
                JournalScreen(vm)
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
}

private inline fun <reified VM : ViewModel> vmFactory(
    noinline creator: () -> VM,
): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer { creator() }
}

/** Slim editorial tab bar — hairline rule, mono labels, no chrome. */
@Composable
private fun ChronicleBar(currentRoute: String?, navController: NavHostController) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (dest in DESTINATIONS) {
                val selected = currentRoute == dest.route
                val tint by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else ChronicleExtras.faint,
                    animationSpec = tween(ChronicleMotion.MICRO, easing = ChronicleMotion.StandardEasing),
                    label = "tabTint",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (!selected) {
                                navController.navigate(dest.route) {
                                    popUpTo(Routes.TODAY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .minimumInteractiveComponentSize(),
                ) {
                    ChronicleIcon(
                        res = dest.icon,
                        contentDescription = dest.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    MetaText(text = dest.label, color = tint)
                }
            }
        }
    }
}
