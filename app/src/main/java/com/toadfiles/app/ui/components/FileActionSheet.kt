package com.toadfiles.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Format
import kotlinx.coroutines.launch

/**
 * The app's ONE per-file action sheet (Mi Files style), shared by Browse (FileItem rows)
 * and the media-library screens (Categories drill-in, Recent) since v1.7.3. Shows when
 * the user taps a row's ⋮ button; long-press starts multi-select instead.
 *
 * Action visibility is honest: an action the calling screen did not wire (null callback)
 * or that doesn't apply to the item (e.g. "Extract here" for a non-archive, "Open with…" for
 * a folder) is simply not shown — we never render a disabled row that hints at features
 * the user can't reach. Browse wires everything; the media screens wire the actions that
 * make sense for a MediaStore row (path-dependent actions only when the on-disk path is
 * known, clipboard copy/move only where a paste target exists). Both zip and 7z are
 * creatable since v1.7.3 via the compress dialog's format chooser, but an existing
 * archive is far more often extracted, so archives get Extract actions instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionSheet(
    item: FileItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    isFavorite: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onZip: (() -> Unit)? = null,
    onExtract: (() -> Unit)? = null,
    onExtractTo: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onCopyPath: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    onShowInFolder: (() -> Unit)? = null,
    pinnedToHome: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /**
     * Audit A21: every row slides the sheet DOWN first (sheetState.hide()), then
     * dismisses and runs its action — the same exit animation a swipe-to-dismiss
     * gets. Row callbacks call [onDismiss] themselves (idempotent state clearing),
     * but that call used to pop the sheet out of composition instantly, so tapping
     * a row and swiping it away looked nothing alike.
     */
    val perform: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        modifier = Modifier.testTag("sheet_file_actions"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            // Header with icon, name, size
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    iconFor(item),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.fillMaxWidth()) {
                    // The sheet header keeps the extension visible even for long names
                    // (it also used to hard-clip: maxLines = 1 with no overflow policy).
                    FileNameText(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        isDirectory = item.isDir,
                    )
                    Text(
                        if (item.isDir) "Folder" else "${Format.size(item.size)} · ${Format.dateTime(item.lastModified)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // Primary actions: Open / Share / Rename
            SheetRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open", onClick = { perform(onOpen) })
            SheetRow(Icons.Outlined.Share, "Share", onClick = { perform(onShare) })
            if (onRename != null) {
                SheetRow(Icons.Outlined.DriveFileRenameOutline, "Rename", onClick = { perform(onRename) })
            }

            // Clipboard actions: Copy / Move (only where a screen has a paste target)
            if (onCopy != null) {
                SheetRow(Icons.Outlined.ContentCopy, "Copy to…", onClick = { perform(onCopy) })
            }
            if (onMove != null) {
                SheetRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to…", onClick = { perform(onMove) })
            }

            // Organize: Move to trash / Compress / Extract. Archives toadFILES can extract (.zip,
            // .7z — and the .xapk/.apkm app bundles since v2.1.0) get Extract actions instead of
            // "Compress this file" — both formats are also creatable since v1.7.3 via the compress
            // dialog's format chooser, but an existing archive is far more often extracted.
            if (onDelete != null) {
                SheetRow(Icons.Outlined.Delete, "Move to trash", destructive = true, onClick = { perform(onDelete) })
            }
            when {
                item.isDir && onZip != null ->
                    SheetRow(Icons.Outlined.Archive, "Compress this folder", onClick = { perform(onZip) })
                ArchiveOps.canExtract(item.name) && onExtract != null -> {
                    SheetRow(Icons.Outlined.FolderZip, "Extract here", onClick = { perform(onExtract) })
                    // Destination picking is optional plumbing: hide the row when a screen
                    // can't handle it, like every other non-applicable action here.
                    if (onExtractTo != null) {
                        SheetRow(Icons.Outlined.FolderOpen, "Extract to…", onClick = { perform(onExtractTo) })
                    }
                }
                onZip != null ->
                    SheetRow(Icons.Outlined.Archive, "Compress this file", onClick = { perform(onZip) })
            }

            // Favorite / advanced
            if (onToggleFavorite != null) {
                SheetRow(
                    if (isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    if (isFavorite) "Remove favorite" else "Add favorite",
                    onClick = { perform(onToggleFavorite) },
                )
            }
            if (item.isDir && onTogglePin != null) {
                SheetRow(
                    Icons.Outlined.PushPin,
                    if (pinnedToHome) "Unpin from Home" else "Pin to Home",
                    onClick = { perform(onTogglePin) },
                )
            }
            // Read-only properties sheet; offered for files and folders alike.
            SheetRow(Icons.Outlined.Info, "Properties", onClick = { perform(onDetails) })
            if (onShowInFolder != null) {
                SheetRow(Icons.Outlined.FolderOpen, "Show in folder", onClick = { perform(onShowInFolder) })
            }
            if (!item.isDir && onCopyPath != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { perform(onCopyPath) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ContentPaste,
                        contentDescription = "Copy full path",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Path", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            item.file.absolutePath,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Copy", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!item.isDir && onOpenWith != null) {
                SheetRow(Icons.Outlined.MoreHoriz, "Open with…", onClick = { perform(onOpenWith) })
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
