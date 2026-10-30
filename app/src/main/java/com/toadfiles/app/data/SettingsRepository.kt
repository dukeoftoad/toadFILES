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
    /**
     * Toggle for any future diagnostic / crash-report upload. toadFILES has no internet
     * permission so this is effectively a no-op today, but it's the user-visible knob that
     * signals intent for the privacy promise ("no diagnostics by default").
     */
    val diagnosticsEnabled: Boolean = false,
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
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics_enabled")
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
            diagnosticsEnabled = p[Keys.DIAGNOSTICS] ?: false,
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
    suspend fun setDiagnosticsEnabled(enabled: Boolean) = store.edit { it[Keys.DIAGNOSTICS] = enabled }

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
    private var usesLoaded = false

    /** Loads persisted counts once (call at startup so previous sessions surface immediately). */
    suspend fun reloadFolderUses() {
        if (usesLoaded) return
        usesLoaded = true
        _folderUses.value = parseFolderUses(store.data.first()[Keys.FOLDER_USES])
    }

    /** Counts one folder visit. In-memory only; persists via [persistFolderUses]. */
    suspend fun recordFolderUse(path: String) {
        reloadFolderUses()
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

    /** Persists the in-memory visit counts (MainActivity.onStop). */
    suspend fun persistFolderUses() {
        store.edit { p ->
            p[Keys.FOLDER_USES] = _folderUses.value.entries
                .map { "${it.value}|${it.key}" }.toSet()
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
