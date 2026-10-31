package com.toadfiles.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Retry context for an encrypted archive whose extraction is waiting for a password
 * (Browse / Categories / Recent share it). The retry re-runs the exact original
 * extract: same archive, same target directory, same replace semantics — only the
 * password is new. Owned by the ViewModel states, defined here so all three
 * surfaces use one type, like [PropertiesDetail].
 */
data class PendingPasswordRetry(
    val archive: File,
    val target: File,
    val replace: Boolean,
)

/**
 * Small password prompt (v1.8.0) shown when an extract comes back
 * [com.toadfiles.app.domain.OpResult.passwordRequired]: the archive is encrypted, so
 * toadFILES asks instead of failing. The typed value is handed to the ViewModel's
 * retry hook; a wrong password comes back as a normal failure result ("Wrong
 * password") through the existing result dialog, and the user can try again.
 */
@Composable
fun PasswordPromptDialog(
    archiveName: String,
    onRetry: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(archiveName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    "This archive is encrypted. Enter its password to extract it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { PasswordEyeToggle(visible) { visible = !visible } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRetry(value) }, enabled = value.isNotBlank()) {
                Text("Retry")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
