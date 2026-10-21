package com.foamplayer.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmRecentTracksParsingTest {

    @Test
    fun `parses scrobbles and skips the now playing entry`() {
        val page = parseRecentTracksPage(
            """
            {
              "recenttracks": {
                "@attr": {
                  "page": "2",
                  "perPage": "200",
                  "totalPages": "5",
                  "total": "812"
                },
                "track": [
                  {
                    "artist": { "#text": "Now Playing Artist" },
                    "name": "Live Track",
                    "album": { "#text": "Live Album" },
                    "@attr": { "nowplaying": "true" }
                  },
                  {
                    "artist": { "#text": "Boards of Canada" },
                    "name": "Roygbiv",
                    "album": { "#text": "Music Has the Right to Children" },
                    "date": { "uts": "1700000000", "#text": "14 Nov 2023" }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(2, page.page)
        assertEquals(5, page.totalPages)
        assertEquals(812, page.total)
        assertEquals(1, page.tracks.size)

        val track = page.tracks.single()
        assertEquals("Boards of Canada", track.artist)
        assertEquals("Roygbiv", track.title)
        assertEquals("Music Has the Right to Children", track.album)
        assertEquals(1_700_000_000L, track.timestampSeconds)
    }

    @Test
    fun `accepts a single track object without an album`() {
        val page = parseRecentTracksPage(
            """
            {
              "recenttracks": {
                "@attr": { "page": "1", "totalPages": "1", "total": "1" },
                "track": {
                  "artist": { "#text": "Aphex Twin" },
                  "name": "Xtal",
                  "date": { "uts": "1600000000" }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, page.tracks.size)
        assertNull(page.tracks.single().album)
        assertEquals("Aphex Twin", page.tracks.single().artist)
    }

    @Test
    fun `returns an empty page when the history has no tracks`() {
        val page = parseRecentTracksPage(
            """{ "recenttracks": { "@attr": { "total": "0", "totalPages": "0" } } }"""
        )

        assertTrue(page.tracks.isEmpty())
        assertEquals(0, page.totalPages)
        assertEquals(0, page.total)
    }
}
