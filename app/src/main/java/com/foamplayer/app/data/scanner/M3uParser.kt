package com.foamplayer.app.data.scanner

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/** One file entry of an M3U playlist, with its preceding #EXTINF metadata. */
data class M3uEntry(
    val path: String,
    val title: String? = null,
    val artist: String? = null
)

/** Parsed content of an M3U file. */
data class M3uPlaylist(
    val name: String?,
    val entries: List<M3uEntry>
)

/** Reads and parses M3U/M3U8 playlists (#EXTINF metadata, #PLAYLIST name). */
@Singleton
class M3uParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Reads the playlist text through SAF; strips a UTF-8 BOM. */
    suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                    .readText()
                    .removePrefix("\uFEFF")
            }
        }.getOrNull()
    }

    fun parse(text: String): M3uPlaylist {
        val entries = mutableListOf<M3uEntry>()
        var playlistName: String? = null
        var title: String? = null
        var artist: String? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:") -> {
                    val display = line.substringAfter(':').substringAfter(',', "")
                    val dash = display.indexOf(" - ")
                    if (dash > 0) {
                        artist = display.substring(0, dash).trim().takeIf { it.isNotEmpty() }
                        title = display.substring(dash + 3).trim().takeIf { it.isNotEmpty() }
                    } else {
                        artist = null
                        title = display.trim().takeIf { it.isNotEmpty() }
                    }
                }
                line.startsWith("#PLAYLIST:") -> {
                    playlistName = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("#") -> Unit
                isRemoteStream(line) -> {
                    // Remote streams are not library tracks; skip with no metadata.
                    title = null
                    artist = null
                }
                else -> {
                    entries += M3uEntry(line, title, artist)
                    title = null
                    artist = null
                }
            }
        }
        return M3uPlaylist(playlistName, entries)
    }

    private fun isRemoteStream(line: String): Boolean =
        line.startsWith("http://", ignoreCase = true) ||
                line.startsWith("https://", ignoreCase = true) ||
                line.startsWith("rtsp://", ignoreCase = true) ||
                line.startsWith("rtmp://", ignoreCase = true)
}
