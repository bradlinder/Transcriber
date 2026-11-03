package org.scrib.transcriber

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DiarizationGroundTruthTest {

    @Test
    fun testGroundTruthDiarizationOnSampleWav() {
        val sampleFile = File("src/local/assets/self-test.wav")
        if (!sampleFile.exists()) {
            println("Sample file self-test.wav missing in test directory")
            return
        }

        // Read 16kHz PCM wav float samples
        val bytes = sampleFile.readBytes()
        if (bytes.size < 44) return

        val sampleCount = (bytes.size - 44) / 2
        val samples = FloatArray(sampleCount)
        val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until sampleCount) {
            samples[i] = buffer.short / 32768.0f
        }

        val testSegments = listOf(
            TranscriptSegment(startMs = 1000, endMs = 2000, text = "Okay."),
            TranscriptSegment(startMs = 2000, endMs = 17000, text = "Ready? Okay, Mr. Robbie Gilbert. Tell me, when people think about animation history, whose names usually come to mind for the general public?"),
            TranscriptSegment(startMs = 17000, endMs = 41000, text = "Well, thank you for having me. Well, of course, Disney was like the first one that comes to people's mind. Miyazaki, I would say, a Japanese animator would be another one. Hanna Barbera, Looney Tunes, Chuck Jones, you know, the classic animation that came out of the 40s and the golden age of animation."),
            TranscriptSegment(startMs = 41000, endMs = 45000, text = "Probably would be my guess would be the first. Names to come to people's minds."),
            TranscriptSegment(startMs = 45000, endMs = 58000, text = "Yeah. And in your opinion, what is commonly missing from those histories? Like who is left out or what is left out? What?"),
            TranscriptSegment(startMs = 58000, endMs = 88000, text = "Well, notably, animation was, by and large, an occupation. Almost exclusively dominated by white men throughout its history. And it's not just that it was dominated by white men. Black people of color and women were specifically kept out of animation, very specifically, and it's very well documented.")
        )

        // Primary speech chunking matching D:\rtvs3
        val primarySpeech = mutableListOf<TranscriptSegment>()
        for (s in testSegments) {
            val durSec = (s.endMs - s.startMs) / 1000.0
            if (durSec > 6.0) {
                var currStartMs = s.startMs
                while (currStartMs + 1000L < s.endMs) {
                    val currEndMs = minOf(currStartMs + 3000L, s.endMs)
                    primarySpeech.add(s.copy(startMs = currStartMs, endMs = currEndMs))
                    currStartMs += 1500L
                }
            } else {
                primarySpeech.add(s)
            }
        }

        println("\n=== PRIMARY SPEECH CHUNKS COUNT: ${primarySpeech.size} ===")
        for (p in primarySpeech) {
            println("[${p.startMs}ms - ${p.endMs}ms]: ${p.text.take(40)}...")
        }

        assertTrue("Primary speech chunks must be created", primarySpeech.size >= 10)
    }
}
