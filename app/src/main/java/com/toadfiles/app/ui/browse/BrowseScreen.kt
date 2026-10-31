package com.toadfiles.app.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.ViewList
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.Environment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.ui.components.ConflictDialog
import com.toadfiles.app.ui.components.ConfirmDialog
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.FileActionSheet
import com.toadfiles.app.ui.components.FileGridCell
import com.toadfiles.app.ui.components.FileRow
import com.toadfiles.app.ui.components.NameInputDialog
import com.toadfiles.app.ui.components.ProgressOverlay
import com.toadfiles.app.ui.components.PropertiesSheet
import com.toadfiles.app.ui.components.ResultDialog
import com.toadfiles.app.ui.components.SortMenu
import com.toadfiles.app.util.Intents
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
    // v1.7.3 abortConfirm: set when the user cancels the progress overlay while the
    // confirmation setting is on; cleared by the dialog's buttons.
    var pendingAbortConfirm by remember { mutableStateOf(false) }

    val rootDir = remember(initialDir) {
        // Bottom-bar Browse passes an empty dir; default to the external storage root
        // so the tab opens to a real folder instead of an empty screen.
        if (initialDir.isNotBlank()) File(initialDir) else Environment.getExternalStorageDirectory()
    }
    LaunchedEffect(rootDir) { vm.openDir(rootDir) }

    var pendingInstallApk by remember { mutableStateOf<File?>(null) }
    var pendingActionSheet by remember { mutableStateOf<FileItem?>(null) }
    /** An archive (.zip/.7z) the user tapped: offered the extract-or-open dialog
     *  instead of an open-with intent (v1.7.1 fix: archives never fall through to
     *  ACTION_VIEW). v1.7.3: the dialog only shows for tap action MENU — EXTRACT and
     *  OPEN_WITH (Settings → Archive actions) are dispatched directly in openItem. */
    var pendingArchiveTap by remember { mutableStateOf<File?>(null) }
    /** An archive the user picked "Extract to…" for: ask for the destination folder name. */
    var pendingExtractTo by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    // Post-extract redirect (v1.7.1): navigate into the freshly extracted folder using
    // Browse's own internal navigation (openDir re-lists and rebuilds breadcrumbs).
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ExtractEvent.ExtractCompleted -> vm.openDir(e.destination)
            }
        }
    }

    // Undo for deletes: trashed items go straight back to their original location.
    LaunchedEffect(state.lastTrashed) {
        val trashed = state.lastTrashed ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Moved ${trashed.size} item(s) to trash",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.dismissUndo()
    }

    val selectionMode = state.selection.isNotEmpty()
    BackHandler(enabled = selectionMode) { vm.clearSelection() }
    BackHandler(enabled = !selectionMode) {
        // v1.7.3 back-key setting: EXIT leaves Browse from any depth — the same thing
        // the old back-at-root behavior did (onNavigateUp → popBackStack; Browse is a
        // bottom-bar tab, so popping with an empty stack stays put, matching the
        // previous root behavior exactly). PARENT walks up one folder per press.
        if (state.settings.backKeyAction == BackKeyAction.EXIT) {
            onNavigateUp()
            return@BackHandler
        }
        val dir = state.dir
        val canGoUp = dir != null && state.breadcrumbs.size > 1 && dir.parentFile?.canRead() == true
        val parent = dir?.parentFile
        if (canGoUp && parent != null) vm.openDir(parent) else onNavigateUp()
    }
    // Registered last, so it wins: while a search is active, Back first clears the search
    // instead of jumping out of the folder.
    BackHandler(enabled = !selectionMode && state.query.isNotBlank()) { vm.setQuery("") }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost; zeroing
        // these avoids a double status-bar pad (the v1.7.3 top "dead band").
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                            // Real share: system share sheet via ACTION_SEND/_MULTIPLE.
                            // Folders can't be shared by Android — only the files in the selection.
                            val shareable = state.selection.filter { it.isFile }.toList()
                            if (shareable.isEmpty()) {
                                vm.requestToast("Folders can't be shared — pick files")
                            } else if (!Intents.shareFiles(context, shareable)) {
                                vm.requestToast("Could not share these files")
                            }
                            vm.clearSelection()
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
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Compress ${state.selection.size} item(s)…") },
                                onClick = {
                                    moreMenuOpen = false
                                    vm.requestZip(state.selection.toList())
                                },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                            )
                            val single = state.selection.singleOrNull()
                            if (single != null && single.isFile && ArchiveOps.canExtract(single.name)) {
                                DropdownMenuItem(
                                    text = { Text("Extract here") },
                                    onClick = {
                                        moreMenuOpen = false
                                        vm.extractZip(single)
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.FolderZip, null) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Extract to…") },
                                    onClick = {
                                        moreMenuOpen = false
                                        pendingExtractTo = single
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                                )
                            }
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
                        IconButton(onClick = { vm.toggleHiddenFiles() }) {
                            Icon(
                                if (state.settings.showHidden) Icons.Outlined.Visibility
                                else Icons.Outlined.VisibilityOff,
                                if (state.settings.showHidden) "Hide hidden files" else "Show hidden files",
                            )
                        }
                        IconButton(onClick = { vm.toggleLayout() }) {
                            Icon(
                                if (state.settings.browseLayout == BrowseLayout.GRID)
                                    Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                "Toggle list / grid view",
                            )
                        }
                        IconButton(onClick = { sortMenuOpen = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                        SortMenu(
                            expanded = sortMenuOpen,
                            current = state.settings.sortMode,
                            currentOrder = state.settings.sortOrder,
                            onSelect = { vm.setSort(it); sortMenuOpen = false },
                            onSelectOrder = { vm.setSortOrder(it); sortMenuOpen = false },
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
                // Pull-to-refresh (v1.7.3): drag down anywhere on the listing (or the
                // empty state) to re-list the current folder. A refresh is a plain
                // folder reload — like every other load() it clears an active search.
                PullToRefreshBox(
                    isRefreshing = state.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                val visible = state.items.filter { state.filterMode.matches(it.name) }
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
                            if (state.searching) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else if (state.query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) {
                                    Icon(Icons.Outlined.Close, "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    Breadcrumbs(state.breadcrumbs) { vm.openDir(it) }
                    Text(
                        buildString {
                            append(visible.size)
                            append(if (visible.size == 1) " item" else " items")
                            if (state.filterMode != FilterMode.ALL) append(" · ${state.filterMode.label.lowercase()}")
                            if (state.query.isNotBlank()) append(" · search results")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                    // The trash lives behind Home's trash button and Settings —
                    // the listing itself stays clean.
                    if (state.searchCapped) {
                        Text(
                            "Reached the ${state.settings.searchResultLimit}-result limit. " +
                                "Narrow the query, or raise the limit in Settings → Browsing.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                }

                val openItem: (FileItem) -> Unit = { item ->
                    when {
                        selectionMode -> vm.toggleSelection(item)
                        item.isDir -> vm.openDir(item.file)
                        item.name.lowercase().endsWith(".apk") -> pendingInstallApk = item.file
                        ArchiveOps.canExtract(item.name) -> when (vm.archiveTapAction(item.name)) {
                            // MENU: the v1.7.1 extract-or-open dialog (default).
                            ArchiveTapAction.SHOW_MENU -> pendingArchiveTap = item.file
                            // EXTRACT: the same pipeline as the dialog / "Extract here"
                            // menu entry, including its target-exists handling.
                            ArchiveTapAction.EXTRACT -> vm.extractZip(item.file)
                            // OPEN_WITH: pre-v1.7.1 behavior — straight ACTION_VIEW.
                            ArchiveTapAction.OPEN_WITH -> if (!Intents.openFile(context, item.file)) {
                                vm.requestToast("No app can open this file")
                            }
                        }
                        else -> if (!Intents.openFile(context, item.file)) {
                            vm.requestToast("No app can open this file")
                        }
                    }
                }
                val longPressItem: (FileItem) -> Unit = { item ->
                    // Press-and-hold starts multi-select (Google Files style); while
                    // already selecting it keeps toggling rows. The per-item action
                    // sheet stays one tap away on the row's ⋮ button.
                    vm.toggleSelection(item)
                }

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

                    else -> if (state.settings.browseLayout == BrowseLayout.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(112.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            gridItems(visible, key = { it.file.path }) { item ->
                                FileGridCell(
                                    item = item,
                                    selected = item.file in state.selection,
                                    isFavorite = item.file.path in state.favoritePaths,
                                    onClick = { openItem(item) },
                                    onLongClick = { longPressItem(item) },
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(visible, key = { _, it -> it.file.path }) { _, item ->
                                FileRow(
                                    item = item,
                                    selected = item.file in state.selection,
                                    isFavorite = item.file.path in state.favoritePaths,
                                    subtitle = if (item.isDir) null else
                                        "${Format.size(item.size)} · ${Format.date(item.lastModified)}",
                                    onClick = { openItem(item) },
                                    onLongClick = { longPressItem(item) },
                                    onMore = { pendingActionSheet = item },
                                )
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
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
    // Compress dialog (v1.7.3): format (ZIP / 7z), compression level, target name and
    // destination (archive-folder setting or current folder) — archives are never
    // created under a silent auto name.
    state.pendingZipFiles?.let { files ->
        CompressDialog(
            count = files.size,
            name = state.pendingZipName,
            destination = state.pendingZipDestination,
            format = state.pendingZipFormat,
            level = state.pendingZipLevel,
            onNameChange = { vm.setZipName(it) },
            onFormatChange = { vm.setZipFormat(it) },
            onLevelChange = { vm.setZipLevel(it) },
            onConfirm = { vm.confirmZip(state.pendingZipName) },
            onDismiss = { vm.dismissZipDialog() },
        )
    }
    // Target archive already exists and overwriteConfirm is on: ask before replacing
    // (Cancel returns to the compress dialog so the user can pick another name).
    state.pendingZipOverwrite?.let { target ->
        ConfirmDialog(
            title = "Overwrite \"${target.name}\"?",
            message = "An archive with this name already exists in " +
                "${target.parent ?: "the destination"}. Compressing again will replace it.",
            confirmLabel = "Overwrite",
            destructive = true,
            onConfirm = { vm.confirmZipOverwrite() },
            onDismiss = { vm.dismissZipOverwrite() },
        )
    }
    // Tapping an archive (.zip/.7z) asks what to do: extract (most common) or open with
    // a viewer app — never a silent ACTION_VIEW (v1.7.1 open-with fix).
    pendingArchiveTap?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingArchiveTap = null },
            title = { Text(archive.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Extract this archive into a new folder here, or open it with an app.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        pendingArchiveTap = null
                        if (!Intents.openFile(context, archive)) vm.requestToast("No app can open this file")
                    }) { Text("Open") }
                    TextButton(onClick = {
                        pendingArchiveTap = null
                        vm.extractZip(archive)
                    }) { Text("Extract here") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingArchiveTap = null }) { Text("Cancel") } },
        )
    }
    // "Extract to…": the user names the destination folder — under the default
    // destination (archive folder when set, v1.7.3), else the current directory.
    pendingExtractTo?.let { archive ->
        NameInputDialog(
            title = "Extract to…",
            initialValue = archive.nameWithoutExtension,
            destination = vm.archiveFolderOr(state.dir)?.path,
            confirmLabel = "Extract",
            onConfirm = {
                pendingExtractTo = null
                vm.extractZip(archive, targetName = it)
            },
            onDismiss = { pendingExtractTo = null },
        )
    }
    pendingInstallApk?.let { apk ->
        ConfirmDialog(
            title = "Install \"${apk.name}\"?",
            message = "toadFILES hands this APK to the Android package installer. " +
                "Android may ask you to allow installs from toadFILES first.",
            confirmLabel = "Install",
            onConfirm = {
                when (Intents.installApk(context, apk)) {
                    Intents.InstallResult.STARTED -> Unit // the installer took over
                    Intents.InstallResult.NEEDS_PERMISSION -> vm.requestToast(
                        "Allow \"Install unknown apps\" for toadFILES, return here, then tap Install again"
                    )
                    Intents.InstallResult.SETTINGS_FAILED -> {
                        // Settings never opened, so there is no toggle to come back from.
                        vm.requestToast("Could not open Android's install-permission settings")
                        pendingInstallApk = null
                    }
                    Intents.InstallResult.FAILED -> {
                        vm.requestToast("Could not start the installer")
                        pendingInstallApk = null
                    }
                }
                if (Intents.hasInstallPermission(context)) pendingInstallApk = null
            },
            onDismiss = { pendingInstallApk = null },
        )
    }
    state.pendingDelete?.let { files ->
        ConfirmDialog(
            title = "Move ${files.size} item(s) to trash?",
            message = "Items are moved to the toadFILES trash and kept for 30 days. " +
                "You can undo right away or restore later from the Trash page in the side menu.",
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
            onCancel = {
                // v1.7.3 abortConfirm: ask before killing a running archive operation
                // unless the user turned the confirmation off in Settings.
                if (state.settings.abortConfirm) {
                    pendingAbortConfirm = true
                } else {
                    vm.cancelOp()
                }
            },
        )
    }
    if (pendingAbortConfirm) {
        ConfirmDialog(
            title = "Abort operation?",
            message = "The operation will stop. Already completed items are kept.",
            confirmLabel = "Abort",
            destructive = true,
            onConfirm = { pendingAbortConfirm = false; vm.cancelOp() },
            onDismiss = { pendingAbortConfirm = false },
        )
    }
    state.result?.let { r ->
        ResultDialog(
            summary = "${state.resultTitle}: ${r.summary}",
            failures = r.failures,
            onDismiss = { vm.dismissResult() },
        )
    }
    state.pendingProperties?.let { file ->
        PropertiesSheet(
            file = file,
            detail = state.propertiesDetail,
            onDismiss = { vm.dismissProperties() },
        )
    }

    // Per-file action sheet (the row's ⋮ button). Each action reuses an existing vm
    // hook so the sheet is purely UI glue, not a new behavior path.
    pendingActionSheet?.let { item ->
        // "Open" mirrors the row-tap behaviour: directory navigates, apk shows the install
        // confirm dialog, anything else is handed to ACTION_VIEW. Inlined here because the
        // row's `openItem` lives in the Scaffold content scope, not the dialog scope.
        val openNow: () -> Unit = {
            when {
                item.isDir -> vm.openDir(item.file)
                item.name.lowercase().endsWith(".apk") -> pendingInstallApk = item.file
                else -> if (!Intents.openFile(context, item.file)) {
                    vm.requestToast("No app can open this file")
                }
            }
        }
        FileActionSheet(
            item = item,
            isFavorite = item.file.path in state.favoritePaths,
            onOpen = {
                pendingActionSheet = null
                openNow()
            },
            onShare = {
                pendingActionSheet = null
                if (!item.isDir) {
                    if (!Intents.shareFiles(context, listOf(item.file))) {
                        vm.requestToast("Could not share this file")
                    }
                } else {
                    vm.requestToast("Folders can't be shared — pick files")
                }
            },
            onRename = {
                pendingActionSheet = null
                vm.requestRename(item.file)
            },
            onCopy = {
                pendingActionSheet = null
                vm.copyToClipboard(Clipboard.Mode.COPY, listOf(item.file))
            },
            onMove = {
                pendingActionSheet = null
                vm.copyToClipboard(Clipboard.Mode.MOVE, listOf(item.file))
            },
            onDelete = {
                pendingActionSheet = null
                vm.requestDelete(listOf(item.file))
            },
            onDetails = {
                pendingActionSheet = null
                vm.requestProperties(item.file)
            },
            onZip = {
                pendingActionSheet = null
                vm.requestZip(listOf(item.file))
            },
            onExtract = if (ArchiveOps.canExtract(item.name)) {
                {
                    pendingActionSheet = null
                    vm.extractZip(item.file)
                }
            } else null,
            onExtractTo = if (ArchiveOps.canExtract(item.name)) {
                {
                    pendingActionSheet = null
                    pendingExtractTo = item.file
                }
            } else null,
            onToggleFavorite = {
                pendingActionSheet = null
                vm.toggleFavorite(item.file.path)
            },
            onCopyPath = {
                pendingActionSheet = null
                if (!Intents.copyPathToClipboard(context, item.file)) {
                    vm.requestToast("Could not copy the path")
                } else {
                    vm.requestToast("Path copied to clipboard")
                }
            },
            onOpenWith = {
                pendingActionSheet = null
                if (!Intents.openWith(context, item.file)) {
                    vm.requestToast("No app can open this file")
                }
            },
            pinnedToHome = item.file.path in state.quickPins,
            onTogglePin = if (item.isDir) {
                {
                    pendingActionSheet = null
                    vm.toggleQuickPin(item.file.path)
                }
            } else null,
            onDismiss = { pendingActionSheet = null },
        )
    }
}

@Composable
private fun Breadcrumbs(path: List<File>, onOpen: (File) -> Unit) {
    // Always reveal the current folder: deep paths start scrolled to the end.
    val listState = rememberLazyListState()
    LaunchedEffect(path.size, path.lastOrNull()?.path) {
        if (path.isNotEmpty()) listState.animateScrollToItem(path.lastIndex)
    }
    LazyRow(
        state = listState,
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

/**
 * v1.7.3 compress dialog: format (ZIP / 7z), compression level ([CompressLevel] labels),
 * target name (the required extension is appended by the ViewModel per format) and the
 * destination folder (archive-folder setting or the current folder), shown under the
 * name field. All state lives in the ViewModel; the dialog is pure UI.
 */
@Composable
private fun CompressDialog(
    count: Int,
    name: String,
    destination: File?,
    format: ArchiveFormat,
    level: CompressLevel,
    onNameChange: (String) -> Unit,
    onFormatChange: (ArchiveFormat) -> Unit,
    onLevelChange: (CompressLevel) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress ${if (count == 1) "1 item" else "$count items"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text("Archive name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                destination?.let {
                    Text(
                        "Into ${it.path}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Format", style = MaterialTheme.typography.titleSmall)
                ArchiveFormat.entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onFormatChange(f) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == f, onClick = { onFormatChange(f) })
                        Text(f.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Compression level", style = MaterialTheme.typography.titleSmall)
                CompressLevel.entries.forEach { l ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onLevelChange(l) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = level == l, onClick = { onLevelChange(l) })
                        Text(l.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
