package com.foamplayer.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.device.DeviceBitPerfectMode
import com.foamplayer.app.audio.dsp.DspPersistence
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionLayoutAlbumKey
import com.foamplayer.app.constants.CollectionLayoutArtistKey
import com.foamplayer.app.constants.CollectionLayoutDefaultKey
import com.foamplayer.app.constants.CollectionLayoutPlaylistKey
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.DefaultEnabledHomeSections
import com.foamplayer.app.constants.DefaultLyricsSourceOrder
import com.foamplayer.app.constants.DefaultNavBarPrimaryOrder
import com.foamplayer.app.constants.DefaultNavBarSecondaryOrder
import com.foamplayer.app.constants.HomeSection
import com.foamplayer.app.constants.LyricsLineBlurKey
import com.foamplayer.app.constants.LyricsWordSyncDisabledKey
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.constants.deserializeHomeSectionOrder
import com.foamplayer.app.constants.deserializeLyricsProviderOrder
import com.foamplayer.app.constants.deserializeLyricsSourceOrder
import com.foamplayer.app.constants.deserializeNavBarItemOrder
import com.foamplayer.app.data.distant.DistantServersCodec
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.distant.DistantServer
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.data.settings.NotificationExtraButton
import com.foamplayer.app.data.settings.SearchHistoryEntry
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.data.settings.ThemeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for app settings, backed by the preferences DataStore.
 * Every write round-trips through [AppSettings.sanitized] so stored state can
 * never drift into an invalid combination.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /** Live settings stream; IO errors fall back to defaults. */
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences.toAppSettings() }

    /** Applies a transformation and persists the sanitized result atomically. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            preferences.write(transform(preferences.toAppSettings()).sanitized())
        }
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) = update { it.copy(bitPerfectEnabled = enabled) }
    suspend fun setListenedThresholdPercent(percent: Int) = update {
        it.copy(listenedThresholdPercent = percent)
    }
    suspend fun setNormalFadeSeconds(seconds: Float) = update {
        it.copy(normalFadeSeconds = seconds)
    }
    suspend fun setQuickFadeSeconds(seconds: Float) = update {
        it.copy(quickFadeSeconds = seconds)
    }
    suspend fun setSilenceBetweenTracksSeconds(seconds: Float) = update {
        it.copy(silenceBetweenTracksSeconds = seconds)
    }
    suspend fun setIconStyle(style: FoamIconStyle) = update { it.copy(iconStyle = style) }
    suspend fun setMiniPlayerRounding(rounding: Int) = update { it.copy(miniPlayerRounding = rounding) }
    suspend fun setFullPlayerRounding(rounding: Int) = update { it.copy(fullPlayerRounding = rounding) }
    suspend fun setMarqueeMode(mode: MarqueeMode) = update { it.copy(marqueeMode = mode) }
    suspend fun setAutomaticColorEnabled(enabled: Boolean) = update { it.copy(automaticColorEnabled = enabled) }
    suspend fun setAccentColor(color: Int) = update { it.copy(accentColor = color) }
    suspend fun setImmersiveModeEnabled(enabled: Boolean) = update { it.copy(immersiveModeEnabled = enabled) }
    suspend fun setAnimateSeekBarEnabled(enabled: Boolean) = update { it.copy(animateSeekBarEnabled = enabled) }
    suspend fun setSeekBarStyle(style: SeekBarStyle) = update { it.copy(seekBarStyle = style) }
    suspend fun setPreferredOutputDeviceId(deviceId: String?) = update {
        it.copy(preferredOutputDeviceId = deviceId)
    }
    suspend fun setAutoOpenPlayerEnabled(enabled: Boolean) = update { it.copy(autoOpenPlayerEnabled = enabled) }
    suspend fun setAnimatedCoverEnabled(enabled: Boolean) = update { it.copy(animatedCoverEnabled = enabled) }
    suspend fun setAnimatedCoverAllowMobileData(enabled: Boolean) = update {
        it.copy(animatedCoverAllowMobileData = enabled)
    }

    /** Legacy boolean entry point kept for existing callers before the mode enum existed. */
    suspend fun setDynamicBackgroundEnabled(enabled: Boolean) = update {
        it.copy(backgroundMode = if (enabled) BackgroundMode.DYNAMIC else BackgroundMode.CUSTOM)
    }
    suspend fun setBackgroundMode(mode: BackgroundMode) = update { it.copy(backgroundMode = mode) }
    suspend fun setCustomBackgroundUri(uri: String?) = update { it.copy(customBackgroundUri = uri) }
    suspend fun setGradientStops(stops: List<GradientStop>) = update { it.copy(gradientStops = stops) }
    suspend fun setBackgroundBlur(blur: Int) = update { it.copy(backgroundBlur = blur) }
    suspend fun setAppBackgroundIntensity(intensity: Int) = update {
        it.copy(appBackgroundIntensity = intensity)
    }
    suspend fun setPlayerBackgroundIntensity(intensity: Int) = update {
        it.copy(playerBackgroundIntensity = intensity)
    }
    suspend fun setForceDynamicBackgroundInCollections(enabled: Boolean) = update {
        it.copy(forceDynamicBackgroundInCollections = enabled)
    }
    suspend fun setForceDynamicBackgroundInPlayer(enabled: Boolean) = update {
        it.copy(forceDynamicBackgroundInPlayer = enabled)
    }
    suspend fun addFolder(path: String) = update { it.copy(libraryFolders = it.libraryFolders + path) }
    suspend fun removeFolder(path: String) = update { it.copy(libraryFolders = it.libraryFolders - path) }

    /** Connects a distant server (replacing any server with the same id). */
    suspend fun upsertDistantServer(server: DistantServer) = update { settings ->
        settings.copy(
            distantServers = settings.distantServers.filterNot { it.id == server.id } + server
        )
    }

    /** Disconnects a distant server and forgets its folders. */
    suspend fun removeDistantServer(serverId: String) = update { settings ->
        settings.copy(distantServers = settings.distantServers.filterNot { it.id == serverId })
    }
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

    suspend fun setLyricsWordSyncDisabled(disabled: Boolean) = update {
        it.copy(lyricsWordSyncDisabled = disabled)
    }

    suspend fun setSearchHistory(history: List<SearchHistoryEntry>) = update { it.copy(searchHistory = history) }

    suspend fun setSearchFilters(lyrics: Boolean, albums: Boolean, artists: Boolean, tracks: Boolean) = update {
        it.copy(
            searchInLyrics = lyrics,
            searchInAlbums = albums,
            searchInArtists = artists,
            searchInTracks = tracks
        )
    }

    suspend fun setLanguage(language: String) = update { it.copy(language = language) }

    suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

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

    /** Sets the order of the Home page's sections (Settings > UI > Home). */
    suspend fun setHomeSectionOrder(order: List<HomeSection>) = update {
        it.copy(homeSectionOrder = order)
    }

    /** Enables or disables one Home page section. */
    suspend fun setHomeSectionEnabled(section: HomeSection, enabled: Boolean) = update {
        val newEnabled = if (enabled) it.enabledHomeSections + section else it.enabledHomeSections - section
        it.copy(enabledHomeSections = newEnabled)
    }

    /** Sets the list density of one media view. */
    suspend fun setMediaDensity(view: MediaListView, density: MediaDensity) = update {
        it.copy(mediaDensities = it.mediaDensities + (view to density))
    }

    /** Sets the sort mode of one media view. */
    suspend fun setMediaSortMode(view: MediaListView, mode: CollectionSortMode) = update {
        it.copy(mediaSortModes = it.mediaSortModes + (view to mode))
    }

    /** Inverts the sort order of one media view. */
    suspend fun setMediaSortInverted(view: MediaListView, inverted: Boolean) = update {
        it.copy(
            mediaSortInverted = if (inverted) it.mediaSortInverted + view else it.mediaSortInverted - view
        )
    }

    suspend fun setCollectionLayoutDefault(layout: CollectionHeroLayout) = update {
        it.copy(collectionLayoutDefault = layout)
    }

    suspend fun setCollectionLayoutArtist(layout: CollectionHeroLayout) = update {
        it.copy(collectionLayoutArtist = layout)
    }

    suspend fun setCollectionLayoutAlbum(layout: CollectionHeroLayout) = update {
        it.copy(collectionLayoutAlbum = layout)
    }

    suspend fun setCollectionLayoutPlaylist(layout: CollectionHeroLayout) = update {
        it.copy(collectionLayoutPlaylist = layout)
    }

    suspend fun setOnboardingCompleted(completed: Boolean) = update {
        it.copy(onboardingCompleted = completed)
    }

    suspend fun setAutoUpdateCheckEnabled(enabled: Boolean) = update {
        it.copy(autoUpdateCheckEnabled = enabled)
    }

    suspend fun setDiscordPresenceEnabled(enabled: Boolean) = update {
        it.copy(discordPresenceEnabled = enabled)
    }

    suspend fun setDiscordOnlineArt(enabled: Boolean) = update {
        it.copy(discordOnlineArt = enabled)
    }

    suspend fun setArtistImageResolveEnabled(enabled: Boolean) = update {
        it.copy(artistImageResolveEnabled = enabled)
    }

    suspend fun setDisplayOnLockScreen(enabled: Boolean) = update {
        it.copy(displayOnLockScreen = enabled)
    }

    suspend fun setFastLockScreenEnabled(enabled: Boolean) = update {
        it.copy(fastLockScreenEnabled = enabled)
    }

    /** Sets the left configurable playback-notification button. */
    suspend fun setNotificationButtonLeft(button: NotificationExtraButton) = update {
        it.copy(notificationButtonLeft = button)
    }

    /** Sets the right configurable playback-notification button. */
    suspend fun setNotificationButtonRight(button: NotificationExtraButton) = update {
        it.copy(notificationButtonRight = button)
    }

    /** Persists the whole DSP chain state (EQ, tone, balance, volume). */
    suspend fun setDspState(state: DspState) = update {
        it.copy(dspStateSerialized = DspPersistence.serialize(state))
    }

    /** Persists the user-saved EQ presets. */
    suspend fun setDspUserPresets(presets: List<DspPreset>) = update {
        it.copy(dspUserPresets = DspPersistence.serializePresets(presets))
    }

    /** Persists one output device's audio configuration. */
    suspend fun setDeviceAudioConfig(deviceId: String, config: DeviceAudioConfig) = update {
        it.copy(deviceAudioConfigs = it.deviceAudioConfigs + (deviceId to config))
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        bitPerfectEnabled = this[Keys.BIT_PERFECT_ENABLED] ?: AppSettings().bitPerfectEnabled,
        listenedThresholdPercent = this[Keys.LISTENED_THRESHOLD_PERCENT]
            ?: AppSettings().listenedThresholdPercent,
        normalFadeSeconds = this[Keys.NORMAL_FADE_SECONDS] ?: AppSettings().normalFadeSeconds,
        quickFadeSeconds = this[Keys.QUICK_FADE_SECONDS] ?: AppSettings().quickFadeSeconds,
        silenceBetweenTracksSeconds = this[Keys.SILENCE_BETWEEN_TRACKS_SECONDS]
            ?: AppSettings().silenceBetweenTracksSeconds,
        libraryFolders = this[Keys.SELECTED_FOLDERS] ?: emptySet(),
        distantServers = DistantServersCodec.decode(this[Keys.DISTANT_SERVERS]),
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
        seekBarStyle = this[Keys.SEEK_BAR_STYLE]
            ?.let { runCatching { SeekBarStyle.valueOf(it) }.getOrNull() }
            ?: AppSettings().seekBarStyle,
        autoOpenPlayerEnabled = this[Keys.AUTO_OPEN_PLAYER] ?: AppSettings().autoOpenPlayerEnabled,
        animatedCoverEnabled = this[Keys.ANIMATED_COVER_ENABLED] ?: AppSettings().animatedCoverEnabled,
        animatedCoverAllowMobileData = this[Keys.ANIMATED_COVER_ALLOW_MOBILE_DATA]
            ?: AppSettings().animatedCoverAllowMobileData,
        backgroundMode = this[Keys.BACKGROUND_MODE]
            ?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() }
            ?: AppSettings().backgroundMode,
        customBackgroundUri = this[Keys.CUSTOM_BACKGROUND_URI]?.takeIf { it.isNotEmpty() },
        gradientStops = deserializeGradientStops(this[Keys.GRADIENT_STOPS])
            ?: AppSettings().gradientStops,
        backgroundBlur = this[Keys.BACKGROUND_BLUR] ?: AppSettings().backgroundBlur,
        appBackgroundIntensity = this[Keys.APP_BACKGROUND_INTENSITY]
            // Migration: the pre-split single intensity becomes the app background's value.
            ?: this[Keys.DYNAMIC_BACKGROUND_INTENSITY]
            ?: AppSettings().appBackgroundIntensity,
        playerBackgroundIntensity = this[Keys.PLAYER_BACKGROUND_INTENSITY]
            ?: AppSettings().playerBackgroundIntensity,
        forceDynamicBackgroundInCollections = this[Keys.FORCE_DYNAMIC_BACKGROUND_IN_COLLECTIONS]
            ?: AppSettings().forceDynamicBackgroundInCollections,
        forceDynamicBackgroundInPlayer = this[Keys.FORCE_DYNAMIC_BACKGROUND_IN_PLAYER]
            ?: AppSettings().forceDynamicBackgroundInPlayer,
        lyricsProviderOrder = deserializeLyricsProviderOrder(this[Keys.LYRICS_PROVIDER_ORDER]),
        enabledLyricsProviders = this[Keys.ENABLED_LYRICS_PROVIDERS]
            ?.mapNotNull { name -> PreferredLyricsProvider.entries.find { it.name == name } }
            ?.toSet() ?: PreferredLyricsProvider.entries.toSet(),
        lyricsSourceOrder = deserializeLyricsSourceOrder(this[Keys.LYRICS_SOURCE_ORDER]),
        enabledLyricsSources = this[Keys.ENABLED_LYRICS_SOURCES]
            ?.mapNotNull { name -> LyricsSource.entries.find { it.name == name } }
            ?.toSet() ?: DefaultLyricsSourceOrder.toSet(),
        lyricsLineBlur = this[Keys.LYRICS_LINE_BLUR] ?: AppSettings().lyricsLineBlur,
        lyricsWordSyncDisabled = this[Keys.LYRICS_WORD_SYNC_DISABLED] ?: AppSettings().lyricsWordSyncDisabled,
        searchHistory = deserializeSearchHistory(this[Keys.SEARCH_HISTORY]),
        searchInLyrics = this[Keys.SEARCH_IN_LYRICS] ?: AppSettings().searchInLyrics,
        searchInAlbums = this[Keys.SEARCH_IN_ALBUMS] ?: AppSettings().searchInAlbums,
        searchInArtists = this[Keys.SEARCH_IN_ARTISTS] ?: AppSettings().searchInArtists,
        searchInTracks = this[Keys.SEARCH_IN_TRACKS] ?: AppSettings().searchInTracks,
        language = this[Keys.LANGUAGE] ?: AppSettings().language,
        themeMode = this[Keys.THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: AppSettings().themeMode,
        navigationBarMode = this[Keys.NAVIGATION_BAR_MODE]
            ?.let { runCatching { NavigationBarMode.valueOf(it) }.getOrNull() }
            ?: AppSettings().navigationBarMode,
        navBarTransparentWhenPlayerOpen = this[Keys.NAVBAR_TRANSPARENT_PLAYER_OPEN] ?: AppSettings().navBarTransparentWhenPlayerOpen,
        navBarPrimaryOrder = deserializeNavBarItemOrder(this[Keys.NAVBAR_PRIMARY_ORDER], DefaultNavBarPrimaryOrder),
        navBarPrimaryEnabled = this[Keys.NAVBAR_PRIMARY_ENABLED]
            ?.mapNotNull { name -> NavBarItem.entries.find { it.name == name } }
            ?.toSet() ?: AppSettings().navBarPrimaryEnabled,
        navBarSecondaryOrder = deserializeNavBarItemOrder(this[Keys.NAVBAR_SECONDARY_ORDER], DefaultNavBarSecondaryOrder),
        navBarSecondaryEnabled = statsAwareSecondaryEnabled(
            storedOrder = this[Keys.NAVBAR_SECONDARY_ORDER],
            storedEnabled = this[Keys.NAVBAR_SECONDARY_ENABLED]
        ),
        homeSectionOrder = deserializeHomeSectionOrder(this[Keys.HOME_SECTION_ORDER]),
        enabledHomeSections = this[Keys.ENABLED_HOME_SECTIONS]
            ?.mapNotNull { name -> HomeSection.entries.find { it.name == name } }
            ?.toSet()
            ?: DefaultEnabledHomeSections,
        mediaDensities = deserializeMediaDensities(this[Keys.MEDIA_DENSITIES]),
        mediaSortModes = deserializeMediaSortModes(this[Keys.MEDIA_SORT_MODES]),
        mediaSortInverted = this[Keys.MEDIA_SORT_INVERTED]
            ?.mapNotNull { name -> MediaListView.entries.find { it.name == name } }
            ?.toSet() ?: emptySet(),
        collectionLayoutDefault = CollectionHeroLayout.fromName(this[Keys.COLLECTION_LAYOUT_DEFAULT])
            ?: AppSettings().collectionLayoutDefault,
        collectionLayoutArtist = CollectionHeroLayout.fromName(this[Keys.COLLECTION_LAYOUT_ARTIST])
            ?: AppSettings().collectionLayoutArtist,
        collectionLayoutAlbum = CollectionHeroLayout.fromName(this[Keys.COLLECTION_LAYOUT_ALBUM])
            ?: AppSettings().collectionLayoutAlbum,
        collectionLayoutPlaylist = CollectionHeroLayout.fromName(this[Keys.COLLECTION_LAYOUT_PLAYLIST])
            ?: AppSettings().collectionLayoutPlaylist,
        onboardingCompleted = this[Keys.ONBOARDING_COMPLETED] ?: AppSettings().onboardingCompleted,
        autoUpdateCheckEnabled = this[Keys.AUTO_UPDATE_CHECK_ENABLED] ?: AppSettings().autoUpdateCheckEnabled,
        discordPresenceEnabled = this[Keys.DISCORD_PRESENCE_ENABLED] ?: AppSettings().discordPresenceEnabled,
        discordOnlineArt = this[Keys.DISCORD_ONLINE_ART] ?: AppSettings().discordOnlineArt,
        artistImageResolveEnabled = this[Keys.ARTIST_IMAGE_RESOLVE_ENABLED] ?: AppSettings().artistImageResolveEnabled,
        displayOnLockScreen = this[Keys.DISPLAY_ON_LOCK_SCREEN] ?: AppSettings().displayOnLockScreen,
        fastLockScreenEnabled = this[Keys.FAST_LOCK_SCREEN_ENABLED] ?: AppSettings().fastLockScreenEnabled,
        notificationButtonLeft = this[Keys.NOTIFICATION_BUTTON_LEFT]
            ?.let { runCatching { NotificationExtraButton.valueOf(it) }.getOrNull() }
            ?: AppSettings().notificationButtonLeft,
        notificationButtonRight = this[Keys.NOTIFICATION_BUTTON_RIGHT]
            ?.let { runCatching { NotificationExtraButton.valueOf(it) }.getOrNull() }
            ?: AppSettings().notificationButtonRight,
        dspStateSerialized = this[Keys.DSP_STATE].orEmpty(),
        dspUserPresets = this[Keys.DSP_USER_PRESETS].orEmpty(),
        deviceAudioConfigs = deserializeDeviceAudioConfigs(this[Keys.DEVICE_AUDIO_CONFIGS]),
        preferredOutputDeviceId = this[Keys.PREFERRED_OUTPUT_DEVICE_ID]?.takeIf { it.isNotBlank() }
    ).sanitized()

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.BIT_PERFECT_ENABLED] = settings.bitPerfectEnabled
        this[Keys.LISTENED_THRESHOLD_PERCENT] = settings.listenedThresholdPercent
        this[Keys.NORMAL_FADE_SECONDS] = settings.normalFadeSeconds
        this[Keys.QUICK_FADE_SECONDS] = settings.quickFadeSeconds
        this[Keys.SILENCE_BETWEEN_TRACKS_SECONDS] = settings.silenceBetweenTracksSeconds
        this[Keys.SELECTED_FOLDERS] = settings.libraryFolders
        this[Keys.DISTANT_SERVERS] = DistantServersCodec.encode(settings.distantServers)
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
        this[Keys.SEEK_BAR_STYLE] = settings.seekBarStyle.name
        this[Keys.AUTO_OPEN_PLAYER] = settings.autoOpenPlayerEnabled
        this[Keys.ANIMATED_COVER_ENABLED] = settings.animatedCoverEnabled
        this[Keys.ANIMATED_COVER_ALLOW_MOBILE_DATA] = settings.animatedCoverAllowMobileData
        this[Keys.BACKGROUND_MODE] = settings.backgroundMode.name
        this[Keys.CUSTOM_BACKGROUND_URI] = settings.customBackgroundUri.orEmpty()
        this[Keys.GRADIENT_STOPS] = serializeGradientStops(settings.gradientStops)
        this[Keys.BACKGROUND_BLUR] = settings.backgroundBlur
        this[Keys.APP_BACKGROUND_INTENSITY] = settings.appBackgroundIntensity
        this[Keys.PLAYER_BACKGROUND_INTENSITY] = settings.playerBackgroundIntensity
        this[Keys.FORCE_DYNAMIC_BACKGROUND_IN_COLLECTIONS] = settings.forceDynamicBackgroundInCollections
        this[Keys.FORCE_DYNAMIC_BACKGROUND_IN_PLAYER] = settings.forceDynamicBackgroundInPlayer
        this[Keys.LYRICS_PROVIDER_ORDER] = settings.lyricsProviderOrder.joinToString(",") { it.name }
        this[Keys.ENABLED_LYRICS_PROVIDERS] = settings.enabledLyricsProviders.map { it.name }.toSet()
        this[Keys.LYRICS_SOURCE_ORDER] = settings.lyricsSourceOrder.joinToString(",") { it.name }
        this[Keys.ENABLED_LYRICS_SOURCES] = settings.enabledLyricsSources.map { it.name }.toSet()
        this[Keys.LYRICS_LINE_BLUR] = settings.lyricsLineBlur
        this[Keys.LYRICS_WORD_SYNC_DISABLED] = settings.lyricsWordSyncDisabled
        this[Keys.SEARCH_HISTORY] = serializeSearchHistory(settings.searchHistory)
        this[Keys.SEARCH_IN_LYRICS] = settings.searchInLyrics
        this[Keys.SEARCH_IN_ALBUMS] = settings.searchInAlbums
        this[Keys.SEARCH_IN_ARTISTS] = settings.searchInArtists
        this[Keys.SEARCH_IN_TRACKS] = settings.searchInTracks
        this[Keys.LANGUAGE] = settings.language
        this[Keys.THEME_MODE] = settings.themeMode.name
        this[Keys.NAVIGATION_BAR_MODE] = settings.navigationBarMode.name
        this[Keys.NAVBAR_TRANSPARENT_PLAYER_OPEN] = settings.navBarTransparentWhenPlayerOpen
        this[Keys.NAVBAR_PRIMARY_ORDER] = settings.navBarPrimaryOrder.joinToString(",") { it.name }
        this[Keys.NAVBAR_PRIMARY_ENABLED] = settings.navBarPrimaryEnabled.map { it.name }.toSet()
        this[Keys.NAVBAR_SECONDARY_ORDER] = settings.navBarSecondaryOrder.joinToString(",") { it.name }
        this[Keys.NAVBAR_SECONDARY_ENABLED] = settings.navBarSecondaryEnabled.map { it.name }.toSet()
        this[Keys.HOME_SECTION_ORDER] = settings.homeSectionOrder.joinToString(",") { it.name }
        this[Keys.ENABLED_HOME_SECTIONS] = settings.enabledHomeSections.map { it.name }.toSet()
        this[Keys.MEDIA_DENSITIES] = serializeMediaDensities(settings.mediaDensities)
        this[Keys.MEDIA_SORT_MODES] = serializeMediaSortModes(settings.mediaSortModes)
        this[Keys.MEDIA_SORT_INVERTED] = settings.mediaSortInverted.map { it.name }.toSet()
        this[Keys.COLLECTION_LAYOUT_DEFAULT] = settings.collectionLayoutDefault.name
        this[Keys.COLLECTION_LAYOUT_ARTIST] = settings.collectionLayoutArtist.name
        this[Keys.COLLECTION_LAYOUT_ALBUM] = settings.collectionLayoutAlbum.name
        this[Keys.COLLECTION_LAYOUT_PLAYLIST] = settings.collectionLayoutPlaylist.name
        this[Keys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
        this[Keys.AUTO_UPDATE_CHECK_ENABLED] = settings.autoUpdateCheckEnabled
        this[Keys.DISCORD_PRESENCE_ENABLED] = settings.discordPresenceEnabled
        this[Keys.DISCORD_ONLINE_ART] = settings.discordOnlineArt
        this[Keys.ARTIST_IMAGE_RESOLVE_ENABLED] = settings.artistImageResolveEnabled
        this[Keys.DISPLAY_ON_LOCK_SCREEN] = settings.displayOnLockScreen
        this[Keys.FAST_LOCK_SCREEN_ENABLED] = settings.fastLockScreenEnabled
        this[Keys.NOTIFICATION_BUTTON_LEFT] = settings.notificationButtonLeft.name
        this[Keys.NOTIFICATION_BUTTON_RIGHT] = settings.notificationButtonRight.name
        this[Keys.DSP_STATE] = settings.dspStateSerialized
        this[Keys.DSP_USER_PRESETS] = settings.dspUserPresets
        this[Keys.PREFERRED_OUTPUT_DEVICE_ID] = settings.preferredOutputDeviceId.orEmpty()
        this[Keys.DEVICE_AUDIO_CONFIGS] = serializeDeviceAudioConfigs(settings.deviceAudioConfigs)
    }

    private object Keys {
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("bit_perfect_enabled")
        val LISTENED_THRESHOLD_PERCENT = intPreferencesKey("listened_threshold_percent")
        val NORMAL_FADE_SECONDS = floatPreferencesKey("normal_fade_seconds")
        val QUICK_FADE_SECONDS = floatPreferencesKey("quick_fade_seconds")
        val SILENCE_BETWEEN_TRACKS_SECONDS = floatPreferencesKey("silence_between_tracks_seconds")
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
        val DISTANT_SERVERS = stringPreferencesKey("distant_servers")
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
        val SEEK_BAR_STYLE = stringPreferencesKey("seek_bar_style")
        val AUTO_OPEN_PLAYER = booleanPreferencesKey("auto_open_player")
        val ANIMATED_COVER_ENABLED = booleanPreferencesKey("animated_cover_enabled")
        val ANIMATED_COVER_ALLOW_MOBILE_DATA = booleanPreferencesKey("animated_cover_allow_mobile_data")
        val BACKGROUND_MODE = stringPreferencesKey("background_mode")
        val CUSTOM_BACKGROUND_URI = stringPreferencesKey("custom_background_uri")
        val GRADIENT_STOPS = stringPreferencesKey("gradient_stops")
        val BACKGROUND_BLUR = intPreferencesKey("background_blur")
        val DYNAMIC_BACKGROUND_INTENSITY = intPreferencesKey("dynamic_background_intensity")
        val APP_BACKGROUND_INTENSITY = intPreferencesKey("app_background_intensity")
        val PLAYER_BACKGROUND_INTENSITY = intPreferencesKey("player_background_intensity")
        val FORCE_DYNAMIC_BACKGROUND_IN_COLLECTIONS =
            booleanPreferencesKey("force_dynamic_background_in_collections")
        val FORCE_DYNAMIC_BACKGROUND_IN_PLAYER = booleanPreferencesKey("force_dynamic_background_in_player")
        val LYRICS_PROVIDER_ORDER = stringPreferencesKey("lyrics_provider_order")
        val ENABLED_LYRICS_PROVIDERS = stringSetPreferencesKey("enabled_lyrics_providers")
        val LYRICS_SOURCE_ORDER = stringPreferencesKey("lyrics_source_order")
        val ENABLED_LYRICS_SOURCES = stringSetPreferencesKey("enabled_lyrics_sources")
        val LYRICS_LINE_BLUR = LyricsLineBlurKey
        val LYRICS_WORD_SYNC_DISABLED = LyricsWordSyncDisabledKey
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val SEARCH_IN_LYRICS = booleanPreferencesKey("search_in_lyrics")
        val SEARCH_IN_ALBUMS = booleanPreferencesKey("search_in_albums")
        val SEARCH_IN_ARTISTS = booleanPreferencesKey("search_in_artists")
        val SEARCH_IN_TRACKS = booleanPreferencesKey("search_in_tracks")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NAVIGATION_BAR_MODE = stringPreferencesKey("navigation_bar_mode")
        val NAVBAR_TRANSPARENT_PLAYER_OPEN = booleanPreferencesKey("navbar_transparent_player_open")
        val NAVBAR_PRIMARY_ORDER = stringPreferencesKey("navbar_primary_order")
        val NAVBAR_PRIMARY_ENABLED = stringSetPreferencesKey("navbar_primary_enabled")
        val NAVBAR_SECONDARY_ORDER = stringPreferencesKey("navbar_secondary_order")
        val NAVBAR_SECONDARY_ENABLED = stringSetPreferencesKey("navbar_secondary_enabled")
        val HOME_SECTION_ORDER = stringPreferencesKey("home_section_order")
        val ENABLED_HOME_SECTIONS = stringSetPreferencesKey("enabled_home_sections")
        val MEDIA_DENSITIES = stringPreferencesKey("media_densities")
        val MEDIA_SORT_MODES = stringPreferencesKey("media_sort_modes")
        val MEDIA_SORT_INVERTED = stringSetPreferencesKey("media_sort_inverted")
        val COLLECTION_LAYOUT_DEFAULT = CollectionLayoutDefaultKey
        val COLLECTION_LAYOUT_ARTIST = CollectionLayoutArtistKey
        val COLLECTION_LAYOUT_ALBUM = CollectionLayoutAlbumKey
        val COLLECTION_LAYOUT_PLAYLIST = CollectionLayoutPlaylistKey
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val AUTO_UPDATE_CHECK_ENABLED = booleanPreferencesKey("auto_update_check_enabled")
        val DISCORD_PRESENCE_ENABLED = booleanPreferencesKey("discord_presence_enabled")
        val DISCORD_ONLINE_ART = booleanPreferencesKey("discord_online_art")
        val ARTIST_IMAGE_RESOLVE_ENABLED = booleanPreferencesKey("artist_image_resolve_enabled")
        val DISPLAY_ON_LOCK_SCREEN = booleanPreferencesKey("display_on_lock_screen")
        val FAST_LOCK_SCREEN_ENABLED = booleanPreferencesKey("fast_lock_screen_enabled")
        val NOTIFICATION_BUTTON_LEFT = stringPreferencesKey("notification_button_left")
        val NOTIFICATION_BUTTON_RIGHT = stringPreferencesKey("notification_button_right")
        val DSP_STATE = stringPreferencesKey("dsp_state")
        val DSP_USER_PRESETS = stringPreferencesKey("dsp_user_presets")
        val DEVICE_AUDIO_CONFIGS = stringPreferencesKey("device_audio_configs")
        val PREFERRED_OUTPUT_DEVICE_ID = stringPreferencesKey("preferred_output_device_id")
    }
}

/**
 * "offset:x:argb" entries joined with ';'. Entries with unparsable numbers are
 * dropped; a legacy 2-part entry ("offset:argb") is accepted and defaults its
 * dot to the horizontal middle. A value that decodes to nothing falls back to
 * the defaults via the caller's `?:`.
 */
internal fun deserializeGradientStops(stored: String?): List<GradientStop>? {
    if (stored.isNullOrEmpty()) return null
    val stops = stored.split(";").mapNotNull { entry ->
        val parts = entry.split(":")
        when (parts.size) {
            3 -> {
                val offset = parts[0].toFloatOrNull() ?: return@mapNotNull null
                val x = parts[1].toFloatOrNull() ?: return@mapNotNull null
                val argb = parts[2].toIntOrNull() ?: return@mapNotNull null
                GradientStop(offset, argb, x)
            }
            2 -> {
                val offset = parts[0].toFloatOrNull() ?: return@mapNotNull null
                val argb = parts[1].toIntOrNull() ?: return@mapNotNull null
                GradientStop(offset, argb, 0.5f)
            }
            else -> null
        }
    }
    return stops.ifEmpty { null }
}

internal fun serializeGradientStops(stops: List<GradientStop>): String =
    stops.joinToString(";") { "${it.offset}:${it.x}:${it.argb}" }

/**
 * "id=mode:rate" entries joined with ';'. Unknown ids/modes are dropped so
 * removed devices and renamed enum members clean themselves out on the next write.
 */
private fun deserializeDeviceAudioConfigs(stored: String?): Map<String, DeviceAudioConfig> =
    stored?.split(";")?.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val id = parts[0].trim().ifEmpty { return@mapNotNull null }
        val configParts = parts[1].split(":", limit = 2)
        if (configParts.size != 2) return@mapNotNull null
        val mode = DeviceBitPerfectMode.entries.firstOrNull { it.name == configParts[0] }
            ?: return@mapNotNull null
        val rate = configParts[1].toIntOrNull() ?: 0
        id to DeviceAudioConfig(
            bitPerfectMode = mode,
            sampleRateOverrideHz = rate
        )
    }?.toMap() ?: emptyMap()

private fun serializeDeviceAudioConfigs(configs: Map<String, DeviceAudioConfig>): String =
    configs.entries.joinToString(";") { (id, config) ->
        "${id.trim()}=${config.bitPerfectMode.name}:${config.sampleRateOverrideHz}"
    }

/** "VIEW=DENSITY" entries joined with ';'; unknown views/densities are dropped. */
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

/** "VIEW=MODE" entries joined with ';'; unknown views/modes are dropped. */
private fun deserializeMediaSortModes(stored: String?): Map<MediaListView, CollectionSortMode> =
    stored?.split(";")?.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val view = MediaListView.entries.find { it.name == parts[0] } ?: return@mapNotNull null
        val mode = CollectionSortMode.entries.find { it.name == parts[1] } ?: return@mapNotNull null
        view to mode
    }?.toMap() ?: emptyMap()

private fun serializeMediaSortModes(modes: Map<MediaListView, CollectionSortMode>): String =
    modes.entries.joinToString(";") { "${it.key.name}=${it.value.name}" }

/** "T:<path>|A:<album><US><artist>|R:<artist>"; unprefixed entries are legacy track paths. */
private const val SEARCH_HISTORY_FIELD_SEPARATOR = '\u001F'

private fun deserializeSearchHistory(stored: String?): List<SearchHistoryEntry> =
    stored?.split("|")?.mapNotNull(::parseSearchHistoryEntry).orEmpty()

private fun parseSearchHistoryEntry(raw: String): SearchHistoryEntry? {
    if (raw.length < 2 || raw[1] != ':') {
        return if (raw.isEmpty()) null else SearchHistoryEntry.Track(raw)
    }
    val payload = raw.substring(2)
    return when (raw[0]) {
        'T' -> SearchHistoryEntry.Track(payload)
        'A' -> payload.split(SEARCH_HISTORY_FIELD_SEPARATOR, limit = 2)
            .takeIf { it.size == 2 }
            ?.let { (album, artist) -> SearchHistoryEntry.Album(album, artist) }
        'R' -> SearchHistoryEntry.Artist(payload)
        else -> null
    }
}

private fun serializeSearchHistory(history: List<SearchHistoryEntry>): String =
    history.joinToString("|") { entry ->
        when (entry) {
            is SearchHistoryEntry.Track -> "T:${entry.path}"
            is SearchHistoryEntry.Album -> "A:${entry.album}$SEARCH_HISTORY_FIELD_SEPARATOR${entry.artist}"
            is SearchHistoryEntry.Artist -> "R:${entry.name}"
        }
    }

/**
 * Preference-level migration for the Stats nav item: files written before the
 * item existed carry neither "STATS" in the second-bar order nor in the enabled
 * set, so its default placement (second bar, enabled) is applied to them. Once
 * "STATS" appears in the stored order, the user's stored enabled state wins.
 */
private fun statsAwareSecondaryEnabled(storedOrder: String?, storedEnabled: Set<String>?): Set<NavBarItem> {
    val enabled = storedEnabled
        ?.mapNotNull { name -> NavBarItem.entries.find { it.name == name } }
        ?.toSet()
        ?: AppSettings().navBarSecondaryEnabled
    return if (storedOrder?.contains("STATS") == true) {
        enabled
    } else {
        enabled + NavBarItem.STATS
    }
}
