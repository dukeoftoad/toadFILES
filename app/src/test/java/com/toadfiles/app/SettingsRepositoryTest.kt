package com.toadfiles.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.toadfiles.app.data.SettingsRepository
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SettingsRepository against a real DataStore file — a fresh, isolated store per test so
 * no state can leak between tests. Corrupt stored values must fall back to safe defaults.
 */
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newRepo(): SettingsRepository = SettingsRepository(store = newStore())

    private fun newStore() = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmp.root, "${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults are sensible on first launch`() = runTest {
        val s = newRepo().settings.first()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertTrue(s.dynamicColor)
        assertEquals(false, s.showHidden)
        assertEquals(SortMode.NAME, s.sortMode)
        assertEquals(SortOrder.ASC, s.sortOrder)
        assertEquals(BrowseLayout.LIST, s.browseLayout)
        assertTrue(s.favorites.isEmpty())
    }

    @Test
    fun `setters persist and read back`() = runTest {
        val repo = newRepo()
        repo.setThemeMode(ThemeMode.DARK)
        repo.setDynamicColor(false)
        repo.setShowHidden(true)
        repo.setSortMode(SortMode.SIZE)
        repo.setSortOrder(SortOrder.DESC)
        repo.setBrowseLayout(BrowseLayout.GRID)

        val s = repo.settings.first()
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals(false, s.dynamicColor)
        assertEquals(true, s.showHidden)
        assertEquals(SortMode.SIZE, s.sortMode)
        assertEquals(SortOrder.DESC, s.sortOrder)
        assertEquals(BrowseLayout.GRID, s.browseLayout)
    }

    @Test
    fun `toggleFavorite adds then removes`() = runTest {
        val repo = newRepo()
        val path = "/storage/emulated/0/a.txt"
        repo.toggleFavorite(path)
        assertTrue(path in repo.settings.first().favorites)
        repo.toggleFavorite(path)
        assertTrue(path !in repo.settings.first().favorites)
    }

    @Test
    fun `toggleFavorite keeps other favorites intact`() = runTest {
        val repo = newRepo()
        val a = "/storage/emulated/0/a.txt"
        val b = "/storage/emulated/0/b.txt"
        repo.toggleFavorite(a)
        repo.toggleFavorite(b)
        repo.toggleFavorite(a) // remove a
        assertEquals(setOf(b), repo.settings.first().favorites)
    }

    @Test
    fun `remapFavorites rewrites exact path and whole subtree`() = runTest {
        val repo = newRepo()
        val exact = "/vol/old.txt"
        val inside = "/vol/oldfolder/nested/deep.txt"
        val other = "/vol/other.txt"
        repo.toggleFavorite(exact)
        repo.toggleFavorite(inside)
        repo.toggleFavorite(other)

        repo.remapFavorites("/vol/oldfolder", "/vol/newfolder")

        val favs = repo.settings.first().favorites
        assertTrue(exact in favs)                            // untouched — different item
        assertTrue(other in favs)                            // untouched — sibling, not inside
        assertTrue("/vol/newfolder/nested/deep.txt" in favs) // subtree rewritten
        assertEquals(3, favs.size)
    }

    @Test
    fun `remapFavorites handles exact rename of a favorited file`() = runTest {
        val repo = newRepo()
        val old = "/vol/report.pdf"
        repo.toggleFavorite(old)
        repo.remapFavorites(old, "/vol/report-renamed.pdf")
        assertEquals(setOf("/vol/report-renamed.pdf"), repo.settings.first().favorites)
    }

    @Test
    fun `remapFavorites is a no-op when nothing matches`() = runTest {
        val repo = newRepo()
        val keep = "/vol/keep.txt"
        repo.toggleFavorite(keep)
        repo.remapFavorites("/somewhere-else", "/new-place")
        assertEquals(setOf(keep), repo.settings.first().favorites)
    }

    @Test
    fun `corrupt enum values fall back to defaults`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        // Inject a value that no longer maps to a ThemeMode:
        store.edit {
            it[stringPreferencesKey("theme_mode")] = "NOT_A_REAL_MODE"
        }
        assertEquals(ThemeMode.SYSTEM, repo.settings.first().themeMode)
    }
}
