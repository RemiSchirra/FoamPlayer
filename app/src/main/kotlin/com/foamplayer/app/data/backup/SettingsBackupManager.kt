package com.foamplayer.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.foamplayer.app.data.db.dao.ListenHistoryDao
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.ListenHistoryEntity
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.scanner.PlaylistCoverStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Report returned by [SettingsBackupManager.export]/[importFeatures]. */
data class BackupResult(
    val outcome: BackupOutcome,
    /** Files written (export) or read (import). */
    val fileCount: Int = 0,
    /** Per-feature summary shown in the confirmation sheet (null on export). */
    val details: String? = null
) {
    val isSuccess: Boolean get() = outcome is BackupOutcome.Success
    val errorMessage: String? get() = (outcome as? BackupOutcome.Error)?.message
}

/**
 * Exports / imports the user's data as JSON through SAF, always as ONE
 * `foamplayer_<date>_<time>.bak` file carrying a "version" plus one member
 * per selected feature.
 */
@Singleton
class SettingsBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val listenHistoryDao: ListenHistoryDao,
    private val coverStore: PlaylistCoverStore
) {

    // ------------------------------------------------------------------ export

    suspend fun export(
        folderUri: String,
        features: Set<BackupFeature>
    ): BackupResult = withContext(Dispatchers.IO) {
        if (features.isEmpty()) {
            return@withContext BackupResult(BackupOutcome.Error("No features selected"))
        }
        runCatching {
            val payload = buildExportPayload(features)
            writeDocument(folderUri.toUri(), backupFileName(), payload.toString(2))
            BackupResult(BackupOutcome.Success, fileCount = 1)
        }.getOrElse { error ->
            BackupResult(BackupOutcome.Error(error.message ?: "Export failed"))
        }
    }

    /** One JSON object per feature selection: `{"version":1,"<feature>":{...}}`. */
    private suspend fun buildExportPayload(features: Set<BackupFeature>): JSONObject {
        val payload = JSONObject()
        payload.put("version", BackupJson.FORMAT_VERSION)
        val settings = settingsRepository.settings.first()
        features.forEach { feature ->
            when (feature) {
                BackupFeature.APP_SETTINGS ->
                    payload.put(feature.key, BackupJson.appSettingsToJson(settings))
                BackupFeature.MUSIC_FOLDERS ->
                    payload.put(feature.key, JSONArray(settings.libraryFolders.toList()))
                BackupFeature.EQUALIZER_PRESETS ->
                    payload.put(feature.key, BackupJson.presetsToJson(settings.dspUserPresets))
                BackupFeature.PLAYLISTS ->
                    payload.put(feature.key, playlistsToJson())
                BackupFeature.SONG_STATISTICS ->
                    payload.put(feature.key, songStatsToJson())
                BackupFeature.SEARCH_HISTORY ->
                    payload.put(
                        feature.key,
                        BackupJson.searchHistoryToJson(settings.searchHistory)
                    )
            }
        }
        return payload
    }

    private suspend fun playlistsToJson(): JSONArray {
        val playlists = playlistDao.getAllPlaylists().first()
        val trackById = trackDao.getAllTracksOnce().associateBy { it.id }
        val array = JSONArray()
        playlists.forEach { playlist ->
            val tracks = JSONArray()
            playlistDao.getTrackIdsForPlaylistOnce(playlist.playlistId).forEach { trackId ->
                val track = trackById[trackId] ?: return@forEach
                tracks.put(
                    JSONObject().apply {
                        put("title", track.title)
                        put("album", track.album)
                        put("artist", track.artist)
                    }
                )
            }
            array.put(
                JSONObject().apply {
                    put("name", playlist.name)
                    playlist.imageUrl?.let { ref ->
                        put("imageUrl", ref)
                        if (!ref.contains("://")) {
                            coverStore.readCoverBytes(ref)?.let { bytes ->
                                put("coverData", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            }
                        }
                    }
                    put("sortOrder", playlist.sortOrder)
                    put("tracks", tracks)
                }
            )
        }
        return array
    }

    /**
     * Per-song stats export: the aggregate columns of the tracks table plus
     * every listen timestamp, so the stats screen's period analysis (built on
     * the listen_history table) can be fully restored.
     */
    private suspend fun songStatsToJson(): JSONArray {
        val stats = trackDao.getPlayStats()
        val trackByPath = trackDao.getAllTracksOnce().associateBy { it.path }
        // Group each track's listen rows by its path for the JSON entries.
        val listensByTrackId = listenHistoryDao.getAllListens()
            .groupBy { it.trackId }
        val array = JSONArray()
        stats.forEach { row ->
            val track = trackByPath[row.path]
            array.put(
                JSONObject().apply {
                    put("path", row.path)
                    if (track != null) {
                        put("title", track.title)
                        put("album", track.album)
                        put("artist", track.artist)
                    }
                    put("playCount", row.playCount)
                    row.lastPlayedAt?.let { put("lastPlayedAt", it) }
                    put("score", row.score)
                    track?.id?.let { trackId ->
                        val listens = JSONArray()
                        listensByTrackId[trackId]?.forEach { listen ->
                            listens.put(listen.listenedAt)
                        }
                        if (listens.length() > 0) put("listens", listens)
                    }
                }
            )
        }
        return array
    }

    // ------------------------------------------------------------------ import

    /**
     * Which features the given backup file actually contains — the import
     * sheet disables the rest. Null when the file can't be read or parsed.
     */
    suspend fun detectFeatures(fileUri: String): Set<BackupFeature>? =
        withContext(Dispatchers.IO) {
            val text = readDocument(fileUri.toUri()) ?: return@withContext null
            runCatching {
                val payload = JSONObject(text)
                BackupFeature.entries.filter { payload.has(it.key) }.toSet()
            }.getOrNull()
        }

    suspend fun importFeatures(fileUris: List<String>, features: Set<BackupFeature>): BackupResult =
        withContext(Dispatchers.IO) {
            if (features.isEmpty() || fileUris.isEmpty()) {
                return@withContext BackupResult(BackupOutcome.Error("Nothing selected"))
            }
            runCatching {
                var appliedFiles = 0
                var statsApplied = 0
                var statsSkipped = 0
                fileUris.forEach { fileUri ->
                    val text = readDocument(fileUri.toUri()) ?: return@forEach
                    val payload = JSONObject(text)
                    if (BackupFeature.SONG_STATISTICS in features &&
                        payload.has(BackupFeature.SONG_STATISTICS.key)
                    ) {
                        // Matched / skipped entry counts for the confirmation sheet.
                        val before = statsApplied
                        importSongStats(payload.optJSONArray(BackupFeature.SONG_STATISTICS.key)) {
                            matched, skipped ->
                            statsApplied += matched
                            statsSkipped += skipped
                        }
                        if (statsApplied > before) appliedFiles++
                    }
                    val rest = features - BackupFeature.SONG_STATISTICS
                    val restPayload = JSONObject(payload.toString()).apply {
                        remove(BackupFeature.SONG_STATISTICS.key)
                    }
                    if (importPayload(restPayload, rest)) appliedFiles++
                }
                if (appliedFiles == 0) {
                    BackupResult(BackupOutcome.Error("No importable data found"))
                } else {
                    val details = if (BackupFeature.SONG_STATISTICS in features) {
                        "Stats: $statsApplied restored, $statsSkipped not found in library"
                    } else null
                    BackupResult(BackupOutcome.Success, fileCount = appliedFiles, details = details)
                }
            }.getOrElse { error ->
                BackupResult(BackupOutcome.Error(error.message ?: "Import failed"))
            }
        }

    /** Applies every selected feature present in [payload]; true when at least one was. */
    private suspend fun importPayload(payload: JSONObject, features: Set<BackupFeature>): Boolean {
        var applied = false
        features.forEach { feature ->
            if (!payload.has(feature.key)) return@forEach
            when (feature) {
                BackupFeature.APP_SETTINGS ->
                    payload.optJSONObject(feature.key)?.let { json ->
                        settingsRepository.update { current ->
                            BackupJson.appSettingsFromJson(json).copy(
                                libraryFolders = current.libraryFolders,
                                // Onboarding state is machine-local: never overwrite
                                // it with the decoded default (false), or the
                                // welcome flow re-appears after every import.
                                onboardingCompleted = current.onboardingCompleted
                            )
                        }
                        applied = true
                    }
                BackupFeature.MUSIC_FOLDERS -> {
                    val folders = stringList(payload.optJSONArray(feature.key))
                    if (folders.isNotEmpty()) {
                        settingsRepository.update { current ->
                            current.copy(libraryFolders = current.libraryFolders + folders)
                        }
                        applied = true
                    }
                }
                BackupFeature.EQUALIZER_PRESETS -> {
                    val incoming = BackupJson.presetsFromJson(payload.optJSONArray(feature.key))
                    if (incoming.isNotEmpty()) {
                        settingsRepository.update { current ->
                            current.copy(dspUserPresets = incoming)
                        }
                        applied = true
                    }
                }
                BackupFeature.PLAYLISTS -> {
                    payload.optJSONArray(feature.key)?.let { array ->
                        importPlaylists(array)
                        applied = true
                    }
                }
                BackupFeature.SEARCH_HISTORY -> {
                    val incoming = BackupJson.searchHistoryFromJsonTopLevel(
                        payload.optJSONArray(feature.key)
                    )
                    if (incoming.isNotEmpty()) {
                        settingsRepository.update { current ->
                            current.copy(searchHistory = incoming)
                        }
                        applied = true
                    }
                }
                // SONG_STATISTICS is imported separately (with match reporting).
                BackupFeature.SONG_STATISTICS -> Unit
            }
        }
        return applied
    }

    /**
     * Playlists are merged by name: an existing playlist with the same name
     * keeps its identity (its tracks are replaced by the imported order),
     * otherwise a new playlist is created. Tracks are resolved against the
     * current library by title/album (falling back to title only); entries
     * that match nothing are skipped.
     */
    private suspend fun importPlaylists(array: JSONArray) {
        val existing = playlistDao.getAllPlaylists().first()
        val byName = existing.associateBy { it.name.trim().lowercase() }
        val trackIndex = TrackTitleIndex(trackDao.getAllTracksOnce())
        (0 until array.length()).forEach { i ->
            val entry = array.optJSONObject(i) ?: return@forEach
            val name = entry.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@forEach
            val trackIds = trackIdsFromJson(entry.optJSONArray("tracks"), trackIndex)
            val existingPlaylist = byName[name.lowercase()]
            val playlistId = existingPlaylist?.playlistId
                ?: playlistDao.createPlaylist(
                    PlaylistEntity(
                        name = name,
                        imageUrl = restoredCoverRef(entry),
                        sortOrder = entry.optInt("sortOrder", playlistDao.nextPlaylistSortOrder())
                    )
                )
            playlistDao.clearPlaylistTracks(playlistId)
            trackIds.forEachIndexed { index, trackId ->
                playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId, index))
            }
        }
    }

    /**
     * Cover ref for a newly created (import-side) playlist: embedded
     * "coverData" wins — materialized into the cover store — so the restored
     * cover survives even though the original file was wiped with app data.
     * Without it, a path-based ref from another device points at a file that
     * does not exist here and is dropped (the playlist falls back to first-
     * track art), while an external document URI is kept as-is: its grant may
     * still be alive on THIS device, and [migrateLegacyUriCovers] will
     * privatize it at the next launch if so.
     */
    private suspend fun restoredCoverRef(entry: JSONObject): String? {
        val coverData = entry.optString("coverData").takeIf { it.isNotEmpty() }
        if (coverData != null) {
            val bytes = runCatching {
                Base64.decode(coverData, Base64.NO_WRAP)
            }.getOrNull()
            if (bytes != null) {
                coverStore.writeCoverBytes(bytes)?.let { return it }
            }
        }
        val imageRef = entry.optString("imageUrl").takeIf { it.isNotEmpty() } ?: return null
        return imageRef.takeIf { it.contains("://") }
    }

    /**
     * Stats are identified by title/album (falling back to title only, then to
     * the stored path) and only applied to tracks present in the library. The
     * tracks-table aggregate columns are set directly and the listen history
     * is rebuilt from the exported timestamps, so period-based stats screens
     * (top tracks, daily charts…) come back too.
     *
     * Backups written before listens were exported carry no "listens" array:
     * for those the aggregate columns are still restored, and [onResult]
     * reports how many entries could not be matched at all.
     */
    private suspend fun importSongStats(array: JSONArray, onResult: (matched: Int, skipped: Int) -> Unit) {
        val trackIndex = TrackTitleIndex(trackDao.getAllTracksOnce())
        val listensToInsert = mutableListOf<ListenHistoryEntity>()
        var matched = 0
        var skipped = 0
        (0 until array.length()).forEach { i ->
            val entry = array.optJSONObject(i) ?: return@forEach
            val track = trackIndex.match(
                title = entry.optString("title"),
                album = entry.optString("album"),
                path = entry.optString("path").takeIf { it.isNotEmpty() }
            )
            if (track == null) {
                skipped++
                return@forEach
            }
            matched++
            trackDao.importPlayStats(
                trackId = track.id,
                playCount = entry.optInt("playCount", 0).coerceAtLeast(0),
                lastPlayedAt = if (entry.has("lastPlayedAt")) entry.optLong("lastPlayedAt") else null,
                score = entry.optInt("score", 0).coerceIn(-1, 2)
            )
            // One history row per exported listen timestamp.
            val listens = entry.optJSONArray("listens")
            if (listens != null) {
                (0 until listens.length()).forEach { j ->
                    val timestamp = listens.optLong(j)
                    if (timestamp > 0) {
                        listensToInsert += ListenHistoryEntity(
                            trackId = track.id,
                            listenedAt = timestamp
                        )
                    }
                }
            }
        }
        if (listensToInsert.isNotEmpty()) {
            listenHistoryDao.insertListens(listensToInsert)
        }
        onResult(matched, skipped)
    }

    // ------------------------------------------------------------------ SAF io

    private fun writeDocument(treeUri: Uri, fileName: String, content: String) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        // Create (or let the system reuse) the file, then overwrite through its URI.
        val created = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocumentUri,
                "application/json",
                fileName
            )
        }.getOrNull()
        val documentUri = created ?: parentDocumentUri
        context.contentResolver.openOutputStream(documentUri, "wt")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun readDocument(uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()

    // ----------------------------------------------------------------- helpers

    /** `foamplayer_2026-09-23_14-05-07.bak` — local date + time to the second. */
    private fun backupFileName(): String {
        val now = java.time.LocalDateTime.now()
        val stamp = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss")
            .format(now)
        return "foamplayer_$stamp.bak"
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotEmpty() }
        }
    }

    private fun trackIdsFromJson(array: JSONArray?, index: TrackTitleIndex): List<Long> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            index.match(entry.optString("title"), entry.optString("album"))?.id
        }
    }
}

/**
 * Resolves backup track entries against the current library: exact
 * (title, album) match first, then title-only. Comparisons are lowercase and
 * whitespace-trimmed. A stored path (legacy backups / local files) is used as
 * a last-resort key.
 */
private class TrackTitleIndex(tracks: List<TrackEntity>) {
    private val byTitleAlbum = HashMap<String, TrackEntity>()
    private val byTitle = HashMap<String, TrackEntity>()
    private val byPath = HashMap<String, TrackEntity>()

    init {
        tracks.forEach { track ->
            val title = normalize(track.title)
            if (title.isNotEmpty()) {
                byTitleAlbum.putIfAbsent("$title\n${normalize(track.album)}", track)
                byTitle.putIfAbsent(title, track)
            }
            byPath.putIfAbsent(normalize(track.path), track)
        }
    }

    fun match(title: String, album: String, path: String? = null): TrackEntity? {
        val normalizedTitle = normalize(title)
        byTitleAlbum["$normalizedTitle\n${normalize(album)}"]?.let { return it }
        if (normalizedTitle.isNotEmpty()) {
            byTitle[normalizedTitle]?.let { return it }
        }
        return path?.let { byPath[normalize(it)] }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
