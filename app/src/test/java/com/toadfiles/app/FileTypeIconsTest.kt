package com.toadfiles.app

import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.ui.components.TYPE_LABELS
import com.toadfiles.app.ui.components.iconFor
import com.toadfiles.app.ui.theme.ToadFileApk
import com.toadfiles.app.ui.theme.ToadFileArchive
import com.toadfiles.app.ui.theme.ToadFileAudio
import com.toadfiles.app.ui.theme.ToadFileDoc
import com.toadfiles.app.ui.theme.ToadFileGeneric
import com.toadfiles.app.ui.theme.ToadFileImage
import com.toadfiles.app.ui.theme.ToadFileVideo
import com.toadfiles.app.ui.theme.ToadFolder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins [iconFor] to the friendly type names shown by the Properties sheet
 * ([TYPE_LABELS]): every extension that gets a human label like "HEIF image" or
 * "HTML document" must also get the matching category icon in the file lists —
 * otherwise the sheet says "image" while the row shows a generic file icon.
 *
 * Since the v1.11 redesign the family is the custom duotone toad set
 * ([ToadFileImage], [ToadFileVideo], …, [ToadFolder]). Code/data files (json, xml,
 * kt, java) keep the generic dot-grid icon ON PURPOSE — they are pinned here so
 * that stays a decision, not drift.
 */
class FileTypeIconsTest {

    private fun item(name: String, isDir: Boolean = false) =
        iconFor(FileItem(File("/tmp/toadfiles-test/$name"), name, isDir, 0L, 0L))

    @Test
    fun `every labeled extension gets its category icon`() {
        val expected = mapOf(
            "image" to ToadFileImage,
            "video" to ToadFileVideo,
            "audio" to ToadFileAudio,
            "archive" to ToadFileArchive,
            "package" to ToadFileApk,
            "document" to ToadFileDoc,
            "spreadsheet" to ToadFileDoc,
            "presentation" to ToadFileDoc,
            // "Text file" / "Markdown document" — the doc icon for txt/md too.
            "file" to ToadFileDoc,
        )
        val intentionallyGeneric = setOf("json", "xml", "kt", "java")
        for ((ext, label) in TYPE_LABELS) {
            val want = if (ext in intentionallyGeneric) {
                ToadFileGeneric
            } else {
                expected.entries.firstOrNull { label.endsWith(it.key) }?.value
                    ?: error("No icon rule for \"$label\" ($ext) — add one to iconFor or the allowlist")
            }
            assertEquals("icon for .$ext (\"$label\")", want, item("file.$ext"))
        }
    }

    @Test
    fun `regression - heif and html-family show their category icon`() {
        assertEquals(ToadFileImage, item("photo.heif"))
        assertEquals(ToadFileDoc, item("page.html"))
        assertEquals(ToadFileDoc, item("page.htm"))
    }

    @Test
    fun `unknown, extension-less and upper-case names behave`() {
        assertEquals(ToadFileGeneric, item("data.xyz"))
        assertEquals(ToadFileGeneric, item("README"))
        assertEquals(ToadFileArchive, item("BACKUP.ZIP"))
        assertEquals(ToadFolder, item("Pictures", isDir = true))
    }
}
