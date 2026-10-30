package com.toadfiles.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.iconFor
import com.toadfiles.app.util.Intents
import java.io.File

/**
 * Home dashboard. A hero storage card up top, then quick-access tiles, favorites and a
 * compact recent feed. Everything is computed on demand — toadFILES never scans storage
 * in the background.
 */
@Composable
fun HomeScreen(
    bottomPadding: Dp,
    onOpenFolder: (String) -> Unit,
    onOpenStorage: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editorOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<QuickAccessEntry?>(null) }

    // Home data (storage, quick links, recents) can change while the user is in another
    // tab — refresh every time this screen re-enters composition.
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        // ---- Header ----
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("toadFILES", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Lightweight · Fast · Private",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenTrash) { Icon(Icons.Outlined.Delete, "Trash") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
            }
        }

        // ---- Hero storage card ----
        item {
            val stats = state.storage
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { onOpenStorage() },
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                )
                            )
                        ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Storage,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Internal storage",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                "Storage details",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        if (stats == null) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                "${Format.size(stats.freeBytes)} free",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { stats.usedFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${Format.size(stats.usedBytes)} used of ${Format.size(stats.totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // ---- Quick access (user pins + most used + standard folders, all editable) ----
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Quick access",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editorOpen = true }) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            }
        }
        if (state.quickAccess.isEmpty()) {
            item { EmptyState("No quick folders yet — tap Edit to pin some") }
        } else {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((state.quickAccess.size + 2) / 3 * 104).dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false,
                ) {
                    gridItems(state.quickAccess, key = { it.dir.path }) { q ->
                        QuickTile(
                            name = q.label,
                            icon = quickIconFor(q.label),
                            pinned = q.pinned,
                            mostUsed = q.source == QuickAccessEntry.Source.MOST_USED,
                            onClick = { onOpenFolder(q.dir.path) },
                            onLongClick = { removeTarget = q },
                        )
                    }
                }
            }
        }

        // ---- Favorites ----
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Star, null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
                Text("Favorites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
        if (state.favorites.isEmpty()) {
            item {
                Text(
                    "Long-press files in Browse and choose \"Add favorite\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        } else {
            items(state.favorites, key = { "fav" + it.file.path }) { fav ->
                FavoriteRow(
                    item = fav,
                    onClick = {
                        if (fav.isDir) onOpenFolder(fav.file.path)
                        else if (!Intents.openFile(context, fav.file)) {
                            // Nothing can open it: no fake success, just a silent row tap.
                        }
                    },
                    onToggleFavorite = { vm.toggleFavorite(fav.file.path) },
                )
            }
        }

        // ---- Recent ----
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenRecent) { Text("See all") }
            }
        }
        item {
            when {
                state.loadingRecent && state.recentFiles.isEmpty() -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }

                state.recentFiles.isEmpty() -> EmptyState(
                    "No recent files reported by the system media library",
                )

                else -> Column(Modifier.padding(horizontal = 16.dp)) {
                    state.recentFiles.take(HomeRecentPreviewCount).forEach { e ->
                        RecentPreviewRow(
                            e = e,
                            onClick = {
                                if (!Intents.openContent(context, e.uri, e.mime)) {
                                    // Nothing installed can open this type; no fake feedback.
                                }
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // ---- Privacy footnote ----
        item {
            Text(
                "toadFILES never scans your storage in the background. " +
                    "Recent files come straight from the OS media library on demand.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }

    // ---- Quick access editing ----

    if (editorOpen) {
        QuickAccessEditorDialog(
            candidates = state.quickCandidates,
            onToggle = { path, shown -> vm.setQuickShown(path, shown) },
            onDismiss = { editorOpen = false },
        )
    }

    removeTarget?.let { q ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove \"${q.label}\"?") },
            text = { Text("It disappears from Quick access. You can add it back any time with Edit.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeQuickAccess(q.dir.path)
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

/** The "＋ Edit" dialog: check folders on/off for Quick access. */
@Composable
private fun QuickAccessEditorDialog(
    candidates: List<QuickCandidate>,
    onToggle: (path: String, shown: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize Quick access") },
        text = {
            Column {
                Text(
                    "Checked folders appear on your Home screen. " +
                        "Folders you open often show up automatically as \"most used\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.path }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(c.path, !c.shown) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    c.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Checkbox(
                                checked = c.shown,
                                onCheckedChange = { onToggle(c.path, it) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Icon per standard quick-link folder; unknown folders fall back to a folder glyph. */
private fun quickIconFor(name: String): ImageVector = when (name.lowercase()) {
    "download" -> Icons.Outlined.Download
    "dcim" -> Icons.Outlined.PhotoCamera
    "documents" -> Icons.Outlined.Description
    "pictures" -> Icons.Outlined.Image
    "music" -> Icons.Outlined.Audiotrack
    "movies" -> Icons.Outlined.Movie
    else -> Icons.Outlined.Folder
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickTile(
    name: String,
    icon: ImageVector,
    pinned: Boolean,
    mostUsed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            if (pinned) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = name,
                        tint = if (pinned) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                if (mostUsed) "most used" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FavoriteRow(
    item: FileItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(item),
                    null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (item.isDir) "Folder" else Format.size(item.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The star removes the favorite — only the row itself opens the item.
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    Icons.Outlined.Star,
                    "Remove favorite",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun RecentPreviewRow(
    e: MediaRepository.MediaEntry,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.category == FileCategory.IMAGES || e.category == FileCategory.VIDEOS) {
                AsyncImage(
                    model = e.uri,
                    contentDescription = e.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(44.dp),
                )
            } else {
                Card(shape = RoundedCornerShape(10.dp)) {
                    Row(
                        Modifier.size(44.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            e.name.substringAfterLast('.', "?").uppercase().take(3),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.name,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Format.size(e.size)} · ${Format.date(e.dateModified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** How many recent rows we preview on Home — full list lives in the Recent route. */
private const val HomeRecentPreviewCount = 8
