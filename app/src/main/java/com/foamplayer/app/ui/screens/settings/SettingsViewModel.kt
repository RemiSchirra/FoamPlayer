package com.foamplayer.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.data.backup.BackupFeature
import com.foamplayer.app.data.backup.BackupResult
import com.foamplayer.app.data.backup.SettingsBackupManager
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistImageScanState
import com.foamplayer.app.data.scanner.ArtistImageScanner
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository,
    private val artistImageScanner: ArtistImageScanner,
    private val settingsBackupManager: SettingsBackupManager
) : ViewModel() {

    /** One observable state for every settings screen and the application theme. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // Seed with defaults instead of a runBlocking DataStore read: this VM is created
        // during first composition, and the blocking read used to land on the main
        // thread's first frame. Consumers tolerate defaults for a frame until the real
        // values arrive.
        initialValue = AppSettings()
    )

    /**
     * Raw onboarding flag, null until DataStore's first emission. The null state lets
     * the app root hold a blank frame instead of flashing the onboarding for existing
     * users while the stored value loads.
     */
    val onboardingCompleted: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.onboardingCompleted }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus

    /** Live progress of the artist image scan (found / total artists). */
    val artistImageScan: StateFlow<ArtistImageScanState> = artistImageScanner.state

    fun setBitPerfectEnabled(enabled: Boolean) = launch { settingsRepository.setBitPerfectEnabled(enabled) }
    fun setListenedThresholdPercent(percent: Int) = launch {
        settingsRepository.setListenedThresholdPercent(percent)
    }
    fun setIconStyle(style: FoamIconStyle) = launch { settingsRepository.setIconStyle(style) }
    fun setMiniPlayerRounding(rounding: Int) = launch { settingsRepository.setMiniPlayerRounding(rounding) }
    fun setFullPlayerRounding(rounding: Int) = launch { settingsRepository.setFullPlayerRounding(rounding) }
    fun setMarqueeMode(mode: MarqueeMode) = launch { settingsRepository.setMarqueeMode(mode) }
    fun addFolder(path: String) = launch { settingsRepository.addFolder(path) }
    fun removeFolder(path: String) = launch { settingsRepository.removeFolder(path) }
    fun setArtistSeparators(separators: Set<String>) = launch { settingsRepository.setArtistSeparators(separators) }
    fun setGenreSeparators(separators: Set<String>) = launch { settingsRepository.setGenreSeparators(separators) }
    fun setAutomaticColorEnabled(enabled: Boolean) = launch { settingsRepository.setAutomaticColorEnabled(enabled) }
    fun setAccentColor(color: Int) = launch { settingsRepository.setAccentColor(color) }
    fun setImmersiveModeEnabled(enabled: Boolean) = launch { settingsRepository.setImmersiveModeEnabled(enabled) }
    fun setAnimateSeekBarEnabled(enabled: Boolean) = launch { settingsRepository.setAnimateSeekBarEnabled(enabled) }
    fun setAutoOpenPlayerEnabled(enabled: Boolean) = launch { settingsRepository.setAutoOpenPlayerEnabled(enabled) }
    fun setAnimatedCoverEnabled(enabled: Boolean) = launch { settingsRepository.setAnimatedCoverEnabled(enabled) }
    fun setAnimatedCoverAllowMobileData(enabled: Boolean) = launch {
        settingsRepository.setAnimatedCoverAllowMobileData(enabled)
    }
    fun setDynamicBackgroundEnabled(enabled: Boolean) = launch { settingsRepository.setDynamicBackgroundEnabled(enabled) }
    fun setBackgroundMode(mode: BackgroundMode) = launch { settingsRepository.setBackgroundMode(mode) }
    fun setCustomBackgroundUri(uri: String?) = launch { settingsRepository.setCustomBackgroundUri(uri) }
    fun setGradientStops(stops: List<GradientStop>) = launch { settingsRepository.setGradientStops(stops) }
    fun setBackgroundBlur(blur: Int) = launch { settingsRepository.setBackgroundBlur(blur) }
    fun setAppBackgroundIntensity(intensity: Int) = launch {
        settingsRepository.setAppBackgroundIntensity(intensity)
    }
    fun setPlayerBackgroundIntensity(intensity: Int) = launch {
        settingsRepository.setPlayerBackgroundIntensity(intensity)
    }
    fun setForceDynamicBackgroundInCollections(enabled: Boolean) = launch {
        settingsRepository.setForceDynamicBackgroundInCollections(enabled)
    }
    fun setForceDynamicBackgroundInPlayer(enabled: Boolean) = launch {
        settingsRepository.setForceDynamicBackgroundInPlayer(enabled)
    }

    fun setCollectionLayoutDefault(layout: CollectionHeroLayout) = launch {
        settingsRepository.setCollectionLayoutDefault(layout)
    }

    fun setCollectionLayoutArtist(layout: CollectionHeroLayout) = launch {
        settingsRepository.setCollectionLayoutArtist(layout)
    }

    fun setCollectionLayoutAlbum(layout: CollectionHeroLayout) = launch {
        settingsRepository.setCollectionLayoutAlbum(layout)
    }

    fun setCollectionLayoutPlaylist(layout: CollectionHeroLayout) = launch {
        settingsRepository.setCollectionLayoutPlaylist(layout)
    }

    fun setOnboardingCompleted(completed: Boolean) = launch {
        settingsRepository.setOnboardingCompleted(completed)
    }

    fun startScan() = launch { trackRepository.rescanLibrary() }

    fun setArtistImageResolveEnabled(enabled: Boolean) = launch {
        settingsRepository.setArtistImageResolveEnabled(enabled)
    }

    fun startArtistImageScan() = artistImageScanner.startScan()

    fun stopArtistImageScan() = artistImageScanner.stopScan()

    fun clearArtistImageCache() = artistImageScanner.clearCache()

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

    fun setLyricsLineBlur(enabled: Boolean) = launch {
        settingsRepository.setLyricsLineBlur(enabled)
    }

    fun setLanguage(language: String) = launch {
        settingsRepository.setLanguage(language)
    }

    fun setThemeMode(mode: ThemeMode) = launch {
        settingsRepository.setThemeMode(mode)
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

    // --------------------------------------------------------------- backups

    /** Reachable for the import sheet's feature probe. */
    val backupManager: SettingsBackupManager = settingsBackupManager

    /** Live result of the last export/import; consumed by the settings sheet. */
    private val _backupResult = MutableStateFlow<BackupResult?>(null)
    val backupResult: StateFlow<BackupResult?> = _backupResult.asStateFlow()

    /** True while an import is running; shows the loading modal. */
    private val _importRunning = MutableStateFlow(false)
    val importRunning: StateFlow<Boolean> = _importRunning.asStateFlow()

    private var backupJob: Job? = null

    val backupRunning: Boolean get() = backupJob?.isActive == true

    fun exportSettings(folderUri: String, features: Set<BackupFeature>) {
        if (backupRunning) return
        backupJob = viewModelScope.launch {
            _backupResult.value = backupManager.export(folderUri, features)
        }
    }

    fun importSettings(fileUris: List<String>, features: Set<BackupFeature>) {
        if (backupRunning) return
        backupJob = viewModelScope.launch {
            _importRunning.value = true
            try {
                _backupResult.value = backupManager.importFeatures(fileUris, features)
            } finally {
                _importRunning.value = false
            }
        }
    }

    fun consumeBackupResult() {
        _backupResult.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}