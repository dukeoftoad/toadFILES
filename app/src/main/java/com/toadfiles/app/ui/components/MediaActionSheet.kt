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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
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
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.Format

/**
 * Action sheet for MediaStore entries (Categories drill-in, Recent), opened from a row's
 * overflow button. Mirrors the [FileActionSheet] used in Browse, trimmed to the actions that
 * make sense for media-library rows: everything works on the content URI, and path-dependent
 * actions (delete to trash, copy path, show in folder) are only offered when the on-disk
 * path is known. Actions that don't apply are hidden, never rendered disabled. Multi-select
 * is started by long-pressing a row, so the sheet needs no "Select more" entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionSheet(
    entry: MediaRepository.MediaEntry,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onShowInFolder: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDetails: () -> Unit,
    onCopyPath: (() -> Unit)?,
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    mediaIconFor(entry),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        "${Format.size(entry.size)} · ${Format.date(entry.dateModified)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            MediaSheetRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open", onClick = onOpen)
            MediaSheetRow(Icons.Outlined.MoreHoriz, "Open with…", onClick = onOpenWith)
            MediaSheetRow(Icons.Outlined.Share, "Share", onClick = onShare)
            if (onShowInFolder != null) {
                MediaSheetRow(Icons.Outlined.FolderOpen, "Show in folder", onClick = onShowInFolder)
            }
            if (onCopyPath != null) {
                MediaSheetRow(Icons.Outlined.ContentPaste, "Copy path", onClick = onCopyPath)
            }
            if (onDelete != null) {
                MediaSheetRow(Icons.Outlined.Delete, "Delete", destructive = true, onClick = onDelete)
            }
            MediaSheetRow(Icons.Outlined.Info, "Details", onClick = onDetails)

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Icon for a media entry based on its category — matches the look of Browse rows. */
private fun mediaIconFor(entry: MediaRepository.MediaEntry) = when (entry.category) {
    com.toadfiles.app.domain.FileCategory.IMAGES -> Icons.Outlined.Image
    com.toadfiles.app.domain.FileCategory.VIDEOS -> Icons.Outlined.Videocam
    com.toadfiles.app.domain.FileCategory.AUDIO -> Icons.Outlined.Audiotrack
    com.toadfiles.app.domain.FileCategory.ARCHIVES -> Icons.Outlined.FolderZip
    com.toadfiles.app.domain.FileCategory.APKS -> Icons.Outlined.InstallMobile
    com.toadfiles.app.domain.FileCategory.DOCS -> Icons.Outlined.Description
}

@Composable
private fun MediaSheetRow(
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
