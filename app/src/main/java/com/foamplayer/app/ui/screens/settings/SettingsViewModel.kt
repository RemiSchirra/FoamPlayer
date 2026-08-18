package com.foamplayer.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.theme.FoamIconStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    val isBitPerfectEnabled: StateFlow<Boolean> = settingsRepository.isBitPerfectEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val iconStyle: StateFlow<FoamIconStyle> = settingsRepository.iconStyle.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FoamIconStyle.FLAT
    )

    val selectedFolders: StateFlow<Set<String>> = settingsRepository.selectedFolders.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScanStatus.Idle
    )

    fun setBitPerfectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBitPerfectEnabled(enabled)
        }
    }

    fun setIconStyle(style: FoamIconStyle) {
        viewModelScope.launch {
            settingsRepository.setIconStyle(style)
        }
    }

    fun addFolder(path: String) {
        viewModelScope.launch {
            settingsRepository.addFolder(path)
        }
    }

    fun removeFolder(path: String) {
        viewModelScope.launch {
            settingsRepository.removeFolder(path)
        }
    }

    fun startScan() {
        viewModelScope.launch {
            trackRepository.rescanLibrary()
        }
    }
}