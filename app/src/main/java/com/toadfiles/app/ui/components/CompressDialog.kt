package com.toadfiles.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import java.io.File
import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.CompressLevel
// Audit A23: the volume contract (VolumeChoice / VOLUME_MB / mappers) moved to
// domain/ArchivePrefs.kt next to its sibling contract enums — imported below.
import com.toadfiles.app.domain.VolumeChoice
import com.toadfiles.app.domain.volumeChoiceFor

/** Encryption options offered in the compress dialog (7z only). */
val ENCRYPTION_OPTIONS = listOf("None", "Data (AES-256)")

/**
 * OK is enabled only with a valid name and — when data encryption is on — a non-blank
 * password that matches its confirmation. Format-specific rules (ZIP cannot encrypt)
 * are enforced by the ViewModel: it never leaves [encrypted] on for ZIP.
 */
fun compressConfirmEnabled(
    name: String,
    encrypted: Boolean,
    password: String,
    passwordConfirm: String,
): Boolean = name.isNotBlank() &&
    (!encrypted || (password.isNotEmpty() && password == passwordConfirm))

/**
 * Shared compress dialog (v1.7.5 shared surface, v1.8.0 ZArchiver-style layout):
 * archive name, destination preview, format (ZIP / 7z) and compression level
 * dropdowns, 7z-only data encryption (AES-256) with password + confirmation,
 * "split into volumes" with a Custom… size, "delete source files" and — for batches —
 * "create a separate archive per item". All state lives in the calling ViewModel;
 * the dialog is pure UI. Archives are never created under a silent auto name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressDialog(
    count: Int,
    name: String,
    destination: File?,
    format: ArchiveFormat,
    level: CompressLevel,
    encrypted: Boolean,
    password: String,
    passwordConfirm: String,
    volumeBytes: Long?,
    volumeCustom: Boolean,
    volumeCustomText: String,
    deleteSources: Boolean,
    separate: Boolean,
    onNameChange: (String) -> Unit,
    onFormatChange: (ArchiveFormat) -> Unit,
    onLevelChange: (CompressLevel) -> Unit,
    onEncryptedChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onVolumeChange: (VolumeChoice) -> Unit,
    onVolumeCustomTextChange: (String) -> Unit,
    onDeleteSourcesChange: (Boolean) -> Unit,
    onSeparateChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // One eye toggle reveals both password fields, ZArchiver-style.
    var showPassword by remember { mutableStateOf(false) }
    // commons-compress cannot create encrypted zip: the encryption choice exists
    // only for 7z. The ViewModel also resets it when the format flips to ZIP.
    val encryptionEnabled = format == ArchiveFormat.SEVEN_Z
    val volumeChoice = volumeChoiceFor(volumeBytes, volumeCustom)

    AlertDialog(
        modifier = Modifier.testTag("dialog_compress"),
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
                DropdownField(
                    label = "Format",
                    options = ArchiveFormat.entries.map { it.label },
                    selected = format.label,
                ) { onFormatChange(ArchiveFormat.entries[it]) }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "Compression level",
                    options = CompressLevel.entries.map { it.label },
                    selected = level.label,
                ) { onLevelChange(CompressLevel.entries[it]) }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "Encryption",
                    options = ENCRYPTION_OPTIONS,
                    selected = if (encrypted) ENCRYPTION_OPTIONS[1] else ENCRYPTION_OPTIONS[0],
                    enabled = encryptionEnabled,
                ) { onEncryptedChange(it == 1) }
                if (encrypted && encryptionEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            PasswordEyeToggle(showPassword) { showPassword = !showPassword }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = onPasswordConfirmChange,
                        singleLine = true,
                        label = { Text("Confirm password") },
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            PasswordEyeToggle(showPassword) { showPassword = !showPassword }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "Split into volumes",
                    options = VolumeChoice.entries.map { it.label },
                    selected = volumeChoice.label,
                ) { onVolumeChange(VolumeChoice.entries[it]) } // domain enum (A23)
                if (volumeChoice == VolumeChoice.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = volumeCustomText,
                        onValueChange = onVolumeCustomTextChange,
                        singleLine = true,
                        label = { Text("Volume size (MB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                CheckboxRow(
                    label = "Delete source files after archiving",
                    checked = deleteSources,
                    onCheckedChange = onDeleteSourcesChange,
                )
                if (count > 1) {
                    CheckboxRow(
                        label = "Create separate archive for each item",
                        checked = separate,
                        onCheckedChange = onSeparateChange,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = compressConfirmEnabled(name, encrypted, password, passwordConfirm),
            ) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The shared eye icon button used by the compress and password-prompt dialogs. */
@Composable
fun PasswordEyeToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (visible) "Hide password" else "Show password",
        )
    }
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Read-only ExposedDropdownMenuBox with the app's field styling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
