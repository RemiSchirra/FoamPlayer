package com.foamplayer.app.discord

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.foamplayer.discord.DiscordAccount
import com.foamplayer.discord.DiscordAuthSession
import com.foamplayer.discord.DiscordCredentialStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class DiscordTokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : DiscordCredentialStore {

    val session: Flow<DiscordAuthSession?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toSession() }

    override suspend fun load(): DiscordAuthSession? {
        return runCatching { dataStore.data.first().toSession() }.getOrNull()
    }

    override suspend fun save(session: DiscordAuthSession) {
        dataStore.edit { preferences ->
            preferences[KEY_ACCESS_TOKEN] = session.accessToken
            session.refreshToken?.takeIf { it.isNotBlank() }?.let {
                preferences[KEY_REFRESH_TOKEN] = it
            }
            preferences[KEY_EXPIRES_AT] = session.expiresAtMillis
            session.account?.let { account ->
                preferences[KEY_USER_ID] = account.id
                preferences[KEY_USERNAME] = account.username
                preferences[KEY_DISPLAY_NAME] = account.displayName
                preferences[KEY_AVATAR_URL] = account.avatarUrl.orEmpty()
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_ACCESS_TOKEN)
            preferences.remove(KEY_REFRESH_TOKEN)
            preferences.remove(KEY_EXPIRES_AT)
            preferences.remove(KEY_USER_ID)
            preferences.remove(KEY_USERNAME)
            preferences.remove(KEY_DISPLAY_NAME)
            preferences.remove(KEY_AVATAR_URL)
        }
    }

    private fun Preferences.toSession(): DiscordAuthSession? {
        val accessToken = this[KEY_ACCESS_TOKEN]?.takeUnless { it.isBlank() } ?: return null
        val username = this[KEY_USERNAME].orEmpty()
        val account = if (username.isBlank() && this[KEY_USER_ID].isNullOrBlank()) {
            null
        } else {
            DiscordAccount(
                id = this[KEY_USER_ID].orEmpty(),
                username = username,
                displayName = this[KEY_DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: username,
                avatarUrl = this[KEY_AVATAR_URL]?.takeIf { it.isNotBlank() }
            )
        }
        return DiscordAuthSession(
            accessToken = accessToken,
            refreshToken = this[KEY_REFRESH_TOKEN],
            expiresAtMillis = this[KEY_EXPIRES_AT] ?: 0L,
            account = account
        )
    }

    companion object {
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("discord_access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("discord_refresh_token")
        private val KEY_EXPIRES_AT = longPreferencesKey("discord_token_expires_at")
        private val KEY_USER_ID = stringPreferencesKey("discord_user_id")
        private val KEY_USERNAME = stringPreferencesKey("discord_username")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("discord_display_name")
        private val KEY_AVATAR_URL = stringPreferencesKey("discord_avatar_url")
    }
}
