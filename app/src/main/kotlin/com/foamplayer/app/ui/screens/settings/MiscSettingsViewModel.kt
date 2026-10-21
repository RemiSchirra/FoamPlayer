package com.foamplayer.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.service.LockScreenPlayerLauncher
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.NotificationExtraButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MiscSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setDisplayOnLockScreen(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDisplayOnLockScreen(enabled)
        }
    }

    fun setFastLockScreenEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFastLockScreenEnabled(enabled)
        }
    }

    fun setNotificationButtonLeft(button: NotificationExtraButton) {
        viewModelScope.launch {
            settingsRepository.setNotificationButtonLeft(button)
        }
    }

    fun setNotificationButtonRight(button: NotificationExtraButton) {
        viewModelScope.launch {
            settingsRepository.setNotificationButtonRight(button)
        }
    }

    /** Whether the system lets the app show the lock player over other apps. */
    fun canDrawOverlays(context: Context): Boolean = LockScreenPlayerLauncher.canDrawOverlays(context)

    /** Opens the system screen granting the "Display over other apps" permission. */
    fun openOverlaySettings(context: Context) {
        LockScreenPlayerLauncher.openOverlaySettings(context)
    }
}
