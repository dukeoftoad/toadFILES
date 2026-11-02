package com.toadfiles.app.ui.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.MoreVert

import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.toFileItem
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.ConfirmDialog
import com.toadfiles.app.ui.components.ConflictDialog
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.FileGridCell
import com.toadfiles.app.ui.components.FileRow
import com.toadfiles.app.ui.components.MediaEntryActionSheet
import com.toadfiles.app.ui.components.MediaOpOverlays
import com.toadfiles.app.ui.components.CompressDialog
import com.toadfiles.app.ui.components.PasswordPromptDialog
import com.toadfiles.app.ui.components.ConfirmDialog
import com.toadfiles.app.ui.components.NameInputDialog
import com.toadfiles.app.ui.components.PropertiesSheet
import com.toadfiles.app.ui.components.SortMenu
import com.toadfiles.app.util.Intents
import java.io.File

/**
 * Per-category look for the tiles: every category gets its own icon and its own color
 * role from the active palette, so the grid scans quickly instead of reading as one
 * wall of identical cards. Roles (not raw hues) are used on purpose — the tiles re-tint
 * themselves correctly under all seven accents and both dark/light modes.
 */
private data class CategoryLook(val icon: ImageVector, val container: Color, val onContainer: Color)

@Composable
private fun lookFor(category: FileCategory): CategoryLook {
    val s = MaterialTheme.colorScheme
    return when (category) {
        FileCategory.IMAGES -> CategoryLook(Icons.Outlined.Image, s.primaryContainer, s.onPrimaryContainer)
        FileCategory.VIDEOS -> CategoryLook(Icons.Outlined.Videocam, s.tertiaryContainer, s.onTertiaryContainer)
        FileCategory.AUDIO -> CategoryLook(Icons.Outlined.Audiotrack, s.secondaryContainer, s.onSecondaryContainer)
        FileCategory.DOCS -> CategoryLook(Icons.Outlined.Description, s.surfaceVariant, s.onSurfaceVariant)
        FileCategory.ARCHIVES -> CategoryLook(Icons.Outlined.FolderZip, s.secondaryContainer, s.onSecondaryContainer)
        FileCategory.APKS -> CategoryLook(Icons.Outlined.InstallMobile, s.tertiaryContainer, s.onTertiaryContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onShowInFolder: (String) -> Unit,
    vm: CategoriesViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var actionSheetFor by remember { mutableStateOf<MediaRepository.MediaEntry?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var bulkMenuOpen by remember { mutableStateOf(false) }
    /** An archive the user picked "Extract to…" for: ask for the destination folder name. */
    var pendingExtractTo by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    // Undo for deletes, exactly like Browse: trashed items go straight back. The
    // offer is consumed the moment it is shown (D2) so navigating away mid-window
    // can never resurrect an ancient undo later; keyed on the offer sequence so
    // consuming cannot cancel the snackbar that is still showing.
    LaunchedEffect(state.undoOfferSeq) {
        val trashed = state.lastTrashed ?: return@LaunchedEffect
        vm.consumeUndoOffer()
        val result = snackbar.showSnackbar(
            message = "Moved ${trashed.size} item(s) to trash",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete(trashed)
    }

    // System back: first leave multi-select, then the drill-in list, then the tab.
    // Without these handlers back popped the whole route straight to Home (bug report).
    val inDrillIn = state.selected != null
    val inSelection = state.selection.isNotEmpty()
    BackHandler(enabled = inSelection) { vm.clearSelection() }
    BackHandler(enabled = inDrillIn && !inSelection) { vm.close() }

    val openEntry: (MediaRepository.MediaEntry) -> Boolean = { e ->
        Intents.openContent(context, e.uri, e.mime)
    }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost; zeroing
        // these avoids a double status-bar pad (the v1.7.3 top "dead band").
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
        topBar = {
            if (inDrillIn && inSelection) {
                TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
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
                            val picked = vm.selectedEntries()
                            if (picked.isNotEmpty()) {
                                val mime = commonMime(picked)
                                if (!Intents.shareUris(context, picked.map { it.uri }, mime)) {
                                    vm.requestToast("Could not share these items")
                                }
                            }
                            vm.clearSelection()
                        }) { Icon(Icons.Outlined.Share, "Share") }
                        IconButton(onClick = { vm.requestDelete(vm.selectedEntries()) }) {
                            Icon(Icons.Outlined.Delete, "Delete")
                        }
                        // Bulk menu, like Browse's: the batch ops that make sense for
                        // media rows (zip, single-archive extract, properties, rename).
                        IconButton(onClick = { bulkMenuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = bulkMenuOpen, onDismissRequest = { bulkMenuOpen = false }) {
                            val picked = vm.selectedEntries()
                            DropdownMenuItem(
                                text = { Text("Zip ${picked.size} item(s)…") },
                                onClick = {
                                    bulkMenuOpen = false
                                    val files = picked.mapNotNull { it.data?.let(::File) }
                                    if (files.isEmpty()) {
                                        vm.requestToast("None of these items has an on-disk path")
                                    } else {
                                        vm.requestZip(files)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                            )
                            val single = picked.singleOrNull()
                            if (single != null && single.data != null &&
                                ArchiveOps.canExtract(single.name)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Extract here") },
                                    onClick = {
                                        bulkMenuOpen = false
                                        vm.extractZip(File(single.data!!))
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.FolderZip, null) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Properties") },
                                onClick = {
                                    bulkMenuOpen = false
                                    picked.firstOrNull()?.let { vm.requestProperties(it) }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            )
                            val renameTarget = picked.singleOrNull()
                            if (renameTarget != null && renameTarget.data != null) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        bulkMenuOpen = false
                                        vm.requestRename(renameTarget)
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        if (inDrillIn) {
                            Column {
                                Text(state.selected!!.label)
                                Text(
                                    "${state.entries.size} item(s) · " +
                                        Format.size(state.entries.sumOf { it.size }),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text("Categories")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (inDrillIn) vm.close() else onBack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (inDrillIn) {
                            // Sort + layout toggle, the same shared controls Browse offers.
                            IconButton(onClick = { vm.toggleLayout() }) {
                                Icon(
                                    if (state.layout == BrowseLayout.GRID)
                                        Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                    "Toggle list / grid view",
                                )
                            }
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Outlined.Sort, "Sort")
                            }
                            SortMenu(
                                expanded = sortMenuOpen,
                                current = state.sortMode,
                                currentOrder = state.sortOrder,
                                onSelect = { vm.setSort(it); sortMenuOpen = false },
                                onSelectOrder = { vm.setSortOrder(it); sortMenuOpen = false },
                                onDismiss = { sortMenuOpen = false },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        // Pull-to-refresh (v1.7.3): drag down anywhere to re-query the media library.
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        if (state.selected == null) {
            CategoryGrid(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onOpen = vm::open,
            )
        } else {
            CategoryEntries(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onEntryTap = { e ->
                    when {
                        inSelection -> vm.toggleSelection(e)
                        else -> if (!openEntry(e)) vm.requestToast("No app can open this file")
                    }
                },
                onEntryLongPress = { e ->
                    // Press-and-hold starts multi-select, like Browse; the row's ⋮
                    // keeps the action sheet one tap away.
                    vm.toggleSelection(e)
                },
                onEntryMore = { e -> actionSheetFor = e },
            )
        }
        }
    }

    // Per-entry action sheet (the row's ⋮ button) — the SAME shared sheet Browse renders,
    // with the shared media wiring from ui/components (audit 7.3). Long-press is reserved
    // for multi-select. Categories wires the Browse-parity extras: rename, zip,
    // "Extract to…" and the properties sheet.
    MediaEntryActionSheet(
        entry = actionSheetFor,
        onDismiss = { actionSheetFor = null },
        open = openEntry,
        onShowInFolder = onShowInFolder,
        toast = vm::requestToast,
        requestDelete = { e -> vm.requestDelete(listOf(e)) },
        onDetails = vm::requestProperties,
        onExtract = { f -> vm.extractZip(f) },
        onRename = vm::requestRename,
        onZip = { f -> vm.requestZip(listOf(f)) },
        onExtractTo = { f -> pendingExtractTo = f },
    )

    // Delete confirmation: media entries go to the real 30-day trash.
    state.pendingDelete?.let { entries ->
        ConfirmDialog(
            title = "Move ${entries.size} item(s) to trash?",
            message = "Items are moved to the toadFILES trash and kept for 30 days. " +
                "You can undo right away or restore them from the Trash page.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.confirmDelete() },
            onDismiss = { vm.dismissDelete() },
        )
    }

    // Rename, like Browse's NameInputDialog flow.
    state.pendingRename?.let { entry ->
        NameInputDialog(
            title = "Rename",
            initialValue = entry.name,
            confirmLabel = "Rename",
            onConfirm = { vm.rename(entry, it) },
            onDismiss = { vm.dismissRename() },
        )
    }

    // Compress dialog (v1.8.0, ZArchiver-style — Browse parity): format and compression
    // level dropdowns, 7z-only data encryption with a password pair, split-into-volumes
    // (with Custom…), delete-sources and separate-archive checkboxes, target name and
    // destination — archives are never created under a silent auto name.
    state.pendingZipFiles?.let { files ->
        CompressDialog(
            count = files.size,
            name = state.pendingZipName,
            destination = state.pendingZipDestination,
            format = state.pendingZipFormat,
            level = state.pendingZipLevel,
            encrypted = state.pendingZipEncrypted,
            password = state.pendingZipPassword,
            passwordConfirm = state.pendingZipPasswordConfirm,
            volumeBytes = state.pendingZipVolumeBytes,
            volumeCustom = state.pendingZipVolumeCustom,
            volumeCustomText = state.pendingZipVolumeCustomText,
            deleteSources = state.pendingZipDeleteSources,
            separate = state.pendingZipSeparate,
            onNameChange = { vm.setZipName(it) },
            onFormatChange = { vm.setZipFormat(it) },
            onLevelChange = { vm.setZipLevel(it) },
            onEncryptedChange = { vm.setZipEncrypted(it) },
            onPasswordChange = { vm.setZipPassword(it) },
            onPasswordConfirmChange = { vm.setZipPasswordConfirm(it) },
            onVolumeChange = { vm.setZipVolume(it) },
            onVolumeCustomTextChange = { vm.setZipVolumeCustomText(it) },
            onDeleteSourcesChange = { vm.setZipDeleteSources(it) },
            onSeparateChange = { vm.setZipSeparate(it) },
            onConfirm = { vm.confirmZip(state.pendingZipName) },
            onDismiss = { vm.dismissZipDialog() },
        )
    }

    // "Extract to…": the user names the destination folder next to the archive, the
    // same ask-for-a-name pattern as Browse.
    pendingExtractTo?.let { archive ->
        NameInputDialog(
            title = "Extract to…",
            initialValue = archive.nameWithoutExtension,
            confirmLabel = "Extract",
            onConfirm = {
                pendingExtractTo = null
                vm.extractZip(archive, targetName = it)
            },
            onDismiss = { pendingExtractTo = null },
        )
    }

    // "Extract to…" collision: the shared conflict dialog with Browse's semantics
    // (keep both / replace / skip).
    state.pendingExtractConflict?.let { pc ->
        ConflictDialog(
            conflicts = listOf(pc.target.name),
            targetName = pc.target.name,
            onDecide = { vm.resolveExtractConflict(it) },
            onDismiss = { vm.dismissExtractConflict() },
        )
    }

    // Properties: the same read-only sheet Browse shows.
    state.pendingProperties?.let { file ->
        PropertiesSheet(
            file = file,
            detail = state.propertiesDetail,
            onDismiss = { vm.dismissProperties() },
        )
    }

    // Extract/zip progress + result: the same op model Browse shows, shared wiring
    // (audit 7.3).
    MediaOpOverlays(
        progress = state.progress,
        result = state.result,
        resultTitle = state.resultTitle,
        onCancel = { vm.cancelOp() },
        onDismissResult = { vm.dismissResult() },
    )

    // Encrypted archive: the extract came back passwordRequired — ask, then retry
    // with the same target dir / overwrite semantics (v1.8.0).
    state.pendingPasswordFor?.let { archive ->
        PasswordPromptDialog(
            archiveName = archive.name,
            onRetry = { pw -> vm.retryExtractWithPassword(pw) },
            onCancel = { vm.dismissPasswordPrompt() },
        )
    }
}

/** One common mime for a mixed batch of media entries (shareUris needs a single type). */
private fun commonMime(entries: List<MediaRepository.MediaEntry>): String {
    if (entries.size == 1) return entries.first().mime
    val mimes = entries.map { it.mime }.toSet()
    return when {
        mimes.size == 1 -> mimes.first()
        mimes.all { it.startsWith("image/") } -> "image/*"
        mimes.all { it.startsWith("video/") } -> "video/*"
        mimes.all { it.startsWith("audio/") } -> "audio/*"
        else -> "*/*"
    }
}

/**
 * Overview grid: a totals header card plus a 2-column tile grid. Each tile shows the
 * category's icon in a tonal circle, its label, and an honest count/size pair fed from
 * the same MediaStore query the drill-in list uses — no separate counting pass.
 */
@Composable
private fun CategoryGrid(
    state: CategoriesUiState,
    modifier: Modifier = Modifier,
    onOpen: (FileCategory) -> Unit,
) {
    when {
        state.loading && state.summaries.isEmpty() -> Row(
            modifier
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        state.summaries.isEmpty() -> EmptyState(
            "Nothing in the media library yet — files appear here once the system indexes them",
            modifier,
        )

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                TotalsHeader(state.summaries)
            }
            gridItems(state.summaries, key = { it.category.name }) { s ->
                CategoryTile(s, onClick = { onOpen(s.category) })
            }
        }
    }
}

@Composable
private fun TotalsHeader(summaries: List<CategorySummary>) {
    val totalItems = summaries.sumOf { it.count }
    val totalBytes = summaries.sumOf { it.totalBytes }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Media library", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "$totalItems indexed item(s) · ${Format.size(totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryTile(summary: CategorySummary, onClick: () -> Unit) {
    val look = lookFor(summary.category)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(look.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, null, tint = look.onContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                summary.category.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${summary.count} item(s) · ${Format.size(summary.totalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Drill-in view for one category. v1.7.3: the rows and grid cells are the SAME shared
 * [FileRow]/[FileGridCell] components Browse renders (entries bridge to FileItem via
 * [toFileItem]), with the user-switchable list/grid layout, the shared sort menu and
 * the same selection affordances. Tap opens the file, long-press starts multi-select,
 * and the ⋮ affordance opens the shared action sheet.
 */
@Composable
private fun CategoryEntries(
    state: CategoriesUiState,
    modifier: Modifier = Modifier,
    onEntryTap: (MediaRepository.MediaEntry) -> Unit,
    onEntryLongPress: (MediaRepository.MediaEntry) -> Unit,
    onEntryMore: (MediaRepository.MediaEntry) -> Unit,
) {
    when {
        state.entriesLoading -> Row(
            modifier.padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        state.entries.isEmpty() -> EmptyState(
            "No ${state.selected!!.label.lowercase()} found in the media library",
            modifier,
        )

        state.layout == BrowseLayout.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            gridItems(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                FileGridCell(
                    item = e.toFileItem(),
                    selected = e.uri.toString() in state.selection,
                    isFavorite = false,
                    onClick = { onEntryTap(e) },
                    onLongClick = { onEntryLongPress(e) },
                    onMore = { onEntryMore(e) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
        }

        else -> LazyColumn(modifier) {
            items(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                FileRow(
                    item = e.toFileItem(),
                    selected = e.uri.toString() in state.selection,
                    isFavorite = false,
                    subtitle = "${Format.size(e.size)} · ${Format.date(e.dateModified)}",
                    onClick = { onEntryTap(e) },
                    onLongClick = { onEntryLongPress(e) },
                    onMore = { onEntryMore(e) },
                )
            }
            if (state.entries.size >= CategoriesViewModel.QUERY_LIMIT) {
                item {
                    Text(
                        "Showing the first ${CategoriesViewModel.QUERY_LIMIT} results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
