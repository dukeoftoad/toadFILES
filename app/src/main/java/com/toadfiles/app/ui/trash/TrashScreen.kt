package com.toadfiles.app.ui.trash

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.TrashEntry
import com.toadfiles.app.ui.components.ConfirmDialog
import com.toadfiles.app.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    vm: TrashViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost; zeroing
        // these avoids a double status-bar pad (the v1.7.3 top "dead band").
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
        topBar = {
            TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(if (state.selection.isEmpty()) "Trash" else "${state.selection.size} selected")
                },
                navigationIcon = {
                    if (state.selection.isNotEmpty()) {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Outlined.Close, "Clear selection")
                        }
                    } else if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open navigation menu") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (state.selection.isNotEmpty()) {
                        // Batch restore never destroys data — no confirmation needed.
                        IconButton(onClick = { vm.requestBatchRestore() }) {
                            Icon(Icons.Outlined.Restore, "Restore selected")
                        }
                        IconButton(onClick = { vm.requestBatchDeleteForever() }) {
                            Icon(Icons.Outlined.DeleteForever, "Delete selected forever")
                        }
                    } else {
                        IconButton(onClick = { vm.requestEmpty() }, enabled = state.entries.isNotEmpty()) {
                            Icon(Icons.Outlined.DeleteSweep, "Empty trash")
                        }
                    }
                },
            )
        },
    ) { padding ->
        // Pull-to-refresh (v1.7.3): re-reads the trash folders (same reload the screen
        // runs after restore/delete/empty). Works on the empty state too.
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        when {
            state.loading && state.entries.isEmpty() -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator() }

            state.entries.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
            ) {
                EmptyState("Trash is empty")
                Text(
                    "Deleted items are kept for ${Trash.RETENTION_DAYS} days, then removed automatically.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
            ) {
                item {
                    Text(
                        "Deleted items are kept for ${Trash.RETENTION_DAYS} days, then removed automatically. " +
                            "You can also undo a deletion right after it happens in Browse.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                items(state.entries, key = { it.file.absolutePath }) { e ->
                    TrashRow(
                        e = e,
                        selected = e.file.absolutePath in state.selection,
                        selectionMode = state.selection.isNotEmpty(),
                        canRestore = e.originPath.isNotEmpty(),
                        onRestore = { vm.restore(e) },
                        onDelete = { vm.requestDeleteForever(e) },
                        onToggle = { vm.toggleSelection(e) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        }
    }

    if (state.confirmingEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Permanently delete all ${state.entries.size} ${Format.itemWord(state.entries.size)}? This cannot be undone.",
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = { vm.emptyNow() },
            onDismiss = { vm.dismissEmpty() },
        )
    }

    // Permanent single-item deletion gets the same respect: confirm before destroying.
    state.confirmingDelete?.let { e ->
        ConfirmDialog(
            title = "Delete forever?",
            message = "Permanently delete \"" +
                (e.originPath.substringAfterLast('/').ifEmpty { e.file.name }) +
                "\"? This cannot be undone.",
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = { vm.confirmDeleteForever() },
            onDismiss = { vm.dismissDeleteForever() },
        )
    }

    // Batch delete forever: same respect, applied to the whole selection (v1.7.5).
    if (state.confirmingBatchDelete) {
        ConfirmDialog(
            title = "Delete ${state.selection.size} ${Format.itemWord(state.selection.size)} forever?",
            message = "Permanently delete the ${state.selection.size} selected ${Format.itemWord(state.selection.size)}? This cannot be undone.",
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = { vm.confirmBatchDeleteForever() },
            onDismiss = { vm.dismissBatchDeleteForever() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(
    e: TrashEntry,
    selected: Boolean,
    selectionMode: Boolean,
    /** False when the entry lost its metadata: restore can never succeed (there is
     *  no origin to return to), so the affordance is not even offered (D6). Delete
     *  forever stays available. */
    canRestore: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                // Long-press enters selection mode (same affordance as Browse/Recent).
                onLongClick = onToggle,
                onClick = { if (selectionMode) onToggle() else if (canRestore) onRestore() },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        } else {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.originPath.substringAfterLast('/').ifEmpty { e.file.name },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                e.originPath.ifEmpty { "Original location unknown" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (e.file.isFile) Format.size(e.file.length()) else null,
                    "deleted ${Format.date(e.trashedAt)}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selectionMode) {
            if (canRestore) {
                IconButton(onClick = onRestore) { Icon(Icons.Outlined.Restore, "Restore") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteForever, "Delete forever") }
        } else if (selected) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
