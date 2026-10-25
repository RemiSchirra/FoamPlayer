package com.foamplayer.app.lastfm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.foamplayer.lastfm.LastFmScrobble
import com.foamplayer.lastfm.LastFmTrack
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LastFmScrobbleQueueTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private fun TestScope.createQueue(): LastFmScrobbleQueue {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher()),
            produceFile = { File(tmpFolder.root, "scrobbles_${System.nanoTime()}.preferences_pb") }
        )
        return LastFmScrobbleQueue(dataStore)
    }

    private fun scrobble(artist: String, title: String, timestamp: Long) = LastFmScrobble(
        track = LastFmTrack(artist = artist, title = title),
        timestampSeconds = timestamp
    )

    @Test
    fun `enqueue keeps scrobbles in chronological order and drops duplicates`() = runTest {
        val queue = createQueue()

        queue.enqueueAll(
            listOf(
                scrobble("A", "First", 1_700_000_200),
                scrobble("B", "Second", 1_700_000_100)
            )
        )
        // Duplicate of the first listen: same track, same timestamp.
        queue.enqueue(scrobble("A", "First", 1_700_000_200))

        val pending = queue.pending.first()

        assertEquals(listOf("Second", "First"), pending.map { it.track.title })
    }

    @Test
    fun `removeAll removes only the flushed scrobbles`() = runTest {
        val queue = createQueue()
        val flushed = listOf(
            scrobble("A", "One", 1_700_000_100),
            scrobble("B", "Two", 1_700_000_200)
        )
        queue.enqueueAll(flushed + scrobble("C", "Three", 1_700_000_300))

        queue.removeAll(flushed)

        assertEquals(
            listOf("Three"),
            queue.pending.first().map { it.track.title }
        )
    }

    @Test
    fun `queue is capped at MAX_PENDING_SCROBBLES keeping the newest`() = runTest {
        val queue = createQueue()
        val many = (0 until LastFmScrobbleQueue.MAX_PENDING_SCROBBLES + 5).map { index ->
            scrobble("Artist", "Track $index", 1_700_000_000L + index)
        }

        queue.enqueueAll(many)

        val pending = queue.pending.first()
        assertEquals(LastFmScrobbleQueue.MAX_PENDING_SCROBBLES, pending.size)
        assertEquals("Track 5", pending.first().track.title)
        assertEquals(
            "Track ${LastFmScrobbleQueue.MAX_PENDING_SCROBBLES + 4}",
            pending.last().track.title
        )
        assertTrue(
            pending.zipWithNext().all { (a, b) -> a.timestampSeconds <= b.timestampSeconds }
        )
    }
}
