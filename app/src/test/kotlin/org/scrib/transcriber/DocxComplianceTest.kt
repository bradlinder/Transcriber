package org.scrib.transcriber

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class DocxComplianceTest {

    @Test
    fun testDocxZipContainerStructure() {
        val testDocx = File.createTempFile("test_transcript_", ".docx")
        testDocx.deleteOnExit()

        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 5000, text = "Okay. Ready? Okay, Mr. Robbie Gilbert."),
            TranscriptSegment(startMs = 5000, endMs = 12000, text = "Tell me, when people think about animation history, whose names usually come to mind for the general public?")
        )

        DocxExporter.exportToDocx(testDocx, "261013_145659_Tr1.WAV", sampleSegments, wordThreshold = 20)

        assertTrue("Generated .docx file must exist", testDocx.exists())
        assertTrue("Generated .docx file size must be > 1 KB", testDocx.length() > 1000L)

        // Verify ZIP container structure and End of Central Directory (EOCD) header
        ZipFile(testDocx).use { zip ->
            assertNotNull("[Content_Types].xml must exist in .docx container", zip.getEntry("[Content_Types].xml"))
            assertNotNull("_rels/.rels must exist in .docx container", zip.getEntry("_rels/.rels"))
            assertNotNull("word/_rels/document.xml.rels must exist in .docx container", zip.getEntry("word/_rels/document.xml.rels"))
            assertNotNull("word/document.xml must exist in .docx container", zip.getEntry("word/document.xml"))
        }

        println("DOCX compliance test passed! Container size: ${testDocx.length()} bytes")
    }

    @Test
    fun testDocxColorizedSpeakerLabels() {
        val testDocx = File.createTempFile("test_transcript_colored_", ".docx")
        testDocx.deleteOnExit()

        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 5000, text = "Hello everyone, welcome to the interview.", speaker = "Alice"),
            TranscriptSegment(startMs = 5500, endMs = 12000, text = "Thank you Alice, great to be here.", speaker = "Bob")
        )

        DocxExporter.exportToDocx(
            destFile = testDocx,
            title = "Interview Transcript",
            segments = sampleSegments,
            wordThreshold = 20,
            showTimestamps = true,
            showSpeakers = true,
            colorSpeakers = true
        )

        assertTrue(testDocx.exists())

        ZipFile(testDocx).use { zip ->
            val docEntry = zip.getEntry("word/document.xml")
            assertNotNull(docEntry)
            val docXml = zip.getInputStream(docEntry).bufferedReader(Charsets.UTF_8).readText()

            // Verify timestamp has muted gray 787878
            assertTrue("Timestamp should have muted color 787878", docXml.contains("<w:color w:val=\"787878\"/>"))

            // Verify Alice gets first RTVS3 color (Sapphire Blue 1A56DB)
            assertTrue("Speaker Alice should be colored with 1A56DB", docXml.contains("1A56DB"))
            assertTrue("Alice label should exist", docXml.contains("Alice: "))

            // Verify Bob gets second RTVS3 color (Rose Crimson BE123C)
            assertTrue("Speaker Bob should be colored with BE123C", docXml.contains("BE123C"))
            assertTrue("Bob label should exist", docXml.contains("Bob: "))
        }
    }

    @Test
    fun testDocxUncolorizedSpeakerLabels() {
        val testDocx = File.createTempFile("test_transcript_uncolored_", ".docx")
        testDocx.deleteOnExit()

        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 5000, text = "Hello everyone.", speaker = "Alice"),
            TranscriptSegment(startMs = 5500, endMs = 12000, text = "Thank you Alice.", speaker = "Bob")
        )

        DocxExporter.exportToDocx(
            destFile = testDocx,
            title = "Interview Transcript",
            segments = sampleSegments,
            wordThreshold = 20,
            showTimestamps = true,
            showSpeakers = true,
            colorSpeakers = false
        )

        assertTrue(testDocx.exists())

        ZipFile(testDocx).use { zip ->
            val docEntry = zip.getEntry("word/document.xml")
            assertNotNull(docEntry)
            val docXml = zip.getInputStream(docEntry).bufferedReader(Charsets.UTF_8).readText()

            // Alice and Bob should have bold speaker runs without color tags
            assertTrue("Alice label should exist", docXml.contains("Alice: "))
            assertTrue("Bob label should exist", docXml.contains("Bob: "))
            assertFalse("Speaker Alice should not have 1A56DB when colorSpeakers is false", docXml.contains("1A56DB"))
            assertFalse("Speaker Bob should not have BE123C when colorSpeakers is false", docXml.contains("BE123C"))
        }
    }
}
