package com.toadfiles.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.TrashEntry
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.OpProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Shared progress overlay for the multi-item flows (owner report: they used to run
     *  blind — no bar, no count, no cancel). Same shape Browse's op overlay consumes. */
    val progress: Pair<String, OpProgress>? = null,
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

    /** The running multi-item flow (empty / batch delete / batch restore); cancelled
     *  by [cancelBatch] from the progress overlay's Cancel button. */
    private var batchJob: Job? = null

    /** Test seam: awaited before processing item [i] of a batch. Production: null —
     *  real filesystem deletes are far too fast for a cancel test to land mid-loop
     *  without a deterministic pause point. */
    internal var perItemGate: (suspend (Int) -> Unit)? = null

    /** Stops the running batch at the next item boundary. Items already processed are
     *  kept — deletion is permanent — and the summary toast + refresh still run. */
    fun cancelBatch() {
        batchJob?.cancel()
    }

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
     *  surfaced per D4/D1; the shared progress overlay tracks each item. */
    fun requestBatchRestore() {
        val selected = selectedEntries()
        if (selected.isEmpty()) return
        _state.update { it.copy(selection = emptySet()) }
        val restoredPaths = mutableListOf<String>()
        var renamed = 0
        runBatch(
            title = "Restoring",
            targets = selected,
            op = { e ->
                val dest = Trash.restore(e.file)
                if (dest != null) {
                    restoredPaths += dest.absolutePath
                    if (dest.name != e.originPath.substringAfterLast('/')) renamed++
                }
                dest != null
            },
        ) { restored, cancelled ->
            // A trashed file's MediaStore row is long gone — only a scan brings the
            // restored files back into Recent/Categories and galleries (D1).
            if (restoredPaths.isNotEmpty()) media.rescan(restoredPaths)
            val renamedNote = if (renamed > 0) ", $renamed renamed" else ""
            _state.update {
                it.copy(
                    toast = when {
                        cancelled -> "Stopped — restored $restored of ${selected.size}, the rest is still in trash$renamedNote"
                        restored == selected.size -> "Restored $restored ${Format.itemWord(restored)}$renamedNote"
                        else -> "Restored $restored of ${selected.size} — some original locations are unavailable$renamedNote"
                    },
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
        runBatch(
            title = "Deleting forever",
            targets = selected,
            op = { e -> Trash.deleteForever(e.file) },
        ) { deleted, cancelled ->
            _state.update {
                it.copy(
                    toast = when {
                        cancelled -> "Stopped — deleted $deleted of ${selected.size}, the rest is still in trash"
                        deleted == selected.size -> "Deleted $deleted ${Format.itemWord(deleted)} forever"
                        else -> "Deleted $deleted of ${selected.size} — the rest may already be gone"
                    },
                )
            }
            refresh()
        }
    }

    private fun selectedEntries(): List<TrashEntry> {
        val sel = _state.value.selection
        return _state.value.entries.filter { it.file.absolutePath in sel }
    }

    private fun displayName(e: TrashEntry) =
        e.originPath.substringAfterLast('/').ifEmpty { e.file.name }

    /**
     * The shared multi-item runner (owner report): emits [progress] per item so the
     * screen can show the same overlay Browse uses, and stops at the next item
     * boundary when the job is cancelled — items already processed are kept.
     * [onFinished] ALWAYS runs, also after a cancel, so the toast and the refresh
     * reflect what actually happened instead of leaving a stale list.
     */
    private fun runBatch(
        title: String,
        targets: List<TrashEntry>,
        op: (TrashEntry) -> Boolean,
        onFinished: (done: Int, cancelled: Boolean) -> Unit,
    ) {
        batchJob?.cancel()
        batchJob = viewModelScope.launch(ioDispatcher) {
            var done = 0
            var cancelled = false
            for ((i, e) in targets.withIndex()) {
                _state.update {
                    it.copy(progress = title to OpProgress(i, targets.size, displayName(e)))
                }
                perItemGate?.invoke(i)
                try {
                    // Trash.deleteForever is blocking — re-entering the dispatcher per
                    // item gives every item a real cancellation checkpoint.
                    withContext(ioDispatcher) { op(e) }
                } catch (_: CancellationException) {
                    cancelled = true
                    break
                }
                done++
            }
            _state.update { it.copy(progress = null) }
            onFinished(done, cancelled)
        }
    }

    fun emptyNow() {
        _state.update { it.copy(confirmingEmpty = false) }
        val targets = _state.value.entries
        if (targets.isEmpty()) return
        runBatch(
            title = "Emptying trash",
            targets = targets,
            op = { e -> Trash.deleteForever(e.file) },
        ) { done, cancelled ->
            // "Empty" can fail per item (a payload the filesystem refuses to delete);
            // report what is actually left instead of claiming success (D5).
            val remaining = Trash.entries(volumeRoots).size
            _state.update {
                it.copy(
                    toast = when {
                        cancelled -> "Stopped — $done of ${targets.size} deleted, the rest is still in trash"
                        remaining > 0 -> "Couldn't empty trash — $remaining ${Format.itemWord(remaining)} couldn't be deleted"
                        else -> "Trash emptied — $done ${Format.itemWord(done)} removed forever"
                    },
                )
            }
            refresh()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
