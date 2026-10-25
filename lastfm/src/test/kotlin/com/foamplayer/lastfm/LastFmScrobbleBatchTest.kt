package com.foamplayer.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test

class LastFmScrobbleBatchTest {

    @Test
    fun `batch parameters index every track field per scrobble`() {
        val scrobbles = listOf(
            LastFmScrobble(
                track = LastFmTrack(
                    artist = "Artist",
                    title = "Song",
                    album = "Album",
                    albumArtist = "Album Artist",
                    durationSeconds = 200,
                    trackNumber = 3
                ),
                timestampSeconds = 1_700_000_000
            ),
            LastFmScrobble(
                track = LastFmTrack(artist = "Artist 2", title = "Song 2"),
                timestampSeconds = 1_700_000_500
            )
        )

        val params = scrobbles.toBatchParameters()

        assertEquals("Artist", params["artist[0]"])
        assertEquals("Song", params["track[0]"])
        assertEquals("Album", params["album[0]"])
        assertEquals("Album Artist", params["albumArtist[0]"])
        assertEquals("200", params["duration[0]"])
        assertEquals("3", params["trackNumber[0]"])
        assertEquals("1700000000", params["timestamp[0]"])
        assertEquals("Artist 2", params["artist[1]"])
        assertEquals("Song 2", params["track[1]"])
        assertEquals("1700000500", params["timestamp[1]"])
        assertEquals(10, params.size)
    }

    @Test
    fun `indexed batch keys sign the same regardless of insertion order`() {
        val forward = linkedMapOf(
            "method" to "track.scrobble",
            "api_key" to "key",
            "artist[0]" to "Artist",
            "track[0]" to "Song",
            "timestamp[0]" to "1700000000"
        )
        val reversed = linkedMapOf(
            "timestamp[0]" to "1700000000",
            "track[0]" to "Song",
            "artist[0]" to "Artist",
            "api_key" to "key",
            "method" to "track.scrobble"
        )

        assertEquals(
            LastFmApi.signature(forward, "secret"),
            LastFmApi.signature(reversed, "secret")
        )
    }
}
