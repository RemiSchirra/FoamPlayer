package com.foamplayer.app.data.settings

import androidx.compose.runtime.Immutable
import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.dsp.DspPersistence
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.DefaultLyricsProviderOrder
import com.foamplayer.app.constants.DefaultLyricsSourceOrder
import com.foamplayer.app.constants.AllNavBarScreenItems
import com.foamplayer.app.constants.DefaultNavBarPrimaryOrder
import com.foamplayer.app.constants.DefaultNavBarScreenItems
import com.foamplayer.app.constants.DefaultNavBarSecondaryOrder
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.constants.defaultSortMode

/** The complete, validated set of user-configurable application preferences. */
enum class FoamIconStyle(val label: String) {
    SOLID("Solid"),
    OUTLINE("Outline"),
    FLAT("Flat")
}

/** Scrolling effect applied to overflowing titles in the full player. */
enum class MarqueeMode(val label: String) {
    PING_PONG("Ping Pong"),
    INFINITE("Infinite")
}

/** Which color palette the UI renders with; FOLLOW_SYSTEM resolves per system dark mode. */
enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("Follow system"),
    DARK("Dark"),
    LIGHT("Light")
}

/** Which visual treatment drives the app-wide and player backgrounds. */
enum class BackgroundMode(val label: String) {
    DYNAMIC("Dynamic"),
    CUSTOM("Custom"),
    GRADIENT("Gradient")
}

/**
 * One draggable color anchor of the gradient background. [offset] is the
 * normalized vertical position on the gradient (0..1); [x] is the dot's
 * normalized horizontal placement in the editor preview (0..1) so dots can be
 * spread out visually — it does not affect the rendered gradient.
 */
@Immutable
data class GradientStop(
    val offset: Float,
    val argb: Int,
    val x: Float = 0.5f
)

// Immutable by construction, but its Set/List/Map fields make it unstable to the
// compiler's inference. This annotation lets every composable receiving AppSettings
// (or screens fed from it) skip when an unrelated preference changes.
@Immutable
data class AppSettings(
    val bitPerfectEnabled: Boolean = true,
    /** Fraction of a track's duration (in %) it must play to count as listened. */
    val listenedThresholdPercent: Int = DEFAULT_LISTENED_THRESHOLD_PERCENT,
    /** Crossfade duration at natural track transitions, in seconds. 0 disables. */
    val normalFadeSeconds: Float = DEFAULT_NORMAL_FADE_SECONDS,
    /** Duration of quick fades on play/pause/seek/skip, in seconds. 0 disables. */
    val quickFadeSeconds: Float = DEFAULT_QUICK_FADE_SECONDS,
    val libraryFolders: Set<String> = emptySet(),
    val iconStyle: FoamIconStyle = FoamIconStyle.FLAT,
    val miniPlayerRounding: Int = 40,
    val fullPlayerRounding: Int = 40,
    val marqueeMode: MarqueeMode = MarqueeMode.PING_PONG,
    val artistSeparators: Set<String> = DEFAULT_SEPARATORS,
    val genreSeparators: Set<String> = DEFAULT_SEPARATORS,
    val automaticColorEnabled: Boolean = true,
    val accentColor: Int = DEFAULT_ACCENT_COLOR,
    val immersiveModeEnabled: Boolean = true,
    val animateSeekBarEnabled: Boolean = false,
    val autoOpenPlayerEnabled: Boolean = false,
    /** Animated (canvas) album cover in the immersive full player; the sub-toggle allows mobile data. */
    val animatedCoverEnabled: Boolean = false,
    val animatedCoverAllowMobileData: Boolean = false,
    /** Which of the dynamic art / custom image / gradient treatments the backdrops use. */
    val backgroundMode: BackgroundMode = BackgroundMode.DYNAMIC,
    /** Content URI of the custom background image ([BackgroundMode.CUSTOM]); null until picked. */
    val customBackgroundUri: String? = null,
    /** Color anchors of the gradient background ([BackgroundMode.GRADIENT]); 1..4 stops. */
    val gradientStops: List<GradientStop> = DefaultGradientStops,
    val backgroundBlur: Int = DEFAULT_BACKGROUND_BLUR,
    val appBackgroundIntensity: Int = DEFAULT_APP_BACKGROUND_INTENSITY,
    val playerBackgroundIntensity: Int = DEFAULT_PLAYER_BACKGROUND_INTENSITY,
    val lyricsProviderOrder: List<PreferredLyricsProvider> = DefaultLyricsProviderOrder,
    val enabledLyricsProviders: Set<PreferredLyricsProvider> = PreferredLyricsProvider.entries.toSet(),
    val lyricsSourceOrder: List<LyricsSource> = DefaultLyricsSourceOrder,
    val enabledLyricsSources: Set<LyricsSource> = LyricsSource.entries.toSet(),
    /** Blur applied to non-active lines in the synced-lyrics view; off by default (GPU-costly). */
    val lyricsLineBlur: Boolean = false,
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
    val searchInLyrics: Boolean = true,
    val searchInAlbums: Boolean = true,
    val searchInArtists: Boolean = true,
    val searchInTracks: Boolean = true,
    val language: String = "auto",
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val navigationBarMode: NavigationBarMode = NavigationBarMode.ATTACHED,
    val navBarTransparentWhenPlayerOpen: Boolean = false,
    val navBarPrimaryOrder: List<NavBarItem> = DefaultNavBarPrimaryOrder,
    val navBarPrimaryEnabled: Set<NavBarItem> = DefaultNavBarScreenItems.toSet(),
    val navBarSecondaryOrder: List<NavBarItem> = DefaultNavBarSecondaryOrder,
    val navBarSecondaryEnabled: Set<NavBarItem> = setOf(NavBarItem.STATS),
    /** Per-view media list density; views missing from the map fall back to [MediaDensity.ROW_NORMAL]. */
    val mediaDensities: Map<MediaListView, MediaDensity> = emptyMap(),
    /** Per-view media list sort (organisation modal); views missing from the map use [defaultSortMode]. */
    val mediaSortModes: Map<MediaListView, CollectionSortMode> = emptyMap(),
    /** Views whose sort the user has inverted (the organisation modal's "Inverse" checkbox). */
    val mediaSortInverted: Set<MediaListView> = emptySet(),
    /** Hero layout of collection screens; every kind starts as the classic compact header. */
    val collectionLayoutDefault: CollectionHeroLayout = CollectionHeroLayout.Compact,
    val collectionLayoutArtist: CollectionHeroLayout = CollectionHeroLayout.Compact,
    val collectionLayoutAlbum: CollectionHeroLayout = CollectionHeroLayout.Compact,
    val collectionLayoutPlaylist: CollectionHeroLayout = CollectionHeroLayout.Compact,
    /** False until the first-run onboarding flow (welcome / permissions / library) is finished. */
    val onboardingCompleted: Boolean = false,
    /** Publishes the currently playing track as Discord Rich Presence via the user's Discord account. */
    val discordPresenceEnabled: Boolean = false,
    /** Looks tracks up online (Spotify, then YouTube) and uses the album cover as the Discord image. */
    val discordOnlineArt: Boolean = true,
    /** Resolves a picture for the library's artists from Spotify (Settings > Library scan). */
    val artistImageResolveEnabled: Boolean = false,
    /** Complete DSP chain state (EQ, tone, balance, volume), serialized by [DspPersistence]. */
    val dspStateSerialized: String = "",
    /** User-saved EQ presets, serialized by [DspPersistence]. */
    val dspUserPresets: String = "",
    /** Per-output-device audio configuration, keyed by device id. */
    val deviceAudioConfigs: Map<String, DeviceAudioConfig> = emptyMap()
) {
    /**
     * Items of the main bar, in the user's configured order. Includes the [NavBarItem.MORE]
     * positional marker (the toggle itself is only rendered while the second bar is non-empty).
     */
    val primaryNavBarItems: List<NavBarItem>
        get() = navBarPrimaryOrder.filter { it == NavBarItem.MORE || it in navBarPrimaryEnabled }

    /** Enabled items of the second bar (the row the "more" button reveals). */
    val secondaryNavBarItems: List<NavBarItem> get() = navBarSecondaryOrder.filter { it in navBarSecondaryEnabled }

    /** The [MediaDensity] remembered by [view]; every view starts as the classic row. */
    fun mediaDensity(view: MediaListView): MediaDensity = mediaDensities[view] ?: MediaDensity.ROW_NORMAL

    /** The [DeviceAudioConfig] remembered by [deviceId]; unknown devices start at defaults. */
    fun deviceAudioConfig(deviceId: String?): DeviceAudioConfig =
        if (deviceId == null) DeviceAudioConfig() else deviceAudioConfigs[deviceId] ?: DeviceAudioConfig()

    /** The [CollectionSortMode] remembered by [view]; every view starts at its [defaultSortMode]. */
    fun mediaSortMode(view: MediaListView): CollectionSortMode =
        mediaSortModes[view] ?: view.defaultSortMode()

    /** Whether [view]'s sort order is inverted (organisation modal's "Inverse" checkbox). */
    fun mediaSortIsInverted(view: MediaListView): Boolean = view in mediaSortInverted

    /**
     * The hero layout configured for [target]: album detail pages use the Album
     * value, playlist detail pages the Playlist value, the artist page the
     * Artist value, and every other collection (genres, years, folders, all
     * tracks, the "list of lists" screens) the Default value. Playlists are
     * identified by their detail route, so no CollectionKind member is needed.
     */
    fun collectionHeroLayout(target: CollectionHeroTarget): CollectionHeroLayout = when (target) {
        CollectionHeroTarget.Album -> collectionLayoutAlbum
        CollectionHeroTarget.Artist -> collectionLayoutArtist
        CollectionHeroTarget.Playlist -> collectionLayoutPlaylist
        CollectionHeroTarget.Default -> collectionLayoutDefault
    }

    fun sanitized(): AppSettings {
        // The "more" toggle is never a switchable item; its visibility is derived
        // from the second bar's content.
        val primaryEnabled = navBarPrimaryEnabled.filter { it in AllNavBarScreenItems }.toSet()
        val secondaryEnabled = navBarSecondaryEnabled.filter { it in AllNavBarScreenItems }.toSet()

        // The settings entry must exist in at least one of the two bars.
        val finalPrimaryEnabled =
            if (NavBarItem.SETTINGS in primaryEnabled || NavBarItem.SETTINGS in secondaryEnabled) {
                primaryEnabled
            } else {
                primaryEnabled + NavBarItem.SETTINGS
            }

        return copy(
            libraryFolders = libraryFolders.map(String::trim).filter(String::isNotEmpty).toSet(),
            listenedThresholdPercent = listenedThresholdPercent.coerceIn(
                MIN_LISTENED_THRESHOLD_PERCENT,
                MAX_LISTENED_THRESHOLD_PERCENT
            ),
            normalFadeSeconds = normalFadeSeconds.coerceIn(MIN_NORMAL_FADE_SECONDS, MAX_NORMAL_FADE_SECONDS),
            quickFadeSeconds = quickFadeSeconds.coerceIn(MIN_QUICK_FADE_SECONDS, MAX_QUICK_FADE_SECONDS),
            miniPlayerRounding = miniPlayerRounding.coerceIn(MIN_ROUNDING, MAX_ROUNDING),
            fullPlayerRounding = fullPlayerRounding.coerceIn(MIN_ROUNDING, MAX_ROUNDING),
            backgroundBlur = backgroundBlur.coerceIn(MIN_BACKGROUND_BLUR, MAX_BACKGROUND_BLUR),
            appBackgroundIntensity = appBackgroundIntensity.coerceIn(
                MIN_DYNAMIC_BACKGROUND_INTENSITY,
                MAX_DYNAMIC_BACKGROUND_INTENSITY
            ),
            playerBackgroundIntensity = playerBackgroundIntensity.coerceIn(
                MIN_DYNAMIC_BACKGROUND_INTENSITY,
                MAX_DYNAMIC_BACKGROUND_INTENSITY
            ),
            gradientStops = gradientStops.sanitizedGradientStops(),
            artistSeparators = artistSeparators.normalizedSeparators(),
            genreSeparators = genreSeparators.normalizedSeparators(),
            navBarPrimaryOrder = normalizedNavBarOrder(navBarPrimaryOrder, DefaultNavBarPrimaryOrder),
            navBarPrimaryEnabled = finalPrimaryEnabled,
            navBarSecondaryOrder = normalizedNavBarOrder(navBarSecondaryOrder, DefaultNavBarSecondaryOrder),
            navBarSecondaryEnabled = secondaryEnabled
        )
    }

    companion object {
        // 0xFFFF5252 (the app's existing default red) as a signed ARGB Int.
        const val DEFAULT_ACCENT_COLOR = -44_462
        const val MIN_LISTENED_THRESHOLD_PERCENT = 0
        const val MAX_LISTENED_THRESHOLD_PERCENT = 100
        const val DEFAULT_LISTENED_THRESHOLD_PERCENT = 50
        const val MIN_ROUNDING = 0
        const val MAX_ROUNDING = 100
        const val MIN_BACKGROUND_BLUR = 0
        const val MAX_BACKGROUND_BLUR = 100
        const val DEFAULT_BACKGROUND_BLUR = 20
        const val MIN_DYNAMIC_BACKGROUND_INTENSITY = 0
        const val MAX_DYNAMIC_BACKGROUND_INTENSITY = 100
        const val DEFAULT_APP_BACKGROUND_INTENSITY = 50
        const val DEFAULT_PLAYER_BACKGROUND_INTENSITY = 80
        const val MIN_GRADIENT_STOPS = 1
        const val MAX_GRADIENT_STOPS = 4
        val DefaultGradientStops = listOf(
            GradientStop(0f, 0xFF141E30.toInt(), 0.3f),
            GradientStop(1f, 0xFF243B55.toInt(), 0.7f)
        )
        const val MIN_NORMAL_FADE_SECONDS = 0f
        const val MAX_NORMAL_FADE_SECONDS = 15f
        const val DEFAULT_NORMAL_FADE_SECONDS = 5f
        const val MIN_QUICK_FADE_SECONDS = 0f
        const val MAX_QUICK_FADE_SECONDS = 1f
        const val DEFAULT_QUICK_FADE_SECONDS = 0.2f
        val DEFAULT_SEPARATORS = setOf(";", ",")
    }
}

private fun Set<String>.normalizedSeparators(): Set<String> =
    map(String::trim).filter(String::isNotEmpty).toSet().ifEmpty { AppSettings.DEFAULT_SEPARATORS }

/** Clamps every position, drops malformed stops and caps the list at [AppSettings.MAX_GRADIENT_STOPS]. */
private fun List<GradientStop>.sanitizedGradientStops(): List<GradientStop> {
    val cleaned = filter { it.offset.isFinite() && it.x.isFinite() }
        .map { GradientStop(it.offset.coerceIn(0f, 1f), it.argb, it.x.coerceIn(0f, 1f)) }
        .distinct()
        .take(AppSettings.MAX_GRADIENT_STOPS)
    return cleaned.ifEmpty { AppSettings.DefaultGradientStops }
}

/** Completes a stored nav-bar order so it contains every [allowed] item exactly once. */
private fun normalizedNavBarOrder(order: List<NavBarItem>, allowed: List<NavBarItem>): List<NavBarItem> {
    val parsed = order.filter { it in allowed }.distinct()
    return parsed + allowed.filterNot { it in parsed }
}
