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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.ApkInstallDialog
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.theme.toadFileIconFor
import com.toadfiles.app.ui.components.MediaTapRoute
import com.toadfiles.app.ui.components.iconFor
import com.toadfiles.app.ui.components.routeMediaTap
import com.toadfiles.app.util.Intents
import android.widget.Toast
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
    onOpenDrawer: (() -> Unit)? = null,
    /** Flaw fix: extractable archives route into Browse's extract-or-open dialog
     *  instead of a bare ACTION_VIEW that bounces back into toadFILES itself. */
    onOpenArchive: (File) -> Unit = {},
    vm: HomeViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editorOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<QuickAccessEntry?>(null) }
    /** APK favorite awaiting the shared install-confirm dialog. */
    var pendingInstallFile by remember { mutableStateOf<File?>(null) }
    /** APK recent-row awaiting the shared install-confirm dialog. */
    var pendingInstallEntry by remember { mutableStateOf<MediaRepository.MediaEntry?>(null) }

    // Home data (storage, quick links, recents) can change while the user is in another
    // tab — refresh every time this screen re-enters composition.
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
    ) {
        // ---- Compact action row (drawer mode only) ----
        // v1.8.1: the top-right Trash shortcut is GONE — Trash is now a permanent tile
        // in Quick access below (one tap, same as every other destination). What
        // remains of this row is the hamburger, and only in SIDE_MENU mode where the
        // drawer is the primary nav. Bottom-bar mode has no action row at all, so the
        // storage card starts right under the status bar.
        if (onOpenDrawer != null) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Outlined.Menu, "Open navigation menu")
                    }
                }
            }
        }

        // ---- Hero storage card ----
        item {
            val stats = state.storage
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clickable { onOpenStorage() },
                shape = RoundedCornerShape(22.dp),
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
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Storage,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(6.dp))
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
                        Spacer(Modifier.height(8.dp))
                        if (stats == null) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                "${Format.size(stats.freeBytes)} free",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { stats.usedFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp),
                            )
                            Spacer(Modifier.height(6.dp))
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
            HomeSectionHeader(
                icon = null,
                title = "Quick access",
                trailing = {
                    TextButton(onClick = { editorOpen = true }) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit")
                    }
                },
            )
        }
        // The grid always renders: the permanent Trash tile leads it even when the user
        // has no quick folders pinned yet. v1.8.x: plain Rows instead of a fixed-height
        // grid — the old 100dp-per-row budget clipped taller tile stacks, so each Row now
        // wraps to its tallest tile and nothing is cut off at any font scale.
        item {
            // Trash + the quick folders, chunked into rows of three. A short last row
            // pads with weight(1f) spacers, matching the old Fixed(3) geometry: tiles
            // keep their 1/3 width instead of stretching to fill the row.
            val slots = buildList {
                // v1.8.1: Trash leads Quick access — a first-class destination tile
                // instead of the old top-right toolbar icon (which crowded the
                // status bar / storage card on some devices).
                add(
                    QuickTileSlot(
                        name = "Trash",
                        icon = Icons.Outlined.Delete,
                        pinned = false,
                        mostUsed = false,
                        onClick = onOpenTrash,
                        onLongClick = onOpenTrash,
                    )
                )
                state.quickAccess.forEach { q ->
                    add(
                        QuickTileSlot(
                            name = q.label,
                            icon = quickIconFor(q.label),
                            pinned = q.pinned,
                            mostUsed = q.source == QuickAccessEntry.Source.MOST_USED,
                            onClick = { onOpenFolder(q.dir.path) },
                            onLongClick = { removeTarget = q },
                        )
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                slots.chunked(3).forEach { rowSlots ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowSlots.forEach { slot ->
                            QuickTile(
                                name = slot.name,
                                icon = slot.icon,
                                pinned = slot.pinned,
                                mostUsed = slot.mostUsed,
                                onClick = slot.onClick,
                                onLongClick = slot.onLongClick,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ---- Favorites ----
        item {
            HomeSectionHeader(
                icon = Icons.Outlined.Star,
                title = "Favorites",
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (state.favorites.isEmpty()) {
            item {
                Text(
                    "Long-press files in Browse and choose \"Add favorite\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        } else {
            items(state.favorites, key = { "fav" + it.file.path }) { fav ->
                FavoriteRow(
                    item = fav,
                    onClick = {
                        when {
                            fav.isDir -> onOpenFolder(fav.file.path)
                            // Flaw fix (owner report): an APK favorite goes to the
                            // system package installer (a notes app was hijacking
                            // plain ACTION_VIEWs), an extractable archive into the
                            // extract-or-open dialog — never a bare resolver.
                            fav.file.name.lowercase().endsWith(".apk") -> pendingInstallFile = fav.file
                            ArchiveOps.canExtract(fav.file.name) -> onOpenArchive(fav.file)
                            else -> if (!Intents.openFile(context, fav.file)) {
                                // Nothing can open it: no fake success, just a silent row tap.
                            }
                        }
                    },
                    onToggleFavorite = { vm.toggleFavorite(fav.file.path) },
                )
            }
        }

        // ---- Recent ----
        item {
            HomeSectionHeader(
                icon = Icons.Outlined.History,
                title = "Recent",
                trailing = { TextButton(onClick = onOpenRecent) { Text("See all") } },
            )
        }
        // Type filter chips, the same row the Recent screen carries: All + every
        // category, rendered outside the loading/empty/list branches so picking a
        // filter with no results never hides the only way back to "All". Shown only
        // once the feed can be filtered — a fresh install with an empty media library
        // gets no dead chip row.
        if (!state.loadingRecent && (state.recentFiles.isNotEmpty() || state.recentFilter != null)) {
            item {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.recentFilter == null,
                            onClick = { vm.setRecentFilter(null) },
                            label = { Text("All") },
                        )
                    }
                    items(FileCategory.entries) { c ->
                        FilterChip(
                            selected = state.recentFilter == c,
                            onClick = { vm.setRecentFilter(c) },
                            label = { Text(c.label) },
                        )
                    }
                }
            }
        }
        item {
            when {
                state.loadingRecent && state.recentFiles.isEmpty() -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }

                state.recentFiles.isEmpty() -> EmptyState(
                    if (state.recentFilter == null) {
                        "No recent files reported by the system media library"
                    } else {
                        "No recent ${state.recentFilter!!.label.lowercase()} found"
                    },
                )

                else -> Column(Modifier.padding(horizontal = 16.dp)) {
                    state.recentFiles.take(HomeRecentPreviewCount).forEach { e ->
                        RecentPreviewRow(
                            e = e,
                            onClick = {
                                // Flaw fix: the same tap-routing rule Recent uses —
                                // installer for APKs, the extract flow for archives,
                                // an external viewer otherwise.
                                when (val route = routeMediaTap(e)) {
                                    is MediaTapRoute.InstallApk -> pendingInstallEntry = e
                                    is MediaTapRoute.OpenArchive -> onOpenArchive(route.archive)
                                    is MediaTapRoute.OpenExternal ->
                                        if (!Intents.openContent(context, route.uri, route.mime)) {
                                            // Nothing installed can open this type; no fake feedback.
                                        }
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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

    // APK install confirms (favorite + recent strip): the shared dialog Browse
    // shows. Home has no snackbar channel by design, so outcome messages use a
    // plain platform toast, exactly like MainActivity's handoff messages.
    val toast: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    pendingInstallFile?.let { apk ->
        ApkInstallDialog(
            apkName = apk.name,
            launch = { Intents.installApk(context, apk) },
            onDismiss = { pendingInstallFile = null },
            toast = toast,
        )
    }
    pendingInstallEntry?.let { e ->
        ApkInstallDialog(
            apkName = e.name,
            launch = { Intents.installApkFromUri(context, e.uri) },
            onDismiss = { pendingInstallEntry = null },
            toast = toast,
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
                                // Honesty: checking an entry toggles a PIN, so say which
                                // ones already are — checking an "Automatic" entry pins
                                // (and promotes) it, which used to be silent.
                                Text(
                                    if (c.pinned) "Pinned" else "Automatic",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * One of Home's section headers (Quick access / Favorites / Recent). Unified so the
 * rhythm is identical everywhere and headers align with the 16dp content gutter —
 * they used to start at 20dp while every list below them starts at 16dp.
 */
@Composable
private fun HomeSectionHeader(
    icon: ImageVector?,
    title: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** One cell in Home's quick-access rows: the permanent Trash tile or a quick folder. */
private data class QuickTileSlot(
    val name: String,
    val icon: ImageVector,
    val pinned: Boolean,
    val mostUsed: Boolean,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
)

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
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
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
            Spacer(Modifier.height(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only most-used tiles carry a caption; the old " " placeholder wasted a
            // 16dp line on every other tile.
            if (mostUsed) {
                Text(
                    "most used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
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
                // v1.11: the toad file-type family replaces the extension monogram.
                Box(
                    Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        toadFileIconFor(e.name.substringAfterLast('.', "")),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
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
