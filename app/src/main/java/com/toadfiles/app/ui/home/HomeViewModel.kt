package com.toadfiles.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.StorageStats
import com.toadfiles.app.domain.Trash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** One tile in Home's Quick access grid. */
data class QuickAccessEntry(
    val label: String,
    val dir: File,
    val pinned: Boolean,
    val source: Source,
) {
    enum class Source { PINNED, MOST_USED, DEFAULT }
}

/** One row inside the Quick access editor dialog. */
data class QuickCandidate(
    val name: String,
    val path: String,
    val shown: Boolean,
    val isDefault: Boolean,
    /** True while the folder sits in the pins set — the editor labels it "Pinned". */
    val pinned: Boolean,
)

data class HomeUiState(
    val storage: StorageStats? = null,
    /**
     * Quick access tiles: user pins first (most-visited pins first), then up to four
     * "most used" folders the user never pinned or removed, then the standard folders.
     * Fully editable — see the editor dialog on Home.
     */
    val quickAccess: List<QuickAccessEntry> = emptyList(),
    /** Candidates for the Quick access editor. */
    val quickCandidates: List<QuickCandidate> = emptyList(),
    /**
     * v2.5.0: the two most-opened file categories, for Quick access's most-used
     * strip (owner spec: capped at two, categories ONLY — the folder tiles above
     * keep today's behavior untouched). Empty until a category has been opened.
     */
    val topCategories: List<FileCategory> = emptyList(),
    val favorites: List<FileItem> = emptyList(),
    /** Active type filter for the recent feed; null = All. Same chips row as the Recent screen. */
    val recentFilter: FileCategory? = null,
    /**
     * Smart-recent feed on Home. Replaces the old Categories section + the standalone Recent
     * tab: the user wanted one unified home. We list files the OS media library reports as
     * recently modified, newest first, capped at [HomeViewModel.RECENT_LIMIT].
     */
    val recentFiles: List<MediaRepository.MediaEntry> = emptyList(),
    val loadingRecent: Boolean = false,
)

class HomeViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests; production uses the container's MediaStore-backed repository. */
    private val media: MediaQuerySource = container.mediaRepo,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    /** Latest settings snapshot, so refresh() can rebuild Quick access without waiting for an emission. */
    private var lastSettings: ToadSettings = ToadSettings()

    /** Latest live folder-use counts (in-memory, persisted on app stop). */
    private var lastUses: Map<String, Int> = emptyMap()

    /** Request ids + live jobs per lane, so an older query can never overwrite newer state. */
    private var recentSeq = 0L
    private var favoritesSeq = 0L
    private var quickAccessSeq = 0L
    private var recentJob: Job? = null
    private var favoritesJob: Job? = null
    private var quickAccessJob: Job? = null

    init {
        refreshStorage()
        // Surface "most used" from previous sessions right away (folders + categories).
        viewModelScope.launch { container.settingsRepo.reloadFolderUses() }
        viewModelScope.launch { container.settingsRepo.reloadCategoryUses() }
        refreshRecent()
        viewModelScope.launch {
            // One-shot cleanup of favorites pointing at paths that no longer exist
            // (file renamed / moved / deleted outside toadFILES, or volume unmounted).
            // The result also flows back via settings so refreshFavorites re-runs.
            container.settingsRepo.pruneDeadFavorites()
        }
        viewModelScope.launch {
            combine(
                container.settingsRepo.settings,
                container.settingsRepo.folderUses,
                container.settingsRepo.categoryUses,
            ) { s, uses, catUses ->
                Triple(s, uses, catUses)
            }.collect { (s, uses, catUses) ->
                lastSettings = s
                lastUses = uses
                refreshFavorites(s.favorites)
                rebuildQuickAccess(s, uses)
                updateTopCategories(catUses)
            }
        }
    }

    /**
     * v2.5.0: the top two most-opened categories for Quick access's most-used strip
     * (owner spec: capped at two, categories only — the folder tiles above keep
     * today's behavior). At least one open is required; a fresh install shows no
     * strip at all rather than an empty shell.
     */
    private fun updateTopCategories(catUses: Map<String, Int>) {
        val top = catUses.entries
            .filter { it.value >= 1 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(2)
            .mapNotNull { (name, _) -> runCatching { FileCategory.valueOf(name) }.getOrNull() }
        _state.update { it.copy(topCategories = top) }
    }

    fun refreshStorage() {
        viewModelScope.launch(ioDispatcher) {
            val stats = container.fileRepo.statsOf(container.fileRepo.primaryRoot())
            _state.update { it.copy(storage = stats) }
        }
    }

    /**
     * Builds the Quick access row: explicit user pins first (most-visited pins first),
     * then up to four "most used" folders the user never pinned or removed, then the
     * standard folders that still exist. Everything respects the user's removals.
     */
    private fun rebuildQuickAccess(s: ToadSettings, uses: Map<String, Int>) {
        val token = ++quickAccessSeq
        quickAccessJob?.cancel()
        quickAccessJob = viewModelScope.launch(ioDispatcher) {
            val defaults = container.fileRepo.quickLinks()
            val defaultPaths = defaults.map { it.second.path }.toSet()

            val out = mutableListOf<QuickAccessEntry>()
            // 1. Pinned folders (existing ones only), most-visited first.
            s.quickPins.mapNotNull { p -> File(p).takeIf { it.isDirectory } }
                .sortedWith(
                    compareByDescending<File> { uses[it.path] ?: 0 }
                        .thenBy { it.name.lowercase() },
                )
                .forEach { out += QuickAccessEntry(it.name, it, pinned = true, source = QuickAccessEntry.Source.PINNED) }

            // 2. Most used (never pinned, never removed), at least two visits, top four.
            uses.entries
                .filter { it.value >= 2 }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key.lowercase() },
                )
                .mapNotNull { (p, _) ->
                    File(p).takeIf {
                        it.isDirectory && p !in s.quickPins && p !in s.quickHidden
                    }
                }
                .take(4)
                .forEach {
                    out += QuickAccessEntry(it.name, it, pinned = false, source = QuickAccessEntry.Source.MOST_USED)
                }

            // 3. Standard defaults, unless already shown or removed by the user.
            defaults.forEach { (name, dir) ->
                if (dir.path !in s.quickHidden && out.none { it.dir.path == dir.path }) {
                    out += QuickAccessEntry(name, dir, pinned = false, source = QuickAccessEntry.Source.DEFAULT)
                }
            }
            _state.update { s ->
                if (token != quickAccessSeq) s // a newer settings snapshot took over
                else s.copy(quickAccess = capQuickAccess(out))
            }

            // Editor candidates: pins + defaults + most used + top-level folders.
            val topDirs = container.fileRepo.primaryRoot()
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") && it.name != Trash.DIR_NAME }
                .sortedBy { it.name.lowercase() }
                .take(15)
            val candidates = (s.quickPins + defaultPaths + uses.keys + topDirs.map { it.path })
                .distinct()
                .mapNotNull { p -> File(p).takeIf { it.isDirectory } }
                .sortedBy { it.name.lowercase() }
                .map { f ->
                    QuickCandidate(
                        name = defaults.firstOrNull { it.second.path == f.path }?.first ?: f.name,
                        path = f.path,
                        shown = f.path !in s.quickHidden,
                        isDefault = f.path in defaultPaths,
                        pinned = f.path in s.quickPins,
                    )
                }
            _state.update { s ->
                if (token != quickAccessSeq) s // a newer settings snapshot took over
                else s.copy(quickCandidates = candidates)
            }
        }
    }

    /**
     * The [QUICK_CAP] trim with a default-starvation guard: plain `take(10)` let ten pins
     * elbow every standard folder off the grid. When the list overflows, the last
     * [QUICK_MIN_DEFAULTS] slots are reserved for DEFAULT entries (when any exist); the
     * excess is dropped from the tail, so the lowest-ranked most-used folders go first
     * and only defaults beyond the reserve follow. The pins → most used → defaults order
     * is preserved.
     */
    private fun capQuickAccess(entries: List<QuickAccessEntry>): List<QuickAccessEntry> {
        if (entries.size <= QUICK_CAP) return entries
        val defaultsAvailable = entries.count { it.source == QuickAccessEntry.Source.DEFAULT }
        if (defaultsAvailable == 0) return entries.take(QUICK_CAP)
        val reserve = minOf(defaultsAvailable, QUICK_MIN_DEFAULTS)
        val head = entries.take(QUICK_CAP - reserve)
        val tail = entries.drop(QUICK_CAP - reserve)
            .filter { it.source == QuickAccessEntry.Source.DEFAULT }
            .take(reserve)
        return head + tail
    }

    // ---------- quick access editing ----------

    /** Editor checkbox: shown -> pin (and unhide); hidden -> unpin and hide. */
    fun setQuickShown(path: String, shown: Boolean) {
        viewModelScope.launch {
            if (shown) {
                container.settingsRepo.toggleQuickPin(path)
            } else {
                container.settingsRepo.hideFromQuickAccess(path)
            }
        }
    }

    /** Long-press a tile: removes it (unpin + hide). */
    fun removeQuickAccess(path: String) {
        viewModelScope.launch { container.settingsRepo.hideFromQuickAccess(path) }
    }

    /** Smart-recent files from the OS media library, newest first. Bounded to keep the list cheap. */
    fun refreshRecent() {
        val token = ++recentSeq
        val filter = _state.value.recentFilter
        _state.update { it.copy(loadingRecent = true) }
        recentJob?.cancel()
        recentJob = viewModelScope.launch(ioDispatcher) {
            val recents = if (filter == null) {
                media.recent(limit = RECENT_LIMIT)
            } else {
                media.list(filter, limit = RECENT_LIMIT)
            }
            if (token != recentSeq) return@launch // superseded by a newer refresh
            _state.update { it.copy(recentFiles = recents, loadingRecent = false) }
        }
    }

    /** Switches the recent feed's type filter (All / Images / Videos / …) and reloads. */
    fun setRecentFilter(filter: FileCategory?) {
        if (_state.value.recentFilter == filter) return
        _state.update { it.copy(recentFilter = filter) }
        refreshRecent()
    }

    private fun refreshFavorites(paths: Set<String>) {
        val token = ++favoritesSeq
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch(ioDispatcher) {
            val items = paths.mapNotNull { p ->
                val f = File(p)
                if (f.exists()) FileItem(f, f.name, f.isDirectory, f.length(), f.lastModified()) else null
            }.sortedBy { it.name.lowercase() }
            if (token != favoritesSeq) return@launch // a newer settings emission took over
            _state.update { it.copy(favorites = items) }
        }
    }

    /** Re-reads everything that can change while the user is in other tabs. */
    fun refresh() {
        refreshStorage()
        refreshRecent()
        // Quick access depends on the disk (new/deleted folders), so rebuild it on
        // every re-entry — not only when settings change.
        rebuildQuickAccess(lastSettings, lastUses)
    }

    fun toggleFavorite(path: String) {
        viewModelScope.launch { container.settingsRepo.toggleFavorite(path) }
    }

    companion object {
        /** Bounded recent list — matches the original Recent tab's cap. */
        const val RECENT_LIMIT = 60

        /** Upper bound for Quick access entries (the permanent Trash tile renders on top). */
        const val QUICK_CAP = 10

        /** When [QUICK_CAP] trims, at least this many standard folders stay on the grid. */
        const val QUICK_MIN_DEFAULTS = 2
    }
}
