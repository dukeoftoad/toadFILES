package com.toadfiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.util.VideoThumbs
import com.toadfiles.app.ui.theme.EmptyFolderArt
import com.toadfiles.app.ui.theme.ToadFileApk
import com.toadfiles.app.ui.theme.ToadFileArchive
import com.toadfiles.app.ui.theme.ToadFileAudio
import com.toadfiles.app.ui.theme.ToadFileDoc
import com.toadfiles.app.ui.theme.ToadFileGeneric
import com.toadfiles.app.ui.theme.ToadFileImage
import com.toadfiles.app.ui.theme.ToadFileVideo
import com.toadfiles.app.ui.theme.ToadFolder
import com.toadfiles.app.ui.theme.LocalPerformanceMode
import com.toadfiles.app.ui.theme.LocalToadMotion
import com.toadfiles.app.ui.theme.toadFileIconFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import coil3.compose.AsyncImage

/**
 * Pulses [onPulse] twice — the "Show in folder" pinpoint flash (owner report: a bare
 * folder jump left the user hunting for the file themselves). on → off → on → off,
 * always settling on "off", ~1s total with the default timings so the flash reads as
 * two deliberate blinks, not a strobe.
 */
internal suspend fun blinkTwice(
    pulseMs: Long = 300,
    gapMs: Long = 200,
    onPulse: (Boolean) -> Unit,
) {
    repeat(2) {
        onPulse(true)
        delay(pulseMs)
        onPulse(false)
        delay(gapMs)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    isFavorite: Boolean,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    highlight: Boolean = false,
) {
    // The pinpoint flash uses the tertiary container so it reads as its own signal,
    // never as "selected" (which stays secondaryContainer).
    val bgTarget = when {
        highlight -> MaterialTheme.colorScheme.tertiaryContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val bg by animateColorAsState(bgTarget, tween(150), label = "fileRowBg")
    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isImage = !item.isDir && FilterMode.IMAGES.matches(item.name)
            val isVideo = !item.isDir && FilterMode.VIDEOS.matches(item.name)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isImage -> AsyncImage(
                        model = item.file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(iconFor(item)),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    )
                    isVideo -> VideoThumb(
                        path = item.file.path,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    )
                    else -> Icon(
                        imageVector = iconFor(item),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle ?: run {
                        if (item.isDir) "Folder" else "${Format.size(item.size)} · ${Format.dateTime(item.lastModified)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFavorite) {
                Text("★", color = MaterialTheme.colorScheme.tertiary)
            }
            // Row overflow: the single-item action sheet lives here because
            // long-press is reserved for starting multi-select.
            if (onMore != null && !selected) {
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "More actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Checkbox(checked = true, onCheckedChange = null)
            }
        }
    }
}

fun iconFor(item: FileItem): ImageVector = if (item.isDir) {
    ToadFolder
} else {
    toadFileIconFor(item.name.substringAfterLast('.', ""))
}

fun filterIconFor(mode: FilterMode): ImageVector = when (mode) {
    FilterMode.ALL -> ToadFileGeneric
    FilterMode.DOCS -> ToadFileDoc
    FilterMode.IMAGES -> ToadFileImage
    FilterMode.VIDEOS -> ToadFileVideo
    FilterMode.AUDIO -> ToadFileAudio
    FilterMode.ARCHIVES -> ToadFileArchive
    FilterMode.APKS -> ToadFileApk
}

/**
 * v2.5.0: icon per media category — the drawer's Category dropdown and Most-used
 * section plus Home's most-used strip share the same glyphs as the Categories
 * screen's tiles (CategoryLook), so a category reads identically everywhere.
 */
fun categoryIconFor(category: FileCategory): ImageVector = when (category) {
    FileCategory.IMAGES -> Icons.Outlined.Image
    FileCategory.VIDEOS -> Icons.Outlined.Videocam
    FileCategory.AUDIO -> Icons.Outlined.Audiotrack
    FileCategory.DOCS -> Icons.Outlined.Description
    FileCategory.ARCHIVES -> Icons.Outlined.FolderZip
    FileCategory.APKS -> Icons.Outlined.InstallMobile
}

/**
 * Video thumbnail from MediaMetadataRetriever with a tiny in-memory cache (no extra dependency).
 * The state keys on the file's identity stamp (path + mtime/size), so a video replaced in
 * place re-decodes its frame instead of showing a stale one (audit finding P3.2).
 *
 * Audit A6: [VideoThumbs.stampFor] is a Files.readAttributes stat — it moved out of
 * composition onto IO (produceState); a recomposing grid cell no longer pays a syscall,
 * it just reads the cached stamp state until the stat (one per distinct path) lands.
 */
@Composable
fun VideoThumb(path: String, modifier: Modifier = Modifier) {
    // Performance mode: MediaMetadataRetriever decode + LruCache bitmap churn are
    // exactly the recurring CPU/RAM cost the mode exists to remove — keep the
    // generic videocam icon and never touch the retriever.
    val performanceMode = LocalPerformanceMode.current
    val stamp by produceState<String?>(initialValue = null, path, performanceMode) {
        if (performanceMode) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) { VideoThumbs.stampFor(path) }
        }
    }
    var bitmap by remember(path, stamp) {
        mutableStateOf(stamp?.let { VideoThumbs.peek(path, it) })
    }
    LaunchedEffect(path, stamp, performanceMode) {
        if (!performanceMode && bitmap == null) bitmap = VideoThumbs.load(File(path))
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Icon(
            Icons.Outlined.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Compact grid cell shared by Browse's grid layout and the Categories drill-in grid.
 * [onMore] (when wired) renders the always-visible ⋮ badge that opens the per-item action
 * sheet — screens where long-press starts multi-select need an explicit sheet trigger,
 * and it sits exactly where the selection check goes (the two never render together).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    item: FileItem,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    highlight: Boolean = false,
) {
    // Same pinpoint flash as FileRow — the reveal can land in either layout.
    val bgTarget = when {
        highlight -> MaterialTheme.colorScheme.tertiaryContainer
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val bg by animateColorAsState(bgTarget, tween(150), label = "fileCellBg")
    Surface(color = bg, shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val isImage = !item.isDir && FilterMode.IMAGES.matches(item.name)
            val isVideo = !item.isDir && FilterMode.VIDEOS.matches(item.name)
            Box {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isImage -> AsyncImage(
                            model = item.file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            error = rememberVectorPainter(iconFor(item)),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        isVideo -> VideoThumb(
                            path = item.file.path,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        else -> Icon(
                            imageVector = iconFor(item),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                if (selected) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(22.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(3.dp),
                        )
                    }
                } else if (onMore != null) {
                    // Overflow affordance for the per-item action sheet. The scrim disc
                    // keeps it visible over any thumbnail.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            .clickable(onClickLabel = "More actions") { onMore() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "More actions",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                if (item.isDir) "Folder" else Format.size(item.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (isFavorite) {
                Text("★", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Scale-and-fade entrance driven by the user's motion style (the "toad hop").
 * Wrap anything that should land with a spring — FABs, badges, new rows.
 * Re-fires whenever [key] changes; removal is up to the caller's `if`.
 */
@Composable
fun HopIn(
    key: Any? = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = LocalToadMotion.current
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.55f,
        animationSpec = motion.emphasized,
        label = "hop_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(140),
        label = "hop_alpha",
    )
    Box(modifier.scale(scale).alpha(alpha)) { content() }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    /** Optional custom illustration; defaults to the toad-over-folder scene. */
    art: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (art != null) art() else EmptyFolderArt()
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        modifier = Modifier.testTag("dialog_confirm"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = true,
                colors = if (destructive) androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ) else androidx.compose.material3.ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Optional destination path shown under the field (v1.7.3: archive folder default). */
    destination: String? = null,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        modifier = Modifier.testTag("dialog_name_input"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
                destination?.let {
                    Text(
                        "Into $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConflictDialog(
    conflicts: List<String>,
    targetName: String,
    onDecide: (ConflictStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("dialog_conflict"),
        onDismissRequest = onDismiss,
        title = { Text("Conflicts in \"$targetName\"") },
        text = {
            Column {
                Text(
                    "${conflicts.size} ${Format.itemWord(conflicts.size)} already exist here:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    conflicts.take(5).joinToString("\n") { "• $it" } +
                        if (conflicts.size > 5) "\n… and ${conflicts.size - 5} more" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(12.dp))
                ConflictStrategy.entries.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDecide(s) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = false, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (s) {
                                ConflictStrategy.RENAME -> "Keep both (add \"(1)\", \"(2)\", …)"
                                ConflictStrategy.REPLACE -> "Replace existing"
                                ConflictStrategy.SKIP -> "Skip conflicting items"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProgressOverlay(
    text: String,
    done: Int,
    total: Int,
    currentItem: String?,
    onCancel: () -> Unit,
    bytesDone: Long = -1L,
    bytesTotal: Long = -1L,
) {
    AlertDialog(
        modifier = Modifier.testTag("overlay_progress"),
        onDismissRequest = {},
        title = { Text(text) },
        text = {
            Column {
                if (total > 0) {
                    // Determinate: a smooth bar plus the two signals a bare bar withholds
                    // — how far along (percent) and what is happening. Byte-level ops
                    // (zip extraction, owner report "441 of 2") drive the percent from
                    // bytes: an archive of a couple of huge files made the item-derived
                    // bar jump 0 → 50% → 100% while nothing seemed to happen; bytes move
                    // it every buffer. Item-count ops (copy, move, compress) keep the
                    // item-derived percent.
                    val hasBytes = bytesTotal > 0
                    val target = if (hasBytes) {
                        (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
                    } else {
                        (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                    val animated by animateFloatAsState(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 250),
                        label = "progress_fraction",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${(target * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val item = currentItem?.takeIf { it.isNotBlank() }
                    if (hasBytes) {
                        // Line 1: the byte tally with the live entry (middle-ellipsis —
                        // the meaningful tail of a name survives long res/… paths).
                        Text(
                            buildString {
                                append(Format.size(bytesDone))
                                append(" of ").append(Format.size(bytesTotal))
                                if (item != null) append(" — ").append(item)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Line 2: the item count, so "how many files" stays answered.
                        Text(
                            "File $done of $total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            buildString {
                                append(done).append(" of ").append(total)
                                append(if (total == 1) " item" else " items")
                                if (item != null) append(" — ").append(item)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                } else {
                    // Unknown total (zip-volume merge, listing-driven ops): indeterminate.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Working…", style = MaterialTheme.typography.bodyMedium)
                    }
                    currentItem?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Finished-dialog title derived from the op's short word ("Copying" → "Copy finished"):
 *  the dialog used to say "Operation finished" for everything, which never told you
 *  WHAT finished. Unknown/blank titles keep the old generic text. */
fun resultDoneTitle(resultTitle: String): String = when (resultTitle.trim()) {
    "Copying" -> "Copy finished"
    "Moving" -> "Move finished"
    "Extracting" -> "Extract finished"
    "Zipping" -> "Zip finished"
    "Compressing" -> "Compression finished"
    else -> "Operation finished"
}

@Composable
fun ResultDialog(
    summary: String,
    failures: List<String>,
    onDismiss: () -> Unit,
    resultTitle: String = "",
) {
    AlertDialog(
        modifier = Modifier.testTag("dialog_result"),
        onDismissRequest = onDismiss,
        title = { Text(resultDoneTitle(resultTitle)) },
        text = {
            Column {
                Text(summary, style = MaterialTheme.typography.bodyLarge)
                if (failures.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Details:", style = MaterialTheme.typography.titleMedium)
                    Text(
                        failures.take(10).joinToString("\n") { "• $it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Stable user-facing label per sort mode (audit A26 — never derived from enum.name). */
fun sortModeLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "Name"
    SortMode.SIZE -> "Size"
    SortMode.DATE -> "Date"
    SortMode.TYPE -> "Type"
}

@Composable
fun SortMenu(
    expanded: Boolean,
    current: SortMode,
    currentOrder: SortOrder,
    onSelect: (SortMode) -> Unit,
    onSelectOrder: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMode.entries.forEach { mode ->
            androidx.compose.material3.DropdownMenuItem(
                // Audit A26: stable label mapping — deriving the copy from enum.name
                // meant a silent enum rename reworded the UI.
                text = { Text(sortModeLabel(mode)) },
                trailingIcon = {
                    if (mode == current) {
                        Icon(Icons.Outlined.Check, contentDescription = "Selected")
                    }
                },
                onClick = { onSelect(mode) },
            )
        }
        androidx.compose.material3.HorizontalDivider()
        SortOrder.entries.forEach { order ->
            val label = if (order == SortOrder.ASC) "Ascending" else "Descending"
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(label) },
                trailingIcon = {
                    if (order == currentOrder) {
                        Icon(Icons.Outlined.Check, contentDescription = "Selected")
                    }
                },
                onClick = { onSelectOrder(order) },
            )
        }
    }
}
