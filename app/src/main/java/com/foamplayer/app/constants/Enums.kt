package com.foamplayer.app.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.foamplayer.app.R
import java.time.LocalDateTime
import java.time.ZoneOffset

const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"

enum class AppFontPreference {
    DEFAULT,
    SYSTEM,
    CUSTOM,
}

enum class LibraryViewType {
    LIST,
    GRID,
    ;

    fun toggle() =
        when (this) {
            LIST -> GRID
            GRID -> LIST
        }
}

enum class SongSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}

enum class PlaylistSongSortType {
    CUSTOM,
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}

enum class AutoPlaylistSongSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}

enum class ArtistSortType {
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    PLAY_TIME,
}

enum class ArtistSongSortType {
    CREATE_DATE,
    NAME,
    PLAY_TIME,
}

enum class AlbumSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    YEAR,
    SONG_COUNT,
    LENGTH,
    PLAY_TIME,
}

enum class PlaylistSortType {
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    LAST_UPDATED,
    CUSTOM,
}

enum class MixSortType {
    CREATE_DATE,
    NAME,
    LAST_UPDATED,
}

enum class GridItemSize {
    BIG,
    SMALL,
}

/**
 * Density of the app's media lists, compact to spacious. ROW_* render the shared
 * row scaffold; GRID_* render square artwork cells in a fixed column count.
 */
enum class MediaDensity(val label: String) {
    ROW_SMALL("Row Small"),
    ROW_NORMAL("Row Normal"),
    ROW_BIG("Row Big"),
    GRID_SMALL("Grid Small"),
    GRID_NORMAL("Grid Normal"),
    GRID_BIG("Grid Big"),
    ;

    val isGrid: Boolean
        get() = this == GRID_SMALL || this == GRID_NORMAL || this == GRID_BIG

    /** Number of columns when [isGrid]. */
    val gridColumns: Int
        get() = when (this) {
            GRID_SMALL -> 4
            GRID_NORMAL -> 3
            GRID_BIG -> 2
            else -> 1
        }

    companion object {
        fun fromName(name: String?): MediaDensity? = entries.find { it.name == name }
    }
}

/** A media list view; each persists its own [MediaDensity]. Keys are stable across releases. */
enum class MediaListView {
    PLAYLISTS,
    PLAYLIST_DETAIL,
    ARTIST_DETAIL,
    BROWSE_ALL_TRACKS,
    BROWSE_ALBUMS,
    BROWSE_ARTISTS,
    BROWSE_GENRES,
    BROWSE_YEARS,
    BROWSE_FOLDERS,
    BROWSE_GENRE_TRACKS,
    BROWSE_YEAR_TRACKS,
    BROWSE_ARTISTS_IN,
    BROWSE_ALBUMS_IN,
    HOME_SEE_ALL_PLAYLISTS,
    HOME_SEE_ALL_ALBUMS,
    HOME_SEE_ALL_RECENT_TRACKS,
    HOME_SEE_ALL_FAVORITE_TRACKS,
    HOME_SEE_ALL_ARTISTS,
    STATS_SEE_ALL_TRACKS,
    STATS_SEE_ALL_ARTISTS,
    STATS_SEE_ALL_ALBUMS,
}

/**
 * Per-view content sorting, chosen from the organisation modal and persisted.
 * [CUSTOM] is only offered where a user-defined order exists; [RECOMMENDED]
 * keeps the source query's own ranking and is likewise only offered on the
 * ranked Home pages.
 */
enum class CollectionSortMode(val label: String) {
    CUSTOM("Custom order"),
    NAME("By name"),
    TRACK_NUMBER("By track disc and track number"),
    ARTIST("By artist"),
    ALBUM("By album"),
    YEAR("By year"),
    SCORE("By score"),
    DURATION("By duration"),
    LISTEN_DATE("By listen date"),
    LISTEN_COUNT("By listen count"),
    RANDOM("By random"),
    RECOMMENDED("By recommendation"),
}

/** Sort modes offered for a track list (everything but the playlist-only custom order). */
val TrackSortOptions: List<CollectionSortMode> =
    listOf(
        CollectionSortMode.NAME,
        CollectionSortMode.TRACK_NUMBER,
        CollectionSortMode.ARTIST,
        CollectionSortMode.ALBUM,
        CollectionSortMode.YEAR,
        CollectionSortMode.SCORE,
        CollectionSortMode.DURATION,
        CollectionSortMode.LISTEN_DATE,
        CollectionSortMode.LISTEN_COUNT,
        CollectionSortMode.RANDOM
    )

/**
 * A view's initial sort mode: user-ordered views start in [CollectionSortMode.CUSTOM],
 * the ranked Home see-all pages in [CollectionSortMode.RECOMMENDED] (the source
 * query's own ranking, so a "See all" shows its row's order), everything else
 * alphabetically.
 */
fun MediaListView.defaultSortMode(): CollectionSortMode = when (this) {
    MediaListView.PLAYLISTS,
    MediaListView.PLAYLIST_DETAIL -> CollectionSortMode.CUSTOM
    MediaListView.BROWSE_YEAR_TRACKS -> CollectionSortMode.ALBUM
    MediaListView.HOME_SEE_ALL_RECENT_TRACKS -> CollectionSortMode.LISTEN_DATE
    MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS,
    MediaListView.STATS_SEE_ALL_TRACKS -> CollectionSortMode.LISTEN_COUNT
    MediaListView.HOME_SEE_ALL_PLAYLISTS,
    MediaListView.HOME_SEE_ALL_ALBUMS,
    MediaListView.HOME_SEE_ALL_ARTISTS -> CollectionSortMode.RECOMMENDED
    else -> CollectionSortMode.NAME
}

enum class PreferredLyricsProvider {
    BETTER_LYRICS,
    YOULY_PLUS,
    LRCLIB,
    KUGOU,
    SIMPMUSIC,
    UNISON,
    PAXSENIX_APPLE_MUSIC,
    PAXSENIX_NETEASE,
    PAXSENIX_SPOTIFY,
    PAXSENIX_MUSIXMATCH,
    PAXSENIX_YOUTUBE,
}

val DefaultLyricsProviderOrder =
    listOf(
        PreferredLyricsProvider.BETTER_LYRICS,
        PreferredLyricsProvider.YOULY_PLUS,
        PreferredLyricsProvider.LRCLIB,
        PreferredLyricsProvider.KUGOU,
        PreferredLyricsProvider.SIMPMUSIC,
        PreferredLyricsProvider.UNISON,
        PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC,
        PreferredLyricsProvider.PAXSENIX_NETEASE,
        PreferredLyricsProvider.PAXSENIX_SPOTIFY,
        PreferredLyricsProvider.PAXSENIX_MUSIXMATCH,
        PreferredLyricsProvider.PAXSENIX_YOUTUBE,
    )

/** Parses a comma-separated provider order, appending any missing defaults at the end. */
fun deserializeLyricsProviderOrder(orderStr: String?): List<PreferredLyricsProvider> {
    if (orderStr.isNullOrBlank()) return DefaultLyricsProviderOrder

    val parsed =
        orderStr
            .split(",")
            .mapNotNull { name -> PreferredLyricsProvider.entries.find { it.name == name.trim() } }
            .distinct()

    val missing = DefaultLyricsProviderOrder.filterNot { it in parsed }
    return parsed + missing
}

enum class LyricsSource(val label: String) {
    ONLINE("Online provider"),
    LYRICS_FILE("Lyrics file"),
    EMBEDDED("Embedded lyrics"),
}

val DefaultLyricsSourceOrder =
    listOf(
        LyricsSource.ONLINE,
        LyricsSource.LYRICS_FILE,
        LyricsSource.EMBEDDED,
    )

/** Parses a comma-separated source order, appending any missing defaults at the end. */
fun deserializeLyricsSourceOrder(orderStr: String?): List<LyricsSource> {
    if (orderStr.isNullOrBlank()) return DefaultLyricsSourceOrder

    val parsed =
        orderStr
            .split(",")
            .mapNotNull { name -> LyricsSource.entries.find { it.name == name.trim() } }
            .distinct()

    val missing = DefaultLyricsSourceOrder.filterNot { it in parsed }
    return parsed + missing
}

enum class LyricsAnimationStyle {
    NONE,
    FADE,
    GLOW,
    SLIDE,
    KARAOKE,
    APPLE,
}

enum class LyricsPosition {
    LEFT,
    CENTER,
    RIGHT,
}

val LyricsTextSizeKey = floatPreferencesKey("lyricsTextSize")
val LyricsLineSpacingKey = floatPreferencesKey("lyricsLineSpacing")
val LyricsLineBlurKey = booleanPreferencesKey("lyricsLineBlur")

val TopSize = stringPreferencesKey("topSize")

const val HISTORY_DURATION_DEFAULT = 30
const val HISTORY_DURATION_MIN = 5
const val HISTORY_DURATION_MAX = 60
val HISTORY_DURATION_RANGE = HISTORY_DURATION_MIN.toFloat()..HISTORY_DURATION_MAX.toFloat()
val HISTORY_DURATION_LEGACY_FLOAT_KEY = floatPreferencesKey("historyDuration")
val HistoryDuration = intPreferencesKey("historyDuration")

val PlayerButtonsStyleKey = stringPreferencesKey("player_buttons_style")
val PlayerBackgroundStyleKey = stringPreferencesKey("playerBackgroundStyle")
val ShowLyricsKey = booleanPreferencesKey("showLyrics")
val ShowLyricsPlayerControlsKey = booleanPreferencesKey("showLyricsPlayerControls")
val LyricsTextPositionKey = stringPreferencesKey("lyricsTextPosition")
val LyricsClickKey = booleanPreferencesKey("lyricsClick")
val LyricsScrollKey = booleanPreferencesKey("lyricsScrollKey")
val LyricsRomanizeJapaneseKey = booleanPreferencesKey("lyricsRomanizeJapanese")
val LyricsRomanizeKoreanKey = booleanPreferencesKey("lyricsRomanizeKorean")
val LyricsRomanizeChineseKey = booleanPreferencesKey("lyricsRomanizeChinese")
val LyricsRomanizeHindiKey = booleanPreferencesKey("lyricsRomanizeHindi")
val LyricsRomanizeOtherLanguagesKey = booleanPreferencesKey("lyricsRomanizeOtherLanguages")
val TranslateLyricsKey = booleanPreferencesKey("translateLyrics")

val PreloadQueueLyricsEnabledKey = booleanPreferencesKey("preload_queue_lyrics_enabled")
val QueueLyricsPreloadCountKey = intPreferencesKey("queue_lyrics_preload_count")

val PlayerVolumeKey = floatPreferencesKey("playerVolume")
val RepeatModeKey = intPreferencesKey("repeatMode")

val SearchSourceKey = stringPreferencesKey("searchSource")
val SwipeThumbnailKey = booleanPreferencesKey("swipeThumbnail")
val SwipeSensitivityKey = floatPreferencesKey("swipeSensitivity")

enum class SearchSource {
    LOCAL,
    ONLINE,
    ;

    fun toggle() =
        when (this) {
            LOCAL -> ONLINE
            ONLINE -> LOCAL
        }
}

val VisitorDataKey = stringPreferencesKey("visitorData")
val DataSyncIdKey = stringPreferencesKey("dataSyncId")
val InnerTubeCookieKey = stringPreferencesKey("innerTubeCookie")
val PoTokenKey = stringPreferencesKey("poToken")
val AccountNameKey = stringPreferencesKey("accountName")
val AccountEmailKey = stringPreferencesKey("accountEmail")
val AccountChannelHandleKey = stringPreferencesKey("accountChannelHandle")
val SavedAccountsKey = stringPreferencesKey("savedAccounts")
val UseLoginForBrowse = booleanPreferencesKey("useLoginForBrowse")
val SpotifySpDcKey = stringPreferencesKey("spotify_sp_dc")
val SpotifySpKeyKey = stringPreferencesKey("spotify_sp_key")
val SpotifyAccessTokenKey = stringPreferencesKey("spotify_access_token")
val SpotifyAccessTokenExpiresAtKey = longPreferencesKey("spotify_access_token_expires_at")
val SpotifyAccountNameKey = stringPreferencesKey("spotify_account_name")
val SpotifyAccountAvatarUrlKey = stringPreferencesKey("spotify_account_avatar_url")
val ShowSpotifyPlaylistsKey = booleanPreferencesKey("show_spotify_playlists")
val UseSpotifyHomeKey = booleanPreferencesKey("use_spotify_home")
val SpotifyLibraryPlaylistsCacheKey = stringPreferencesKey("spotify_library_playlists_cache")
val SpotifyLikedSongsCacheKey = stringPreferencesKey("spotify_liked_songs_cache")
val SpotifyProfileRecentItemsKey = stringPreferencesKey("spotify_profile_recent_items_json")
val SpotifyProfileTopTracksKey = stringPreferencesKey("spotify_profile_top_tracks_json")
val SpotifyProfileArtistsKey = stringPreferencesKey("spotify_profile_artists_json")
val SpotifyProfileCacheTsKey = longPreferencesKey("spotify_profile_cache_ts")
val UseSpotifySearchKey = booleanPreferencesKey("use_spotify_search")
val SpotifySyncLikesKey = booleanPreferencesKey("spotify_sync_likes")
val HideYtmLikedSongsKey = booleanPreferencesKey("hide_ytm_liked_songs")
val EnableLosslessKey = booleanPreferencesKey("enable_lossless")
val MemoryCacheToggleKey = booleanPreferencesKey("memory_cache_toggle")
val DownloadLocationUriKey = stringPreferencesKey("download_location_uri")
val QobuzAppIdKey = stringPreferencesKey("qobuz_app_id")
val QobuzAppSecretKey = stringPreferencesKey("qobuz_app_secret")
val QobuzUserAuthTokenKey = stringPreferencesKey("qobuz_user_auth_token")

val WebClientPoTokenEnabledKey = booleanPreferencesKey("webClientPoTokenEnabled")
val PoTokenGvsKey = stringPreferencesKey("poTokenGvs")
val PoTokenPlayerKey = stringPreferencesKey("poTokenPlayer")
val UseVisitorDataKey = booleanPreferencesKey("useVisitorData")
val PoTokenSourceUrlKey = stringPreferencesKey("poTokenSourceUrl")

val LanguageCodeToName =
    mapOf(
        "en" to "English (US)",
        "en-GB" to "English (UK)",
        "en-NG" to "English (Nigeria)",
        "ja" to "日本語",
        "ko" to "한국어",
        "vi" to "Tiếng Việt",
        "zh" to "中文",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "pt" to "Português",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "hi" to "हिन्दी",
        "th" to "ไทย",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Melayu",
        "uk" to "Українська",
        "cs" to "Čeština",
        "el" to "Ελληνικά",
        "he" to "עברית",
        "hu" to "Magyar",
        "ro" to "Română",
        "fi" to "Suomi",
        "da" to "Dansk",
        "no" to "Norsk",
        "sv" to "Svenska",
        "sk" to "Slovenčina",
        "bg" to "Български",
        "hr" to "Hrvatski",
        "sr" to "Срpsки",
        "lt" to "Lietuvių",
        "lv" to "Latviešu",
        "et" to "Eesti",
    )

val CountryCodeToName =
    mapOf(
        "JP" to "Japan",
        "KR" to "South Korea",
        "US" to "United States",
        "GB" to "United Kingdom",
        "CN" to "China",
        "TW" to "Taiwan",
        "HK" to "Hong Kong",
        "FR" to "France",
        "DE" to "Germany",
        "ES" to "Spain",
        "MX" to "Mexico",
        "BR" to "Brazil",
        "RU" to "Russia",
        "IT" to "Italy",
        "NL" to "Netherlands",
        "PL" to "Poland",
        "TR" to "Turkey",
        "AU" to "Australia",
        "CA" to "Canada",
        "IN" to "India",
        "ID" to "Indonesia",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "NG" to "Nigeria",
        "PH" to "Philippines",
        "MY" to "Malaysia",
        "SG" to "Singapore",
        "AR" to "Argentina",
        "CL" to "Chile",
        "CO" to "Colombia",
        "PE" to "Peru",
        "ZA" to "South Africa",
        "EG" to "Egypt",
        "SA" to "Saudi Arabia",
        "AE" to "United Arab Emirates",
    )

val LaunchCountKey = intPreferencesKey("launch_count")
val OnboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
val HasPressedStarKey = booleanPreferencesKey("has_pressed_star")
val RemindAfterKey = intPreferencesKey("remind_after")

val EnableUpdateNotificationKey = booleanPreferencesKey("enableUpdateNotification")
val UpdateChannelKey = stringPreferencesKey("updateChannel")
val LastUpdateCheckKey = longPreferencesKey("lastUpdateCheck")
val LastNotifiedVersionKey = stringPreferencesKey("lastNotifiedVersion")

val GitHubContributorsEtagKey = stringPreferencesKey("github_contributors_etag")
val GitHubContributorsJsonKey = stringPreferencesKey("github_contributors_json")
val GitHubContributorsLastCheckedAtKey = longPreferencesKey("github_contributors_last_checked_at")
val GitHubTranslationContributorsJsonKey = stringPreferencesKey("github_translation_contributors_json")
val GitHubTranslationContributorsLastCheckedAtKey = longPreferencesKey("github_translation_contributors_last_checked_at")

val GitHubReleasesEtagKey = stringPreferencesKey("github_releases_etag")
val GitHubReleasesJsonKey = stringPreferencesKey("github_releases_json")
val GitHubReleasesLastCheckedAtKey = longPreferencesKey("github_releases_last_checked_at")
val GitHubReleasesFingerprintKey = stringPreferencesKey("github_releases_fingerprint")

val TogetherOnlineEndpointCacheKey = stringPreferencesKey("together_online_endpoint_cache")
val TogetherOnlineEndpointLastCheckedAtKey = longPreferencesKey("together_online_endpoint_last_checked_at")

enum class UpdateChannel {
    STABLE,
    NIGHTLY,
    DAILY_NIGHTLY,
}

/** Bottom navigation bar layout: docked bar, floating bubble or bubble attached to the mini player. */
enum class NavigationBarMode {
    DOCKED,
    FLOATING,
    ATTACHED
}

/**
 * An item placeable in a navigation bar. [MORE] only marks the primary bar's
 * expand toggle; it never navigates and only applies while the second bar is non-empty.
 */
enum class NavBarItem {
    HOME,
    PLAYLISTS,
    SEARCH,
    EQUALIZER,
    STATS,
    SETTINGS,
    MORE,
}

/** The nav items that open a screen; [NavBarItem.MORE] is not one of them. */
val DefaultNavBarScreenItems =
    listOf(
        NavBarItem.HOME,
        NavBarItem.PLAYLISTS,
        NavBarItem.SEARCH,
        NavBarItem.EQUALIZER,
        NavBarItem.SETTINGS,
    )

/** Every switchable nav item (main bar + second bar). */
val AllNavBarScreenItems = DefaultNavBarScreenItems + NavBarItem.STATS

/** Default layout of the second bar: the screen items, with Stats last. */
val DefaultNavBarSecondaryOrder = DefaultNavBarScreenItems + NavBarItem.STATS

/** Default layout of the main bar: the screen items, with the "more" toggle last. */
val DefaultNavBarPrimaryOrder = DefaultNavBarScreenItems + NavBarItem.MORE

/**
 * Parses a stored, comma-separated [NavBarItem] order string, appending unknown
 * or missing entries in their default position so every [allowed] item appears exactly once.
 */
fun deserializeNavBarItemOrder(orderStr: String?, allowed: List<NavBarItem>): List<NavBarItem> {
    if (orderStr.isNullOrBlank()) return allowed

    val parsed =
        orderStr
            .split(",")
            .mapNotNull { name -> allowed.find { it.name == name.trim() } }
            .distinct()

    return parsed + allowed.filterNot { it in parsed }
}

/**
 * Layout of a collection screen's hero. The IMMERSIVE variants bleed the artwork
 * under the status bar with the text overlaid; Compact is image-left/title-right;
 * Normal is a big rounded artwork with the text underneath.
 */
enum class CollectionHeroLayout(val label: String) {
    Normal("Normal"),
    Immersive("Immersive"),
    Compact("Compact"),
    CompactImmersive("Compact Immersive"),
    ;

    /** True when the artwork bleeds under the status bar with overlaid text. */
    val isImmersive: Boolean
        get() = this == Immersive || this == CompactImmersive

    companion object {
        fun fromName(name: String?): CollectionHeroLayout? = entries.find { it.name == name }
    }
}

/** Which collection type a hero layout applies to; everything else resolves to [Default]. */
enum class CollectionHeroTarget {
    Default,
    Artist,
    Album,
    Playlist,
}

val CollectionLayoutDefaultKey = stringPreferencesKey("collection_layout_default")
val CollectionLayoutArtistKey = stringPreferencesKey("collection_layout_artist")
val CollectionLayoutAlbumKey = stringPreferencesKey("collection_layout_album")
val CollectionLayoutPlaylistKey = stringPreferencesKey("collection_layout_playlist")
