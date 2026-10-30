package com.toadfiles.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Trash
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
            val entries = runCatching { media.list(category, limit = 1000) }.getOrDefault(emptyList())
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

    // ---------- details ----------

    fun requestDetails(entry: MediaRepository.MediaEntry) =
        _state.update { it.copy(detailsOf = entry) }

    fun dismissDetails() = _state.update { it.copy(detailsOf = null) }

    fun requestToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
