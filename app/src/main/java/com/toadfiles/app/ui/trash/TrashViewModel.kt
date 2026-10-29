package com.toadfiles.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.domain.Trash
import com.toadfiles.app.domain.TrashEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TrashUiState(
    val entries: List<TrashEntry> = emptyList(),
    val loading: Boolean = false,
    val confirmingEmpty: Boolean = false,
    val toast: String? = null,
)

/** Browse's trash: restore, delete forever, empty — all on real files, all on Dispatchers.IO. */
class TrashViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state

    private val volumeRoots: List<File>
        get() = container.fileRepo.roots().map { it.dir }

    init {
        refresh(purgeFirst = true)
    }

    fun refresh(purgeFirst: Boolean = false) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(ioDispatcher) {
            if (purgeFirst) Trash.purge(volumeRoots)
            _state.update { it.copy(entries = Trash.entries(volumeRoots), loading = false) }
        }
    }

    fun restore(entry: TrashEntry) {
        viewModelScope.launch(ioDispatcher) {
            val ok = Trash.restore(entry.file) != null
            _state.update {
                it.copy(
                    toast = if (ok) "Restored \"${entry.originPath.substringAfterLast('/')}\""
                    else "Could not restore — the original location is unavailable",
                )
            }
            refresh()
        }
    }

    fun deleteForever(entry: TrashEntry) {
        viewModelScope.launch(ioDispatcher) {
            Trash.deleteForever(entry.file)
            refresh()
        }
    }

    fun requestEmpty() = _state.update { it.copy(confirmingEmpty = true) }
    fun dismissEmpty() = _state.update { it.copy(confirmingEmpty = false) }

    fun emptyNow() {
        _state.update { it.copy(confirmingEmpty = false) }
        viewModelScope.launch(ioDispatcher) {
            val removed = Trash.empty(volumeRoots)
            _state.update { it.copy(toast = "Trash emptied — $removed item(s) removed forever") }
            refresh()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
