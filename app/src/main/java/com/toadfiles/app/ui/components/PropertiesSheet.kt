package com.toadfiles.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.toadfiles.app.domain.Checksums
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.PropertiesBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Computed detail behind the properties sheet (shared by Browse, Categories and any
 * other screen that opens [PropertiesSheet]). Folder sizes/counts arrive asynchronously
 * (recursive walk on IO); [PropertiesDetail.error] carries the app's standard error copy
 * when a folder could not be read at all. Files use [fileCount]/[dirCount] = 0.
 *
 * Promoted to ui/components (v1.7.3) from BrowseViewModel so the media screens can offer
 * the exact same properties sheet without importing Browse internals.
 */
data class PropertiesDetail(
    val sizeText: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
    val error: String? = null,
)

/**
 * The one size/contents computation behind every [PropertiesSheet] (formerly Browse's
 * private `computePropertiesDetail`): size via [FileOps.sizeOf] (the app's single size
 * engine) and, for folders, content counts via [PropertiesBuilder]. Cancellation is
 * checked between the walks so a dismissed sheet drops its late result.
 */
suspend fun computeFilePropertiesDetail(
    file: File,
    builder: PropertiesBuilder,
): PropertiesDetail {
    if (file.isDirectory && !file.canRead()) {
        return PropertiesDetail(
            sizeText = "—", sizeBytes = 0L, fileCount = 0, dirCount = 0,
            error = "Folder is not readable",
        )
    }
    val sizeBytes = FileOps.sizeOf(file)
    coroutineContext.ensureActive()
    val counts = if (file.isDirectory) builder.countEntries(file) else null
    return PropertiesDetail(
        sizeText = Format.size(sizeBytes),
        sizeBytes = sizeBytes,
        fileCount = counts?.files ?: 0,
        dirCount = counts?.dirs ?: 0,
    )
}

/**
 * Read-only properties sheet for a single file or folder (Browse's ⋮ → Properties, the
 * Browse selection menu and the Categories drill-in). Rendered like [FileActionSheet]:
 * a Material 3 modal bottom sheet with the same header treatment. The only action
 * inside is Close — nothing here mutates.
 *
 * [detail] is produced asynchronously by the owning ViewModel via
 * [computeFilePropertiesDetail]; while it is null the size and contents rows show a
 * "Calculating…" placeholder, and a folder that could not be read shows the app's
 * standard "Folder is not readable" copy in the error color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesSheet(
    file: File,
    detail: PropertiesDetail?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = Modifier.testTag("sheet_properties"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // Header with icon and the full, selectable name.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    iconFor(FileItem(file, file.name, file.isDirectory, 0L, 0L)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                SelectionContainer {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            DetailRow("Type", fileTypeLabel(file.name, file.isDirectory))
            DetailRow("Location", file.parentFile?.path ?: "/", selectable = true)

            if (file.isDirectory) {
                when {
                    detail == null -> {
                        DetailRow("Size", "", loading = true)
                        DetailRow("Contents", "", loading = true)
                    }
                    detail.error != null ->
                        DetailRow("Size", detail.error, valueColor = MaterialTheme.colorScheme.error)
                    else -> {
                        DetailRow("Size", detail.sizeText)
                        DetailRow("Contents", "${detail.fileCount} files · ${detail.dirCount} subfolders")
                    }
                }
            } else {
                // A plain file's size needs no walk: the detail lands almost immediately,
                // but the loading state is still honoured for the one dispatch gap.
                when {
                    detail == null -> DetailRow("Size", "", loading = true)
                    else -> DetailRow("Size", detail.sizeText)
                }
            }

            DetailRow("Modified", Format.dateTime(file.lastModified()))
            // v2.1.0 "Add SHA": a selectable SHA-256 for files, computed on IO while the
            // sheet is open (cancels with it — the LaunchedEffect dies on dismiss). The
            // hex lands in a selection container so a long-press copies it; big files
            // show the same "Calculating…" treatment as folder sizes.
            if (!file.isDirectory) {
                var sha256 by remember(file.path) { mutableStateOf<String?>(null) }
                var failed by remember(file.path) { mutableStateOf(false) }
                LaunchedEffect(file.path) {
                    runCatching { withContext(Dispatchers.IO) { Checksums.sha256(file) } }
                        .onSuccess { sha256 = it }
                        .onFailure { failed = true }
                }
                if (failed) {
                    DetailRow("Checksum (SHA-256)", "Could not read the file", valueColor = MaterialTheme.colorScheme.error)
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            "Checksum (SHA-256)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        if (sha256 == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Calculating…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            SelectionContainer {
                                Text(
                                    sha256!!, // set one branch above — non-null here
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            // The app's hidden rule is dot-prefixed names (FileRepository.list); the row is
            // only shown when it actually applies — no invented attributes.
            if (file.name.startsWith(".")) {
                DetailRow("Hidden", "Yes")
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                Text("Close")
            }
        }
    }
}

/**
 * Extension-derived human type label: folders are "Folder", common extensions map to a
 * friendly name ("ZIP archive", "JPEG image", …), anything unknown falls back to the raw
 * extension uppercase, and extension-less files to plain "File".
 */
fun fileTypeLabel(name: String, isDir: Boolean): String {
    if (isDir) return "Folder"
    val dot = name.lastIndexOf('.')
    val ext = if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    if (ext.isEmpty()) return "File"
    return TYPE_LABELS[ext] ?: ext.uppercase()
}

// internal (not private) so FileTypeIconsTest can pin iconFor() to the same extension
// set: a friendly label here must never come with a generic file icon in the lists.
internal val TYPE_LABELS = mapOf(
    "zip" to "ZIP archive",
    "rar" to "RAR archive",
    "7z" to "7z archive",
    "tar" to "Tar archive",
    "gz" to "Gzip archive",
    "bz2" to "Bzip2 archive",
    "xz" to "XZ archive",
    "jpg" to "JPEG image",
    "jpeg" to "JPEG image",
    "png" to "PNG image",
    "gif" to "GIF image",
    "webp" to "WebP image",
    "bmp" to "BMP image",
    "heic" to "HEIC image",
    "heif" to "HEIF image",
    "svg" to "SVG image",
    "mp4" to "MP4 video",
    "mkv" to "MKV video",
    "avi" to "AVI video",
    "mov" to "QuickTime video",
    "webm" to "WebM video",
    "3gp" to "3GP video",
    "m4v" to "M4V video",
    "mp3" to "MP3 audio",
    "flac" to "FLAC audio",
    "wav" to "WAV audio",
    "ogg" to "OGG audio",
    "m4a" to "M4A audio",
    "aac" to "AAC audio",
    "opus" to "Opus audio",
    "amr" to "AMR audio",
    "pdf" to "PDF document",
    "txt" to "Text file",
    "md" to "Markdown document",
    "csv" to "CSV spreadsheet",
    "doc" to "Word document",
    "docx" to "Word document",
    "xls" to "Excel spreadsheet",
    "xlsx" to "Excel spreadsheet",
    "ppt" to "PowerPoint presentation",
    "pptx" to "PowerPoint presentation",
    "apk" to "Android package",
    "html" to "HTML document",
    "htm" to "HTML document",
    "json" to "JSON file",
    "xml" to "XML file",
    "kt" to "Kotlin source",
    "java" to "Java source",
)

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    selectable: Boolean = false,
    loading: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Calculating…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (selectable) {
            SelectionContainer {
                ValueText(value, valueColor)
            }
        } else {
            ValueText(value, valueColor)
        }
    }
}

@Composable
private fun ValueText(value: String, color: Color) {
    Text(value, style = MaterialTheme.typography.bodyLarge, color = color)
}
