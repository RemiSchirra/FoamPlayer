package com.foamplayer.app.ui.navigation

import android.net.Uri

sealed class Screen(val route: String, val title: String) {
    object Onboarding : Screen("onboarding", "Onboarding")
    object Home : Screen("home", "Home")
    object Playlists : Screen("playlists", "Playlists")
    object Search : Screen("search", "Search")
    object Stats : Screen("stats", "Statistics")
    object StatsSeeAll : Screen("stats/see-all/{kind}?period={period}", "Statistics") {
        /** [kind] is a [StatsTopKind] name, [period] a [StatsPeriod] name. */
        fun createRoute(kind: String, period: String): String =
            "stats/see-all/$kind?period=$period"
    }
    object Equalizer : Screen("equalizer", "Equalizer")
    object Settings : Screen("settings", "Settings")
    object SettingsAudio : Screen("settings/audio", "Audio Settings")
    object SettingsAudioPlayback : Screen("settings/audio/playback", "Playback Settings")
    object SettingsAudioDevices : Screen("settings/audio/devices", "Audio Devices")
    object SettingsAudioEqualizer : Screen("settings/audio/equalizer", "Equalizer Settings")
    object SettingsUI : Screen("settings/ui", "UI Settings")
    object SettingsColors : Screen("settings/ui/colors", "Color Settings")
    object SettingsPlayer : Screen("settings/ui/player", "Player Settings")
    object SettingsNavBar : Screen("settings/ui/navbar", "Navigation Bar Settings")
    object SettingsBackground : Screen("settings/ui/background", "Background Settings")
    object SettingsUICollections : Screen("settings/ui/collections", "Collection Layouts")
    object SettingsUIHome : Screen("settings/ui/home", "Home Layout")
    object SettingsLibrary : Screen("settings/library", "Library Settings")
    object SettingsLyrics : Screen("settings/lyrics", "Lyrics Settings")
    object SettingsLyricsManager : Screen("settings/lyrics/manager", "Lyrics Manager")
    object SettingsLyricsManagerResults :
        Screen("settings/lyrics/manager/results?trackId={trackId}", "Lyrics Search Results") {
        fun createRoute(trackId: Long) = "settings/lyrics/manager/results?trackId=$trackId"
    }
    object SettingsMisc : Screen("settings/misc", "Misc Settings")
    object SettingsNotification : Screen("settings/misc/notification", "Notification Settings")
    object SettingsTest : Screen("settings/test", "Component Test")

    object ArtistDetail : Screen("artist/{artistName}?image={image}&embedded={embedded}", "Artist") {
        /**
         * [image] seeds the hero on its very first frame (before the cache lookup
         * completes) so the shared-element transition from the artist lists never
         * flashes the letter fallback — the same mechanism as [PlaylistDetail].
         * [imageIsEmbeddedArt] says how the seed loads: a Spotify picture is a
         * plain file, the automatic first-track fallback is an audio file whose
         * embedded picture must be extracted.
         */
        fun createRoute(
            artistName: String,
            image: String? = null,
            imageIsEmbeddedArt: Boolean = false
        ): String =
            "artist/${Uri.encode(artistName)}" +
                "?image=${Uri.encode(image.orEmpty())}&embedded=$imageIsEmbeddedArt"
    }
    object ArtistSongs : Screen("artist/{artistName}/songs", "Artist Songs") {
        fun createRoute(artistName: String) = "artist/${Uri.encode(artistName)}/songs"
    }
    object AlbumDetail : Screen("album/{albumName}?artUrl={artUrl}&artist={artist}", "Album") {
        fun createRoute(albumName: String, artUrl: String? = null, artist: String? = null): String {
            val encodedName = Uri.encode(albumName)
            val encodedArt = Uri.encode(artUrl.orEmpty())
            val encodedArtist = Uri.encode(artist.orEmpty())
            return "album/$encodedName?artUrl=$encodedArt&artist=$encodedArtist"
        }
    }
    object PlaylistDetail : Screen("playlist/{playlistId}?name={name}&imageUrl={imageUrl}&embedded={embedded}", "Playlist") {
        /**
         * [name]/[imageUrl] seed the header on its very first frame (before the
         * DB-loaded playlist arrives) so the shared-element transition from the
         * playlists list never flashes the letter/title fallback. [embedded]
         * says how [imageUrl] loads: a custom cover is a plain image URI while
         * the automatic first-track fallback is an audio file whose embedded
         * picture must be extracted — the same mechanism as [ArtistDetail].
         */
        fun createRoute(
            playlistId: Long,
            name: String,
            imageUrl: String? = null,
            imageIsEmbeddedArt: Boolean = false
        ): String =
            "playlist/$playlistId?name=${Uri.encode(name)}" +
                "&imageUrl=${Uri.encode(imageUrl.orEmpty())}&embedded=$imageIsEmbeddedArt"
    }

    object BrowseAlbums : Screen("browse/albums", "Albums")
    object BrowseAllTracks : Screen("browse/tracks", "All Tracks")
    object BrowseArtists : Screen("browse/artists", "Artists")
    object BrowseGenres : Screen("browse/genres", "Genres")
    object BrowseYears : Screen("browse/years", "Years")

    // "See all" pages behind the Home screen's rows: up to 50 items of one row's
    // type, sorted identically to the row.
    object HomeSeeAllPlaylists : Screen("browse/home/playlists", "Recommended Playlists")
    object HomeSeeAllAlbums : Screen("browse/home/albums", "Recommended Albums")
    object HomeSeeAllRecentTracks : Screen("browse/home/recent", "Recent Listens")
    object HomeSeeAllFavoriteTracks : Screen("browse/home/favorite-tracks", "Favorite Tracks")
    object HomeSeeAllTopTracks : Screen("browse/home/favorites", "Old Favorites")
    object HomeSeeAllOldFavoriteAlbums : Screen("browse/home/old-favorite-albums", "Old Favorite Albums")
    object HomeSeeAllArtists : Screen("browse/home/artists", "Favorite Artists")
    object HomeSeeAllRecentlyAddedAlbums : Screen("browse/home/recently-added-albums", "Recently Added Albums")
    object HomeSeeAllRecentlyAddedArtists : Screen("browse/home/recently-added-artists", "Recently Added Artists")
    object BrowseGenreDetail : Screen("browse/genre/{genre}", "Genre") {
        fun createRoute(genre: String) = "browse/genre/${Uri.encode(genre)}"
    }
    object BrowseYearDetail : Screen("browse/year/{year}", "Year") {
        fun createRoute(year: Int) = "browse/year/$year"
    }
    object BrowseFolders : Screen("browse/folders?path={path}", "Folders") {
        fun createRoute(path: String? = null): String {
            val encoded = Uri.encode(path.orEmpty())
            return "browse/folders?path=$encoded"
        }
    }

    // List views behind the collection modal's "See Artist" / "See Album" rows:
    // the artists/albums held by one source collection (kind = "Genre" / "Year" /
    // "Folder", key = genre name / year / folder path).
    object BrowseArtistsIn : Screen("browse/artists-in/{kind}/{key}", "Artists In") {
        fun createRoute(kind: String, key: String) = "browse/artists-in/$kind/${Uri.encode(key)}"
    }
    object BrowseAlbumsIn : Screen("browse/albums-in/{kind}/{key}", "Albums In") {
        fun createRoute(kind: String, key: String) = "browse/albums-in/$kind/${Uri.encode(key)}"
    }
}
