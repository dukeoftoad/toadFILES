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
import com.toadfiles.app.domain.AppFont
import com.toadfiles.app.domain.AppIconColor
import com.toadfiles.app.domain.AppIconDesign
import com.toadfiles.app.domain.AppTheme
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.domain.MotionStyle
import com.toadfiles.app.domain.SevenZipOpenAction
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.domain.TextScale
import com.toadfiles.app.domain.ZipOpenAction
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
    /** v1.11: the full visual identity (Appearance tab). [AppTheme.DEFAULT] = the redesign. */
    val appTheme: AppTheme = AppTheme.DEFAULT,
    /** v1.11: how the app moves (nav transitions, springs, splash). */
    val motionStyle: MotionStyle = MotionStyle.DEFAULT,
    /** v1.11: the typeface (Appearance tab). [AppFont.DEFAULT] = bundled Manrope. */
    val appFont: AppFont = AppFont.DEFAULT,
    /** v2.4.0: app-wide text-size multiplier (Appearance tab). [TextScale.DEFAULT] = 1.0,
     *  applied at the theme root through LocalDensity so every sp-based size scales
     *  together with the device's own font setting. Available in both editions — an
     *  accessibility knob, not a feature Lite strips. */
    val textScale: TextScale = TextScale.DEFAULT,
    /** Legacy v1.10 flag. Kept (and written) only so a pre-1.11 install's
     *  Material-You choice can be migrated into [appTheme]; the Appearance tab
     *  no longer exposes it — Material You is its own theme entry now. */
    val dynamicColor: Boolean = true,
    /** Accent chosen by the user — applies to [AppTheme.CLASSIC] only. */
    val accentColor: AccentColor = AccentColor.TOAD,
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortOrder: SortOrder = SortOrder.ASC,
    val browseLayout: BrowseLayout = BrowseLayout.LIST,
    /** v1.8.1: layout of the Recent screen (list or grid), toggled from its toolbar. */
    val recentLayout: BrowseLayout = BrowseLayout.LIST,
    val favorites: Set<String> = emptySet(),
    /** Cap applied to in-folder tree search. Bounded to [SEARCH_LIMIT_MIN]..[SEARCH_LIMIT_MAX]. */
    val searchResultLimit: Int = SEARCH_LIMIT_DEFAULT,
    /** When true, in-folder tree search also opens small text files and matches their contents. */
    val contentSearch: Boolean = false,
    /** Folders the user explicitly pinned to Home's Quick access. */
    val quickPins: Set<String> = emptySet(),
    /** Folders the user removed from Quick access (defaults or auto "most used" entries). */
    val quickHidden: Set<String> = emptySet(),
    // ---- v1.7.3 contract (defaults = exact v1.7.2 behavior) ----
    /** What a tap on a .zip does. MENU = archive actions sheet (v1.7.1 behavior). */
    val zipOpenAction: ZipOpenAction = ZipOpenAction.MENU,
    /** What a tap on a .7z does. MENU = archive actions sheet (v1.7.1 behavior). */
    val sevenZipOpenAction: SevenZipOpenAction = SevenZipOpenAction.MENU,
    /** Which navigation UX is active: bottom bar (v1.7.2 default) or side menu only. */
    val navStyle: NavStyle = NavStyle.BOTTOM_BAR,
    /** Back key inside Browse: go to parent folder (default) or exit the app. */
    val backKeyAction: BackKeyAction = BackKeyAction.PARENT,
    /** Delete source files after a successful compression. */
    val deleteAfterCompress: Boolean = false,
    /** Delete the archive after a successful extraction. */
    val deleteAfterExtract: Boolean = false,
    /** Confirmation dialog before aborting a running archive operation. */
    val abortConfirm: Boolean = true,
    /** Confirmation dialog when extraction would overwrite existing files. */
    val overwriteConfirm: Boolean = true,
    /** One-knob overload switch (v2.3.0): forces MotionStyle.NONE app-wide (no
     *  animations, no splash) and skips recurring heavy work (video thumbnail
     *  decoding) — trades the polish for lower CPU, RAM and battery draw. */
    val performanceMode: Boolean = false,
    /** Default folder for archives (compression output AND extraction destination).
     *  Blank string = "same folder as the source" (v1.7.2 behavior). */
    val archiveFolder: String = "",
    // ---- v1.9: app icon ----
    /** Launcher icon foreground design; applied to the launcher via activity-aliases. */
    val appIconDesign: AppIconDesign = AppIconDesign.TOAD,
    /** Launcher icon background tint. */
    val appIconColor: AppIconColor = AppIconColor.GREEN,
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

        /** Upper bound on how many distinct categories the "most used" tracker remembers.
         *  FileCategory has six entries, so this ceiling is purely defensive. */
        const val MAX_TRACKED_CATEGORIES = 20
    }

    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
        val MOTION_STYLE = stringPreferencesKey("motion_style")
        val APP_FONT = stringPreferencesKey("app_font")
        val TEXT_SCALE = stringPreferencesKey("text_scale")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val ACCENT = stringPreferencesKey("accent_color")
        val HIDDEN = booleanPreferencesKey("show_hidden")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val LAYOUT = stringPreferencesKey("browse_layout")
        val RECENT_LAYOUT = stringPreferencesKey("recent_layout")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val SEARCH_LIMIT = intPreferencesKey("search_limit")
        val CONTENT_SEARCH = booleanPreferencesKey("content_search")
        val QUICK_PINS = stringSetPreferencesKey("quick_pins")
        val QUICK_HIDDEN = stringSetPreferencesKey("quick_hidden")
        val FOLDER_USES = stringSetPreferencesKey("folder_uses")
        val CATEGORY_USES = stringSetPreferencesKey("category_uses")
        val ZIP_OPEN = stringPreferencesKey("zip_open_action")
        val SEVENZIP_OPEN = stringPreferencesKey("sevenzip_open_action")
        val NAV_STYLE = stringPreferencesKey("nav_style")
        val BACK_KEY = stringPreferencesKey("back_key_action")
        val DELETE_AFTER_COMPRESS = booleanPreferencesKey("delete_after_compress")
        val DELETE_AFTER_EXTRACT = booleanPreferencesKey("delete_after_extract")
        val ABORT_CONFIRM = booleanPreferencesKey("abort_confirm")
        val OVERWRITE_CONFIRM = booleanPreferencesKey("overwrite_confirm")
        val PERFORMANCE_MODE = booleanPreferencesKey("performance_mode")
        val ARCHIVE_FOLDER = stringPreferencesKey("archive_folder")
        val APP_ICON_DESIGN = stringPreferencesKey("app_icon_design")
        val APP_ICON_COLOR = stringPreferencesKey("app_icon_color")
    }

    val settings: Flow<ToadSettings> = store.data.map { p ->
        ToadSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            appTheme = p[Keys.APP_THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: migrateAppTheme(p),
            motionStyle = p[Keys.MOTION_STYLE]?.let { runCatching { MotionStyle.valueOf(it) }.getOrNull() }
                ?: MotionStyle.DEFAULT,
            appFont = p[Keys.APP_FONT]?.let { runCatching { AppFont.valueOf(it) }.getOrNull() }
                ?: AppFont.DEFAULT,
            textScale = p[Keys.TEXT_SCALE]?.let { runCatching { TextScale.valueOf(it) }.getOrNull() }
                ?: TextScale.DEFAULT,
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
            recentLayout = p[Keys.RECENT_LAYOUT]?.let { runCatching { BrowseLayout.valueOf(it) }.getOrNull() }
                ?: BrowseLayout.LIST,
            favorites = p[Keys.FAVORITES] ?: emptySet(),
            searchResultLimit = p[Keys.SEARCH_LIMIT]?.coerceIn(
                ToadSettings.SEARCH_LIMIT_MIN,
                ToadSettings.SEARCH_LIMIT_MAX,
            ) ?: ToadSettings.SEARCH_LIMIT_DEFAULT,
            contentSearch = p[Keys.CONTENT_SEARCH] ?: false,
            quickPins = p[Keys.QUICK_PINS] ?: emptySet(),
            quickHidden = p[Keys.QUICK_HIDDEN] ?: emptySet(),
            zipOpenAction = p[Keys.ZIP_OPEN]?.let { runCatching { ZipOpenAction.valueOf(it) }.getOrNull() }
                ?: ZipOpenAction.MENU,
            sevenZipOpenAction = p[Keys.SEVENZIP_OPEN]?.let { runCatching { SevenZipOpenAction.valueOf(it) }.getOrNull() }
                ?: SevenZipOpenAction.MENU,
            navStyle = p[Keys.NAV_STYLE]?.let { runCatching { NavStyle.valueOf(it) }.getOrNull() }
                ?: NavStyle.BOTTOM_BAR,
            backKeyAction = p[Keys.BACK_KEY]?.let { runCatching { BackKeyAction.valueOf(it) }.getOrNull() }
                ?: BackKeyAction.PARENT,
            deleteAfterCompress = p[Keys.DELETE_AFTER_COMPRESS] ?: false,
            deleteAfterExtract = p[Keys.DELETE_AFTER_EXTRACT] ?: false,
            abortConfirm = p[Keys.ABORT_CONFIRM] ?: true,
            overwriteConfirm = p[Keys.OVERWRITE_CONFIRM] ?: true,
            performanceMode = p[Keys.PERFORMANCE_MODE] ?: false,
            archiveFolder = p[Keys.ARCHIVE_FOLDER] ?: "",
            appIconDesign = p[Keys.APP_ICON_DESIGN]?.let { runCatching { AppIconDesign.valueOf(it) }.getOrNull() }
                ?: AppIconDesign.TOAD,
            appIconColor = p[Keys.APP_ICON_COLOR]?.let { runCatching { AppIconColor.valueOf(it) }.getOrNull() }
                ?: AppIconColor.GREEN,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setAppTheme(theme: AppTheme) = store.edit { it[Keys.APP_THEME] = theme.name }
    suspend fun setMotionStyle(style: MotionStyle) = store.edit { it[Keys.MOTION_STYLE] = style.name }
    suspend fun setAppFont(font: AppFont) = store.edit { it[Keys.APP_FONT] = font.name }
    suspend fun setTextScale(scale: TextScale) = store.edit { it[Keys.TEXT_SCALE] = scale.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setAccentColor(accent: AccentColor) = store.edit { it[Keys.ACCENT] = accent.name }
    suspend fun setShowHidden(enabled: Boolean) = store.edit { it[Keys.HIDDEN] = enabled }
    suspend fun setSortMode(mode: SortMode) = store.edit { it[Keys.SORT_MODE] = mode.name }
    suspend fun setSortOrder(order: SortOrder) = store.edit { it[Keys.SORT_ORDER] = order.name }
    suspend fun setBrowseLayout(layout: BrowseLayout) = store.edit { it[Keys.LAYOUT] = layout.name }
    suspend fun setRecentLayout(layout: BrowseLayout) = store.edit { it[Keys.RECENT_LAYOUT] = layout.name }
    suspend fun setSearchResultLimit(limit: Int) = store.edit {
        it[Keys.SEARCH_LIMIT] = limit.coerceIn(ToadSettings.SEARCH_LIMIT_MIN, ToadSettings.SEARCH_LIMIT_MAX)
    }
    suspend fun setContentSearch(enabled: Boolean) = store.edit { it[Keys.CONTENT_SEARCH] = enabled }
    suspend fun setZipOpenAction(action: ZipOpenAction) = store.edit { it[Keys.ZIP_OPEN] = action.name }
    suspend fun setSevenZipOpenAction(action: SevenZipOpenAction) = store.edit { it[Keys.SEVENZIP_OPEN] = action.name }
    suspend fun setNavStyle(style: NavStyle) = store.edit { it[Keys.NAV_STYLE] = style.name }
    suspend fun setBackKeyAction(action: BackKeyAction) = store.edit { it[Keys.BACK_KEY] = action.name }
    suspend fun setDeleteAfterCompress(enabled: Boolean) = store.edit { it[Keys.DELETE_AFTER_COMPRESS] = enabled }
    suspend fun setDeleteAfterExtract(enabled: Boolean) = store.edit { it[Keys.DELETE_AFTER_EXTRACT] = enabled }
    suspend fun setAbortConfirm(enabled: Boolean) = store.edit { it[Keys.ABORT_CONFIRM] = enabled }
    suspend fun setOverwriteConfirm(enabled: Boolean) = store.edit { it[Keys.OVERWRITE_CONFIRM] = enabled }
    suspend fun setPerformanceMode(enabled: Boolean) = store.edit { it[Keys.PERFORMANCE_MODE] = enabled }
    suspend fun setArchiveFolder(path: String) = store.edit { it[Keys.ARCHIVE_FOLDER] = path.trim() }
    suspend fun setAppIconDesign(design: AppIconDesign) = store.edit { it[Keys.APP_ICON_DESIGN] = design.name }
    suspend fun setAppIconColor(color: AppIconColor) = store.edit { it[Keys.APP_ICON_COLOR] = color.name }

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

    // ---------- Most-used categories (v2.5.0) ----------

    /**
     * Live "most used" FILE-CATEGORY open counts, keyed by [FileCategory.name]. The
     * exact sibling of the folder-use tracker above — same "count|key" persistence
     * format, same mutex, same hydrate-on-first-use discipline — fed by
     * [CategoriesViewModel.open] (every drill-in counts, wherever it starts: the
     * grid, the drawer's Category dropdown, or a Quick access tile) and read by the
     * drawer's Most-used section and Home's most-used strip. Categories are never
     * renamed or remapped, so no remapSavedPaths counterpart exists here.
     */
    private val _categoryUses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val categoryUses: StateFlow<Map<String, Int>> = _categoryUses

    /** True once the persisted category counts have been read into [_categoryUses]. Guarded by [usesMutex]. */
    @Volatile
    private var categoryUsesLoaded = false

    /** Loads persisted category counts once (call at startup so previous sessions surface immediately). */
    suspend fun reloadCategoryUses() {
        if (categoryUsesLoaded) return
        usesMutex.withLock {
            if (!categoryUsesLoaded) hydrateCategoryUsesLocked()
        }
    }

    /** Reads the persisted counts into [_categoryUses]. The caller must hold [usesMutex]. */
    private suspend fun hydrateCategoryUsesLocked() {
        _categoryUses.value = parseFolderUses(store.data.first()[Keys.CATEGORY_USES])
        categoryUsesLoaded = true
    }

    /** Counts one category open. In-memory only; persists via [persistCategoryUses]. */
    suspend fun recordCategoryUse(name: String) {
        if (name.isBlank()) return
        usesMutex.withLock {
            if (!categoryUsesLoaded) hydrateCategoryUsesLocked()
            _categoryUses.update { uses ->
                val next = uses + (name to (uses[name] ?: 0) + 1)
                if (next.size > MAX_TRACKED_CATEGORIES) {
                    next.entries.sortedByDescending { it.value }.take(MAX_TRACKED_CATEGORIES)
                        .associate { it.key to it.value }
                } else {
                    next
                }
            }
        }
    }

    /**
     * Persists the in-memory category counts (MainActivity.onStop, next to the folder
     * flush). Always hydrates first — flushing a still-empty map would erase the
     * previous session's history, exactly like the folder-use flush.
     */
    suspend fun persistCategoryUses() {
        usesMutex.withLock {
            if (!categoryUsesLoaded) hydrateCategoryUsesLocked()
            val entries = _categoryUses.value.entries
                .map { "${it.value}|${it.key}" }.toSet()
            store.edit { p ->
                p[Keys.CATEGORY_USES] = entries
            }
        }
    }

    /**
     * v1.11 theme migration for upgraders who never stored an [AppTheme]:
     *  - explicitly turned dynamic color OFF or picked an accent → keep their look
     *    as [AppTheme.CLASSIC] (the accent picker lives there),
     *  - everyone else (Material You on, the v1.10 default, or fresh installs)
     *    gets the redesign's [AppTheme.DEFAULT].
     */
    private fun migrateAppTheme(p: Preferences): AppTheme =
        if (p[Keys.DYNAMIC] == false || p[Keys.ACCENT] != null) AppTheme.CLASSIC
        else AppTheme.DEFAULT

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
