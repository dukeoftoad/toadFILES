package com.toadfiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.toadfiles.app.data.Clipboard

/**
 * The persistent move/copy bar, docked above the bottom navigation while a transfer is
 * armed (Browse screen). It is the ONLY feedback for an armed transfer — no extra toast.
 *
 * Design: inspired by zhanghai/MaterialFiles (GPL-3.0) — one calm, neutral surface with
 * an explicit Cancel — and Google Files' action-first "Move to here / Copy to here"
 * wording. The icon states the intent (drive-file-move vs copy) instead of a generic
 * paste glyph, and the count is properly pluralized ("1 item" / "2 items"), never "(s)".
 *
 * Purely presentational: the caller decides whether Paste is enabled and why
 * ([blockedReason] — e.g. "You can't move a folder into itself", shown in the error
 * color in place of the count while Paste is disabled).
 */
@Composable
fun TransferBar(
    mode: Clipboard.Mode,
    count: Int,
    blockedReason: String?,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("transfer_bar"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .testTag(
                        if (mode == Clipboard.Mode.MOVE) "transfer_icon_move"
                        else "transfer_icon_copy",
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (mode == Clipboard.Mode.MOVE) Icons.AutoMirrored.Outlined.DriveFileMove
                    else Icons.Outlined.ContentCopy,
                    contentDescription = null, // the title carries the meaning
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (mode == Clipboard.Mode.MOVE) "Move to here" else "Copy to here",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (blockedReason != null) blockedReason
                    else if (count == 1) "1 item" else "$count items",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blockedReason != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("transfer_cancel")) {
                Text("Cancel")
            }
            Button(
                onClick = onPaste,
                enabled = blockedReason == null,
                modifier = Modifier.testTag("transfer_paste"),
            ) {
                Text("Paste")
            }
        }
    }
}
