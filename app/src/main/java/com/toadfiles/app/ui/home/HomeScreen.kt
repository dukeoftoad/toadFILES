package com.toadfiles.app.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.iconFor
import com.toadfiles.app.util.Intents
import java.io.File

@Composable
fun HomeScreen(
    bottomPadding: Dp,
    onOpenFolder: (String) -> Unit,
    onOpenCategories: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Home data (storage, quick links, categories) can change while the user is in
    // another tab — refresh every time this screen re-enters composition.
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
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
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, "Settings")
                }
            }
        }

        // Storage summary
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { onOpenStorage() },
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PieChart, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Internal storage", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    val stats = state.storage
                    if (stats == null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        LinearProgressIndicator(
                            progress = { stats.usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${Format.size(stats.usedBytes)} used of ${Format.size(stats.totalBytes)} · " +
                                "${Format.size(stats.freeBytes)} free",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Quick links
        item {
            Text(
                "Quick access",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
            )
            if (state.quickLinks.isEmpty()) {
                EmptyState("No standard folders found")
            } else {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.quickLinks, key = { it.first }) { (name, dir) ->
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.clickable { onOpenFolder(dir.path) },
                        ) {
                            Column(
                                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(name, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        // Favorites
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 20.dp),
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
            itemsIndexed(state.favorites, key = { _, it -> "fav" + it.file.path }) { _, fav ->
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

        // Categories
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenCategories) { Text("See all") }
            }
        }
        item {
            if (state.loadingCategories && state.categories.isEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            } else {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val maxBytes = state.categories.maxOfOrNull { it.totalBytes } ?: 0L
                    state.categories.forEach { c ->
                        CategoryCard(
                            summary = c,
                            fillFraction = if (maxBytes > 0L) {
                                (c.totalBytes.toFloat() / maxBytes).coerceIn(0.05f, 1f)
                            } else 0f,
                            onClick = { onOpenCategories() },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recently changed files appear in the Recent tab. " +
                        "toadFILES never scans your storage in the background.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    item: FileItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(item),
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
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

@Composable
private fun CategoryCard(summary: CategorySummary, fillFraction: Float, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(summary.category.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${summary.count} item(s) · ${Format.size(summary.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.padding(start = 8.dp)) {
                CircularProgressIndicator(
                    progress = { fillFraction },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
