package com.foamplayer.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

class DiscordPresenceClient(
    private val logger: DiscordLogger? = null
) {
    private val mutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gateway: DiscordGatewayClient? = null
    private var activeToken: String? = null

    @Volatile private var transportInvalidatedListener: ((String) -> Unit)? = null

    val isStarted: Boolean
        get() = gateway != null && !activeToken.isNullOrBlank() && gateway!!.isReady

    fun setOnTransportInvalidated(listener: ((String) -> Unit)?) {
        transportInvalidatedListener = listener
    }

    suspend fun updatePresence(
        accessToken: String,
        activity: DiscordPresenceActivity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val token = accessToken.trim()
            if (token.isBlank()) {
                return@withLock Result.failure(IllegalArgumentException("Discord access token is missing"))
            }

            val payload = buildPresencePayload(token, activity)
            var lastError: Throwable? = null

            repeat(MAX_SEND_ATTEMPTS) { attempt ->
                val connectResult = ensureConnected(token, force = attempt > 0)
                if (connectResult.isFailure) {
                    logger?.log("PresenceClient: connect failed (attempt=$attempt): ${connectResult.exceptionOrNull()?.message}")
                    return@withLock connectResult
                }

                val sent = gateway?.takeIf { it.isReady }?.sendPresenceUpdate(payload) ?: false
                if (sent) return@withLock Result.success(Unit)

                lastError = IllegalStateException("Failed to send presence update (attempt=$attempt)")
                logger?.log("PresenceClient: send failed (attempt=$attempt)")
                tearDown()
            }

            Result.failure(lastError ?: IllegalStateException("Failed to send presence update"))
        }
    }

    suspend fun clearPresence(accessToken: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val empty = buildJsonObject {
                putJsonArray("activities") {}
                put("afk", false)
                put("since", JsonNull)
                put("status", "online")
            }

            var lastError: Throwable? = null
            repeat(MAX_SEND_ATTEMPTS) { attempt ->
                val existing = gateway
                if (existing == null || !existing.isReady) {
                    val token = accessToken?.trim().orEmpty()
                    if (token.isBlank()) {
                        return@withLock Result.failure(lastError ?: IllegalStateException("Discord gateway not connected"))
                    }
                    val connectResult = ensureConnected(token, force = existing != null || attempt > 0)
                    if (connectResult.isFailure) return@withLock connectResult
                }

                val sent = gateway?.takeIf { it.isReady }?.sendPresenceUpdate(empty) ?: false
                if (sent) return@withLock Result.success(Unit)

                lastError = IllegalStateException("Failed to clear presence (attempt=$attempt)")
                tearDown()
            }

            Result.failure(lastError ?: IllegalStateException("Failed to clear presence"))
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            tearDown()
            Result.success(Unit)
        }
    }

    private suspend fun ensureConnected(token: String, force: Boolean = false): Result<Unit> {
        val current = gateway
        if (!force && activeToken == token && current != null && current.isReady && !current.isLikelyDead) {
            return Result.success(Unit)
        }

        if (current != null && current.isLikelyDead) {
            logger?.log("PresenceClient: gateway unresponsive, reconnecting")
        }

        tearDown()

        val newGateway = DiscordGatewayClient(logger = logger)
        newGateway.onClose = { code, reason ->
            logger?.log("PresenceClient: gateway closed code=$code reason=$reason")
            invalidateGateway(newGateway, "gateway_closed_$code:$reason")
        }
        newGateway.onError = { error ->
            logger?.log("PresenceClient: gateway error: ${error.message}")
            transportInvalidatedListener?.let { listener ->
                runCatching { listener("gateway_error:${error.message}") }
            }
        }

        return try {
            newGateway.connect(token)
            gateway = newGateway
            activeToken = token
            logger?.log("PresenceClient: gateway ready")
            Result.success(Unit)
        } catch (e: CancellationException) {
            runCatching { newGateway.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { newGateway.disconnect() }
            Result.failure(e)
        }
    }

    private fun invalidateGateway(expected: DiscordGatewayClient, reason: String) {
        callbackScope.launch {
            var invalidated = false
            mutex.withLock {
                if (gateway === expected) {
                    tearDown()
                    invalidated = true
                }
            }
            if (invalidated) {
                runCatching { transportInvalidatedListener?.invoke(reason) }
            }
        }
    }

    private suspend fun tearDown() {
        gateway?.let { current ->
            current.onClose = null
            current.onError = null
            current.onReady = null
            runCatching { current.disconnect() }
        }
        gateway = null
        activeToken = null
    }

    private suspend fun buildPresencePayload(token: String, activity: DiscordPresenceActivity): JsonObject {
        val largeImage = activity.assets.largeImage
        val smallImage = activity.assets.smallImage

        val activityJson = buildJsonObject {
            put("name", activity.name)
            put("type", activity.type.wireValue)
            activity.details?.let { put("details", it) }
            activity.state?.let { put("state", it) }
            put("application_id", activity.applicationId)

            if (activity.timestamps.startEpochSeconds != null || activity.timestamps.endEpochSeconds != null) {
                putJsonObject("timestamps") {
                    activity.timestamps.startEpochSeconds?.let { put("start", it * 1000L) }
                    activity.timestamps.endEpochSeconds?.let { put("end", it * 1000L) }
                }
            }

            if (largeImage != null || smallImage != null ||
                activity.assets.largeText != null || activity.assets.smallText != null
            ) {
                putJsonObject("assets") {
                    largeImage?.let { put("large_image", it) }
                    activity.assets.largeText?.let { put("large_text", it) }
                    smallImage?.let { put("small_image", it) }
                    activity.assets.smallText?.let { put("small_text", it) }
                }
            }

            if (activity.buttons.isNotEmpty()) {
                putJsonArray("buttons") {
                    activity.buttons.take(MAX_BUTTONS).forEach { add(JsonPrimitive(it.label)) }
                }
                putJsonObject("metadata") {
                    putJsonArray("button_urls") {
                        activity.buttons.take(MAX_BUTTONS).forEach { add(JsonPrimitive(it.url)) }
                    }
                }
            }

            put("platform", activity.platform)
        }

        return buildJsonObject {
            putJsonArray("activities") { add(activityJson) }
            put("afk", false)
            put("since", JsonNull)
            put("status", activity.onlineStatus.wireValue)
        }
    }

    companion object {
        private const val MAX_SEND_ATTEMPTS = 2
        private const val MAX_BUTTONS = 2
    }
}
