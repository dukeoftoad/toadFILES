package com.toadfiles.app

import android.os.Environment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import com.toadfiles.app.ToadApp
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.screenshots.FakeMediaProvider
import com.toadfiles.app.screenshots.MediaRow
import com.toadfiles.app.ui.categories.CategoriesScreen
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * P2.4 regression + v1.7.3 parity: the Categories drill-in renders the SAME shared rows
 * and grid cells Browse uses (FileRow / FileGridCell), and each exposes a reachable
 * per-item action sheet via its ⋮ affordance. Long-press is reserved for multi-select,
 * so rows (docs/audio/archives/apks) and grid cells (images/videos) each render the
 * trigger. The sheet is the shared FileActionSheet wiring, so a zip row must offer the
 * Browse-parity actions (Extract here, Extract to…, Rename, Zip, Properties).
 */
@RunWith(AndroidJUnit4::class)
// qualifiers: a tall virtual screen so every category tile in the LazyVerticalGrid
// (Archives sits below the fold on Robolectric's default 320x470) is composed.
@Config(application = ToadApp::class, sdk = [35], qualifiers = "w411dp-h1200dp")
class CategoriesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val media = FakeMediaProvider()
    private val rows = mutableListOf<MediaRow>()
    private var rowId = 1L

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        ToadViewModelFactory.init(app)

        // Coil's default decoder is not functional under Robolectric — same swap the
        // screenshot test uses so thumbnails load without crashing the composition.
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(BitmapFactoryDecoder.Factory()) }
                .build()
        }

        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        val docs = File(root, "Documents").apply { mkdirs() }
        val cam = File(root, "DCIM/Camera").apply { mkdirs() }
        val arch = File(root, "Download").apply { mkdirs() }
        val pdf = File(docs, "quarterly-report.pdf").apply { writeBytes(ByteArray(4_096) { 3 }) }
        val jpg = File(cam, "IMG_1.jpg").apply { writeBytes(png()) }
        val zip = File(arch, "bundle.zip").apply {
            // A real (tiny) zip so ArchiveOps.canExtract/entryCount behave honestly.
            java.util.zip.ZipOutputStream(outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("inner.txt"))
                zos.write("zipbody".toByteArray())
                zos.closeEntry()
            }
        }

        val now = System.currentTimeMillis() / 1000L
        rows += MediaRow(rowId++, pdf.name, pdf.length(), now, "application/pdf", pdf.path, 6)
        rows += MediaRow(rowId++, jpg.name, jpg.length(), now - 60, "image/jpeg", jpg.path, 1)
        rows += MediaRow(rowId++, zip.name, zip.length(), now - 120, "application/zip", zip.path, 0)
        media.rowsList = rows.toList()
        ShadowContentResolver.registerProviderInternal("media", media)
    }

    private fun render() {
        compose.setContent {
            ToadTheme {
                CategoriesScreen(bottomPadding = Dp(0f), onBack = {}, onShowInFolder = {})
            }
        }
    }

    private fun openDrillIn(tileLabel: String) {
        render()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Media library").fetchSemanticsNodes().isNotEmpty()
        }
        // The grid tiles land asynchronously (summaries query) — never race the click.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(tileLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(tileLabel).performClick()
    }

    @Test
    fun `document rows expose the per-item action sheet`() {
        openDrillIn("Documents")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(
                "quarterly-report.pdf",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // The row's ⋮ affordance is rendered and opens the action sheet.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("More actions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("More actions")[0].performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Open with…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Properties").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `image grid cells expose the per-item action sheet`() {
        openDrillIn("Images")
        // The shared FileGridCell renders the file name (content description = full
        // name) under the thumbnail.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(
                "IMG_1.jpg",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // The grid cell's ⋮ affordance is rendered and opens the action sheet.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("More actions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("More actions")[0].performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Open with…").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `archive rows offer the Browse-parity actions in the shared sheet`() {
        openDrillIn("Archives")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(
                "bundle.zip",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("More actions")[0].performClick()

        // Extract (here + to…), Rename, Move to trash and Properties — the exact rows
        // Browse renders for the same file, now shared.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Extract here").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Extract to…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Rename").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Properties").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Move to trash").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `drill-in list starts right under the top bar, not a bar-height lower`() {
        openDrillIn("Documents")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(
                "quarterly-report.pdf",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // The Scaffold insets are consumed exactly once (by the PullToRefreshBox).
        // The old bug applied them a second time on the entries, pushing the whole
        // drill-in a full TopAppBar height down: the first row sat ~144dp from the
        // top instead of ~80dp, leaving a dead band under the bar. 110dp separates
        // the two states with margin on both sides, in any density.
        val firstRowTopPx = compose.onNodeWithContentDescription(
            "quarterly-report.pdf",
            useUnmergedTree = true,
        )
            .fetchSemanticsNode().positionInRoot.y
        val limitPx = with(compose.density) { 110.dp.toPx() }
        assert(firstRowTopPx < limitPx) {
            "first row top is $firstRowTopPx px but must be under $limitPx px — " +
                "the drill-in looks double-padded again"
        }
    }

    /** Tiny real PNG so the grid cell's AsyncImage has decodable content. */
    private fun png(): ByteArray {
        val img = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.fillRect(0, 0, 32, 32)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
