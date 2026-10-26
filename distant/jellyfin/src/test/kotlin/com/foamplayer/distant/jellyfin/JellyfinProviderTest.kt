package com.foamplayer.distant.jellyfin

import com.foamplayer.distant.DistantCredentials
import com.foamplayer.distant.DistantProviderType
import com.foamplayer.distant.DistantServer
import com.foamplayer.distant.DistantTrack
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the wire format Jellyfin expects: the server only reads the standard
 * `Authorization` header (unless legacy authorization is enabled, where it
 * reads `X-Emby-Authorization`) and answers 400 when the parsed client name,
 * device id or version is missing.
 */
class JellyfinProviderTest {

    private lateinit var server: HttpServer
    private var authorization: String? = null
    private var legacyAuthorization: String? = null
    private var body: String? = null

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/Users/AuthenticateByName") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            legacyAuthorization = exchange.requestHeaders.getFirst("X-Emby-Authorization")
            body = exchange.requestBody.readBytes().decodeToString()
            val payload = """{"AccessToken":"token-123","User":{"Id":"user-123"}}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `login sends the MediaBrowser authorization header and returns the token`() = runBlocking {
        val credentials = provider().login(draft())

        assertEquals("token-123", credentials.secret)
        assertEquals("user-123", credentials.userId)
        assertEquals("alice", credentials.username)

        val header = authorization
        assertNotNull(header)
        assertTrue(header!!.startsWith("MediaBrowser "))
        assertTrue(header.contains("Client=\"FoamPlayer\""))
        assertTrue(header.contains("DeviceId=\"foamplayer\""))
        assertTrue(header.contains("Version=\"1.0.0\""))
        // Older servers read the legacy header, so both must carry the same value.
        assertEquals(header, legacyAuthorization)

        val json = body
        assertNotNull(json)
        assertTrue(json!!.contains("\"Username\":\"alice\""))
        assertTrue(json.contains("\"Pw\":\"secret\""))
    }

    @Test
    fun `stream and art urls authorize with the ApiKey query parameter`() {
        val connected = draft().copy(credentials = DistantCredentials(secret = "token-123", userId = "user-123"))
        val provider = provider()

        val stream = provider.streamUrl(connected, "item-1")
        assertTrue(stream.contains("/Audio/item-1/stream"))
        assertTrue(stream.contains("ApiKey=token-123"))

        val art = provider.artUrl(connected, DistantTrack(remoteId = "item-1", title = ""))
        assertTrue(art!!.contains("ApiKey=token-123"))
    }

    private fun provider() = JellyfinProvider()

    private fun draft() = DistantServer(
        id = "jellyfin-test",
        type = DistantProviderType.JELLYFIN,
        name = "Test",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        credentials = DistantCredentials(username = "alice", secret = "secret")
    )
}
