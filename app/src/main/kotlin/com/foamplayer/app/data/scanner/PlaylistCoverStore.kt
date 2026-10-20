package com.foamplayer.app.data.scanner

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.foamplayer.app.data.db.dao.PlaylistDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Owns the app-private copies of user-picked playlist cover images
 * (`filesDir/playlist_covers/`), stored as plain absolute paths in
 * [com.foamplayer.app.data.db.entities.PlaylistEntity.imageUrl].
 *
 * Why a private copy instead of the picked document URI: a persisted
 * `content://` grant dies with the app's data — clearing app data (or a
 * restore on another device) revokes it and wipes [android.content.Context.getFilesDir],
 * so a backup that only references the URI comes back coverless. Copying the
 * bytes in at pick time makes the cover self-contained, and embedding those
 * bytes (base64) in the backup file lets [com.foamplayer.app.data.backup.SettingsBackupManager]
 * re-materialize them after a clear. Image rendering is content-sniffed by
 * Coil, so the stored file's extension is cosmetic only.
 */
@Singleton
class PlaylistCoverStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao
) {

    /** Directory the app-private cover copies live in. */
    val coversDir: File get() = File(context.filesDir, COVERS_DIR_NAME)

    /**
     * Copies the picked image's bytes into [coversDir] and returns the stored
     * absolute path, or null when the copy failed (unreadable source, empty
     * result). The caller decides what a null means: keep the current cover
     * on a replace, or create the playlist without one.
     */
    suspend fun importPickedImage(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        coversDir.mkdirs()
        val destination = File(coversDir, "cover_${UUID.randomUUID()}.${extensionFor(sourceUri)}")
        val storedPath = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            destination.takeIf { it.length() > 0 }?.absolutePath
        }.getOrNull()
        // A partial file must not linger: it would be picked up as a valid
        // cover and exported as truncated bytes.
        if (storedPath == null) runCatching { destination.delete() }
        storedPath
    }

    /**
     * The bytes of a stored cover, for backup embedding. Returns null when
     * the file is gone (stale path) or too large to embed — the backup then
     * carries only the reference, and a restored playlist degrades to the
     * embedded-art/letter fallback instead of ballooning the .bak file.
     */
    suspend fun readCoverBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_EMBEDDED_BYTES) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Materializes cover bytes (from a decoded backup entry) into a fresh
     * file in [coversDir]; returns the stored path, or null when writing
     * failed (then the import falls back to the plain URI reference).
     */
    suspend fun writeCoverBytes(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        coversDir.mkdirs()
        val destination = File(coversDir, "cover_${UUID.randomUUID()}.$DEFAULT_EXTENSION")
        val storedPath = runCatching {
            destination.writeBytes(bytes)
            destination.takeIf { it.length() > 0 }?.absolutePath
        }.getOrNull()
        if (storedPath == null) runCatching { destination.delete() }
        storedPath
    }

    /**
     * Deletes a cover file — but only when [path] points inside [coversDir],
     * so stale external URIs or foreign paths can never delete anything the
     * store doesn't own. Best-effort: a failed delete just leaves an orphan.
     */
    suspend fun deleteIfOwned(path: String?) {
        if (path.isNullOrEmpty()) return
        withContext(Dispatchers.IO) {
            val owned = runCatching {
                val dir = coversDir.canonicalPath + File.separator
                File(path).canonicalPath.startsWith(dir)
            }.getOrDefault(false)
            if (owned) {
                val file = File(path)
                if (file.isFile) runCatching { file.delete() }
            }
        }
    }

    /**
     * Best stored representation of an image reference: scheme-carrying
     * refs (a just-picked document URI) are copied into [coversDir] and the
     * private copy's path returned; plain paths pass through unchanged (they
     * are already store-owned or legacy local paths). When the copy fails the
     * original ref is returned, preserving the pre-store behavior for that
     * pick instead of silently dropping the user's choice.
     */
    suspend fun resolveStoredRef(imageRef: String): String =
        if (imageRef.contains("://")) {
            runCatching { Uri.parse(imageRef) }.getOrNull()
                ?.let { importPickedImage(it) } ?: imageRef
        } else imageRef

    /**
     * One-time-per-launch rescue of pre-store covers that still reference
     * picked document URIs: while their persisted grant is still alive the
     * bytes are copied into [coversDir] and the playlist row re-pointed at
     * the private copy — so a later app-data clear can still restore them
     * from a backup. Unreadable sources (revoked grant, deleted file) are
     * skipped and retried on the next launch; a no-op failure costs nothing.
     */
    suspend fun migrateLegacyUriCovers() = withContext(Dispatchers.IO) {
        val playlists = runCatching { playlistDao.getAllPlaylists().first() }.getOrDefault(emptyList())
        playlists.forEach { playlist ->
            val image = playlist.imageUrl?.takeIf { it.isNotBlank() } ?: return@forEach
            // App-private paths are already safe; only scheme-carrying
            // references (picked document URIs) need rescuing.
            if (!image.contains("://")) return@forEach
            val source = runCatching { Uri.parse(image) }.getOrNull() ?: return@forEach
            val copied = importPickedImage(source) ?: return@forEach
            runCatching { playlistDao.setPlaylistImage(playlist.playlistId, copied) }
        }
    }

    /** File extension for the picked image, from its reported MIME type. */
    private fun extensionFor(sourceUri: Uri): String =
        runCatching {
            context.contentResolver.getType(sourceUri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: DEFAULT_EXTENSION

    companion object {
        private const val COVERS_DIR_NAME = "playlist_covers"
        private const val DEFAULT_EXTENSION = "img"

        /** Embedding guard so one oversized pick can't balloon the backup file. */
        private const val MAX_EMBEDDED_BYTES = 10L * 1024 * 1024
    }
}
