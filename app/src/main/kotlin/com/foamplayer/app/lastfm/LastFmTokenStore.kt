package com.foamplayer.app.lastfm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.foamplayer.lastfm.LastFmSession
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Persists the Last.fm session key and account name in the app DataStore. */
@Singleton
class LastFmTokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val session: Flow<LastFmSession?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toSession() }

    suspend fun save(session: LastFmSession) {
        dataStore.edit { preferences ->
            preferences[KEY_SESSION_KEY] = session.sessionKey
            preferences[KEY_USERNAME] = session.username
            preferences[KEY_SUBSCRIBER] = session.subscriber
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_SESSION_KEY)
            preferences.remove(KEY_USERNAME)
            preferences.remove(KEY_SUBSCRIBER)
        }
    }

    private fun Preferences.toSession(): LastFmSession? {
        val sessionKey = this[KEY_SESSION_KEY]?.takeIf { it.isNotBlank() } ?: return null
        return LastFmSession(
            username = this[KEY_USERNAME].orEmpty(),
            sessionKey = sessionKey,
            subscriber = this[KEY_SUBSCRIBER] ?: false
        )
    }

    companion object {
        private val KEY_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        private val KEY_USERNAME = stringPreferencesKey("lastfm_username")
        private val KEY_SUBSCRIBER = booleanPreferencesKey("lastfm_subscriber")
    }
}
