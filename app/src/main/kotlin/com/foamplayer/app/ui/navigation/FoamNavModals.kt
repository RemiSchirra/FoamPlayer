package com.foamplayer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.PlaylistInsertPosition
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.ui.modals.CollectionFoamModal
import com.foamplayer.app.ui.modals.CollectionFoamModalState
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.PlaylistPickerModal
import com.foamplayer.app.ui.modals.TrackFoamModal
import com.foamplayer.app.ui.modals.TrackFoamModalState
import com.foamplayer.app.ui.modals.TrackRadioModal
import com.foamplayer.app.ui.modals.TrackRadioState
import com.foamplayer.app.ui.modals.TrackRadioViewModel
import com.foamplayer.app.ui.modals.UpdateFoamModal
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsDetailsViewModel
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsTrackDetails
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsTrackDetailsModal
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.update.DownloadState
import com.foamplayer.app.update.GitHubRelease
import com.foamplayer.app.update.UpdateManager
import java.io.File

/**
 * Mutable state owned by the root host and shared between the destination graph
 * (the collection modal, playlist pickers) and the global dialogs.
 */
@Stable
internal class FoamNavModalState {
    var playlistPickerTrack by mutableStateOf<TrackEntity?>(null)
    var pickerBatchCollection by mutableStateOf<CollectionInfo?>(null)
    var pickerBatchTracks by mutableStateOf<List<TrackEntity>?>(null)
    var pickerExcludedPlaylistId by mutableStateOf<Long?>(null)

    private var _lyricsModalTrack by mutableStateOf<TrackEntity?>(null)
    var lyricsModalTrack: TrackEntity?
        get() = _lyricsModalTrack
        set(value) {
            _lyricsModalTrack = value
            if (value != null) lastLyricsModalTrack = value
        }

    var lastLyricsModalTrack by mutableStateOf<TrackEntity?>(null)
        private set

    /** Keeps the previous track while the lyrics sheet slides away. */
    val lyricsDisplayTrack: TrackEntity?
        get() = lyricsModalTrack ?: lastLyricsModalTrack

    fun openPlaylistPickerFor(track: TrackEntity) {
        playlistPickerTrack = track
    }

    fun openPlaylistPickerFor(collection: CollectionInfo) {
        pickerBatchCollection = collection
        pickerBatchTracks = collection.tracks
    }

    fun dismissPlaylistPicker() {
        playlistPickerTrack = null
        pickerBatchCollection = null
        pickerBatchTracks = null
        pickerExcludedPlaylistId = null
    }
}

/**
 * Every global dialog hosted above the nav tree: update sheets, track and
 * collection long-press actions, the playlist picker, song radio and lyrics.
 */
@Composable
internal fun FoamGlobalModals(
    modalState: FoamNavModalState,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    playlistsViewModel: PlaylistsViewModel,
    trackRadioViewModel: TrackRadioViewModel,
    lyricsDetailsViewModel: LyricsDetailsViewModel,
    updateManager: UpdateManager,
    updateStartupRelease: GitHubRelease?,
    updateDownloadState: DownloadState,
    updateInstallFile: File?,
    trackModalState: TrackFoamModalState,
    collectionModalState: CollectionFoamModalState,
    trackRadioState: TrackRadioState,
    lyricsTrackDetails: LyricsTrackDetails?,
    isLyricsDetailsLoading: Boolean,
    playlists: List<com.foamplayer.app.data.db.entities.PlaylistEntity>,
    playlistCovers: Map<Long, PlaylistCover>,
    playlistIdsWithTrack: Set<Long>,
    playlistIdsWithBatch: Set<Long>,
    addTrackInsertPosition: PlaylistInsertPosition,
    settings: AppSettings,
    scoreOverrides: Map<Long, Int>,
    existingGenres: List<String>,
    onNavigateClosingPlayer: (route: String, pattern: String) -> Unit,
    onActiveSharedCollectionChange: (String?) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (album: String, albumArtPath: String?, artist: String?) -> Unit,
) {
    // Update sheets: startup "new update available" (auto-check) and the
    // "install" prompt raised whenever a download completes. Dismissing a sheet
    // never stops the download; only the explicit "Cancel download" button does.
    UpdateFoamModal(
        startupRelease = updateStartupRelease,
        downloadState = updateDownloadState,
        installFile = updateInstallFile,
        onDismissStartup = updateManager::dismissStartupRelease,
        onCancelDownload = updateManager::cancelDownload,
        onDownload = updateManager::downloadAndInstall,
        onInstall = updateManager::installApk,
        onDismissInstall = updateManager::dismissInstallPrompt
    )

    // Long-press track actions (plain Dialogs hosted at the root).
    TrackFoamModal(
        state = trackModalState,
        onPlayNext = playerViewModel::playNext,
        onAddToQueue = playerViewModel::addToQueue,
        onAddToPlaylist = { track -> modalState.openPlaylistPickerFor(track) },
        onSeeArtist = { track ->
            onActiveSharedCollectionChange(sharedCollectionTag("artist", track.artist))
            onNavigateClosingPlayer(
                Screen.ArtistDetail.createRoute(track.artist),
                Screen.ArtistDetail.route
            )
        },
        onSeeAlbum = { track ->
            onActiveSharedCollectionChange(sharedCollectionTag("album", track.album))
            onNavigateClosingPlayer(
                Screen.AlbumDetail.createRoute(track.album, track.albumArtPath, track.artist),
                Screen.AlbumDetail.route
            )
        },
        onSeeRadio = trackRadioViewModel::openRadioFor,
        onOpenLyrics = { track ->
            modalState.lyricsModalTrack = track
            lyricsDetailsViewModel.loadTrackDetails(track)
        },
        onResetPlayStats = { track ->
            playerViewModel.resetPlayStats(track.id)
            trackModalState.updateCurrentTrack { it.copy(playCount = 0, lastPlayedAt = null) }
        },
        onApplyMetadata = { track, edit ->
            playerViewModel.updateTrackMetadata(track, edit)
            trackModalState.updateCurrentTrack {
                it.copy(
                    title = edit.title,
                    artist = edit.artist,
                    album = edit.album,
                    albumArtist = edit.albumArtist,
                    genre = edit.genre.ifEmpty { null },
                    composer = edit.composer,
                    year = edit.year,
                    trackNumber = edit.trackNumber,
                    discNumber = edit.discNumber
                )
            }
        },
        onSetInstrumental = { track, instrumental ->
            playerViewModel.setTrackInstrumental(track.id, instrumental)
            trackModalState.updateCurrentTrack { it.copy(instrumental = instrumental) }
        },
        scoreOverrides = scoreOverrides,
        existingGenres = existingGenres,
        onSetScore = playerViewModel::setTrackScore,
        useAlbumArtAccent = settings.automaticColorEnabled
    )

    PlaylistPickerModal(
        track = modalState.playlistPickerTrack,
        playlists = modalState.pickerExcludedPlaylistId
            ?.let { excluded -> playlists.filterNot { it.playlistId == excluded } }
            ?: playlists,
        playlistCovers = playlistCovers,
        playlistIdsWithTrack = if (modalState.pickerBatchCollection != null) {
            playlistIdsWithBatch
        } else {
            playlistIdsWithTrack
        },
        insertPosition = addTrackInsertPosition,
        onToggle = { playlist, checked ->
            val batchTrackList = modalState.pickerBatchTracks
            if (modalState.pickerBatchCollection != null && batchTrackList != null) {
                if (checked) {
                    playlistsViewModel.addTracksToPlaylist(playlist.playlistId, batchTrackList.map { it.id })
                } else {
                    playlistsViewModel.removeTracksFromPlaylist(playlist.playlistId, batchTrackList.map { it.id })
                }
            } else {
                modalState.playlistPickerTrack?.let { track ->
                    if (checked) {
                        playlistsViewModel.addTrackToPlaylist(playlist.playlistId, track.id)
                    } else {
                        playlistsViewModel.removeTrackFromPlaylist(playlist.playlistId, track.id)
                    }
                }
            }
        },
        onInsertPositionChange = playlistsViewModel::setAddTrackInsertPosition,
        onCreatePlaylist = { name -> playlistsViewModel.createPlaylist(name) },
        onDismiss = modalState::dismissPlaylistPicker,
        collection = modalState.pickerBatchCollection,
        collectionTracks = modalState.pickerBatchTracks ?: emptyList()
    )

    TrackRadioModal(
        visible = trackRadioState.sourceTrack != null,
        sourceTrack = trackRadioState.sourceTrack,
        tracks = trackRadioState.tracks,
        isLoading = trackRadioState.isLoading,
        useAlbumArtAccent = settings.automaticColorEnabled,
        onPlay = { startIndex ->
            val radioTracks = trackRadioState.tracks
            if (radioTracks.isNotEmpty()) {
                playerViewModel.playQueueReplacingUpcoming(radioTracks, startIndex)
            }
            trackRadioViewModel.dismissRadio()
        },
        onDismiss = trackRadioViewModel::dismissRadio
    )

    LyricsTrackDetailsModal(
        visible = modalState.lyricsModalTrack != null,
        track = modalState.lyricsDisplayTrack,
        details = lyricsTrackDetails?.takeIf { it.track.id == modalState.lyricsDisplayTrack?.id },
        isLoading = isLyricsDetailsLoading,
        onDismiss = { modalState.lyricsModalTrack = null },
        onDelete = { modalState.lyricsModalTrack?.let { lyricsDetailsViewModel.deleteLyrics(it) } },
        onSearch = {
            val trackId = modalState.lyricsModalTrack?.id
            modalState.lyricsModalTrack = null
            trackModalState.dismiss()
            trackId?.let {
                navController.navigate(Screen.SettingsLyricsManagerResults.createRoute(it))
            }
        }
    )

    // Long-press collection actions; a folder/genre/year spanning several
    // artists/albums opens the matching list screen instead.
    CollectionFoamModal(
        state = collectionModalState,
        onPlayNext = { collection -> playerViewModel.playNext(collection.tracks) },
        onAddToQueue = { collection -> playerViewModel.addToQueue(collection.tracks) },
        onAddToPlaylist = { collection ->
            // Nothing to add for a track-less collection.
            if (collection.tracks.isNotEmpty()) {
                modalState.openPlaylistPickerFor(collection)
            }
        },
        onSeeArtist = onOpenArtist,
        onSeeAlbum = onOpenAlbum,
        onSeeArtistList = { sourceKind, sourceKey ->
            navController.navigate(Screen.BrowseArtistsIn.createRoute(sourceKind.name, sourceKey))
        },
        onSeeAlbumList = { sourceKind, sourceKey ->
            navController.navigate(Screen.BrowseAlbumsIn.createRoute(sourceKind.name, sourceKey))
        },
        useAlbumArtAccent = settings.automaticColorEnabled
    )
}
