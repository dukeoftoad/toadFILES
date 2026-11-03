package com.toadfiles.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.data.FileRepository
import com.toadfiles.app.data.MediaQuerySource
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
import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.components.PendingPasswordRetry
import com.toadfiles.app.ui.components.PropertiesDetail
import com.toadfiles.app.ui.components.VolumeChoice
import com.toadfiles.app.ui.components.customVolumeTextToBytes
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
    /** Last delete batch (trash payload files) offered to the snackbar's Undo action.
     *  One-shot (D2): the screen consumes it the moment the snackbar shows it, so
     *  navigating away mid-window can never resurrect an ancient undo later. */
    val lastTrashed: List<File>? = null,
    /** Bumped whenever a new undo offer is made. The snackbar effect keys on this id
     *  (not on [lastTrashed]) so consuming the offer — nulling [lastTrashed] — can
     *  never cancel the snackbar that is still showing. */
    val undoOfferSeq: Long = 0,
    // dialogs
    val pendingNewFolder: Boolean = false,
    val pendingRename: File? = null,
    val pendingDelete: List<File>? = null,
    /** Batch waiting for the user in the compress dialog: name, format, level and
     *  destination are chosen there (v1.7.3 format/level chooser). */
    val pendingZipFiles: List<File>? = null,
    val pendingZipName: String = "",
    val pendingZipFormat: ArchiveFormat = ArchiveFormat.ZIP,
    val pendingZipLevel: CompressLevel = CompressLevel.NORMAL,
    /** Folder the new archive will land in: the archive-folder setting when set,
     *  else the current folder. Shown in the compress dialog. */
    val pendingZipDestination: File? = null,
    /** Existing archive target awaiting the overwrite confirmation. */
    val pendingZipOverwrite: File? = null,
    /** 7z data encryption (AES-256) — 7z only; ZIP creation cannot be encrypted. */
    val pendingZipEncrypted: Boolean = false,
    /** Typed password pair for the encrypted-7z flow; blanked whenever encryption turns off. */
    val pendingZipPassword: String = "",
    val pendingZipPasswordConfirm: String = "",
    /** Split-into-volumes size in bytes; null = no split (see [setZipVolume]). */
    val pendingZipVolumeBytes: Long? = null,
    /** True while the Custom… volume option is selected; [pendingZipVolumeCustomText] then holds the megabytes. */
    val pendingZipVolumeCustom: Boolean = false,
    val pendingZipVolumeCustomText: String = "",
    /** Per-run override of delete-after-compress (initialized from the persisted setting). */
    val pendingZipDeleteSources: Boolean = false,
    /** One archive per source instead of a single batch archive; offered for count > 1. */
    val pendingZipSeparate: Boolean = false,
    /** Encrypted archive whose extraction is waiting for a password (drives PasswordPromptDialog). */
    val pendingPasswordFor: File? = null,
    /** Retry context for [pendingPasswordFor]: same target dir / replace semantics as the original call. */
    val pendingPasswordRetry: PendingPasswordRetry? = null,
    val pendingConflict: PendingConflict? = null,
    val progress: Pair<String, OpProgress>? = null,
    val result: OpResult? = null,
    val resultTitle: String = "",
    /** Item whose read-only properties sheet is open; null when closed. */
    val pendingProperties: File? = null,
    /** Computed size/contents for the properties sheet; null while the walk is running. */
    val propertiesDetail: PropertiesDetail? = null,
    val toast: String? = null,
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
    /** Injectable for tests; production uses the container's MediaStore-backed repository
     *  (an undo re-scans the restored files back into the media index). */
    private val media: MediaQuerySource = container.mediaRepo,
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

    /** Monotonic id of the current undo offer; see [BrowseUiState.undoOfferSeq]. */
    private var undoOfferId = 0L

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
    }

    fun openDir(dir: File) {
        // Count visits so Home's Quick access can surface most-used folders — but only
        // for folders the user actually browses: the app's own trash dir at a volume
        // root never qualifies, while a user folder with the same name deeper in the
        // tree does (D11). In-memory only — the repository batches the disk write.
        if (dir.isDirectory && !isOwnTrashDir(dir)) {
            viewModelScope.launch { settingsRepo.recordFolderUse(dir.absolutePath) }
        }
        load(dir)
    }

    /** True only for the real trash dir at a volume root. The roots are only consulted
     *  when the name matches, so normal navigation pays nothing extra. */
    private fun isOwnTrashDir(dir: File): Boolean =
        dir.name == Trash.DIR_NAME && fileRepo.roots().any { it.dir == dir.parentFile }

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
        // 7z archives AND 7z volume sets (.7z.001) honor the 7z tap setting; everything
        // else extractable (.zip, .zip.001 volume starts) honors the zip setting.
        // A volume's later parts (.002, …) are not extractable entry points and never
        // reach here ([ArchiveOps.canExtract] is the gate in the screens).
        val is7z = ArchiveOps.extension(fileName) == "7z" ||
            fileName.lowercase().endsWith(".7z.001")
        return if (is7z) {
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
        // No arming toast: the TransferBar is the immediate, persistent feedback
        // (a second Snackbar for the same event was visual noise).
        _state.update { it.copy(selection = emptySet()) }
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
            _state.update {
                it.copy(
                    progress = null,
                    lastTrashed = result.moved.takeIf { t -> t.isNotEmpty() },
                    undoOfferSeq = ++undoOfferId,
                    toast = when {
                        result.moved.isEmpty() -> "Could not move anything to trash"
                        result.failureCount > 0 ->
                            "Moved ${result.moved.size} of ${files.size} to trash (${result.failureCount} failed)"
                        else -> null // the undo snackbar reports success instead
                    },
                )
            }
            _state.value.dir?.let { d -> load(d) }
        }
    }

    /**
     * Restores a delete batch (snackbar "Undo") from the captured payload list.
     * Honest accounting (N5): items already gone from the trash are reported as
     * "already gone", collision renames are counted (D4), and restored files are
     * re-scanned into the media index (D1).
     */
    fun undoDelete(trashed: List<File>) {
        if (trashed.isEmpty()) return
        opJob = viewModelScope.launch(ioDispatcher) {
            var restored = 0
            var renamed = 0
            var gone = 0
            val restoredPaths = mutableListOf<String>()
            for (t in trashed) {
                // exists() follows symlinks — a dangling-link payload still restores (D7).
                if (!t.exists() && !Files.isSymbolicLink(t.toPath())) {
                    gone++
                    continue
                }
                val origin = Trash.originOf(t)
                val dest = Trash.restore(t) ?: continue // stays in the trash, still restorable
                restored++
                if (origin != null && dest.name != origin.substringAfterLast('/')) renamed++
                restoredPaths += dest.absolutePath
            }
            media.rescan(restoredPaths)
            val renamedNote = if (renamed > 0) ", $renamed renamed" else ""
            _state.update {
                it.copy(
                    toast = when {
                        gone > 0 -> "Restored $restored of ${trashed.size} — $gone already gone$renamedNote"
                        restored < trashed.size ->
                            "Restored $restored of ${trashed.size} — the rest stays in Trash$renamedNote"
                        else -> "Restored $restored item(s)$renamedNote"
                    },
                )
            }
            _state.value.dir?.let { d -> load(d) }
        }
    }

    /**
     * One-shot undo offer (D2): consumed the moment the snackbar shows it, so a
     * navigation mid-window can never leave a stale offer in the retained ViewModel
     * that resurrects (and lies about "restoring") on the next visit.
     */
    fun consumeUndoOffer() = _state.update { it.copy(lastTrashed = null) }

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

    /**
     * Non-null when Paste must be disabled in the current directory: a MOVE whose
     * destination is a selected folder itself or anything inside it would nest a tree
     * under itself. COPY is always allowed (the conflict dialog owns naming there).
     */
    fun transferBlockedReason(): String? {
        val clip = _state.value.clipboard ?: return null
        if (clip.mode != Clipboard.Mode.MOVE) return null
        val dir = _state.value.dir ?: return null
        val dirPath = dir.absolutePath.trimEnd('/')
        return clip.files.firstOrNull { f ->
            val p = f.absolutePath.trimEnd('/')
            dirPath == p || dirPath.startsWith(p + File.separator)
        }?.let { "You can't move a folder into itself" }
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
        // ZIP_EXTRACT names the archive in the overlay; resultTitle stays "Extracting".
        val progressTitle = if (kind == OpKind.ZIP_EXTRACT) {
            "Extracting ${sources.first().name}"
        } else {
            label(kind)
        }
        _state.update {
            it.copy(
                progress = progressTitle to OpProgress(
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
                    ArchiveOps.extract(sources.first(), dest, strategy == ConflictStrategy.REPLACE, password = null, onProgress = onP)
                }
            }
        }, progressTitle) { r ->
            if (kind == OpKind.MOVE && r.succeeded > 0) remapMovedFavorites(sources, target)
            // Post-extract redirect (v1.7.1): navigate into the extracted folder.
            if (kind == OpKind.ZIP_EXTRACT) {
                if (r.passwordRequired) {
                    // Encrypted archive: ask for the password instead of reporting a
                    // failure. Retry keeps this run's own target dir / replace choice.
                    _state.update {
                        it.copy(
                            result = null,
                            pendingPasswordFor = sources.first(),
                            pendingPasswordRetry = PendingPasswordRetry(
                                sources.first(),
                                extractDest ?: target,
                                strategy == ConflictStrategy.REPLACE,
                            ),
                        )
                    }
                } else if (r.succeeded > 0) {
                    extractDest?.let { d -> _events.tryEmit(ExtractEvent.ExtractCompleted(d)) }
                    // v1.7.3 delete-after-extract (conflict-dialog path); the media
                    // flows (Recent/Categories) mirror this via trashArchiveAfterExtract.
                    // [OpResult.fullySucceeded] gates the trash: a partially failed or
                    // aborted extraction keeps the archive — the extraction is incomplete.
                    if (r.fullySucceeded && _state.value.settings.deleteAfterExtract) {
                        trashSourcesAfterArchiveOp(sources)
                    }
                }
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
        progressTitle: String = title,
        onDone: suspend (OpResult) -> Unit = {},
    ) {
        opJob = viewModelScope.launch(ioDispatcher) {
            val r = op { p -> _state.update { it.copy(progress = progressTitle to p) } }
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

    // ---------- compress (zip + 7z) ----------

    /** Default destination for new archives and "Extract to…" (v1.7.3): the user's
     *  archive folder when one is set, else [fallback] (the current folder). A blank
     *  setting keeps the v1.7.2 behavior — same folder as the source. */
    fun archiveFolderOr(fallback: File?): File? {
        val folder = _state.value.settings.archiveFolder.trim()
        return if (folder.isNotEmpty()) File(folder) else fallback
    }

    /** Opens the compress dialog for a batch (UI flow). The delete-sources checkbox
     *  starts as the persisted deleteAfterCompress setting — a per-run override from
     *  there on, not a second read at confirm time. */
    fun requestZip(files: List<File>) {
        _state.update {
            it.copy(
                pendingZipFiles = files,
                pendingZipName = suggestedZipName(files),
                pendingZipFormat = ArchiveFormat.ZIP,
                pendingZipLevel = CompressLevel.NORMAL,
                pendingZipDestination = archiveFolderOr(it.dir),
                pendingZipEncrypted = false,
                pendingZipPassword = "",
                pendingZipPasswordConfirm = "",
                pendingZipVolumeBytes = null,
                pendingZipVolumeCustom = false,
                pendingZipVolumeCustomText = "",
                pendingZipDeleteSources = it.settings.deleteAfterCompress,
                pendingZipSeparate = false,
            )
        }
    }

    fun dismissZipDialog() = _state.update {
        it.copy(
            pendingZipFiles = null, pendingZipName = "", pendingZipOverwrite = null,
            pendingZipEncrypted = false, pendingZipPassword = "", pendingZipPasswordConfirm = "",
            pendingZipVolumeBytes = null, pendingZipVolumeCustom = false, pendingZipVolumeCustomText = "",
            pendingZipDeleteSources = false, pendingZipSeparate = false,
        )
    }

    /** Compress-dialog state setters (the dialog's fields are backed by the VM). */
    fun setZipName(name: String) = _state.update { it.copy(pendingZipName = name) }

    /** commons-compress cannot create encrypted zip: switching the format to ZIP also
     *  drops the encryption choice and both password fields. */
    fun setZipFormat(format: ArchiveFormat) = _state.update {
        if (format == ArchiveFormat.ZIP &&
            (it.pendingZipEncrypted || it.pendingZipPassword.isNotEmpty() || it.pendingZipPasswordConfirm.isNotEmpty())
        ) {
            it.copy(
                pendingZipFormat = format,
                pendingZipEncrypted = false,
                pendingZipPassword = "",
                pendingZipPasswordConfirm = "",
            )
        } else it.copy(pendingZipFormat = format)
    }

    fun setZipLevel(level: CompressLevel) = _state.update { it.copy(pendingZipLevel = level) }
    fun setZipEncrypted(on: Boolean) = _state.update { it.copy(pendingZipEncrypted = on) }
    fun setZipPassword(pw: String) = _state.update { it.copy(pendingZipPassword = pw) }
    fun setZipPasswordConfirm(pw: String) = _state.update { it.copy(pendingZipPasswordConfirm = pw) }

    /** Applies a "Split into volumes" pick: presets map straight to bytes, Custom…
     *  keeps the draft text field as the source of truth (see [resolveVolumeBytes]). */
    fun setZipVolume(choice: VolumeChoice) = _state.update {
        it.copy(
            pendingZipVolumeBytes = choice.bytes,
            pendingZipVolumeCustom = choice == VolumeChoice.CUSTOM,
            pendingZipVolumeCustomText = if (choice == VolumeChoice.CUSTOM) it.pendingZipVolumeCustomText else "",
        )
    }

    fun setZipVolumeCustomText(text: String) = _state.update { it.copy(pendingZipVolumeCustomText = text) }
    fun setZipDeleteSources(on: Boolean) = _state.update { it.copy(pendingZipDeleteSources = on) }
    fun setZipSeparate(on: Boolean) = _state.update { it.copy(pendingZipSeparate = on) }

    /** Effective volume size: the picked preset, or Custom… parsed from its megabytes
     *  text (blank/invalid/non-positive ⇒ no split). */
    fun resolveVolumeBytes(): Long? {
        val st = _state.value
        return if (st.pendingZipVolumeCustom) customVolumeTextToBytes(st.pendingZipVolumeCustomText)
        else st.pendingZipVolumeBytes
    }

    /** Suggested archive name: the single item's name, or "archive.zip" for batches. */
    fun suggestedZipName(files: List<File>): String = when (files.size) {
        1 -> {
            val n = files[0].name
            if (n.lowercase().endsWith(".zip")) n else files[0].nameWithoutExtension + ".zip"
        }
        else -> "archive.zip"
    }

    /** Strips a foreign archive extension (".zip"/".7z") and appends the chosen
     *  format's own, so switching format in the dialog renames the suggestion honestly. */
    fun normalizeArchiveName(name: String, format: ArchiveFormat): String {
        val lower = name.lowercase()
        val base = when {
            lower.endsWith(".zip") -> name.dropLast(4)
            lower.endsWith(".7z") -> name.dropLast(3)
            else -> name
        }
        return "$base.${format.extension}"
    }

    /**
     * Compresses [name] under the dialog's format/level/destination. Validations as
     * before (non-blank name, no path separators) plus the password rules when data
     * encryption is on. When a target already exists — the archive itself, or with
     * volumes on the first volume of an existing set ("<name>.<ext>.001") — the
     * overwriteConfirm setting decides: ask first (Cancel returns to the dialog) or
     * replace silently.
     */
    fun confirmZip(name: String) {
        val st = _state.value
        val files = st.pendingZipFiles ?: return
        val dest = st.pendingZipDestination ?: st.dir ?: return
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
            _state.update { it.copy(toast = "Enter a valid archive name") }
            return
        }
        val encrypted = st.pendingZipEncrypted && st.pendingZipFormat == ArchiveFormat.SEVEN_Z
        if (encrypted) {
            if (st.pendingZipPassword.isBlank()) {
                _state.update { it.copy(toast = "Enter a password") }
                return
            }
            if (st.pendingZipPassword != st.pendingZipPasswordConfirm) {
                _state.update { it.copy(toast = "Passwords do not match") }
                return
            }
        }
        // The typed name is what the run uses; sync it so an overwrite confirm can
        // recompute the exact same plan without carrying a second copy of the name.
        _state.update { it.copy(pendingZipName = clean) }
        val hit = plannedTargets().firstOrNull(::collidesWith)
        if (hit != null && st.settings.overwriteConfirm) {
            _state.update { it.copy(pendingZipOverwrite = hit) }
            return
        }
        runConfirmedZip()
    }

    /** One planned output file per run: the single batch archive, or — when "separate
     *  archive for each item" is on — one per source, named after the source. Reads the
     *  LIVE state: callers run it after the typed name has been synced into
     *  pendingZipName, so the single-mode target is exactly what the user typed. */
    private fun plannedTargets(): List<File> {
        val st = _state.value
        val files = st.pendingZipFiles ?: return emptyList()
        val dest = st.pendingZipDestination ?: st.dir ?: return emptyList()
        val separate = st.pendingZipSeparate && files.size > 1
        return if (separate) {
            files.map { src -> File(dest, normalizeArchiveName(src.nameWithoutExtension, st.pendingZipFormat)) }
        } else {
            listOf(File(dest, normalizeArchiveName(st.pendingZipName.trim(), st.pendingZipFormat)))
        }
    }

    /** A target collides when it exists — or, with volumes on, when the first volume
     *  of an existing set ("<name>.<ext>.001") does. */
    private fun collidesWith(target: File): Boolean {
        if (target.exists()) return true
        return resolveVolumeBytes() != null &&
            File(target.parentFile ?: target, "${target.name}.001").exists()
    }

    /** Overwrite confirmed: publish over the colliding target(s) after all. */
    fun confirmZipOverwrite() {
        if (_state.value.pendingZipOverwrite == null) return
        runConfirmedZip()
    }

    /** Overwrite declined: back to the compress dialog so the user can pick a new name. */
    fun dismissZipOverwrite() = _state.update { it.copy(pendingZipOverwrite = null) }

    /**
     * Runs the confirmed plan: one archive (or one per source), with the dialog's
     * encryption, volume size and delete-sources choice. Clears the dialog state —
     * passwords included, they are never left in state — before the op starts.
     */
    private fun runConfirmedZip() {
        val st = _state.value
        val files = st.pendingZipFiles ?: return
        val dest = st.pendingZipDestination ?: st.dir ?: return
        val targets = plannedTargets()
        val encrypted = st.pendingZipEncrypted && st.pendingZipFormat == ArchiveFormat.SEVEN_Z
        val password: CharArray? = if (encrypted) st.pendingZipPassword.toCharArray() else null
        val volumeBytes = resolveVolumeBytes()
        val deleteSources = st.pendingZipDeleteSources
        _state.update {
            it.copy(
                pendingZipFiles = null, pendingZipName = "", pendingZipOverwrite = null,
                pendingZipEncrypted = false, pendingZipPassword = "", pendingZipPasswordConfirm = "",
                pendingZipVolumeBytes = null, pendingZipVolumeCustom = false, pendingZipVolumeCustomText = "",
                pendingZipDeleteSources = false, pendingZipSeparate = false,
            )
        }
        zipAll(files, targets, st.pendingZipFormat, st.pendingZipLevel, password, volumeBytes, deleteSources)
    }

    /** Quick zip with the classic auto name (tests; UI goes through the compress dialog). */
    fun zipSelected(files: List<File>) {
        val dir = _state.value.dir ?: return
        zipTo(files, File(dir, PathSafety.uniqueName(dir, "archive.zip")))
    }

    private fun zipTo(
        files: List<File>,
        out: File,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        level: CompressLevel = CompressLevel.NORMAL,
        password: CharArray? = null,
        volumeBytes: Long? = null,
        deleteSources: Boolean = _state.value.settings.deleteAfterCompress,
    ) {
        zipAll(files, listOf(out), format, level, password, volumeBytes, deleteSources)
    }

    /**
     * The one compress engine (v1.8.0): runs [targets] sequentially — a single archive,
     * or one per source for "separate archive for each item" — with the same format,
     * level, password and volume size for each, and aggregates the per-archive results
     * into one combined [OpResult] (summed counts; cancelled if any run was cancelled;
     * failures concatenated). Progress ticks across archives stay monotonic: the running
     * base of finished archives is added to each tick.
     */
    private fun zipAll(
        files: List<File>,
        targets: List<File>,
        format: ArchiveFormat,
        level: CompressLevel,
        password: CharArray?,
        volumeBytes: Long?,
        deleteSources: Boolean,
    ) {
        val dir = _state.value.dir
        val separate = targets.size > 1
        val title = if (format == ArchiveFormat.SEVEN_Z) "Compressing" else "Zipping"
        _state.update { it.copy(progress = title to OpProgress(0, files.size, "")) }
        opJob = viewModelScope.launch(ioDispatcher) {
            var succeeded = 0
            var skipped = 0
            var failed = 0
            var cancelled = false
            val failures = mutableListOf<String>()
            var base = 0
            for (i in targets.indices) {
                val srcs = if (separate) listOf(files[i]) else files
                val r = ArchiveOps.compress(srcs, targets[i], format, level, password = password, volumeBytes = volumeBytes) { p ->
                    _state.update { it.copy(progress = title to OpProgress(base + p.done, files.size, p.currentItem)) }
                }
                succeeded += r.succeeded
                skipped += r.skipped
                failed += r.failed
                if (r.cancelled) cancelled = true
                failures += r.failures
                base += r.succeeded
                // Delete-after-compress honors the dialog checkbox (initialized from the
                // persisted setting): per archive, only a fully successful run trashes
                // that archive's own source — a failed sibling never deletes another's.
                if (deleteSources && r.fullySucceeded) trashSourcesAfterArchiveOp(srcs)
            }
            val combined = OpResult(
                succeeded = succeeded,
                skipped = skipped,
                failed = failed,
                cancelled = cancelled,
                failures = failures.distinct(),
            )
            _state.update { it.copy(progress = null, result = combined, resultTitle = title) }
            dir?.let { load(it) }
        }
    }

    /**
     * "Delete after archive op" follows the app's delete convention (checked): the
     * sources move to the toadFILES trash — 30-day retention, restorable, undoable via
     * [BrowseUiState.lastTrashed] — never a silent permanent erase. ZArchiver deletes
     * permanently; toadFILES deletes like its own Delete button does.
     */
    private suspend fun trashSourcesAfterArchiveOp(files: List<File>) {
        val live = files.filter { it.exists() }
        if (live.isEmpty()) return
        val roots = container.fileRepo.roots().map { it.dir }
        val result = Trash.trash(live, roots)
        _state.update {
            it.copy(
                lastTrashed = result.moved.takeIf { m -> m.isNotEmpty() },
                undoOfferSeq = ++undoOfferId,
                toast = if (result.failureCount > 0) {
                    "Could not delete ${result.failureCount} source item(s)"
                } else it.toast,
            )
        }
    }

    /**
     * Extracts an archive (.zip or .7z) into a folder named after it next to it — or,
     * when [targetName] is set ("Extract to…"), into a folder with that name under the
     * default destination: the user's archive folder when set, else the current
     * directory (v1.7.3). The default flow refuses an existing destination (as before);
     * the named flow asks via the shared conflict dialog: Replace overwrites colliding
     * entries, Keep both extracts into a uniquely named sibling, Skip merges without
     * overwriting.
     */
    fun extractZip(file: File, targetName: String? = null) {
        if (targetName == null) {
            val dir = file.parentFile ?: return
            val target = File(dir, file.nameWithoutExtension)
            if (target.exists()) {
                // Same collision flow as "Extract to…" and paste: the user picks the
                // strategy instead of hitting a dead-end toast (Keep both extracts into
                // a unique sibling, Replace/Skip merge into the existing folder).
                _state.update {
                    it.copy(
                        pendingConflict = PendingConflict(
                            OpKind.ZIP_EXTRACT, target, listOf(file), listOf(target.name),
                        ),
                    )
                }
                return
            }
            startExtract(file, target)
        } else {
            val dir = archiveFolderOr(_state.value.dir) ?: return
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
     *  the extracted folder (v1.7.1 post-extract redirect). v1.7.3: a successful
     *  extraction honors deleteAfterExtract (app delete convention: trash, undoable).
     *  v1.8.0: [password] retries an encrypted archive; an encrypted archive without a
     *  password comes back [OpResult.passwordRequired] and opens the password prompt
     *  instead of reporting a failure. */
    private fun startExtract(file: File, target: File, replace: Boolean = false, password: CharArray? = null) {
        // The overlay names the archive — "Extracting" alone is ambiguous when several
        // archives sit in the same folder. resultTitle stays the short "Extracting".
        val progressTitle = "Extracting ${file.name}"
        _state.update {
            it.copy(progress = progressTitle to OpProgress(0, ArchiveOps.entryCount(file) ?: 1, ""))
        }
        opJob = viewModelScope.launch(ioDispatcher) {
            val r = ArchiveOps.extract(file, target, replace, password = password) { p ->
                _state.update { it.copy(progress = progressTitle to p) }
            }
            if (r.passwordRequired) {
                // Encrypted archive: ask for the password, don't report a failure.
                _state.update {
                    it.copy(
                        progress = null, result = null,
                        pendingPasswordFor = file,
                        pendingPasswordRetry = PendingPasswordRetry(file, target, replace),
                    )
                }
                return@launch
            }
            _state.update { it.copy(progress = null, result = r, resultTitle = "Extracting") }
            if (r.succeeded > 0) {
                _events.tryEmit(ExtractEvent.ExtractCompleted(target))
                // The media flows (Recent/Categories) honor this too via
                // trashArchiveAfterExtract — same trash-based convention. Only a
                // fully successful extraction trashes the archive (partial failures
                // and aborts keep it: the extraction is incomplete).
                if (r.fullySucceeded && _state.value.settings.deleteAfterExtract) {
                    trashSourcesAfterArchiveOp(listOf(file))
                }
            }
            load(target.parentFile ?: return@launch)
        }
    }

    /** Retry hook for [PasswordPromptDialog]: re-runs the stored extract with the typed
     *  password, keeping the original target dir / replace semantics. A wrong password
     *  comes back as a normal failure result through the existing result dialog. */
    fun retryExtractWithPassword(password: String) {
        val ctx = _state.value.pendingPasswordRetry ?: return
        dismissPasswordPrompt()
        if (password.isBlank()) {
            _state.update { it.copy(toast = "Enter a password") }
            return
        }
        startExtract(ctx.archive, ctx.target, ctx.replace, password.toCharArray())
    }

    fun dismissPasswordPrompt() = _state.update {
        it.copy(pendingPasswordFor = null, pendingPasswordRetry = null)
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
