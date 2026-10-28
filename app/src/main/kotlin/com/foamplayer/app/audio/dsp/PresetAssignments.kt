package com.foamplayer.app.audio.dsp

import org.json.JSONObject

/**
 * How an output device is classified for preset assignment. Four coarse kinds
 * (not per-device ids): the same EQ preset follows the phone speaker, every
 * Bluetooth output, any DAC/wired route and any Chromecast receiver.
 */
enum class AssignedDeviceKind(val key: String) {
    SPEAKER("speaker"),
    BLUETOOTH("bluetooth"),
    USB_DAC("usb"),
    CHROMECAST("chromecast");

    companion object {
        fun fromKey(key: String): AssignedDeviceKind? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * EQ preset assignments per playback target. The auto-switcher resolves in
 * priority order — track > album > playlist > output-device kind — and falls
 * back to [lastManualPresetName] (the preset the user last picked by hand)
 * when nothing is assigned to what is currently playing.
 *
 * Every map points a target at a preset NAME: a deleted preset simply stops
 * resolving, and a renamed preset is remapped by [DspEngine.renameUserPreset].
 *
 * Stored as JSON in the settings DataStore: album names are arbitrary text,
 * so the DSP chain's compact separator-based format is not safe here.
 */
data class PresetAssignments(
    /** Track id → preset name. Always prioritary over every other target. */
    val trackPresets: Map<Long, String> = emptyMap(),
    /** Album name → preset name. */
    val albumPresets: Map<String, String> = emptyMap(),
    /** Playlist id → preset name. Resolved only while playing from that playlist. */
    val playlistPresets: Map<Long, String> = emptyMap(),
    /** Device kind → preset name. */
    val devicePresets: Map<AssignedDeviceKind, String> = emptyMap(),
    /** The last preset the user applied by hand; the auto-switch revert target. */
    val lastManualPresetName: String? = null
) {
    /** True when [presetName] is assigned to [target] (the assign-modal checklist). */
    fun isAssigned(target: AssignedTarget, presetName: String): Boolean = when (target) {
        is AssignedTarget.Track -> trackPresets[target.trackId] == presetName
        is AssignedTarget.Album -> albumPresets[target.album] == presetName
        is AssignedTarget.Playlist -> playlistPresets[target.playlistId] == presetName
        is AssignedTarget.Device -> devicePresets[target.kind] == presetName
    }

    /** Copy with [presetName] (re)assigned to [target]; null removes the assignment. */
    fun withAssignment(target: AssignedTarget, presetName: String?): PresetAssignments =
        when (target) {
            is AssignedTarget.Track -> copy(trackPresets = withEntry(trackPresets, target.trackId, presetName))
            is AssignedTarget.Album -> copy(albumPresets = withEntry(albumPresets, target.album, presetName))
            is AssignedTarget.Playlist -> copy(playlistPresets = withEntry(playlistPresets, target.playlistId, presetName))
            is AssignedTarget.Device -> copy(devicePresets = withEntry(devicePresets, target.kind, presetName))
        }

    /** Copy with every assignment to [presetName] removed (preset deleted / renamed away). */
    fun withoutPreset(presetName: String): PresetAssignments = copy(
        trackPresets = trackPresets.filterValues { it != presetName },
        albumPresets = albumPresets.filterValues { it != presetName },
        playlistPresets = playlistPresets.filterValues { it != presetName },
        devicePresets = devicePresets.filterValues { it != presetName },
        lastManualPresetName = lastManualPresetName?.takeIf { it != presetName }
    )

    /** Copy with every assignment to [oldName] remapped to [newName] (preset renamed). */
    fun withRenamedPreset(oldName: String, newName: String): PresetAssignments = copy(
        trackPresets = trackPresets.mapValues { if (it.value == oldName) newName else it.value },
        albumPresets = albumPresets.mapValues { if (it.value == oldName) newName else it.value },
        playlistPresets = playlistPresets.mapValues { if (it.value == oldName) newName else it.value },
        devicePresets = devicePresets.mapValues { if (it.value == oldName) newName else it.value },
        lastManualPresetName = lastManualPresetName?.let { if (it == oldName) newName else it }
    )

    // plumbing

    private fun <K> withEntry(map: Map<K, String>, key: K, presetName: String?): Map<K, String> =
        if (presetName == null) map - key else map + (key to presetName)

    // serialize

    fun serialize(): String = JSONObject().apply {
        put(KEY_TRACKS, trackPresets.toNamedJson())
        put(KEY_ALBUMS, albumPresets.toNamedJson())
        put(KEY_PLAYLISTS, playlistPresets.toNamedJson())
        put(KEY_DEVICES, JSONObject().apply {
            devicePresets.forEach { (kind, name) -> put(kind.key, name) }
        })
        lastManualPresetName?.let { put(KEY_LAST_MANUAL, it) }
    }.toString()

    companion object {
        private const val KEY_TRACKS = "tracks"
        private const val KEY_ALBUMS = "albums"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_DEVICES = "devices"
        private const val KEY_LAST_MANUAL = "lastManual"

        private fun Map<*, String>.toNamedJson(): JSONObject = JSONObject().apply {
            forEach { (id, name) -> put(id.toString(), name) }
        }

        private fun JSONObject.toIdNameMap(): Map<Long, String> {
            val result = mutableMapOf<Long, String>()
            for (key in keys()) {
                val id = key.toLongOrNull() ?: continue
                optString(key).takeIf { it.isNotEmpty() }?.let { result[id] = it }
            }
            return result
        }

        private fun JSONObject.toStringNameMap(): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (key in keys()) {
                optString(key).takeIf { it.isNotEmpty() }?.let { result[key] = it }
            }
            return result
        }

        fun deserialize(raw: String?): PresetAssignments {
            if (raw.isNullOrBlank()) return PresetAssignments()
            return try {
                val json = JSONObject(raw)
                val devices = mutableMapOf<AssignedDeviceKind, String>()
                json.optJSONObject(KEY_DEVICES)?.let { jsonDevices ->
                    for (key in jsonDevices.keys()) {
                        AssignedDeviceKind.fromKey(key)?.let { kind ->
                            jsonDevices.optString(key).takeIf { it.isNotEmpty() }
                                ?.let { devices[kind] = it }
                        }
                    }
                }
                PresetAssignments(
                    trackPresets = json.optJSONObject(KEY_TRACKS)?.toIdNameMap().orEmpty(),
                    albumPresets = json.optJSONObject(KEY_ALBUMS)?.toStringNameMap().orEmpty(),
                    playlistPresets = json.optJSONObject(KEY_PLAYLISTS)?.toIdNameMap().orEmpty(),
                    devicePresets = devices,
                    lastManualPresetName = json.optString(KEY_LAST_MANUAL).takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                // Defensive: a malformed payload can never break the audio path.
                PresetAssignments()
            }
        }
    }
}

/**
 * One assignment row of the assign modal. Sealed so the checklist can toggle
 * any target without four separate callback shapes.
 */
sealed class AssignedTarget {
    data class Track(val trackId: Long) : AssignedTarget()
    data class Album(val album: String) : AssignedTarget()
    data class Playlist(val playlistId: Long) : AssignedTarget()
    data class Device(val kind: AssignedDeviceKind) : AssignedTarget()
}
