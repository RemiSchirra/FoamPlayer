package com.foamplayer.app.ui.screens.lyrics

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure line lookup behind the lyric auto-scroll: which line owns a playback time, and
 * which time is focused so a finished line stops pulling the list forward.
 */
class LyricsFocusScrollingTest {

    private fun lyrics(vararg spans: Pair<Int, Int>): SyncedLyrics =
        SyncedLyrics(
            lines = spans.mapIndexed { index, (start, end) ->
                SyncedLine(content = "line $index", translation = null, start = start, end = end)
            }
        )

    private val sample = lyrics(0 to 1_000, 2_000 to 3_000, 4_000 to 5_000)

    // ------------------------------------------------------- line index lookup

    @Test
    fun `returns -1 before the first line starts`() {
        assertEquals(-1, sample.findLastStartedLineIndex(-1))
    }

    @Test
    fun `returns the line that started last`() {
        assertEquals(0, sample.findLastStartedLineIndex(0))
        assertEquals(0, sample.findLastStartedLineIndex(1_500))
        assertEquals(1, sample.findLastStartedLineIndex(2_000))
        assertEquals(2, sample.findLastStartedLineIndex(4_500))
    }

    @Test
    fun `returns the last line after every line started`() {
        assertEquals(2, sample.findLastStartedLineIndex(60_000))
    }

    @Test
    fun `an empty lyrics list has no started line`() {
        assertEquals(-1, SyncedLyrics(emptyList()).findLastStartedLineIndex(1_000))
    }

    @Test
    fun `lines that share a start resolve to the last of them`() {
        val overlapping = lyrics(0 to 500, 0 to 800)

        assertEquals(1, overlapping.findLastStartedLineIndex(0))
    }

    // -------------------------------------------------------- focus position

    @Test
    fun `a time inside a line focuses the same time`() {
        assertEquals(500, sample.positionForStableLineFocus(500))
    }

    @Test
    fun `a time after a line end focuses that line's last moment`() {
        // Between line 0 (ends 1000) and line 1 (starts 2000): hold line 0 at its end.
        assertEquals(999, sample.positionForStableLineFocus(1_500))
        assertEquals(2_999, sample.positionForStableLineFocus(3_500))
    }

    @Test
    fun `a time before the first line is passed through`() {
        assertEquals(-250, sample.positionForStableLineFocus(-250))
    }

    @Test
    fun `a time past the last line focuses its last moment`() {
        assertEquals(4_999, sample.positionForStableLineFocus(90_000))
    }

    @Test
    fun `empty lyrics pass the time through`() {
        assertEquals(1_234, SyncedLyrics(emptyList()).positionForStableLineFocus(1_234))
    }

    @Test
    fun `the focused time never leaves the owning line`() {
        for (time in -100..6_000 step 25) {
            val focused = sample.positionForStableLineFocus(time)
            val index = sample.findLastStartedLineIndex(time)
            if (index < 0) {
                assertEquals(time, focused)
                continue
            }
            val line: ISyncedLine = sample.lines[index]
            assertEquals(true, focused in line.start..line.end)
        }
    }
}
