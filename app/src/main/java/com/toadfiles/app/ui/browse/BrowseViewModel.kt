package com.toadfiles.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.domain.PathSafety
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.Sorting
import com.toadfiles.app.domain.ZipSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class BrowseUiState(
    val dir: File? = null,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val breadcrumbs: List<File> = emptyList(),
    val selection: Set<File> = emptySet(),
    val query: String = "",
    val searching: Boolean = false,
    val filterMode: FilterMode = FilterMode.ALL,
    val settings: ToadSettings = ToadSettings(),
    val favoritePaths: Set<String> = emptySet(),
    val clipboard: Clipboard.Clip? = null,
    // dialogs
    val pendingNewFolder: Boolean = false,
    val pendingRename: File? = null,
    val pendingDelete: List<File>? = null,
    val pendingConflict: PendingConflict? = null,
    val progress: Pair<String, OpProgress>? = null,
    val result: OpResult? = null,
    val resultTitle: String = "",
    val properties: File? = null,
    val propertiesDetail: PropertiesDetail? = null,
    val toast: String? = null,
)

data class PendingConflict(val op: OpKind, val targetDir: File, val sources: List<File>, val conflicts: List<String>)

enum class OpKind { COPY, MOVE, ZIP_EXTRACT }

data class PropertiesDetail(val sizeText: String, val children: Int?)

@OptIn(FlowPreview::class)
class BrowseViewModel(private val container: AppContainer) : ViewModel() {

    private val fileRepo = container.fileRepo
    private val settingsRepo = container.settingsRepo

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state

    private var searchJob: Job? = null

    /** First directory opened in this session; up-navigation and breadcrumbs never go above it. */
    private var browseRoot: File? = null

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        favoritePaths = s.favorites,
                    )
                }
                _state.value.dir?.let { d -> load(d) }
            }
        }
        viewModelScope.launch {
            container.clipboard.clip.collect { c ->
                _state.update { it.copy(clipboard = c) }
            }
        }
    }

    fun openDir(dir: File) = load(dir)

    fun load(dir: File) {
        if (browseRoot == null) browseRoot = dir
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
        viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value.settings
            if (!dir.isDirectory || !dir.canRead()) {
                // Guard against stale results: only apply if this directory is still the current one.
                if (_state.value.dir?.path == dir.path) {
                    _state.update {
                        it.copy(loading = false, items = emptyList(), error = "Folder is not readable")
                    }
                }
                return@launch
            }
            val items = fileRepo.list(dir, s.showHidden)
                .sortedWith(Sorting.comparator(s.sortMode, s.sortOrder))
            if (_state.value.dir?.path == dir.path) {
                _state.update { it.copy(loading = false, items = items) }
            }
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
        _state.update { it.copy(query = q, searching = q.isNotBlank()) }
        searchJob?.cancel()
        val dir = _state.value.dir ?: return
        if (q.isBlank()) return
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // debounce
            val showHidden = _state.value.settings.showHidden
            val results = searchTree(dir, q, showHidden)
            // Only apply if this search is still the latest
            if (_state.value.query == q) {
                _state.update {
                    it.copy(items = results, searching = false)
                }
            }
        }
    }

    private fun searchTree(root: File, query: String, showHidden: Boolean): List<FileItem> {
        val q = query.lowercase()
        val out = mutableListOf<FileItem>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < 500) {
            val dir = stack.removeLast()
            val children = fileRepo.list(dir, showHidden)
            for (c in children) {
                if (c.name.lowercase().contains(q)) out += c
                if (c.isDir) stack.addLast(c.file)
            }
        }
        return out.sortedWith(
            compareByDescending<FileItem> { it.isDir }
                .thenComparator { a, b -> Sorting.naturalCompare(a.name, b.name) }
        )
    }

    fun setSort(mode: SortMode) {
        viewModelScope.launch { settingsRepo.setSortMode(mode) }
    }

    fun setFilter(mode: FilterMode) {
        _state.update { it.copy(filterMode = mode) }
    }

    fun requestToast(message: String) {
        _state.update { it.copy(toast = message) }
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

    // ---------- favorites ----------

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
        viewModelScope.launch(Dispatchers.IO) {
            val dest = FileOps.rename(file, newName)
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

    // ---------- delete ----------

    fun requestDelete(files: List<File>) = _state.update { it.copy(pendingDelete = files) }

    fun confirmDelete() {
        val files = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null, progress = "Deleting" to OpProgress(0, files.size, "")) }
        runOp("Delete") { onP -> FileOps.delete(files, onP) }
    }

    // ---------- copy / move (paste) ----------

    fun pasteFromClipboard() {
        val clip = _state.value.clipboard ?: return
        val dir = _state.value.dir ?: return
        viewModelScope.launch(Dispatchers.IO) {
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
        _state.update { it.copy(progress = label(kind) to OpProgress(0, sources.size, "")) }
        runOp(label(kind)) { onP ->
            when (kind) {
                OpKind.COPY -> FileOps.copy(sources, target, strategy, onP)
                OpKind.MOVE -> FileOps.move(sources, target, strategy, onP)
                OpKind.ZIP_EXTRACT -> ZipSupport.extract(sources.first(), target, strategy == ConflictStrategy.REPLACE, onProgress = onP)
            }
        }
        container.clipboard.clear()
    }

    private fun label(kind: OpKind) = when (kind) {
        OpKind.COPY -> "Copying"
        OpKind.MOVE -> "Moving"
        OpKind.ZIP_EXTRACT -> "Extracting"
    }

    private fun runOp(title: String, op: suspend (onP: (OpProgress) -> Unit) -> OpResult) {
        opJob = viewModelScope.launch(Dispatchers.IO) {
            val r = op { p -> _state.update { it.copy(progress = title to p) } }
            _state.update { it.copy(progress = null, result = r, resultTitle = title) }
            _state.value.dir?.let { d -> load(d) }
        }
    }

    fun cancelOp() {
        // Cooperative cancellation: cancelling the launched op job.
        opJob?.cancel()
        _state.update { it.copy(progress = null) }
    }

    private var opJob: Job? = null

    // ---------- zip ----------

    fun zipSelected(files: List<File>) {
        val dir = _state.value.dir ?: return
        val outName = PathSafety.uniqueName(dir, "archive.zip")
        val out = File(dir, outName)
        _state.update { it.copy(progress = "Zipping" to OpProgress(0, files.size, "")) }
        opJob = viewModelScope.launch(Dispatchers.IO) {
            val r = ZipSupport.zip(files, out) { p -> _state.update { it.copy(progress = "Zipping" to p) } }
            _state.update { it.copy(progress = null, result = r, resultTitle = "Zipping") }
            load(dir)
        }
    }

    fun extractZip(file: File) {
        val dir = file.parentFile ?: return
        val targetName = file.nameWithoutExtension
        val target = File(dir, targetName)
        val replace = target.exists()
        _state.update { it.copy(progress = "Extracting" to OpProgress(0, 1, "")) }
        opJob = viewModelScope.launch(Dispatchers.IO) {
            val r = ZipSupport.extract(file, target, replace) { p ->
                _state.update { it.copy(progress = "Extracting" to p) }
            }
            _state.update { it.copy(progress = null, result = r, resultTitle = "Extracting") }
            load(dir)
        }
    }

    // ---------- properties ----------

    fun requestProperties(file: File) {
        _state.update { it.copy(properties = file, propertiesDetail = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val size = FileOps.sizeOf(file)
            val children = if (file.isDirectory) fileRepo.childCount(file) else null
            _state.update {
                if (it.properties?.path == file.path) {
                    it.copy(propertiesDetail = PropertiesDetail(com.toadfiles.app.domain.Format.size(size), children))
                } else it
            }
        }
    }

    fun dismissProperties() = _state.update { it.copy(properties = null, propertiesDetail = null) }

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
        super.onCleared()
    }
}
