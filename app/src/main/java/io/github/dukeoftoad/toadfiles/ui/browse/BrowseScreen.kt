package io.github.dukeoftoad.toadfiles.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.toadfiles.ToadViewModelFactory
import io.github.dukeoftoad.toadfiles.data.Clipboard
import io.github.dukeoftoad.toadfiles.domain.FilterMode
import io.github.dukeoftoad.toadfiles.domain.Format
import io.github.dukeoftoad.toadfiles.domain.SortMode
import io.github.dukeoftoad.toadfiles.ui.components.ConflictDialog
import io.github.dukeoftoad.toadfiles.ui.components.ConfirmDialog
import io.github.dukeoftoad.toadfiles.ui.components.EmptyState
import io.github.dukeoftoad.toadfiles.ui.components.FileRow
import io.github.dukeoftoad.toadfiles.ui.components.NameInputDialog
import io.github.dukeoftoad.toadfiles.ui.components.ProgressOverlay
import io.github.dukeoftoad.toadfiles.ui.components.ResultDialog
import io.github.dukeoftoad.toadfiles.ui.components.SortMenu
import io.github.dukeoftoad.toadfiles.util.Intents
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    initialDir: String,
    bottomPadding: Dp,
    onNavigateUp: () -> Unit,
    vm: BrowseViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }

    val rootDir = remember(initialDir) {
        if (initialDir.isNotBlank()) File(initialDir) else null
    }
    LaunchedEffect(rootDir) { rootDir?.let { vm.openDir(it) } }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    val selectionMode = state.selection.isNotEmpty()
    BackHandler(enabled = selectionMode) { vm.clearSelection() }
    BackHandler(enabled = !selectionMode) {
        val dir = state.dir
        val canGoUp = dir != null && state.breadcrumbs.size > 1 && dir.parentFile?.canRead() == true
        if (canGoUp) vm.openDir(dir!!.parentFile!!) else onNavigateUp()
    }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${state.selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Outlined.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Outlined.SelectAll, "Select all")
                        }
                        IconButton(onClick = {
                            val ok = Intents.shareFiles(context, state.selection.filter { it.isFile }.toList())
                            if (!ok) vm.requestToast("No app can share these files")
                        }) { Icon(Icons.Outlined.Share, "Share") }
                        IconButton(onClick = { vm.requestDelete(state.selection.toList()) }) {
                            Icon(Icons.Outlined.Delete, "Delete")
                        }
                        IconButton(onClick = { moreMenuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy to…") },
                                onClick = {
                                    moreMenuOpen = false
                                    vm.copyToClipboard(Clipboard.Mode.COPY, state.selection.toList())
                                },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Move to…") },
                                onClick = {
                                    moreMenuOpen = false
                                    vm.copyToClipboard(Clipboard.Mode.MOVE, state.selection.toList())
                                },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Zip ${state.selection.size} item(s)") },
                                onClick = {
                                    moreMenuOpen = false
                                    vm.zipSelected(state.selection.toList())
                                },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Properties") },
                                onClick = {
                                    moreMenuOpen = false
                                    state.selection.firstOrNull()?.let { vm.requestProperties(it) }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            )
                            val first = state.selection.firstOrNull()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (first != null && first.path in state.favoritePaths)
                                            "Remove favorite" else "Add favorite"
                                    )
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    first?.let { vm.toggleFavorite(it.path) }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (first != null && first.path in state.favoritePaths)
                                            Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                        null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    moreMenuOpen = false
                                    if (state.selection.size == 1) {
                                        state.selection.firstOrNull()?.let { vm.requestRename(it) }
                                    } else {
                                        vm.requestToast("Select exactly one item to rename")
                                    }
                                },
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(state.dir?.name ?: "Browse", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { sortMenuOpen = true }) { Icon(Icons.Outlined.Sort, "Sort") }
                        SortMenu(
                            expanded = sortMenuOpen,
                            current = state.settings.sortMode,
                            onSelect = { vm.setSort(it); sortMenuOpen = false },
                            onDismiss = { sortMenuOpen = false },
                        )
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(Icons.Outlined.FilterList, "Filter")
                        }
                        FilterMenu(
                            expanded = filterMenuOpen,
                            current = state.filterMode,
                            onSelect = { vm.setFilter(it); filterMenuOpen = false },
                            onDismiss = { filterMenuOpen = false },
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = { vm.requestNewFolder() }) {
                    Icon(Icons.Outlined.CreateNewFolder, "New folder")
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.fillMaxSize()) {
                if (!selectionMode) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { vm.setQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Search in this folder tree") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) {
                                    Icon(Icons.Outlined.Close, "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    Breadcrumbs(state.breadcrumbs) { vm.openDir(it) }
                }

                val visible = state.items.filter { state.filterMode.matches(it.name) }
                when {
                    state.loading -> Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }

                    visible.isEmpty() -> EmptyState(
                        when {
                            state.error != null -> state.error!!
                            state.query.isNotBlank() -> "No matches for \"${state.query}\""
                            else -> "Empty folder"
                        }
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(visible, key = { _, it -> it.file.path }) { _, item ->
                            FileRow(
                                item = item,
                                selected = item.file in state.selection,
                                isFavorite = item.file.path in state.favoritePaths,
                                subtitle = if (item.isDir) null else
                                    "${Format.size(item.size)} · ${Format.date(item.lastModified)}",
                                onClick = {
                                    when {
                                        selectionMode -> vm.toggleSelection(item)
                                        item.isDir -> vm.openDir(item.file)
                                        else -> {
                                            if (!Intents.openFile(context, item.file)) {
                                                vm.requestToast("No app can open this file")
                                            }
                                        }
                                    }
                                },
                                onLongClick = { if (!selectionMode) vm.toggleSelection(item) },
                            )
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }

            if (!selectionMode && state.clipboard != null) {
                val clip = state.clipboard!!
                PasteBar(
                    mode = clip.mode.name.lowercase(),
                    count = clip.files.size,
                    onPaste = { vm.pasteFromClipboard() },
                    onDiscard = { vm.clearClipboard() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            }
        }
    }

    // ---- dialogs ----
    if (state.pendingNewFolder) {
        NameInputDialog(
            title = "New folder",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { vm.createFolder(it) },
            onDismiss = { vm.dismissNewFolder() },
        )
    }
    state.pendingRename?.let { file ->
        NameInputDialog(
            title = "Rename",
            initialValue = file.name,
            confirmLabel = "Rename",
            onConfirm = { vm.rename(file, it) },
            onDismiss = { vm.dismissRename() },
        )
    }
    state.pendingDelete?.let { files ->
        ConfirmDialog(
            title = "Delete ${files.size} item(s)?",
            message = "Deleted files cannot be recovered from toadFILES. " +
                "Some may remain in the other app's trash if it keeps one.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.confirmDelete() },
            onDismiss = { vm.dismissDelete() },
        )
    }
    state.pendingConflict?.let { pc ->
        ConflictDialog(
            conflicts = pc.conflicts,
            targetName = pc.targetDir.name,
            onDecide = { vm.resolveConflict(it) },
            onDismiss = { vm.dismissConflict() },
        )
    }
    state.progress?.let { (title, p) ->
        ProgressOverlay(
            text = title,
            fraction = if (p.total > 0) p.done.toFloat() / p.total else null,
            onCancel = { vm.cancelOp() },
        )
    }
    state.result?.let { r ->
        ResultDialog(
            summary = "${state.resultTitle}: ${r.summary}",
            failures = r.failures,
            onDismiss = { vm.dismissResult() },
        )
    }
    state.properties?.let { file ->
        PropertiesDialog(
            file = file,
            detail = state.propertiesDetail,
            favorite = file.path in state.favoritePaths,
            onToggleFavorite = { vm.toggleFavorite(file.path) },
            onDismiss = { vm.dismissProperties() },
        )
    }
}

@Composable
private fun Breadcrumbs(path: List<File>, onOpen: (File) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        items(path) { seg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpen(seg) }) {
                    Text(
                        seg.name.ifEmpty { "/" },
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Text("›", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun FilterMenu(
    expanded: Boolean,
    current: FilterMode,
    onSelect: (FilterMode) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FilterMode.entries.forEach { m ->
            DropdownMenuItem(
                text = { Text(if (m == current) "${m.label} ✓" else m.label) },
                onClick = { onSelect(m) },
            )
        }
    }
}

@Composable
private fun PasteBar(
    mode: String,
    count: Int,
    onPaste: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ContentPaste, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                "$count item(s) ready to $mode",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onPaste) { Text("Paste") }
            IconButton(onClick = onDiscard) {
                Icon(
                    Icons.Outlined.Close, "Discard",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PropertiesDialog(
    file: File,
    detail: PropertiesDetail?,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2) },
        text = {
            Column {
                Text("Path: ${file.path}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Text("Type: ${if (file.isDirectory) "Folder" else "File"}")
                if (detail != null) {
                    Text("Size: ${detail.sizeText}")
                    detail.children?.let { Text("Items: $it") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Computing size…", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("Modified: ${Format.dateTime(file.lastModified())}")
                Text(
                    "Readable: ${if (file.canRead()) "yes" else "no"} · " +
                        "Writable: ${if (file.canWrite()) "yes" else "no"}"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onToggleFavorite) {
                Text(if (favorite) "★ Remove favorite" else "☆ Add favorite")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
