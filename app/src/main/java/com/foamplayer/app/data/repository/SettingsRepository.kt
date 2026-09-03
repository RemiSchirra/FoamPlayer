package com.foamplayer.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.FoamIconStyle
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences.toAppSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            preferences.write(transform(preferences.toAppSettings()).sanitized())
        }
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) = update { it.copy(bitPerfectEnabled = enabled) }
    suspend fun setIconStyle(style: FoamIconStyle) = update { it.copy(iconStyle = style) }
    suspend fun setMiniPlayerRounding(rounding: Int) = update { it.copy(miniPlayerRounding = rounding) }
    suspend fun setFullPlayerRounding(rounding: Int) = update { it.copy(fullPlayerRounding = rounding) }
    suspend fun setAutomaticColorEnabled(enabled: Boolean) = update { it.copy(automaticColorEnabled = enabled) }
    suspend fun setAccentColor(color: Int) = update { it.copy(accentColor = color) }
    suspend fun setImmersiveModeEnabled(enabled: Boolean) = update { it.copy(immersiveModeEnabled = enabled) }
    suspend fun setAnimateSeekBarEnabled(enabled: Boolean) = update { it.copy(animateSeekBarEnabled = enabled) }
    suspend fun setAutoOpenPlayerEnabled(enabled: Boolean) = update { it.copy(autoOpenPlayerEnabled = enabled) }
    suspend fun setDynamicBackgroundEnabled(enabled: Boolean) = update { it.copy(dynamicBackgroundEnabled = enabled) }
    suspend fun setBackgroundBlur(blur: Int) = update { it.copy(backgroundBlur = blur) }
    suspend fun setDynamicBackgroundIntensity(intensity: Int) = update {
        it.copy(dynamicBackgroundIntensity = intensity)
    }
    suspend fun addFolder(path: String) = update { it.copy(libraryFolders = it.libraryFolders + path) }
    suspend fun removeFolder(path: String) = update { it.copy(libraryFolders = it.libraryFolders - path) }
    suspend fun setArtistSeparators(separators: Set<String>) = update { it.copy(artistSeparators = separators) }
    suspend fun setGenreSeparators(separators: Set<String>) = update { it.copy(genreSeparators = separators) }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        bitPerfectEnabled = this[Keys.BIT_PERFECT_ENABLED] ?: AppSettings().bitPerfectEnabled,
        libraryFolders = this[Keys.SELECTED_FOLDERS] ?: emptySet(),
        iconStyle = this[Keys.ICON_STYLE]
            ?.let { runCatching { FoamIconStyle.valueOf(it) }.getOrNull() }
            ?: AppSettings().iconStyle,
        miniPlayerRounding = this[Keys.MINI_PLAYER_ROUNDING] ?: AppSettings().miniPlayerRounding,
        fullPlayerRounding = this[Keys.FULL_PLAYER_ROUNDING] ?: AppSettings().fullPlayerRounding,
        artistSeparators = this[Keys.ARTIST_SEPARATORS] ?: AppSettings.DEFAULT_SEPARATORS,
        genreSeparators = this[Keys.GENRE_SEPARATORS] ?: AppSettings.DEFAULT_SEPARATORS,
        automaticColorEnabled = this[Keys.AUTOMATIC_COLOR] ?: AppSettings().automaticColorEnabled,
        accentColor = this[Keys.ACCENT_COLOR] ?: AppSettings.DEFAULT_ACCENT_COLOR,
        immersiveModeEnabled = this[Keys.IMMERSIVE_MODE] ?: AppSettings().immersiveModeEnabled,
        animateSeekBarEnabled = this[Keys.ANIMATE_SEEK_BAR] ?: AppSettings().animateSeekBarEnabled,
        autoOpenPlayerEnabled = this[Keys.AUTO_OPEN_PLAYER] ?: AppSettings().autoOpenPlayerEnabled,
        dynamicBackgroundEnabled = this[Keys.DYNAMIC_BACKGROUND] ?: AppSettings().dynamicBackgroundEnabled,
        backgroundBlur = this[Keys.BACKGROUND_BLUR] ?: AppSettings().backgroundBlur,
        dynamicBackgroundIntensity = this[Keys.DYNAMIC_BACKGROUND_INTENSITY]
            ?: AppSettings().dynamicBackgroundIntensity
    ).sanitized()

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.BIT_PERFECT_ENABLED] = settings.bitPerfectEnabled
        this[Keys.SELECTED_FOLDERS] = settings.libraryFolders
        this[Keys.ICON_STYLE] = settings.iconStyle.name
        this[Keys.MINI_PLAYER_ROUNDING] = settings.miniPlayerRounding
        this[Keys.FULL_PLAYER_ROUNDING] = settings.fullPlayerRounding
        this[Keys.ARTIST_SEPARATORS] = settings.artistSeparators
        this[Keys.GENRE_SEPARATORS] = settings.genreSeparators
        this[Keys.AUTOMATIC_COLOR] = settings.automaticColorEnabled
        this[Keys.ACCENT_COLOR] = settings.accentColor
        this[Keys.IMMERSIVE_MODE] = settings.immersiveModeEnabled
        this[Keys.ANIMATE_SEEK_BAR] = settings.animateSeekBarEnabled
        this[Keys.AUTO_OPEN_PLAYER] = settings.autoOpenPlayerEnabled
        this[Keys.DYNAMIC_BACKGROUND] = settings.dynamicBackgroundEnabled
        this[Keys.BACKGROUND_BLUR] = settings.backgroundBlur
        this[Keys.DYNAMIC_BACKGROUND_INTENSITY] = settings.dynamicBackgroundIntensity
    }

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
        val DYNAMIC_BACKGROUND = booleanPreferencesKey("dynamic_background")
        val BACKGROUND_BLUR = intPreferencesKey("background_blur")
        val DYNAMIC_BACKGROUND_INTENSITY = intPreferencesKey("dynamic_background_intensity")
    }
}
