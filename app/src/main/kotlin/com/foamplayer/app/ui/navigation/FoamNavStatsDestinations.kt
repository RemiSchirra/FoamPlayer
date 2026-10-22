package com.foamplayer.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.ui.screens.stats.StatsScreen
import com.foamplayer.app.ui.screens.stats.StatsSeeAllScreen
import com.foamplayer.app.ui.screens.stats.StatsTopKind

internal fun NavGraphBuilder.statsDestinations(context: FoamNavGraphContext) {
    composable(Screen.Stats.route) {
        StatsScreen(
            onArtistClick = { artistName, image, imageIsEmbeddedArt ->
                context.openArtist(artistName, image, imageIsEmbeddedArt)
            },
            onAlbumClick = { album, artUrl, artist ->
                context.openAlbum(album, artUrl, artist)
            },
            onSeeAll = { kind, period ->
                context.navController.navigate(
                    Screen.StatsSeeAll.createRoute(kind.name, period.name)
                )
            },
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) }
        )
    }
    composable(Screen.StatsSeeAll.route) { backStackEntry ->
        val kind = backStackEntry.arguments?.getString("kind")
            ?.let { name -> StatsTopKind.entries.find { it.name == name } }
            ?: StatsTopKind.TRACKS
        val view = when (kind) {
            StatsTopKind.TRACKS -> MediaListView.STATS_SEE_ALL_TRACKS
            StatsTopKind.ARTISTS -> MediaListView.STATS_SEE_ALL_ARTISTS
            StatsTopKind.ALBUMS -> MediaListView.STATS_SEE_ALL_ALBUMS
        }
        StatsSeeAllScreen(
            onBack = { context.back() },
            onArtistClick = { artistName, image, imageIsEmbeddedArt ->
                context.openArtist(artistName, image, imageIsEmbeddedArt)
            },
            onAlbumClick = { album, artUrl, artist ->
                context.openAlbum(album, artUrl, artist)
            },
            onTrackClick = { tracks, index -> context.onPlayTracks(tracks, index, null) },
            activeTrackId = context.activeTrackId,
            activeCollectionId = context.activeCollectionId,
            density = context.densityFor(view),
            onDensityChange = { context.setDensity(view, it) },
            sortMode = context.sortModeFor(view),
            onSortChange = { context.setSortMode(view, it) },
            sortInverted = context.sortInvertedFor(view),
            onSortInvertedChange = { context.setSortInverted(view, it) },
            sharedTransitionScope = context.sharedTransitionScope,
            animatedVisibilityScope = this,
            playerTransitionVisibilityScope = context.playerTransitionVisibilityScope,
            sharedTrackId = context.sharedTrackId,
            heroLayout = context.heroLayoutFor(CollectionHeroTarget.Default)
        )
    }
}
