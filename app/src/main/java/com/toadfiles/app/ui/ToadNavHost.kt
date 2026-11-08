package com.toadfiles.app.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TrendingUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.toadfiles.app.ui.components.categoryIconFor
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
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.IS_LITE
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.ui.theme.LocalToadMotion
import com.toadfiles.app.ui.theme.ToadFace

object Routes {
    const val HOME = "home"
    const val BROWSE = "browse?dir={dir}&reveal={reveal}&open_archive={openArchive}&archive_dest={archiveDest}"
    // v2.5.0: the optional category arg lands the Categories screen straight in a
    // drill-in — the drawer's Category dropdown and Most-used section (and Home's
    // most-used strip) jump directly to one category instead of the grid.
    const val CATEGORIES = "categories?category={category}"
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

    /** v2.5.0: the Categories route pre-opened on one category (drawer / Quick access). */
    fun categories(category: FileCategory): String = "categories?category=${category.name}"

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
    // v2.5.0: the drawer's Most-used section reads the live use trackers.
    val navFolderUses by settingsVm.folderUses.collectAsStateWithLifecycle()
    val navCategoryUses by settingsVm.categoryUses.collectAsStateWithLifecycle()
    // v1.11: nav transitions follow the user's motion style (Springy toad / Calm /
    // Cinematic), chosen on the Appearance tab.
    val motion = LocalToadMotion.current
    // Lite (docs/LITE_EDITION_PLAN.md §3 phase 2): the Categories media screens are a
    // full-edition tab — lite never registers the route, so the tab row hides it and
    // R8 strips the screen's code from the release build.
    val destinations = buildList {
        add(BottomDestination(Routes.HOME, "Home") { Icon(Icons.Outlined.Home, null) })
        add(BottomDestination(Routes.BROWSE, "Browse") { Icon(Icons.Outlined.Folder, null) })
        if (!IS_LITE) {
            add(BottomDestination(Routes.CATEGORIES, "Categories") { Icon(Icons.Outlined.Category, null) })
        }
        add(BottomDestination(Routes.SETTINGS, "Settings") { Icon(Icons.Outlined.Settings, null) })
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Full destination set for the side drawer. Home and Categories were missing in
    // v1.7.3, making them unreachable in SIDE_MENU mode; About sits below a divider.
    // Lite drops Categories with the tab (Storage never was a primary destination).
    // v2.5.0: the plain "Categories" entry is REPLACED by the Category dropdown +
    // Most-used section below (owner request) — they render in the same slot via
    // DrawerCategorySection, so the flat list no longer carries a Categories row.
    val drawerDestinations = buildList {
        add(DrawerDestination(Routes.HOME, "Home", Icons.Outlined.Home))
        add(DrawerDestination(Routes.BROWSE, "Browse", Icons.Outlined.Folder))
        add(DrawerDestination(Routes.RECENT, "Recent", Icons.Outlined.History))
        add(DrawerDestination(Routes.TRASH, "Trash", Icons.Outlined.Delete))
        // ---- secondary group ----
        add(DrawerDestination(Routes.SETTINGS, "Settings", Icons.Outlined.Settings))
        add(DrawerDestination(Routes.ABOUT, "About", Icons.Outlined.Info))
    }
    val drawerDividerAfter = Routes.TRASH
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentBase = backStackEntry?.destination?.route?.substringBefore('?')
    val openFromDrawer: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        // Same reselection semantics as the bottom bar: never stack duplicates — and
        // v2.1.0: re-tapping the drawer's ALREADY-ACTIVE item resets that tab to its
        // top level (TabReselect) instead of a no-op navigation.
        val base = route.substringBefore('?')
        if (currentBase == base) {
            TabReselect.reselect(base)
        } else {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // Hamburger affordance — passed to top-level screens ONLY in SIDE_MENU mode.
    val openDrawer: (() -> Unit)? = if (useDrawer) {
        { scope.launch { drawerState.open() } }
    } else null

    // Flaw fix (owner report): tapping an extractable archive on a media surface
    // (Recent, Home's strip/favorites, the Categories drill-in) routes into
    // Browse's extract-or-open dialog — extract here / extract to… / open — instead
    // of firing a VIEW intent that, now that toadFILES registers as an archive
    // handler, bounces back into the app itself or off into another archiver.
    val openArchive: (File) -> Unit = { archive ->
        navController.navigateSingleTop(
            Routes.browse(
                archive.parentFile?.absolutePath ?: "",
                openArchive = archive.absolutePath,
            )
        )
    }

    // v2.5.0 drawer quick-jumps. Unlike openFromDrawer these navigate FRESH (no
    // state restore): a sub-category tap must land in THAT category's drill-in, and
    // a most-used-folder tap in THAT folder — restoring a saved Categories/Browse
    // state would silently ignore the pick. Back still returns to where you were.
    val openCategoryFromDrawer: (FileCategory) -> Unit = { category ->
        scope.launch { drawerState.close() }
        navController.navigateSingleTop(Routes.categories(category))
    }
    val openFolderFromDrawer: (String) -> Unit = { path ->
        scope.launch { drawerState.close() }
        navController.navigateSingleTop(Routes.browse(path))
    }

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
                            // v2.1.0 tab re-tap: tapping the ALREADY-ACTIVE tab means
                            // "take me back up" — Browse returns to its starting folder,
                            // Categories closes the drill-in, Recent drops its filter,
                            // Trash clears the selection (see TabReselect). Switching
                            // tabs still keeps every tab's state as before.
                            if (currentBase == baseRoute) {
                                TabReselect.reselect(baseRoute)
                                return@NavigationBarItem
                            }
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
            // v1.11: motion-style-driven transitions (see LocalToadMotion).
            enterTransition = { motion.screenEnter },
            exitTransition = { motion.screenExit },
            popEnterTransition = { motion.popEnter },
            popExitTransition = { motion.popExit },
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
                    onOpenArchive = openArchive,
                    // v2.5.0: the most-used-categories strip jumps straight into a
                    // drill-in. (Lite never shows the strip — no Categories screen.)
                    onOpenCategory = { navController.navigateSingleTop(Routes.categories(it)) },
                )
            }
            composable(
                route = Routes.BROWSE,
                arguments = listOf(
                    navArgument("dir") { type = NavType.StringType; defaultValue = "" },
                    navArgument("reveal") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                    // v1.11: "Open with toadFILES" handoff — the archive to open the
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
                    onOpenArchive = openArchive,
                )
            }
            if (!IS_LITE) {
                // Full edition only: per-category media library (per-category directory
                // scans + cursor churn + large grid state — lite's heaviest runtime
                // cut after video thumbnails).
                composable(
                    route = Routes.CATEGORIES,
                    arguments = listOf(
                        navArgument("category") {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                    ),
                ) { entry ->
                    val initialCategory = entry.arguments?.getString("category")
                        ?.let { runCatching { FileCategory.valueOf(it) }.getOrNull() }
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
                        onOpenArchive = openArchive,
                        initialCategory = initialCategory,
                    )
                }
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
                    // Lite ships no changelog asset and no update checker — the About
                    // screen hides both cards there and never calls this.
                    onOpenWhatsNew = { if (!IS_LITE) navController.navigateSingleTop(Routes.WHATS_NEW) },
                )
            }

            if (!IS_LITE) {
                // Full edition only: the bundled-changelog What's New page. Lite skips
                // the route and the syncChangelog asset copy (plan §3 phase 2).
                composable(Routes.WHATS_NEW) {
                    WhatsNewScreen(
                        bottomPadding = padding.calculateBottomPadding(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.TRASH) {
                TrashScreen(
                    bottomPadding = padding.calculateBottomPadding(),
                    onBack = { navController.popBackStack() },
                    onOpenDrawer = openDrawer,
                )
            }
            if (!IS_LITE) {
                // Full edition only: per-volume storage + the biggest-folders analyzer
                // (a full-tree walk of the volume — the heaviest single operation in
                // the app; lite's Home card is hidden with it, plan §3 phase 2).
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
    }

    // The shell: the drawer exists ONLY in SIDE_MENU mode. In BOTTOM_BAR mode the
    // app is the plain Scaffold above — no drawer, no hamburger anywhere.
    if (useDrawer) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    // v2.5.0: the sheet scrolls — with a Category dropdown expanded
                    // (or on short screens) the lower entries were pushed out of view
                    // and untappable; now they stay reachable everywhere.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .testTag("drawer_scroll"),
                    ) {
                    // The mascot welcomes you in — same face as the splash.
                    ToadFace(
                        size = 56.dp,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                    )
                    Text(
                        "toadFILES",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(
                            start = 28.dp, top = 8.dp, bottom = 4.dp,
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
                        // v2.5.0: the Category dropdown + Most-used section replace the
                        // plain "Categories" entry, sitting exactly where it used to —
                        // between Browse and Recent. Full edition only: lite has no
                        // Categories screen to open, so the whole block folds away.
                        if (dest.label == "Recent" && !IS_LITE) {
                            DrawerCategorySection(
                                currentBase = currentBase,
                                categoryUses = navCategoryUses,
                                folderUses = navFolderUses,
                                onOpenCategory = openCategoryFromDrawer,
                                onOpenFolder = openFolderFromDrawer,
                            )
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
                        // Audit A24: the divider renders AFTER the item it follows — it
                        // used to render BEFORE ("dividerAfter" matched on the way in),
                        // cutting Trash off from its primary group while Settings/About
                        // sat undivided below.
                        if (dest.route == drawerDividerAfter) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                    }
                    }
                }
            },
        ) { appScaffold() }
    } else {
        appScaffold()
    }
}

/** How many entries each Most-used group shows in the drawer (owner cap: small lists). */
private const val DRAWER_MOST_USED_LIMIT = 3

/**
 * v2.5.0: the two new drawer sections (owner request) replacing the plain
 * "Categories" entry:
 *
 *  1. **Category** — a mini dropdown of the file categories (the same six the
 *     Categories screen shows); tapping one lands directly in that category's
 *     drill-in and records the open for the Most-used tracker.
 *  2. **Most used** — a mini dropdown with BOTH groups: the most-opened categories
 *     and the most-visited folders (the existing folder-use tracker). Empty on a
 *     fresh install, with an honest hint instead of blank space.
 *
 * Both sections expand in place (the chevron rotates); re-tapping collapses.
 */
@Composable
private fun DrawerCategorySection(
    currentBase: String?,
    categoryUses: Map<String, Int>,
    folderUses: Map<String, Int>,
    onOpenCategory: (FileCategory) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    var categoryExpanded by rememberSaveable { mutableStateOf(false) }
    var mostUsedExpanded by rememberSaveable { mutableStateOf(false) }
    val categoriesBase = Routes.CATEGORIES.substringBefore('?')

    // ---- 1. Category (+ mini dropdown of sub-categories) ----
    NavigationDrawerItem(
        label = { Text("Category") },
        icon = { Icon(Icons.Outlined.Category, null) },
        selected = currentBase == categoriesBase,
        onClick = { categoryExpanded = !categoryExpanded },
        badge = {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (categoryExpanded) "Collapse categories" else "Expand categories",
                modifier = Modifier.rotate(if (categoryExpanded) 180f else 0f),
            )
        },
        modifier = Modifier
            .testTag("drawer_Category")
            .padding(NavigationDrawerItemDefaults.ItemPadding),
    )
    if (categoryExpanded) {
        FileCategory.entries.forEach { category ->
            DrawerSubRow(
                label = category.label,
                icon = categoryIconFor(category),
                onClick = { onOpenCategory(category) },
                modifier = Modifier.testTag("drawer_sub_${category.label}"),
            )
        }
    }

    // ---- 2. Most used (+ mini dropdown: top categories AND top folders) ----
    NavigationDrawerItem(
        label = { Text("Most used") },
        icon = { Icon(Icons.Outlined.TrendingUp, null) },
        selected = false,
        onClick = { mostUsedExpanded = !mostUsedExpanded },
        badge = {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (mostUsedExpanded) "Collapse most used" else "Expand most used",
                modifier = Modifier.rotate(if (mostUsedExpanded) 180f else 0f),
            )
        },
        modifier = Modifier
            .testTag("drawer_Most used")
            .padding(NavigationDrawerItemDefaults.ItemPadding),
    )
    if (mostUsedExpanded) {
        val topCategories = categoryUses.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(DRAWER_MOST_USED_LIMIT)
            .mapNotNull { (name, _) -> runCatching { FileCategory.valueOf(name) }.getOrNull() }
        val topFolders = folderUses.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(DRAWER_MOST_USED_LIMIT)
            .mapNotNull { (path, _) -> File(path).takeIf { it.isDirectory } }

        if (topCategories.isEmpty() && topFolders.isEmpty()) {
            Text(
                "Nothing yet — open categories and folders to fill this in",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("drawer_most_used_empty")
                    .padding(start = 52.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
            )
        } else {
            if (topCategories.isNotEmpty()) {
                DrawerGroupLabel("Categories")
                topCategories.forEach { category ->
                    DrawerSubRow(
                        label = category.label,
                        icon = categoryIconFor(category),
                        onClick = { onOpenCategory(category) },
                        modifier = Modifier.testTag("drawer_used_${category.label}"),
                    )
                }
            }
            if (topFolders.isNotEmpty()) {
                DrawerGroupLabel("Folders")
                topFolders.forEach { folder ->
                    DrawerSubRow(
                        label = folder.name,
                        icon = Icons.Outlined.Folder,
                        onClick = { onOpenFolder(folder.absolutePath) },
                        modifier = Modifier.testTag("drawer_used_folder_${folder.name}"),
                    )
                }
            }
        }
    }
}

/** Small group heading inside the Most-used dropdown ("Categories" / "Folders"). */
@Composable
private fun DrawerGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 52.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** An indented sub-row inside a drawer dropdown — icon + label, one tap to jump. */
@Composable
private fun DrawerSubRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 40.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
