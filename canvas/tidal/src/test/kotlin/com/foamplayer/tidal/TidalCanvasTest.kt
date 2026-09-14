package com.foamplayer.tidal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TidalCanvas].
 *
 * The `getBySongArtist`/`getByAlbumArtist` tests hit the live Tidal search API
 * (api.tidal.com with the public embed token) and require internet access.
 * Fixtures use tracks/albums verified to carry a `videoCover`.
 */
class TidalCanvasTest {

    // --- formatVideoUrl (pure, offline) --------------------------------------

    @Test
    fun `formatVideoUrl builds resources url from five-part id`() {
        val url = TidalCanvas.formatVideoUrl("e9e126a9-97b6-4f99-b7b6-c242cb6eb31d")
        assertEquals(
            "https://resources.tidal.com/videos/e9e126a9/97b6/4f99/b7b6/c242cb6eb31d/1280x1280.mp4",
            url,
        )
    }

    @Test
    fun `formatVideoUrl returns null when id does not have five parts`() {
        assertNull(TidalCanvas.formatVideoUrl(""))
        assertNull(TidalCanvas.formatVideoUrl("e9e126a9"))
        assertNull(TidalCanvas.formatVideoUrl("e9e126a9-97b6-4f99-b7b6"))
        assertNull(TidalCanvas.formatVideoUrl("e9e126a9-97b6-4f99-b7b6-c242cb6eb31d-extra"))
    }

    // --- online fetch ---------------------------------------------------------

    @Test(timeout = 90_000)
    fun `getBySongArtist fetches video cover for a track known to have one`() = runBlocking {
        val result = TidalCanvas.getBySongArtist("Blinding Lights", "The Weeknd")

        assertNotNull("expected a canvas for 'Blinding Lights' (known videoCover)", result)
        result!!

        assertEquals("Blinding Lights", result.name)
        assertTrue("expected artist to contain 'Weeknd', was: ${result.artist}", result.artist?.contains("Weeknd") == true)

        val url = result.preferredAnimationUrl
        assertNotNull("expected a video url", url)
        assertTrue("url should point at tidal resources, was: $url", url!!.startsWith("https://resources.tidal.com/videos/"))
        assertTrue("url should be the 1280x1280 mp4, was: $url", url.endsWith("/1280x1280.mp4"))
    }

    @Test(timeout = 90_000)
    fun `getBySongArtist resolves with an album hint`() = runBlocking {
        val result = TidalCanvas.getBySongArtist("Blinding Lights", "The Weeknd", album = "After Hours")

        assertNotNull("expected a canvas when an album hint is supplied", result)
        assertNotNull(result!!.preferredAnimationUrl)
        assertEquals("After Hours", result.albumName)
    }

    @Test(timeout = 90_000)
    fun `getByAlbumArtist fetches video cover for an album known to have one`() = runBlocking {
        val result = TidalCanvas.getByAlbumArtist("After Hours", "The Weeknd")

        assertNotNull("expected a canvas for 'After Hours' (known videoCover)", result)
        result!!

        assertTrue(
            "expected title to contain 'After Hours', was: ${result.name}",
            result.name?.contains("After Hours", ignoreCase = true) == true,
        )
        val url = result.preferredAnimationUrl
        assertNotNull(url)
        assertTrue("url should point at tidal resources, was: $url", url!!.startsWith("https://resources.tidal.com/videos/"))
    }

    @Test(timeout = 90_000)
    fun `search returns null for an unknown song without throwing`() = runBlocking {
        val result = TidalCanvas.getBySongArtist("Zzqqxv Nonexistent Track 42", "Nobody Real Artist")
        assertNull(result)
    }

    @Test(timeout = 90_000)
    fun `artist validation rejects results from other artists`() = runBlocking {
        // A real Tidal track title, but queried against a totally different
        // artist name: the artist check must filter everything out.
        val result = TidalCanvas.getBySongArtist("Blinding Lights", "Zzqqxv Nobody")
        assertNull(result)
    }
}
