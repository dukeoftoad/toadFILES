package com.toadfiles.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
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
    /** Injectable for tests; production uses the container's MediaStore-backed repository
     *  (a restore re-scans the restored files back into the media index). */
    private val media: MediaQuerySource = container.mediaRepo,
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
            val dest = Trash.restore(entry.file)
            _state.update {
                it.copy(
                    toast = if (dest == null) {
                        "Could not restore — the original location is unavailable"
                    } else {
                        restoreToast(
                            dest = dest,
                            originalName = entry.originPath.substringAfterLast('/'),
                            originParent = entry.originPath.substringBeforeLast('/', ""),
                        )
                    },
                )
            }
            // A trashed file's MediaStore row is long gone — only a scan brings the
            // restored file back into Recent/Categories and galleries (D1).
            if (dest != null) media.rescan(listOf(dest.absolutePath))
            refresh()
        }
    }

    /** Honest restore copy (D3/D4): names a collision rename ("name (1).ext") and/or
     *  the volume-root fallback when the original folder could not be recreated. */
    private fun restoreToast(dest: File, originalName: String, originParent: String): String {
        val renamed = dest.name != originalName
        val fellBack = dest.parentFile?.absolutePath != originParent.ifEmpty { "/" }
        return when {
            renamed && fellBack ->
                "Restored as \"${dest.name}\" — original folder unavailable, saved to ${shortPath(dest.parent)}"
            renamed -> "Restored as \"${dest.name}\""
            fellBack ->
                "Restored \"$originalName\" — original folder unavailable, saved to ${shortPath(dest.parent)}"
            else -> "Restored \"$originalName\""
        }
    }

    /** Short display form of a path for toasts: the last two segments when it is long. */
    private fun shortPath(path: String?): String {
        if (path == null) return ""
        val segments = path.split('/').filter { it.isNotEmpty() }
        return if (segments.size <= 2) path else "…/${segments.takeLast(2).joinToString("/")}"
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

    /** Batch restore never destroys data — no confirmation needed. The selection is
     *  cleared synchronously BEFORE the work launches (like [confirmBatchDeleteForever])
     *  so a double-tap can never relaunch the same batch (N2). Renames and rescans are
     *  surfaced per D4/D1. */
    fun requestBatchRestore() {
        val selected = selectedEntries()
        if (selected.isEmpty()) return
        _state.update { it.copy(selection = emptySet()) }
        viewModelScope.launch(ioDispatcher) {
            var restored = 0
            var renamed = 0
            val restoredPaths = mutableListOf<String>()
            for (e in selected) {
                val dest = Trash.restore(e.file) ?: continue
                restored++
                if (dest.name != e.originPath.substringAfterLast('/')) renamed++
                restoredPaths += dest.absolutePath
            }
            media.rescan(restoredPaths)
            val renamedNote = if (renamed > 0) ", $renamed renamed" else ""
            _state.update {
                it.copy(
                    toast = if (restored == selected.size) "Restored $restored item(s)$renamedNote"
                    else "Restored $restored of ${selected.size} — some original locations are unavailable$renamedNote",
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
            // "Empty" can fail per item (a payload the filesystem refuses to delete);
            // report what is actually left instead of claiming success (D5).
            val remaining = Trash.entries(volumeRoots).size
            _state.update {
                it.copy(
                    toast = if (remaining > 0) "Couldn't empty trash — $remaining item(s) couldn't be deleted"
                    else "Trash emptied — $removed item(s) removed forever",
                )
            }
            refresh()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
