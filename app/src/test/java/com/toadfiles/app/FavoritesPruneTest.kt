package com.toadfiles.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.toadfiles.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Favorites staleness: paths that no longer exist on disk (file renamed / moved / deleted
 * outside toadFILES, or volume unmounted) must be pruned from the persisted favorites set
 * so it does not silently accumulate dead entries.
 */
class FavoritesPruneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        repo = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "${System.nanoTime()}.preferences_pb")
            },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `pruneDeadFavorites drops entries whose files no longer exist`() = runTest {
        val alive = File(tmp.root, "alive.txt").apply { writeText("x") }
        val dead = File(tmp.root, "gone.txt")
        repo.toggleFavorite(alive.path)
        repo.toggleFavorite(dead.path) // never created on disk

        val removed = repo.pruneDeadFavorites()

        assertEquals(1, removed)
        val favs = repo.settings.first().favorites
        assertEquals(setOf(alive.path), favs)
    }

    @Test
    fun `pruneDeadFavorites is idempotent`() = runTest {
        val alive = File(tmp.root, "alive.txt").apply { writeText("x") }
        val dead = File(tmp.root, "gone.txt")
        repo.toggleFavorite(alive.path)
        repo.toggleFavorite(dead.path)

        val first = repo.pruneDeadFavorites()
        val second = repo.pruneDeadFavorites()

        assertEquals(1, first)
        assertEquals(0, second) // nothing left to prune
        assertEquals(setOf(alive.path), repo.settings.first().favorites)
    }

    @Test
    fun `pruneDeadFavorites is a no-op on empty favorites`() = runTest {
        assertEquals(0, repo.pruneDeadFavorites())
        assertTrue(repo.settings.first().favorites.isEmpty())
    }

    @Test
    fun `pruneDeadFavorites keeps favorites whose files exist`() = runTest {
        val a = File(tmp.root, "a.txt").apply { writeText("x") }
        val b = File(tmp.root, "b.txt").apply { writeText("y") }
        val c = File(tmp.root, "c.txt").apply { writeText("z") }
        listOf(a, b, c).forEach { repo.toggleFavorite(it.path) }

        assertEquals(0, repo.pruneDeadFavorites())
        assertEquals(setOf(a.path, b.path, c.path), repo.settings.first().favorites)
    }

    @Test
    fun `pruneDeadFavorites handles directories too`() = runTest {
        val liveDir = File(tmp.root, "live-dir").apply { mkdirs() }
        val deadDir = File(tmp.root, "dead-dir")
        repo.toggleFavorite(liveDir.path)
        repo.toggleFavorite(deadDir.path)

        assertEquals(1, repo.pruneDeadFavorites())
        assertEquals(setOf(liveDir.path), repo.settings.first().favorites)
    }
}
