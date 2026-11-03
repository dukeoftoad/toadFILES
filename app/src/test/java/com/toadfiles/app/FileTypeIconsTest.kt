package com.toadfiles.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Videocam
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.ui.components.TYPE_LABELS
import com.toadfiles.app.ui.components.iconFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins [iconFor] to the friendly type names shown by the Properties sheet
 * ([TYPE_LABELS]): every extension that gets a human label like "HEIF image" or
 * "HTML document" must also get the matching category icon in the file lists —
 * otherwise the sheet says "image" while the row shows a generic file icon.
 *
 * Before this test, "heif" had an "HEIF image" label but a generic icon, and
 * "htm"/"html" ("HTML document") were unlisted too. Code/data files (json, xml,
 * kt, java) keep the generic file icon ON PURPOSE — there is no source-code icon
 * in the outlined set, and they are pinned here so that stays a decision, not drift.
 */
class FileTypeIconsTest {

    private fun item(name: String, isDir: Boolean = false) =
        iconFor(FileItem(File("/tmp/toadfiles-test/$name"), name, isDir, 0L, 0L))

    @Test
    fun `every labeled extension gets its category icon`() {
        val expected = mapOf(
            "image" to Icons.Outlined.Image,
            "video" to Icons.Outlined.Videocam,
            "audio" to Icons.Outlined.Audiotrack,
            "archive" to Icons.Outlined.FolderZip,
            "package" to Icons.Outlined.InstallMobile,
            "document" to Icons.Outlined.Description,
            "spreadsheet" to Icons.Outlined.Description,
            "presentation" to Icons.Outlined.Description,
            // "Text file" / "JSON file" — the generic doc icon for txt/md too.
            "file" to Icons.Outlined.Description,
        )
        val intentionallyGeneric = setOf("json", "xml", "kt", "java")
        for ((ext, label) in TYPE_LABELS) {
            val want = if (ext in intentionallyGeneric) {
                Icons.AutoMirrored.Outlined.InsertDriveFile
            } else {
                expected.entries.firstOrNull { label.endsWith(it.key) }?.value
                    ?: error("No icon rule for \"$label\" ($ext) — add one to iconFor or the allowlist")
            }
            assertEquals("icon for .$ext (\"$label\")", want, item("file.$ext"))
        }
    }

    @Test
    fun `regression - heif and html-family show their category icon`() {
        assertEquals(Icons.Outlined.Image, item("photo.heif"))
        assertEquals(Icons.Outlined.Description, item("page.html"))
        assertEquals(Icons.Outlined.Description, item("page.htm"))
    }

    @Test
    fun `unknown, extension-less and upper-case names behave`() {
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, item("data.xyz"))
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, item("README"))
        assertEquals(Icons.Outlined.FolderZip, item("BACKUP.ZIP"))
        assertEquals(Icons.Outlined.Folder, item("Pictures", isDir = true))
    }
}
