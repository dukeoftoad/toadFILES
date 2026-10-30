package com.toadfiles.app.ui.recent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.MediaActionSheet
import com.toadfiles.app.util.Intents

/**
 * Recent files from the OS media library. Each row is fully actionable: tap opens the
 * file, long-press opens the action sheet (open, open with, share, show in folder,
 * delete to trash, details, copy path), and long-press in selection mode builds a
 * multi-select batch. Trashed files are hidden by the repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onShowInFolder: (String) -> Unit,
    vm: RecentViewModel = viewModel(factory = ToadViewModelFactory),
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

    val inSelection = state.selection.isNotEmpty()
    BackHandler(enabled = inSelection) { vm.clearSelection() }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
        topBar = {
            if (inSelection) {
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
                                val mime = if (picked.size == 1) picked.first().mime else "*/*"
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
                    title = { Text("Recent") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    },
                )
            }
        },
    ) { padding ->
        when {
            state.loading && state.entries.isEmpty() -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator() }

            state.entries.isEmpty() -> EmptyState(
                if (state.filter == null) {
                    "No recent files reported by the system media library"
                } else {
                    "No recent ${state.filter!!.label.lowercase()} found"
                },
                Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Type filter tabs: All + every category.
                item {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.filter == null,
                                onClick = { vm.setFilter(null) },
                                label = { Text("All") },
                            )
                        }
                        items(FileCategory.entries) { c ->
                            FilterChip(
                                selected = state.filter == c,
                                onClick = { vm.setFilter(c) },
                                label = { Text(c.label) },
                            )
                        }
                    }
                }
                items(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                    RecentRow(
                        e = e,
                        selected = e.uri.toString() in state.selection,
                        onClick = {
                            if (inSelection) {
                                vm.toggleSelection(e)
                            } else if (!Intents.openContent(context, e.uri, e.mime)) {
                                vm.requestToast("No app can open this file")
                            }
                        },
                        // Press-and-hold starts multi-select, like Browse; the row's
                        // ⋮ keeps the action sheet one tap away.
                        onLongClick = { vm.toggleSelection(e) },
                        onMore = { actionSheetFor = e },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    // Long-press action sheet — the same one the Categories drill-in uses.
    actionSheetFor?.let { entry ->
        MediaActionSheet(
            entry = entry,
            onOpen = {
                actionSheetFor = null
                if (!Intents.openContent(context, entry.uri, entry.mime)) {
                    vm.requestToast("No app can open this file")
                }
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
                    if (Intents.copyPathToClipboard(context, java.io.File(path))) {
                        vm.requestToast("Path copied to clipboard")
                    } else {
                        vm.requestToast("Could not copy the path")
                    }
                }
            },
            onDismiss = { actionSheetFor = null },
        )
    }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(
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
            if (e.category == FileCategory.IMAGES || e.category == FileCategory.VIDEOS) {
                AsyncImage(
                    model = e.data?.let { java.io.File(it) } ?: e.uri,
                    contentDescription = e.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp),
                        ),
                )
            } else {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        e.name.substringAfterLast('.', "?").uppercase().take(4),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
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
