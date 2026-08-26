package com.foamplayer.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.foamplayer.app.ui.theme.FoamIconStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "foam_settings")

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context
) {
    private val PREF_BIT_PERFECT_ENABLED = booleanPreferencesKey("bit_perfect_enabled")
    private val PREF_SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
    private val PREF_ICON_STYLE = stringPreferencesKey("icon_style")
    private val PREF_MINI_PLAYER_ROUNDING = intPreferencesKey("mini_player_rounding")
    private val PREF_FULL_PLAYER_ROUNDING = intPreferencesKey("full_player_rounding")
    private val PREF_ARTIST_SEPARATORS = stringSetPreferencesKey("artist_separators")
    private val PREF_GENRE_SEPARATORS = stringSetPreferencesKey("genre_separators")

    val isBitPerfectEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PREF_BIT_PERFECT_ENABLED] ?: true
    }

    val selectedFolders: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[PREF_SELECTED_FOLDERS] ?: emptySet()
    }

    val iconStyle: Flow<FoamIconStyle> = context.dataStore.data.map { prefs ->
        val styleName = prefs[PREF_ICON_STYLE] ?: FoamIconStyle.FLAT.name
        try {
            FoamIconStyle.valueOf(styleName)
        } catch (e: Exception) {
            FoamIconStyle.FLAT
        }
    }

    val miniPlayerRounding: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PREF_MINI_PLAYER_ROUNDING] ?: 8
    }

    val fullPlayerRounding: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PREF_FULL_PLAYER_ROUNDING] ?: 24
    }

    val artistSeparators: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[PREF_ARTIST_SEPARATORS] ?: setOf(";", ",")
    }

    val genreSeparators: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[PREF_GENRE_SEPARATORS] ?: setOf(";", ",")
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PREF_BIT_PERFECT_ENABLED] = enabled
        }
    }

    suspend fun setIconStyle(style: FoamIconStyle) {
        context.dataStore.edit { prefs ->
            prefs[PREF_ICON_STYLE] = style.name
        }
    }

    suspend fun setMiniPlayerRounding(rounding: Int) {
        context.dataStore.edit { prefs ->
            prefs[PREF_MINI_PLAYER_ROUNDING] = rounding
        }
    }

    suspend fun setFullPlayerRounding(rounding: Int) {
        context.dataStore.edit { prefs ->
            prefs[PREF_FULL_PLAYER_ROUNDING] = rounding
        }
    }

    suspend fun addFolder(path: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PREF_SELECTED_FOLDERS] ?: emptySet()
            prefs[PREF_SELECTED_FOLDERS] = current + path
        }
    }

    suspend fun removeFolder(path: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PREF_SELECTED_FOLDERS] ?: emptySet()
            prefs[PREF_SELECTED_FOLDERS] = current - path
        }
    }

    suspend fun setArtistSeparators(separators: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[PREF_ARTIST_SEPARATORS] = separators
        }
    }

    suspend fun setGenreSeparators(separators: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[PREF_GENRE_SEPARATORS] = separators
        }
    }
}
