package com.toadfiles.app.ui.categories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.MediaActionSheet
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

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
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
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
        topBar = {
            if (inDrillIn && inSelection) {
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
                    },
                )
            } else {
                TopAppBar(
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
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    },
                )
            }
        },
    ) { padding ->
        if (state.selected == null) {
            CategoryGrid(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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

    // Per-entry action sheet (the row's ⋮ button): every action lives here,
    // like the Browse sheet. Long-press is reserved for multi-select.
    actionSheetFor?.let { entry ->
        MediaActionSheet(
            entry = entry,
            onOpen = {
                actionSheetFor = null
                if (!openEntry(entry)) vm.requestToast("No app can open this file")
            },
            onOpenWith = {
                actionSheetFor = null
                if (!Intents.openContentWith(context, entry.uri, entry.mime)) {
                    vm.requestToast("No app can open this file")
                }
            },
            onShare = {
                actionSheetFor = null
                if (!Intents.shareUris(context, listOf(entry.uri), entry.mime)) {
                    vm.requestToast("Could not share this item")
                }
            },
            onShowInFolder = entry.data?.let { path ->
                {
                    actionSheetFor = null
                    val parent = java.io.File(path).parentFile
                    if (parent != null && parent.isDirectory) onShowInFolder(parent.absolutePath)
                }
            },
            onDelete = entry.data?.let {
                {
                    actionSheetFor = null
                    vm.requestDelete(listOf(entry))
                }
            },
            onDetails = {
                actionSheetFor = null
                vm.requestDetails(entry)
            },
            onCopyPath = entry.data?.let { path ->
                {
                    actionSheetFor = null
                    val f = java.io.File(path)
                    if (Intents.copyPathToClipboard(context, f)) {
                        vm.requestToast("Path copied to clipboard")
                    } else {
                        vm.requestToast("Could not copy the path")
                    }
                }
            },
            onDismiss = { actionSheetFor = null },
        )
    }

    // Delete confirmation: media entries go to the real 30-day trash.
    state.pendingDelete?.let { entries ->
        AlertDialog(
            onDismissRequest = { vm.dismissDelete() },
            title = { Text("Move ${entries.size} item(s) to trash?") },
            text = {
                Text("Items are moved to the toadFILES trash and kept for 30 days. " +
                    "You can restore them from Settings → Trash.")
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissDelete() }) { Text("Cancel") } },
        )
    }

    // Details dialog for a media entry.
    state.detailsOf?.let { e ->
        AlertDialog(
            onDismissRequest = { vm.dismissDetails() },
            title = { Text(e.name, maxLines = 2) },
            text = {
                Column {
                    if (e.data != null) Text("Path: ${e.data}", style = MaterialTheme.typography.labelMedium)
                    Text("Type: ${e.mime}")
                    Text("Size: ${Format.size(e.size)}")
                    Text("Modified: ${Format.dateTime(e.dateModified)}")
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissDetails() }) { Text("Close") } },
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
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
            Modifier.padding(18.dp),
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
        Column(Modifier.padding(16.dp)) {
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
 * Drill-in view for one category, newest first (MediaStore sorts by DATE_MODIFIED DESC).
 * Photos and videos render as a modern thumbnail grid; other categories use compact
 * rows. Tap opens the file, long-press opens the action sheet or starts multi-select.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryEntries(
    state: CategoriesUiState,
    modifier: Modifier = Modifier,
    onEntryTap: (MediaRepository.MediaEntry) -> Unit,
    onEntryLongPress: (MediaRepository.MediaEntry) -> Unit,
    onEntryMore: (MediaRepository.MediaEntry) -> Unit,
) {
    val visual = state.selected == FileCategory.IMAGES || state.selected == FileCategory.VIDEOS
    when {
        state.entriesLoading -> Row(
            modifier.padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        state.entries.isEmpty() -> EmptyState(
            "No ${state.selected!!.label.lowercase()} found in the media library",
            modifier,
        )

        visual -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            modifier = modifier,
            contentPadding = PaddingValues(4.dp),
        ) {
            gridItems(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                MediaGridCell(
                    e = e,
                    selected = e.uri.toString() in state.selection,
                    onClick = { onEntryTap(e) },
                    onLongClick = { onEntryLongPress(e) },
                    onMore = { onEntryMore(e) },
                )
            }
        }

        else -> LazyColumn(modifier) {
            items(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                MediaRow(
                    e = e,
                    selected = e.uri.toString() in state.selection,
                    onClick = { onEntryTap(e) },
                    onLongClick = { onEntryLongPress(e) },
                    onMore = { onEntryMore(e) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * Square thumbnail cell for image/video grids, with a selection check overlay and a
 * discoverable ⋮ affordance for the per-item action sheet (long-press starts
 * multi-select, so the sheet needs its own always-visible trigger).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridCell(
    e: MediaRepository.MediaEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            // Prefer the real file when its path is known (faster, works for video frames);
            // fall back to the MediaStore URI when the index hides the path.
            model = e.data?.let(::File) ?: e.uri,
            contentDescription = e.name,
            contentScale = ContentScale.Crop,
            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(lookFor(e.category).icon),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .fillMaxSize(),
        )
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        } else {
            // Overflow affordance for the per-entry action sheet. The scrim disc keeps
            // it visible over any thumbnail; it sits where the selection check goes,
            // and the two never render at the same time.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .clickable(onClickLabel = "More actions") { onMore() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "More actions",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Compact row for non-visual categories (docs, audio, archives, APKs). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    e: MediaRepository.MediaEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    lookFor(e.category).icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${Format.size(e.size)} · ${Format.date(e.dateModified)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Row overflow: the action sheet lives here because long-press
            // is reserved for starting multi-select.
            if (!selected) {
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "More actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
