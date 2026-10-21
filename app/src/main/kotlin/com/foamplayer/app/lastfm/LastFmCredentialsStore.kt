package com.foamplayer.app.lastfm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.foamplayer.app.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where the Last.fm API key and shared secret come from. */
enum class LastFmCredentialSource { APP, CUSTOM }

/** The effective Last.fm API credentials used to sign requests. */
data class LastFmCredentials(
    val apiKey: String,
    val apiSecret: String,
    val source: LastFmCredentialSource
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}

/**
 * Persists the user's choice between the credentials bundled with the build
 * and a custom Last.fm API key + shared secret. Custom credentials are kept
 * device-local: they never go through the settings backup.
 */
@Singleton
class LastFmCredentialsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val preferences = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val credentials: Flow<LastFmCredentials> = preferences
        .map { it.toCredentials() }
        .distinctUntilChanged()

    val customApiKey: Flow<String> = preferences
        .map { it[KEY_CUSTOM_API_KEY].orEmpty() }
        .distinctUntilChanged()

    val customApiSecret: Flow<String> = preferences
        .map { it[KEY_CUSTOM_API_SECRET].orEmpty() }
        .distinctUntilChanged()

    suspend fun current(): LastFmCredentials = credentials.first()

    suspend fun setSource(source: LastFmCredentialSource) {
        dataStore.edit { preferences ->
            preferences[KEY_SOURCE] = source.storageValue
        }
    }

    suspend fun setCustomCredentials(apiKey: String, apiSecret: String) {
        dataStore.edit { preferences ->
            preferences[KEY_CUSTOM_API_KEY] = apiKey.trim()
            preferences[KEY_CUSTOM_API_SECRET] = apiSecret.trim()
        }
    }

    private fun Preferences.toCredentials(): LastFmCredentials {
        if (this[KEY_SOURCE] == LastFmCredentialSource.CUSTOM.storageValue) {
            return LastFmCredentials(
                apiKey = this[KEY_CUSTOM_API_KEY].orEmpty().trim(),
                apiSecret = this[KEY_CUSTOM_API_SECRET].orEmpty().trim(),
                source = LastFmCredentialSource.CUSTOM
            )
        }
        return appCredentials()
    }

    private val LastFmCredentialSource.storageValue: String
        get() = when (this) {
            LastFmCredentialSource.APP -> SOURCE_APP
            LastFmCredentialSource.CUSTOM -> SOURCE_CUSTOM
        }

    companion object {
        private const val SOURCE_APP = "app"
        private const val SOURCE_CUSTOM = "custom"

        private val KEY_SOURCE = stringPreferencesKey("lastfm_credential_source")
        private val KEY_CUSTOM_API_KEY = stringPreferencesKey("lastfm_custom_api_key")
        private val KEY_CUSTOM_API_SECRET = stringPreferencesKey("lastfm_custom_api_secret")

        /** Credentials compiled into the APK from local.properties or the build environment. */
        fun appCredentials(): LastFmCredentials = LastFmCredentials(
            apiKey = BuildConfig.LASTFM_API_KEY.trim(),
            apiSecret = BuildConfig.LASTFM_API_SECRET.trim(),
            source = LastFmCredentialSource.APP
        )
    }
}
