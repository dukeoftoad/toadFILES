package com.toadfiles.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.toadfiles.app.data.SettingsRepository
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `search limit defaults to 500 and clamps to the legal range`() = runTest {
        val repo = newRepo()
        assertEquals(ToadSettings.SEARCH_LIMIT_DEFAULT, repo.settings.first().searchResultLimit)

        repo.setSearchResultLimit(5) // below SEARCH_LIMIT_MIN (10)
        assertEquals(ToadSettings.SEARCH_LIMIT_MIN, repo.settings.first().searchResultLimit)

        repo.setSearchResultLimit(99_999) // above SEARCH_LIMIT_MAX (2000)
        assertEquals(ToadSettings.SEARCH_LIMIT_MAX, repo.settings.first().searchResultLimit)

        repo.setSearchResultLimit(250)
        assertEquals(250, repo.settings.first().searchResultLimit)
    }

    @Test
    fun `content search defaults off and toggles cleanly`() = runTest {
        val repo = newRepo()
        assertEquals(false, repo.settings.first().contentSearch)
        repo.setContentSearch(true)
        assertEquals(true, repo.settings.first().contentSearch)
        repo.setContentSearch(false)
        assertEquals(false, repo.settings.first().contentSearch)
    }

    @Test
    fun `corrupt search limit value falls back to the default`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit {
            it[androidx.datastore.preferences.core.intPreferencesKey("search_limit")] = -7
        }
        // The clamping in the reader should bring -7 back to SEARCH_LIMIT_MIN (50).
        assertEquals(ToadSettings.SEARCH_LIMIT_MIN, repo.settings.first().searchResultLimit)
    }

    // ---- v1.4: accent color ----

    @Test
    fun `accent color defaults to TOAD on first launch`() = runTest {
        val s = newRepo().settings.first()
        assertEquals(AccentColor.TOAD, s.accentColor)
    }

    @Test
    fun `setAccentColor persists the choice and reads it back`() = runTest {
        val repo = newRepo()
        AccentColor.entries.forEach { accent ->
            repo.setAccentColor(accent)
            assertEquals(accent, repo.settings.first().accentColor)
        }
    }

    @Test
    fun `corrupt accent value falls back to TOAD`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit { it[stringPreferencesKey("accent_color")] = "NEVER_WAS_AN_ACCENT" }
        assertEquals(AccentColor.TOAD, repo.settings.first().accentColor)
    }

    @Test
    fun `accent color survives an unrelated write`() = runTest {
        // Changing another setting must not silently reset the accent.
        val repo = newRepo()
        repo.setAccentColor(AccentColor.PURPLE)
        repo.setShowHidden(true)
        repo.setSortMode(SortMode.SIZE)
        assertEquals(AccentColor.PURPLE, repo.settings.first().accentColor)
    }

    // ---- v1.4: diagnostics toggle ----

    @Test
    fun `diagnostics defaults off`() = runTest {
        assertEquals(false, newRepo().settings.first().diagnosticsEnabled)
    }

    @Test
    fun `setDiagnosticsEnabled persists and reads back`() = runTest {
        val repo = newRepo()
        repo.setDiagnosticsEnabled(true)
        assertEquals(true, repo.settings.first().diagnosticsEnabled)
        repo.setDiagnosticsEnabled(false)
        assertEquals(false, repo.settings.first().diagnosticsEnabled)
    }

    @Test
    fun `diagnostics toggle survives an unrelated write`() = runTest {
        val repo = newRepo()
        repo.setDiagnosticsEnabled(true)
        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(true, repo.settings.first().diagnosticsEnabled)
    }

    @Test
    fun `quick pins toggle and always unhide`() = runTest {
        val repo = newRepo()
        val path = "/storage/emulated/0/Movies"

        repo.toggleQuickPin(path)
        assertEquals(setOf(path), repo.settings.first().quickPins)

        // Hiding removes the pin; re-pinning clears the hidden mark.
        repo.hideFromQuickAccess(path)
        assertTrue(repo.settings.first().quickPins.isEmpty())
        assertEquals(setOf(path), repo.settings.first().quickHidden)

        repo.toggleQuickPin(path)
        assertEquals(setOf(path), repo.settings.first().quickPins)
        assertTrue(repo.settings.first().quickHidden.isEmpty())
    }

    @Test
    fun `folder use counts accumulate in memory and survive persist-reload`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        val a = "/storage/emulated/0/Download"
        val b = "/storage/emulated/0/DCIM"

        repo.recordFolderUse(a)
        repo.recordFolderUse(a)
        repo.recordFolderUse(b)

        // Live counts are visible immediately, without any disk write.
        assertEquals(mapOf(a to 2, b to 1), repo.folderUses.value)

        // Persist, then a fresh repository on the same store hydrates them back.
        repo.persistFolderUses()
        val repo2 = SettingsRepository(store = store)
        assertEquals(emptyMap<String, Int>(), repo2.folderUses.value) // not loaded yet
        repo2.recordFolderUse("/warmup") // triggers the hydrate
        assertEquals(2, repo2.folderUses.value[a])
        assertEquals(1, repo2.folderUses.value[b])
        assertEquals(1, repo2.folderUses.value["/warmup"])
    }

    @Test
    fun `folder use entries with junk are ignored on read`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit {
            it[stringSetPreferencesKey("folder_uses")] = setOf(
                "3|/storage/emulated/0/ok",
                "not-a-count|/x",
                "5|",
                "missing-separator",
            )
        }
        // Recording triggers the hydrate from disk; junk lines must be skipped.
        repo.recordFolderUse("/storage/emulated/0/warm")
        assertEquals(
            mapOf("/storage/emulated/0/ok" to 3, "/storage/emulated/0/warm" to 1),
            repo.folderUses.value,
        )
    }

    // ---- P2.7: hydration must be serialized against persistence ----

    @Test
    fun `persistFolderUses hydrates first so an early stop cannot erase saved counts`() = runTest {
        val store = newStore()
        // A previous session saved its history.
        store.edit {
            it[stringSetPreferencesKey("folder_uses")] = setOf("7|/storage/emulated/0/Download")
        }
        val repo = SettingsRepository(store = store)
        // App is stopped before the initial read completed: persisting the still-empty
        // in-memory map would wipe the history. The repository must hydrate first.
        repo.persistFolderUses()

        val repo2 = SettingsRepository(store = store)
        assertEquals(emptyMap<String, Int>(), repo2.folderUses.value) // not loaded yet
        repo2.recordFolderUse("/warmup") // triggers the hydrate
        assertEquals(7, repo2.folderUses.value["/storage/emulated/0/Download"])
        assertEquals(1, repo2.folderUses.value["/warmup"])
    }

    @Test
    fun `concurrent first loads hydrate exactly once and keep every count`() = runTest {
        val store = newStore()
        store.edit {
            it[stringSetPreferencesKey("folder_uses")] = setOf("3|/storage/emulated/0/A")
        }
        val repo = SettingsRepository(store = store)
        // Startup races: HomeViewModel reloads, a Browse visit records a use, and
        // MainActivity.onStop persists — all before the first read finishes.
        kotlinx.coroutines.coroutineScope {
            launch { repo.reloadFolderUses() }
            launch { repo.reloadFolderUses() }
            launch { repo.recordFolderUse("/storage/emulated/0/B") }
        }
        assertEquals(3, repo.folderUses.value["/storage/emulated/0/A"])
        assertEquals(1, repo.folderUses.value["/storage/emulated/0/B"])
    }

    // ---- P2.8: one subtree remap for favorites + quick access + usage counters ----

    @Test
    fun `remapSavedPaths rewrites favorites pins hidden marks and usage counters`() = runTest {
        val repo = newRepo()
        val old = "/storage/emulated/0/Projects"
        repo.toggleFavorite("$old/notes.txt")            // favorite descendant
        repo.toggleQuickPin(old)                         // exact pin
        repo.toggleQuickPin("/storage/emulated/0/Other") // untouched pin
        repo.hideFromQuickAccess("$old/secret")          // hidden descendant
        repo.recordFolderUse(old)                        // usage counter, exact
        repo.recordFolderUse("$old/sub")                 // usage counter, descendant
        repo.recordFolderUse("/storage/emulated/0/Download") // untouched counter

        repo.remapSavedPaths(old, "/storage/emulated/0/Work")

        val s = repo.settings.first()
        assertEquals(setOf("/storage/emulated/0/Work/notes.txt"), s.favorites)
        assertEquals(setOf("/storage/emulated/0/Work", "/storage/emulated/0/Other"), s.quickPins)
        assertEquals(setOf("/storage/emulated/0/Work/secret"), s.quickHidden)

        // Live counters are remapped with their counts intact.
        assertEquals(1, repo.folderUses.value["/storage/emulated/0/Work"])
        assertEquals(1, repo.folderUses.value["/storage/emulated/0/Work/sub"])
        assertEquals(1, repo.folderUses.value["/storage/emulated/0/Download"])
        assertNull(repo.folderUses.value[old])
        assertNull(repo.folderUses.value["$old/sub"])
    }

    @Test
    fun `remapSavedPaths rewrites persisted folder uses on disk`() = runTest {
        val store = newStore()
        store.edit {
            it[stringSetPreferencesKey("folder_uses")] = setOf(
                "4|/vol/Old",
                "2|/vol/Old/nested",
                "9|/vol/Keep",
            )
        }
        val repo = SettingsRepository(store = store)
        repo.remapSavedPaths("/vol/Old", "/vol/New")

        // A fresh repository hydrates the rewritten disk state, not the old paths.
        val repo2 = SettingsRepository(store = store)
        repo2.recordFolderUse("/warmup")
        assertEquals(4, repo2.folderUses.value["/vol/New"])
        assertEquals(2, repo2.folderUses.value["/vol/New/nested"])
        assertEquals(9, repo2.folderUses.value["/vol/Keep"])
        assertNull(repo2.folderUses.value["/vol/Old"])
    }

    @Test
    fun `remapSavedPaths flushes remapped live counts for the next session`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        repo.recordFolderUse("/vol/Old")
        repo.remapSavedPaths("/vol/Old", "/vol/New")
        repo.persistFolderUses()

        val repo2 = SettingsRepository(store = store)
        repo2.recordFolderUse("/warmup")
        assertEquals(1, repo2.folderUses.value["/vol/New"])
        assertNull(repo2.folderUses.value["/vol/Old"])
    }

    @Test
    fun `remapSavedPaths is a no-op when nothing matches`() = runTest {
        val repo = newRepo()
        repo.toggleFavorite("/vol/keep.txt")
        repo.toggleQuickPin("/vol/keep-folder")

        repo.remapSavedPaths("/somewhere-else", "/new-place")

        val s = repo.settings.first()
        assertEquals(setOf("/vol/keep.txt"), s.favorites)
        assertEquals(setOf("/vol/keep-folder"), s.quickPins)
        assertTrue(s.quickHidden.isEmpty())
    }
}
