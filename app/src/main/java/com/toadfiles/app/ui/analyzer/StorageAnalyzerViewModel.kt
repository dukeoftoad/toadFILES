package com.toadfiles.app.ui.analyzer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.StorageRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class StorageAnalyzerUiState(
    /** Mounted volumes offered before a scan starts (same source as the Storage screen). */
    val volumes: List<StorageRoot> = emptyList(),
    val volumesLoading: Boolean = true,
    /** Volume currently being scanned (or last scanned). */
    val root: File? = null,
    val scanning: Boolean = false,
    val scannedFiles: Int = 0,
    val scannedBytes: Long = 0L,
    /** Latest (possibly partial) scan result; kept after completion and cancellation. */
    val result: AnalyzerResult? = null,
    val error: String? = null,
)

/**
 * MI Files / Google Files style storage analyzer: one cancellable recursive walk per
 * volume on Dispatchers.IO, with progressive partial results (categories and biggest
 * files are always current; folders appear as their subtrees complete). Nothing runs
 * in the background — a scan only happens when the user asks for it.
 */
class StorageAnalyzerViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageAnalyzerUiState())
    val state: StateFlow<StorageAnalyzerUiState> = _state

    private var scanJob: Job? = null
    private var volumesJob: Job? = null

    init {
        loadVolumes()
    }

    fun loadVolumes() {
        volumesJob?.cancel()
        volumesJob = viewModelScope.launch(ioDispatcher) {
            val roots = container.fileRepo.roots()
            _state.update { it.copy(volumes = roots, volumesLoading = false) }
        }
    }

    /**
     * Auto-scan on entry (route argument): never restarts a scan of a root that is
     * already scanned or in progress, so back-navigation does not re-trigger the walk.
     */
    fun autoScan(root: File) {
        val s = _state.value
        if (s.root == root && (s.scanning || s.result != null)) return
        scan(root)
    }

    fun scan(root: File) {
        scanJob?.cancel()
        _state.update {
            it.copy(
                root = root,
                scanning = true,
                scannedFiles = 0,
                scannedBytes = 0L,
                result = null,
                error = null,
            )
        }
        scanJob = viewModelScope.launch(ioDispatcher) {
            if (!root.isDirectory) {
                _state.update {
                    it.copy(scanning = false, error = "\"${root.absolutePath}\" is not a folder.")
                }
                return@launch
            }
            if (root.listFiles() == null) {
                _state.update {
                    it.copy(
                        scanning = false,
                        error = "Could not read \"${root.absolutePath}\". " +
                            "Check that toadFILES has access to this folder.",
                    )
                }
                return@launch
            }
            val acc = SizeAccumulator()
            var files = 0
            var bytes = 0L
            try {
                val result = StorageAnalyzer.scan(root, acc) { size ->
                    files++
                    bytes += size
                    // Publish a progressive snapshot every PROGRESS_EVERY files: the walk
                    // keeps mutating the accumulator, but the callback runs on the same
                    // coroutine, so the snapshot is always a consistent point-in-time view.
                    if (files % PROGRESS_EVERY == 0) {
                        _state.update {
                            it.copy(scannedFiles = files, scannedBytes = bytes, result = acc.build())
                        }
                    }
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        result = result,
                        scannedFiles = result.fileCount,
                        scannedBytes = result.totalBytes,
                    )
                }
            } catch (ce: CancellationException) {
                // Cancelled mid-walk: keep the last partial snapshot, flip scanning off
                // in finally. Re-throw so the coroutine completes as cancelled.
                throw ce
            } finally {
                _state.update { it.copy(scanning = false, scannedFiles = files, scannedBytes = bytes) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    /** Back to the volume picker; forgets results and errors, reloads volumes. */
    fun reset() {
        scanJob?.cancel()
        _state.update {
            StorageAnalyzerUiState(volumes = it.volumes, volumesLoading = it.volumesLoading)
        }
        loadVolumes()
    }

    private companion object {
        /** State-publish granularity for the walk; smaller means smoother, chattier progress. */
        private const val PROGRESS_EVERY = 512
    }
}
