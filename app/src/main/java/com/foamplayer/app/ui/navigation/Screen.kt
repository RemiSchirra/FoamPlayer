package com.foamplayer.app.ui.navigation

import android.net.Uri

sealed class Screen(val route: String, val title: String) {
    object Onboarding : Screen("onboarding", "Onboarding")
    object Home : Screen("home", "Home")
    object Playlists : Screen("playlists", "Playlists")
    object Search : Screen("search", "Search")
    object Equalizer : Screen("equalizer", "Equalizer")
    object Settings : Screen("settings", "Settings")
    object SettingsAudio : Screen("settings/audio", "Audio Settings")
    object SettingsUI : Screen("settings/ui", "UI Settings")
    object SettingsColors : Screen("settings/ui/colors", "Color Settings")
    object SettingsPlayer : Screen("settings/ui/player", "Player Settings")
    object SettingsNavBar : Screen("settings/ui/navbar", "Navigation Bar Settings")
    object SettingsBackground : Screen("settings/ui/background", "Background Settings")
    object SettingsUICollections : Screen("settings/ui/collections", "Collection Layouts")
    object SettingsLibrary : Screen("settings/library", "Library Settings")
    object SettingsLyrics : Screen("settings/lyrics", "Lyrics Settings")
    object SettingsLyricsManager : Screen("settings/lyrics/manager", "Lyrics Manager")
    object SettingsLyricsManagerResults :
        Screen("settings/lyrics/manager/results?trackId={trackId}", "Lyrics Search Results") {
        fun createRoute(trackId: Long) = "settings/lyrics/manager/results?trackId=$trackId"
    }
    object SettingsMisc : Screen("settings/misc", "Misc Settings")
    object SettingsTest : Screen("settings/test", "Component Test")

    object ArtistDetail : Screen("artist/{artistName}", "Artist") {
        fun createRoute(artistName: String) = "artist/${Uri.encode(artistName)}"
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
    object PlaylistDetail : Screen("playlist/{playlistId}?name={name}&imageUrl={imageUrl}", "Playlist") {
        /**
         * [name]/[imageUrl] seed the header on its very first frame (before the
         * DB-loaded playlist arrives) so the shared-element transition from the
         * playlists list never flashes the letter/title fallback.
         */
        fun createRoute(playlistId: Long, name: String, imageUrl: String? = null): String =
            "playlist/$playlistId?name=${Uri.encode(name)}&imageUrl=${Uri.encode(imageUrl.orEmpty())}"
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
    object HomeSeeAllFavoriteTracks : Screen("browse/home/favorites", "Old Favorites")
    object HomeSeeAllArtists : Screen("browse/home/artists", "Favorite Artists")
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
