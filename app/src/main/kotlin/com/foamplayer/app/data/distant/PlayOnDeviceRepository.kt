package com.foamplayer.app.data.distant

import com.foamplayer.app.audio.cast.ChromecastController
import com.foamplayer.app.audio.device.AudioOutputDevice
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.ui.modals.PlayOnDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

/**
 * Assembles the "Play on" target list: the always-present local playback, every
 * Bluetooth output currently attached to the system (live through
 * [OutputDeviceManager]'s audio-device callback), the Chromecasts discovered on
 * the LAN by [ChromecastController], and the controllable remote players of the
 * configured media servers (Jellyfin's remote-control session API: the server
 * plays the queue on its device, no audio flows through this app).
 *
 * Discovery only runs while the sheet is open (see [startChromecastDiscovery]),
 * so a closed sheet costs no multicast traffic.
 *
 * UPnP/DLNA renderers need their own discovery protocol; they join this list
 * once that lands.
 */
@Singleton
class PlayOnDeviceRepository @Inject constructor(
    private val outputDeviceManager: OutputDeviceManager,
    private val distantRepository: DistantRepository,
    private val chromecastController: ChromecastController
) {

    /** Latest remote-session snapshot; refreshed only while [sessionPoll] is collected. */
    private val remoteSessions = MutableStateFlow<List<PlayOnDevice>>(emptyList())

    /** Latest polled server sessions (id + server), kept for synchronous resolution. */
    @Volatile
    var lastPolledSessions: List<DistantRepository.DistantRemoteSession> = emptyList()
        private set

    /** True while the LAN is being scanned for Chromecasts. */
    val scanningForChromecasts: StateFlow<Boolean> = chromecastController.scanning

    /** Every selectable target, in display order: this device first, then the rest. */
    val devices: Flow<List<PlayOnDevice>> =
        combine(
            outputDeviceManager.devices,
            remoteSessions.asStateFlow(),
            chromecastController.devices
        ) { outputs, remotes, casts ->
            listOf(PlayOnDevice.ThisDevice) +
                outputs.toBluetoothTargets() +
                casts.map { PlayOnDevice.chromecast(name = it.name, id = it.id) } +
                remotes
        }.distinctUntilChanged()

    /** Opens the Chromecast scan (called when the "Play on" sheet appears). */
    fun startChromecastDiscovery() = chromecastController.startDiscovery()

    /** Closes the Chromecast scan (called when the "Play on" sheet goes away). */
    fun stopChromecastDiscovery() = chromecastController.stopDiscovery()

    /** True while a Chromecast session is live; the sheet then shows it as the selection. */
    val chromecastDeviceId: StateFlow<String?> = chromecastController.connectedDeviceId

    /**
     * Polls the configured servers for controllable sessions at [POLL_MS] and
     * publishes them. Collected only while the "Play on" sheet is up: one GET per
     * configured server per tick, nothing while the sheet is closed. Sessions
     * vanish from the sheet ~[POLL_MS] after they go inactive.
     */
    val sessionPoll: Flow<Unit> = flow {
        while (true) {
            val sessions: List<DistantRepository.DistantRemoteSession> =
                try {
                    distantRepository.listRemoteSessions()
                } catch (e: Exception) {
                    emptyList()
                }
            lastPolledSessions = sessions
            remoteSessions.value = sessions.map { remote ->
                PlayOnDevice.remotePlayer(
                    sessionId = remote.session.id,
                    serverId = remote.server.id,
                    serverType = remote.server.type.displayName,
                    deviceName = remote.session.deviceName
                )
            }
            emit(Unit)
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    /** Resolves a "remote:" target id against the last poll, or null when stale/unknown. */
    fun remoteSessionFor(id: String): DistantRepository.DistantRemoteSession? {
        val parts = id.split(':', limit = 4)
        if (parts.size != 4 || parts[0] != "remote") return null
        val (_, serverId, sessionId) = parts
        return lastPolledSessions.firstOrNull {
            it.server.id == serverId && it.session.id == sessionId
        }
    }

    /** Commands [remote]'s device to play the server [itemIds] now; result per success. */
    suspend fun remotePlay(remote: DistantRepository.DistantRemoteSession, itemIds: List<String>): Boolean =
        runCatching { distantRepository.remotePlay(remote, itemIds) }.isSuccess

    private fun List<AudioOutputDevice>.toBluetoothTargets(): List<PlayOnDevice> =
        filter { it.isBluetooth }
            .map { output ->
                // id IS the AudioOutputDevice.id, not a re-derived key: the routing
                // layer resolves picks against its own enumeration.
                PlayOnDevice.bluetooth(name = output.name, id = output.id)
            }

    companion object {
        const val POLL_MS = 5_000L
    }
}
