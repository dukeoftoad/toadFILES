package com.toadfiles.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.data.FileRepository
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.domain.PathSafety
import com.toadfiles.app.domain.PropertiesBuilder
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.Sorting
import com.toadfiles.app.domain.SevenZipOpenAction
import com.toadfiles.app.domain.ZipOpenAction
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.components.PropertiesDetail
import com.toadfiles.app.ui.components.computeFilePropertiesDetail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class BrowseUiState(
    val dir: File? = null,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val breadcrumbs: List<File> = emptyList(),
    val selection: Set<File> = emptySet(),
    val query: String = "",
    val searching: Boolean = false,
    /** True when the last search hit the configured limit (UI can warn the user). */
    val searchCapped: Boolean = false,
    val filterMode: FilterMode = FilterMode.ALL,
    val settings: ToadSettings = ToadSettings(),
    val favoritePaths: Set<String> = emptySet(),
    /** Folders pinned to Home's Quick access (drives the folder sheet's pin label). */
    val quickPins: Set<String> = emptySet(),
    val clipboard: Clipboard.Clip? = null,
    /** Number of items currently in the trash across all known volumes; -1 means "not loaded". */
    val trashCount: Int = -1,
    // dialogs
    val pendingNewFolder: Boolean = false,
    val pendingRename: File? = null,
    val pendingDelete: List<File>? = null,
    /** Batch waiting for the user to type an archive name in the Zip dialog. */
    val pendingZipFiles: List<File>? = null,
    val pendingZipName: String = "",
    val pendingConflict: PendingConflict? = null,
    val progress: Pair<String, OpProgress>? = null,
    val result: OpResult? = null,
    val resultTitle: String = "",
    /** Item whose read-only properties sheet is open; null when closed. */
    val pendingProperties: File? = null,
    /** Computed size/contents for the properties sheet; null while the walk is running. */
    val propertiesDetail: PropertiesDetail? = null,
    val toast: String? = null,
    val lastTrashed: List<File>? = null,
)

data class PendingConflict(val op: OpKind, val targetDir: File, val sources: List<File>, val conflicts: List<String>)

enum class OpKind { COPY, MOVE, ZIP_EXTRACT }

/** One-shot UI events — navigation after an extract, … — never replayed as state. */
sealed interface ExtractEvent {
    /** A finished extraction published at least one item; the UI can navigate into it. */
    data class ExtractCompleted(val destination: File) : ExtractEvent
}

/**
 * What a row tap on an archive does (v1.7.3): the resolved, format-agnostic form of
 * [ZipOpenAction] / [SevenZipOpenAction] so the screen only has one enum to switch on.
 */
enum class ArchiveTapAction { SHOW_MENU, EXTRACT, OPEN_WITH }

@OptIn(FlowPreview::class)
class BrowseViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests; production uses the container's real-file-system repository. */
    private val fileRepo: FileRepository = container.fileRepo,
    /** Injectable for tests; production walks the real file system for contents counts. */
    private val propertiesBuilder: PropertiesBuilder = PropertiesBuilder(),
) : ViewModel() {

    private val settingsRepo = container.settingsRepo

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state

    /** One-shot navigation events (post-extract redirect, …); see [ExtractEvent]. */
    private val _events = MutableSharedFlow<ExtractEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ExtractEvent> = _events

    private var searchJob: Job? = null

    /** Current folder-listing job; cancelled and superseded by the next load (P2.8). */
    private var loadJob: Job? = null

    /** Properties computation for the open sheet; cancelled on dismiss (see requestProperties). */
    private var propertiesJob: Job? = null

    /**
     * Monotonically increasing load request id; only the newest load may apply its
     * listing (P2.8). Atomic because load() is also called from IO coroutines.
     */
    private val loadSeq = AtomicLong(0L)

    /** First directory opened in this session; up-navigation and breadcrumbs never go above it. */
    private var browseRoot: File? = null

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        favoritePaths = s.favorites,
                        quickPins = s.quickPins,
                    )
                }
                // Re-list only when a setting that affects the listing changed; other
                // emissions (quick pins, favorites, …) must not disturb the view.
                val relevant = Triple(s.showHidden, s.sortMode, s.sortOrder)
                val changed = relevant != lastListSettings
                lastListSettings = relevant
                if (changed) _state.value.dir?.let { d -> load(d) }
            }
        }
        viewModelScope.launch {
            container.clipboard.clip.collect { c ->
                _state.update { it.copy(clipboard = c) }
            }
        }
        refreshTrashCount()
    }

    fun openDir(dir: File) {
        // Count visits (skipping the trash) so Home's Quick access can surface
        // most-used folders. In-memory only — the repository batches the disk write.
        if (dir.isDirectory && dir.name != Trash.DIR_NAME) {
            viewModelScope.launch { settingsRepo.recordFolderUse(dir.absolutePath) }
        }
        load(dir)
    }

    fun load(dir: File) {
        if (browseRoot == null) browseRoot = dir
        // P2.8: every load takes a monotonically increasing token and cancels the load job
        // it supersedes. Two loads for the same path can overlap (rapid refresh, sort or
        // hidden-setting change, op-triggered re-list — they all funnel through here), and
        // a slower older result must never overwrite the newer listing.
        val token = loadSeq.incrementAndGet()
        val settings = _state.value.settings
        _state.update {
            it.copy(
                dir = dir,
                loading = true,
                error = null,
                selection = emptySet(),
                query = "",
                searching = false,
                breadcrumbs = breadcrumbs(dir),
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher) {
            if (!dir.isDirectory || !dir.canRead()) {
                applyListing(dir, token, settings) {
                    it.copy(loading = false, items = emptyList(), error = "Folder is not readable")
                }
                return@launch
            }
            val items = fileRepo.list(dir, settings.showHidden)
                .sortedWith(Sorting.comparator(settings.sortMode, settings.sortOrder))
            ensureActive() // honour a cancel that arrived while the listing was read
            applyListing(dir, token, settings) {
                it.copy(loading = false, items = items)
            }
        }
    }

    /**
     * Applies a finished listing only when its request is still the newest one and the
     * view still shows the same directory with the same listing-affecting settings
     * (showHidden / sortMode / sortOrder); otherwise the result is stale and dropped.
     * Checked atomically inside the state update, like RecentViewModel's token guard.
     */
    private fun applyListing(
        dir: File,
        token: Long,
        settings: ToadSettings,
        update: (BrowseUiState) -> BrowseUiState,
    ) {
        _state.update { s ->
            val current = s.settings
            val stale = token != loadSeq.get() ||
                s.dir?.path != dir.path ||
                current.showHidden != settings.showHidden ||
                current.sortMode != settings.sortMode ||
                current.sortOrder != settings.sortOrder
            if (stale) s else update(s)
        }
    }

    private fun breadcrumbs(dir: File): List<File> {
        val root = browseRoot
        val out = mutableListOf<File>()
        var f: File? = dir
        while (f != null) {
            out.add(0, f)
            if (root != null && f.path == root.path) break
            f = f.parentFile
        }
        return out
    }

    fun toggleSelection(item: FileItem) {
        _state.update {
            val next = it.selection.toMutableSet()
            if (!next.add(item.file)) next.remove(item.file)
            it.copy(selection = next)
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Selects only the items the user can actually see (respects the active filter). */
    fun selectAll() = _state.update { s ->
        s.copy(selection = s.items.filter { f -> s.filterMode.matches(f.name) }.map { f -> f.file }.toSet())
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, searching = q.isNotBlank(), searchCapped = false) }
        searchJob?.cancel()
        val dir = _state.value.dir ?: return
        if (q.isBlank()) {
            // Clearing the search must restore the real folder listing — otherwise the
            // stale search results kept masquerading as the folder contents.
            load(dir)
            return
        }
        // P2.8: the search replaces the folder listing, so any in-flight load result is
        // now stale — invalidate its token and cancel it before the search takes over.
        loadSeq.incrementAndGet()
        loadJob?.cancel()
        searchJob = viewModelScope.launch(ioDispatcher) {
            delay(300) // debounce
            val s = _state.value.settings
            val results = searchTree(
                root = dir,
                query = q,
                showHidden = s.showHidden,
                limit = s.searchResultLimit,
                includeContent = s.contentSearch,
            )
            // Only apply if this search is still the latest
            if (_state.value.query == q) {
                _state.update {
                    it.copy(
                        items = results.items,
                        searching = false,
                        searchCapped = results.capped,
                    )
                }
            }
        }
    }

    /**
     * Recursive in-tree search. Matches file name (always) and small-text-file content (opt-in).
     *
     * Symlink policy (P2.1, explicit): directory-ness is decided with no-follow attributes
     * (see [isRealDirectory]) instead of [File.isDirectory], which silently follows links.
     * Directory symlinks are never recursed into — a link back to an ancestor cannot trap
     * this traversal in a cycle — but they still surface as name-matched results. On top
     * of that, a visited set of canonical paths breaks any remaining cycle, and the
     * traversal honours cancellation at every directory and every entry.
     */
    private suspend fun searchTree(
        root: File,
        query: String,
        showHidden: Boolean,
        limit: Int,
        includeContent: Boolean,
    ): SearchResult {
        val q = query.lowercase()
        val out = mutableListOf<FileItem>()
        var capped = false
        val stack = ArrayDeque<File>()
        // Canonical paths of directories already queued: breaks cycles for good even if a
        // symlinked directory ever slips through (the same physical dir is visited once).
        val visited = HashSet<String>()
        fun push(dir: File) {
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
            if (visited.add(canonical)) stack.addLast(dir)
        }
        push(root)
        while (stack.isNotEmpty() && out.size < limit) {
            coroutineContext.ensureActive() // cancellation checkpoint per directory
            val dir = stack.removeLast()
            val children = fileRepo.list(dir, showHidden)
            for (c in children) {
                coroutineContext.ensureActive() // cancellation checkpoint per file
                if (c.name.lowercase().contains(q)) {
                    out += c
                    if (out.size >= limit) {
                        capped = true
                        break
                    }
                } else if (includeContent && !c.isDir && contentFileMightMatch(c.file, q)) {
                    out += c
                    if (out.size >= limit) {
                        capped = true
                        break
                    }
                }
                // Recurse into real directories only; a symlinked directory is matched by
                // name above but never pushed (a link to an ancestor would loop forever).
                if (isRealDirectory(c.file)) push(c.file)
            }
        }
        return SearchResult(
            items = out.sortedWith(
                compareByDescending<FileItem> { it.isDir }
                    .thenComparator { a, b -> Sorting.naturalCompare(a.name, b.name) }
            ),
            capped = capped,
        )
    }

    private data class SearchResult(val items: List<FileItem>, val capped: Boolean)

    /**
     * True only for a real directory. Unlike [File.isDirectory], this never follows a
     * symbolic link: a symlink pointing at a directory is NOT a directory for traversal
     * purposes (Browse's search does not recurse into symlinked folders).
     */
    private fun isRealDirectory(f: File): Boolean = try {
        val attrs = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attrs.isDirectory && !attrs.isSymbolicLink
    } catch (_: IOException) {
        false // unreadable or vanished entry: nothing to recurse into
    }

    /**
     * Cheap content peek: only opens files with extension in the safe-to-read allowlist
     * and smaller than [CONTENT_MAX_BYTES]. Reads as text (UTF-8); binary content never matches.
     */
    private fun contentFileMightMatch(f: File, q: String): Boolean {
        if (!f.isFile || f.length() > CONTENT_MAX_BYTES || f.length() == 0L) return false
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (ext !in CONTENT_EXTENSIONS) return false
        return try {
            f.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    if (String(buf, 0, n).lowercase().contains(q)) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Refresh the in-trash item count for the "Recently deleted" chip in Browse. */
    fun refreshTrashCount() {
        viewModelScope.launch(ioDispatcher) {
            val roots = fileRepo.roots().map { it.dir }
            val n = runCatching { Trash.entries(roots).size }.getOrDefault(0)
            _state.update { it.copy(trashCount = n) }
        }
    }

    companion object {
        /** Largest file size (in bytes) the content-search peek will open. */
        private const val CONTENT_MAX_BYTES = 1L * 1024 * 1024 // 1 MiB

        /** Extensions considered small-text-ish. Binary formats never match (decoded as gibberish). */
        private val CONTENT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "csv", "tsv", "json", "yaml", "yml",
            "xml", "html", "htm", "css", "js", "mjs", "ts", "kt", "kts", "java",
            "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift",
            "sh", "bash", "zsh", "bat", "ps1", "ini", "cfg", "conf", "toml",
            "properties", "gradle", "gitignore", "env", "sql",
        )
    }

    fun setSort(mode: SortMode) {
        viewModelScope.launch { settingsRepo.setSortMode(mode) }
    }

    /**
     * Pull-to-refresh hook: re-lists the current folder through the standard [load]
     * path (same semantics as an operation-triggered reload — clears query/selection).
     */
    fun refresh() {
        _state.value.dir?.let { load(it) }
    }

    /**
     * Resolves the v1.7.3 tap behavior for an extractable archive: .7z honors
     * [ToadSettings.sevenZipOpenAction], everything else (i.e. .zip) honors
     * [ToadSettings.zipOpenAction]. SHOW_MENU keeps the v1.7.1 extract-or-open dialog,
     * EXTRACT runs the exact pipeline behind the menu's "Extract here" (including the
     * same pre-existing-target refusal / conflict dialog via [extractZip]), OPEN_WITH
     * is the pre-v1.7.1 ACTION_VIEW behavior.
     */
    fun archiveTapAction(fileName: String): ArchiveTapAction {
        val s = _state.value.settings
        return if (ArchiveOps.extension(fileName) == "7z") {
            when (s.sevenZipOpenAction) {
                SevenZipOpenAction.MENU -> ArchiveTapAction.SHOW_MENU
                SevenZipOpenAction.EXTRACT -> ArchiveTapAction.EXTRACT
                SevenZipOpenAction.OPEN_WITH -> ArchiveTapAction.OPEN_WITH
            }
        } else {
            when (s.zipOpenAction) {
                ZipOpenAction.MENU -> ArchiveTapAction.SHOW_MENU
                ZipOpenAction.EXTRACT -> ArchiveTapAction.EXTRACT
                ZipOpenAction.OPEN_WITH -> ArchiveTapAction.OPEN_WITH
            }
        }
    }

    fun setFilter(mode: FilterMode) {
        _state.update { it.copy(filterMode = mode) }
    }

    fun requestToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    /**
     * Real Android share: the screen hands the given files to the system share sheet via
     * ACTION_SEND / ACTION_SEND_MULTIPLE (FileProvider URIs, see [com.toadfiles.app.util.Intents]).
     * The user picks the target app; toadFILES itself uploads nothing. The manifest's
     * INTERNET permission exists solely for the user-initiated "Check for updates" flow
     * (GitHub releases check + APK download) — no background network, analytics or telemetry.
     */
    fun shareFiles(files: List<File>) {
        if (files.none { it.isFile }) {
            _state.update { it.copy(toast = "Nothing shareable selected") }
        }
    }

    /** Quick toggle for hidden files, reachable straight from the Browse top bar. */
    fun toggleHiddenFiles() {
        viewModelScope.launch {
            settingsRepo.setShowHidden(!_state.value.settings.showHidden)
        }
    }

    fun clearClipboard() {
        container.clipboard.clear()
    }

    fun copyToClipboard(mode: Clipboard.Mode, files: List<File>) {
        container.clipboard.set(mode, files)
        _state.update {
            it.copy(
                toast = "${files.size} item(s) ready to " +
                    (if (mode == Clipboard.Mode.COPY) "copy" else "move"),
                selection = emptySet(),
            )
        }
    }

    fun setSortOrder(order: com.toadfiles.app.domain.SortOrder) {
        viewModelScope.launch { settingsRepo.setSortOrder(order) }
    }

    fun toggleLayout() {
        val next = if (_state.value.settings.browseLayout == BrowseLayout.GRID) BrowseLayout.LIST else BrowseLayout.GRID
        viewModelScope.launch { settingsRepo.setBrowseLayout(next) }
    }

    // ---------- favorites ----------

    /** Pins/unpins a folder for Home's Quick access (folder action sheet). */
    fun toggleQuickPin(path: String) {
        viewModelScope.launch {
            val wasPinned = path in _state.value.quickPins
            settingsRepo.toggleQuickPin(path)
            _state.update {
                it.copy(
                    toast = if (wasPinned) "Removed from Quick access"
                    else "Pinned to Quick access on Home",
                )
            }
        }
    }

    fun toggleFavorite(path: String) {
        viewModelScope.launch { settingsRepo.toggleFavorite(path) }
    }

    // ---------- new folder / rename ----------

    fun requestNewFolder() = _state.update { it.copy(pendingNewFolder = true) }

    fun createFolder(name: String) {
        val dir = _state.value.dir ?: return
        val created = fileRepo.newFolder(dir, name)
        _state.update {
            it.copy(
                pendingNewFolder = false,
                toast = if (created != null) "Folder \"${created.name}\" created"
                else "Could not create folder \"${name.trim()}\"",
            )
        }
        load(dir)
    }

    fun requestRename(file: File) = _state.update { it.copy(pendingRename = file) }

    fun rename(file: File, newName: String) {
        viewModelScope.launch(ioDispatcher) {
            val dest = FileOps.rename(file, newName)
            if (dest != null) remapSavedPaths(file.path, dest.path)
            _state.update {
                it.copy(
                    pendingRename = null,
                    toast = when {
                        dest == null -> "Rename failed (name taken or invalid)"
                        else -> "Renamed to \"${dest.name}\""
                    },
                )
            }
            _state.value.dir?.let { d -> load(d) }
        }
    }

    /**
     * Everything saved against (or under) a renamed/moved path keeps working: favorites,
     * Quick access pins, hidden marks and "most used" folder-use counters are remapped
     * in one atomic transaction (see [SettingsRepository.remapSavedPaths]).
     */
    private suspend fun remapSavedPaths(oldPath: String, newPath: String) {
        settingsRepo.remapSavedPaths(oldPath, newPath)
    }

    private suspend fun remapMovedFavorites(sources: List<File>, target: File) {
        for (src in sources) {
            if (src.exists()) continue
            val dest = File(target, src.name)
            if (dest.exists()) remapSavedPaths(src.path, dest.path)
        }
    }

    // ---------- delete ----------

    fun requestDelete(files: List<File>) = _state.update { it.copy(pendingDelete = files) }

    fun confirmDelete() {
        val files = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null, progress = "Deleting" to OpProgress(0, files.size, "")) }
        opJob = viewModelScope.launch(ioDispatcher) {
            val roots = container.fileRepo.roots().map { it.dir }
            val result = Trash.trash(files, roots)
            val failed = files.count { it.exists() }
            _state.update {
                it.copy(
                    progress = null,
                    lastTrashed = result.moved.takeIf { t -> t.isNotEmpty() },
                    toast = when {
                        result.moved.isEmpty() -> "Could not move anything to trash"
                        failed > 0 -> "Moved ${result.moved.size} of ${files.size} to trash ($failed failed)"
                        else -> null // the undo snackbar reports success instead
                    },
                )
            }
            refreshTrashCount()
            _state.value.dir?.let { d -> load(d) }
        }
    }

    /** Restores the most recent delete batch (snackbar "Undo"). */
    fun undoDelete() {
        val trashed = _state.value.lastTrashed ?: return
        _state.update { it.copy(lastTrashed = null) }
        opJob = viewModelScope.launch(ioDispatcher) {
            var restored = 0
            for (t in trashed) if (Trash.restore(t) != null) restored++
            _state.update {
                it.copy(
                    toast = if (restored == trashed.size) "Restored $restored item(s)"
                    else "Restored $restored of ${trashed.size} — the rest stays in Trash"
                )
            }
            refreshTrashCount()
            _state.value.dir?.let { d -> load(d) }
        }
    }

    fun dismissUndo() = _state.update { it.copy(lastTrashed = null) }

    // ---------- copy / move (paste) ----------

    fun pasteFromClipboard() {
        val clip = _state.value.clipboard ?: return
        val dir = _state.value.dir ?: return
        viewModelScope.launch(ioDispatcher) {
            val conflicts = clip.files.filter { File(dir, it.name).exists() && it.parentFile != dir }.map { it.name }
            if (conflicts.isEmpty()) {
                startOp(clip.mode.toKind(), dir, clip.files, ConflictStrategy.RENAME)
            } else {
                _state.update {
                    it.copy(pendingConflict = PendingConflict(clip.mode.toKind(), dir, clip.files, conflicts))
                }
            }
        }
    }

    private fun Clipboard.Mode.toKind() = when (this) {
        Clipboard.Mode.COPY -> OpKind.COPY
        Clipboard.Mode.MOVE -> OpKind.MOVE
    }

    fun resolveConflict(strategy: ConflictStrategy) {
        val pc = _state.value.pendingConflict ?: return
        _state.update { it.copy(pendingConflict = null) }
        startOp(pc.op, pc.targetDir, pc.sources, strategy)
    }

    private fun startOp(kind: OpKind, target: File, sources: List<File>, strategy: ConflictStrategy) {
        try {
            PathSafety.rejectSelfTarget(sources, target)
        } catch (e: IOException) {
            _state.update { it.copy(toast = e.message) }
            return
        }
        _state.update {
            it.copy(
                progress = label(kind) to OpProgress(
                    0,
                    if (kind == OpKind.ZIP_EXTRACT) ArchiveOps.entryCount(sources.first()) ?: 1 else sources.size,
                    "",
                ),
            )
        }
        var extractDest: File? = null
        runOp(label(kind), { onP ->
            when (kind) {
                OpKind.COPY -> FileOps.copy(sources, target, strategy, onP)
                OpKind.MOVE -> FileOps.move(sources, target, strategy, onP)
                OpKind.ZIP_EXTRACT -> {
                    // "Keep both" for extraction means a fresh sibling destination
                    // ("unpacked (1)", …); SKIP merges into the existing folder and only
                    // skips colliding entries; REPLACE overwrites them.
                    val dest = if (strategy == ConflictStrategy.RENAME) {
                        val parent = target.parentFile ?: target
                        File(parent, PathSafety.uniqueName(parent, target.name))
                    } else target
                    extractDest = dest
                    ArchiveOps.extract(sources.first(), dest, strategy == ConflictStrategy.REPLACE, onProgress = onP)
                }
            }
        }) { r ->
            if (kind == OpKind.MOVE && r.succeeded > 0) remapMovedFavorites(sources, target)
            // Post-extract redirect (v1.7.1): navigate into the extracted folder.
            if (kind == OpKind.ZIP_EXTRACT && r.succeeded > 0) {
                extractDest?.let { d -> _events.tryEmit(ExtractEvent.ExtractCompleted(d)) }
            }
        }
        // Extraction doesn't touch the clipboard — don't drop a pending paste batch.
        if (kind != OpKind.ZIP_EXTRACT) container.clipboard.clear()
    }

    private fun label(kind: OpKind) = when (kind) {
        OpKind.COPY -> "Copying"
        OpKind.MOVE -> "Moving"
        OpKind.ZIP_EXTRACT -> "Extracting"
    }

    private fun runOp(
        title: String,
        op: suspend (onP: (OpProgress) -> Unit) -> OpResult,
        onDone: suspend (OpResult) -> Unit = {},
    ) {
        opJob = viewModelScope.launch(ioDispatcher) {
            val r = op { p -> _state.update { it.copy(progress = title to p) } }
            _state.update { it.copy(progress = null, result = r, resultTitle = title) }
            onDone(r)
            _state.value.dir?.let { d -> load(d) }
        }
    }

    fun cancelOp() {
        // Cooperative cancellation: cancelling the launched op job.
        opJob?.cancel()
        _state.update { it.copy(progress = null) }
    }

    private var opJob: Job? = null

    /** Last (showHidden, sortMode, sortOrder) that triggered a re-list. */
    private var lastListSettings: Triple<Boolean, SortMode, com.toadfiles.app.domain.SortOrder>? = null

    // ---------- zip ----------

    /** Opens the archive-name dialog for a batch (UI flow). */
    fun requestZip(files: List<File>) {
        _state.update { it.copy(pendingZipFiles = files, pendingZipName = suggestedZipName(files)) }
    }

    fun dismissZipDialog() = _state.update { it.copy(pendingZipFiles = null, pendingZipName = "") }

    /** Suggested archive name: the single item's name, or "archive.zip" for batches. */
    fun suggestedZipName(files: List<File>): String = when (files.size) {
        1 -> {
            val n = files[0].name
            if (n.lowercase().endsWith(".zip")) n else files[0].nameWithoutExtension + ".zip"
        }
        else -> "archive.zip"
    }

    /** Zips [files] under the user-typed name (".zip" appended when missing, collisions deduped). */
    fun confirmZip(name: String) {
        val files = _state.value.pendingZipFiles ?: return
        val dir = _state.value.dir ?: return
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
            _state.update { it.copy(toast = "Enter a valid archive name") }
            return
        }
        _state.update { it.copy(pendingZipFiles = null, pendingZipName = "") }
        val base = if (clean.lowercase().endsWith(".zip")) clean else "$clean.zip"
        zipTo(files, File(dir, PathSafety.uniqueName(dir, base)))
    }

    /** Quick zip with the classic auto name (tests; UI goes through the name dialog). */
    fun zipSelected(files: List<File>) {
        val dir = _state.value.dir ?: return
        zipTo(files, File(dir, PathSafety.uniqueName(dir, "archive.zip")))
    }

    private fun zipTo(files: List<File>, out: File) {
        val dir = _state.value.dir ?: return
        _state.update { it.copy(progress = "Zipping" to OpProgress(0, files.size, "")) }
        opJob = viewModelScope.launch(ioDispatcher) {
            val r = ArchiveOps.zip(files, out) { p -> _state.update { it.copy(progress = "Zipping" to p) } }
            _state.update { it.copy(progress = null, result = r, resultTitle = "Zipping") }
            load(dir)
        }
    }

    /**
     * Extracts an archive (.zip or .7z) into a folder named after it next to it — or,
     * when [targetName] is set ("Extract to…"), into a folder with that name under the
     * current directory. The default flow refuses an existing destination (as before);
     * the named flow asks via the shared conflict dialog: Replace overwrites colliding
     * entries, Keep both extracts into a uniquely named sibling, Skip merges without
     * overwriting.
     */
    fun extractZip(file: File, targetName: String? = null) {
        if (targetName == null) {
            val dir = file.parentFile ?: return
            val target = File(dir, file.nameWithoutExtension)
            if (target.exists()) {
                _state.update {
                    it.copy(toast = "A folder named \"${target.name}\" already exists. Rename or move it before extracting.")
                }
                return
            }
            startExtract(file, target)
        } else {
            val dir = _state.value.dir ?: return
            val clean = targetName.trim()
            if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
                _state.update { it.copy(toast = "Enter a valid folder name") }
                return
            }
            val target = File(dir, clean)
            if (target.exists()) {
                // Same collision flow as paste: the user picks the strategy.
                _state.update {
                    it.copy(pendingConflict = PendingConflict(OpKind.ZIP_EXTRACT, target, listOf(file), listOf(clean)))
                }
            } else {
                startExtract(file, target)
            }
        }
    }

    /** Runs the ArchiveOps extract with Browse's standard progress/result plumbing and
     *  emits an [ExtractEvent.ExtractCompleted] one-shot so the screen can navigate into
     *  the extracted folder (v1.7.1 post-extract redirect). */
    private fun startExtract(file: File, target: File) {
        _state.update {
            it.copy(progress = "Extracting" to OpProgress(0, ArchiveOps.entryCount(file) ?: 1, ""))
        }
        opJob = viewModelScope.launch(ioDispatcher) {
            val r = ArchiveOps.extract(file, target, false) { p ->
                _state.update { it.copy(progress = "Extracting" to p) }
            }
            _state.update { it.copy(progress = null, result = r, resultTitle = "Extracting") }
            if (r.succeeded > 0) _events.tryEmit(ExtractEvent.ExtractCompleted(target))
            load(target.parentFile ?: return@launch)
        }
    }

    // ---------- properties ----------

    /**
     * Opens the read-only properties sheet for [file] and starts the computation on the IO
     * dispatcher: size via [FileOps.sizeOf] (the app's single size engine) and, for folders,
     * content counts via [PropertiesBuilder]. Dismissing cancels the in-flight computation;
     * a late result for a superseded request is dropped — only the item the sheet currently
     * shows may apply its detail.
     */
    fun requestProperties(file: File) {
        _state.update { it.copy(pendingProperties = file, propertiesDetail = null) }
        propertiesJob?.cancel()
        propertiesJob = viewModelScope.launch(ioDispatcher) {
            val detail = computePropertiesDetail(file)
            coroutineContext.ensureActive() // dismissed/superseded mid-walk: drop the result
            _state.update {
                if (it.pendingProperties?.path == file.path) {
                    it.copy(propertiesDetail = detail)
                } else it
            }
        }
    }

    fun dismissProperties() {
        propertiesJob?.cancel()
        _state.update { it.copy(pendingProperties = null, propertiesDetail = null) }
    }

    /** Blocking FS walks (size, counts) run inside the IO-dispatched properties job; the
     *  computation itself is the shared [computeFilePropertiesDetail] (v1.7.3: Categories
     *  runs the exact same engine for its properties sheet). */
    private suspend fun computePropertiesDetail(file: File): PropertiesDetail =
        computeFilePropertiesDetail(file, propertiesBuilder)

    // ---------- misc ----------

    fun consumeToast() = _state.update { it.copy(toast = null) }
    fun dismissResult() = _state.update { it.copy(result = null) }
    fun dismissNewFolder() = _state.update { it.copy(pendingNewFolder = false) }
    fun dismissRename() = _state.update { it.copy(pendingRename = null) }
    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }
    fun dismissConflict() = _state.update { it.copy(pendingConflict = null) }

    override fun onCleared() {
        searchJob?.cancel()
        opJob?.cancel()
        propertiesJob?.cancel()
        super.onCleared()
    }
}
