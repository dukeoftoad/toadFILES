package com.toadfiles.app.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.MediaTrashOps
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.ui.components.OpRunner
import com.toadfiles.app.ui.components.PendingPasswordRetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

data class RecentUiState(
    /** Active type filter; null = All. */
    val filter: FileCategory? = null,
    /** v1.8.1: list or grid, persisted via settings (recentLayout). */
    val layout: BrowseLayout = BrowseLayout.LIST,
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val loading: Boolean = false,
    /** Multi-select, keyed by content-uri string. */
    val selection: Set<String> = emptySet(),
    val pendingDelete: List<MediaRepository.MediaEntry>? = null,
    val detailsOf: MediaRepository.MediaEntry? = null,
    val toast: String? = null,
    /** Last delete batch (trash payload files), for the snackbar's Undo action.
     *  One-shot (D2): the screen consumes it the moment the snackbar shows it. */
    val lastTrashed: List<File>? = null,
    /** Bumped whenever a new undo offer is made; the snackbar effect keys on this id
     *  so consuming the offer (nulling [lastTrashed]) cannot cancel the showing snackbar. */
    val undoOfferSeq: Long = 0,
    /** Encrypted archive whose extraction is waiting for a password (drives PasswordPromptDialog). */
    val pendingPasswordFor: File? = null,
    /** Retry context for [pendingPasswordFor]: same target dir / replace semantics as the original call. */
    val pendingPasswordRetry: PendingPasswordRetry? = null,
    // extract op (same op/progress/result model as Browse)
    val progress: Pair<String, OpProgress>? = null,
    val result: OpResult? = null,
    val resultTitle: String = "",
)

/**
 * Recent files straight from the OS media library, newest first. Entries sitting in the
 * toadFILES trash folder are filtered out by the repository. Every row is actionable:
 * open, share, show in folder, delete to trash — via the long-press sheet or multi-select.
 */
class RecentViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests; production uses the container's MediaStore-backed repository. */
    private val media: MediaQuerySource = container.mediaRepo,
) : ViewModel() {

    private val _state = MutableStateFlow(RecentUiState())
    val state: StateFlow<RecentUiState> = _state

    /** Monotonically increasing request id; only the newest query may apply its result. */
    private var requestSeq = 0L
    private var queryJob: Job? = null

    /** Monotonic id of the current undo offer; see [RecentUiState.undoOfferSeq]. */
    private var undoOfferId = 0L

    /** Shared op plumbing (audit 7.3): launches the extract, owns cancel/dispose. */
    private val ops = OpRunner(viewModelScope, ioDispatcher)

    init {
        refresh()
        // v1.8.1: the list/grid choice persists across launches like Browse's does.
        viewModelScope.launch {
            container.settingsRepo.settings.collect { s ->
                _state.update { it.copy(layout = s.recentLayout) }
            }
        }
    }

    /** v1.8.1: flip the Recent layout (list ⇄ grid) and persist it. */
    fun toggleLayout() {
        val next = if (_state.value.layout == BrowseLayout.GRID) BrowseLayout.LIST else BrowseLayout.GRID
        _state.update { it.copy(layout = next) }
        viewModelScope.launch(ioDispatcher) { container.settingsRepo.setRecentLayout(next) }
    }

    fun refresh() {
        val token = ++requestSeq
        val filter = _state.value.filter
        _state.update { it.copy(loading = true) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch(ioDispatcher) {
            // One query, no uncapped cleanup pass: the repository's own capped
            // reconcileStaleRows + trash-path hiding already heal ghost rows inside
            // recent()/list(); the extra deleteStale here used to re-query (and row-drop)
            // up to QUERY_LIMIT entries on every refresh.
            val entries = if (filter == null) {
                media.recent(limit = QUERY_LIMIT)
            } else {
                media.list(filter, limit = QUERY_LIMIT)
            }
            _state.update { s ->
                // A slow older query must never overwrite a newer result: drop the
                // outcome when a newer request started or the filter moved on while
                // this one was in flight.
                if (token != requestSeq || s.filter != filter) return@update s
                // Drop selections that no longer exist after a refresh.
                val live = entries.map { it.uri.toString() }.toSet()
                s.copy(entries = entries, loading = false, selection = s.selection.intersect(live))
            }
        }
    }

    /** Switches the type filter tab (All / Images / Videos / …) and reloads. */
    fun setFilter(filter: FileCategory?) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter) }
        refresh()
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
     * Same trash flow as Categories, through the shared [MediaTrashOps] pipeline: real
     * paths into the 30-day trash, per-entry honest accounting. Entries whose cached
     * path is stale (moved/renamed outside the app) are SKIPPED and reported — never
     * trashed, never row-dropped. Only entries that really moved have their now-stale
     * rows dropped. Full success is reported by the screen's Undo snackbar (like
     * Browse/Categories) instead of a plain toast.
     */
    fun confirmDelete() {
        val entries = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null, selection = emptySet()) }
        viewModelScope.launch(ioDispatcher) {
            val roots = container.fileRepo.roots().map { it.dir }
            val result = MediaTrashOps.deleteToTrash(entries, roots)
            media.deleteStale(result.movedEntries)
            _state.update {
                it.copy(
                    lastTrashed = result.movedFiles.takeIf { t -> t.isNotEmpty() },
                    undoOfferSeq = ++undoOfferId,
                    toast = MediaTrashOps.toastFor(result),
                )
            }
            refresh()
        }
    }

    /**
     * Restores a delete batch (snackbar "Undo") from the captured payload list,
     * exactly like Browse/Categories. Honest accounting (N5): items already gone from
     * the trash are reported as such, collision renames are counted (D4), and restored
     * files are re-scanned into the media index (D1) so they reappear here immediately.
     */
    fun undoDelete(trashed: List<File>) {
        if (trashed.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
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
            refresh()
        }
    }

    /**
     * One-shot undo offer (D2): consumed the moment the snackbar shows it, so a
     * navigation mid-window can never leave a stale offer in the retained ViewModel
     * that resurrects (and lies about "restoring") on the next visit.
     */
    fun consumeUndoOffer() = _state.update { it.copy(lastTrashed = null) }

    // ---------- zip extract ----------

    /**
     * Extracts an archive row (.zip or .7z) into a sibling folder named after it — the
     * same hardened ArchiveOps pipeline (with progress + result) Browse runs, surfaced
     * where the user found the file. Offered for extractable archives with an on-disk
     * path; 7z is extract-only.
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
        runExtract(file, target)
    }

    private fun runExtract(file: File, target: File, replace: Boolean = false, password: CharArray? = null) {
        _state.update { it.copy(progress = "Extracting" to OpProgress(0, ArchiveOps.entryCount(file) ?: 1, "")) }
        ops.run(
            op = { onP -> ArchiveOps.extract(file, target, replace, password = password, onProgress = onP) },
            onProgress = { p -> _state.update { it.copy(progress = "Extracting" to p) } },
            onDone = { r ->
                if (r.passwordRequired) {
                    // Encrypted archive: ask for the password, don't report a failure.
                    // Nothing was extracted, so no refresh is needed either.
                    _state.update {
                        it.copy(
                            progress = null, result = null,
                            pendingPasswordFor = file,
                            pendingPasswordRetry = PendingPasswordRetry(file, target, replace),
                        )
                    }
                    return@run
                }
                _state.update { it.copy(progress = null, result = r, resultTitle = "Extracting") }
                if (r.fullySucceeded) trashArchiveAfterExtract(file)
                refresh()
            },
        )
    }

    /** Retry hook for [PasswordPromptDialog]: re-runs the stored extract with the typed
     *  password, keeping the original target dir / replace semantics. A wrong password
     *  comes back as a normal failure result through the existing result dialog, and
     *  the user can try again from the archive. */
    fun retryExtractWithPassword(password: String) {
        val ctx = _state.value.pendingPasswordRetry ?: return
        dismissPasswordPrompt()
        if (password.isBlank()) {
            requestToast("Enter a password")
            return
        }
        runExtract(ctx.archive, ctx.target, ctx.replace, password.toCharArray())
    }

    fun dismissPasswordPrompt() = _state.update {
        it.copy(pendingPasswordFor = null, pendingPasswordRetry = null)
    }

    /**
     * v1.7.3 delete-after-extract (media flows): after a successful extraction, honor
     * the setting by moving the archive to the toadFILES trash (undoable) — the same
     * convention BrowseViewModel.trashSourcesAfterArchiveOp follows.
     */
    private fun trashArchiveAfterExtract(file: File) {
        viewModelScope.launch {
            if (!container.settingsRepo.settings.first().deleteAfterExtract) return@launch
            if (!file.exists()) return@launch
            val roots = container.fileRepo.roots().map { it.dir }
            if (Trash.trash(listOf(file), roots).failureCount > 0) {
                requestToast("Could not delete the archive")
            }
        }
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
        queryJob?.cancel()
        ops.dispose()
        super.onCleared()
    }

    companion object {
        /** Query cap shared with the screen so it can disclose truncation honestly. */
        const val QUERY_LIMIT = 400
    }
}
