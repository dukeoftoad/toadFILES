package com.toadfiles.app

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.home.HomeScreen
import com.toadfiles.app.ui.home.HomeViewModel
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Home's recent-feed filter chips: the row renders once the feed has content, a chip
 * tap routes the query through the filtered lane (and shows a filter-aware empty
 * state), and a fresh install with no media renders no dead chip row. Same approach
 * as RecentScreenTest, against the full Home screen with an injected fake media source.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ToadApp::class, sdk = [35])
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private lateinit var vm: HomeViewModel

    /** The two docs the fake media library reports for recent(). */
    private val docs = listOf(
        mediaEntry(1, "notes.txt"),
        mediaEntry(2, "report.pdf"),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(context)
    }

    /** Instant, in-memory media source: recent() hands back [recentResult]; list() is empty. */
    private class FakeMedia(private val recentResult: List<MediaRepository.MediaEntry>) : MediaQuerySource {
        override fun list(category: FileCategory, limit: Int): List<MediaRepository.MediaEntry> = emptyList()
        override fun recent(limit: Int): List<MediaRepository.MediaEntry> = recentResult
        override fun summaries(): List<CategorySummary> = FileCategory.entries.map { CategorySummary(it, 0, 0L) }
        override fun deleteStale(entries: List<MediaRepository.MediaEntry>): Int = 0
    }

    private fun mediaEntry(id: Long, name: String) = MediaRepository.MediaEntry(
        id = id,
        uri = android.net.Uri.parse("content://test/$id"),
        name = name,
        size = 5L,
        dateModified = 0L,
        mime = "text/plain",
        category = FileCategory.DOCS,
        data = null,
    )

    private fun render(media: FakeMedia) {
        vm = HomeViewModel(container, media = media)
        compose.setContent {
            ToadTheme {
                HomeScreen(
                    bottomPadding = Dp(0f),
                    onOpenFolder = {},
                    onOpenStorage = {},
                    onOpenRecent = {},
                    onOpenTrash = {},
                    onOpenSettings = {},
                    vm = vm,
                )
            }
        }
    }

    @Test
    fun `recent filter chips render and route the query through the filtered lane`() {
        render(FakeMedia(docs))
        // The feed settles with the two docs — "All" + every category chip render.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("All").fetchSemanticsNodes().isNotEmpty()
        }

        // Pick Images: the filtered lane comes back empty (the fake has none)…
        compose.onNodeWithText("Images").performClick()
        compose.waitUntil(10_000) { vm.state.value.recentFilter == FileCategory.IMAGES }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No recent images found").fetchSemanticsNodes().isNotEmpty()
        }
        // …the chip row survives the empty result (the way back to All stays put)…
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Videos").fetchSemanticsNodes().isNotEmpty()
        }

        // …and switching back to All restores the feed.
        compose.onNodeWithText("All").performClick()
        compose.waitUntil(10_000) { vm.state.value.recentFilter == null }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `no chip row is rendered for an empty unfiltered feed`() {
        render(FakeMedia(emptyList()))
        // Fresh install: the feed is empty and no filter is active, so the row would be
        // a dead row of chips — it must not render at all.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No recent files reported by the system media library")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("All").fetchSemanticsNodes().isEmpty())
    }
}
