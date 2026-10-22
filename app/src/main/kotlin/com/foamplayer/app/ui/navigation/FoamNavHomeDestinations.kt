package com.foamplayer.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable as navComposable
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.ui.screens.home.FavoriteArtistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.FavoriteTracksSeeAllScreen
import com.foamplayer.app.ui.screens.home.HomeScreen
import com.foamplayer.app.ui.screens.home.OldFavoriteAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.OldFavoritesSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentTracksSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentlyAddedAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentlyAddedArtistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedPlaylistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedTracksSeeAllScreen
import com.foamplayer.app.ui.screens.onboarding.OnboardingScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsScreen
import com.foamplayer.app.ui.screens.search.SearchScreen

internal fun NavGraphBuilder.onboardingDestination(context: FoamNavGraphContext) {
    navComposable(Screen.Onboarding.route) {
        OnboardingScreen(
            onFinish = {
                context.navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                }
            }
        )
    }
}

/** Home, its "see all" pages, the playlists tab and search. */
internal fun NavGraphBuilder.homeDestinations(context: FoamNavGraphContext) {
    composable(Screen.Home.route) {
        HomeScreen(
            viewModel = context.homeViewModel,
            onPlaylistClick = { playlist ->
                context.openPlaylist(playlist.playlistId, playlist.name, playlist.imageUrl)
            },
            onAlbumClick = { album ->
                context.openAlbum(album.album, album.albumArtPath, album.artist)
            },
            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                context.openArtist(artist, image, imageIsEmbeddedArt)
            },
            onTrackClick = { track -> context.onPlayTracks(listOf(track), 0, null) },
            onSeeAllAlbums = { context.navigateUnshared(Screen.HomeSeeAllAlbums.route) },
            onSeeAllRecent = { context.navigateUnshared(Screen.HomeSeeAllRecentTracks.route) },
            onSeeAllFavorites = { context.navigateUnshared(Screen.HomeSeeAllTopTracks.route) },
            onSeeAllArtists = { context.navigateUnshared(Screen.HomeSeeAllArtists.route) },
            onSeeAllFavoriteTracks = { context.navigateUnshared(Screen.HomeSeeAllFavoriteTracks.route) },
            onSeeAllRecommendedTracks = { context.navigateUnshared(Screen.HomeSeeAllRecommendedTracks.route) },
            onSeeAllOldFavoriteAlbums = { context.navigateUnshared(Screen.HomeSeeAllOldFavoriteAlbums.route) },
            onSeeAllRecentlyAddedAlbums = { context.navigateUnshared(Screen.HomeSeeAllRecentlyAddedAlbums.route) },
            onSeeAllRecentlyAddedArtists = { context.navigateUnshared(Screen.HomeSeeAllRecentlyAddedArtists.route) },
            onOpenHomeSettings = { context.navigateUnshared(Screen.SettingsUIHome.route) },
            onOpenLibrarySettings = { context.navigateUnshared(Screen.SettingsLibrary.route) },
            sections = context.settings.homeSections,
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId
        )
    }
    composable(Screen.HomeSeeAllPlaylists.route) {
        RecommendedPlaylistsSeeAllScreen(
            onPlaylistClick = { playlist ->
                context.openPlaylist(playlist.playlistId, playlist.name, playlist.imageUrl)
            },
            onBack = { context.back() },
            density = context.densityFor(MediaListView.HOME_SEE_ALL_PLAYLISTS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_PLAYLISTS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_PLAYLISTS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_PLAYLISTS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_PLAYLISTS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_PLAYLISTS, it) },
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllAlbums.route) {
        RecommendedAlbumsSeeAllScreen(
            onAlbumClick = { album ->
                context.openAlbum(album.album, album.albumArtPath, album.artist)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_ALBUMS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_ALBUMS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_ALBUMS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_ALBUMS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_ALBUMS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_ALBUMS, it) },
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllRecentTracks.route) {
        RecentTracksSeeAllScreen(
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_RECENT_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_RECENT_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_RECENT_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_RECENT_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_RECENT_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_RECENT_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllFavoriteTracks.route) {
        FavoriteTracksSeeAllScreen(
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllRecommendedTracks.route) {
        RecommendedTracksSeeAllScreen(
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllTopTracks.route) {
        OldFavoritesSeeAllScreen(
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) },
            onBack = { context.back() },
            activeTrackId = context.activeTrackId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_TOP_TRACKS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_TOP_TRACKS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_TOP_TRACKS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_TOP_TRACKS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_TOP_TRACKS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_TOP_TRACKS, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default),
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllOldFavoriteAlbums.route) {
        OldFavoriteAlbumsSeeAllScreen(
            onAlbumClick = { album ->
                context.openAlbum(album.album, album.albumArtPath, album.artist)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS, it) },
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllRecentlyAddedAlbums.route) {
        RecentlyAddedAlbumsSeeAllScreen(
            onAlbumClick = { album ->
                context.openAlbum(album.album, album.albumArtPath, album.artist)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS, it) },
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllArtists.route) {
        FavoriteArtistsSeeAllScreen(
            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                context.openArtist(artist, image, imageIsEmbeddedArt)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_ARTISTS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_ARTISTS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_ARTISTS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_ARTISTS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_ARTISTS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_ARTISTS, it) },
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.HomeSeeAllRecentlyAddedArtists.route) {
        RecentlyAddedArtistsSeeAllScreen(
            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                context.openArtist(artist, image, imageIsEmbeddedArt)
            },
            onBack = { context.back() },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS),
            onDensityChange = { context.setDensity(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS, it) },
            sortMode = context.sortModeFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS),
            onSortChange = { context.setSortMode(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS, it) },
            viewModel = context.homeViewModel
        )
    }
    composable(Screen.Playlists.route) {
        PlaylistsScreen(
            onPlaylistClick = { playlist ->
                context.openPlaylist(playlist.playlistId, playlist.name, playlist.imageUrl)
            },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(MediaListView.PLAYLISTS),
            onDensityChange = { context.setDensity(MediaListView.PLAYLISTS, it) },
            sortMode = context.sortModeFor(MediaListView.PLAYLISTS),
            onSortChange = { context.setSortMode(MediaListView.PLAYLISTS, it) },
            sortInverted = context.sortInvertedFor(MediaListView.PLAYLISTS),
            onSortInvertedChange = { context.setSortInverted(MediaListView.PLAYLISTS, it) }
        )
    }
    composable(Screen.Search.route) {
        SearchScreen(
            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                context.openArtist(artist, image, imageIsEmbeddedArt)
            },
            onAlbumClick = { albumResult ->
                context.openAlbum(albumResult.album, albumResult.albumArtPath, albumResult.artist)
            },
            onBrowseAllTracks = { context.navigateUnshared(Screen.BrowseAllTracks.route) },
            onBrowseAlbums = { context.navigateUnshared(Screen.BrowseAlbums.route) },
            onBrowseFolders = { context.navigateUnshared(Screen.BrowseFolders.createRoute()) },
            onBrowseGenres = { context.navigateUnshared(Screen.BrowseGenres.route) },
            onBrowseArtists = { context.navigateUnshared(Screen.BrowseArtists.route) },
            onBrowseComposers = { context.navigateUnshared(Screen.BrowseComposers.route) },
            onBrowseYears = { context.navigateUnshared(Screen.BrowseYears.route) },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            activeTrackId = context.activeTrackId,
            onTrackClick = { track -> context.onPlayTracks(listOf(track), 0, null) }
        )
    }
}
