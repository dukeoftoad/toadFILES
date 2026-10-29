package com.toadfiles.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoriesUiState(
    val summaries: List<CategorySummary> = emptyList(),
    val loading: Boolean = false,
    val selected: FileCategory? = null,
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val entriesLoading: Boolean = false,
)

class CategoriesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CategoriesUiState())
    val state: StateFlow<CategoriesUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val summaries = container.mediaRepo.summaries()
            _state.update { it.copy(summaries = summaries, loading = false) }
        }
    }

    fun open(category: FileCategory) {
        _state.update { it.copy(selected = category, entries = emptyList(), entriesLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val entries = container.mediaRepo.list(category)
            _state.update { it.copy(entries = entries, entriesLoading = false) }
        }
    }

    fun close() {
        _state.update { it.copy(selected = null, entries = emptyList()) }
    }
}
