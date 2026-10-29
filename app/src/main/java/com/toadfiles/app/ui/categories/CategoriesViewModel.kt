package com.toadfiles.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.FileCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class CategoriesUiState(
    val summaries: List<CategorySummary> = emptyList(),
    val loading: Boolean = false,
    val selected: FileCategory? = null,
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val entriesLoading: Boolean = false,
    val toast: String? = null,
)

/**
 * Drives the Categories tab: a grid of media-library categories, each opening a flat
 * list of that category's files. Everything comes from MediaStore on demand — the tab
 * never walks the filesystem itself, so it costs nothing until the user opens it.
 */
class CategoriesViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoriesUiState())
    val state: StateFlow<CategoriesUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(ioDispatcher) {
            val summaries = runCatching { container.mediaRepo.summaries() }.getOrDefault(emptyList())
            _state.update { it.copy(summaries = summaries, loading = false) }
        }
    }

    fun open(category: FileCategory) {
        _state.update { it.copy(selected = category, entries = emptyList(), entriesLoading = true) }
        viewModelScope.launch(ioDispatcher) {
            val entries = runCatching { container.mediaRepo.list(category) }.getOrDefault(emptyList())
            _state.update { it.copy(entries = entries, entriesLoading = false) }
        }
    }

    fun close() {
        _state.update { it.copy(selected = null, entries = emptyList()) }
    }

    /**
     * Same local-only share the Browse sheet and Recent use: files are copied into the
     * app-private shared folder, nothing is handed to another app. Media entries whose
     * on-disk path is unknown are reported honestly instead of silently dropped.
     */
    fun shareLocal(entries: List<MediaRepository.MediaEntry>) {
        val paths = entries.mapNotNull { it.data }.map(::File)
        if (paths.isEmpty()) {
            _state.update { it.copy(toast = "Can't share this item — no on-disk path") }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val r = container.sharedRepo.copyIn(paths)
            _state.update {
                it.copy(
                    toast = when {
                        r.copied == 0 -> "Could not share any files"
                        r.skipped == 0 -> "Shared ${r.copied} file(s) locally"
                        else -> "Shared ${r.copied} of ${r.copied + r.skipped} file(s) locally"
                    },
                )
            }
        }
    }

    fun requestToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
