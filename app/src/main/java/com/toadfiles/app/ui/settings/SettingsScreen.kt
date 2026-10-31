package com.toadfiles.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Card
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
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchLimitMenuOpen by remember { mutableStateOf(false) }
    var archiveFolderPickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
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
                .padding(horizontal = 16.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            // Theme / dynamic color / accent live on the dedicated Appearance subpage now.
            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onOpenAppearance != null) { onOpenAppearance?.invoke() },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Appearance", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Theme, Dynamic Color, Accent Color",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Open",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Text("Browsing", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { sortMenuOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Default sort", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                settings.sortMode.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Change",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { vm.setSort(m); sortMenuOpen = false },
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show hidden files", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Names starting with a dot",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.showHidden, onCheckedChange = { vm.setShowHidden(it) })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Search inside file contents", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Open small text files (.txt .md .json .xml .kt …) and search their contents",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.contentSearch, onCheckedChange = { vm.setContentSearch(it) })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { searchLimitMenuOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Search result limit", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${settings.searchResultLimit} matches per search. Higher limits scan longer.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Change",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        DropdownMenu(expanded = searchLimitMenuOpen, onDismissRequest = { searchLimitMenuOpen = false }) {
                            SEARCH_LIMIT_CHOICES.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text("$n") },
                                    onClick = { vm.setSearchResultLimit(n); searchLimitMenuOpen = false },
                                )
                            }
                        }
                    }
                }
            }

            Text("General", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    ChoiceRow(
                        title = "Open Action (ZIP)",
                        choices = ZipOpenAction.entries,
                        selected = settings.zipOpenAction,
                        label = ::zipOpenLabel,
                        onSelect = { vm.setZipOpenAction(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ChoiceRow(
                        title = "Open Action (7z)",
                        choices = SevenZipOpenAction.entries,
                        selected = settings.sevenZipOpenAction,
                        label = ::sevenZipOpenLabel,
                        onSelect = { vm.setSevenZipOpenAction(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ChoiceRow(
                        title = "Back Key Action",
                        choices = BackKeyAction.entries,
                        selected = settings.backKeyAction,
                        label = ::backKeyLabel,
                        onSelect = { vm.setBackKeyAction(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ChoiceRow(
                        title = "Navigation Style",
                        choices = NavStyle.entries,
                        selected = settings.navStyle,
                        label = ::navStyleLabel,
                        onSelect = { vm.setNavStyle(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow(
                        title = "Delete Source After Compression",
                        subtitle = "Remove the original files once the archive is created",
                        checked = settings.deleteAfterCompress,
                        onChange = { vm.setDeleteAfterCompress(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow(
                        title = "Delete Archive After Extraction",
                        subtitle = "Remove the archive once everything is extracted",
                        checked = settings.deleteAfterExtract,
                        onChange = { vm.setDeleteAfterExtract(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow(
                        title = "Confirm Before Aborting",
                        subtitle = "Ask before canceling a running archive operation",
                        checked = settings.abortConfirm,
                        onChange = { vm.setAbortConfirm(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow(
                        title = "Confirm Before Overwriting",
                        subtitle = "Ask before extraction replaces existing files",
                        checked = settings.overwriteConfirm,
                        onChange = { vm.setOverwriteConfirm(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { archiveFolderPickerOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Archive Folder", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                settings.archiveFolder.ifBlank { "Same Folder As Source" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "Change",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (settings.favorites.isNotEmpty()) {
                Text("Favorites", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
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
                                IconButton(onClick = { vm.removeFavorite(path) }) {
                                    Text("Remove", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Text("About", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))

            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAbout() },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("About toadFILES", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Version, storage, credits, changelog · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Open",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (archiveFolderPickerOpen) {
        ArchiveFolderPicker(
            initial = settings.archiveFolder,
            onSelect = {
                vm.setArchiveFolder(it)
                archiveFolderPickerOpen = false
            },
            onDismiss = { archiveFolderPickerOpen = false },
        )
    }
}

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

/** Label + current value + "Change" affordance with a dropdown, as used by "Default sort". */
@Composable
private fun <T> ChoiceRow(
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
            .clickable { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                label(selected),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Change",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
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

/** Label + explanatory subtitle + switch, as used by "Show hidden files". */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
