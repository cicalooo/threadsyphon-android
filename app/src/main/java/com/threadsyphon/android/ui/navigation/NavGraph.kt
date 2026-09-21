package com.threadsyphon.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.ui.find.FindScreen
import com.threadsyphon.android.ui.rules.RulesScreen
import com.threadsyphon.android.ui.settings.SettingsScreen
import com.threadsyphon.android.ui.threads.ThreadDetailScreen
import com.threadsyphon.android.ui.threads.ThreadListScreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Threads : Dest("threads", "Threads", Icons.Default.List)
    data object Find : Dest("find", "Find", Icons.Default.Search)
    data object Rules : Dest("rules", "Rules", Icons.Default.Rule)
    data object Settings : Dest("settings", "Settings", Icons.Default.Settings)
}

private val bottom = listOf(Dest.Threads, Dest.Find, Dest.Rules, Dest.Settings)

@Composable
fun ThreadSyphonNavHost(repository: WatchRepository) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBottom = bottom.any { it.route == current }

    Scaffold(
        bottomBar = {
            if (showBottom) {
                NavigationBar {
                    bottom.forEach { dest ->
                        NavigationBarItem(
                            selected = current == dest.route,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Threads.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Threads.route) {
                ThreadListScreen(repository = repository, onOpenDetail = { id -> nav.navigate("thread/$id") })
            }
            composable(
                route = "thread/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ThreadDetailScreen(threadId = id, repository = repository, onBack = { nav.popBackStack() })
            }
            composable(Dest.Find.route) { FindScreen(repository = repository) }
            composable(Dest.Rules.route) { RulesScreen(repository = repository) }
            composable(Dest.Settings.route) { SettingsScreen(repository = repository) }
        }
    }
}
