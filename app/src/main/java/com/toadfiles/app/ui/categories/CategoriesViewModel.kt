package com.toadfiles.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.ZipSupport
import com.toadfiles.app.ui.components.OpRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class CategoriesUiState(
    val summaries: List<CategorySummary> = emptyList(),
    val loading: Boolean = false,
    val selected: FileCategory? = null,
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val entriesLoading: Boolean = false,
    /** Multi-select inside the drill-in list, keyed by content-uri string. */
    val selection: Set<String> = emptySet(),
    val pendingDelete: List<MediaRepository.MediaEntry>? = null,
    val detailsOf: MediaRepository.MediaEntry? = null,
    val toast: String? = null,
    // extract op (same op/progress/result model as Browse)
    val progress: Pair<String, OpProgress>? = null,
    val result: OpResult? = null,
    val resultTitle: String = "",
)

/**
 * Drives the Categories tab: a grid of media-library categories, each opening that
 * category's files. Everything comes from MediaStore on demand — the tab never walks
 * the filesystem itself, so it costs nothing until the user opens it.
 */
class CategoriesViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests; production uses the container's MediaStore-backed repository. */
    private val media: MediaQuerySource = container.mediaRepo,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoriesUiState())
    val state: StateFlow<CategoriesUiState> = _state

    /** Request ids + live jobs per query lane: the grid summaries and the drill-in entries. */
    private var summariesSeq = 0L
    private var entriesSeq = 0L
    private var summariesJob: Job? = null
    private var entriesJob: Job? = null

    /** Shared op plumbing (audit 7.3): launches the extract, owns cancel/dispose. */
    private val ops = OpRunner(viewModelScope, ioDispatcher)

    init {
        refresh()
    }

    fun refresh() {
        val token = ++summariesSeq
        _state.update { it.copy(loading = true) }
        summariesJob?.cancel()
        summariesJob = viewModelScope.launch(ioDispatcher) {
            val summaries = runCatching { media.summaries() }.getOrDefault(emptyList())
            if (token != summariesSeq) return@launch // superseded by a newer refresh
            _state.update { it.copy(summaries = summaries, loading = false) }
        }
    }

    fun open(category: FileCategory) {
        val token = ++entriesSeq
        _state.update { it.copy(selected = category, entries = emptyList(), selection = emptySet(), entriesLoading = true) }
        entriesJob?.cancel()
        entriesJob = viewModelScope.launch(ioDispatcher) {
            val entries = runCatching { media.list(category, limit = QUERY_LIMIT) }.getOrDefault(emptyList())
            _state.update { s ->
                // Rapid category switches can finish out of order — apply only when this
                // is still the newest drill-in request for the category still on screen.
                if (token != entriesSeq || s.selected != category) return@update s
                s.copy(entries = entries, entriesLoading = false)
            }
        }
    }

    /** Leaves the drill-in list and returns to the category grid. */
    fun close() {
        _state.update { it.copy(selected = null, entries = emptyList(), selection = emptySet()) }
    }

    // ---------- multi-select ----------

    fun toggleSelection(entry: MediaRepository.MediaEntry) {
        _state.update { s ->
            val next = s.selection.toMutableSet()
            if (!next.add(entry.uri.toString())) next.remove(entry.uri.toString())
            s.copy(selection = next)
        }
    }

    fun selectAll() = _state.update { s ->
        s.copy(selection = s.entries.map { it.uri.toString() }.toSet())
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectedEntries(): List<MediaRepository.MediaEntry> =
        _state.value.entries.filter { it.uri.toString() in _state.value.selection }

    // ---------- delete to trash ----------

    fun requestDelete(entries: List<MediaRepository.MediaEntry>) {
        if (entries.isEmpty()) return
        _state.update { it.copy(pendingDelete = entries) }
    }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    /**
     * Moves the chosen media entries into the real toadFILES trash using their on-disk
     * paths, then drops the now-stale MediaStore rows so they disappear immediately
     * instead of waiting for the next media scan. Entries without a path are reported,
     * never silently dropped.
     */
    fun confirmDelete() {
        val entries = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null, selection = emptySet()) }
        viewModelScope.launch(ioDispatcher) {
            val withPath = entries.mapNotNull { e -> e.data?.let { e to File(it) } }
            val noPath = entries.size - withPath.size
            val roots = container.fileRepo.roots().map { it.dir }
            val result = withPath.filter { (_, f) -> f.exists() }
                .map { (_, f) -> f }
                .let { Trash.trash(it, roots) }
            container.mediaRepo.deleteStale(entries)
            val ok = result.moved.size
            _state.update {
                it.copy(
                    toast = when {
                        ok == entries.size -> "Moved ${ok} item(s) to trash"
                        noPath > 0 -> "Moved $ok to trash · $noPath had no on-disk path"
                        else -> "Moved $ok of ${entries.size} to trash"
                    },
                )
            }
            _state.value.selected?.let { open(it) }
            refresh()
        }
    }

    // ---------- zip extract ----------

    /**
     * Extracts an archive row into a sibling folder named after it — the same
     * ZipSupport op (with progress + result) Browse runs, surfaced where the user
     * found the file. Only offered for .zip entries with an on-disk path.
     */
    fun extractZip(file: File) {
        val dir = file.parentFile ?: run {
            requestToast("Could not extract this archive")
            return
        }
        val target = File(dir, file.nameWithoutExtension)
        if (target.exists()) {
            requestToast("A folder named \"${target.name}\" already exists. Rename or move it before extracting.")
            return
        }
        _state.update { it.copy(progress = "Extracting" to OpProgress(0, 1, "")) }
        ops.run(
            op = { onP -> ZipSupport.extract(file, target, false, onProgress = onP) },
            onProgress = { p -> _state.update { it.copy(progress = "Extracting" to p) } },
            onDone = { r ->
                _state.update { it.copy(progress = null, result = r, resultTitle = "Extracting") }
                // Re-list the open category + summaries, like confirmDelete does, so any
                // newly scanned files show up where the user is looking.
                _state.value.selected?.let { open(it) }
                refresh()
            },
        )
    }

    fun cancelOp() = ops.cancel { _state.update { it.copy(progress = null) } }

    fun dismissResult() = _state.update { it.copy(result = null) }

    // ---------- details ----------

    fun requestDetails(entry: MediaRepository.MediaEntry) =
        _state.update { it.copy(detailsOf = entry) }

    fun dismissDetails() = _state.update { it.copy(detailsOf = null) }

    fun requestToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    override fun onCleared() {
        summariesJob?.cancel()
        entriesJob?.cancel()
        ops.dispose()
        super.onCleared()
    }

    companion object {
        /** Query cap shared with the screen so it can disclose truncation honestly. */
        const val QUERY_LIMIT = 1000
    }
}
