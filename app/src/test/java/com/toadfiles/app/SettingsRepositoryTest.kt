package com.toadfiles.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.toadfiles.app.data.SettingsRepository
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.AppFont
import com.toadfiles.app.domain.AppIconColor
import com.toadfiles.app.domain.AppIconDesign
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.domain.SevenZipOpenAction
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.domain.TextScale
import com.toadfiles.app.domain.ZipOpenAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    // ---- v1.11: font picker ----

    @Test
    fun `font defaults to Manrope on first launch - the shipped look`() = runTest {
        assertEquals(AppFont.MANROPE, newRepo().settings.first().appFont)
    }

    @Test
    fun `setAppFont persists the choice and reads it back`() = runTest {
        val repo = newRepo()
        repo.setAppFont(AppFont.TOAD)
        assertEquals(AppFont.TOAD, repo.settings.first().appFont)
        repo.setAppFont(AppFont.SYSTEM)
        assertEquals(AppFont.SYSTEM, repo.settings.first().appFont)
    }

    @Test
    fun `corrupt font value falls back to the default`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit { it[stringPreferencesKey("app_font")] = "NOT_A_FONT" }
        assertEquals(AppFont.DEFAULT, repo.settings.first().appFont)
    }

    @Test
    fun `font choice survives an unrelated write`() = runTest {
        val repo = newRepo()
        repo.setAppFont(AppFont.JETBRAINS_MONO)
        repo.setShowHidden(true)
        repo.setSortMode(SortMode.SIZE)
        assertEquals(AppFont.JETBRAINS_MONO, repo.settings.first().appFont)
    }

    // ---------- v2.4.0: text scale ----------

    @Test
    fun `text scale defaults to STANDARD - the shipped size`() = runTest {
        assertEquals(TextScale.STANDARD, newRepo().settings.first().textScale)
    }

    @Test
    fun `setTextScale persists the choice and reads it back`() = runTest {
        val repo = newRepo()
        repo.setTextScale(TextScale.LARGE)
        assertEquals(TextScale.LARGE, repo.settings.first().textScale)
        repo.setTextScale(TextScale.EXTRA_LARGE)
        assertEquals(TextScale.EXTRA_LARGE, repo.settings.first().textScale)
    }

    @Test
    fun `corrupt text scale value falls back to the default`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit { it[stringPreferencesKey("text_scale")] = "HUMUNGOUS" }
        assertEquals(TextScale.DEFAULT, repo.settings.first().textScale)
    }

    @Test
    fun `text scale choice survives an unrelated write`() = runTest {
        val repo = newRepo()
        repo.setTextScale(TextScale.SMALL)
        repo.setShowHidden(true)
        repo.setSortMode(SortMode.SIZE)
        assertEquals(TextScale.SMALL, repo.settings.first().textScale)
    }

    // ---------- v2.5.0: most-used categories tracker ----------

    @Test
    fun `category use counts accumulate in memory and survive persist-reload`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)

        repo.recordCategoryUse("IMAGES")
        repo.recordCategoryUse("IMAGES")
        repo.recordCategoryUse("VIDEOS")

        // Live counts are visible immediately, without any disk write.
        assertEquals(mapOf("IMAGES" to 2, "VIDEOS" to 1), repo.categoryUses.value)

        // Persist, then a fresh repository on the same store hydrates them back —
        // the exact folder-use persist-reload contract, now for categories.
        repo.persistCategoryUses()
        val repo2 = SettingsRepository(store = store)
        assertEquals(emptyMap<String, Int>(), repo2.categoryUses.value) // not loaded yet
        repo2.recordCategoryUse("ARCHIVES") // triggers the hydrate
        assertEquals(2, repo2.categoryUses.value["IMAGES"])
        assertEquals(1, repo2.categoryUses.value["VIDEOS"])
        assertEquals(1, repo2.categoryUses.value["ARCHIVES"])
    }

    @Test
    fun `category use entries with junk are ignored on read`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit {
            it[stringSetPreferencesKey("category_uses")] = setOf("junk", "|IMAGES", "x|DOCS", "5|APKS")
        }

        repo.recordCategoryUse("AUDIO") // triggers the hydrate

        // The junk rows never surface; the one well-formed row does.
        assertEquals(5, repo.categoryUses.value["APKS"])
        assertNull(repo.categoryUses.value["junk"])
        assertNull(repo.categoryUses.value["IMAGES"])
        assertNull(repo.categoryUses.value["DOCS"])
        assertEquals(1, repo.categoryUses.value["AUDIO"])
    }

    @Test
    fun `blank category names are not recorded`() = runTest {
        val repo = newRepo()
        repo.recordCategoryUse("")
        repo.recordCategoryUse("   ")
        assertEquals(emptyMap<String, Int>(), repo.categoryUses.value)
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

    // ---- v1.7.3: archive actions, nav style, back key, delete-after, archive folder ----

    @Test
    fun `v1 7 3 defaults preserve v1 7 2 behavior`() = runTest {
        val s = newRepo().settings.first()
        assertEquals(ZipOpenAction.MENU, s.zipOpenAction)
        assertEquals(SevenZipOpenAction.MENU, s.sevenZipOpenAction)
        assertEquals(NavStyle.BOTTOM_BAR, s.navStyle)
        assertEquals(BackKeyAction.PARENT, s.backKeyAction)
        assertEquals(false, s.deleteAfterCompress)
        assertEquals(false, s.deleteAfterExtract)
        assertEquals(true, s.abortConfirm)
        assertEquals(true, s.overwriteConfirm)
        assertEquals("", s.archiveFolder) // blank = same folder as source
    }

    @Test
    fun `v1 7 3 setters persist and read back`() = runTest {
        val repo = newRepo()
        repo.setZipOpenAction(ZipOpenAction.OPEN_WITH)
        repo.setSevenZipOpenAction(SevenZipOpenAction.EXTRACT)
        repo.setNavStyle(NavStyle.SIDE_MENU)
        repo.setBackKeyAction(BackKeyAction.EXIT)
        repo.setDeleteAfterCompress(true)
        repo.setDeleteAfterExtract(true)
        repo.setAbortConfirm(false)
        repo.setOverwriteConfirm(false)
        repo.setArchiveFolder("/storage/emulated/0/Archives")

        val s = repo.settings.first()
        assertEquals(ZipOpenAction.OPEN_WITH, s.zipOpenAction)
        assertEquals(SevenZipOpenAction.EXTRACT, s.sevenZipOpenAction)
        assertEquals(NavStyle.SIDE_MENU, s.navStyle)
        assertEquals(BackKeyAction.EXIT, s.backKeyAction)
        assertEquals(true, s.deleteAfterCompress)
        assertEquals(true, s.deleteAfterExtract)
        assertEquals(false, s.abortConfirm)
        assertEquals(false, s.overwriteConfirm)
        assertEquals("/storage/emulated/0/Archives", s.archiveFolder)
    }

    @Test
    fun `corrupt v1 7 3 enum values fall back to defaults`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit {
            it[stringPreferencesKey("zip_open_action")] = "NOT_AN_ACTION"
            it[stringPreferencesKey("sevenzip_open_action")] = "NOT_AN_ACTION"
            it[stringPreferencesKey("nav_style")] = "NOT_A_STYLE"
            it[stringPreferencesKey("back_key_action")] = "NOT_AN_ACTION"
        }
        val s = repo.settings.first()
        assertEquals(ZipOpenAction.MENU, s.zipOpenAction)
        assertEquals(SevenZipOpenAction.MENU, s.sevenZipOpenAction)
        assertEquals(NavStyle.BOTTOM_BAR, s.navStyle)
        assertEquals(BackKeyAction.PARENT, s.backKeyAction)
    }

    @Test
    fun `archive folder trims surrounding whitespace on write`() = runTest {
        val repo = newRepo()
        repo.setArchiveFolder("  /storage/emulated/0/Archives  ")
        assertEquals("/storage/emulated/0/Archives", repo.settings.first().archiveFolder)
    }

    @Test
    fun `blank archive folder round-trips as same-folder-as-source`() = runTest {
        val repo = newRepo()
        repo.setArchiveFolder("/storage/emulated/0/Archives")
        assertEquals("/storage/emulated/0/Archives", repo.settings.first().archiveFolder)

        // Clearing the preference restores the v1.7.2 "same folder as source" default.
        repo.setArchiveFolder("")
        assertEquals("", repo.settings.first().archiveFolder)
        repo.setArchiveFolder("   ") // whitespace-only must not resurrect a path
        assertEquals("", repo.settings.first().archiveFolder)
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

    // ---- P2.9: remap, record and persist are serialized on one mutex ----

    /**
     * Runs a suspend block on a daemon thread so a race test can park it on a latch
     * without ever keeping the test JVM alive after a failure.
     */
    private fun raceThread(name: String, block: suspend () -> Unit): Thread =
        Thread({ runBlocking { block() } }, name).apply { isDaemon = true }

    /**
     * Barrier test (audit P2.9): a flush parks inside its critical section — snapshot
     * taken, write not yet committed — and only then does a rename start. The one mutex
     * must hold the remap back until the flush commits, so the remapped set is written
     * last and no fresh session can resurrect the pre-remap path from disk.
     * Latches only — no sleeps.
     */
    @Test
    fun `persist parked mid-flush cannot land stale paths after a racing remap`() = runTest {
        val store = newStore()
        store.edit { it[stringSetPreferencesKey("folder_uses")] = setOf("4|/vol/Old") }
        val repo = SettingsRepository(store = store)
        repo.reloadFolderUses()

        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        repo.onUsesPersistSnapshotted = {
            parked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val persisting = raceThread("p2-9-persist") { repo.persistFolderUses() }.apply { start() }
        try {
            assertTrue("persist never reached its snapshot", parked.await(10, TimeUnit.SECONDS))

            // The rename starts while the flush is parked inside its critical section.
            val remapping = raceThread("p2-9-remap") { repo.remapSavedPaths("/vol/Old", "/vol/New") }
                .apply { start() }

            release.countDown()
            persisting.join(TimeUnit.SECONDS.toMillis(10))
            remapping.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("persist did not finish", persisting.isAlive)
            assertFalse("remap did not finish", remapping.isAlive)

            // The live map ends up remapped…
            assertEquals(mapOf("/vol/New" to 4), repo.folderUses.value)

            // …and so does the persisted set: a fresh repository must not resurrect /vol/Old.
            val repo2 = SettingsRepository(store = store)
            repo2.recordFolderUse("/warmup") // triggers the hydrate
            assertEquals(4, repo2.folderUses.value["/vol/New"])
            assertNull(repo2.folderUses.value["/vol/Old"])
        } finally {
            release.countDown() // never leave a parked flush hanging
            repo.onUsesPersistSnapshotted = null
        }
    }

    /**
     * Same interleaving as above but with the flush hydrating inside the race (app
     * stopped before the initial read): the hydrate, the snapshot and the write must
     * stay one critical section, so the remap can only land after the full flush.
     */
    @Test
    fun `persist that hydrates mid-race with a remap lands remapped paths everywhere`() = runTest {
        val store = newStore()
        store.edit { it[stringSetPreferencesKey("folder_uses")] = setOf("4|/vol/Old") }
        val repo = SettingsRepository(store = store) // not hydrated yet

        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        repo.onUsesPersistSnapshotted = {
            parked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val persisting = raceThread("p2-9-persist") { repo.persistFolderUses() }.apply { start() }
        try {
            assertTrue("persist never reached its snapshot", parked.await(10, TimeUnit.SECONDS))
            // The flush hydrated first: the previous session's history is in memory.
            assertEquals(mapOf("/vol/Old" to 4), repo.folderUses.value)

            val remapping = raceThread("p2-9-remap") { repo.remapSavedPaths("/vol/Old", "/vol/New") }
                .apply { start() }

            release.countDown()
            persisting.join(TimeUnit.SECONDS.toMillis(10))
            remapping.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("persist did not finish", persisting.isAlive)
            assertFalse("remap did not finish", remapping.isAlive)

            assertEquals(mapOf("/vol/New" to 4), repo.folderUses.value)

            val repo2 = SettingsRepository(store = store)
            repo2.recordFolderUse("/warmup") // triggers the hydrate
            assertEquals(4, repo2.folderUses.value["/vol/New"])
            assertNull(repo2.folderUses.value["/vol/Old"])
        } finally {
            release.countDown() // never leave a parked flush hanging
            repo.onUsesPersistSnapshotted = null
        }
    }

    /**
     * Barrier test (audit P2.9, the audit's exact window): the remap parks right after
     * the persisted set was rewritten but before the live map follows, and a flush is
     * started inside that window. The flush must wait for the remap's mutex section to
     * finish and then persist the REMAPPED in-memory paths — writing the old map here
     * would undo the path rewrite on disk until the next remap.
     */
    @Test
    fun `persist started while a remap is mid-write lands remapped paths everywhere`() = runTest {
        val store = newStore()
        store.edit { it[stringSetPreferencesKey("folder_uses")] = setOf("4|/vol/Old") }
        val repo = SettingsRepository(store = store)
        repo.reloadFolderUses()

        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        repo.onUsesRemapCommitted = {
            parked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val remapping = raceThread("p2-9-remap") { repo.remapSavedPaths("/vol/Old", "/vol/New") }
            .apply { start() }
        try {
            assertTrue("remap never committed its persisted rewrite", parked.await(10, TimeUnit.SECONDS))
            // Parked in the exact window: disk already remapped…
            assertEquals(
                setOf("4|/vol/New"),
                store.data.first()[stringSetPreferencesKey("folder_uses")],
            )
            // …while the live map still carries the old paths.
            assertEquals(mapOf("/vol/Old" to 4), repo.folderUses.value)

            // The flush starts before the remap finishes.
            val persisting = raceThread("p2-9-persist") { repo.persistFolderUses() }.apply { start() }

            release.countDown()
            remapping.join(TimeUnit.SECONDS.toMillis(10))
            persisting.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("remap did not finish", remapping.isAlive)
            assertFalse("persist did not finish", persisting.isAlive)

            assertEquals(mapOf("/vol/New" to 4), repo.folderUses.value)

            val repo2 = SettingsRepository(store = store)
            repo2.recordFolderUse("/warmup") // triggers the hydrate
            assertEquals(4, repo2.folderUses.value["/vol/New"])
            assertNull(repo2.folderUses.value["/vol/Old"])
        } finally {
            release.countDown() // never leave a parked remap hanging
            repo.onUsesRemapCommitted = null
        }
    }

    /** Recording racing a remap must neither lose the fresh count nor keep a stale path. */
    @Test
    fun `recordFolderUse racing remapSavedPaths keeps every count exactly once`() = runTest {
        val store = newStore()
        store.edit { it[stringSetPreferencesKey("folder_uses")] = setOf("3|/vol/A") }
        val repo = SettingsRepository(store = store)
        repo.reloadFolderUses()

        kotlinx.coroutines.coroutineScope {
            launch { repo.recordFolderUse("/vol/B") }
            launch { repo.remapSavedPaths("/vol/A", "/vol/A2") }
        }

        assertEquals(mapOf("/vol/A2" to 3, "/vol/B" to 1), repo.folderUses.value)
    }

    // ---- v1.9: app icon ----

    @Test
    fun `app icon defaults to TOAD and GREEN on first launch`() = runTest {
        val s = newRepo().settings.first()
        // v1.11 redesign: the toad mark is the shipped icon.
        assertEquals(AppIconDesign.TOAD, s.appIconDesign)
        assertEquals(AppIconColor.GREEN, s.appIconColor)
    }

    @Test
    fun `setAppIconDesign and setAppIconColor round-trip over all entries`() = runTest {
        val repo = newRepo()
        AppIconDesign.entries.forEach { d ->
            repo.setAppIconDesign(d)
            assertEquals(d, repo.settings.first().appIconDesign)
        }
        AppIconColor.entries.forEach { c ->
            repo.setAppIconColor(c)
            assertEquals(c, repo.settings.first().appIconColor)
        }
    }

    @Test
    fun `corrupt app icon values fall back to TOAD and GREEN`() = runTest {
        val store = newStore()
        val repo = SettingsRepository(store = store)
        store.edit {
            it[stringPreferencesKey("app_icon_design")] = "NOPE"
            it[stringPreferencesKey("app_icon_color")] = "ALSO_NOT_REAL"
        }
        val s = repo.settings.first()
        assertEquals(AppIconDesign.TOAD, s.appIconDesign)
        assertEquals(AppIconColor.GREEN, s.appIconColor)
    }

    @Test
    fun `performance mode defaults off and persists`() = runTest {
        val repo = newRepo()
        assertEquals(false, repo.settings.first().performanceMode)

        repo.setPerformanceMode(true)
        assertEquals(true, repo.settings.first().performanceMode)

        // A corrupt value can't exist for a boolean key; an absent key falls back to off.
        repo.setPerformanceMode(false)
        assertEquals(false, repo.settings.first().performanceMode)
    }
}
