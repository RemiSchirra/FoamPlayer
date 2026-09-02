package com.foamplayer.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.theme.FoamIconStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
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

    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> = stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = initial
    )

    val isBitPerfectEnabled: StateFlow<Boolean> =
        settingsRepository.isBitPerfectEnabled.asState(true)

    val iconStyle: StateFlow<FoamIconStyle> =
        settingsRepository.iconStyle.asState(FoamIconStyle.FLAT)

    val miniPlayerRounding: StateFlow<Int> =
        settingsRepository.miniPlayerRounding.asState(8)

    val fullPlayerRounding: StateFlow<Int> =
        settingsRepository.fullPlayerRounding.asState(24)

    val selectedFolders: StateFlow<Set<String>> =
        settingsRepository.selectedFolders.asState(emptySet())

    val artistSeparators: StateFlow<Set<String>> =
        settingsRepository.artistSeparators.asState(setOf(";", ","))

    val genreSeparators: StateFlow<Set<String>> =
        settingsRepository.genreSeparators.asState(setOf(";", ","))

    val isAutomaticColorEnabled: StateFlow<Boolean> =
        settingsRepository.isAutomaticColorEnabled.asState(true)

    val accentColor: StateFlow<Int> =
        settingsRepository.accentColor.asState(0xFF6200EE.toInt())

    val isImmersiveModeEnabled: StateFlow<Boolean> =
        settingsRepository.isImmersiveModeEnabled.asState(true)

    val isAnimateSeekBarEnabled: StateFlow<Boolean> =
        settingsRepository.isAnimateSeekBarEnabled.asState(false)

    val isAutoOpenPlayerEnabled: StateFlow<Boolean> =
        settingsRepository.isAutoOpenPlayerEnabled.asState(false)

    val scanStatus: StateFlow<ScanStatus> =
        trackRepository.scanStatus.asState(ScanStatus.Idle)

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

    fun startScan() = launch { trackRepository.rescanLibrary() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}