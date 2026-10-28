package com.foamplayer.app.data.backup

import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.device.DeviceBitPerfectMode
import com.foamplayer.app.audio.dsp.DspPersistence
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.GraphicEqSettings
import com.foamplayer.app.audio.dsp.ParametricEqSettings
import com.foamplayer.app.audio.dsp.PresetKind
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.DefaultEnabledHomeSections
import com.foamplayer.app.constants.DefaultHomeSectionOrder
import com.foamplayer.app.constants.DefaultLyricsSourceOrder
import com.foamplayer.app.constants.DefaultNavBarScreenItems
import com.foamplayer.app.constants.HomeSection
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.data.settings.NotificationExtraButton
import com.foamplayer.app.data.settings.SearchHistoryEntry
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.data.settings.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization of the backed-up features. One JSON object carries a
 * "version" plus one member per feature; the multiple-files variant simply
 * writes one such object per file, keeping only its own feature key.
 *
 * Decoding is defensive throughout: unknown enum names and malformed entries
 * fall back to the same defaults the settings layer itself uses.
 */
object BackupJson {

    const val FORMAT_VERSION = 1

    // ------------------------------------------------------------ app settings

    fun appSettingsToJson(settings: AppSettings): JSONObject = JSONObject().apply {
        put("bitPerfectEnabled", settings.bitPerfectEnabled)
        put("listenedThresholdPercent", settings.listenedThresholdPercent)
        put("normalFadeSeconds", settings.normalFadeSeconds.toDouble())
        put("quickFadeSeconds", settings.quickFadeSeconds.toDouble())
        put("silenceBetweenTracksSeconds", settings.silenceBetweenTracksSeconds.toDouble())
        put("iconStyle", settings.iconStyle.name)
        put("miniPlayerRounding", settings.miniPlayerRounding)
        put("fullPlayerRounding", settings.fullPlayerRounding)
        put("marqueeMode", settings.marqueeMode.name)
        put("artistSeparators", JSONArray(settings.artistSeparators.toList()))
        put("genreSeparators", JSONArray(settings.genreSeparators.toList()))
        put("automaticColorEnabled", settings.automaticColorEnabled)
        put("accentColor", settings.accentColor)
        put("immersiveModeEnabled", settings.immersiveModeEnabled)
        put("animateSeekBarEnabled", settings.animateSeekBarEnabled)
        put("seekBarStyle", settings.seekBarStyle.name)
        put("autoOpenPlayerEnabled", settings.autoOpenPlayerEnabled)
        put("animatedCoverEnabled", settings.animatedCoverEnabled)
        put("animatedCoverAllowMobileData", settings.animatedCoverAllowMobileData)
        put("backgroundMode", settings.backgroundMode.name)
        put("customBackgroundUri", settings.customBackgroundUri.orEmpty())
        put("gradientStops", gradientStopsToJson(settings.gradientStops))
        put("backgroundBlur", settings.backgroundBlur)
        put("appBackgroundIntensity", settings.appBackgroundIntensity)
        put("playerBackgroundIntensity", settings.playerBackgroundIntensity)
        put("forceDynamicBackgroundInCollections", settings.forceDynamicBackgroundInCollections)
        put("forceDynamicBackgroundInPlayer", settings.forceDynamicBackgroundInPlayer)
        put("lyricsProviderOrder", toNamesArray(settings.lyricsProviderOrder))
        put("enabledLyricsProviders", toNamesArray(settings.enabledLyricsProviders))
        put("lyricsSourceOrder", toNamesArray(settings.lyricsSourceOrder))
        put("enabledLyricsSources", toNamesArray(settings.enabledLyricsSources))
        put("lyricsLineBlur", settings.lyricsLineBlur)
        put("lyricsWordSyncDisabled", settings.lyricsWordSyncDisabled)
        put("searchHistory", searchHistoryToJson(settings.searchHistory))
        put("searchInLyrics", settings.searchInLyrics)
        put("searchInAlbums", settings.searchInAlbums)
        put("searchInArtists", settings.searchInArtists)
        put("searchInTracks", settings.searchInTracks)
        put("language", settings.language)
        put("themeMode", settings.themeMode.name)
        put("navigationBarMode", settings.navigationBarMode.name)
        put("navBarTransparentWhenPlayerOpen", settings.navBarTransparentWhenPlayerOpen)
        put("navBarPrimaryOrder", toNamesArray(settings.navBarPrimaryOrder))
        put("navBarPrimaryEnabled", toNamesArray(settings.navBarPrimaryEnabled))
        put("navBarSecondaryOrder", toNamesArray(settings.navBarSecondaryOrder))
        put("navBarSecondaryEnabled", toNamesArray(settings.navBarSecondaryEnabled))
        put("homeSectionOrder", toNamesArray(settings.homeSectionOrder))
        put("enabledHomeSections", toNamesArray(settings.enabledHomeSections))
        put("mediaDensities", enumMapToJson(settings.mediaDensities))
        put("mediaSortModes", enumMapToJson(settings.mediaSortModes))
        put("mediaSortInverted", toNamesArray(settings.mediaSortInverted))
        put("collectionLayoutDefault", settings.collectionLayoutDefault.name)
        put("collectionLayoutArtist", settings.collectionLayoutArtist.name)
        put("collectionLayoutAlbum", settings.collectionLayoutAlbum.name)
        put("collectionLayoutPlaylist", settings.collectionLayoutPlaylist.name)
        put("discordPresenceEnabled", settings.discordPresenceEnabled)
        put("discordOnlineArt", settings.discordOnlineArt)
        put("lastFmScrobblingEnabled", settings.lastFmScrobblingEnabled)
        put("scrobbleOnlyFirstArtist", settings.scrobbleOnlyFirstArtist)
        put("artistImageResolveEnabled", settings.artistImageResolveEnabled)
        put("displayOnLockScreen", settings.displayOnLockScreen)
        put("fastLockScreenEnabled", settings.fastLockScreenEnabled)
        put("notificationButtonLeft", settings.notificationButtonLeft.name)
        put("notificationButtonRight", settings.notificationButtonRight.name)
        put("dspState", settings.dspStateSerialized)
        put("dspUserPresets", settings.dspUserPresets)
        put("dspAssignments", settings.dspAssignments)
        put("deviceAudioConfigs", deviceAudioConfigsToJson(settings.deviceAudioConfigs))
    }

    fun appSettingsFromJson(json: JSONObject): AppSettings = AppSettings(
        bitPerfectEnabled = json.optBoolean("bitPerfectEnabled", true),
        listenedThresholdPercent = json.optInt(
            "listenedThresholdPercent",
            AppSettings.DEFAULT_LISTENED_THRESHOLD_PERCENT
        ),
        normalFadeSeconds = json.optDouble(
            "normalFadeSeconds",
            AppSettings.DEFAULT_NORMAL_FADE_SECONDS.toDouble()
        ).toFloat(),
        quickFadeSeconds = json.optDouble(
            "quickFadeSeconds",
            AppSettings.DEFAULT_QUICK_FADE_SECONDS.toDouble()
        ).toFloat(),
        silenceBetweenTracksSeconds = json.optDouble(
            "silenceBetweenTracksSeconds",
            AppSettings.DEFAULT_SILENCE_BETWEEN_TRACKS_SECONDS.toDouble()
        ).toFloat(),
        iconStyle = decodeEnum(json.optString("iconStyle"), FoamIconStyle.FLAT),
        miniPlayerRounding = json.optInt("miniPlayerRounding", 40),
        fullPlayerRounding = json.optInt("fullPlayerRounding", 40),
        marqueeMode = decodeEnum(json.optString("marqueeMode"), MarqueeMode.PING_PONG),
        artistSeparators = stringSet(json.optJSONArray("artistSeparators"))
            .ifEmpty { AppSettings.DEFAULT_SEPARATORS },
        genreSeparators = stringSet(json.optJSONArray("genreSeparators"))
            .ifEmpty { AppSettings.DEFAULT_SEPARATORS },
        automaticColorEnabled = json.optBoolean("automaticColorEnabled", true),
        accentColor = json.optInt("accentColor", AppSettings.DEFAULT_ACCENT_COLOR),
        immersiveModeEnabled = json.optBoolean("immersiveModeEnabled", true),
        animateSeekBarEnabled = json.optBoolean("animateSeekBarEnabled", false),
        seekBarStyle = decodeEnum(json.optString("seekBarStyle"), SeekBarStyle.NORMAL),
        autoOpenPlayerEnabled = json.optBoolean("autoOpenPlayerEnabled", false),
        animatedCoverEnabled = json.optBoolean("animatedCoverEnabled", false),
        animatedCoverAllowMobileData = json.optBoolean("animatedCoverAllowMobileData", false),
        backgroundMode = decodeEnum(json.optString("backgroundMode"), BackgroundMode.DYNAMIC),
        customBackgroundUri = json.optString("customBackgroundUri").takeIf { it.isNotEmpty() },
        gradientStops = gradientStopsFromJson(json.optJSONArray("gradientStops"))
            ?: AppSettings.DefaultGradientStops,
        backgroundBlur = json.optInt("backgroundBlur", AppSettings.DEFAULT_BACKGROUND_BLUR),
        appBackgroundIntensity = json.optInt(
            "appBackgroundIntensity",
            AppSettings.DEFAULT_APP_BACKGROUND_INTENSITY
        ),
        playerBackgroundIntensity = json.optInt(
            "playerBackgroundIntensity",
            AppSettings.DEFAULT_PLAYER_BACKGROUND_INTENSITY
        ),
        forceDynamicBackgroundInCollections = json.optBoolean(
            "forceDynamicBackgroundInCollections",
            true
        ),
        forceDynamicBackgroundInPlayer = json.optBoolean(
            "forceDynamicBackgroundInPlayer",
            true
        ),
        lyricsProviderOrder = decodeEnumList(json.optJSONArray("lyricsProviderOrder")),
        enabledLyricsProviders = decodeEnumSet(
            json.optJSONArray("enabledLyricsProviders"),
            PreferredLyricsProvider.entries.toSet()
        ),
        lyricsSourceOrder = decodeEnumList(json.optJSONArray("lyricsSourceOrder")),
        enabledLyricsSources = decodeEnumSet(
            json.optJSONArray("enabledLyricsSources"),
            DefaultLyricsSourceOrder.toSet()
        ),
        lyricsLineBlur = json.optBoolean("lyricsLineBlur", false),
        lyricsWordSyncDisabled = json.optBoolean("lyricsWordSyncDisabled", false),
        searchHistory = searchHistoryFromJsonTopLevel(json.optJSONArray("searchHistory")),
        searchInLyrics = json.optBoolean("searchInLyrics", true),
        searchInAlbums = json.optBoolean("searchInAlbums", true),
        searchInArtists = json.optBoolean("searchInArtists", true),
        searchInTracks = json.optBoolean("searchInTracks", true),
        language = json.optString("language", "auto"),
        themeMode = decodeEnum(json.optString("themeMode"), ThemeMode.FOLLOW_SYSTEM),
        navigationBarMode = decodeEnum(json.optString("navigationBarMode"), NavigationBarMode.ATTACHED),
        navBarTransparentWhenPlayerOpen = json.optBoolean("navBarTransparentWhenPlayerOpen", false),
        navBarPrimaryOrder = decodeEnumList(json.optJSONArray("navBarPrimaryOrder")),
        navBarPrimaryEnabled = decodeEnumSet(
            json.optJSONArray("navBarPrimaryEnabled"),
            DefaultNavBarScreenItems.toSet()
        ),
        navBarSecondaryOrder = decodeEnumList(json.optJSONArray("navBarSecondaryOrder")),
        navBarSecondaryEnabled = decodeEnumSet(
            json.optJSONArray("navBarSecondaryEnabled"),
            setOf(NavBarItem.STATS)
        ),
        homeSectionOrder = decodeEnumList<HomeSection>(json.optJSONArray("homeSectionOrder"))
            .ifEmpty { DefaultHomeSectionOrder },
        enabledHomeSections = decodeEnumSet(
            json.optJSONArray("enabledHomeSections"),
            DefaultEnabledHomeSections
        ),
        mediaDensities = decodeEnumMap<MediaListView, MediaDensity>(json.optJSONObject("mediaDensities")),
        mediaSortModes = decodeEnumMap<MediaListView, CollectionSortMode>(json.optJSONObject("mediaSortModes")),
        mediaSortInverted = decodeEnumSet<MediaListView>(json.optJSONArray("mediaSortInverted")),
        collectionLayoutDefault = decodeEnum(json.optString("collectionLayoutDefault"), CollectionHeroLayout.Compact),
        collectionLayoutArtist = decodeEnum(json.optString("collectionLayoutArtist"), CollectionHeroLayout.Compact),
        collectionLayoutAlbum = decodeEnum(json.optString("collectionLayoutAlbum"), CollectionHeroLayout.Compact),
        collectionLayoutPlaylist = decodeEnum(json.optString("collectionLayoutPlaylist"), CollectionHeroLayout.Compact),
        discordPresenceEnabled = json.optBoolean("discordPresenceEnabled", false),
        discordOnlineArt = json.optBoolean("discordOnlineArt", true),
        lastFmScrobblingEnabled = json.optBoolean("lastFmScrobblingEnabled", false),
        scrobbleOnlyFirstArtist = json.optBoolean("scrobbleOnlyFirstArtist", true),
        artistImageResolveEnabled = json.optBoolean("artistImageResolveEnabled", false),
        displayOnLockScreen = json.optBoolean("displayOnLockScreen", false),
        fastLockScreenEnabled = json.optBoolean("fastLockScreenEnabled", true),
        notificationButtonLeft = decodeEnum(
            json.optString("notificationButtonLeft"),
            NotificationExtraButton.SCORE
        ),
        notificationButtonRight = decodeEnum(
            json.optString("notificationButtonRight"),
            NotificationExtraButton.CLOSE
        ),
        dspStateSerialized = json.optString("dspState", ""),
        dspUserPresets = json.optString("dspUserPresets", ""),
        dspAssignments = json.optString("dspAssignments", ""),
        deviceAudioConfigs = deviceAudioConfigsFromJson(json.optJSONObject("deviceAudioConfigs"))
    )

    private fun gradientStopsToJson(stops: List<GradientStop>): JSONArray = JSONArray().apply {
        stops.forEach { stop ->
            put(
                JSONObject().apply {
                    put("offset", stop.offset.toDouble())
                    put("x", stop.x.toDouble())
                    put("argb", stop.argb)
                }
            )
        }
    }

    private fun gradientStopsFromJson(array: JSONArray?): List<GradientStop>? {
        if (array == null) return null
        val stops = (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val offset = entry.optDouble("offset", Double.NaN).toFloat()
            if (offset.isNaN()) return@mapNotNull null
            // "x" is absent in older backups; default the dot to the middle.
            GradientStop(offset, entry.optInt("argb"), entry.optDouble("x", 0.5).toFloat())
        }
        return stops.ifEmpty { null }
    }

    private fun deviceAudioConfigsToJson(configs: Map<String, DeviceAudioConfig>): JSONObject =
        JSONObject().apply {
            configs.forEach { (id, config) ->
                put(
                    id,
                    JSONObject().apply {
                        put("bitPerfectMode", config.bitPerfectMode.name)
                        put("sampleRateOverrideHz", config.sampleRateOverrideHz)
                    }
                )
            }
        }

    private fun deviceAudioConfigsFromJson(json: JSONObject?): Map<String, DeviceAudioConfig> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, DeviceAudioConfig>()
        json.keys().forEach { id ->
            val entry = json.optJSONObject(id) ?: return@forEach
            result[id] = DeviceAudioConfig(
                bitPerfectMode = decodeEnum(
                    entry.optString("bitPerfectMode"),
                    DeviceBitPerfectMode.AUTO
                ),
                sampleRateOverrideHz = entry.optInt("sampleRateOverrideHz", 0)
            )
        }
        return result
    }

    // ----------------------------------------------------------- search history

    /** Top-level backup section: the app's search history (tracks, albums, artists). */
    fun searchHistoryToJson(history: List<SearchHistoryEntry>): JSONArray = JSONArray().apply {
        history.forEach { entry ->
            when (entry) {
                is SearchHistoryEntry.Track -> put(
                    JSONObject().apply {
                        put("type", "track")
                        put("path", entry.path)
                    }
                )
                is SearchHistoryEntry.Album -> put(
                    JSONObject().apply {
                        put("type", "album")
                        put("album", entry.album)
                        put("artist", entry.artist)
                    }
                )
                is SearchHistoryEntry.Artist -> put(
                    JSONObject().apply {
                        put("type", "artist")
                        put("name", entry.name)
                    }
                )
            }
        }
    }

    fun searchHistoryFromJsonTopLevel(array: JSONArray?): List<SearchHistoryEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            when (entry.optString("type")) {
                "track" -> SearchHistoryEntry.Track(entry.optString("path"))
                "album" -> SearchHistoryEntry.Album(entry.optString("album"), entry.optString("artist"))
                "artist" -> SearchHistoryEntry.Artist(entry.optString("name"))
                else -> null
            }
        }
    }

    // -------------------------------------------------------- equalizer presets

    /**
     * Presets round-trip through [DspPersistence]'s own defensive state string,
     * so the audio layer keeps owning their validation.
     */
    fun presetsToJson(serializedPresets: String): JSONArray =
        JSONArray(DspPersistence.deserializePresets(serializedPresets).map { preset ->
            JSONObject().apply {
                put("name", preset.name)
                put("kind", preset.kind.name)
                put("state", DspPersistence.serialize(presetState(preset)))
            }
        })

    fun presetsFromJson(array: JSONArray?): String {
        if (array == null) return ""
        val presets = (0 until array.length()).mapNotNull { i ->
            val preset = array.optJSONObject(i) ?: return@mapNotNull null
            val name = DspPersistence.sanitizeName(preset.optString("name"))
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val state = DspPersistence.deserialize(preset.optString("state"))
            DspPreset(
                name = name,
                kind = if (state.parametricEq.enabled) PresetKind.PARAMETRIC else PresetKind.GRAPHIC,
                graphicGainsDb = state.graphicEq.gainsDb,
                parametricNodes = state.parametricEq.nodes,
                preampDb = state.preampDb,
                fullState = state,
                isBuiltIn = false
            )
        }
        return DspPersistence.serializePresets(presets)
    }

    private fun presetState(preset: DspPreset): DspState =
        preset.fullState ?: when (preset.kind) {
            PresetKind.GRAPHIC -> DspState(
                enabled = true,
                preampDb = preset.preampDb,
                graphicEq = GraphicEqSettings(enabled = true, gainsDb = preset.graphicGainsDb),
                tone = preset.tone
            )
            PresetKind.PARAMETRIC -> DspState(
                enabled = true,
                preampDb = preset.preampDb,
                parametricEq = ParametricEqSettings(enabled = true, nodes = preset.parametricNodes),
                tone = preset.tone
            )
        }

    // ----------------------------------------------------------------- helpers

    private fun toNamesArray(items: Collection<*>): JSONArray =
        JSONArray(items.map { it.toString() })

    private fun enumMapToJson(map: Map<*, *>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) -> put(key.toString(), value.toString()) }
    }

    private fun stringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotEmpty() }
        }.toSet()
    }

    private inline fun <reified T : Enum<T>> decodeEnum(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private inline fun <reified T : Enum<T>> decodeEnumList(array: JSONArray?): List<T> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val name = array.optString(i)
            enumValues<T>().firstOrNull { it.name == name }
        }
    }

    private inline fun <reified T : Enum<T>> decodeEnumSet(array: JSONArray?): Set<T> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { i ->
            val name = array.optString(i)
            enumValues<T>().firstOrNull { it.name == name }
        }.toSet()
    }

    private inline fun <reified T : Enum<T>> decodeEnumSet(array: JSONArray?, fallback: Set<T>): Set<T> =
        decodeEnumSet<T>(array).ifEmpty { fallback }

    private inline fun <reified K : Enum<K>, reified V : Enum<V>> decodeEnumMap(json: JSONObject?): Map<K, V> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<K, V>()
        json.keys().forEach { key ->
            val k = enumValues<K>().firstOrNull { it.name == key } ?: return@forEach
            val v = enumValues<V>().firstOrNull { it.name == json.optString(key) } ?: return@forEach
            result[k] = v
        }
        return result
    }
}
