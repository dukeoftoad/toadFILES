package com.toadfiles.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ToadSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val browseLayout: BrowseLayout = BrowseLayout.LIST,
    val favorites: Set<String> = emptySet(),
    /** Cap applied to in-folder tree search. Bounded to [SEARCH_LIMIT_MIN]..[SEARCH_LIMIT_MAX]. */
    val searchResultLimit: Int = SEARCH_LIMIT_DEFAULT,
    /** When true, in-folder tree search also opens small text files and matches their contents. */
    val contentSearch: Boolean = false,
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
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val HIDDEN = booleanPreferencesKey("show_hidden")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val LAYOUT = stringPreferencesKey("browse_layout")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val SEARCH_LIMIT = intPreferencesKey("search_limit")
        val CONTENT_SEARCH = booleanPreferencesKey("content_search")
    }

    val settings: Flow<ToadSettings> = store.data.map { p ->
        ToadSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
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
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setShowHidden(enabled: Boolean) = store.edit { it[Keys.HIDDEN] = enabled }
    suspend fun setSortMode(mode: SortMode) = store.edit { it[Keys.SORT_MODE] = mode.name }
    suspend fun setSortOrder(order: SortOrder) = store.edit { it[Keys.SORT_ORDER] = order.name }
    suspend fun setBrowseLayout(layout: BrowseLayout) = store.edit { it[Keys.LAYOUT] = layout.name }
    suspend fun setSearchResultLimit(limit: Int) = store.edit {
        it[Keys.SEARCH_LIMIT] = limit.coerceIn(ToadSettings.SEARCH_LIMIT_MIN, ToadSettings.SEARCH_LIMIT_MAX)
    }
    suspend fun setContentSearch(enabled: Boolean) = store.edit { it[Keys.CONTENT_SEARCH] = enabled }

    suspend fun toggleFavorite(path: String) = store.edit { p ->
        val cur = p[Keys.FAVORITES] ?: emptySet()
        p[Keys.FAVORITES] = if (path in cur) cur - path else cur + path
    }

    /** Favorites keep working after their file/folder is renamed or moved: rewrite stored paths. */
    suspend fun remapFavorites(oldPath: String, newPath: String) = store.edit { p ->
        val cur = p[Keys.FAVORITES] ?: emptySet()
        val oldPrefix = oldPath.trimEnd('/')
        val updated = cur.map { f ->
            when {
                f == oldPath -> newPath
                f.startsWith("$oldPrefix/") -> newPath.trimEnd('/') + f.substring(oldPrefix.length)
                else -> f
            }
        }.toSet()
        if (updated != cur) p[Keys.FAVORITES] = updated
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
