package org.scrib.transcriber

import org.junit.Assert.*
import org.junit.Test

class TranscriptFormattingTest {

    @Test
    fun testSpeakerChangeImmediateBreakVsSameSpeakerWordThreshold() {
        val segments = listOf(
            TranscriptSegment(startMs = 0, endMs = 3000, text = "Okay, ready?", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 3000, endMs = 8000, text = "Okay, mister Robbie Gilbert.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 8000, endMs = 15000, text = "Tell me when people think about animation history, whose names usually come to mind for the general public?", speaker = "Speaker 2"),
            TranscriptSegment(startMs = 15000, endMs = 25000, text = "Well, thank you for having me.", speaker = "Speaker 3"),
            TranscriptSegment(startMs = 25000, endMs = 35000, text = "Well, of course Disney was like the first one that comes to people's mind.", speaker = "Speaker 3")
        )

        val formattedText = segments.paragraphsWithTimestamps(wordThreshold = 70)
        val paragraphs = formattedText.split("\n\n").filter { it.isNotBlank() }

        println("\n=== SPEAKER CHANGE & WORD THRESHOLD FORMATTED OUTPUT ===")
        for (p in paragraphs) {
            println("$p\n---")
        }
        println("========================================================\n")

        assertEquals("Should create 3 distinct paragraphs for 3 speaker turns", 3, paragraphs.size)
        assertTrue("Paragraph 1 must have Speaker 1", paragraphs[0].contains("[00:00] Speaker 1:"))
        assertTrue("Paragraph 2 must have Speaker 2", paragraphs[1].contains("[00:08] Speaker 2:"))
        assertTrue("Paragraph 3 must have Speaker 3", paragraphs[2].contains("[00:15] Speaker 3:"))
    }

    @Test
    fun testSentenceStitchingAcrossWindowCutoffMergesMidSentenceFragments() {
        val segments = listOf(
            TranscriptSegment(startMs = 0, endMs = 30000, text = "We are the garden state after all, is something that's just a very smart", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 30000, endMs = 60000, text = "And that's for us at the New Jersey EDA, one of the areas that we're very proud of.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 60000, endMs = 90000, text = "So can you just explain to me?", speaker = "Speaker 2"),
            TranscriptSegment(startMs = 90000, endMs = 120000, text = "what a food desert is and also if that is still the appropriate term.", speaker = "Speaker 2")
        )

        val sentences = segments.toSentences()
        assertEquals("Fragments without punctuation or starting lowercase must stitch into 2 complete sentences", 2, sentences.size)
        assertEquals("Speaker 1", sentences[0].speaker)
        assertEquals("We are the garden state after all, is something that's just a very smart And that's for us at the New Jersey EDA, one of the areas that we're very proud of.", sentences[0].text)
        assertEquals("Speaker 2", sentences[1].speaker)
        assertEquals("So can you just explain to me what a food desert is and also if that is still the appropriate term.", sentences[1].text)
    }

    @Test
    fun testParagraphBreakDoesNotCutMidSentence() {
        val textWithoutPunct = (1..75).joinToString(" ") { "word$it" }
        val sentenceWithPunct = "This sentence finally finishes the thought."

        val segments = listOf(
            TranscriptSegment(startMs = 0, endMs = 20000, text = textWithoutPunct, speaker = "Speaker 1"),
            TranscriptSegment(startMs = 20000, endMs = 30000, text = sentenceWithPunct, speaker = "Speaker 1")
        )

        val formatted = segments.paragraphsWithTimestamps(wordThreshold = 50)
        val paragraphs = formatted.split("\n\n").filter { it.isNotBlank() }

        assertEquals("Must remain in 1 paragraph until natural sentence completion", 1, paragraphs.size)
        assertTrue("Paragraph must contain full text", paragraphs[0].contains("word75 This sentence finally finishes the thought."))
    }
}
