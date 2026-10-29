package com.toadfiles.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.data.ToadSettings
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
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
    fun setShowHidden(enabled: Boolean) = viewModelScope.launch { container.settingsRepo.setShowHidden(enabled) }
    fun setSort(mode: SortMode) = viewModelScope.launch {
        container.settingsRepo.setSortMode(mode)
        container.settingsRepo.setSortOrder(SortOrder.ASC)
    }

    fun removeFavorite(path: String) = viewModelScope.launch { container.settingsRepo.toggleFavorite(path) }
}
