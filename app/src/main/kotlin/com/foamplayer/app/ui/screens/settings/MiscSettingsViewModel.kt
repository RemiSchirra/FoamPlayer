package com.foamplayer.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.service.LockScreenPlayerLauncher
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.discord.DiscordTokenStore
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.NotificationExtraButton
import com.foamplayer.discord.DiscordApi
import com.foamplayer.discord.DiscordAuthCoordinator
import com.foamplayer.discord.DiscordAuthorizationSession
import com.foamplayer.discord.DiscordAuthSession
import com.foamplayer.discord.DiscordOAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DiscordAuthUiState {
    data object Idle : DiscordAuthUiState
    data object Waiting : DiscordAuthUiState
    data object Success : DiscordAuthUiState
    data class Failure(val message: String?) : DiscordAuthUiState
}

@HiltViewModel
class MiscSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tokenStore: DiscordTokenStore,
    private val oAuth: DiscordOAuth
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val discordSession: StateFlow<DiscordAuthSession?> = tokenStore.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _authState = MutableStateFlow<DiscordAuthUiState>(DiscordAuthUiState.Idle)
    val authState: StateFlow<DiscordAuthUiState> = _authState.asStateFlow()

    private var pendingAuthorization: DiscordAuthorizationSession? = null
    private var redirectJob: Job? = null

    fun setDiscordPresenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDiscordPresenceEnabled(enabled)
        }
    }

    fun setDiscordOnlineArt(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDiscordOnlineArt(enabled)
        }
    }

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

    fun startAuthorization(context: Context) {
        if (redirectJob?.isActive == true) return

        val session = oAuth.createAuthorizationSession()
        pendingAuthorization = session
        _authState.value = DiscordAuthUiState.Waiting

        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(session.authorizationUrl)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        }.isSuccess

        if (!launched) {
            pendingAuthorization = null
            _authState.value = DiscordAuthUiState.Failure(null)
            return
        }

        redirectJob = viewModelScope.launch {
            DiscordAuthCoordinator.redirects.collect { redirect ->
                val pending = pendingAuthorization ?: return@collect
                if (!DiscordApi.isRedirect(redirect)) return@collect
                if (DiscordApi.queryParameter(redirect, "state") != pending.state) return@collect

                oAuth.completeAuthorization(pending, redirect)
                    .onSuccess { authSession ->
                        viewModelScope.launch { tokenStore.save(authSession) }
                        _authState.value = DiscordAuthUiState.Success
                    }
                    .onFailure { error ->
                        _authState.value = DiscordAuthUiState.Failure(error.message)
                    }
                finishAuthorization()
            }
        }
    }

    fun cancelAuthorization() {
        finishAuthorization()
    }

    fun disconnect() {
        finishAuthorization()
        viewModelScope.launch {
            tokenStore.clear()
        }
    }

    fun resetAuthState() {
        if (_authState.value != DiscordAuthUiState.Waiting) {
            _authState.value = DiscordAuthUiState.Idle
        }
    }

    private fun finishAuthorization() {
        redirectJob?.cancel()
        redirectJob = null
        pendingAuthorization = null
    }
}