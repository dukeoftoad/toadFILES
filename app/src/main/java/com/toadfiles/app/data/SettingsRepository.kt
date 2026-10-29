package com.toadfiles.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ToadSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val browseLayout: BrowseLayout = BrowseLayout.LIST,
    val favorites: Set<String> = emptySet(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "toadfiles_settings")

/** All preferences persisted locally with Jetpack DataStore. Nothing ever leaves the device. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val HIDDEN = booleanPreferencesKey("show_hidden")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val LAYOUT = stringPreferencesKey("browse_layout")
        val FAVORITES = stringSetPreferencesKey("favorites")
    }

    val settings: Flow<ToadSettings> = context.dataStore.data.map { p ->
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
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setShowHidden(enabled: Boolean) = context.dataStore.edit { it[Keys.HIDDEN] = enabled }
    suspend fun setSortMode(mode: SortMode) = context.dataStore.edit { it[Keys.SORT_MODE] = mode.name }
    suspend fun setSortOrder(order: SortOrder) = context.dataStore.edit { it[Keys.SORT_ORDER] = order.name }
    suspend fun setBrowseLayout(layout: BrowseLayout) = context.dataStore.edit { it[Keys.LAYOUT] = layout.name }

    suspend fun toggleFavorite(path: String) = context.dataStore.edit { p ->
        val cur = p[Keys.FAVORITES] ?: emptySet()
        p[Keys.FAVORITES] = if (path in cur) cur - path else cur + path
    }

    /** Favorites keep working after their file/folder is renamed or moved: rewrite stored paths. */
    suspend fun remapFavorites(oldPath: String, newPath: String) = context.dataStore.edit { p ->
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
}
