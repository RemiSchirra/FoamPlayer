package com.foamplayer.app.ui.screens.lyrics

import com.foamplayer.app.lyrics.LyricsEntry
import com.foamplayer.app.lyrics.WordTimestamp
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure lyrics builders: line timing derivation, karaoke syllable timing, the
 * translation carry-over, and the CJK wrapping/romanization split.
 */
class LyricsKaraokeBuilderTest {

    // ------------------------------------------------------------ plain lines

    @Test
    fun `line end is derived from the next line`() {
        val synced = buildSyncedLyrics(
            entries = listOf(LyricsEntry(0L, "first"), LyricsEntry(2_000L, "second")),
            romanizationMap = emptyMap()
        )

        assertEquals(2, synced.lines.size)
        assertEquals(0, synced.lines[0].start)
        assertEquals(1_999, synced.lines[0].end)
        assertEquals(2_000, synced.lines[1].start)
        // The last line has no successor: it runs for the default four seconds.
        assertEquals(6_000, synced.lines[1].end)
    }

    @Test
    fun `a long instrumental gap caps the line at four seconds`() {
        val synced = buildSyncedLyrics(
            entries = listOf(LyricsEntry(0L, "first"), LyricsEntry(5_000L, "second")),
            romanizationMap = emptyMap()
        )

        assertEquals(4_000, synced.lines[0].end)
    }

    @Test
    fun `instrumental blank and negative-time entries are skipped`() {
        val synced = buildSyncedLyrics(
            entries = listOf(
                LyricsEntry(-1L, "negative"),
                LyricsEntry(0L, "music", isInstrumental = true),
                LyricsEntry(1_000L, "   ")
            ),
            romanizationMap = emptyMap()
        )

        assertTrue(synced.lines.isEmpty())
    }

    @Test
    fun `an empty entry list yields empty lyrics`() {
        assertTrue(buildSyncedLyrics(emptyList(), emptyMap()).lines.isEmpty())
    }

    @Test
    fun `provider translation is carried onto the line`() {
        val synced = buildSyncedLyrics(
            entries = listOf(
                LyricsEntry(0L, "hello", providerTranslationText = "  bonjour  ")
            ),
            romanizationMap = emptyMap()
        )

        assertEquals("bonjour", (synced.lines.single() as SyncedLine).translation)
    }

    @Test
    fun `a translation identical to the line is dropped`() {
        val synced = buildSyncedLyrics(
            entries = listOf(LyricsEntry(0L, "hello", providerTranslationText = "HELLO")),
            romanizationMap = emptyMap()
        )

        assertEquals(null, (synced.lines.single() as SyncedLine).translation)
    }

    // -------------------------------------------------------- word-synced lines

    @Test
    fun `word timings are converted to milliseconds`() {
        val synced = buildSyncedLyrics(
            entries = listOf(
                LyricsEntry(
                    time = 1_000L,
                    text = "hello world",
                    words = listOf(
                        WordTimestamp("hello", startTime = 1.0, endTime = 1.5),
                        WordTimestamp("world", startTime = 1.6, endTime = 2.0)
                    )
                )
            ),
            romanizationMap = emptyMap()
        )

        val line = synced.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(listOf("hello", "world"), line.syllables.map { it.content })
        assertEquals(listOf(1_000, 1_600), line.syllables.map { it.start })
        assertEquals(listOf(1_500, 2_000), line.syllables.map { it.end })
        assertEquals(1_000, line.start)
        assertEquals(2_000, line.end)
    }

    @Test
    fun `the v2 agent right-aligns the syllables`() {
        val synced = buildSyncedLyrics(
            entries = listOf(
                LyricsEntry(
                    time = 0L,
                    text = "word",
                    words = listOf(WordTimestamp("word", 0.0, 0.5)),
                    agent = "v2"
                )
            ),
            romanizationMap = emptyMap()
        )

        assertEquals(
            KaraokeAlignment.End,
            (synced.lines.single() as KaraokeLine.MainKaraokeLine).alignment
        )
    }

    @Test
    fun `background words become an accompaniment line`() {
        val synced = buildSyncedLyrics(
            entries = listOf(
                LyricsEntry(
                    time = 0L,
                    text = "main",
                    words = listOf(
                        WordTimestamp("main", 0.0, 0.8),
                        WordTimestamp("echo", 0.2, 0.6, isBackground = true)
                    )
                )
            ),
            romanizationMap = emptyMap()
        )

        val line = synced.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(1, line.syllables.size)
        assertEquals(1, line.accompanimentLines?.size)
        assertEquals("echo", line.accompanimentLines!!.single().syllables.single().content)
    }

    @Test
    fun `an empty word list for the main line reuses all words`() {
        val synced = buildSyncedLyrics(
            entries = listOf(
                LyricsEntry(
                    time = 0L,
                    text = "only background",
                    words = listOf(WordTimestamp("echo", 0.0, 0.5, isBackground = true))
                )
            ),
            romanizationMap = emptyMap()
        )

        val line = synced.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(1, line.syllables.size)
        assertEquals(null, line.accompanimentLines)
    }

    // ------------------------------------------------------ romanized wrapping

    @Test
    fun `a romanized plain line is split into wrapping karaoke syllables`() {
        val synced = buildSyncedLyrics(
            entries = listOf(LyricsEntry(1_000L, "hello world")),
            romanizationMap = mapOf(0 to listOf("ni hao"))
        )

        val line = synced.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(listOf("hello ", "world"), line.syllables.map { it.content })
        assertEquals(listOf("ni", "hao"), line.syllables.map { it.phonetic })
        // The only line runs 1000..5000, split evenly across the two units.
        assertEquals(listOf(1_000, 3_000), line.syllables.map { it.start })
        assertEquals(listOf(3_000, 5_000), line.syllables.map { it.end })
    }

    @Test
    fun `wrapping units split latin text on whitespace`() {
        assertEquals(listOf("Hello ", "world!"), "Hello world!".toLyricsWrappingUnits())
    }

    @Test
    fun `wrapping units split each CJK grapheme`() {
        assertEquals(listOf("你", "好", "世", "界"), "你好世界".toLyricsWrappingUnits())
    }

    @Test
    fun `wrapping units keep mixed scripts word-based around CJK`() {
        assertEquals(listOf("abc ", "你", "好", " ", "def"), "abc 你好 def".toLyricsWrappingUnits())
    }

    @Test
    fun `an empty string has no wrapping units`() {
        assertTrue("".toLyricsWrappingUnits().isEmpty())
    }

    // ------------------------------------------------------------ TTML gate

    @Test
    fun `a TTML document is detected`() {
        assertTrue(
            isPotentiallyTtml(
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p>hi</p></body></tt>"
            )
        )
    }

    @Test
    fun `plain lyrics are not treated as TTML`() {
        assertFalse(isPotentiallyTtml("[00:01.00]hello"))
    }

    @Test
    fun `a late angle bracket skips the TTML check`() {
        val padded = " ".repeat(200) + "<tt xmlns=\"http://www.w3.org/ns/ttml\"></tt>"

        assertFalse(isPotentiallyTtml(padded))
    }

    @Test
    fun `plain text containing markup is not TTML`() {
        assertFalse(isPotentiallyTtml("hello <b>world</b>"))
    }
}
