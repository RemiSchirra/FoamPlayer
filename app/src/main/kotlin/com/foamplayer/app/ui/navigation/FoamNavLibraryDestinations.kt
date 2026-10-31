package com.foamplayer.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.data.settings.PlayerMode
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.screens.library.AlbumDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistSongsScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistDetailScreen

/** Artist, album and playlist pages (the shared-element detail screens). */
internal fun NavGraphBuilder.libraryDetailDestinations(context: FoamNavGraphContext) {
    composable(
        Screen.ArtistDetail.route,
        arguments = listOf(
            navArgument("artistName") { type = NavType.StringType },
            navArgument("image") { type = NavType.StringType; defaultValue = "" },
            navArgument("embedded") { type = NavType.StringType; defaultValue = "false" }
        )
    ) { backStackEntry ->
        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
        val seedImageUrl = backStackEntry.arguments?.getString("image")
            ?.takeIf { it.isNotBlank() }
        val seedImageIsEmbeddedArt = backStackEntry.arguments
            ?.getString("embedded")?.toBooleanStrictOrNull() ?: false
        ArtistDetailScreen(
            artistName = artistName,
            seedImageUrl = seedImageUrl,
            seedImageIsEmbeddedArt = seedImageIsEmbeddedArt,
            onAlbumClick = { album ->
                context.openAlbum(album.album, album.albumArtPath, album.artist)
            },
            onAllSongsClick = {
                context.navController.navigate(Screen.ArtistSongs.createRoute(artistName))
            },
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.artist(artistName))
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            activeCollectionId = context.activeCollectionId,
            activeTrackId = context.activeTrackId,
            onListBackdropArt = context.onListBackdropArt,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Artist)
        )
    }
    composable(
        Screen.ArtistSongs.route,
        arguments = listOf(navArgument("artistName") { type = NavType.StringType })
    ) { backStackEntry ->
        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
        ArtistSongsScreen(
            artistName = artistName,
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.artist(artistName))
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            activeTrackId = context.activeTrackId,
            onListBackdropArt = context.onListBackdropArt,
            centerTrackRequest = context.centerTrackRequest,
            isPlaybackActive = context.isPlaybackActive,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Artist),
            immersiveMode = context.settings.playerMode != PlayerMode.NORMAL
        )
    }
    composable(
        Screen.AlbumDetail.route,
        arguments = listOf(
            navArgument("albumName") { type = NavType.StringType },
            navArgument("artUrl") { type = NavType.StringType; defaultValue = "" },
            navArgument("artist") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
        val artUrl = (backStackEntry.arguments?.getString("artUrl") ?: "").ifEmpty { null }
        val artist = (backStackEntry.arguments?.getString("artist") ?: "").ifEmpty { null }
        AlbumDetailScreen(
            albumName = albumName,
            initialImageUrl = artUrl,
            initialArtistName = artist,
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.album(albumName))
            },
            onBack = { context.back() },
            onArtistClick = { artistName ->
                context.openArtist(artistName)
            },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            activeTrackId = context.activeTrackId,
            onListBackdropArt = context.onListBackdropArt,
            centerTrackRequest = context.centerTrackRequest,
            isPlaybackActive = context.isPlaybackActive,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Album),
            immersiveMode = context.settings.playerMode != PlayerMode.NORMAL
        )
    }
    composable(
        Screen.PlaylistDetail.route,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.LongType },
            navArgument("name") { type = NavType.StringType; defaultValue = "" },
            navArgument("imageUrl") { type = NavType.StringType; defaultValue = "" },
            navArgument("embedded") { type = NavType.StringType; defaultValue = "false" }
        )
    ) { backStackEntry ->
        val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: -1L
        val initialName = backStackEntry.arguments?.getString("name").orEmpty()
        val initialImageUrl = backStackEntry.arguments?.getString("imageUrl")?.ifEmpty { null }
        val initialImageIsEmbeddedArt = backStackEntry.arguments
            ?.getString("embedded")?.toBooleanStrictOrNull() ?: false
        PlaylistDetailScreen(
            playlistId = playlistId,
            initialName = initialName,
            initialImageUrl = initialImageUrl,
            initialImageIsEmbeddedArt = initialImageIsEmbeddedArt,
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.playlist(playlistId))
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            activeTrackId = context.activeTrackId,
            onListBackdropArt = context.onListBackdropArt,
            density = context.densityFor(MediaListView.PLAYLIST_DETAIL),
            onDensityChange = { context.setDensity(MediaListView.PLAYLIST_DETAIL, it) },
            sortMode = context.sortModeFor(MediaListView.PLAYLIST_DETAIL),
            onSortChange = { context.setSortMode(MediaListView.PLAYLIST_DETAIL, it) },
            sortInverted = context.sortInvertedFor(MediaListView.PLAYLIST_DETAIL),
            onSortInvertedChange = { context.setSortInverted(MediaListView.PLAYLIST_DETAIL, it) },
            centerTrackRequest = context.centerTrackRequest,
            isPlaybackActive = context.isPlaybackActive,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Playlist),
            immersiveMode = context.settings.playerMode != PlayerMode.NORMAL
        )
    }
}
