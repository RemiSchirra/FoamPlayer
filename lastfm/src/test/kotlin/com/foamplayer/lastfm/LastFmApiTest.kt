package com.foamplayer.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmApiTest {

    @Test
    fun `signature sorts parameters and appends the secret`() {
        val signature = LastFmApi.signature(
            params = mapOf(
                "method" to "auth.getToken",
                "api_key" to "123"
            ),
            secret = "secret"
        )

        assertEquals("9a863de2c128d5f2e9bf64c3dba37cdb", signature)
    }

    @Test
    fun `signature ignores api_sig format and callback`() {
        val base = mapOf("method" to "track.scrobble", "api_key" to "key", "artist" to "A")
        val withExcluded = base + mapOf(
            "api_sig" to "deadbeef",
            "format" to "json",
            "callback" to "cb"
        )

        assertEquals(
            LastFmApi.signature(base, "secret"),
            LastFmApi.signature(withExcluded, "secret")
        )
    }

    @Test
    fun `authorization url encodes key and token`() {
        val url = LastFmApi.authorizationUrl("key value", "tok/en")

        assertTrue(url.startsWith(LastFmApi.AUTH_PAGE_URL))
        assertTrue(url.contains("api_key=key%20value"))
        assertTrue(url.contains("token=tok%2Fen"))
    }

    @Test
    fun `blank credentials mean the client is not configured`() {
        assertFalse(LastFmClient("", "").isConfigured)
        assertTrue(LastFmClient("key", "secret").isConfigured)
    }
}
