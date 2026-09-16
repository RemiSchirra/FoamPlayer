package com.foamplayer.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.foamplayer.app.constants.DefaultLyricsProviderOrder
import com.foamplayer.app.constants.DefaultLyricsSourceOrder
import com.foamplayer.app.constants.DefaultNavBarPrimaryOrder
import com.foamplayer.app.constants.DefaultNavBarScreenItems
import com.foamplayer.app.constants.LyricsLineBlurKey
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.constants.deserializeLyricsProviderOrder
import com.foamplayer.app.constants.deserializeLyricsSourceOrder
import com.foamplayer.app.constants.deserializeNavBarItemOrder
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.data.settings.MarqueeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
    suspend fun setMarqueeMode(mode: MarqueeMode) = update { it.copy(marqueeMode = mode) }
    suspend fun setAutomaticColorEnabled(enabled: Boolean) = update { it.copy(automaticColorEnabled = enabled) }
    suspend fun setAccentColor(color: Int) = update { it.copy(accentColor = color) }
    suspend fun setImmersiveModeEnabled(enabled: Boolean) = update { it.copy(immersiveModeEnabled = enabled) }
    suspend fun setAnimateSeekBarEnabled(enabled: Boolean) = update { it.copy(animateSeekBarEnabled = enabled) }
    suspend fun setAutoOpenPlayerEnabled(enabled: Boolean) = update { it.copy(autoOpenPlayerEnabled = enabled) }
    suspend fun setAnimatedCoverEnabled(enabled: Boolean) = update { it.copy(animatedCoverEnabled = enabled) }
    suspend fun setAnimatedCoverAllowMobileData(enabled: Boolean) = update {
        it.copy(animatedCoverAllowMobileData = enabled)
    }
    suspend fun setDynamicBackgroundEnabled(enabled: Boolean) = update { it.copy(dynamicBackgroundEnabled = enabled) }
    suspend fun setBackgroundBlur(blur: Int) = update { it.copy(backgroundBlur = blur) }
    suspend fun setAppBackgroundIntensity(intensity: Int) = update {
        it.copy(appBackgroundIntensity = intensity)
    }
    suspend fun setPlayerBackgroundIntensity(intensity: Int) = update {
        it.copy(playerBackgroundIntensity = intensity)
    }
    suspend fun addFolder(path: String) = update { it.copy(libraryFolders = it.libraryFolders + path) }
    suspend fun removeFolder(path: String) = update { it.copy(libraryFolders = it.libraryFolders - path) }
    suspend fun setArtistSeparators(separators: Set<String>) = update { it.copy(artistSeparators = separators) }
    suspend fun setGenreSeparators(separators: Set<String>) = update { it.copy(genreSeparators = separators) }

    /**
     * Separator sets that produced the current track cache, or null before the
     * first scan. Tracked separately from the live settings so the scanner can
     * reuse previously extracted metadata only when the transformation rules
     * (artist/genre separators) have not changed since.
     */
    suspend fun getLastScanSeparators(): Pair<Set<String>, Set<String>>? {
        val preferences = dataStore.data.first()
        val artist = preferences[Keys.LAST_ARTIST_SEPARATORS] ?: return null
        val genre = preferences[Keys.LAST_GENRE_SEPARATORS] ?: return null
        return artist to genre
    }

    suspend fun markScanSeparators(artist: Set<String>, genre: Set<String>) {
        dataStore.edit { preferences ->
            preferences[Keys.LAST_ARTIST_SEPARATORS] = artist
            preferences[Keys.LAST_GENRE_SEPARATORS] = genre
        }
    }

    suspend fun setLyricsProviderOrder(order: List<PreferredLyricsProvider>) = update {
        it.copy(lyricsProviderOrder = order)
    }

    suspend fun setEnabledLyricsProviders(enabled: Set<PreferredLyricsProvider>) = update {
        it.copy(enabledLyricsProviders = enabled)
    }

    suspend fun setLyricsSourceOrder(order: List<LyricsSource>) = update {
        it.copy(lyricsSourceOrder = order)
    }

    suspend fun setEnabledLyricsSources(enabled: Set<LyricsSource>) = update {
        it.copy(enabledLyricsSources = enabled)
    }

    suspend fun setLyricsLineBlur(enabled: Boolean) = update {
        it.copy(lyricsLineBlur = enabled)
    }

    suspend fun setSearchHistory(history: List<String>) = update { it.copy(searchHistory = history) }

    suspend fun setSearchFilters(lyrics: Boolean, albums: Boolean, artists: Boolean, tracks: Boolean) = update {
        it.copy(
            searchInLyrics = lyrics,
            searchInAlbums = albums,
            searchInArtists = artists,
            searchInTracks = tracks
        )
    }

    suspend fun setLanguage(language: String) = update { it.copy(language = language) }

    suspend fun setNavigationBarMode(mode: NavigationBarMode) = update { it.copy(navigationBarMode = mode) }
    suspend fun setNavBarTransparentWhenPlayerOpen(enabled: Boolean) = update {
        it.copy(navBarTransparentWhenPlayerOpen = enabled)
    }

    suspend fun setNavBarPrimaryOrder(order: List<NavBarItem>) = update {
        it.copy(navBarPrimaryOrder = order)
    }

    suspend fun setNavBarPrimaryEnabled(enabled: Set<NavBarItem>) = update {
        it.copy(navBarPrimaryEnabled = enabled)
    }

    suspend fun setNavBarSecondaryOrder(order: List<NavBarItem>) = update {
        it.copy(navBarSecondaryOrder = order)
    }

    suspend fun setNavBarSecondaryEnabled(enabled: Set<NavBarItem>) = update {
        it.copy(navBarSecondaryEnabled = enabled)
    }

    suspend fun setMediaDensity(view: MediaListView, density: MediaDensity) = update {
        it.copy(mediaDensities = it.mediaDensities + (view to density))
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        bitPerfectEnabled = this[Keys.BIT_PERFECT_ENABLED] ?: AppSettings().bitPerfectEnabled,
        libraryFolders = this[Keys.SELECTED_FOLDERS] ?: emptySet(),
        iconStyle = this[Keys.ICON_STYLE]
            ?.let { runCatching { FoamIconStyle.valueOf(it) }.getOrNull() }
            ?: AppSettings().iconStyle,
        miniPlayerRounding = this[Keys.MINI_PLAYER_ROUNDING] ?: AppSettings().miniPlayerRounding,
        fullPlayerRounding = this[Keys.FULL_PLAYER_ROUNDING] ?: AppSettings().fullPlayerRounding,
        marqueeMode = this[Keys.MARQUEE_MODE]
            ?.let { runCatching { MarqueeMode.valueOf(it) }.getOrNull() }
            ?: AppSettings().marqueeMode,
        artistSeparators = this[Keys.ARTIST_SEPARATORS] ?: AppSettings.DEFAULT_SEPARATORS,
        genreSeparators = this[Keys.GENRE_SEPARATORS] ?: AppSettings.DEFAULT_SEPARATORS,
        automaticColorEnabled = this[Keys.AUTOMATIC_COLOR] ?: AppSettings().automaticColorEnabled,
        accentColor = this[Keys.ACCENT_COLOR] ?: AppSettings.DEFAULT_ACCENT_COLOR,
        immersiveModeEnabled = this[Keys.IMMERSIVE_MODE] ?: AppSettings().immersiveModeEnabled,
        animateSeekBarEnabled = this[Keys.ANIMATE_SEEK_BAR] ?: AppSettings().animateSeekBarEnabled,
        autoOpenPlayerEnabled = this[Keys.AUTO_OPEN_PLAYER] ?: AppSettings().autoOpenPlayerEnabled,
        animatedCoverEnabled = this[Keys.ANIMATED_COVER_ENABLED] ?: AppSettings().animatedCoverEnabled,
        animatedCoverAllowMobileData = this[Keys.ANIMATED_COVER_ALLOW_MOBILE_DATA]
            ?: AppSettings().animatedCoverAllowMobileData,
        dynamicBackgroundEnabled = this[Keys.DYNAMIC_BACKGROUND] ?: AppSettings().dynamicBackgroundEnabled,
        backgroundBlur = this[Keys.BACKGROUND_BLUR] ?: AppSettings().backgroundBlur,
        appBackgroundIntensity = this[Keys.APP_BACKGROUND_INTENSITY]
            // Migration: the pre-split single intensity becomes the app background's value.
            ?: this[Keys.DYNAMIC_BACKGROUND_INTENSITY]
            ?: AppSettings().appBackgroundIntensity,
        playerBackgroundIntensity = this[Keys.PLAYER_BACKGROUND_INTENSITY]
            ?: AppSettings().playerBackgroundIntensity,
        lyricsProviderOrder = deserializeLyricsProviderOrder(this[Keys.LYRICS_PROVIDER_ORDER]),
        enabledLyricsProviders = this[Keys.ENABLED_LYRICS_PROVIDERS]
            ?.mapNotNull { name -> PreferredLyricsProvider.entries.find { it.name == name } }
            ?.toSet() ?: PreferredLyricsProvider.entries.toSet(),
        lyricsSourceOrder = deserializeLyricsSourceOrder(this[Keys.LYRICS_SOURCE_ORDER]),
        enabledLyricsSources = this[Keys.ENABLED_LYRICS_SOURCES]
            ?.mapNotNull { name -> LyricsSource.entries.find { it.name == name } }
            ?.toSet() ?: DefaultLyricsSourceOrder.toSet(),
        lyricsLineBlur = this[Keys.LYRICS_LINE_BLUR] ?: AppSettings().lyricsLineBlur,
        searchHistory = this[Keys.SEARCH_HISTORY]?.split("|")?.filter { it.isNotEmpty() } ?: emptyList(),
        searchInLyrics = this[Keys.SEARCH_IN_LYRICS] ?: AppSettings().searchInLyrics,
        searchInAlbums = this[Keys.SEARCH_IN_ALBUMS] ?: AppSettings().searchInAlbums,
        searchInArtists = this[Keys.SEARCH_IN_ARTISTS] ?: AppSettings().searchInArtists,
        searchInTracks = this[Keys.SEARCH_IN_TRACKS] ?: AppSettings().searchInTracks,
        language = this[Keys.LANGUAGE] ?: AppSettings().language,
        navigationBarMode = this[Keys.NAVIGATION_BAR_MODE]
            ?.let { runCatching { NavigationBarMode.valueOf(it) }.getOrNull() }
            ?: AppSettings().navigationBarMode,
        navBarTransparentWhenPlayerOpen = this[Keys.NAVBAR_TRANSPARENT_PLAYER_OPEN] ?: AppSettings().navBarTransparentWhenPlayerOpen,
        navBarPrimaryOrder = deserializeNavBarItemOrder(this[Keys.NAVBAR_PRIMARY_ORDER], DefaultNavBarPrimaryOrder),
        navBarPrimaryEnabled = this[Keys.NAVBAR_PRIMARY_ENABLED]
            ?.mapNotNull { name -> NavBarItem.entries.find { it.name == name } }
            ?.toSet() ?: AppSettings().navBarPrimaryEnabled,
        navBarSecondaryOrder = deserializeNavBarItemOrder(this[Keys.NAVBAR_SECONDARY_ORDER], DefaultNavBarScreenItems),
        navBarSecondaryEnabled = this[Keys.NAVBAR_SECONDARY_ENABLED]
            ?.mapNotNull { name -> NavBarItem.entries.find { it.name == name } }
            ?.toSet() ?: AppSettings().navBarSecondaryEnabled,
        mediaDensities = deserializeMediaDensities(this[Keys.MEDIA_DENSITIES])
    ).sanitized()

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.BIT_PERFECT_ENABLED] = settings.bitPerfectEnabled
        this[Keys.SELECTED_FOLDERS] = settings.libraryFolders
        this[Keys.ICON_STYLE] = settings.iconStyle.name
        this[Keys.MINI_PLAYER_ROUNDING] = settings.miniPlayerRounding
        this[Keys.FULL_PLAYER_ROUNDING] = settings.fullPlayerRounding
        this[Keys.MARQUEE_MODE] = settings.marqueeMode.name
        this[Keys.ARTIST_SEPARATORS] = settings.artistSeparators
        this[Keys.GENRE_SEPARATORS] = settings.genreSeparators
        this[Keys.AUTOMATIC_COLOR] = settings.automaticColorEnabled
        this[Keys.ACCENT_COLOR] = settings.accentColor
        this[Keys.IMMERSIVE_MODE] = settings.immersiveModeEnabled
        this[Keys.ANIMATE_SEEK_BAR] = settings.animateSeekBarEnabled
        this[Keys.AUTO_OPEN_PLAYER] = settings.autoOpenPlayerEnabled
        this[Keys.ANIMATED_COVER_ENABLED] = settings.animatedCoverEnabled
        this[Keys.ANIMATED_COVER_ALLOW_MOBILE_DATA] = settings.animatedCoverAllowMobileData
        this[Keys.DYNAMIC_BACKGROUND] = settings.dynamicBackgroundEnabled
        this[Keys.BACKGROUND_BLUR] = settings.backgroundBlur
        this[Keys.APP_BACKGROUND_INTENSITY] = settings.appBackgroundIntensity
        this[Keys.PLAYER_BACKGROUND_INTENSITY] = settings.playerBackgroundIntensity
        this[Keys.LYRICS_PROVIDER_ORDER] = settings.lyricsProviderOrder.joinToString(",") { it.name }
        this[Keys.ENABLED_LYRICS_PROVIDERS] = settings.enabledLyricsProviders.map { it.name }.toSet()
        this[Keys.LYRICS_SOURCE_ORDER] = settings.lyricsSourceOrder.joinToString(",") { it.name }
        this[Keys.ENABLED_LYRICS_SOURCES] = settings.enabledLyricsSources.map { it.name }.toSet()
        this[Keys.LYRICS_LINE_BLUR] = settings.lyricsLineBlur
        this[Keys.SEARCH_HISTORY] = settings.searchHistory.joinToString("|")
        this[Keys.SEARCH_IN_LYRICS] = settings.searchInLyrics
        this[Keys.SEARCH_IN_ALBUMS] = settings.searchInAlbums
        this[Keys.SEARCH_IN_ARTISTS] = settings.searchInArtists
        this[Keys.SEARCH_IN_TRACKS] = settings.searchInTracks
        this[Keys.LANGUAGE] = settings.language
        this[Keys.NAVIGATION_BAR_MODE] = settings.navigationBarMode.name
        this[Keys.NAVBAR_TRANSPARENT_PLAYER_OPEN] = settings.navBarTransparentWhenPlayerOpen
        this[Keys.NAVBAR_PRIMARY_ORDER] = settings.navBarPrimaryOrder.joinToString(",") { it.name }
        this[Keys.NAVBAR_PRIMARY_ENABLED] = settings.navBarPrimaryEnabled.map { it.name }.toSet()
        this[Keys.NAVBAR_SECONDARY_ORDER] = settings.navBarSecondaryOrder.joinToString(",") { it.name }
        this[Keys.NAVBAR_SECONDARY_ENABLED] = settings.navBarSecondaryEnabled.map { it.name }.toSet()
        this[Keys.MEDIA_DENSITIES] = serializeMediaDensities(settings.mediaDensities)
    }

    private object Keys {
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("bit_perfect_enabled")
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
        val ICON_STYLE = stringPreferencesKey("icon_style")
        val MINI_PLAYER_ROUNDING = intPreferencesKey("mini_player_rounding")
        val FULL_PLAYER_ROUNDING = intPreferencesKey("full_player_rounding")
        val MARQUEE_MODE = stringPreferencesKey("marquee_mode")
        val ARTIST_SEPARATORS = stringSetPreferencesKey("artist_separators")
        val GENRE_SEPARATORS = stringSetPreferencesKey("genre_separators")
        val LAST_ARTIST_SEPARATORS = stringSetPreferencesKey("last_scan_artist_separators")
        val LAST_GENRE_SEPARATORS = stringSetPreferencesKey("last_scan_genre_separators")
        val AUTOMATIC_COLOR = booleanPreferencesKey("automatic_color")
        val ACCENT_COLOR = intPreferencesKey("accent_color")
        val IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")
        val ANIMATE_SEEK_BAR = booleanPreferencesKey("animate_seek_bar")
        val AUTO_OPEN_PLAYER = booleanPreferencesKey("auto_open_player")
        val ANIMATED_COVER_ENABLED = booleanPreferencesKey("animated_cover_enabled")
        val ANIMATED_COVER_ALLOW_MOBILE_DATA = booleanPreferencesKey("animated_cover_allow_mobile_data")
        val DYNAMIC_BACKGROUND = booleanPreferencesKey("dynamic_background")
        val BACKGROUND_BLUR = intPreferencesKey("background_blur")
        val DYNAMIC_BACKGROUND_INTENSITY = intPreferencesKey("dynamic_background_intensity")
        val APP_BACKGROUND_INTENSITY = intPreferencesKey("app_background_intensity")
        val PLAYER_BACKGROUND_INTENSITY = intPreferencesKey("player_background_intensity")
        val LYRICS_PROVIDER_ORDER = stringPreferencesKey("lyrics_provider_order")
        val ENABLED_LYRICS_PROVIDERS = stringSetPreferencesKey("enabled_lyrics_providers")
        val LYRICS_SOURCE_ORDER = stringPreferencesKey("lyrics_source_order")
        val ENABLED_LYRICS_SOURCES = stringSetPreferencesKey("enabled_lyrics_sources")
        val LYRICS_LINE_BLUR = LyricsLineBlurKey
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val SEARCH_IN_LYRICS = booleanPreferencesKey("search_in_lyrics")
        val SEARCH_IN_ALBUMS = booleanPreferencesKey("search_in_albums")
        val SEARCH_IN_ARTISTS = booleanPreferencesKey("search_in_artists")
        val SEARCH_IN_TRACKS = booleanPreferencesKey("search_in_tracks")
        val LANGUAGE = stringPreferencesKey("language")
        val NAVIGATION_BAR_MODE = stringPreferencesKey("navigation_bar_mode")
        val NAVBAR_TRANSPARENT_PLAYER_OPEN = booleanPreferencesKey("navbar_transparent_player_open")
        val NAVBAR_PRIMARY_ORDER = stringPreferencesKey("navbar_primary_order")
        val NAVBAR_PRIMARY_ENABLED = stringSetPreferencesKey("navbar_primary_enabled")
        val NAVBAR_SECONDARY_ORDER = stringPreferencesKey("navbar_secondary_order")
        val NAVBAR_SECONDARY_ENABLED = stringSetPreferencesKey("navbar_secondary_enabled")
        val MEDIA_DENSITIES = stringPreferencesKey("media_densities")
    }
}

/** "VIEW=DENSITY;VIEW=DENSITY" — unknown views/densities are dropped, so new entries default cleanly. */
private fun deserializeMediaDensities(stored: String?): Map<MediaListView, MediaDensity> =
    stored?.split(";")?.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val view = MediaListView.entries.find { it.name == parts[0] } ?: return@mapNotNull null
        val density = MediaDensity.fromName(parts[1]) ?: return@mapNotNull null
        view to density
    }?.toMap() ?: emptyMap()

private fun serializeMediaDensities(densities: Map<MediaListView, MediaDensity>): String =
    densities.entries.joinToString(";") { "${it.key.name}=${it.value.name}" }
