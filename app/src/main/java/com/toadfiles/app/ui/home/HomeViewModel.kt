package com.toadfiles.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.StorageStats
import kotlinx.coroutines.CoroutineDispatcher
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
    /**
     * Smart-recent feed on Home. Replaces the old Categories section: the user wanted one
     * unified home, with the previously-separate Recent tab folded in. We list files the
     * OS media library reports as recently modified, newest first, capped at [RECENT_LIMIT].
     */
    val recentFiles: List<MediaRepository.MediaEntry> = emptyList(),
    val loadingRecent: Boolean = false,
    /** Kept for the Storage screen which still uses category counts; not shown on Home anymore. */
    val categories: List<CategorySummary> = emptyList(),
    val loadingCategories: Boolean = false,
)

class HomeViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        refreshStorage()
        refreshQuickLinks()
        refreshRecent()
        viewModelScope.launch {
            // One-shot cleanup of favorites pointing at paths that no longer exist
            // (file renamed / moved / deleted outside toadFILES, or volume unmounted).
            // The result also flows back via settings so refreshFavorites re-runs.
            container.settingsRepo.pruneDeadFavorites()
        }
        viewModelScope.launch {
            container.settingsRepo.settings.collect { s ->
                refreshFavorites(s.favorites)
            }
        }
        refreshCategories()
    }

    fun refreshStorage() {
        viewModelScope.launch(ioDispatcher) {
            val stats = container.fileRepo.statsOf(container.fileRepo.primaryRoot())
            _state.update { it.copy(storage = stats) }
        }
    }

    fun refreshQuickLinks() {
        viewModelScope.launch(ioDispatcher) {
            val links = container.fileRepo.quickLinks()
            _state.update { it.copy(quickLinks = links) }
        }
    }

    /** Smart-recent files from the OS media library, newest first. Bounded to keep the list cheap. */
    fun refreshRecent() {
        _state.update { it.copy(loadingRecent = true) }
        viewModelScope.launch(ioDispatcher) {
            val recents = container.mediaRepo.recent(limit = RECENT_LIMIT)
            _state.update { it.copy(recentFiles = recents, loadingRecent = false) }
        }
    }

    private fun refreshFavorites(paths: Set<String>) {
        viewModelScope.launch(ioDispatcher) {
            val items = paths.mapNotNull { p ->
                val f = File(p)
                if (f.exists()) FileItem(f, f.name, f.isDirectory, f.length(), f.lastModified()) else null
            }.sortedBy { it.name.lowercase() }
            _state.update { it.copy(favorites = items) }
        }
    }

    fun refreshCategories() {
        _state.update { it.copy(loadingCategories = true) }
        viewModelScope.launch(ioDispatcher) {
            val summaries = container.mediaRepo.summaries()
            _state.update { it.copy(categories = summaries, loadingCategories = false) }
        }
    }

    /** Re-reads everything that can change while the user is in other tabs. */
    fun refresh() {
        refreshStorage()
        refreshQuickLinks()
        refreshRecent()
        refreshCategories()
    }

    fun toggleFavorite(path: String) {
        viewModelScope.launch { container.settingsRepo.toggleFavorite(path) }
    }

    companion object {
        /** Bounded recent list — matches the original Recent tab's cap. */
        const val RECENT_LIMIT = 60
    }
}
