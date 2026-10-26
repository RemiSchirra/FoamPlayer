package com.foamplayer.app.ui.modals

/** How a "Play on" target streams the music. */
enum class PlayOnDeviceType {
    /** Local playback on this phone/tablet (speaker, wired, USB...). */
    THIS_DEVICE,

    /** Bluetooth output attached to this device (headphones, car, speaker). */
    BLUETOOTH,

    /** Cast to a Chromecast or speaker group. */
    CHROMECAST,

    /** Remote player driven through a media server (Jellyfin remote play). */
    REMOTE_PLAYER,

    /** UPnP/DLNA renderer. */
    UPNP
}

/** One selectable playback target of the "Play on" sheet. */
data class PlayOnDevice(
    val id: String,
    val type: PlayOnDeviceType,
    /** Discovered devices carry their own friendly name; built-ins resolve a localized one. */
    val title: String? = null,
    /** Media-server name of a [PlayOnDeviceType.REMOTE_PLAYER] (Plex, Jellyfin...). */
    val serverName: String? = null
) {
    companion object {
        /** Matches [OutputDeviceManager.ThisDevicePickId] (UI and routing agree on this id). */
        const val ThisDeviceId = "this_device"

        /** The always-present local playback target. */
        val ThisDevice = PlayOnDevice(id = ThisDeviceId, type = PlayOnDeviceType.THIS_DEVICE)

        /** [id] must be the matching [com.foamplayer.app.audio.device.AudioOutputDevice.id]. */
        fun bluetooth(name: String, id: String) = PlayOnDevice(
            id = id,
            type = PlayOnDeviceType.BLUETOOTH,
            title = name
        )

        /** A remote player driven through a media server (Jellyfin remote play). */
        fun remotePlayer(
            sessionId: String,
            serverId: String,
            serverType: String,
            deviceName: String
        ) = PlayOnDevice(
            id = "remote:$serverType:$serverId:$sessionId",
            type = PlayOnDeviceType.REMOTE_PLAYER,
            title = deviceName,
            serverName = serverType
        )

        /** [id] is the Chromecast route id: the key the Cast SDK resolves to a session. */
        fun chromecast(name: String, id: String) = PlayOnDevice(
            id = "chromecast:$id",
            type = PlayOnDeviceType.CHROMECAST,
            title = name
        )

        fun upnp(name: String, address: String) = PlayOnDevice(
            id = "upnp:$address:$name",
            type = PlayOnDeviceType.UPNP,
            title = name
        )
    }
}
