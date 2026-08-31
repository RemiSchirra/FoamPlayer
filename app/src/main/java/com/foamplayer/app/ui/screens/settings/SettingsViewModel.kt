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

    val miniPlayerRounding: StateFlow<Int> = settingsRepository.miniPlayerRounding.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 8
    )

    val fullPlayerRounding: StateFlow<Int> = settingsRepository.fullPlayerRounding.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 24
    )

    val selectedFolders: StateFlow<Set<String>> = settingsRepository.selectedFolders.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    val artistSeparators: StateFlow<Set<String>> = settingsRepository.artistSeparators.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = setOf(";", ",")
    )

    val genreSeparators: StateFlow<Set<String>> = settingsRepository.genreSeparators.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = setOf(";", ",")
    )

    val isAutomaticColorEnabled: StateFlow<Boolean> = settingsRepository.isAutomaticColorEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val accentColor: StateFlow<Int> = settingsRepository.accentColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0xFF6200EE.toInt()
    )

    val isImmersiveModeEnabled: StateFlow<Boolean> = settingsRepository.isImmersiveModeEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val isAnimateSeekBarEnabled: StateFlow<Boolean> = settingsRepository.isAnimateSeekBarEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScanStatus.Idle
    )

    val isAutoOpenPlayerEnabled: StateFlow<Boolean> = settingsRepository.isAutoOpenPlayerEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
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

    fun setMiniPlayerRounding(rounding: Int) {
        viewModelScope.launch {
            settingsRepository.setMiniPlayerRounding(rounding)
        }
    }

    fun setFullPlayerRounding(rounding: Int) {
        viewModelScope.launch {
            settingsRepository.setFullPlayerRounding(rounding)
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

    fun setArtistSeparators(separators: Set<String>) {
        viewModelScope.launch {
            settingsRepository.setArtistSeparators(separators)
        }
    }

    fun setGenreSeparators(separators: Set<String>) {
        viewModelScope.launch {
            settingsRepository.setGenreSeparators(separators)
        }
    }

    fun setAutomaticColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutomaticColorEnabled(enabled)
        }
    }

    fun setAccentColor(color: Int) {
        viewModelScope.launch {
            settingsRepository.setAccentColor(color)
        }
    }

    fun setImmersiveModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setImmersiveModeEnabled(enabled)
        }
    }

    fun setAnimateSeekBarEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAnimateSeekBarEnabled(enabled)
        }
    }

    fun setAutoOpenPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoOpenPlayerEnabled(enabled)
        }
    }

    fun startScan() {
        viewModelScope.launch {
            trackRepository.rescanLibrary()
        }
    }
}
