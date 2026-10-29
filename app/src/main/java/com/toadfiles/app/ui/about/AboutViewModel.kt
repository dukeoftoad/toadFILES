package com.toadfiles.app.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.StorageStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the About page. Everything is loaded on demand when the user opens About
 * (mirroring the rest of toadFILES — no startup scans) so opening About on a phone with
 * a lot of files is bounded by [MediaRepository] queries, never by the ViewModel itself.
 */
data class AboutUiState(
    val loading: Boolean = false,
    val storage: StorageStats? = null,
    val categoryCounts: List<CategorySummary> = emptyList(),
    val diagnosticsEnabled: Boolean = false,
)

/** Aggregates all the "honest about" data for the About screen in one place. */
class AboutViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState(loading = true))
    val state: StateFlow<AboutUiState> = _state

    init {
        refresh()
        // Diagnostics toggle is reactive — re-read it whenever settings change so the
        // About screen stays in sync with a toggle done from Settings or anywhere else.
        viewModelScope.launch {
            container.settingsRepo.settings.collect { s ->
                _state.update { it.copy(diagnosticsEnabled = s.diagnosticsEnabled) }
            }
        }
    }

    /** Re-fetch every figure shown on the About page. Safe to call repeatedly. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(ioDispatcher) {
            val root = container.fileRepo.primaryRoot()
            val storage = container.fileRepo.statsOf(root)
            val summaries = container.mediaRepo.summaries()
            _state.update {
                it.copy(
                    loading = false,
                    storage = storage,
                    categoryCounts = summaries,
                )
            }
        }
    }

    fun setDiagnostics(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepo.setDiagnosticsEnabled(enabled) }
    }
}
