package com.foamplayer.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object GatewayOp {
    const val DISPATCH = 0
    const val HEARTBEAT = 1
    const val IDENTIFY = 2
    const val PRESENCE_UPDATE = 3
    const val RECONNECT = 7
    const val INVALID_SESSION = 9
    const val HELLO = 10
    const val HEARTBEAT_ACK = 11
}

internal object GatewayCloseCodes {
    val NON_RESUMABLE = setOf(4004, 4010, 4011, 4012, 4013, 4014)
}

internal object GatewayIntents {
    const val FLAGS: Int = (1 shl 12) or
            (1 shl 18) or
            (1 shl 19) or
            (1 shl 22) or
            (1 shl 23) or
            (1 shl 27) or
            (1 shl 28) or
            (1 shl 29)
}

internal object GatewayCapabilities {
    const val FLAGS: Int = (1 shl 4) or
            (1 shl 5) or
            (1 shl 12) or
            (1 shl 16)
}

class DiscordGatewayClient(
    private val logger: DiscordLogger? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val connectionGeneration = AtomicLong(0L)

    @Volatile private var httpClient: HttpClient? = null
    @Volatile private var session: ClientWebSocketSession? = null
    @Volatile private var processingJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var closed = true
    @Volatile private var readyReceived = false
    @Volatile private var lastAck = true
    private var liveSeq = 0
    private var lastHeartbeatAt = 0L
    private var ping = -1
    private var token = ""

    var onReady: (() -> Unit)? = null
    var onClose: ((code: Int, reason: String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    private fun debug(message: String) {
        logger?.log("Gateway: $message")
    }

    val latency: Int get() = ping

    val isConnected: Boolean
        get() = !closed && session?.isActive == true && processingJob?.isActive == true

    val isReady: Boolean get() = isConnected && readyReceived

    /**
     * True when a heartbeat was sent but never acknowledged — the signature of
     * a half-open socket where writes "succeed" locally but never reach
     * Discord. Full zombie detection takes up to two heartbeat intervals, so
     * callers can reconnect proactively before wasting updates.
     */
    val isLikelyDead: Boolean
        get() = !lastAck && (System.currentTimeMillis() - lastHeartbeatAt > HEARTBEAT_DEAD_THRESHOLD_MS)

    suspend fun connect(accessToken: String) {
        check(session == null) { "DiscordGatewayClient already connected" }

        token = "Bearer $accessToken"
        closed = false
        readyReceived = false
        liveSeq = 0
        lastAck = true

        val url = "${DiscordApi.GATEWAY_URL}/?v=${DiscordApi.GATEWAY_VERSION}&encoding=json"
        val ready = CompletableDeferred<Unit>()
        val generation = connectionGeneration.incrementAndGet()
        debug("connecting $url")

        val client = HttpClient(OkHttp) { install(WebSockets) }
        httpClient = client

        val ws = try {
            client.webSocketSession(url)
        } catch (e: Throwable) {
            debug("websocket handshake failed: ${e.message}")
            client.close()
            httpClient = null
            closed = true
            throw e
        }
        session = ws

        processingJob = scope.launch {
            try {
                processFrames(ws, ready, generation)
            } catch (e: CancellationException) {
                if (!ready.isCompleted) ready.completeExceptionally(e)
            } catch (e: Exception) {
                if (!ready.isCompleted) ready.completeExceptionally(e)
                onError?.invoke(e)
                handleClose(1006, e.message ?: "connection failure", generation)
            }
        }

        try {
            withTimeout(HELLO_TIMEOUT_MS) { ready.await() }
            debug("ready")
        } catch (e: Exception) {
            debug("did not become ready: ${e.message}")
            disconnect()
            throw IllegalStateException("Discord gateway did not become ready", e)
        }
    }

    fun sendPresenceUpdate(payload: JsonObject): Boolean =
        sendFrame(GatewayOp.PRESENCE_UPDATE, payload, requireReady = true)

    suspend fun disconnect() {
        if (closed && session == null && httpClient == null) return
        closed = true
        readyReceived = false
        heartbeatJob?.cancel()
        heartbeatJob = null

        val ws = session
        session = null
        if (ws != null) {
            runCatching { ws.close(CloseReason(1000, "Client disconnect")) }
            runCatching { ws.cancel() }
        }

        processingJob?.cancelAndJoin()
        processingJob = null
        httpClient?.close()
        httpClient = null
    }

    private suspend fun processFrames(
        ws: ClientWebSocketSession,
        ready: CompletableDeferred<Unit>,
        generation: Long
    ) {
        for (frame in ws.incoming) {
            if (generation != connectionGeneration.get()) return
            when (frame) {
                is Frame.Text -> handleText(frame.readText(), ready, generation)
                is Frame.Close -> {
                    val data = frame.data
                    val code = if (data.size >= 2) {
                        ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    } else {
                        1005
                    }
                    val reason = if (data.size > 2) {
                        String(data, 2, data.size - 2, Charsets.UTF_8)
                    } else {
                        ""
                    }.ifBlank { "closed" }
                    handleClose(code, reason, generation)
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(IllegalStateException("Gateway closed before ready ($code)"))
                    }
                    return
                }
                else -> Unit
            }
        }
        handleClose(1006, "connection closed", generation)
        if (!ready.isCompleted) {
            ready.completeExceptionally(IllegalStateException("Gateway closed before ready"))
        }
    }

    private fun handleText(text: String, ready: CompletableDeferred<Unit>, generation: Long) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val op = obj.getValue("op").jsonPrimitive.int
            val d = obj["d"]
            val seq = obj["s"]?.jsonPrimitive?.intOrNull
            if (seq != null && seq > liveSeq) liveSeq = seq

            when (op) {
                GatewayOp.HELLO -> {
                    val interval = d?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.int
                        ?: DEFAULT_HEARTBEAT_INTERVAL_MS
                    debug("hello, heartbeat every ${interval}ms")
                    startHeartbeat(interval.toLong())
                    sendIdentify()
                }

                GatewayOp.HEARTBEAT_ACK -> {
                    lastAck = true
                    ping = (System.currentTimeMillis() - lastHeartbeatAt).toInt()
                }

                GatewayOp.HEARTBEAT -> sendHeartbeat(force = true)

                GatewayOp.RECONNECT -> forceClose(4000, "server reconnect", generation)

                GatewayOp.INVALID_SESSION -> {
                    val resumable = d?.let {
                        runCatching { it.jsonPrimitive.content == "true" }.getOrDefault(false)
                    } ?: false
                    forceClose(if (resumable) 4000 else 1000, "invalid session", generation)
                }

                GatewayOp.DISPATCH -> handleDispatch(
                    type = obj["t"]?.jsonPrimitive?.let {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }.orEmpty(),
                    d = d,
                    ready = ready,
                    generation = generation
                )
            }
        } catch (e: Exception) {
            onError?.invoke(e)
        }
    }

    private fun handleDispatch(
        type: String,
        d: kotlinx.serialization.json.JsonElement?,
        ready: CompletableDeferred<Unit>,
        generation: Long
    ) {
        when (type) {
            "READY" -> {
                readyReceived = true
                if (!ready.isCompleted) ready.complete(Unit)
                onReady?.invoke()
            }

            "RESUMED" -> {
                readyReceived = true
                if (!ready.isCompleted) ready.complete(Unit)
            }

            else -> Unit
        }
    }

    private fun sendIdentify() {
        val d = buildJsonObject {
            put("capabilities", GatewayCapabilities.FLAGS)
            put("intents", GatewayIntents.FLAGS)
            put("token", token)
            putJsonObject("properties") {
                put("os", "Android")
                put("browser", "FoamPlayer")
                put("device", "Android")
                put("browser_user_agent", "FoamPlayer")
                put("browser_version", "1.0")
                put("client_version", "1.0")
                put("client_build_number", 1)
                put("native_build_number", 1)
                put("release_channel", "unknown")
            }
        }
        sendFrame(GatewayOp.IDENTIFY, d, requireReady = false)
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((intervalMs * Random.nextDouble()).toLong())
            if (isConnected) {
                sendHeartbeat()
                while (isActive && isConnected) {
                    delay(intervalMs)
                    sendHeartbeat()
                }
            }
        }
    }

    private fun sendHeartbeat(force: Boolean = false) {
        val generation = connectionGeneration.get()
        if (!force && !lastAck) {
            forceClose(4009, "heartbeat ack missed", generation)
            return
        }
        lastAck = false
        lastHeartbeatAt = System.currentTimeMillis()
        val seq: kotlinx.serialization.json.JsonElement? = if (liveSeq > 0) {
            kotlinx.serialization.json.JsonPrimitive(liveSeq)
        } else {
            kotlinx.serialization.json.JsonNull
        }
        sendFrame(GatewayOp.HEARTBEAT, seq, requireReady = false)
    }

    private fun sendFrame(op: Int, d: kotlinx.serialization.json.JsonElement?, requireReady: Boolean): Boolean {
        if (closed || requireReady && !readyReceived) {
            debug("send skipped (closed=$closed ready=$readyReceived) op=$op")
            return false
        }
        val ws = session ?: return false
        if (!ws.isActive) return false
        val frame = buildJsonObject {
            put("op", op)
            if (d == null || d is kotlinx.serialization.json.JsonNull) {
                put("d", kotlinx.serialization.json.JsonNull)
            } else {
                put("d", d)
            }
        }.toString()
        return try {
            runCatching {
                ws.outgoing.trySend(Frame.Text(frame)).isSuccess
            }.getOrElse { false }.also { sent ->
                if (!sent) onError?.invoke(IllegalStateException("Failed to send gateway frame op=$op"))
            }
        } catch (e: Exception) {
            onError?.invoke(e)
            false
        }
    }

    private fun forceClose(code: Int, reason: String, generation: Long) {
        val ws = session
        if (ws == null || !ws.isActive) {
            handleClose(code, reason, generation)
        } else {
            scope.launch {
                runCatching { ws.close(CloseReason(code.toShort(), reason)) }
                handleClose(code, reason, generation)
            }
        }
    }

    private fun handleClose(code: Int, reason: String, generation: Long) {
        if (generation != connectionGeneration.get()) return
        if (closed) return
        closed = true
        readyReceived = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        debug("closed code=$code reason=$reason")
        onClose?.invoke(code, reason)
    }

    companion object {
        private const val HELLO_TIMEOUT_MS = 20_000L
        private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 41_250
        private const val HEARTBEAT_DEAD_THRESHOLD_MS = 5_000L
    }
}
