package com.toadfiles.app.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.StorageRoot
import com.toadfiles.app.domain.Trash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DirUsage(val dir: File, val sizeBytes: Long)

data class StorageUiState(
    val roots: List<StorageRoot> = emptyList(),
    val analyzing: Boolean = false,
    val analyzedBytes: Long = 0L,
    val analyzedRoot: File? = null,
    val usages: List<DirUsage> = emptyList(),
    /** Media-library breakdown by type (MediaStore): images, videos, audio, docs… */
    val mediaSummaries: List<CategorySummary> = emptyList(),
    val mediaLoading: Boolean = true,
    /** Items currently held in the toadFILES trash across volumes. */
    val trashCount: Int = 0,
    val trashBytes: Long = 0L,
    /** True while a pull-to-refresh (or init) reload is in flight. */
    val refreshing: Boolean = false,
)

/** Storage overview: real volumes, media-by-type breakdown, trash usage, on-demand analysis. */
class StorageViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state

    private var analyzeJob: Job? = null
    private var refreshJob: Job? = null

    /** Monotonically increasing request id; only the newest refresh may apply its result. */
    private var refreshSeq = 0L

    init {
        refresh()
    }

    fun refresh() {
        val token = ++refreshSeq
        _state.update { it.copy(refreshing = true) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(ioDispatcher) {
            val roots = container.fileRepo.roots()
            val media = runCatching { container.mediaRepo.summaries() }.getOrDefault(emptyList())
            val trashEntries = runCatching { Trash.entries(roots.map { it.dir }) }.getOrDefault(emptyList())
            if (token != refreshSeq) return@launch // superseded by a newer refresh
            _state.update {
                it.copy(
                    roots = roots,
                    mediaSummaries = media,
                    mediaLoading = false,
                    trashCount = trashEntries.size,
                    trashBytes = trashEntries.sumOf { e -> FileOps.sizeOf(e.file) },
                    refreshing = false,
                )
            }
        }
    }

    fun cancelAnalyze() {
        analyzeJob?.cancel()
        _state.update { it.copy(analyzing = false) }
    }

    fun analyzeTopLevel(root: File) {
        if (_state.value.analyzing) return
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return
        _state.update {
            it.copy(analyzing = true, analyzedBytes = 0L, usages = emptyList(), analyzedRoot = root)
        }
        analyzeJob = viewModelScope.launch(ioDispatcher) {
            val result = mutableListOf<DirUsage>()
            var progress = 0L
            try {
                for (dir in dirs.sortedBy { it.name.lowercase() }) {
                    currentCoroutineContext().ensureActive()
                    val size = FileOps.sizeOf(dir)
                    progress += size
                    result += DirUsage(dir, size)
                    _state.update {
                        it.copy(
                            analyzedBytes = progress,
                            usages = result.sortedByDescending { d -> d.sizeBytes },
                        )
                    }
                }
            } finally {
                _state.update { it.copy(analyzing = false) }
            }
        }
    }
}
