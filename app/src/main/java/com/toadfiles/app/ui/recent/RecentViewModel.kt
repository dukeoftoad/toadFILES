package com.toadfiles.app.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class RecentUiState(
    val entries: List<MediaRepository.MediaEntry> = emptyList(),
    val loading: Boolean = false,
    val toast: String? = null,
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

    /**
     * Local-only share: copies [files] into the app-private shared folder. Used by the
     * Recent screen's per-row share button. Honest toast instead of silent failure.
     */
    fun shareLocal(files: List<File>) {
        if (files.isEmpty()) {
            _state.update { it.copy(toast = "Nothing selected to share") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val r = container.sharedRepo.copyIn(files)
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
