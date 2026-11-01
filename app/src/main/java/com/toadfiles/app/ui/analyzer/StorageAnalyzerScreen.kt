package com.toadfiles.app.ui.analyzer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.StorageRoot
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.PropertiesDetail
import com.toadfiles.app.ui.components.PropertiesSheet
import com.toadfiles.app.ui.components.filterIconFor
import com.toadfiles.app.ui.components.iconFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ---- UI-local preferences ----------------------------------------------------

private enum class SortOrder(val label: String) {
    SIZE_DESC("Largest first"),
    NAME_ASC("Name A→Z"),
    RECENT("Recently modified"),
}

private val TOP_N_OPTIONS = listOf(10, 25, 50, Int.MAX_VALUE)

/** Sort choices for the biggest-files list: size, name or modification time. */
private val FILE_SORT_OPTIONS = SortOrder.entries.toList()

/** Sort choices for the largest-folders list: [FolderUsage] carries no timestamp. */
private val FOLDER_SORT_OPTIONS = listOf(SortOrder.SIZE_DESC, SortOrder.NAME_ASC)

/**
 * MI Files / Google Files style storage analyzer: category breakdown, biggest files and
 * largest folders for one volume, from a single on-demand recursive walk.
 *
 * Route Routes.STORAGE_ANALYZER is registered in ToadNavHost; opened from the Storage
 * screen's "Analyze storage" card (with an optional `root` deep-link argument that
 * starts a scan straight away).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    initialRoot: String = "",
    vm: StorageAnalyzerViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Single stable snapshot per composition: prevents NPE-prone `!!` in lambdas and
    // mixed-state reads inside nested composables.
    val root = state.root
    val error = state.error
    val result = state.result
    val scanning = state.scanning

    // Read-only info view for a tapped big file (action sheets are owned by Browse).
    var pendingProperties by remember { mutableStateOf<File?>(null) }

    // Deep link: start scanning straight away when a root was passed via the route.
    // rememberSaveable (not remember) so back-navigation from an opened folder, a
    // rotation or a process restore never restarts the walk; a fresh navigation
    // entry starts fresh.
    var autoScanConsumed by rememberSaveable(initialRoot) { mutableStateOf(false) }
    LaunchedEffect(initialRoot) {
        if (initialRoot.isNotBlank() && !autoScanConsumed) {
            autoScanConsumed = true
            vm.autoScan(File(initialRoot))
        }
    }
    // A deep link is "pending" between composition and the first scan snapshot: show
    // a lightweight placeholder instead of the intro card (no flash of the picker).
    val deepLinkPending = initialRoot.isNotBlank() && !autoScanConsumed &&
        !scanning && root == null && result == null && error == null

    // Live scan timer (~2 fps) so users see progress even during a long walk.
    var scanElapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scanning) {
        if (scanning) {
            val startedAt = System.currentTimeMillis()
            while (true) {
                scanElapsedMs = System.currentTimeMillis() - startedAt
                delay(500L)
            }
        } else {
            scanElapsedMs = 0L
        }
    }

    // Sort / Top-N preferences survive recomposition and configuration changes.
    var fileSort by rememberSaveable { mutableStateOf(SortOrder.SIZE_DESC) }
    var folderSort by rememberSaveable { mutableStateOf(SortOrder.SIZE_DESC) }
    var topN by rememberSaveable { mutableStateOf(25) }

    val sortedFiles = remember(result?.biggestFiles, fileSort, topN) {
        val base = result?.biggestFiles.orEmpty()
        val sorted = when (fileSort) {
            SortOrder.SIZE_DESC -> base.sortedByDescending { it.sizeBytes }
            SortOrder.NAME_ASC  -> base.sortedBy { it.name.lowercase() }
            SortOrder.RECENT    -> base.sortedByDescending { it.lastModified }
        }
        if (topN == Int.MAX_VALUE) sorted else sorted.take(topN)
    }
    val sortedFolders = remember(result?.largestFolders, folderSort, topN) {
        val base = result?.largestFolders.orEmpty()
        // Folders carry no timestamp, so only size and name sorts are offered.
        val sorted =
            if (folderSort == SortOrder.NAME_ASC) base.sortedBy { it.dir.name.lowercase() }
            else base.sortedByDescending { it.sizeBytes }
        if (topN == Int.MAX_VALUE) sorted else sorted.take(topN)
    }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost; zeroing
        // these avoids a double status-bar pad (the "blank space top of the app").
        // The TopAppBar must zero its own insets too, or the dead band returns.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Analyze storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (root != null) vm.scan(root) else vm.loadVolumes()
                        },
                        enabled = !scanning,
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = if (root != null) "Rescan" else "Reload volumes",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // ---- Error state (unreadable / missing folder, permission gap) ----
            if (error != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Could not analyze",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (root != null) {
                                TextButton(onClick = { vm.scan(root) }) { Text("Try again") }
                            }
                        }
                    }
                }
            }

            // ---- Deep-link placeholder — no intro flash before the scan kicks in ----
            if (deepLinkPending) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Starting analysis of \"${File(initialRoot).name}\"…",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            // ---- Intro: pick a volume to analyze ----
            // `!deepLinkPending` (rather than "no route arg") keeps the picker reachable
            // after "Change volume" on a deep-linked entry, instead of getting stuck on
            // the placeholder forever.
            if (!scanning && result == null && error == null && !deepLinkPending) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                "What's eating your storage?",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Pick a volume for a full breakdown: sizes by category, " +
                                    "biggest files and largest folders. The scan runs on " +
                                    "demand and can be cancelled at any time.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            if (state.volumesLoading) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Looking for volumes…",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            } else {
                                if (state.volumes.isEmpty()) {
                                    Text(
                                        "No storage volumes found.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                state.volumes.forEach { vol ->
                                    VolumeAnalyzeRow(root = vol, onAnalyze = { vm.scan(vol.dir) })
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ---- Scan progress (files scanned count, cancellable) ----
            if (scanning) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .semantics {
                                        contentDescription =
                                            "Scanning ${state.scannedFiles} files, " +
                                                "${Format.size(state.scannedBytes)} so far"
                                    },
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Scanning \"${root?.name ?: "volume"}\" — " +
                                    "${state.scannedFiles} file(s) · " +
                                    "${Format.size(state.scannedBytes)} · ${formatDuration(scanElapsedMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { vm.cancelScan() }) { Text("Cancel") }
                        }
                    }
                }
            }

            // ---- Results ----
            if (result != null) {
                if (result.fileCount == 0) {
                    item {
                        EmptyState("Nothing found to analyze in this folder.")
                    }
                } else {
                    // ---- Summary ----
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Scanned", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${result.fileCount} file(s) · ${Format.size(result.totalBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.reset() }) { Text("Change volume") }
                        }
                    }

                    // ---- Category breakdown: horizontal bars, MI Files style ----
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("By category", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(10.dp))
                                if (result.categories.isEmpty()) {
                                    Text(
                                        "No categorised content.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    val maxBytes =
                                        result.categories.maxOfOrNull { it.sizeBytes } ?: 0L
                                    result.categories.forEach { c ->
                                        CategoryBarRow(
                                            usage = c,
                                            // Bar length is relative to the biggest category.
                                            // Floor at 2 % so tiny-but-real buckets stay
                                            // visible; empty buckets stay invisible (a
                                            // phantom bar for 0 bytes reads as a lie).
                                            fillFraction = if (maxBytes > 0L && c.sizeBytes > 0L) {
                                                (c.sizeBytes.toFloat() / maxBytes)
                                                    .coerceIn(0.02f, 1f)
                                            } else 0f,
                                            percentText = percentOf(c.sizeBytes, result.totalBytes),
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }

                    // ---- Top-N control shared by both lists ----
                    item {
                        Spacer(Modifier.height(6.dp))
                        TopNSelector(current = topN, onChange = { topN = it })
                    }

                    // ---- Biggest files ----
                    if (result.biggestFiles.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            SectionHeader(
                                title = "Biggest files",
                                subtitle = "Tap a file for details.",
                                sortOrder = fileSort,
                                options = FILE_SORT_OPTIONS,
                                onSortChange = { fileSort = it },
                            )
                        }
                        items(sortedFiles, key = { it.file.path }) { f ->
                            BigFileRow(bigFile = f, onClick = { pendingProperties = f.file })
                        }
                    }

                    // ---- Largest folders (recursive sizes) ----
                    if (result.largestFolders.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            SectionHeader(
                                title = "Largest folders",
                                subtitle = "Sizes include everything inside each folder.",
                                sortOrder = folderSort,
                                options = FOLDER_SORT_OPTIONS,
                                onSortChange = { folderSort = it },
                            )
                        }
                        items(sortedFolders, key = { it.dir.path }) { u ->
                            LargeFolderRow(
                                usage = u,
                                rootPath = root?.absolutePath,
                                onOpen = { onOpenFolder(u.dir.path) },
                            )
                        }
                    }

                    // ---- Inline rescan — the top-bar icon is hard to reach on a long scroll ----
                    item {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { root?.let(vm::scan) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = root != null && !scanning,
                        ) { Text("Rescan") }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Read-only properties view for the tapped big file. Plain files need no walk, but
    // the stat happens off the main thread: a single length() on slow storage is still
    // a StrictMode disk-read violation and can jank the sheet's entrance.
    pendingProperties?.let { file ->
        val length by produceState(0L, file) {
            value = withContext(Dispatchers.IO) { file.length() }
        }
        PropertiesSheet(
            file = file,
            detail = PropertiesDetail(
                sizeText = Format.size(length),
                sizeBytes = length,
                fileCount = 0,
                dirCount = 0,
            ),
            onDismiss = { pendingProperties = null },
        )
    }
}

// ---- Small helpers -----------------------------------------------------------

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

/** Integer percent of the total, with "<1%" so small-but-real buckets never read as zero. */
private fun percentOf(size: Long, total: Long): String {
    if (total <= 0 || size <= 0) return "0%"
    val pct = size * 100 / total
    return if (pct == 0L) "<1%" else "$pct%"
}

// ---- Composable helpers ------------------------------------------------------

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    sortOrder: SortOrder,
    options: List<SortOrder>,
    onSortChange: (SortOrder) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) { Text(sortOrder.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = { onSortChange(order); expanded = false },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TopNSelector(current: Int, onChange: (Int) -> Unit) {
    // Horizontally scrollable: four chips overflow the narrowest phones.
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TOP_N_OPTIONS.forEach { n ->
            FilterChip(
                selected = current == n,
                onClick = { onChange(n) },
                label = { Text(if (n == Int.MAX_VALUE) "All" else "Top $n") },
            )
        }
    }
}

@Composable
private fun VolumeAnalyzeRow(root: StorageRoot, onAnalyze: () -> Unit) {
    // One click target (the row itself, labelled for TalkBack) instead of a nested
    // button inside a clickable row — no duplicated focus/action for accessibility.
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Analyze ${root.label}") { onAnalyze() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(root.label, style = MaterialTheme.typography.bodyLarge)
            val stats = root.stats
            Text(
                if (stats != null) {
                    "${Format.size(stats.usedBytes)} used of ${Format.size(stats.totalBytes)}"
                } else "Mounted volume",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryBarRow(usage: CategoryUsage, fillFraction: Float, percentText: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                filterIconFor(filterModeOf(usage.category)),
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                usage.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Format.size(usage.sizeBytes)} · $percentText",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fillFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .semantics { contentDescription = "${usage.label}: $percentText" },
        )
    }
}

@Composable
private fun BigFileRow(bigFile: BigFile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(
                FileItem(
                    file = bigFile.file,
                    name = bigFile.name,
                    isDir = false,
                    size = bigFile.sizeBytes,
                    lastModified = bigFile.lastModified,
                )
            ),
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                bigFile.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                bigFile.file.parent ?: "/",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Format.size(bigFile.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LargeFolderRow(
    usage: FolderUsage,
    rootPath: String?,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(usage.dir.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                relativePath(usage.dir, rootPath) + " · ${usage.fileCount} file(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Format.size(usage.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Folder location relative to the scanned root, e.g. "DCIM/Camera".
 * Uses [File.relativeTo] so a trailing slash on `rootPath` or doubled separators
 * in the child's absolute path don't break the prefix calculation the previous
 * `startsWith("$rootPath/")` / `removePrefix("$rootPath/")` pair did.
 */
private fun relativePath(dir: File, rootPath: String?): String {
    if (rootPath.isNullOrBlank()) return dir.absolutePath
    return runCatching {
        dir.relativeTo(File(rootPath)).path.ifBlank { "." }
    }.getOrElse { dir.absolutePath }
}

/** Maps an analyzer category onto the shared filter icons; "Other" gets the generic one. */
private fun filterModeOf(category: FileCategory?): FilterMode = when (category) {
    FileCategory.IMAGES -> FilterMode.IMAGES
    FileCategory.VIDEOS -> FilterMode.VIDEOS
    FileCategory.AUDIO -> FilterMode.AUDIO
    FileCategory.DOCS -> FilterMode.DOCS
    FileCategory.ARCHIVES -> FilterMode.ARCHIVES
    FileCategory.APKS -> FilterMode.APKS
    null -> FilterMode.ALL
}
