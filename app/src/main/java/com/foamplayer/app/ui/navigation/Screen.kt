package com.foamplayer.app.ui.navigation

import android.net.Uri

sealed class Screen(val route: String, val title: String) {
    object Library : Screen("library", "Library")
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
    object SettingsLibrary : Screen("settings/library", "Library Settings")
    object SettingsLyrics : Screen("settings/lyrics", "Lyrics Settings")
    object SettingsLyricsProvider : Screen("settings/lyrics/provider", "Lyrics Providers")
    object SettingsTest : Screen("settings/test", "Component Test")

    object ArtistDetail : Screen("artist/{artistName}", "Artist") {
        fun createRoute(artistName: String) = "artist/${Uri.encode(artistName)}"
    }
    object AlbumDetail : Screen("album/{albumName}?artUrl={artUrl}&artist={artist}", "Album") {
        fun createRoute(albumName: String, artUrl: String? = null, artist: String? = null): String {
            val encodedName = Uri.encode(albumName)
            val encodedArt = Uri.encode(artUrl.orEmpty())
            val encodedArtist = Uri.encode(artist.orEmpty())
            return "album/$encodedName?artUrl=$encodedArt&artist=$encodedArtist"
        }
    }
    object PlaylistDetail : Screen("playlist/{playlistId}", "Playlist") {
        fun createRoute(playlistId: Long) = "playlist/$playlistId"
    }

    object BrowseAlbums : Screen("browse/albums", "Albums")
    object BrowseArtists : Screen("browse/artists", "Artists")
    object BrowseGenres : Screen("browse/genres", "Genres")
    object BrowseYears : Screen("browse/years", "Years")
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
}
