package com.foamplayer.echomusic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EchoMusicCanvas].
 *
 * The provider maps song+artist onto looping canvas videos via a manifest
 * (canvas.echomusic.fun/canvas.json). The manifest endpoint has been
 * intermittently unreachable (NXDOMAIN at times), so the online test asserts
 * the graceful-degradation contract (null, never a throw) rather than a
 * specific match. Parsing is verified offline against sample payloads.
 */
class EchoMusicCanvasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // --- manifest parsing (offline) ---------------------------------------------

    @Test
    fun `manifest json parses into items`() {
        val payload = """
            {"items":[
                {"song":"Dracula","artist":"Tame Impala","url":"https://example.com/Song/1.mp4"},
                {"song":"Flowers","artist":"Miley Cyrus","url":"https://example.com/Song/2.mp4","album":"Endless Summer Vacation"}
            ]}
        """.trimIndent()

        val manifest = json.decodeFromString<EchoMusicCanvasManifest>(payload)

        assertEquals(2, manifest.items.size)
        val first = manifest.items[0]
        assertEquals("Dracula", first.song)
        assertEquals("Tame Impala", first.artist)
        assertEquals("https://example.com/Song/1.mp4", first.url)
    }

    @Test
    fun `manifest with no items field defaults to an empty list`() {
        val manifest = json.decodeFromString<EchoMusicCanvasManifest>("{}")
        assertEquals(0, manifest.items.size)
    }

    @Test
    fun `unknown keys in manifest items are ignored`() {
        val payload = """{"items":[{"song":"her","artist":"JVKE","url":"https://example.com/Song/4.m3u8","futureField":123}]}"""
        val manifest = json.decodeFromString<EchoMusicCanvasManifest>(payload)
        assertEquals(1, manifest.items.size)
        assertEquals("her", manifest.items[0].song)
    }

    // --- input validation (offline, no network) -----------------------------------

    @Test
    fun `blank song or artist returns null without hitting the network`() = runBlocking {
        assertNull(EchoMusicCanvas.getBySongArtist("", "Tame Impala"))
        assertNull(EchoMusicCanvas.getBySongArtist("Dracula", "   "))
        assertNull(EchoMusicCanvas.getBySongArtist("", ""))
    }

    // --- online fetch (graceful degradation) ---------------------------------------

    @Test(timeout = 60_000)
    fun `getBySongArtist never throws and returns null or a valid artwork`() = runBlocking {
        // Whatever the state of the remote manifest, a lookup must either
        // produce a usable artwork or null — never propagate a failure.
        val result = EchoMusicCanvas.getBySongArtist("Dracula", "Tame Impala")
        if (result != null) {
            assertTrue("expected a non-blank animation url", result.preferredAnimationUrl?.isNotBlank() == true)
            assertEquals("Dracula", result.name)
        }
    }

    @Test(timeout = 60_000)
    fun `getBySongArtist returns null for a song absent from the manifest`() = runBlocking {
        // This song is not plausibly in any manifest, so it must be null even
        // when the endpoint is up.
        val result = EchoMusicCanvas.getBySongArtist("Zzqqxv Nonexistent Track 42", "Nobody Real Artist")
        assertNull(result)
    }
}
