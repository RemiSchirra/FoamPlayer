package com.foamplayer.app.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

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

val LyricsLineBlurKey = booleanPreferencesKey("lyricsLineBlur")
val LyricsWordSyncDisabledKey = booleanPreferencesKey("lyricsWordSyncDisabled")


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

/** Default layout of the main bar: every placeable item, with the "more" toggle last. */
val DefaultNavBarPrimaryOrder = AllNavBarScreenItems + NavBarItem.MORE

/**
 * Completes [order] so it contains every [allowed] item exactly once, appending
 * missing entries in their default position. Newly-added entries slot in before
 * a trailing [NavBarItem.MORE] marker so the main bar's expand toggle keeps its
 * default last position (the case of stored layouts written before an item
 * existed, e.g. the Stats migration); everywhere else they are appended at the end.
 */
fun completeNavBarItemOrder(order: List<NavBarItem>, allowed: List<NavBarItem>): List<NavBarItem> {
    val parsed = order.filter { it in allowed }.distinct()
    val missing = allowed.filterNot { it in parsed }
    if (missing.isEmpty()) return parsed
    return if (parsed.lastOrNull() == NavBarItem.MORE) {
        parsed.dropLast(1) + missing + NavBarItem.MORE
    } else {
        parsed + missing
    }
}

/**
 * Parses a stored, comma-separated [NavBarItem] order string, completing it with
 * [completeNavBarItemOrder] so every [allowed] item appears exactly once.
 */
fun deserializeNavBarItemOrder(orderStr: String?, allowed: List<NavBarItem>): List<NavBarItem> {
    if (orderStr.isNullOrBlank()) return allowed

    val parsed =
        orderStr
            .split(",")
            .mapNotNull { name -> allowed.find { it.name == name.trim() } }
            .distinct()

    return completeNavBarItemOrder(parsed, allowed)
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
