package io.github.dukeoftoad.toadfiles.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.toadfiles.AppContainer
import io.github.dukeoftoad.toadfiles.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecentUiState(
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val loading: Boolean = false,
)

class RecentViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(RecentUiState())
    val state: StateFlow<RecentUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val entries = container.mediaRepo.recent()
            _state.update { it.copy(entries = entries, loading = false) }
        }
    }
}
