package com.toadfiles.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.BuildConfig
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.domain.SevenZipOpenAction
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.ZipOpenAction
import com.toadfiles.app.ui.components.ArchiveFolderPicker

private val SEARCH_LIMIT_CHOICES = listOf(100, 250, 500, 1000, 2000)

/**
 * v1.8.1 redesign: every row now carries a tonal icon chip and a clear value/affordance
 * on the trailing side (Material You style), sections are visually grouped cards, and
 * the whole page animates content-size changes so the dropdowns and toggles settle
 * smoothly instead of snapping. All wiring (VM calls, behaviors, dialogs) is unchanged
 * from v1.7.3 — this is a presentation-layer refresh only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    // Deprecated no-op kept for source compatibility; the Trash card was removed in
    // v1.7.3 (Trash lives in the side menu).
    @Suppress("DEPRECATION", "unused")
    onOpenTrash: (() -> Unit)? = null,
    onOpenAbout: () -> Unit,
    // Wired in ToadNavHost to the "appearance" route.
    onOpenAppearance: (() -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null,
    vm: SettingsViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost (v1.8.1: it
        // pads the NavHost once); zeroing these avoids a double status-bar pad.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Settings") },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open navigation menu") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .animateContentSize(),
        ) {
            SectionHeader("Appearance")
            SettingsCard {
                NavigationRow(
                    icon = Icons.Outlined.Palette,
                    title = "Appearance",
                    subtitle = "Theme, Dynamic Color, Accent Color",
                    onClick = { onOpenAppearance?.invoke() },
                )
            }

            SectionHeader("Browsing")
            SettingsCard {
                ChoiceRow(
                    icon = Icons.AutoMirrored.Outlined.Sort,
                    title = "Default sort",
                    choices = SortMode.entries,
                    selected = settings.sortMode,
                    label = ::sortModeLabel,
                    onSelect = { vm.setSort(it) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.Visibility,
                    title = "Show hidden files",
                    subtitle = "Names starting with a dot",
                    checked = settings.showHidden,
                    onChange = { vm.setShowHidden(it) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.ManageSearch,
                    title = "Search inside file contents",
                    subtitle = "Open small text files (.txt .md .json .xml .kt …) and search their contents",
                    checked = settings.contentSearch,
                    onChange = { vm.setContentSearch(it) },
                )
                RowDivider()
                ChoiceRow(
                    icon = Icons.Outlined.Search,
                    title = "Search result limit",
                    choices = SEARCH_LIMIT_CHOICES,
                    selected = settings.searchResultLimit,
                    label = { "$it matches" },
                    onSelect = { vm.setSearchResultLimit(it) },
                )
            }

            SectionHeader("General")
            SettingsCard {
                ChoiceRow(
                    icon = Icons.Outlined.FolderZip,
                    title = "Open Action (ZIP)",
                    choices = ZipOpenAction.entries,
                    selected = settings.zipOpenAction,
                    label = ::zipOpenLabel,
                    onSelect = { vm.setZipOpenAction(it) },
                )
                RowDivider()
                ChoiceRow(
                    icon = Icons.Outlined.Archive,
                    title = "Open Action (7z)",
                    choices = SevenZipOpenAction.entries,
                    selected = settings.sevenZipOpenAction,
                    label = ::sevenZipOpenLabel,
                    onSelect = { vm.setSevenZipOpenAction(it) },
                )
                RowDivider()
                ChoiceRow(
                    icon = Icons.Outlined.Undo,
                    title = "Back Key Action",
                    choices = BackKeyAction.entries,
                    selected = settings.backKeyAction,
                    label = ::backKeyLabel,
                    onSelect = { vm.setBackKeyAction(it) },
                )
                RowDivider()
                ChoiceRow(
                    icon = Icons.Outlined.Menu,
                    title = "Navigation Style",
                    choices = NavStyle.entries,
                    selected = settings.navStyle,
                    label = ::navStyleLabel,
                    onSelect = { vm.setNavStyle(it) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.Delete,
                    title = "Delete Source After Compression",
                    subtitle = "Remove the original files once the archive is created",
                    checked = settings.deleteAfterCompress,
                    onChange = { vm.setDeleteAfterCompress(it) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.FolderZip,
                    title = "Delete Archive After Extraction",
                    subtitle = "Remove the archive once everything is extracted",
                    checked = settings.deleteAfterExtract,
                    onChange = { vm.setDeleteAfterExtract(it) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.Verified,
                    title = "Confirm Before Aborting",
                    subtitle = "Ask before canceling a running archive operation",
                    checked = settings.abortConfirm,
                    onChange = { vm.setAbortConfirm(it) },
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.Verified,
                    title = "Confirm Before Overwriting",
                    subtitle = "Ask before extraction replaces existing files",
                    checked = settings.overwriteConfirm,
                    onChange = { vm.setOverwriteConfirm(it) },
                )
                RowDivider()
                ArchiveFolderRow(
                    current = settings.archiveFolder,
                    onSelect = { vm.setArchiveFolder(it) },
                )
            }

            if (settings.favorites.isNotEmpty()) {
                SectionHeader("Favorites")
                SettingsCard {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        settings.favorites.sorted().forEach { path ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    "Remove",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable { vm.removeFavorite(path) },
                                )
                            }
                        }
                    }
                }
            }

            SectionHeader("About")
            SettingsCard {
                NavigationRow(
                    icon = Icons.Outlined.Info,
                    title = "About toadFILES",
                    subtitle = "Version, updates, changelog · v${BuildConfig.VERSION_NAME}",
                    onClick = { onOpenAbout() },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- section scaffolding ---------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Round tonal chip wrapping a row's leading icon. */
@Composable
private fun IconChip(icon: ImageVector) {
    Box(
        Modifier
            .padding(start = 12.dp, end = 12.dp)
            .size(38.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

// ---- row types -------------------------------------------------------------------------

/** A row that navigates somewhere: icon chip, title + subtitle, trailing chevron. */
@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Label + current value + dropdown, opened by tapping anywhere on the row. */
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                label(selected),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(label(c)) },
                    onClick = { onSelect(c); open = false },
                )
            }
        }
    }
}

/** Label + explanatory subtitle + switch. */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Archive folder target: tap the row to open the shared folder picker. */
@Composable
private fun ArchiveFolderRow(current: String, onSelect: (String) -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { pickerOpen = true }
            .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(Icons.Outlined.FolderZip)
        Column(Modifier.weight(1f)) {
            Text("Archive Folder", style = MaterialTheme.typography.bodyLarge)
            Text(
                current.ifBlank { "Same Folder As Source" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pickerOpen) {
        ArchiveFolderPicker(
            initial = current,
            onSelect = {
                onSelect(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

private fun sortModeLabel(mode: SortMode) = mode.name.lowercase().replaceFirstChar { it.uppercase() }

private fun zipOpenLabel(action: ZipOpenAction) = when (action) {
    ZipOpenAction.MENU -> "Show Menu"
    ZipOpenAction.EXTRACT -> "Extract"
    ZipOpenAction.OPEN_WITH -> "Open With"
}

private fun sevenZipOpenLabel(action: SevenZipOpenAction) = when (action) {
    SevenZipOpenAction.MENU -> "Show Menu"
    SevenZipOpenAction.EXTRACT -> "Extract"
    SevenZipOpenAction.OPEN_WITH -> "Open With"
}

private fun backKeyLabel(action: BackKeyAction) = when (action) {
    BackKeyAction.PARENT -> "Back To Parent Folder"
    BackKeyAction.EXIT -> "Exit App"
}

private fun navStyleLabel(style: NavStyle) = when (style) {
    NavStyle.BOTTOM_BAR -> "Bottom Bar"
    NavStyle.SIDE_MENU -> "Side Menu"
}
