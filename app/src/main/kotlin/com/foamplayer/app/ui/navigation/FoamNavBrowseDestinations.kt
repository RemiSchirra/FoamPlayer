package com.foamplayer.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.screens.browse.AlbumsBrowseScreen
import com.foamplayer.app.ui.screens.browse.AllTracksBrowseScreen
import com.foamplayer.app.ui.screens.browse.ArtistsBrowseScreen
import com.foamplayer.app.ui.screens.browse.CollectionAlbumsScreen
import com.foamplayer.app.ui.screens.browse.CollectionArtistsScreen
import com.foamplayer.app.ui.screens.browse.ComposersBrowseScreen
import com.foamplayer.app.ui.screens.browse.ComposerTracksScreen
import com.foamplayer.app.ui.screens.browse.FolderBrowseScreen
import com.foamplayer.app.ui.screens.browse.GenresBrowseScreen
import com.foamplayer.app.ui.screens.browse.GenreTracksScreen
import com.foamplayer.app.ui.screens.browse.YearsBrowseScreen
import com.foamplayer.app.ui.screens.browse.YearTracksScreen
import com.foamplayer.app.ui.screens.equalizer.EqualizerScreen

/** Browse lists, their detail pages and the standalone equalizer. */
internal fun NavGraphBuilder.browseDestinations(context: FoamNavGraphContext) {
    composable(Screen.BrowseAlbums.route) {
        AlbumsBrowseScreen(
            onAlbumClick = { album ->
                context.openAlbum(album.album, album.albumArtPath, album.artist)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_ALBUMS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_ALBUMS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_ALBUMS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_ALBUMS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_ALBUMS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_ALBUMS, it) }
        )
    }
    composable(Screen.BrowseAllTracks.route) {
        AllTracksBrowseScreen(
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.BROWSE_ALL_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_ALL_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_ALL_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_ALL_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_ALL_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_ALL_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default)
        )
    }
    composable(Screen.BrowseArtists.route) {
        ArtistsBrowseScreen(
            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                context.openArtist(artist, image, imageIsEmbeddedArt)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_ARTISTS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_ARTISTS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_ARTISTS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_ARTISTS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_ARTISTS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_ARTISTS, it) }
        )
    }
    composable(Screen.BrowseComposers.route) {
        ComposersBrowseScreen(
            onComposerClick = { composer ->
                context.navController.navigate(Screen.BrowseComposerDetail.createRoute(composer))
            },
            onBack = { context.back() },
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_COMPOSERS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_COMPOSERS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_COMPOSERS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_COMPOSERS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_COMPOSERS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_COMPOSERS, it) }
        )
    }
    composable(Screen.BrowseGenres.route) {
        GenresBrowseScreen(
            onGenreClick = { genre ->
                context.navController.navigate(Screen.BrowseGenreDetail.createRoute(genre))
            },
            onBack = { context.back() },
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_GENRES),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_GENRES, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_GENRES),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_GENRES, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_GENRES),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_GENRES, it) }
        )
    }
    composable(Screen.BrowseYears.route) {
        YearsBrowseScreen(
            onYearClick = { year ->
                context.navController.navigate(Screen.BrowseYearDetail.createRoute(year))
            },
            onBack = { context.back() },
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_YEARS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_YEARS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_YEARS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_YEARS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_YEARS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_YEARS, it) }
        )
    }
    composable(
        Screen.BrowseGenreDetail.route,
        arguments = listOf(navArgument("genre") { type = NavType.StringType })
    ) { backStackEntry ->
        val genre = backStackEntry.arguments?.getString("genre") ?: ""
        GenreTracksScreen(
            genre = genre,
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.genre(genre))
            },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.BROWSE_GENRE_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_GENRE_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_GENRE_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_GENRE_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_GENRE_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_GENRE_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            centerTrackRequest = context.centerTrackRequest,
            isPlaybackActive = context.isPlaybackActive,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            immersiveMode = context.settings.immersiveModeEnabled
        )
    }
    composable(
        Screen.BrowseYearDetail.route,
        arguments = listOf(navArgument("year") { type = NavType.IntType })
    ) { backStackEntry ->
        val year = backStackEntry.arguments?.getInt("year") ?: 0
        YearTracksScreen(
            year = year,
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.year(year))
            },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.BROWSE_YEAR_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_YEAR_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_YEAR_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_YEAR_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_YEAR_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_YEAR_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            centerTrackRequest = context.centerTrackRequest,
            isPlaybackActive = context.isPlaybackActive,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            immersiveMode = context.settings.immersiveModeEnabled
        )
    }
    composable(
        Screen.BrowseComposerDetail.route,
        arguments = listOf(navArgument("composer") { type = NavType.StringType })
    ) { backStackEntry ->
        val composer = backStackEntry.arguments?.getString("composer") ?: ""
        ComposerTracksScreen(
            composer = composer,
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, CollectionId.composer(composer))
            },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.BROWSE_COMPOSER_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_COMPOSER_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_COMPOSER_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_COMPOSER_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_COMPOSER_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_COMPOSER_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            centerTrackRequest = context.centerTrackRequest,
            isPlaybackActive = context.isPlaybackActive,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            immersiveMode = context.settings.immersiveModeEnabled
        )
    }
    composable(
        Screen.BrowseArtistsIn.route,
        arguments = listOf(
            navArgument("kind") { type = NavType.StringType },
            navArgument("key") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        CollectionArtistsScreen(
            kind = backStackEntry.arguments?.getString("kind").orEmpty(),
            key = backStackEntry.arguments?.getString("key").orEmpty(),
            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                context.openArtist(artist, image, imageIsEmbeddedArt)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_ARTISTS_IN),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_ARTISTS_IN, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_ARTISTS_IN),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_ARTISTS_IN, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_ARTISTS_IN),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_ARTISTS_IN, it) }
        )
    }
    composable(
        Screen.BrowseAlbumsIn.route,
        arguments = listOf(
            navArgument("kind") { type = NavType.StringType },
            navArgument("key") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        CollectionAlbumsScreen(
            kind = backStackEntry.arguments?.getString("kind").orEmpty(),
            key = backStackEntry.arguments?.getString("key").orEmpty(),
            onAlbumClick = { entry ->
                context.openAlbum(entry.album, entry.albumArtPath, entry.artist)
            },
            onBack = { context.back() },
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_ALBUMS_IN),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_ALBUMS_IN, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_ALBUMS_IN),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_ALBUMS_IN, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_ALBUMS_IN),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_ALBUMS_IN, it) }
        )
    }
    composable(
        Screen.BrowseFolders.route,
        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
    ) { backStackEntry ->
        val path = (backStackEntry.arguments?.getString("path") ?: "").ifEmpty { null }
        FolderBrowseScreen(
            path = path,
            onFolderClick = { folderPath ->
                context.navController.navigate(Screen.BrowseFolders.createRoute(folderPath))
            },
            onTrackClick = { tracks, index ->
                context.onPlayTracks(tracks, index, path?.let { CollectionId.folder(it) })
            },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.BROWSE_FOLDERS),
            onDensityChange = { context.setDensity(MediaListView.BROWSE_FOLDERS, it) },
            sortMode = context.sortModeFor(MediaListView.BROWSE_FOLDERS),
            onSortChange = { context.setSortMode(MediaListView.BROWSE_FOLDERS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.BROWSE_FOLDERS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.BROWSE_FOLDERS, it) }
        )
    }
    composable(Screen.Equalizer.route) {
        EqualizerScreen()
    }
}
