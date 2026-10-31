package com.toadfiles.app.screenshots

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import com.github.takahirom.roborazzi.captureRoboImage
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.ToadNavHost
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** One media row: id, display name, size bytes, modified-seconds, mime, on-disk path, media_type. */
data class MediaRow(
    val id: Long,
    val name: String,
    val size: Long,
    val dateModifiedSecs: Long,
    val mime: String,
    val data: String,
    val mediaType: Int,
)

/**
 * Generates the README screenshots with Roborazzi: the real NavHost and the real app
 * container running on Robolectric, with a seeded filesystem (real files under the
 * Robolectric external-storage root), seeded StatFs numbers, and a fake MediaStore
 * provider so Categories/Recent show honest content instead of empty states.
 *
 * Run with:  ./gradlew :app:recordRoborazziDebug
 * In a plain test run the captures are skipped, so CI stays green and fast.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ToadApp::class, sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class TabScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: File
    private var mediaRowId = 1L
    private val mediaRows = mutableListOf<MediaRow>()
    private val media = FakeMediaProvider()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        ToadViewModelFactory.init(app)

        // Coil's default decoder (ImageDecoder) is not functional under Robolectric —
        // swap the singleton loader for the BitmapFactory-based one so thumbnails
        // decode for real in the captured screenshots. Test-only; devices are unaffected.
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(BitmapFactoryDecoder.Factory()) }
                .build()
        }

        root = Environment.getExternalStorageDirectory()
        root.mkdirs()

        // Honest-looking storage numbers for the Home hero card and the Storage screen.
        runCatching { ShadowStatFs.unregisterStats(root.absolutePath) }
        ShadowStatFs.registerStats(root.absolutePath, 2_000_000_000, 900_000_000, 500_000)

        seedFixtures()
        seedMediaRows()
        media.rowsList = mediaRows.toList()

        // Route every content://media/... query/open to the fake provider.
        ShadowContentResolver.registerProviderInternal("media", media)

        // Image thumbnails: Coil loads these through the resolver, so hand it real bytes.
        val shadowResolver = Shadows.shadowOf(app.contentResolver)
        mediaRows.filter { it.mediaType == 1 }.forEach { r ->
            val f = File(r.data)
            if (f.exists()) {
                shadowResolver.registerInputStream(
                    Uri.parse("content://media/external/images/media/${r.id}"),
                    f.inputStream(),
                )
            }
        }
    }

    // ---------- fixtures ----------

    private fun seedFixtures() {
        val cam = File(root, "DCIM/Camera").apply { mkdirs() }
        val dl = File(root, "Download").apply { mkdirs() }
        val docs = File(root, "Documents").apply { mkdirs() }
        File(root, "Music").mkdirs()
        File(root, "Movies").mkdirs()
        File(root, "Pictures").mkdirs()

        // Real PNG bytes so Browse/Category thumbnails actually decode.
        File(cam, "IMG_20251028_101512.jpg").writeBytes(png(0xFF5B8DEF))
        File(cam, "IMG_20251027_184402.jpg").writeBytes(png(0xFFEFA15B))
        File(cam, "IMG_20251026_093020.jpg").writeBytes(png(0xFF7DD87D))
        File(cam, "VID_20251025_201845.mp4").writeBytes(ByteArray(48_000) { 7 })
        File(dl, "toadfiles-1.5.0.apk").writeBytes(ByteArray(2_400_000) { 9 })
        File(dl, "quarterly-report.pdf").writeBytes(ByteArray(320_000) { 4 })
        File(dl, "holiday-photos.zip").writeBytes(ByteArray(18_000_000) { 5 })
        File(docs, "meeting-notes.md").writeText("# Notes\n- ship v1.5.0\n- screenshots\n")
        File(docs, "budget-2025.xlsx").writeBytes(ByteArray(24_000) { 3 })
        File(File(root, ".hidden-stash").apply { mkdirs() }, "secret.txt").writeText("hidden")

        // Two items sitting in the trash so the Trash screenshot has content.
        val victim1 = File(root, "old-screenshot.png").apply { writeBytes(png(0xFFBBBBBB)) }
        val victim2 = File(dl, "draft-v2.docx").apply { writeBytes(ByteArray(1_000) { 1 }) }
        Trash.trash(listOf(victim1, victim2), listOf(root))
    }

    private fun seedMediaRows() {
        val cam = File(root, "DCIM/Camera").path
        val dl = File(root, "Download").path
        val music = File(root, "Music").path
        val docs = File(root, "Documents").path
        val now = System.currentTimeMillis() / 1000L
        val day = 24L * 60 * 60

        fun row(name: String, size: Long, daysAgo: Long, mime: String, data: String, type: Int) =
            MediaRow(
                id = mediaRowId++,
                name = name,
                size = size,
                dateModifiedSecs = now - daysAgo * day,
                mime = mime,
                data = data,
                mediaType = type,
            )

        mediaRows += row("IMG_20251028_101512.jpg", 3_145_728, 0, "image/jpeg", "$cam/IMG_20251028_101512.jpg", 1)
        mediaRows += row("IMG_20251027_184402.jpg", 2_621_440, 1, "image/jpeg", "$cam/IMG_20251027_184402.jpg", 1)
        mediaRows += row("IMG_20251026_093020.jpg", 4_194_304, 2, "image/jpeg", "$cam/IMG_20251026_093020.jpg", 1)
        mediaRows += row("wallpaper-nord.png", 1_048_576, 4, "image/png", "$cam/IMG_20251026_093020.jpg", 1)
        mediaRows += row("IMG_20251021_161004.jpg", 5_242_880, 6, "image/jpeg", "$cam/IMG_20251027_184402.jpg", 1)
        mediaRows += row("VID_20251025_201845.mp4", 94_371_840, 3, "video/mp4", "$cam/VID_20251025_201845.mp4", 2)
        mediaRows += row("holiday-clip.mp4", 41_943_040, 9, "video/mp4", "$cam/VID_20251025_201845.mp4", 2)
        mediaRows += row("midnight-drive.mp3", 8_388_608, 2, "audio/mpeg", "$music/track.mp3", 3)
        mediaRows += row("podcast-ep-41.mp3", 52_428_800, 5, "audio/mpeg", "$music/track.mp3", 3)
        mediaRows += row("lofi-beats.flac", 29_360_128, 11, "audio/flac", "$music/track.mp3", 3)
        mediaRows += row("quarterly-report.pdf", 327_680, 1, "application/pdf", "$dl/quarterly-report.pdf", 6)
        mediaRows += row("meeting-notes.md", 2_048, 0, "text/markdown", "$docs/meeting-notes.md", 6)
        mediaRows += row("budget-2025.xlsx", 24_576, 7, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "$docs/budget-2025.xlsx", 6)
        mediaRows += row("holiday-photos.zip", 18_874_368, 4, "application/zip", "$dl/holiday-photos.zip", 0)
        mediaRows += row("rom-backup.7z", 71_303_216, 15, "application/x-7z-compressed", "$dl/holiday-photos.zip", 0)
        mediaRows += row("toadfiles-1.5.0.apk", 2_516_582, 0, "application/vnd.android.package-archive", "$dl/toadfiles-1.5.0.apk", 0)
    }

    /** Gradient "photo" PNG so thumbnails decode and look like real pictures. */
    private fun png(argb: Long): ByteArray {
        val img = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val base = java.awt.Color(argb.toInt())
        val dark = base.darker().darker()
        // Diagonal two-tone gradient + a highlight circle: reads as a photo thumbnail
        // at grid size instead of a flat placeholder colour.
        val gp = java.awt.GradientPaint(0f, 0f, base, 96f, 96f, dark)
        g.paint = gp
        g.fillRect(0, 0, 96, 96)
        g.color = java.awt.Color(0x33FFFFFF, true)
        g.fillOval(18, 14, 44, 44)
        g.color = java.awt.Color(0x22000000, true)
        g.fillOval(48, 52, 34, 34)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    // ---------- helpers ----------

    private fun startApp() {
        compose.setContent { ToadTheme { ToadNavHost() } }
        settle()
    }

    /** Let async work (listings, Coil thumbnails) settle before capturing. */
    private fun settle(ms: Long = 2500) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(100)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("roborazzi/$name")
    }

    // ---------- screenshots ----------

    @Test
    fun `01 home tab`() {
        startApp()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("toadFILES").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        shot("01-home.png")
    }

    @Test
    fun `02 browse tab`() {
        startApp()
        compose.onNodeWithTag("tab_Browse").performClick()
        settle()
        shot("02-browse.png")
    }

    @Test
    fun `03 categories grid`() {
        startApp()
        compose.onNodeWithTag("tab_Categories").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Media library").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        shot("03-categories.png")
    }

    @Test
    fun `04 images drill-in grid`() {
        startApp()
        compose.onNodeWithTag("tab_Categories").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Images").performClick()
        settle()
        shot("04-category-images.png")
    }

    @Test
    fun `05 recent screen`() {
        startApp()
        compose.onNodeWithText("See all").performClick()
        settle()
        shot("05-recent.png")
    }

    @Test
    fun `06 storage screen`() {
        startApp()
        compose.onNodeWithText("Internal storage").performClick()
        settle()
        shot("06-storage.png")
    }

    @Test
    fun `07 trash screen`() {
        startApp()
        compose.onNodeWithContentDescription("Trash").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Trash").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        shot("07-trash.png")
    }

    @Test
    fun `08 settings tab`() {
        startApp()
        compose.onNodeWithTag("tab_Settings").performClick()
        settle()
        shot("08-settings.png")
    }
}

/** Minimal MediaStore stand-in: filters rows for the exact selections MediaRepository builds. */
class FakeMediaProvider : ContentProvider() {

    var rowsList: List<MediaRow> = emptyList()

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection
            ?: arrayOf("_id", "_display_name", "_size", "date_modified", "mime_type", "_data", "media_type")
        val filtered = when {
            selection == null -> rowsList
            selection.contains("media_type") ->
                rowsList.filter { selectionArgs?.firstOrNull() == it.mediaType.toString() }
            selection.contains("mime_type IN") ->
                rowsList.filter { it.mime in (selectionArgs ?: emptyArray()) }
            selection.contains("mime_type") ->
                rowsList.filter { selectionArgs?.firstOrNull() == it.mime }
            else -> rowsList
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
                }
            )
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val row = rowsList.firstOrNull { it.id == id } ?: return null
        val f = File(row.data)
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
