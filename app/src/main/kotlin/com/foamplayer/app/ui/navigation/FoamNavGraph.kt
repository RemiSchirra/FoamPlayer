package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.screens.home.HomeViewModel
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel

/**
 * Everything a destination needs from the navigation host.
 *
 * Built fresh on every host recomposition, so the registrations always close over
 * the current route state, per-view list settings and player state. The shared
 * navigation targets (artist/album/playlist) live here as helpers so screens do
 * not repeat the "tag the shared collection, then navigate" dance.
 */
internal class FoamNavGraphContext(
    val navController: NavHostController,
    val sharedTransitionScope: SharedTransitionScope,
    val playerTransitionVisibilityScope: AnimatedVisibilityScope,
    val homeViewModel: HomeViewModel,
    val playlistsViewModel: PlaylistsViewModel,
    val playlistCovers: Map<Long, PlaylistCover>,
    val activeCollectionId: String?,
    val activeTrackId: Long?,
    val isPlaybackActive: Boolean,
    val settings: AppSettings,
    val heroLayoutFor: (CollectionHeroTarget) -> CollectionHeroLayout,
    val densityFor: (MediaListView) -> MediaDensity,
    val setDensity: (MediaListView, MediaDensity) -> Unit,
    val sortModeFor: (MediaListView) -> CollectionSortMode,
    val setSortMode: (MediaListView, CollectionSortMode) -> Unit,
    val sortInvertedFor: (MediaListView) -> Boolean,
    val setSortInverted: (MediaListView, Boolean) -> Unit,
    val sharedTrackId: Long?,
    val centerTrackRequest: TrackCenterRequest?,
    val onListBackdropArt: (String?, Boolean) -> Unit,
    val onPlayTracks: (tracks: List<TrackEntity>, index: Int, collectionId: String?) -> Unit,
    val onActiveSharedCollectionChange: (String?) -> Unit,
) {
    fun back() {
        navController.popBackStack()
    }

    /** Opens an artist page, tagging the route pair for the shared-element flight. */
    fun openArtist(name: String, image: String? = null, imageIsEmbeddedArt: Boolean = false) {
        onActiveSharedCollectionChange(sharedCollectionTag("artist", name))
        navController.navigate(Screen.ArtistDetail.createRoute(name, image, imageIsEmbeddedArt))
    }

    /** Opens an album page, tagging the route pair for the shared-element flight. */
    fun openAlbum(name: String, artUrl: String? = null, artist: String? = null) {
        onActiveSharedCollectionChange(sharedCollectionTag("album", name))
        navController.navigate(Screen.AlbumDetail.createRoute(name, artUrl, artist))
    }

    /**
     * Opens a playlist page, preferring the resolved [playlistCovers] entry and
     * falling back to the list row's own image.
     */
    fun openPlaylist(playlistId: Long, name: String, fallbackImageUrl: String?) {
        onActiveSharedCollectionChange(sharedCollectionTag("playlist", "$playlistId"))
        val cover = playlistCovers[playlistId]
        navController.navigate(
            Screen.PlaylistDetail.createRoute(
                playlistId,
                name,
                cover?.imageUrl ?: fallbackImageUrl,
                cover?.isEmbeddedArt ?: false
            )
        )
    }
}

/** Registers every destination of the app on the nav graph. */
internal fun NavGraphBuilder.foamDestinationGraph(context: FoamNavGraphContext) {
    onboardingDestination(context)
    homeDestinations(context)
    browseDestinations(context)
    libraryDetailDestinations(context)
    statsDestinations(context)
    settingsDestinations(context)
}
