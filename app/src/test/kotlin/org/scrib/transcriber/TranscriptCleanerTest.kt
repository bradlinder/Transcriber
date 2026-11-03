package org.scrib.transcriber

import org.junit.Assert.*
import org.junit.Test

class TranscriptCleanerTest {

    @Test
    fun testCollapsesSingleWordStutters() {
        val input = "Yeah yeah yeah yeah yeah, that sounds completely reasonable."
        val output = TranscriptCleaner.collapseRepeatingNgrams(input)
        assertEquals("Yeah yeah, that sounds completely reasonable.", output)
    }

    @Test
    fun testCollapsesMultiWordRunawayLoops() {
        val input = "We will achieve food security. Thank you very much. Thank you very much. Thank you very much."
        val output = TranscriptCleaner.collapseRepeatingNgrams(input)
        assertEquals("We will achieve food security. Thank you very much.", output)
    }

    @Test
    fun testStripsStandardSilenceHallucinations() {
        val phrase1 = "Thanks for watching."
        val phrase2 = "Please like and subscribe to our channel."
        val phrase3 = "Subtitles by the Amara.org community"

        assertEquals("", TranscriptCleaner.stripHallucinationPhrases(phrase1))
        assertEquals("", TranscriptCleaner.stripHallucinationPhrases(phrase2))
        assertEquals("", TranscriptCleaner.stripHallucinationPhrases(phrase3))
    }

    @Test
    fun testTrimsTrailingDegenerateHallucinationTail() {
        val segments = listOf(
            TranscriptSegment(startMs = 0, endMs = 10000, text = "Thank you so much for your time today.", speaker = "Kevin"),
            TranscriptSegment(startMs = 10000, endMs = 20000, text = "You too, have a great weekend.", speaker = "Tara"),
            TranscriptSegment(startMs = 20000, endMs = 25000, text = "Thanks for watching.", speaker = "Tara"),
            TranscriptSegment(startMs = 25000, endMs = 30000, text = "Please subscribe to our channel.", speaker = "Tara")
        )

        val cleaned = TranscriptCleaner.scrubSegments(segments)

        assertEquals("Must remove trailing 2 hallucination segments", 2, cleaned.size)
        assertEquals("Thank you so much for your time today.", cleaned[0].text)
        assertEquals("You too, have a great weekend.", cleaned[1].text)
    }

    @Test
    fun testCollapsesConsecutiveIdenticalSegments() {
        val segments = listOf(
            TranscriptSegment(startMs = 0, endMs = 5000, text = "Hello world.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 5000, endMs = 10000, text = "Hello world.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 10000, endMs = 15000, text = "Continuing discussion.", speaker = "Speaker 1")
        )

        val cleaned = TranscriptCleaner.scrubSegments(segments)

        assertEquals("Must merge identical adjacent duplicate segments into one", 2, cleaned.size)
        assertEquals("Hello world.", cleaned[0].text)
        assertEquals(10000L, cleaned[0].endMs)
        assertEquals("Continuing discussion.", cleaned[1].text)
    }
}
