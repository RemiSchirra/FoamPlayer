package com.foamplayer.app.data.distant

import com.foamplayer.distant.DistantServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON (de)serialization of the connected distant servers for the DataStore. */
object DistantServersCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(DistantServer.serializer())

    fun decode(stored: String?): List<DistantServer> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
    }

    fun encode(servers: List<DistantServer>): String =
        if (servers.isEmpty()) "" else json.encodeToString(serializer, servers)
}
