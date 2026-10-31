package com.toadfiles.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.TrashEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TrashUiState(
    val entries: List<TrashEntry> = emptyList(),
    val loading: Boolean = false,
    val confirmingEmpty: Boolean = false,
    /** Item awaiting its "delete forever" confirmation. */
    val confirmingDelete: TrashEntry? = null,
    /** Multi-select (v1.7.5): absolute paths of the currently selected trash items. */
    val selection: Set<String> = emptySet(),
    /** Batch "delete forever" confirmation (operates on [selection]). */
    val confirmingBatchDelete: Boolean = false,
    val toast: String? = null,
)

/** Browse's trash: restore, delete forever, empty — all on real files, all on Dispatchers.IO. */
class TrashViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state

    private val volumeRoots: List<File>
        get() = container.fileRepo.roots().map { it.dir }

    /** Monotonically increasing request id; only the newest refresh may apply its result. */
    private var refreshSeq = 0L
    private var refreshJob: Job? = null

    init {
        refresh(purgeFirst = true)
    }

    fun refresh(purgeFirst: Boolean = false) {
        val token = ++refreshSeq
        _state.update { it.copy(loading = true) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(ioDispatcher) {
            if (purgeFirst) Trash.purge(volumeRoots)
            val entries = Trash.entries(volumeRoots)
            if (token != refreshSeq) return@launch // superseded by a newer refresh
            _state.update { it.copy(entries = entries, loading = false) }
        }
    }

    fun restore(entry: TrashEntry) {
        viewModelScope.launch(ioDispatcher) {
            val ok = Trash.restore(entry.file) != null
            _state.update {
                it.copy(
                    toast = if (ok) "Restored \"${entry.originPath.substringAfterLast('/')}\""
                    else "Could not restore — the original location is unavailable",
                )
            }
            refresh()
        }
    }

    /** Permanent deletion must be confirmed first — there is no undo for this. */
    fun requestDeleteForever(entry: TrashEntry) = _state.update { it.copy(confirmingDelete = entry) }

    fun dismissDeleteForever() = _state.update { it.copy(confirmingDelete = null) }

    fun confirmDeleteForever() {
        val entry = _state.value.confirmingDelete ?: return
        _state.update { it.copy(confirmingDelete = null) }
        viewModelScope.launch(ioDispatcher) {
            val ok = Trash.deleteForever(entry.file)
            _state.update {
                it.copy(
                    toast = if (ok) "Deleted \"${entry.originPath.substringAfterLast('/')}\" forever"
                    else "Could not delete — the item may already be gone",
                )
            }
            refresh()
        }
    }

    fun requestEmpty() = _state.update { it.copy(confirmingEmpty = true) }
    fun dismissEmpty() = _state.update { it.copy(confirmingEmpty = false) }

    // ---- Multi-select (v1.7.5): batch restore / delete forever ----

    fun toggleSelection(entry: TrashEntry) = _state.update {
        val path = entry.file.absolutePath
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet(), confirmingBatchDelete = false) }

    /** Select everything currently listed (the header row doubles as select-all). */
    fun selectAll() = _state.update {
        it.copy(selection = it.entries.mapTo(mutableSetOf()) { e -> e.file.absolutePath })
    }

    fun requestBatchRestore() {
        val selected = selectedEntries()
        if (selected.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            val restored = selected.count { Trash.restore(it.file) != null }
            _state.update {
                it.copy(
                    selection = emptySet(),
                    toast = if (restored == selected.size) "Restored $restored item(s)"
                    else "Restored $restored of ${selected.size} — some original locations are unavailable",
                )
            }
            refresh()
        }
    }

    /** Permanent batch deletion must be confirmed first — there is no undo for this. */
    fun requestBatchDeleteForever() {
        if (_state.value.selection.isNotEmpty()) _state.update { it.copy(confirmingBatchDelete = true) }
    }

    fun dismissBatchDeleteForever() = _state.update { it.copy(confirmingBatchDelete = false) }

    fun confirmBatchDeleteForever() {
        val selected = selectedEntries()
        _state.update { it.copy(confirmingBatchDelete = false, selection = emptySet()) }
        if (selected.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            val deleted = selected.count { Trash.deleteForever(it.file) }
            _state.update {
                it.copy(
                    toast = if (deleted == selected.size) "Deleted $deleted item(s) forever"
                    else "Deleted $deleted of ${selected.size} — the rest may already be gone",
                )
            }
            refresh()
        }
    }

    private fun selectedEntries(): List<TrashEntry> {
        val sel = _state.value.selection
        return _state.value.entries.filter { it.file.absolutePath in sel }
    }

    fun emptyNow() {
        _state.update { it.copy(confirmingEmpty = false) }
        viewModelScope.launch(ioDispatcher) {
            val removed = Trash.empty(volumeRoots)
            _state.update { it.copy(toast = "Trash emptied — $removed item(s) removed forever") }
            refresh()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
