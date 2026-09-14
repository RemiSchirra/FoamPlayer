package com.foamplayer.apple

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AppleMusicCanvas].
 *
 * These exercise the full online pipeline against the real Apple endpoints:
 * live web-player JWT scraping (music.apple.com), AMP API search and album
 * lookup, so they require internet access. Fixtures were verified against the
 * live AMP catalog: "After Hours" by The Weeknd carries editorialVideo HLS
 * motion artwork, reachable both directly by album id and via song/album
 * search resolution.
 *
 * The playlist-id and blacklisting behaviour is checked without depending on
 * any particular catalogue state.
 */
class AppleMusicCanvasTest {

    companion object {
        // Apple catalog id for "After Hours" — The Weeknd. Verified to expose
        // editorialVideo.motionDetailSquare.video (an HLS .m3u8 manifest).
        private const val AFTER_HOURS_ALBUM_ID = "1499378108"
    }

    // --- direct album lookup ---------------------------------------------------

    @Test(timeout = 120_000)
    fun `getByAlbumId fetches motion artwork for After Hours`() = runBlocking {
        val result = AppleMusicCanvas.getByAlbumId(AFTER_HOURS_ALBUM_ID)

        assertNotNull("expected motion artwork for album $AFTER_HOURS_ALBUM_ID", result)
        result!!

        assertEquals("After Hours", result.albumName)
        assertTrue("expected artist to contain 'Weeknd', was: ${result.artist}", result.artist?.contains("Weeknd") == true)

        val hls = result.animated
        assertNotNull("expected an HLS url in the animated field", hls)
        assertTrue("expected an .m3u8 HLS manifest, was: $hls", hls!!.endsWith(".m3u8"))
    }

    @Test(timeout = 30_000)
    fun `getByAlbumId ignores playlist ids without hitting the API`() = runBlocking {
        val result = AppleMusicCanvas.getByAlbumId("pl.u-verifiedplaylistid")
        assertNull("playlist ids must be rejected up front", result)
    }

    // --- search-driven resolution ----------------------------------------------

    @Test(timeout = 180_000)
    fun `getBySongArtist resolves a song to an album with motion artwork`() = runBlocking {
        val result = AppleMusicCanvas.getBySongArtist("Blinding Lights", "The Weeknd")

        assertNotNull("expected a canvas for 'Blinding Lights' via After Hours resolution", result)
        result!!

        assertTrue("expected artist to contain 'Weeknd', was: ${result.artist}", result.artist?.contains("Weeknd") == true)
        assertEquals("Blinding Lights", result.name)
        assertNotNull("expected an animation url", result.animated)
        assertTrue("expected a non-blank animation url", result.animated!!.isNotBlank())
    }

    @Test(timeout = 120_000)
    fun `getByAlbumArtist fetches motion artwork for After Hours`() = runBlocking {
        val result = AppleMusicCanvas.getByAlbumArtist("After Hours", "The Weeknd")

        assertNotNull("expected a canvas for the 'After Hours' album search", result)
        result!!

        assertNotNull("expected an animation url", result.preferredAnimationUrl)
        assertTrue("expected a non-blank animation url", result.preferredAnimationUrl!!.isNotBlank())
    }

    @Test(timeout = 120_000)
    fun `getBySongArtist returns null for an unknown song without throwing`() = runBlocking {
        val result = AppleMusicCanvas.getBySongArtist("Zzqqxv Nonexistent Track 42", "Nobody Real Artist")
        assertNull(result)
    }

    @Test(timeout = 120_000)
    fun `getBySongArtist returns null when the artist does not match`() = runBlocking {
        // Real song title, wrong artist: the strict/fuzzy artist gate must
        // filter every candidate out instead of returning a mismatched canvas.
        val result = AppleMusicCanvas.getBySongArtist("Blinding Lights", "Zzqqxv Nobody")
        assertNull(result)
    }

    // --- result quality gates ----------------------------------------------------

    @Test(timeout = 180_000)
    fun `getBySongArtist with album hint returns artwork carrying the album name`() = runBlocking {
        val result = AppleMusicCanvas.getBySongArtist("Blinding Lights", "The Weeknd", album = "After Hours")

        assertNotNull(result)
        result!!

        assertEquals("After Hours", result.albumName)
        assertNotNull(result.animated)
    }
}
