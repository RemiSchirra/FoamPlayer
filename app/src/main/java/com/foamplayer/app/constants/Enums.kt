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
 * The six list/grid density modes of the app's media lists, from the most
 * compact to the most spacious. The ROW_* modes render the shared row scaffold
 * (with growing artwork and text lines); the GRID_* modes render square
 * artwork cells in a fixed number of columns.
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

/**
 * One app view holding a media list. Each remembers its own [MediaDensity],
 * so per-view keys must stay stable across releases (they are persisted).
 */
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
}

/**
 * Sorting of a media list's content, chosen per view from the 3-dots
 * organisation modal and persisted per view. [CUSTOM] is only offered where a
 * user-defined order exists (playlist tracks, the playlists list itself); the
 * other modes apply to whatever the view holds — for track lists every mode is
 * meaningful, for collection lists (albums, artists, genres...) only the ones
 * the entry data supports are offered.
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
}

/** The sort modes offered for a list of tracks (everything but the playlist-only custom order). */
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
 * The sort a view starts with. Views holding a user-defined order start in it
 * (their stored order IS the display order today); year tracks keep their
 * album-grouped query order; the home see-all pages keep the query orders
 * their rows are named for; everything else is alphabetical.
 */
fun MediaListView.defaultSortMode(): CollectionSortMode = when (this) {
    MediaListView.PLAYLISTS,
    MediaListView.PLAYLIST_DETAIL -> CollectionSortMode.CUSTOM
    MediaListView.BROWSE_YEAR_TRACKS -> CollectionSortMode.ALBUM
    MediaListView.HOME_SEE_ALL_RECENT_TRACKS -> CollectionSortMode.LISTEN_DATE
    MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS -> CollectionSortMode.LISTEN_COUNT
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

// Queue lyrics pre-load settings
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

// App rating / star prompt preferences
val LaunchCountKey = intPreferencesKey("launch_count")
val OnboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
val HasPressedStarKey = booleanPreferencesKey("has_pressed_star")
val RemindAfterKey = intPreferencesKey("remind_after")

// Update settings
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

/**
 * Layout for the app's bottom navigation bar.
 *
 *  DOCKED   – classic bar at the bottom of the screen
 *  UNDOCKED – floating bubble
 *  ATTACHED – bubble but connect to the mini player
 */
enum class NavigationBarMode {
    DOCKED,
    FLOATING,
    ATTACHED
}

/**
 * An item that can be placed in one of the two navigation bars.
 *
 * [MORE] is a positional marker for the "more" toggle: it is only valid inside
 * the *primary* bar order and is rendered as the expand button (never navigates),
 * and only while the second bar is non-empty.
 */
enum class NavBarItem {
    HOME,
    PLAYLISTS,
    SEARCH,
    EQUALIZER,
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

/** Default layout of the main bar: the screen items, with the "more" toggle last. */
val DefaultNavBarPrimaryOrder = DefaultNavBarScreenItems + NavBarItem.MORE

/**
 * Parses a stored, comma-separated [NavBarItem] order string. Unknown or missing
 * entries are appended in their default position so the list always contains
 * every [allowed] item exactly once.
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
 * Layout of a collection screen's hero (the artwork + title header above the
 * list). COMPACT is the classic image-left/title-right row; the IMMERSIVE
 * variants bleed the artwork under the status bar with the text block overlaid
 * on it; NORMAL shows a big rounded artwork with the text block underneath.
 */
enum class CollectionHeroLayout(val label: String) {
    Compact("Compact"),
    Immersive("Immersive"),
    CompactImmersive("Compact Immersive"),
    Normal("Normal"),
    ;

    /** True when the artwork bleeds under the status bar with overlaid text. */
    val isImmersive: Boolean
        get() = this == Immersive || this == CompactImmersive

    companion object {
        fun fromName(name: String?): CollectionHeroLayout? = entries.find { it.name == name }
    }
}

/**
 * Which collection a hero layout applies to. Every collection that is not an
 * album, artist or playlist detail (genres, years, folders, all tracks and the
 * "list of lists" screens) resolves to [Default].
 */
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
