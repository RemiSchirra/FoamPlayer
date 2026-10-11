package com.foamplayer.app.data.lyrics

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes lyrics into an audio file's embedded tag, in the exact places
 * [com.foamplayer.app.lyrics.providers.EmbeddedLyricsProvider] reads back:
 *  - MP3:  ID3v2.3 / ID3v2.4 USLT frame (existing USLT frames are replaced),
 *  - FLAC: Vorbis comment block, LYRICS=... entry.
 * Other containers (m4a, ogg/opus, wav) are rejected with a descriptive error:
 * rewriting them in place is not safe without a full remux.
 *
 * [removeEmbeddedLyrics] reverses the operation: MP3 tags are rebuilt without
 * their USLT frames (dropped entirely when nothing else remains) and FLAC
 * Vorbis comments without their LYRICS entries.
 *
 * Every rewrite is streaming and non-destructive: the new tag region is written
 * to a temp file, the untouched audio payload is appended, and only then is the
 * original replaced — a failure anywhere leaves the source file intact.
 */
object AudioLyricsTagWriter {

    private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac")

    fun canEmbed(trackPath: String): Boolean = extensionOf(trackPath) in SUPPORTED_EXTENSIONS

    fun embed(context: Context, trackPath: String, lyrics: String): Result<Unit> = runCatching {
        val extension = extensionOf(trackPath)
        if (extension !in SUPPORTED_EXTENSIONS) {
            error("Embedding lyrics is not supported for .$extension files — save as a lyrics file instead")
        }
        val plan = when (extension) {
            "flac" -> planFlacRewrite(context, trackPath) { rebuildVorbisComment(it, lyrics) }
            else -> planMp3Rewrite(context, trackPath) { tag ->
                if (tag == null) {
                    // No existing ID3v2 tag: create a fresh v2.4 one.
                    val newFrame = buildUsltFrame(lyrics, 4)
                    buildId3Header(4, newFrame.size) + newFrame
                } else {
                    buildTagWithLyrics(tag, lyrics)
                }
            }
        }
        applyRewrite(context, trackPath, plan)
    }

    /**
     * Removes embedded lyrics from the file. Returns whether the file was
     * modified; unsupported formats and files without any embedded lyrics are
     * reported as "not modified" (false), never as errors.
     */
    fun removeEmbeddedLyrics(context: Context, trackPath: String): Result<Boolean> = runCatching {
        val extension = extensionOf(trackPath)
        if (extension !in SUPPORTED_EXTENSIONS) return@runCatching false

        when (extension) {
            "flac" -> removeFlacLyrics(context, trackPath)
            else -> removeMp3Lyrics(context, trackPath)
        }
    }

    // -------------------------------------------------------------- rewriting

    private class RewritePlan(val newPrefix: ByteArray, val audioDataOffset: Long)

    private fun extensionOf(trackPath: String): String =
        Uri.parse(trackPath).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

    private fun resolveFile(trackPath: String): File? {
        val uri = Uri.parse(trackPath)
        return when (uri.scheme) {
            null, "", "file" -> File(uri.path ?: trackPath)
            else -> null
        }
    }

    private fun openSource(context: Context, trackPath: String): InputStream {
        val file = resolveFile(trackPath)
        return file?.inputStream()
            ?: context.contentResolver.openInputStream(Uri.parse(trackPath))
            ?: throw IOException("Could not open audio file for reading")
    }

    private fun applyRewrite(context: Context, trackPath: String, plan: RewritePlan) {
        val temp = File.createTempFile("foam_lyrics", ".tmp", context.cacheDir)
        try {
            temp.outputStream().use { output ->
                output.write(plan.newPrefix)
                openSource(context, trackPath).use { input ->
                    skipFully(input, plan.audioDataOffset)
                    input.copyTo(output, 64 * 1024)
                }
            }

            val file = resolveFile(trackPath)
            if (file != null) {
                if (file.exists() && !file.delete()) throw IOException("Could not replace audio file")
                temp.inputStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                context.contentResolver.openOutputStream(Uri.parse(trackPath), "wt")?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not open audio file for writing")
            }
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------------ FLAC

    private const val FLAC_BLOCK_VORBIS_COMMENT = 4

    private class FlacStructure(
        val blocks: List<Pair<Int, ByteArray>>,
        val vorbisIndex: Int,
        val audioOffset: Long
    )

    private fun parseFlac(input: InputStream): FlacStructure {
        val magic = readNBytesCompat(input, 4)
        if (String(magic, Charsets.US_ASCII) != "fLaC") throw IOException("Not a valid FLAC file")

        var audioOffset = 4L
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var vorbisIndex = -1

        var last = false
        while (!last) {
            val header = readNBytesCompat(input, 4)
            if (header.size < 4) throw IOException("Truncated FLAC metadata")
            last = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val size = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
            val body = readNBytesCompat(input, size)
            if (body.size < size) throw IOException("Truncated FLAC metadata")
            audioOffset += 4L + size

            if (type == FLAC_BLOCK_VORBIS_COMMENT) vorbisIndex = blocks.size
            blocks.add(type to body)
        }
        return FlacStructure(blocks, vorbisIndex, audioOffset)
    }

    private fun buildFlacPrefix(structure: FlacStructure, newVorbisBody: ByteArray?): ByteArray {
        val prefix = ByteArrayOutputStream()
        prefix.write("fLaC".toByteArray(Charsets.US_ASCII))
        structure.blocks.forEachIndexed { index, (type, body) ->
            val isVorbis = index == structure.vorbisIndex
            val effectiveBody = if (isVorbis && newVorbisBody != null) newVorbisBody else body
            val isLast = index == structure.blocks.lastIndex
            prefix.write(((if (isLast) 0x80 else 0) or type) and 0xFF)
            val size = effectiveBody.size
            if (size >= 1 shl 24) throw IOException("Lyrics too large for FLAC metadata block")
            prefix.write((size shr 16) and 0xFF)
            prefix.write((size shr 8) and 0xFF)
            prefix.write(size and 0xFF)
            prefix.write(effectiveBody)
        }
        return prefix.toByteArray()
    }

    private fun planFlacRewrite(
        context: Context,
        trackPath: String,
        buildVorbis: (ByteArray?) -> ByteArray
    ): RewritePlan {
        val structure = openSource(context, trackPath).use(::parseFlac)
        val newVorbisBody = buildVorbis(structure.blocks.getOrNull(structure.vorbisIndex)?.second)
        return RewritePlan(buildFlacPrefix(structure, newVorbisBody), structure.audioOffset)
    }

    private fun removeFlacLyrics(context: Context, trackPath: String): Boolean {
        val structure = openSource(context, trackPath).use(::parseFlac)
        val vorbisBody = structure.blocks.getOrNull(structure.vorbisIndex)?.second ?: return false
        val (newBody, removed) = stripLyricsFromVorbisComment(vorbisBody)
        if (!removed) return false
        applyRewrite(context, trackPath, RewritePlan(buildFlacPrefix(structure, newBody), structure.audioOffset))
        return true
    }

    private fun rebuildVorbisComment(existing: ByteArray?, lyrics: String): ByteArray {
        val (vendor, comments) = parseVorbisComments(existing)
        val kept = comments.filter { comment ->
            comment.substringBefore('=').uppercase() !in LYRICS_COMMENT_KEYS
        } + "LYRICS=$lyrics"
        return serializeVorbisComments(vendor, kept)
    }

    private fun stripLyricsFromVorbisComment(existing: ByteArray): Pair<ByteArray, Boolean> {
        val (vendor, comments) = parseVorbisComments(existing)
        val kept = comments.filter { comment ->
            comment.substringBefore('=').uppercase() !in LYRICS_COMMENT_KEYS
        }
        if (kept.size == comments.size) return existing to false
        return serializeVorbisComments(vendor, kept) to true
    }

    private val LYRICS_COMMENT_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "LYRICS-XXX")

    /** (vendor, comments) — throws on structurally broken blocks. */
    private fun parseVorbisComments(existing: ByteArray?): Pair<ByteArray, List<String>> {
        if (existing == null) return "FoamPlayer".toByteArray(Charsets.UTF_8) to emptyList()

        val buffer = ByteBuffer.wrap(existing).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 4) throw IOException("Corrupted FLAC Vorbis comment block")
        val vendorLength = buffer.int
        if (vendorLength < 0 || vendorLength > buffer.remaining()) throw IOException("Corrupted FLAC Vorbis comment block")
        val vendor = ByteArray(vendorLength).also { buffer.get(it) }
        if (buffer.remaining() < 4) throw IOException("Corrupted FLAC Vorbis comment block")
        val count = buffer.int
        val comments = mutableListOf<String>()
        repeat(count.coerceAtLeast(0)) {
            if (buffer.remaining() < 4) throw IOException("Corrupted FLAC Vorbis comment block")
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) throw IOException("Corrupted FLAC Vorbis comment block")
            comments.add(String(ByteArray(length).also { buffer.get(it) }, Charsets.UTF_8))
        }
        return vendor to comments
    }

    private fun serializeVorbisComments(vendor: ByteArray, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val le = { value: Int -> out.write(byteArrayOf(
            (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte()
        )) }
        le(vendor.size)
        out.write(vendor)
        le(comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(Charsets.UTF_8)
            le(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------------- MP3

    private const val ID3_USLT = "USLT"

    // Compiled once: this loop walks every frame of the tag (large MP3 files can
    // carry hundreds), so a per-frame Regex() compile was pure overhead.
    private val FRAME_ID_REGEX = Regex("[A-Z0-9]{4}")

    private class Id3TagInfo(
        val major: Int,
        val tagSize: Int,
        val frames: ByteArray,
        val audioOffset: Long
    )

    /** null when the file carries no ID3v2 tag (audio starts at offset 0). */
    private fun parseId3(input: InputStream): Id3TagInfo? {
        val header = readNBytesCompat(input, 10)
        if (header.size < 3 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major !in 3..4) throw IOException("Unsupported ID3v2.$major tag")
        val flags = header[5].toInt() and 0xFF
        if (flags and 0x80 != 0) throw IOException("ID3 tag uses unsynchronisation")
        if (flags and 0x40 != 0) throw IOException("ID3 tag uses an extended header")
        if (flags and 0x10 != 0) throw IOException("ID3 tag uses a footer")
        val tagSize = syncSafeInt(header, 6)
        val frames = readNBytesCompat(input, tagSize)
        if (frames.size < tagSize) throw IOException("Truncated ID3 tag")
        return Id3TagInfo(major, tagSize, frames, 10L + tagSize)
    }

    private class FrameExtraction(val kept: ByteArray, val usltCount: Int)

    private fun extractNonUsltFrames(frames: ByteArray, major: Int): FrameExtraction {
        val out = ByteArrayOutputStream()
        var usltCount = 0
        var pos = 0
        while (pos + 10 <= frames.size) {
            if (frames[pos] == 0.toByte()) break // padding
            val id = String(frames, pos, 4, Charsets.US_ASCII)
            if (!FRAME_ID_REGEX.matches(id)) break
            val size = if (major >= 4) syncSafeInt(frames, pos + 4) else beInt(frames, pos + 4)
            if (size <= 0 || pos + 10 + size > frames.size) break
            if (id == ID3_USLT) {
                usltCount++
            } else {
                out.write(frames, pos, 10 + size)
            }
            pos += 10 + size
        }
        return FrameExtraction(out.toByteArray(), usltCount)
    }

    private fun buildTagWithLyrics(frames: Id3TagInfo, lyrics: String): ByteArray {
        val keptFrames = extractNonUsltFrames(frames.frames, frames.major).kept
        val newFrame = buildUsltFrame(lyrics, frames.major)
        val tagBody = keptFrames + newFrame
        return buildId3Header(frames.major, tagBody.size) + tagBody
    }

    private fun planMp3Rewrite(
        context: Context,
        trackPath: String,
        buildTag: (Id3TagInfo?) -> ByteArray
    ): RewritePlan {
        val tag = openSource(context, trackPath).use { input -> parseId3(input) }
        return RewritePlan(buildTag(tag), tag?.audioOffset ?: 0L)
    }

    private fun removeMp3Lyrics(context: Context, trackPath: String): Boolean {
        val tag = openSource(context, trackPath).use { input -> parseId3(input) } ?: return false
        val extraction = extractNonUsltFrames(tag.frames, tag.major)
        if (extraction.usltCount == 0) return false
        val prefix = if (extraction.kept.isEmpty()) ByteArray(0) else buildId3Header(tag.major, extraction.kept.size) + extraction.kept
        applyRewrite(context, trackPath, RewritePlan(prefix, tag.audioOffset))
        return true
    }

    private fun buildUsltFrame(lyrics: String, major: Int): ByteArray {
        val (encoding, textBytes, terminator) = when {
            major >= 4 -> Triple(3, lyrics.toByteArray(Charsets.UTF_8), byteArrayOf(0))
            lyrics.all { it.code in 0..255 } -> Triple(0, lyrics.toByteArray(Charsets.ISO_8859_1), byteArrayOf(0))
            else -> Triple(1, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + lyrics.toByteArray(Charsets.UTF_16LE), byteArrayOf(0, 0))
        }

        val body = ByteArrayOutputStream()
        body.write(encoding)
        body.write("eng".toByteArray(Charsets.US_ASCII))
        body.write(terminator) // empty descriptor
        body.write(textBytes)
        body.write(terminator)

        val frame = ByteArrayOutputStream()
        frame.write(ID3_USLT.toByteArray(Charsets.US_ASCII))
        val size = body.size()
        if (major >= 4) {
            frame.write(syncSafeBytes(size))
        } else {
            frame.write((size shr 24) and 0xFF)
            frame.write((size shr 16) and 0xFF)
            frame.write((size shr 8) and 0xFF)
            frame.write(size and 0xFF)
        }
        frame.write(0)
        frame.write(0)
        body.writeTo(frame)
        return frame.toByteArray()
    }

    private fun buildId3Header(major: Int, tagSize: Int): ByteArray =
        "ID3".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(major.toByte(), 0, 0) +
                syncSafeBytes(tagSize)

    private fun syncSafeBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
                ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
                ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

    // InputStream.readNBytes is API 33+; the codebase ships compat copies instead.
    private fun readNBytesCompat(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var totalRead = 0
        while (totalRead < n) {
            val read = input.read(buffer, totalRead, n - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == n) buffer else buffer.copyOf(totalRead)
    }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val discarded = readNBytesCompat(input, minOf(remaining, 8192L).toInt())
                if (discarded.isEmpty()) break
                remaining -= discarded.size
            } else {
                remaining -= skipped
            }
        }
    }
}
