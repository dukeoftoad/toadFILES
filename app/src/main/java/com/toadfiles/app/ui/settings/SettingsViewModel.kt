package com.toadfiles.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.domain.SevenZipOpenAction
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import com.toadfiles.app.domain.ZipOpenAction
import com.toadfiles.app.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<ToadSettings> = container.settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ToadSettings())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settingsRepo.setThemeMode(mode) }
    fun setDynamic(enabled: Boolean) = viewModelScope.launch { container.settingsRepo.setDynamicColor(enabled) }
    fun setAccent(accent: AccentColor) = viewModelScope.launch { container.settingsRepo.setAccentColor(accent) }
    fun setShowHidden(enabled: Boolean) = viewModelScope.launch { container.settingsRepo.setShowHidden(enabled) }
    fun setSort(mode: SortMode) = viewModelScope.launch {
        container.settingsRepo.setSortMode(mode)
        container.settingsRepo.setSortOrder(SortOrder.ASC)
    }
    fun setContentSearch(enabled: Boolean) = viewModelScope.launch { container.settingsRepo.setContentSearch(enabled) }
    fun setSearchResultLimit(limit: Int) = viewModelScope.launch { container.settingsRepo.setSearchResultLimit(limit) }

    // ---- v1.7.3: archive behavior + navigation UX ----

    fun setZipOpenAction(action: ZipOpenAction) =
        viewModelScope.launch { container.settingsRepo.setZipOpenAction(action) }
    fun setSevenZipOpenAction(action: SevenZipOpenAction) =
        viewModelScope.launch { container.settingsRepo.setSevenZipOpenAction(action) }
    fun setNavStyle(style: NavStyle) =
        viewModelScope.launch { container.settingsRepo.setNavStyle(style) }
    fun setBackKeyAction(action: BackKeyAction) =
        viewModelScope.launch { container.settingsRepo.setBackKeyAction(action) }
    fun setDeleteAfterCompress(enabled: Boolean) =
        viewModelScope.launch { container.settingsRepo.setDeleteAfterCompress(enabled) }
    fun setDeleteAfterExtract(enabled: Boolean) =
        viewModelScope.launch { container.settingsRepo.setDeleteAfterExtract(enabled) }
    fun setAbortConfirm(enabled: Boolean) =
        viewModelScope.launch { container.settingsRepo.setAbortConfirm(enabled) }
    fun setOverwriteConfirm(enabled: Boolean) =
        viewModelScope.launch { container.settingsRepo.setOverwriteConfirm(enabled) }
    fun setArchiveFolder(path: String) =
        viewModelScope.launch { container.settingsRepo.setArchiveFolder(path) }

    fun removeFavorite(path: String) = viewModelScope.launch { container.settingsRepo.toggleFavorite(path) }
}
