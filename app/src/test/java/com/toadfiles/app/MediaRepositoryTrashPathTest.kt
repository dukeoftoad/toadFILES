package com.toadfiles.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * Trash-path detection powers the "hide trashed files everywhere" rule: renaming a file
 * into the per-volume trash folder does not instantly update the MediaStore index, so
 * Recent and Categories filter rows by their DATA path instead. The media-store-backed
 * tests below run against a fake provider registered on Robolectric's
 * ShadowContentResolver (same pattern as the screenshot fixtures), so queries AND the
 * repo-side stale-row cleanup go through the real ContentResolver plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaRepositoryTrashPathTest {

    /** One media row: id, display name, size bytes, modified-seconds, mime, on-disk path, media_type. */
    private data class Row(
        val id: Long,
        val name: String,
        val size: Long,
        val dateModifiedSecs: Long,
        val mime: String?,
        val data: String?,
        val mediaType: Int,
    )

    /**
     * Minimal MediaStore double: row listing (selection evaluated the way the repo
     * writes it — media_type=?, mime IN/=? plus lower(name) LIKE extension fallbacks),
     * COUNT/SUM aggregates for summaries(), and recorded deletes.
     */
    private class FakeMediaProvider : ContentProvider() {

        var rows: List<Row> = emptyList()
        val deletedUris = mutableListOf<Uri>()

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String? = null

        private fun matches(r: Row, selection: String?, args: Array<out String>?): Boolean {
            if (selection == null) return true
            val a = args ?: emptyArray()
            // Extension fallbacks arrive as lower(_display_name) LIKE '%.<ext>' clauses.
            val likeExts = Regex("""LIKE '%\.([a-zA-Z0-9]+)'""")
                .findAll(selection)
                .map { it.groupValues[1] }
                .toList()
            val nameMatches = r.name != null && likeExts.any { r.name.lowercase().endsWith(".$it") }
            return when {
                selection.contains("media_type") -> a.firstOrNull() == r.mediaType.toString()
                selection.contains("mime_type IN") -> (r.mime != null && r.mime in a) || nameMatches
                selection.contains("mime_type") -> r.mime == a.firstOrNull() || nameMatches
                else -> true
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection
                ?: arrayOf("_id", "_display_name", "_size", "date_modified", "mime_type", "_data", "media_type")
            val filtered = rows.filter { matches(it, selection, selectionArgs) }
            // Aggregate projection (summaries()): COUNT(_id) + SUM(_size) over the selection.
            if (cols.size == 2 && cols[0].startsWith("COUNT(") && cols[1].startsWith("SUM(")) {
                return MatrixCursor(cols).apply {
                    addRow(listOf(filtered.size.toLong(), if (filtered.isEmpty()) null else filtered.sumOf { it.size }))
                }
            }
            val cursor = MatrixCursor(cols)
            for (r in filtered.sortedByDescending { it.dateModifiedSecs }) {
                cursor.addRow(
                    cols.map { c ->
                        when (c) {
                            "_id" -> r.id
                            "_display_name" -> r.name
                            "_size" -> r.size
                            "date_modified" -> r.dateModifiedSecs
                            "mime_type" -> r.mime
                            "_data" -> r.data
                            "media_type" -> r.mediaType
                            else -> null
                        }
                    },
                )
            }
            return cursor
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            return if (rows.any { it.id == id }) {
                deletedUris += uri
                1
            } else {
                0
            }
        }
    }

    private lateinit var root: File
    private lateinit var repo: MediaRepository
    private val provider = FakeMediaProvider()
    private var nextId = 1L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repo = MediaRepository(context.contentResolver)
        root = File(Environment.getExternalStorageDirectory(), "trash-path-test-${System.nanoTime()}").apply { mkdirs() }
        // Route every content://media/... query/delete to the fake provider.
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    // ---------- fixtures ----------

    private fun row(name: String, mime: String?, data: String?, mediaType: Int, size: Long = 10L) =
        Row(id = nextId++, name = name, size = size, dateModifiedSecs = 1_700_000_000L, mime = mime, data = data, mediaType = mediaType)

    private fun seed(vararg rows: Row) {
        provider.rows = rows.toList()
    }

    private fun realFile(name: String): String =
        File(root, name).apply { parentFile?.mkdirs(); writeText("x") }.path

    // ---------- trash-path detection (pure) ----------

    @Test
    fun `paths inside the trash folder on any volume are detected`() {
        assertTrue(MediaRepository.isTrashPath("/storage/emulated/0/${Trash.DIR_NAME}/1700000000_photo.jpg"))
        assertTrue(MediaRepository.isTrashPath("/storage/0000-0001/${Trash.DIR_NAME}/1700000000_song.mp3"))
    }

    @Test
    fun `the trash folder itself counts as inside`() {
        assertTrue(MediaRepository.isTrashPath("/storage/emulated/0/${Trash.DIR_NAME}"))
    }

    @Test
    fun `ordinary paths are not flagged`() {
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/DCIM/photo.jpg"))
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/Download/report.pdf"))
        assertFalse(MediaRepository.isTrashPath(""))
    }

    @Test
    fun `lookalike folder names are not flagged`() {
        // A file literally named ".toadfiles-trash" (not a folder segment) stays visible.
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/notes/.toadfiles-trash.txt"))
        // A sibling with the trash name as a substring is not the trash folder.
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/not.toadfiles-trash/file.txt"))
    }

    // ---------- stale rows: hidden AND removed from the index ----------

    @Test
    fun `list hides rows whose file vanished and deletes those stale rows`() {
        seed(
            row("keep.jpg", "image/jpeg", realFile("pics/keep.jpg"), 1),
            row("vanished.jpg", "image/jpeg", "/storage/emulated/0/pics/vanished.jpg", 1),
            row("trashed.jpg", "image/jpeg", "/storage/emulated/0/${Trash.DIR_NAME}/trashed.jpg", 1),
        )

        val entries = repo.list(FileCategory.IMAGES, 100)

        assertEquals(listOf("keep.jpg"), entries.map { it.name })
        // Only the truly vanished row is reconciled; trash-path rows stay in the
        // index because their files still exist inside the trash folder.
        assertEquals(1, provider.deletedUris.size)
        assertEquals("2", provider.deletedUris[0].lastPathSegment)
        assertTrue(provider.deletedUris[0].toString().startsWith("content://media/"))
    }

    @Test
    fun `recent hides rows whose file vanished and deletes those stale rows`() {
        seed(
            row("fresh.jpg", "image/jpeg", realFile("pics/fresh.jpg"), 1),
            row("ghost.mp3", "audio/mpeg", "/storage/emulated/0/Music/ghost.mp3", 3),
        )

        val entries = repo.recent(20)

        assertEquals(listOf("fresh.jpg"), entries.map { it.name })
        assertEquals(listOf("2"), provider.deletedUris.map { it.lastPathSegment })
    }

    @Test
    fun `stale cleanup is capped at 50 deletes per query`() {
        val rows = buildList {
            add(row("keep.txt", "text/plain", realFile("docs/keep.txt"), 6))
            repeat(55) { add(row("gone$it.txt", "text/plain", "/storage/emulated/0/Docs/gone$it.txt", 6)) }
        }
        seed(*rows.toTypedArray())

        repo.list(FileCategory.DOCS, 100)

        assertEquals(50, provider.deletedUris.size)
    }

    // ---------- archives match by extension, not only by MIME ----------

    @Test
    fun `archives match by extension when the mime type is missing`() {
        seed(
            row("backup.tar", null, realFile("archives/backup.tar"), 0),
            row("site.gz", null, realFile("archives/site.gz"), 0),
            row("notes.txt", null, realFile("archives/notes.txt"), 0),
        )

        val names = repo.list(FileCategory.ARCHIVES, 100).map { it.name }

        assertTrue(names.contains("backup.tar"))
        assertTrue(names.contains("site.gz"))
        assertFalse(names.contains("notes.txt"))
    }

    // ---------- summaries: true totals from COUNT/SUM aggregates ----------

    @Test
    fun `summaries reports true totals from the aggregate query not the listed size`() {
        seed(
            row("a.jpg", "image/jpeg", realFile("pics/a.jpg"), 1, size = 100L),
            row("b.jpg", "image/jpeg", realFile("pics/b.jpg"), 1, size = 200L),
            row("c.jpg", "image/jpeg", realFile("pics/c.jpg"), 1, size = 300L),
            row("d.jpg", "image/jpeg", realFile("pics/d.jpg"), 1, size = 400L),
            row("ghost.jpg", "image/jpeg", "/storage/emulated/0/pics/ghost.jpg", 1, size = 500L),
        )

        val summary = repo.summaries().first { it.category == FileCategory.IMAGES }

        // COUNT/SUM see all 5 index rows (1500 bytes), including the ghost — unlike
        // list(), which hides it. The old implementation reported the capped
        // list().size instead of the true total.
        assertEquals(5, summary.count)
        assertEquals(1500L, summary.totalBytes)
        assertEquals(4, repo.list(FileCategory.IMAGES, 100).size)
    }

    @Test
    fun `summaries over an empty index reports zero count and zero bytes`() {
        seed()

        val summary = repo.summaries().first { it.category == FileCategory.VIDEOS }

        // SUM over zero rows is NULL and must not surface as garbage.
        assertEquals(0, summary.count)
        assertEquals(0L, summary.totalBytes)
    }
}
