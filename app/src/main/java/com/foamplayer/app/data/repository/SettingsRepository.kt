package com.foamplayer.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
}
