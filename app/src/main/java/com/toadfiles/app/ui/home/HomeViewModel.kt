package com.toadfiles.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.StorageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class HomeUiState(
    val storage: StorageStats? = null,
    val quickLinks: List<Pair<String, File>> = emptyList(),
    val favorites: List<FileItem> = emptyList(),
    val categories: List<CategorySummary> = emptyList(),
    val loadingCategories: Boolean = false,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        refreshStorage()
        refreshQuickLinks()
        viewModelScope.launch {
            container.settingsRepo.settings.collect { s ->
                refreshFavorites(s.favorites)
            }
        }
        refreshCategories()
    }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = container.fileRepo.statsOf(container.fileRepo.primaryRoot())
            _state.update { it.copy(storage = stats) }
        }
    }

    private fun refreshQuickLinks() {
        viewModelScope.launch(Dispatchers.IO) {
            val links = container.fileRepo.quickLinks()
            _state.update { it.copy(quickLinks = links) }
        }
    }

    private fun refreshFavorites(paths: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = paths.mapNotNull { p ->
                val f = File(p)
                if (f.exists()) FileItem(f, f.name, f.isDirectory, f.length(), f.lastModified()) else null
            }.sortedBy { it.name.lowercase() }
            _state.update { it.copy(favorites = items) }
        }
    }

    private fun refreshCategories() {
        _state.update { it.copy(loadingCategories = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val summaries = container.mediaRepo.summaries()
            _state.update { it.copy(categories = summaries, loadingCategories = false) }
        }
    }

    /** Re-reads everything that can change while the user is in other tabs. */
    fun refresh() {
        refreshStorage()
        refreshQuickLinks()
        refreshCategories()
    }

    fun toggleFavorite(path: String) {
        viewModelScope.launch { container.settingsRepo.toggleFavorite(path) }
    }
}
