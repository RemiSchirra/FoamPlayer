package com.foamplayer.app.audio.engine

import com.foamplayer.app.data.db.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the session-snapshot projection: auxiliary entries are dropped and the index follows. */
class QueueSnapshotTest {

    private fun track(id: Long): TrackEntity = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        genre = null,
        year = null,
        trackNumber = null,
        discNumber = null,
        duration = 180_000L,
        bitrate = 320,
        sampleRate = 44_100,
        bitDepth = 16,
        channels = 2,
        format = "mp3",
        path = "/music/$id.mp3",
        size = 1_000L,
        albumArtPath = null
    )

    @Test
    fun `auxiliary entries are not persisted`() {
        val queue = listOf(track(1), track(2), track(3), track(4))
        val state = PlaybackState(
            queue = queue,
            queueEntryIds = listOf(1L, 2L, 3L, 4L),
            queueIndex = 3,
            auxiliaryFlags = listOf(false, true, false, false),
            queueSourceFlags = listOf(true, false, true, false)
        )

        val snapshot = QueueSnapshot.from(state)

        assertEquals(listOf(1L, 3L, 4L), snapshot.trackIds)
        assertEquals(listOf(true, true, false), snapshot.sourceFlags)
    }

    @Test
    fun `saved index counts main entries only`() {
        val queue = listOf(track(1), track(2), track(3), track(4))
        val state = PlaybackState(
            queue = queue,
            queueEntryIds = listOf(1L, 2L, 3L, 4L),
            queueIndex = 3,
            auxiliaryFlags = listOf(false, true, false, false)
        )

        // Two main entries precede index 3, so the restored index is 2.
        assertEquals(2, QueueSnapshot.from(state).currentIndex)
    }

    @Test
    fun `a playing auxiliary entry keeps the preceding main count`() {
        val queue = listOf(track(1), track(2), track(3))
        val state = PlaybackState(
            queue = queue,
            queueEntryIds = listOf(1L, 2L, 3L),
            queueIndex = 1,
            auxiliaryFlags = listOf(false, true, false)
        )

        val snapshot = QueueSnapshot.from(state)

        assertEquals(listOf(1L, 3L), snapshot.trackIds)
        assertEquals(1, snapshot.currentIndex)
    }

    @Test
    fun `a missing flag list is treated like a main entry`() {
        val state = PlaybackState(
            queue = listOf(track(1), track(2)),
            queueEntryIds = listOf(1L, 2L),
            queueIndex = 1,
            auxiliaryFlags = emptyList(),
            queueSourceFlags = emptyList()
        )

        val snapshot = QueueSnapshot.from(state)

        assertEquals(listOf(1L, 2L), snapshot.trackIds)
        assertEquals(listOf(false, false), snapshot.sourceFlags)
        assertEquals(1, snapshot.currentIndex)
    }

    @Test
    fun `an empty queue produces an empty snapshot`() {
        val snapshot = QueueSnapshot.from(PlaybackState())

        assertTrue(snapshot.trackIds.isEmpty())
        assertEquals(0, snapshot.currentIndex)
    }
}
