package com.foamplayer.app.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.foamplayer.app.data.repository.TrackMetadataEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes user-edited metadata back into the audio files themselves, so the
 * tags survive a library rescan (the scanner re-reads the files, so a
 * database-only edit would be reverted).
 *
 * Tag editors need random access, which content streams don't offer: the
 * document is copied to a cache file first, edited with jaudiotagger there,
 * and streamed back over the original document. [writeTags]' format argument
 * (the track's stored extension) names the temp file so jaudiotagger picks
 * the right reader.
 */
@Singleton
class AudioTagWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Best-effort: writes [edit]'s fields into the file at [uri] and reports
     * whether the write landed. A failure (unsupported container, read-only
     * document, no tag support…) never throws — the caller still updates the
     * database.
     */
    fun writeTags(uri: Uri, format: String, edit: TrackMetadataEdit): Boolean {
        val extension = format.filter { it.isLetterOrDigit() }.lowercase()
        val temp = File.createTempFile("foamtags", if (extension.isEmpty()) "" else ".$extension", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return false

            val audioFile = AudioFileIO.read(temp)
            audioFile.getTagOrCreateAndSetDefault().apply {
                setOrDelete(FieldKey.TITLE, edit.title)
                setOrDelete(FieldKey.ARTIST, edit.artist)
                setOrDelete(FieldKey.ALBUM, edit.album)
                setOrDelete(FieldKey.ALBUM_ARTIST, edit.albumArtist)
                setOrDelete(FieldKey.GENRE, edit.genre.ifEmpty { null })
                setOrDelete(FieldKey.COMPOSER, edit.composer)
                setOrDelete(FieldKey.YEAR, edit.year?.toString())
                setOrDelete(FieldKey.TRACK, edit.trackNumber?.toString())
                setOrDelete(FieldKey.DISC_NO, edit.discNumber?.toString())
            }
            audioFile.commit()

            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                temp.inputStream().use { it.copyTo(output) }
            } ?: return false
            return true
        } catch (e: Exception) {
            Log.w(TAG, "writeTags: could not edit $uri", e)
            return false
        } finally {
            temp.delete()
        }
    }

    private companion object {
        private const val TAG = "FoamAudioTagWriter"
    }
}

/** [Tag.setField] replaces the field's values, so an empty value clears it. */
private fun Tag.setOrDelete(field: FieldKey, value: String?) {
    if (value.isNullOrEmpty()) {
        if (hasField(field)) deleteField(field)
    } else {
        setField(field, value)
    }
}
