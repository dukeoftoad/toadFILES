package com.toadfiles.app.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import java.io.File
import kotlinx.coroutines.launch
import com.toadfiles.app.ui.about.AboutScreen
import com.toadfiles.app.ui.analyzer.StorageAnalyzerScreen
import com.toadfiles.app.ui.browse.BrowseScreen
import com.toadfiles.app.ui.categories.CategoriesScreen
import com.toadfiles.app.ui.home.HomeScreen
import com.toadfiles.app.ui.recent.RecentScreen
import com.toadfiles.app.ui.settings.AppearanceScreen
import com.toadfiles.app.ui.settings.SettingsScreen
import com.toadfiles.app.ui.settings.SettingsViewModel
import com.toadfiles.app.ui.trash.TrashScreen
import com.toadfiles.app.ui.storage.StorageScreen
import com.toadfiles.app.ui.whatsnew.WhatsNewScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.NavStyle

object Routes {
    const val HOME = "home"
    const val BROWSE = "browse?dir={dir}&reveal={reveal}&open_archive={openArchive}&archive_dest={archiveDest}"
    const val CATEGORIES = "categories"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val WHATS_NEW = "whats_new"
    const val TRASH = "trash"
    const val STORAGE = "storage"
    const val APPEARANCE = "appearance"
    const val STORAGE_ANALYZER = "storage_analyzer?root={root}"

    fun browse(
        dir: String,
        reveal: String? = null,
        openArchive: String? = null,
        archiveDest: String? = null,
    ): String =
        "browse?dir=${Uri.encode(dir)}" + (reveal?.let { "&reveal=${Uri.encode(it)}" } ?: "") +
            (openArchive?.let { "&open_archive=${Uri.encode(it)}" } ?: "") +
            (archiveDest?.let { "&archive_dest=${Uri.encode(it)}" } ?: "")
    fun storageAnalyzer(root: String = ""): String =
        "storage_analyzer?root=${Uri.encode(root)}"
}

private data class BottomDestination(val route: String, val label: String, val icon: @Composable () -> Unit)

/** Side-drawer entry — the primary navigation in SIDE_MENU mode (no bottom bar). */
private data class DrawerDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

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
    // v1.7.4: the two navigation styles are now truly mutually exclusive.
    //  - BOTTOM_BAR: bottom bar only. No drawer, no hamburger anywhere; screens
    //    that would show one (Recent/Settings/Trash/Appearance) fall back to a Back arrow.
    //  - SIDE_MENU: ModalNavigationDrawer only, no bottom bar. The drawer carries
    //    ALL primary destinations (Home, Browse, Categories, Recent, Trash, Settings, About).
    val settingsVm: SettingsViewModel = viewModel(factory = ToadViewModelFactory)
    val navSettings by settingsVm.settings.collectAsStateWithLifecycle()
    val navStyle = navSettings.navStyle
    val useDrawer = navStyle == NavStyle.SIDE_MENU
    val destinations = listOf(
        BottomDestination(Routes.HOME, "Home") { Icon(Icons.Outlined.Home, null) },
        BottomDestination(Routes.BROWSE, "Browse") { Icon(Icons.Outlined.Folder, null) },
        BottomDestination(Routes.CATEGORIES, "Categories") { Icon(Icons.Outlined.Category, null) },
        BottomDestination(Routes.SETTINGS, "Settings") { Icon(Icons.Outlined.Settings, null) },
    )

    // Full destination set for the side drawer. Home and Categories were missing in
    // v1.7.3, making them unreachable in SIDE_MENU mode; About sits below a divider.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val drawerDestinations = listOf(
        DrawerDestination(Routes.HOME, "Home", Icons.Outlined.Home),
        DrawerDestination(Routes.BROWSE, "Browse", Icons.Outlined.Folder),
        DrawerDestination(Routes.CATEGORIES, "Categories", Icons.Outlined.Category),
        DrawerDestination(Routes.RECENT, "Recent", Icons.Outlined.History),
        DrawerDestination(Routes.TRASH, "Trash", Icons.Outlined.Delete),
        // ---- secondary group ----
        DrawerDestination(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
        DrawerDestination(Routes.ABOUT, "About", Icons.Outlined.Info),
    )
    val drawerDividerAfter = Routes.TRASH
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentBase = backStackEntry?.destination?.route?.substringBefore('?')
    val openFromDrawer: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        // Same reselection semantics as the bottom bar: never stack duplicates.
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // Hamburger affordance — passed to top-level screens ONLY in SIDE_MENU mode.
    val openDrawer: (() -> Unit)? = if (useDrawer) {
        { scope.launch { drawerState.open() } }
    } else null

    val appScaffold: @Composable () -> Unit = {
    Scaffold(
        // Single source of truth for window insets: this outer Scaffold applies the
        // status-bar (and navigation-bar) insets exactly once. Every inner screen
        // Scaffold sets contentWindowInsets = WindowInsets(0, 0, 0, 0) so the status
        // bar is never padded twice — the v1.7.3 "dead band" at the top.
        bottomBar = {
            if (!useDrawer) {
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
                        modifier = Modifier.testTag("tab_${dest.label}"),
                    )
                }
            }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // v1.8.1: the shell applies the status-bar inset exactly ONCE, here. Every
            // screen Scaffold and TopAppBar zeroes its own window insets, so no screen
            // can double-pad (blank band) or skip padding (content under the clock).
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            // v1.8.1: gentle cross-fade + micro-slide instead of the abrupt swap.
            enterTransition = {
                fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 }
            },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = {
                fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 24 }
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onOpenFolder = { navController.navigateSingleTop(Routes.browse(it)) },
                    onOpenStorage = { navController.navigateSingleTop(Routes.STORAGE) },
                    onOpenRecent = { navController.navigateSingleTop(Routes.RECENT) },
                    onOpenTrash = { navController.navigateSingleTop(Routes.TRASH) },
                    onOpenSettings = { navController.navigateSingleTop(Routes.SETTINGS) },
                    onOpenDrawer = openDrawer,
                )
            }
            composable(
                route = Routes.BROWSE,
                arguments = listOf(
                    navArgument("dir") { type = NavType.StringType; defaultValue = "" },
                    navArgument("reveal") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                    // v1.10.2: "Open with toadFILES" handoff — the archive to open the
                    // extract-or-open dialog for, and (for stream-copied archives that
                    // exist only in the cache) the user-visible destination folder.
                    navArgument("open_archive") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                    navArgument("archive_dest") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                ),
            ) { entry ->
                BrowseScreen(
                    initialDir = entry.arguments?.getString("dir").orEmpty(),
                    bottomPadding = padding.calculateBottomPadding(),
                    onNavigateUp = { navController.popBackStack() },
                    revealName = entry.arguments?.getString("reveal"),
                    openArchivePath = entry.arguments?.getString("open_archive"),
                    archiveDestPath = entry.arguments?.getString("archive_dest"),
                )
            }
            composable(Routes.RECENT) {
                RecentScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onShowInFolder = { path ->
                        // "Show in folder" (owner report): the file must be PINPOINTED,
                        // not just its folder opened — pass the file name along so Browse
                        // scrolls to it and blinks it twice.
                        val f = File(path)
                        val parent = f.parentFile
                        if (parent != null && parent.isDirectory) {
                            navController.navigateSingleTop(
                                Routes.browse(parent.absolutePath, reveal = f.name)
                            )
                        }
                    },
                    onOpenDrawer = openDrawer,
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onShowInFolder = { path ->
                        // Same pinpoint as Recent: hand Browse the file name to flash.
                        val f = File(path)
                        val parent = f.parentFile
                        if (parent != null && parent.isDirectory) {
                            navController.navigateSingleTop(
                                Routes.browse(parent.absolutePath, reveal = f.name)
                            )
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenAbout = { navController.navigateSingleTop(Routes.ABOUT) },
                    onOpenAppearance = { navController.navigateSingleTop(Routes.APPEARANCE) },
                    onOpenDrawer = openDrawer,
                )
            }

            composable(Routes.APPEARANCE) {
                AppearanceScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenWhatsNew = { navController.navigateSingleTop(Routes.WHATS_NEW) },
                )
            }

            composable(Routes.WHATS_NEW) {
                WhatsNewScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.TRASH) {
                TrashScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenDrawer = openDrawer,
                )
            }
            composable(Routes.STORAGE) {
                StorageScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenFolder = { navController.navigateSingleTop(Routes.browse(it)) },
                    onOpenAnalyzer = { navController.navigateSingleTop(Routes.storageAnalyzer()) },
                )
            }

            composable(
                route = Routes.STORAGE_ANALYZER,
                arguments = listOf(navArgument("root") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                StorageAnalyzerScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenFolder = { dir -> navController.navigateSingleTop(Routes.browse(dir)) },
                    initialRoot = entry.arguments?.getString("root").orEmpty(),
                )
            }
        }
    }
    }

    // The shell: the drawer exists ONLY in SIDE_MENU mode. In BOTTOM_BAR mode the
    // app is the plain Scaffold above — no drawer, no hamburger anywhere.
    if (useDrawer) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        "toadFILES",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(
                            start = 28.dp, top = 20.dp, bottom = 4.dp,
                        ),
                    )
                    Text(
                        "Lightweight · Fast · Private",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, bottom = 12.dp),
                    )
                    HorizontalDivider()
                    drawerDestinations.forEach { dest ->
                        val base = dest.route.substringBefore('?')
                        if (dest.route == drawerDividerAfter) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                        NavigationDrawerItem(
                            label = { Text(dest.label) },
                            icon = { Icon(dest.icon, null) },
                            selected = currentBase == base,
                            onClick = { openFromDrawer(base) },
                            modifier = Modifier
                                .testTag("drawer_${dest.label}")
                                .padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            },
        ) { appScaffold() }
    } else {
        appScaffold()
    }
}
