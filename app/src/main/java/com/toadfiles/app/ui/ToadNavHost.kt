package com.toadfiles.app.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.toadfiles.app.ui.about.AboutScreen
import com.toadfiles.app.ui.browse.BrowseScreen
import com.toadfiles.app.ui.categories.CategoriesScreen
import com.toadfiles.app.ui.home.HomeScreen
import com.toadfiles.app.ui.recent.RecentScreen
import com.toadfiles.app.ui.settings.SettingsScreen
import com.toadfiles.app.ui.trash.TrashScreen
import com.toadfiles.app.ui.storage.StorageScreen

object Routes {
    const val HOME = "home"
    const val BROWSE = "browse?dir={dir}"
    const val CATEGORIES = "categories"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val TRASH = "trash"
    const val STORAGE = "storage"

    fun browse(dir: String): String = "browse?dir=${Uri.encode(dir)}"
}

private data class BottomDestination(val route: String, val label: String, val icon: @Composable () -> Unit)

/**
 * Navigate without stacking a second copy when the destination is already on top.
 * Plain navigate() pushed a new instance on every tap, so repeated taps on
 * "Settings" (or a quick link) opened the screen again and again.
 */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

@Composable
fun ToadNavHost(navController: NavHostController = rememberNavController()) {
    val destinations = listOf(
        BottomDestination(Routes.HOME, "Home") { Icon(Icons.Outlined.Home, null) },
        BottomDestination(Routes.BROWSE, "Browse") { Icon(Icons.Outlined.Folder, null) },
        BottomDestination(Routes.CATEGORIES, "Categories") { Icon(Icons.Outlined.Category, null) },
        BottomDestination(Routes.SETTINGS, "Settings") { Icon(Icons.Outlined.Settings, null) },
    )

    Scaffold(
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            NavigationBar {
                destinations.forEach { dest ->
                    val baseRoute = dest.route.substringBefore('?')
                    val currentBase = currentRoute?.substringBefore('?')
                    NavigationBarItem(
                        selected = currentBase == baseRoute,
                        onClick = {
                            // Navigate with the BASE route ("browse"), never the raw pattern
                            // ("browse?dir={dir}") — navigating the pattern makes Navigation
                            // parse the literal "{dir}" as the argument value, which sent the
                            // Browse tab to a folder literally named "{dir}" (caught by E2E).
                            navController.navigate(baseRoute) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = dest.icon,
                        label = { Text(dest.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onOpenFolder = { navController.navigateSingleTop(Routes.browse(it)) },
                    onOpenStorage = { navController.navigateSingleTop(Routes.STORAGE) },
                    onOpenRecent = { navController.navigateSingleTop(Routes.RECENT) },
                    onOpenTrash = { navController.navigateSingleTop(Routes.TRASH) },
                    onOpenSettings = { navController.navigateSingleTop(Routes.SETTINGS) },
                )
            }
            composable(
                route = Routes.BROWSE,
                arguments = listOf(navArgument("dir") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                BrowseScreen(
                    initialDir = entry.arguments?.getString("dir").orEmpty(),
                    bottomPadding = padding.calculateBottomPadding(),
                    onNavigateUp = { navController.popBackStack() },
                )
            }
            composable(Routes.RECENT) {
                RecentScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onShowInFolder = { dir -> navController.navigateSingleTop(Routes.browse(dir)) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onShowInFolder = { dir -> navController.navigateSingleTop(Routes.browse(dir)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenTrash = { navController.navigateSingleTop(Routes.TRASH) },
                    onOpenAbout = { navController.navigateSingleTop(Routes.ABOUT) },
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.TRASH) {
                TrashScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.STORAGE) {
                StorageScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenFolder = { navController.navigateSingleTop(Routes.browse(it)) },
                )
            }
        }
    }
}
