package com.foamplayer.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.update.DownloadState
import com.foamplayer.app.update.GitHubRelease
import com.foamplayer.app.update.UpdateCheckResult
import com.foamplayer.app.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** States of the About screen's update section. */
sealed interface AboutUiState {
    data object Idle : AboutUiState
    data object Checking : AboutUiState
    data class Available(val release: GitHubRelease) : AboutUiState
    data object UpToDate : AboutUiState
    data object Error : AboutUiState
}

@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val updateManager: UpdateManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** The running build's version name (BuildConfig.VERSION_NAME). */
    val currentVersion: String = updateManager.currentVersion

    val checking: StateFlow<Boolean> = updateManager.checking.asStateFlow()
    val downloadState: StateFlow<DownloadState> = updateManager.downloadState.asStateFlow()

    private val _uiState = MutableStateFlow<AboutUiState>(AboutUiState.Idle)
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    /** The release whose release notes the modal is showing; null when closed. */
    private val _releaseNotes = MutableStateFlow<GitHubRelease?>(null)
    val releaseNotes: StateFlow<GitHubRelease?> = _releaseNotes.asStateFlow()

    init {
        // Surface a cached "update available" immediately (e.g. the auto-check
        // that ran at app start), then fall back to Idle.
        val cached = updateManager.lastCheckResult
        if (cached is UpdateCheckResult.UpdateAvailable) {
            _uiState.value = AboutUiState.Available(cached.release)
        }
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdateCheckEnabled(enabled)
        }
    }

    /** Manual "Check for update"; result lands in [uiState]. */
    fun checkForUpdate() {
        if (updateManager.checking.value) return
        viewModelScope.launch {
            _uiState.value = AboutUiState.Checking
            _uiState.value = when (val result = updateManager.checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> AboutUiState.Available(result.release)
                UpdateCheckResult.UpToDate -> AboutUiState.UpToDate
                UpdateCheckResult.Error -> AboutUiState.Error
            }
        }
    }

    /** Downloads the release's APK; progress notification + install prompt. */
    fun downloadAndInstall(release: GitHubRelease) {
        updateManager.downloadAndInstall(release)
    }

    /** Cancels a running download. */
    fun cancelDownload() = updateManager.cancelDownload()

    /** Launches the system installer for an already-downloaded APK. */
    fun install(file: java.io.File) = updateManager.installApk(file)

    /** The APK downloaded for [release] previously, if still on disk. */
    fun downloadedApkFile(release: GitHubRelease): java.io.File? =
        updateManager.downloadedApk(release)

    fun showReleaseNotes(release: GitHubRelease) {
        _releaseNotes.value = release
    }

    fun dismissReleaseNotes() {
        _releaseNotes.value = null
    }
}
