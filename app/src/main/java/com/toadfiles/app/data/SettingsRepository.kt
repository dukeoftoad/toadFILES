package com.toadfiles.app.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ToadSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Accent chosen by the user when dynamic color is OFF. Ignored when dynamicColor is on. */
    val accentColor: AccentColor = AccentColor.TOAD,
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val browseLayout: BrowseLayout = BrowseLayout.LIST,
    val favorites: Set<String> = emptySet(),
    /** Cap applied to in-folder tree search. Bounded to [SEARCH_LIMIT_MIN]..[SEARCH_LIMIT_MAX]. */
    val searchResultLimit: Int = SEARCH_LIMIT_DEFAULT,
    /** When true, in-folder tree search also opens small text files and matches their contents. */
    val contentSearch: Boolean = false,
    /** Folders the user explicitly pinned to Home's Quick access. */
    val quickPins: Set<String> = emptySet(),
    /** Folders the user removed from Quick access (defaults or auto "most used" entries). */
    val quickHidden: Set<String> = emptySet(),
) {
    companion object {
        const val SEARCH_LIMIT_MIN = 10
        const val SEARCH_LIMIT_DEFAULT = 500
        const val SEARCH_LIMIT_MAX = 2000
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "toadfiles_settings")

/** All preferences persisted locally with Jetpack DataStore. Nothing ever leaves the device. */
class SettingsRepository(
    /** Injectable for tests; production uses the app-wide singleton DataStore. */
    private val store: DataStore<Preferences>,
) {
    companion object {
        /** Upper bound on how many distinct folders the "most used" tracker remembers. */
        const val MAX_TRACKED_FOLDERS = 100
    }

    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val ACCENT = stringPreferencesKey("accent_color")
        val HIDDEN = booleanPreferencesKey("show_hidden")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val LAYOUT = stringPreferencesKey("browse_layout")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val SEARCH_LIMIT = intPreferencesKey("search_limit")
        val CONTENT_SEARCH = booleanPreferencesKey("content_search")
        val QUICK_PINS = stringSetPreferencesKey("quick_pins")
        val QUICK_HIDDEN = stringSetPreferencesKey("quick_hidden")
        val FOLDER_USES = stringSetPreferencesKey("folder_uses")
    }

    val settings: Flow<ToadSettings> = store.data.map { p ->
        ToadSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            accentColor = p[Keys.ACCENT]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
                ?: AccentColor.TOAD,
            showHidden = p[Keys.HIDDEN] ?: false,
            sortMode = p[Keys.SORT_MODE]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() }
                ?: SortMode.NAME,
            sortOrder = p[Keys.SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.ASC,
            browseLayout = p[Keys.LAYOUT]?.let { runCatching { BrowseLayout.valueOf(it) }.getOrNull() }
                ?: BrowseLayout.LIST,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            searchResultLimit = p[Keys.SEARCH_LIMIT]?.coerceIn(
                ToadSettings.SEARCH_LIMIT_MIN,
                ToadSettings.SEARCH_LIMIT_MAX,
            ) ?: ToadSettings.SEARCH_LIMIT_DEFAULT,
            contentSearch = p[Keys.CONTENT_SEARCH] ?: false,
            quickPins = p[Keys.QUICK_PINS] ?: emptySet(),
            quickHidden = p[Keys.QUICK_HIDDEN] ?: emptySet(),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setAccentColor(accent: AccentColor) = store.edit { it[Keys.ACCENT] = accent.name }
    suspend fun setShowHidden(enabled: Boolean) = store.edit { it[Keys.HIDDEN] = enabled }
    suspend fun setSortMode(mode: SortMode) = store.edit { it[Keys.SORT_MODE] = mode.name }
    suspend fun setSortOrder(order: SortOrder) = store.edit { it[Keys.SORT_ORDER] = order.name }
    suspend fun setBrowseLayout(layout: BrowseLayout) = store.edit { it[Keys.LAYOUT] = layout.name }
    suspend fun setSearchResultLimit(limit: Int) = store.edit {
        it[Keys.SEARCH_LIMIT] = limit.coerceIn(ToadSettings.SEARCH_LIMIT_MIN, ToadSettings.SEARCH_LIMIT_MAX)
    }
    suspend fun setContentSearch(enabled: Boolean) = store.edit { it[Keys.CONTENT_SEARCH] = enabled }

    // ---------- Quick access (Home) ----------

    /** Pins or unpins a folder for Home's Quick access row. */
    suspend fun toggleQuickPin(path: String) = store.edit { p ->
        val cur = p[Keys.QUICK_PINS] ?: emptySet()
        p[Keys.QUICK_PINS] = if (path in cur) cur - path else cur + path
        // (Re)pinning always makes the folder visible again.
        p[Keys.QUICK_HIDDEN] = (p[Keys.QUICK_HIDDEN] ?: emptySet()) - path
    }

    /** Removes a folder from Quick access: unpins it and hides it so defaults/auto entries never resurface it. */
    suspend fun hideFromQuickAccess(path: String) = store.edit { p ->
        p[Keys.QUICK_PINS] = (p[Keys.QUICK_PINS] ?: emptySet()) - path
        p[Keys.QUICK_HIDDEN] = (p[Keys.QUICK_HIDDEN] ?: emptySet()) + path
    }

    /**
     * Live "most used" folder counts. Kept in memory while the app runs — a DataStore
     * write per folder visit would hammer flash and contend with settings writes — and
     * flushed to disk by [persistFolderUses] when the app goes to the background.
     */
    private val _folderUses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderUses: StateFlow<Map<String, Int>> = _folderUses

    /**
     * Serializes every mutation of the folder-use state — hydration, visit recording,
     * remapping and persistence (audit P2.9). With only hydration guarded, a concurrent
     * [persistFolderUses] could write the pre-remap in-memory paths after
     * [remapSavedPaths] had already rewritten the persisted set, resurrecting stale
     * paths on disk until the next remap or session update.
     */
    private val usesMutex = Mutex()

    /** True once the persisted counts have been read into [_folderUses]. Guarded by [usesMutex]. */
    @Volatile
    private var usesLoaded = false

    /**
     * Test-only gate (audit P2.9 barrier test): invoked inside [persistFolderUses] while
     * holding [usesMutex], right after the snapshot was taken and before it is written —
     * the exact window in which a concurrent remap used to be able to commit its
     * rewritten set. Always null in production, so this is a no-op there.
     */
    @VisibleForTesting
    internal var onUsesPersistSnapshotted: (() -> Unit)? = null

    /**
     * Test-only gate (audit P2.9 barrier test): invoked inside [remapSavedPaths] while
     * holding [usesMutex], right after the persisted set was rewritten and before the
     * live map follows — the window in which a concurrent flush used to be able to
     * write the pre-remap in-memory paths. Always null in production, so this is a
     * no-op there.
     */
    @VisibleForTesting
    internal var onUsesRemapCommitted: (() -> Unit)? = null

    /** Loads persisted counts once (call at startup so previous sessions surface immediately). */
    suspend fun reloadFolderUses() {
        if (usesLoaded) return
        usesMutex.withLock {
            if (!usesLoaded) hydrateUsesLocked()
        }
    }

    /**
     * Reads the persisted counts into [_folderUses]. The caller must hold [usesMutex].
     * Only marks the map loaded after the read has actually landed — otherwise a
     * stop-before-start persist() could flush the empty map over the saved history.
     */
    private suspend fun hydrateUsesLocked() {
        _folderUses.value = parseFolderUses(store.data.first()[Keys.FOLDER_USES])
        usesLoaded = true
    }

    /**
     * Counts one folder visit. In-memory only; persists via [persistFolderUses].
     * Runs under [usesMutex] (audit P2.9) so a concurrent remap either rewrites this
     * visit's path along with the rest of the map or lands strictly after it.
     */
    suspend fun recordFolderUse(path: String) {
        usesMutex.withLock {
            if (!usesLoaded) hydrateUsesLocked()
            _folderUses.update { uses ->
                val next = uses + (path to (uses[path] ?: 0) + 1)
                if (next.size > MAX_TRACKED_FOLDERS) {
                    next.entries.sortedByDescending { it.value }.take(MAX_TRACKED_FOLDERS)
                        .associate { it.key to it.value }
                } else {
                    next
                }
            }
        }
    }

    /**
     * Persists the in-memory visit counts (MainActivity.onStop). Always hydrates first:
     * if the app is stopped before the initial read finished, flushing the still-empty
     * map would erase the previous session's history instead of preserving it.
     *
     * Snapshot and write share one [usesMutex] critical section with remapping (audit
     * P2.9): a remap can never commit its rewritten set in between, so a flush can no
     * longer resurrect pre-remap paths after a rename or move.
     */
    suspend fun persistFolderUses() {
        usesMutex.withLock {
            if (!usesLoaded) hydrateUsesLocked()
            val entries = _folderUses.value.entries
                .map { "${it.value}|${it.key}" }.toSet()
            onUsesPersistSnapshotted?.invoke()
            store.edit { p ->
                p[Keys.FOLDER_USES] = entries
            }
        }
    }

    private fun parseFolderUses(raw: Set<String>?): Map<String, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in raw) {
            val sep = entry.indexOf('|')
            if (sep <= 0) continue
            val count = entry.substring(0, sep).toIntOrNull() ?: continue
            val path = entry.substring(sep + 1)
            if (path.isNotEmpty()) out[path] = count
        }
        return out
    }

    suspend fun toggleFavorite(path: String) = store.edit { p ->
        val cur = p[Keys.FAVORITES] ?: emptySet()
        p[Keys.FAVORITES] = if (path in cur) cur - path else cur + path
    }

    /** Favorites keep working after their file/folder is renamed or moved: rewrite stored paths. */
    suspend fun remapFavorites(oldPath: String, newPath: String) = store.edit { p ->
        val cur = p[Keys.FAVORITES] ?: emptySet()
        val remap = subtreeRemap(oldPath, newPath)
        val updated = cur.map(remap).toSet()
        if (updated != cur) p[Keys.FAVORITES] = updated
    }

    /**
     * One atomic subtree rename across every path-keyed preference: favorites, Quick
     * access pins, Quick access hidden marks, and the "most used" folder-use counters
     * (both the persisted set and the live in-memory map). Rename or move [oldPath] to
     * [newPath] and every stored path at or under it keeps pointing at the right item.
     *
     * Without this, a renamed/moved folder would silently drop out of Quick access,
     * reappear as a default, or lose its usage history — favorites were the only keys
     * remapped before.
     *
     * The persisted rewrite and the live-map rewrite share one [usesMutex] critical
     * section with recording and persistence (audit P2.9), so no interleaving can write
     * the old in-memory paths to disk once the remapped set has been persisted.
     */
    suspend fun remapSavedPaths(oldPath: String, newPath: String) {
        val remap = subtreeRemap(oldPath, newPath)
        usesMutex.withLock {
            store.edit { p ->
                val favs = p[Keys.FAVORITES] ?: emptySet()
                val remappedFavs = favs.map(remap).toSet()
                if (remappedFavs != favs) p[Keys.FAVORITES] = remappedFavs

                val pins = p[Keys.QUICK_PINS] ?: emptySet()
                val remappedPins = pins.map(remap).toSet()
                if (remappedPins != pins) p[Keys.QUICK_PINS] = remappedPins

                val hidden = p[Keys.QUICK_HIDDEN] ?: emptySet()
                val remappedHidden = hidden.map(remap).toSet()
                if (remappedHidden != hidden) p[Keys.QUICK_HIDDEN] = remappedHidden

                val uses = p[Keys.FOLDER_USES] ?: emptySet()
                val remappedUses = uses.map { entry ->
                    val sep = entry.indexOf('|')
                    if (sep <= 0) entry else "${entry.substring(0, sep)}|${remap(entry.substring(sep + 1))}"
                }.toSet()
                if (remappedUses != uses) p[Keys.FOLDER_USES] = remappedUses
            }
            onUsesRemapCommitted?.invoke()
            // Keep the live counts consistent with the rewritten disk state. If hydration
            // has not happened yet, skip the map rewrite — the upcoming hydration reads the
            // already-remapped values from disk.
            if (usesLoaded) {
                _folderUses.update { m -> m.entries.associate { (k, v) -> remap(k) to v } }
            }
        }
    }

    /**
     * Builds the path rewrite for one subtree rename: an exact match becomes [newPath],
     * anything under [oldPath] moves to the same spot under it, everything else is kept.
     */
    private fun subtreeRemap(oldPath: String, newPath: String): (String) -> String {
        val oldPrefix = oldPath.trimEnd('/')
        val newPrefix = newPath.trimEnd('/')
        return { path ->
            when {
                path == oldPath || path == oldPrefix -> newPrefix
                path.startsWith("$oldPrefix/") -> newPrefix + path.substring(oldPrefix.length)
                else -> path
            }
        }
    }

    /**
     * Removes favorites whose target no longer exists on disk (file renamed/moved/deleted
     * outside toadFILES, or volume unmounted). Returns how many entries were dropped.
     * Idempotent: a second call with no further external changes is a no-op.
     */
    suspend fun pruneDeadFavorites(): Int {
        val current: Set<String> = store.data.first()[Keys.FAVORITES] ?: emptySet()
        if (current.isEmpty()) return 0
        // Re-check existence inside the edit block so the write reflects a fresh snapshot.
        var removed = 0
        store.edit { p ->
            val before = p[Keys.FAVORITES] ?: current
            val after = before.filterTo(mutableSetOf()) {
                runCatching { File(it).exists() }.getOrDefault(false)
            }
            removed = before.size - after.size
            if (removed > 0) p[Keys.FAVORITES] = after
        }
        return removed
    }
}
