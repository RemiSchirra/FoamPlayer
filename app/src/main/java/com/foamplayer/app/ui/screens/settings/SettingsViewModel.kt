package com.foamplayer.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.FoamIconStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    /** One observable state for every settings screen and the application theme. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus

    fun setBitPerfectEnabled(enabled: Boolean) = launch { settingsRepository.setBitPerfectEnabled(enabled) }
    fun setIconStyle(style: FoamIconStyle) = launch { settingsRepository.setIconStyle(style) }
    fun setMiniPlayerRounding(rounding: Int) = launch { settingsRepository.setMiniPlayerRounding(rounding) }
    fun setFullPlayerRounding(rounding: Int) = launch { settingsRepository.setFullPlayerRounding(rounding) }
    fun addFolder(path: String) = launch { settingsRepository.addFolder(path) }
    fun removeFolder(path: String) = launch { settingsRepository.removeFolder(path) }
    fun setArtistSeparators(separators: Set<String>) = launch { settingsRepository.setArtistSeparators(separators) }
    fun setGenreSeparators(separators: Set<String>) = launch { settingsRepository.setGenreSeparators(separators) }
    fun setAutomaticColorEnabled(enabled: Boolean) = launch { settingsRepository.setAutomaticColorEnabled(enabled) }
    fun setAccentColor(color: Int) = launch { settingsRepository.setAccentColor(color) }
    fun setImmersiveModeEnabled(enabled: Boolean) = launch { settingsRepository.setImmersiveModeEnabled(enabled) }
    fun setAnimateSeekBarEnabled(enabled: Boolean) = launch { settingsRepository.setAnimateSeekBarEnabled(enabled) }
    fun setAutoOpenPlayerEnabled(enabled: Boolean) = launch { settingsRepository.setAutoOpenPlayerEnabled(enabled) }
    fun setDynamicBackgroundEnabled(enabled: Boolean) = launch { settingsRepository.setDynamicBackgroundEnabled(enabled) }
    fun setBackgroundBlur(blur: Int) = launch { settingsRepository.setBackgroundBlur(blur) }
    fun startScan() = launch { trackRepository.rescanLibrary() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
