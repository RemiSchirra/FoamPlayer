package com.foamplayer.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.FoamIconStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    /** One observable state for every settings screen and the application theme. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = runBlocking { settingsRepository.settings.first() }
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
    fun setAppBackgroundIntensity(intensity: Int) = launch {
        settingsRepository.setAppBackgroundIntensity(intensity)
    }
    fun setPlayerBackgroundIntensity(intensity: Int) = launch {
        settingsRepository.setPlayerBackgroundIntensity(intensity)
    }
    fun startScan() = launch { trackRepository.rescanLibrary() }

    fun setLyricsProviderOrder(order: List<PreferredLyricsProvider>) = launch {
        settingsRepository.setLyricsProviderOrder(order)
    }

    fun setLyricsProviderEnabled(provider: PreferredLyricsProvider, enabled: Boolean) = launch {
        val currentEnabled = settings.value.enabledLyricsProviders
        val newEnabled = if (enabled) currentEnabled + provider else currentEnabled - provider
        settingsRepository.setEnabledLyricsProviders(newEnabled)
    }

    fun setLyricsSourceOrder(order: List<LyricsSource>) = launch {
        settingsRepository.setLyricsSourceOrder(order)
    }

    fun setLyricsSourceEnabled(source: LyricsSource, enabled: Boolean) = launch {
        val currentEnabled = settings.value.enabledLyricsSources
        val newEnabled = if (enabled) currentEnabled + source else currentEnabled - source
        settingsRepository.setEnabledLyricsSources(newEnabled)
    }

    fun setLanguage(language: String) = launch {
        settingsRepository.setLanguage(language)
    }

    fun setNavigationBarMode(mode: NavigationBarMode) = launch {
        settingsRepository.setNavigationBarMode(mode)
    }

    fun setNavBarTransparentWhenPlayerOpen(enabled: Boolean) = launch {
        settingsRepository.setNavBarTransparentWhenPlayerOpen(enabled)
    }

    fun setNavBarPrimaryOrder(order: List<NavBarItem>) = launch {
        settingsRepository.setNavBarPrimaryOrder(order)
    }

    fun setNavBarPrimaryItemEnabled(item: NavBarItem, enabled: Boolean) = launch {
        val current = settings.value
        // Settings must exist in at least one of the two bars.
        if (item == NavBarItem.SETTINGS && !enabled && item !in current.navBarSecondaryEnabled) return@launch
        val newEnabled = if (enabled) current.navBarPrimaryEnabled + item else current.navBarPrimaryEnabled - item
        settingsRepository.setNavBarPrimaryEnabled(newEnabled)
    }

    fun setNavBarSecondaryOrder(order: List<NavBarItem>) = launch {
        settingsRepository.setNavBarSecondaryOrder(order)
    }

    fun setNavBarSecondaryItemEnabled(item: NavBarItem, enabled: Boolean) = launch {
        val current = settings.value
        // Settings must exist in at least one of the two bars.
        if (item == NavBarItem.SETTINGS && !enabled && item !in current.navBarPrimaryEnabled) return@launch
        val newEnabled = if (enabled) current.navBarSecondaryEnabled + item else current.navBarSecondaryEnabled - item
        settingsRepository.setNavBarSecondaryEnabled(newEnabled)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
