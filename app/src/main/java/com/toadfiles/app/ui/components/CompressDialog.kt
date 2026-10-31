package com.toadfiles.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.CompressLevel

/**
 * Shared compress dialog (v1.7.5 — extracted from BrowseScreen so Categories offers
 * the exact same flow): target name, destination preview, format (ZIP / 7z) and
 * compression level. Archives are never created under a silent auto name.
 */
@Composable
fun CompressDialog(
    count: Int,
    name: String,
    destination: File?,
    format: ArchiveFormat,
    level: CompressLevel,
    onNameChange: (String) -> Unit,
    onFormatChange: (ArchiveFormat) -> Unit,
    onLevelChange: (CompressLevel) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress ${if (count == 1) "1 item" else "$count items"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text("Archive name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                destination?.let {
                    Text(
                        "Into ${it.path}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Format", style = MaterialTheme.typography.titleSmall)
                ArchiveFormat.entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onFormatChange(f) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == f, onClick = { onFormatChange(f) })
                        Text(f.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Compression level", style = MaterialTheme.typography.titleSmall)
                CompressLevel.entries.forEach { l ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onLevelChange(l) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = level == l, onClick = { onLevelChange(l) })
                        Text(l.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
