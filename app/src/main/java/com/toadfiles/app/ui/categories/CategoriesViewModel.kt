package com.toadfiles.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaQuerySource
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.toFileItem
import com.toadfiles.app.domain.ArchiveFormat
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.CompressLevel
import com.toadfiles.app.domain.BrowseLayout
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.domain.PathSafety
import com.toadfiles.app.domain.PropertiesBuilder
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.domain.Sorting
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.ui.components.OpRunner
import com.toadfiles.app.ui.components.PropertiesDetail
import com.toadfiles.app.ui.components.computeFilePropertiesDetail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/** "Extract to…" collision waiting for the user's strategy in the shared ConflictDialog. */
data class PendingExtractConflict(val archive: File, val target: File)

data class CategoriesUiState(
    val summaries: List<CategorySummary> = emptyList(),
    val loading: Boolean = false,
    val selected: FileCategory? = null,
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val entriesLoading: Boolean = false,
    /** Multi-select inside the drill-in list, keyed by content-uri string. */
    val selection: Set<String> = emptySet(),
    /** Drill-in sort (session-local). DATE/DESC is MediaStore's own newest-first order. */
    val sortMode: SortMode = SortMode.DATE,
    val sortOrder: SortOrder = SortOrder.DESC,
    /** Drill-in layout, Browse-style. Visual categories default to GRID, others to LIST. */
    val layout: BrowseLayout = BrowseLayout.LIST,
    val pendingDelete: List<MediaRepository.MediaEntry>? = null,
    val pendingRename: MediaRepository.MediaEntry? = null,
    /** Batch waiting for the user to type an archive name in the Zip dialog. */
    val pendingZipFiles: List<File>? = null,
    val pendingZipName: String = "",
    /** Chosen archive format in the compress dialog (Browse parity, v1.7.5). */
    val pendingZipFormat: ArchiveFormat = ArchiveFormat.ZIP,
    /** Chosen compression level in the compress dialog (Browse parity, v1.7.5). */
    val pendingZipLevel: CompressLevel = CompressLevel.NORMAL,
    /** Destination shown in the compress dialog (archive-folder setting or the
     *  source's own folder) — resolved asynchronously at request time. */
    val pendingZipDestination: File? = null,
    /** "Extract to…" collision waiting for a strategy. */
    val pendingExtractConflict: PendingExtractConflict? = null,
    /** Item whose read-only properties sheet is open; null when closed. */
    val pendingProperties: File? = null,
    /** Computed size/contents for the properties sheet; null while the walk is running. */
    val propertiesDetail: PropertiesDetail? = null,
    val toast: String? = null,
    /** Last delete batch (trash payload files), for the snackbar's Undo action. */
    val lastTrashed: List<File>? = null,
    // file ops (same op/progress/result model as Browse)
    val progress: Pair<String, OpProgress>? = null,
    val result: OpResult? = null,
    val resultTitle: String = "",
)

/**
 * Drives the Categories tab: a grid of media-library categories, each opening that
 * category's files. Everything comes from MediaStore on demand — the tab never walks
 * the filesystem itself, so it costs nothing until the user opens it.
 *
 * v1.7.3: the drill-in offers Browse-level per-file parity — the SAME shared rows,
 * grid cells, action sheet and properties sheet Browse renders — on top of the same
 * hardened pipelines (Trash, ArchiveOps, FileOps, Sorting). Clipboard copy/move and
 * favorites stay Browse-only: Categories has no paste target or favorites store.
 */
class CategoriesViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable for tests; production uses the container's MediaStore-backed repository. */
    private val media: MediaQuerySource = container.mediaRepo,
    /** Injectable for tests; production walks the real file system for contents counts. */
    private val propertiesBuilder: PropertiesBuilder = PropertiesBuilder(),
) : ViewModel() {

    private val _state = MutableStateFlow(CategoriesUiState())
    val state: StateFlow<CategoriesUiState> = _state

    /** Request ids + live jobs per query lane: the grid summaries and the drill-in entries. */
    private var summariesSeq = 0L
    private var entriesSeq = 0L
    private var summariesJob: Job? = null
    private var entriesJob: Job? = null

    /** Properties computation for the open sheet; cancelled on dismiss (see requestProperties). */
    private var propertiesJob: Job? = null

    /** Shared op plumbing (audit 7.3): launches extract/zip, owns cancel/dispose. */
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
        _state.update {
            it.copy(
                selected = category,
                entries = emptyList(),
                selection = emptySet(),
                entriesLoading = true,
                // Visual categories open as a thumbnail grid, like before; everything
                // else as rows. The user can flip it with the shared layout toggle.
                layout = if (category == FileCategory.IMAGES || category == FileCategory.VIDEOS) {
                    BrowseLayout.GRID
                } else BrowseLayout.LIST,
            )
        }
        entriesJob?.cancel()
        entriesJob = viewModelScope.launch(ioDispatcher) {
            val entries = runCatching { media.list(category, limit = QUERY_LIMIT) }.getOrDefault(emptyList())
            _state.update { s ->
                // Rapid category switches can finish out of order — apply only when this
                // is still the newest drill-in request for the category still on screen.
                if (token != entriesSeq || s.selected != category) return@update s
                s.copy(entries = sortEntries(entries), entriesLoading = false)
            }
        }
    }

    /** Leaves the drill-in list and returns to the category grid. */
    fun close() {
        _state.update { it.copy(selected = null, entries = emptyList(), selection = emptySet()) }
    }

    // ---------- sort / layout (Browse parity, session-local) ----------

    fun setSort(mode: SortMode) = _state.update {
        it.copy(sortMode = mode, entries = sortEntries(it.entries, mode, it.sortOrder))
    }

    fun setSortOrder(order: SortOrder) = _state.update {
        it.copy(sortOrder = order, entries = sortEntries(it.entries, it.sortMode, order))
    }

    fun toggleLayout() = _state.update {
        it.copy(
            layout = if (it.layout == BrowseLayout.GRID) BrowseLayout.LIST else BrowseLayout.GRID,
        )
    }

    /** Client-side sort with the app's single [Sorting] engine (entries bridge to FileItem). */
    private fun sortEntries(
        entries: List<MediaRepository.MediaEntry>,
        mode: SortMode = _state.value.sortMode,
        order: SortOrder = _state.value.sortOrder,
    ): List<MediaRepository.MediaEntry> {
        val cmp = Sorting.comparator(mode, order)
        return entries
            .map { it.toFileItem() to it }
            .sortedWith { a, b -> cmp.compare(a.first, b.first) }
            .map { it.second }
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

    // ---------- delete to trash (+ undo) ----------

    fun requestDelete(entries: List<MediaRepository.MediaEntry>) {
        if (entries.isEmpty()) return
        _state.update { it.copy(pendingDelete = entries) }
    }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    /**
     * Moves the chosen media entries into the real toadFILES trash using their on-disk
     * paths, then drops the now-stale MediaStore rows so they disappear immediately
     * instead of waiting for the next media scan. Entries without a path are reported,
     * never silently dropped. Full success is reported by the screen's Undo snackbar
     * (like Browse) instead of a plain toast.
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
                    lastTrashed = result.moved.takeIf { t -> t.isNotEmpty() },
                    toast = when {
                        ok == entries.size -> null // the undo snackbar reports success
                        noPath > 0 -> "Moved $ok to trash · $noPath had no on-disk path"
                        else -> "Moved $ok of ${entries.size} to trash"
                    },
                )
            }
            _state.value.selected?.let { open(it) }
            refresh()
        }
    }

    /** Restores the most recent delete batch (snackbar "Undo"), exactly like Browse. */
    fun undoDelete() {
        val trashed = _state.value.lastTrashed ?: return
        _state.update { it.copy(lastTrashed = null) }
        viewModelScope.launch(ioDispatcher) {
            var restored = 0
            for (t in trashed) if (Trash.restore(t) != null) restored++
            _state.update {
                it.copy(
                    toast = if (restored == trashed.size) "Restored $restored item(s)"
                    else "Restored $restored of ${trashed.size} — the rest stays in Trash",
                )
            }
            _state.value.selected?.let { open(it) }
            refresh()
        }
    }

    fun dismissUndo() = _state.update { it.copy(lastTrashed = null) }

    // ---------- rename (Browse parity) ----------

    fun requestRename(entry: MediaRepository.MediaEntry) =
        _state.update { it.copy(pendingRename = entry) }

    fun dismissRename() = _state.update { it.copy(pendingRename = null) }

    /**
     * Renames the entry's on-disk file with the shared [FileOps.rename] (collision-safe),
     * then drops the now-stale MediaStore row so the old name disappears immediately.
     * The renamed file joins the index on the next media scan, like any external change.
     */
    fun rename(entry: MediaRepository.MediaEntry, newName: String) {
        val path = entry.data
        if (path == null) {
            _state.update { it.copy(pendingRename = null, toast = "This file has no on-disk path to rename") }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val dest = FileOps.rename(File(path), newName)
            if (dest != null) media.deleteStale(listOf(entry))
            _state.update {
                it.copy(
                    pendingRename = null,
                    toast = if (dest == null) "Rename failed (name taken or invalid)"
                    else "Renamed to \"${dest.name}\"",
                )
            }
            _state.value.selected?.let { open(it) }
            refresh()
        }
    }

    // ---------- zip (Browse parity) ----------

    /** Opens the archive-name dialog for a batch (UI flow). */
    fun requestZip(files: List<File>) {
        if (files.isEmpty()) return
        _state.update {
            it.copy(
                pendingZipFiles = files,
                pendingZipName = suggestedZipName(files),
                pendingZipFormat = ArchiveFormat.ZIP,
                pendingZipLevel = CompressLevel.NORMAL,
            )
        }
        // Destination preview: archive-folder setting when set, else the source's own
        // folder (resolved on IO — same semantics Browse shows in its dialog).
        val fallbackDir = files.first().parentFile
        viewModelScope.launch {
            val dest = archiveFolderOr(fallbackDir ?: return@launch)
            _state.update { if (it.pendingZipFiles != null) it.copy(pendingZipDestination = dest) else it }
        }
    }

    fun setZipName(name: String) = _state.update { it.copy(pendingZipName = name) }

    fun dismissZipDialog() = _state.update {
        it.copy(
            pendingZipFiles = null, pendingZipName = "",
            pendingZipFormat = ArchiveFormat.ZIP, pendingZipLevel = CompressLevel.NORMAL,
            pendingZipDestination = null,
        )
    }

    fun setZipFormat(f: ArchiveFormat) = _state.update { it.copy(pendingZipFormat = f) }
    fun setZipLevel(l: CompressLevel) = _state.update { it.copy(pendingZipLevel = l) }

    /** Suggested archive name: the single item's name, or "archive.zip" for batches. */
    fun suggestedZipName(files: List<File>): String = when (files.size) {
        1 -> {
            val n = files[0].name
            if (n.lowercase().endsWith(".zip")) n else files[0].nameWithoutExtension + ".zip"
        }
        else -> "archive.zip"
    }

    /** Zips [files] under the user-typed name — into the archive-folder setting's
     *  folder when one is set (v1.7.3 default destination, same as Browse's compress
     *  dialog), else the first file's folder (".zip" appended when missing, collisions
     *  deduped) — the same dialog flow as Browse. */
    fun confirmZip(name: String) {
        val st = _state.value
        val files = st.pendingZipFiles ?: return
        val format = st.pendingZipFormat
        val level = st.pendingZipLevel
        val fallbackDir = files.first().parentFile ?: return
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
            _state.update { it.copy(toast = "Enter a valid archive name") }
            return
        }
        _state.update {
            it.copy(
                pendingZipFiles = null, pendingZipName = "",
                pendingZipFormat = ArchiveFormat.ZIP, pendingZipLevel = CompressLevel.NORMAL,
                pendingZipDestination = null,
            )
        }
        viewModelScope.launch {
            val dir = archiveFolderOr(fallbackDir)
            // Extension follows the chosen format (ZIP → .zip, 7z → .7z).
            val base = if (clean.lowercase().endsWith("." + format.extension)) clean
            else "$clean.${format.extension}"
            zipTo(files, File(dir, PathSafety.uniqueName(dir, base)), format, level)
        }
    }

    /** Default archive destination (v1.7.3): the user's archive folder when set, else
     *  [fallback] (the source's own folder). Mirrors BrowseViewModel.archiveFolderOr. */
    private suspend fun archiveFolderOr(fallback: File): File {
        val folder = container.settingsRepo.settings.first().archiveFolder.trim()
        return if (folder.isNotEmpty()) File(folder) else fallback
    }

    private fun zipTo(files: List<File>, out: File, format: ArchiveFormat, level: CompressLevel) {
        _state.update { it.copy(progress = "Zipping" to OpProgress(0, files.size, "")) }
        ops.run(
            op = { onP -> ArchiveOps.compress(files, out, format, level, onP) },
            onProgress = { p -> _state.update { it.copy(progress = "Zipping" to p) } },
            onDone = { r ->
                _state.update { it.copy(progress = null, result = r, resultTitle = "Zipping") }
                // v1.7.3 delete-after-compress, same trash convention as Browse:
                // only a fully successful run moves the sources to the trash.
                if (r.fullySucceeded) trashSourcesAfterCompress(files)
                // The new archive lands outside the media index until the next scan; a
                // refresh keeps the grid honest either way.
                _state.value.selected?.let { open(it) }
                refresh()
            },
        )
    }

    // ---------- zip extract (default + named destination + conflict) ----------

    /**
     * Extracts an archive row (.zip or .7z) — the same hardened ArchiveOps pipeline
     * (with progress + result) Browse runs, surfaced where the user found the file.
     * The default flow extracts into a sibling folder named after the archive and
     * refuses an existing destination; [targetName] ("Extract to…") extracts into a
     * folder with that name, asking via the shared conflict dialog when it exists.
     * Offered for extractable archives with an on-disk path; 7z is extract-only.
     */
    fun extractZip(file: File, targetName: String? = null) {
        if (targetName == null) {
            val dir = file.parentFile ?: run {
                requestToast("Could not extract this archive")
                return
            }
            val target = File(dir, file.nameWithoutExtension)
            if (target.exists()) {
                requestToast("A folder named \"${target.name}\" already exists. Rename or move it before extracting.")
                return
            }
            startExtract(file, target)
        } else {
            // "Extract to…" defaults to the archive-folder setting when one is set,
            // else the archive's own folder — same as Browse's "Extract to…" (v1.7.3).
            val fallbackDir = file.parentFile ?: run {
                requestToast("Could not extract this archive")
                return
            }
            val clean = targetName.trim()
            if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
                requestToast("Enter a valid folder name")
                return
            }
            viewModelScope.launch {
                val dir = archiveFolderOr(fallbackDir)
                val target = File(dir, clean)
                if (target.exists()) {
                    // Same collision flow as Browse's "Extract to…": the user picks the strategy.
                    _state.update { it.copy(pendingExtractConflict = PendingExtractConflict(file, target)) }
                } else {
                    startExtract(file, target)
                }
            }
        }
    }

    /**
     * Resolves an "Extract to…" collision with the shared ConflictDialog semantics:
     * Keep both extracts into a uniquely named sibling, Replace overwrites colliding
     * entries, Skip merges into the existing folder without overwriting.
     */
    fun resolveExtractConflict(strategy: ConflictStrategy) {
        val pc = _state.value.pendingExtractConflict ?: return
        _state.update { it.copy(pendingExtractConflict = null) }
        val parent = pc.target.parentFile ?: return
        val target = if (strategy == ConflictStrategy.RENAME) {
            File(parent, PathSafety.uniqueName(parent, pc.target.name))
        } else pc.target
        startExtract(pc.archive, target, overwrite = strategy == ConflictStrategy.REPLACE)
    }

    fun dismissExtractConflict() = _state.update { it.copy(pendingExtractConflict = null) }

    private fun startExtract(file: File, target: File, overwrite: Boolean = false) {
        _state.update { it.copy(progress = "Extracting" to OpProgress(0, ArchiveOps.entryCount(file) ?: 1, "")) }
        ops.run(
            op = { onP -> ArchiveOps.extract(file, target, overwrite, onProgress = onP) },
            onProgress = { p -> _state.update { it.copy(progress = "Extracting" to p) } },
            onDone = { r ->
                _state.update { it.copy(progress = null, result = r, resultTitle = "Extracting") }
                if (r.fullySucceeded) trashArchiveAfterExtract(file)
                // Re-list the open category + summaries, like confirmDelete does, so any
                // newly scanned files show up where the user is looking.
                _state.value.selected?.let { open(it) }
                refresh()
            },
        )
    }

    /**
     * v1.7.3 delete-after-compress (Categories): after a fully successful compression,
     * honor the setting by moving the sources to the toadFILES trash (undoable) — the
     * same convention BrowseViewModel.trashSourcesAfterArchiveOp follows.
     */
    private fun trashSourcesAfterCompress(files: List<File>) {
        viewModelScope.launch {
            if (!container.settingsRepo.settings.first().deleteAfterCompress) return@launch
            val live = files.filter { it.exists() }
            if (live.isEmpty()) return@launch
            val roots = container.fileRepo.roots().map { it.dir }
            val result = Trash.trash(live, roots)
            if (result.failureCount > 0) {
                requestToast("Could not delete ${result.failureCount} source item(s)")
            }
        }
    }

    /**
     * v1.7.3 delete-after-extract (media flows): after a fully successful extraction,
     * honor the setting by moving the archive to the toadFILES trash (undoable) — the
     * same convention BrowseViewModel.trashSourcesAfterArchiveOp follows.
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

    // ---------- properties (Browse parity) ----------

    /**
     * Opens the shared read-only properties sheet for the entry's on-disk file and
     * starts the computation on the IO dispatcher — the exact engine Browse runs
     * ([computeFilePropertiesDetail]). Dismissing cancels the in-flight computation.
     */
    fun requestProperties(entry: MediaRepository.MediaEntry) {
        val file = entry.data?.let(::File) ?: run {
            requestToast("This file has no on-disk path to inspect")
            return
        }
        _state.update { it.copy(pendingProperties = file, propertiesDetail = null) }
        propertiesJob?.cancel()
        propertiesJob = viewModelScope.launch(ioDispatcher) {
            val detail = computeFilePropertiesDetail(file, propertiesBuilder)
            coroutineContext.ensureActive() // dismissed/superseded mid-walk: drop the result
            _state.update {
                if (it.pendingProperties?.path == file.path) it.copy(propertiesDetail = detail) else it
            }
        }
    }

    fun dismissProperties() {
        propertiesJob?.cancel()
        _state.update { it.copy(pendingProperties = null, propertiesDetail = null) }
    }

    // ---------- misc ----------

    fun requestToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    override fun onCleared() {
        summariesJob?.cancel()
        entriesJob?.cancel()
        propertiesJob?.cancel()
        ops.dispose()
        super.onCleared()
    }

    companion object {
        /** Query cap shared with the screen so it can disclose truncation honestly. */
        const val QUERY_LIMIT = 1000
    }
}
