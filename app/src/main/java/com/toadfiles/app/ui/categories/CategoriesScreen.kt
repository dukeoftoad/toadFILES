package com.toadfiles.app.ui.categories

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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.util.Intents

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

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.selected?.label ?: "Categories")
                        if (state.selected != null) {
                            Text(
                                "${state.entries.size} item(s)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.selected != null) vm.close() else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.selected == null) {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    }
                },
            )
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
                onOpenEntry = {
                    if (!Intents.openContent(context, it.uri, it.mime)) {
                        // No handler installed for this type; no fake feedback.
                    }
                },
                onShareEntry = { e ->
                    if (e.data != null) vm.shareLocal(listOf(e))
                    else vm.requestToast("Can't share this item — no on-disk path")
                },
                onShowInFolder = { e ->
                    val parent = e.data?.let { java.io.File(it).parentFile }
                    if (parent != null && parent.isDirectory) onShowInFolder(parent.absolutePath)
                },
            )
        }
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

/** Drill-in list for one category, newest first (MediaStore sorts by DATE_MODIFIED DESC). */
@Composable
private fun CategoryEntries(
    state: CategoriesUiState,
    modifier: Modifier = Modifier,
    onOpenEntry: (MediaRepository.MediaEntry) -> Unit,
    onShareEntry: (MediaRepository.MediaEntry) -> Unit,
    onShowInFolder: (MediaRepository.MediaEntry) -> Unit,
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

        else -> LazyColumn(modifier) {
            items(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                MediaRow(
                    e,
                    onClick = { onOpenEntry(e) },
                    onShare = { onShareEntry(e) },
                    onShowInFolder = { onShowInFolder(e) },
                )
            }
        }
    }
}

@Composable
private fun MediaRow(
    e: MediaRepository.MediaEntry,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.category == FileCategory.IMAGES || e.category == FileCategory.VIDEOS) {
                AsyncImage(
                    model = e.uri,
                    contentDescription = e.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(52.dp),
                )
            } else {
                Card(shape = RoundedCornerShape(10.dp)) {
                    Box(
                        Modifier.size(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            e.name.substringAfterLast('.', "?").uppercase().take(4),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${Format.size(e.size)} · ${Format.date(e.dateModified)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShowInFolder, enabled = e.data != null) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Show in folder")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, "Share a local copy")
            }
        }
    }
}
