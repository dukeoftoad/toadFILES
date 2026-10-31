package com.toadfiles.app.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.Format

/**
 * Mi Files-style long-press action sheet. Shows when the user long-presses a single file
 * outside of multi-select. Action visibility is honest: actions that don't apply to the
 * current item (e.g. "Extract here" for a non-zip, "Open with" for a folder) are simply
 * not shown — we never render a disabled row that hints at features the user can't reach.
 *
 * The sheet is deliberately narrow: Open / Share / Rename / Copy / Move / Delete /
 * Compress / Favorite / Pin to Home / Properties / Copy path / Open with. Multi-select is
 * started by long-pressing a row (Google Files style) — no extra entry point needed —
 * and the bulk toolbar (select-all, bulk compress, …) takes over from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionSheet(
    item: FileItem,
    isFavorite: Boolean,
    pinnedToHome: Boolean = false,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onZip: () -> Unit,
    onExtract: (() -> Unit)?,
    onExtractTo: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit,
    onCopyPath: () -> Unit,
    onOpenWith: () -> Unit,
    onTogglePin: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
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
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        if (item.isDir) "Folder" else "${Format.size(item.size)} · ${Format.date(item.lastModified)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // Primary actions: Open / Share / Rename
            SheetRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open", onClick = onOpen)
            SheetRow(Icons.Outlined.Share, "Share", onClick = onShare)
            SheetRow(Icons.Outlined.DriveFileRenameOutline, "Rename", onClick = onRename)

            // Clipboard actions: Copy / Move
            SheetRow(Icons.Outlined.ContentCopy, "Copy to…", onClick = onCopy)
            SheetRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to…", onClick = onMove)

            // Organize: Delete / Compress / Extract. Archives toadFILES can extract
            // (.zip, .7z) get Extract actions instead of "Compress this file" — both
            // formats are also creatable since v1.7.3 via the compress dialog's
            // format chooser, but an existing archive is far more often extracted.
            SheetRow(Icons.Outlined.Delete, "Delete", destructive = true, onClick = onDelete)
            if (item.isDir) {
                SheetRow(Icons.Outlined.Archive, "Compress this folder", onClick = onZip)
            } else if (ArchiveOps.canExtract(item.name)) {
                SheetRow(Icons.Outlined.FolderZip, "Extract here", onClick = { onExtract?.invoke() })
                // Destination picking is optional plumbing: hide the row when a screen
                // can't handle it, like every other non-applicable action here.
                if (onExtractTo != null) {
                    SheetRow(Icons.Outlined.FolderOpen, "Extract to…", onClick = onExtractTo)
                }
            } else {
                SheetRow(Icons.Outlined.Archive, "Compress this file", onClick = onZip)
            }

            // Favorite / advanced
            SheetRow(
                if (isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (isFavorite) "Remove favorite" else "Add favorite",
                onClick = onToggleFavorite,
            )
            if (item.isDir && onTogglePin != null) {
                SheetRow(
                    Icons.Outlined.PushPin,
                    if (pinnedToHome) "Unpin from Home" else "Pin to Home",
                    onClick = onTogglePin,
                )
            }
            // Read-only properties sheet; offered for files and folders alike.
            SheetRow(Icons.Outlined.Info, "Properties", onClick = onDetails)
            if (!item.isDir) {
                SheetRow(Icons.Outlined.ContentPaste, "Copy path", onClick = onCopyPath)
                SheetRow(Icons.Outlined.MoreHoriz, "Open with…", onClick = onOpenWith)
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
