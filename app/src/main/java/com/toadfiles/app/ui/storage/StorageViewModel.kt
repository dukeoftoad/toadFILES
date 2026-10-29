package com.toadfiles.app.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.FileOps
import com.toadfiles.app.domain.StorageStats
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
    val stats: StorageStats? = null,
    val analyzing: Boolean = false,
    val analyzedBytes: Long = 0L,
    val usages: List<DirUsage> = emptyList(),
)

/** Storage analyzer: computes real folder sizes only on demand, never at startup, fully cancellable. */
class StorageViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state

    private var analyzeJob: Job? = null

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = container.fileRepo.statsOf(container.fileRepo.primaryRoot())
            _state.update { it.copy(stats = stats) }
        }
    }

    fun cancelAnalyze() {
        analyzeJob?.cancel()
        _state.update { it.copy(analyzing = false) }
    }

    fun analyzeTopLevel() {
        if (_state.value.analyzing) return
        val root = container.fileRepo.primaryRoot()
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return
        _state.update { it.copy(analyzing = true, analyzedBytes = 0L, usages = emptyList()) }
        analyzeJob = viewModelScope.launch(Dispatchers.IO) {
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
