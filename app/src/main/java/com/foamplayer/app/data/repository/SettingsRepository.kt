package com.foamplayer.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
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
    // Keys
    private object Keys {
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("bit_perfect_enabled")
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
        val ICON_STYLE = stringPreferencesKey("icon_style")
        val MINI_PLAYER_ROUNDING = intPreferencesKey("mini_player_rounding")
        val FULL_PLAYER_ROUNDING = intPreferencesKey("full_player_rounding")
        val ARTIST_SEPARATORS = stringSetPreferencesKey("artist_separators")
        val GENRE_SEPARATORS = stringSetPreferencesKey("genre_separators")
        val AUTOMATIC_COLOR = booleanPreferencesKey("automatic_color")
        val ACCENT_COLOR = intPreferencesKey("accent_color")
        val IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")
        val ANIMATE_SEEK_BAR = booleanPreferencesKey("animate_seek_bar")
        val AUTO_OPEN_PLAYER = booleanPreferencesKey("auto_open_player")
    }

    // Getter & setter
    private fun <T> preference(key: Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data.map { prefs -> prefs[key] ?: default }

    private suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }

    // Preferences relative to their current state
    private suspend fun <T> updatePreference(key: Preferences.Key<T>, default: T, transform: (T) -> T) {
        context.dataStore.edit { prefs ->
            prefs[key] = transform(prefs[key] ?: default)
        }
    }

    // ---- Simple settings --------------------------------------------------
    val isBitPerfectEnabled: Flow<Boolean> = preference(Keys.BIT_PERFECT_ENABLED, true)
    suspend fun setBitPerfectEnabled(enabled: Boolean) = setPreference(Keys.BIT_PERFECT_ENABLED, enabled)

    val miniPlayerRounding: Flow<Int> = preference(Keys.MINI_PLAYER_ROUNDING, 8)
    suspend fun setMiniPlayerRounding(rounding: Int) = setPreference(Keys.MINI_PLAYER_ROUNDING, rounding)

    val fullPlayerRounding: Flow<Int> = preference(Keys.FULL_PLAYER_ROUNDING, 24)
    suspend fun setFullPlayerRounding(rounding: Int) = setPreference(Keys.FULL_PLAYER_ROUNDING, rounding)

    val isAutomaticColorEnabled: Flow<Boolean> = preference(Keys.AUTOMATIC_COLOR, true)
    suspend fun setAutomaticColorEnabled(enabled: Boolean) = setPreference(Keys.AUTOMATIC_COLOR, enabled)

    val accentColor: Flow<Int> = preference(Keys.ACCENT_COLOR, 0xFFFF5252.toInt())
    suspend fun setAccentColor(color: Int) = setPreference(Keys.ACCENT_COLOR, color)

    val isImmersiveModeEnabled: Flow<Boolean> = preference(Keys.IMMERSIVE_MODE, true)
    suspend fun setImmersiveModeEnabled(enabled: Boolean) = setPreference(Keys.IMMERSIVE_MODE, enabled)

    val isAnimateSeekBarEnabled: Flow<Boolean> = preference(Keys.ANIMATE_SEEK_BAR, false)
    suspend fun setAnimateSeekBarEnabled(enabled: Boolean) = setPreference(Keys.ANIMATE_SEEK_BAR, enabled)

    val isAutoOpenPlayerEnabled: Flow<Boolean> = preference(Keys.AUTO_OPEN_PLAYER, false)
    suspend fun setAutoOpenPlayerEnabled(enabled: Boolean) = setPreference(Keys.AUTO_OPEN_PLAYER, enabled)

    val artistSeparators: Flow<Set<String>> = preference(Keys.ARTIST_SEPARATORS, setOf(";", ","))
    suspend fun setArtistSeparators(separators: Set<String>) = setPreference(Keys.ARTIST_SEPARATORS, separators)

    val genreSeparators: Flow<Set<String>> = preference(Keys.GENRE_SEPARATORS, setOf(";", ","))
    suspend fun setGenreSeparators(separators: Set<String>) = setPreference(Keys.GENRE_SEPARATORS, separators)

    // Enum: stored as its name, parsed back with a safe fallback.
    val iconStyle: Flow<FoamIconStyle> = context.dataStore.data.map { prefs ->
        prefs[Keys.ICON_STYLE]?.let { name ->
            runCatching { FoamIconStyle.valueOf(name) }.getOrNull()
        } ?: FoamIconStyle.FLAT
    }

    suspend fun setIconStyle(style: FoamIconStyle) = setPreference(Keys.ICON_STYLE, style.name)

    // Folder set: mutated relative to its current value rather than replaced.
    val selectedFolders: Flow<Set<String>> = preference(Keys.SELECTED_FOLDERS, emptySet())

    suspend fun addFolder(path: String) =
        updatePreference(Keys.SELECTED_FOLDERS, emptySet()) { it + path }

    suspend fun removeFolder(path: String) =
        updatePreference(Keys.SELECTED_FOLDERS, emptySet()) { it - path }
}