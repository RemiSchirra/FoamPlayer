package com.foamplayer.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Writes playlists as .m3u8 files into a SAF-picked folder: one
 * `<playlist name>.m3u8` per playlist, entries pointing at each track's
 * stored path so players and this app's own M3U importer can read them back.
 */
@Singleton
class PlaylistExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao
) {

    /** Exports every playlist into [folderUri]; the result counts the files written. */
    suspend fun exportAll(folderUri: String): BackupResult = withContext(Dispatchers.IO) {
        val playlists = playlistDao.getAllPlaylists().first()
        if (playlists.isEmpty()) {
            return@withContext BackupResult(BackupOutcome.Error("No playlists to export"))
        }
        val trackById = trackDao.getAllTracksOnce().associateBy { it.id }
        var written = 0
        runCatching {
            playlists.forEach { playlist ->
                writePlaylist(playlist.playlistId, playlist.name, folderUri, trackById)
                written++
            }
            BackupResult(BackupOutcome.Success, fileCount = written)
        }.getOrElse { error ->
            if (written > 0) {
                BackupResult(BackupOutcome.Success, fileCount = written)
            } else {
                BackupResult(BackupOutcome.Error(error.message ?: "Export failed"))
            }
        }
    }

    /** Exports one playlist into [folderUri] as a single `<name>.m3u8` file. */
    suspend fun exportOne(playlistId: Long, folderUri: String): BackupResult = withContext(Dispatchers.IO) {
        val playlist = playlistDao.getAllPlaylists().first()
            .firstOrNull { it.playlistId == playlistId }
            ?: return@withContext BackupResult(BackupOutcome.Error("Playlist not found"))
        val trackById = trackDao.getAllTracksOnce().associateBy { it.id }
        runCatching {
            writePlaylist(playlistId, playlist.name, folderUri, trackById)
            BackupResult(BackupOutcome.Success, fileCount = 1)
        }.getOrElse { error ->
            BackupResult(BackupOutcome.Error(error.message ?: "Export failed"))
        }
    }

    private suspend fun writePlaylist(
        playlistId: Long,
        playlistName: String,
        folderUri: String,
        trackById: Map<Long, TrackEntity>
    ) {
        val trackIds = playlistDao.getTrackIdsForPlaylistOnce(playlistId)
        writeM3u8(
            folderUri.toUri(),
            m3u8FileName(playlistName),
            buildM3u8(trackIds.mapNotNull(trackById::get))
        )
    }

    private fun buildM3u8(tracks: List<TrackEntity>): String = buildString {
        appendLine("#EXTM3U")
        tracks.forEach { track ->
            appendLine("#EXTINF:${track.duration / 1000},${track.artist} - ${track.title}")
            appendLine(track.path)
        }
    }

    // Same SAF write pattern as SettingsBackupManager.writeDocument, with an M3U8 mime type.
    private fun writeM3u8(treeUri: Uri, fileName: String, content: String) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val created = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocumentUri,
                "audio/x-mpegurl",
                fileName
            )
        }.getOrNull()
        val documentUri = created ?: parentDocumentUri
        context.contentResolver.openOutputStream(documentUri, "wt")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    /** Filesystem-safe `<name>.m3u8`; reserved characters become underscores. */
    private fun m3u8FileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "playlist" } + ".m3u8"
}
