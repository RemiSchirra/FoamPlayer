package com.foamplayer.app.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the preset resolution priority
 * ([assignedPresetFor]): the playlist target resolves only while the
 * current track is still inside the queue source's own section — swiping
 * onto a track that is not a playlist member (while the queue keeps its
 * "playlist:" source marker) must drop the resolution, not follow it.
 */
class PresetResolverTest {

    private val assignments = PresetAssignments(
        trackPresets = mapOf(42L to "Track Preset"),
        albumPresets = mapOf("Album A" to "Album Preset"),
        playlistPresets = mapOf(7L to "Playlist Preset"),
        devicePresets = mapOf(AssignedDeviceKind.SPEAKER to "Device Preset")
    )

    private fun resolve(
        trackId: Long? = 42L,
        album: String? = "Album A",
        queueSourceId: String? = "playlist:7",
        inQueueSourceSection: Boolean = true
    ): String? = assignedPresetFor(
        PresetPlaybackContext(trackId, album, queueSourceId, inQueueSourceSection),
        assignments,
        AssignedDeviceKind.SPEAKER
    )

    @Test
    fun `track assignment wins over everything else`() {
        assertEquals("Track Preset", resolve())
    }

    @Test
    fun `album assignment wins while the track is unassigned`() {
        assertEquals("Album Preset", resolve(trackId = 99L))
    }

    @Test
    fun `playlist assignment resolves while playing from the playlist`() {
        assertEquals("Playlist Preset", resolve(trackId = 99L, album = "Album B"))
    }

    @Test
    fun `playlist assignment does not follow a swipe outside the playlist`() {
        // The queue keeps its "playlist:" source marker after swiping onto a
        // track that is not a playlist member: the playlist target must stop
        // resolving and the device target takes over instead.
        assertEquals(
            "Device Preset",
            resolve(trackId = 99L, album = "Album B", inQueueSourceSection = false)
        )
    }

    @Test
    fun `album target still wins outside the source section`() {
        assertEquals("Album Preset", resolve(trackId = 99L, inQueueSourceSection = false))
    }

    @Test
    fun `device target resolves when nothing above it matches`() {
        assertEquals(
            "Device Preset",
            resolve(trackId = 99L, album = "Album B", queueSourceId = null)
        )
    }

    @Test
    fun `nothing assigned resolves null`() {
        assertNull(
            assignedPresetFor(
                PresetPlaybackContext(trackId = 99L, album = "Album B"),
                PresetAssignments(),
                null
            )
        )
    }

    @Test
    fun `nothing playing resolves null`() {
        assertNull(
            assignedPresetFor(
                PresetPlaybackContext(),
                assignments,
                AssignedDeviceKind.SPEAKER
            )
        )
    }
}
