package com.foamplayer.vivimusic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ViviMusicCanvas].
 *
 * The online tests hit the live manifest endpoint
 * (vivimusicanvas.mkmdevilmi.workers.dev/canvas.json) and require internet
 * access. Fixtures use entries verified to exist in the published manifest
 * ("Dracula" — Tame Impala and "Flowers" — Miley Cyrus).
 */
class ViviMusicCanvasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // --- manifest parsing (offline) ----------------------------------------------

    @Test
    fun `manifest json parses into items`() {
        val payload = """
            {"items":[
                {"song":"Dracula","artist":"Tame Impala","url":"https://example.com/Song/1.mp4","album":"Deadbeat"},
                {"song":"12 to 12","artist":"sombr","url":"https://example.com/Song/12.m3u8"}
            ]}
        """.trimIndent()

        val manifest = json.decodeFromString<ViviMusicCanvasManifest>(payload)

        assertEquals(2, manifest.items.size)
        val first = manifest.items[0]
        assertEquals("Dracula", first.song)
        assertEquals("Tame Impala", first.artist)
        assertEquals("https://example.com/Song/1.mp4", first.url)
    }

    @Test
    fun `manifest with no items field defaults to an empty list`() {
        val manifest = json.decodeFromString<ViviMusicCanvasManifest>("{}")
        assertEquals(0, manifest.items.size)
    }

    // --- input validation (offline, no network) -------------------------------------

    @Test
    fun `blank song or artist returns null without hitting the network`() = runBlocking {
        assertNull(ViviMusicCanvas.getBySongArtist("", "Tame Impala"))
        assertNull(ViviMusicCanvas.getBySongArtist("Dracula", "   "))
        assertNull(ViviMusicCanvas.getBySongArtist("", ""))
    }

    // --- online fetch ----------------------------------------------------------------

    @Test(timeout = 60_000)
    fun `getBySongArtist finds Dracula by Tame Impala`() = runBlocking {
        val result = ViviMusicCanvas.getBySongArtist("Dracula", "Tame Impala")

        assertNotNull("expected a canvas for 'Dracula' (present in the live manifest)", result)
        result!!

        assertEquals("Dracula", result.name)
        assertEquals("Tame Impala", result.artist)
        assertEquals("https://vivimusicanvas.mkmdevilmi.workers.dev/Song/1.mp4", result.videoUrl)
        assertEquals(result.videoUrl, result.animated)
        assertTrue(result.preferredAnimationUrl?.isNotBlank() == true)
    }

    @Test(timeout = 60_000)
    fun `getBySongArtist finds Flowers by Miley Cyrus`() = runBlocking {
        val result = ViviMusicCanvas.getBySongArtist("Flowers", "Miley Cyrus")

        assertNotNull("expected a canvas for 'Flowers' (present in the live manifest)", result)
        result!!

        assertEquals("Flowers", result.name)
        assertEquals("Miley Cyrus", result.artist)
        assertTrue("expected a canvas url from the vivimusic worker", result.preferredAnimationUrl!!.startsWith("https://vivimusicanvas.mkmdevilmi.workers.dev/"))
    }

    @Test(timeout = 60_000)
    fun `getBySongArtist matches case-insensitively and partially`() = runBlocking {
        // The matcher checks containment in both directions with ignoreCase.
        val result = ViviMusicCanvas.getBySongArtist("dracula", "tame impala")
        assertNotNull("case-insensitive lookup should still match", result)
        assertEquals("Dracula", result!!.name)
    }

    @Test(timeout = 60_000)
    fun `getBySongArtist returns null for a song absent from the manifest`() = runBlocking {
        val result = ViviMusicCanvas.getBySongArtist("Zzqqxv Nonexistent Track 42", "Nobody Real Artist")
        assertNull(result)
    }
}
