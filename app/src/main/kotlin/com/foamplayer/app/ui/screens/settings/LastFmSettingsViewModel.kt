package com.foamplayer.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.lastfm.LastFmClientFactory
import com.foamplayer.app.lastfm.LastFmCredentialSource
import com.foamplayer.app.lastfm.LastFmCredentials
import com.foamplayer.app.lastfm.LastFmCredentialsStore
import com.foamplayer.app.lastfm.LastFmHistoryImporter
import com.foamplayer.app.lastfm.LastFmImportProgress
import com.foamplayer.app.lastfm.LastFmImportResult
import com.foamplayer.app.lastfm.LastFmTokenStore
import com.foamplayer.lastfm.LastFmClient
import com.foamplayer.lastfm.LastFmSession
import com.foamplayer.lastfm.LastFmSessionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Last.fm browser authorization flow states surfaced in the settings screen. */
sealed interface LastFmAuthUiState {
    data object Idle : LastFmAuthUiState
    data object Waiting : LastFmAuthUiState
    data object Success : LastFmAuthUiState
    data class Failure(val message: String?) : LastFmAuthUiState
}

/** State of the "import my Last.fm history" sheet flow. */
sealed interface LastFmImportUiState {
    data object Idle : LastFmImportUiState
    data class Running(
        val progress: LastFmImportProgress = LastFmImportProgress()
    ) : LastFmImportUiState
    data class Finished(val result: LastFmImportResult) : LastFmImportUiState
    data class Failed(val message: String?) : LastFmImportUiState
}

@HiltViewModel
class LastFmSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tokenStore: LastFmTokenStore,
    private val credentialsStore: LastFmCredentialsStore,
    private val clientFactory: LastFmClientFactory,
    private val historyImporter: LastFmHistoryImporter
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val session: StateFlow<LastFmSession?> = tokenStore.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val credentials: StateFlow<LastFmCredentials> = credentialsStore.credentials
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            LastFmCredentialsStore.appCredentials()
        )

    val customApiKey: StateFlow<String> = credentialsStore.customApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val customApiSecret: StateFlow<String> = credentialsStore.customApiSecret
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _authState = MutableStateFlow<LastFmAuthUiState>(LastFmAuthUiState.Idle)
    val authState: StateFlow<LastFmAuthUiState> = _authState.asStateFlow()

    private val _importState = MutableStateFlow<LastFmImportUiState>(LastFmImportUiState.Idle)
    val importState: StateFlow<LastFmImportUiState> = _importState.asStateFlow()

    /** Whether this build was compiled with usable app-provided credentials. */
    val appCredentialsConfigured: Boolean get() = LastFmCredentialsStore.appCredentials().isConfigured

    private var authJob: Job? = null
    private var importJob: Job? = null

    fun setScrobblingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLastFmScrobblingEnabled(enabled)
        }
    }

    /** Switches to the API credentials bundled with the build. */
    fun useAppCredentials() {
        switchCredentials {
            credentialsStore.setSource(LastFmCredentialSource.APP)
        }
    }

    /** Stores a custom API key + secret and switches to it. */
    fun saveCustomCredentials(apiKey: String, apiSecret: String) {
        switchCredentials {
            credentialsStore.setCustomCredentials(apiKey, apiSecret)
            credentialsStore.setSource(LastFmCredentialSource.CUSTOM)
        }
    }

    /**
     * Applies a credential change and drops the stored session when the actual
     * key or secret changed: Last.fm session keys are only valid for the API
     * account that authorized them.
     */
    private fun switchCredentials(apply: suspend () -> Unit) {
        viewModelScope.launch {
            cancelAuthorization()
            val previous = credentialsStore.current()
            apply()
            val updated = credentialsStore.current()
            if (updated.apiKey != previous.apiKey || updated.apiSecret != previous.apiSecret) {
                tokenStore.clear()
            }
            _authState.value = LastFmAuthUiState.Idle
        }
    }

    /**
     * Requests a token, opens Last.fm's authorization page and polls until the
     * user approves it or [AUTHORIZATION_TIMEOUT_MS] elapses.
     */
    fun startAuthorization(context: Context) {
        if (authJob?.isActive == true) return
        _authState.value = LastFmAuthUiState.Waiting

        authJob = viewModelScope.launch {
            val credentials = credentialsStore.current()
            if (!credentials.isConfigured) {
                _authState.value = LastFmAuthUiState.Failure(LastFmClient.NOT_CONFIGURED_MESSAGE)
                return@launch
            }

            val client = clientFactory.create(credentials)
            try {
                val authorization = client.createAuthorizationSession().getOrElse { error ->
                    _authState.value = LastFmAuthUiState.Failure(error.message)
                    return@launch
                }

                val launched = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, authorization.authorizationUrl.toUri()).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                    )
                }.isSuccess

                if (!launched) {
                    _authState.value = LastFmAuthUiState.Failure(null)
                    return@launch
                }

                pollForSession(client, authorization.token)
            } finally {
                client.close()
            }
        }
    }

    private suspend fun pollForSession(client: LastFmClient, token: String) {
        val deadline = System.currentTimeMillis() + AUTHORIZATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            when (val result = client.fetchSession(token)) {
                is LastFmSessionResult.Authorized -> {
                    tokenStore.save(result.session)
                    _authState.value = LastFmAuthUiState.Success
                    return
                }
                is LastFmSessionResult.Pending -> Unit
                is LastFmSessionResult.Failed -> {
                    _authState.value = LastFmAuthUiState.Failure(result.message)
                    return
                }
            }
        }
        _authState.value = LastFmAuthUiState.Failure(null)
    }

    fun cancelAuthorization() {
        authJob?.cancel()
        authJob = null
        if (_authState.value is LastFmAuthUiState.Waiting) {
            _authState.value = LastFmAuthUiState.Idle
        }
    }

    fun disconnect() {
        cancelAuthorization()
        cancelImport()
        viewModelScope.launch {
            tokenStore.clear()
        }
    }

    /**
     * Downloads the connected account's complete scrobble history and applies it
     * to the library. [eraseHistory] clears every track's listen count,
     * last-played date and the local listen history before importing; scores are
     * left untouched either way.
     */
    fun importHistory(eraseHistory: Boolean) {
        if (importJob?.isActive == true) return
        val session = session.value ?: return

        importJob = viewModelScope.launch {
            val credentials = credentialsStore.current()
            if (!credentials.isConfigured) {
                _importState.value =
                    LastFmImportUiState.Failed(LastFmClient.NOT_CONFIGURED_MESSAGE)
                return@launch
            }

            _importState.value = LastFmImportUiState.Running()
            val client = clientFactory.create(credentials)
            try {
                val result = historyImporter.import(
                    client = client,
                    username = session.username,
                    eraseHistory = eraseHistory,
                    onProgress = { progress ->
                        _importState.value = LastFmImportUiState.Running(progress)
                    }
                )
                _importState.value = LastFmImportUiState.Finished(result)
            } catch (e: CancellationException) {
                _importState.value = LastFmImportUiState.Idle
                throw e
            } catch (e: Exception) {
                _importState.value = LastFmImportUiState.Failed(e.message)
            } finally {
                client.close()
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        if (_importState.value is LastFmImportUiState.Running) {
            _importState.value = LastFmImportUiState.Idle
        }
    }

    /** Closes the success/failure sheet; a running import is left alone. */
    fun dismissImportResult() {
        if (_importState.value !is LastFmImportUiState.Running) {
            _importState.value = LastFmImportUiState.Idle
        }
    }

    /** Returns to Idle unless an authorization is still waiting. */
    fun resetAuthState() {
        if (_authState.value !is LastFmAuthUiState.Waiting) {
            _authState.value = LastFmAuthUiState.Idle
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val AUTHORIZATION_TIMEOUT_MS = 3 * 60_000L
    }
}
