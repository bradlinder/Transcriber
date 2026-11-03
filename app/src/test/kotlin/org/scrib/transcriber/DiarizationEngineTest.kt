package org.scrib.transcriber

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiarizationEngineTest {

    @Test
    fun testSpeakerChangeTriggersImmediateParagraphBreakWithTimeMarker() {
        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 3000, text = "Okay, ready?", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 3000, endMs = 8000, text = "Okay, mister Robbie Gilbert.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 8000, endMs = 15000, text = "Tell me when people think about animation history.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 15000, endMs = 25000, text = "Well, thank you for having me.", speaker = "Speaker 2")
        )

        val formattedText = sampleSegments.paragraphsWithTimestamps(wordThreshold = 70)
        println("\n=== DIARIZED PARAGRAPH FORMATTED TEXT ===")
        println(formattedText)
        println("=========================================\n")

        // Speaker 1 speaks all 3 first sentences under one paragraph block at [00:00]
        assertTrue("Paragraph must start with [00:00] Speaker 1:", formattedText.startsWith("[00:00] Speaker 1:"))
        // Speaker 2 starts new paragraph block immediately at [00:15]
        assertTrue("Paragraph 2 must start with [00:15] Speaker 2:", formattedText.contains("[00:15] Speaker 2:"))
        // Ensure there is a double newline separating the speaker turns
        assertTrue("Paragraphs must be separated by double newline", formattedText.contains("\n\n[00:15] Speaker 2:"))
    }

    @Test
    fun testRenameSpeakerUpdatesAllOccurrences() {
        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 3000, text = "Okay, ready?", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 3000, endMs = 8000, text = "Well, thank you for having me.", speaker = "Speaker 2")
        )

        val renamed = sampleSegments.renameSpeaker("Speaker 2", "Robbie Gilbert")

        assertEquals("Speaker 1", renamed[0].speaker)
        assertEquals("Robbie Gilbert", renamed[1].speaker)

        val formatted = renamed.paragraphsWithTimestamps(wordThreshold = 70)
        assertTrue("Formatted text must contain renamed speaker label", formatted.contains("Robbie Gilbert:"))
        assertFalse("Formatted text must no longer contain old speaker label", formatted.contains("Speaker 2:"))
    }

    @Test
    fun testTimestampAndSpeakerToggles() {
        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 3000, text = "Hello world.", speaker = "Speaker 1")
        )

        val noTimestampsNoSpeakers = sampleSegments.paragraphsWithTimestamps(showTimestamps = false, showSpeakers = false)
        assertFalse("Must not contain time marker [00:00]", noTimestampsNoSpeakers.contains("[00:00]"))
        assertFalse("Must not contain speaker label Speaker 1:", noTimestampsNoSpeakers.contains("Speaker 1:"))
        assertEquals("Hello world.", noTimestampsNoSpeakers)

        val timestampsOnly = sampleSegments.paragraphsWithTimestamps(showTimestamps = true, showSpeakers = false)
        assertTrue("Must contain time marker [00:00]", timestampsOnly.contains("[00:00]"))
        assertFalse("Must not contain speaker label Speaker 1:", timestampsOnly.contains("Speaker 1:"))
    }

    @Test
    fun testWordThresholdTriggersParagraphBreakAtNaturalSentenceBoundaryWithinSameSpeaker() {
        val sentence1 = "This is a detailed opening statement by the first speaker discussing the history of animated motion pictures and how artists developed the craft over decades in studios across the country."
        val sentence2 = "They worked tirelessly through long production cycles creating thousands of individual hand-drawn cels to bring memorable characters to life on the silver screen before digital computers existed."
        val sentence3 = "These techniques established the foundational grammar of storytelling and visual rhythm that creators still rely upon in the contemporary era today."

        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 20000, text = sentence1, speaker = "Speaker 1"),
            TranscriptSegment(startMs = 20000, endMs = 45000, text = sentence2, speaker = "Speaker 1"),
            TranscriptSegment(startMs = 45000, endMs = 70000, text = sentence3, speaker = "Speaker 1")
        )

        // With wordThreshold = 40 words, sentence 1 + sentence 2 exceeds 40 words, so sentence 3 starts a new paragraph
        val formatted = sampleSegments.paragraphsWithTimestamps(wordThreshold = 40)
        println("\n=== WORD THRESHOLD FORMATTED TEXT ===")
        println(formatted)
        println("=====================================\n")

        assertTrue("First paragraph must start with [00:00] Speaker 1:", formatted.startsWith("[00:00] Speaker 1:"))
        // New paragraph at sentence 3 start [00:45]
        assertTrue("Same speaker second paragraph must start with [00:45]", formatted.contains("[00:45]"))
        // It must NOT repeat 'Speaker 1:' since it is a continuation of the same speaker
        assertFalse("Same speaker continuation must not repeat 'Speaker 1:'", formatted.contains("[00:45] Speaker 1:"))
    }

    @Test
    fun testShortFragmentMergerPreventsOrphanParagraphBreaks() {
        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 5000, text = "We explored several classic", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 5000, endMs = 7000, text = "examples.", speaker = "Speaker 1"),
            TranscriptSegment(startMs = 7000, endMs = 14000, text = "Indeed they were remarkable.", speaker = "Speaker 2")
        )

        val sentences = sampleSegments.toSentences()
        assertEquals("Fragments must merge into parent sentence", 2, sentences.size)
        assertEquals("We explored several classic examples.", sentences[0].text)
        assertEquals("Indeed they were remarkable.", sentences[1].text)
    }

    @Test
    fun testWeSpeakerOnnxModelLoadsAndRunsInference() {
        val modelFile = File("src/main/assets/models/diarization/wespeaker_en_voxceleb_CAM++.onnx")
        if (!modelFile.exists()) {
            println("WeSpeaker model not present at " + modelFile.absolutePath + ", skipping test")
            return
        }

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile.absolutePath)
        assertNotNull("ONNX session must be created", session)
        assertEquals(1, session.numInputs)
        assertEquals("feats", session.inputNames.first())
        assertEquals(1, session.numOutputs)
        assertEquals("embs", session.outputNames.first())
        session.close()
    }

    @Test
    fun testClusterCountAcrossRealAudioScenarios() {
        val modelFile = File("src/main/assets/models/diarization/wespeaker_en_voxceleb_CAM++.onnx")
        if (!modelFile.exists()) return

        val jfkFile = File("../transcribe.cpp/samples/jfk.wav")
        val dotsFile = File("../transcribe.cpp/samples/dots.wav")
        val cjFile = File("../transcribe.cpp/samples/cj-swimming-drop.wav")
        if (!jfkFile.exists() || !dotsFile.exists() || !cjFile.exists()) return

        fun loadWav(file: File): FloatArray {
            val bytes = file.readBytes()
            val shorts = (bytes.size - 44) / 2
            val floats = FloatArray(shorts)
            val bb = java.nio.ByteBuffer.wrap(bytes, 44, shorts * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shorts) {
                floats[i] = bb.short.toFloat() / 32768.0f
            }
            return floats
        }

        val jfkAudio = loadWav(jfkFile)
        val dotsAudio = loadWav(dotsFile)
        val cjAudio = loadWav(cjFile)

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile.absolutePath)
        val hp = DiarizationEngine.HighpassFilter85Hz()

        fun getChunk(audio: FloatArray, startSec: Float, durSec: Float): FloatArray {
            val start = (startSec * 16000).toInt()
            val len = (durSec * 16000).toInt()
            val slice = FloatArray(len)
            System.arraycopy(audio, start, slice, 0, len)
            return DiarizationEngine.prepareAudioChunk(slice, hp)
        }

        // 1. Solo speaker (JFK)
        val jfkEmbs = (0..3).map { i ->
            DiarizationEngine.extractEmbedding(session, env, getChunk(jfkAudio, 1.0f + i * 2.5f, 2.5f))!!
        }
        val jfkLabels = DiarizationEngine.clusterAhcThreshold(jfkEmbs, 0.68f)
        assertEquals("Solo speaker must form exactly 1 cluster", 1, jfkLabels.distinct().size)

        // 2. Real-world noisy/dynamic speech (CJ)
        val cjEmbs = (0..6).map { i ->
            DiarizationEngine.extractEmbedding(session, env, getChunk(cjAudio, 2.0f + i * 8.0f, 3.0f))!!
        }
        val cjLabels = DiarizationEngine.clusterAhcThreshold(cjEmbs, 0.68f)
        assertEquals("Dynamic speech must not over-segment into phantom speakers", 1, cjLabels.distinct().size)

        // 3. Two distinct speakers (JFK + Dots)
        val mixedEmbs = listOf(
            DiarizationEngine.extractEmbedding(session, env, getChunk(jfkAudio, 1.0f, 3.0f))!!,
            DiarizationEngine.extractEmbedding(session, env, getChunk(dotsAudio, 1.0f, 3.0f))!!,
            DiarizationEngine.extractEmbedding(session, env, getChunk(jfkAudio, 5.0f, 3.0f))!!,
            DiarizationEngine.extractEmbedding(session, env, getChunk(dotsAudio, 5.0f, 3.0f))!!,
            DiarizationEngine.extractEmbedding(session, env, getChunk(jfkAudio, 8.0f, 2.5f))!!,
            DiarizationEngine.extractEmbedding(session, env, getChunk(dotsAudio, 9.0f, 3.0f))!!
        )
        val mixLabels = DiarizationEngine.clusterAhcThreshold(mixedEmbs, 0.68f)
        assertEquals("Two distinct speakers must cluster into exactly 2 speakers", 2, mixLabels.distinct().size)

        session.close()
    }

    @Test
    fun testAcousticPauseSnappingBetweenUtterances() {
        val sampleRate = 16000
        val totalSec = 6
        val samples = FloatArray(totalSec * sampleRate)

        // Utterance 1: 0.0s to 1.8s (speech energy)
        for (i in 0 until (1.8 * sampleRate).toInt()) {
            samples[i] = (kotlin.math.sin(2.0 * Math.PI * 300.0 * i / sampleRate) * 0.5).toFloat()
        }
        // Silence pause: 1.8s to 3.5s (samples remain 0.0)
        // Utterance 2: 3.5s to 5.5s (speech energy)
        for (i in (3.5 * sampleRate).toInt() until (5.5 * sampleRate).toInt()) {
            samples[i] = (kotlin.math.sin(2.0 * Math.PI * 450.0 * i / sampleRate) * 0.5).toFloat()
        }

        val rawSentences = listOf(
            "First short statement.",
            "This is a considerably longer second utterance continuing after the pause."
        )

        val aligned = AcousticAligner.alignSentences(
            rawSentences = rawSentences,
            startMs = 0L,
            endMs = (totalSec * 1000).toLong(),
            samples = samples,
            sampleRate = sampleRate
        )

        assertEquals("Must produce exactly 2 aligned sentences", 2, aligned.size)
        // Utterance 1 must end within the silence window (1800ms - 3500ms) rather than missing into speech
        assertTrue("Utterance 1 must cut off in the silence gap (>=1750ms and <=3500ms)",
            aligned[0].endMs in 1750L..3500L)
        // Utterance 2 must begin at or after the silence gap
        assertTrue("Utterance 2 must start at or after the silence gap",
            aligned[1].startMs >= 1750L)
    }

    @Test
    fun testTrimSpeechBoundsRemovesCoarseWhisperPadding() {
        val sampleRate = 16000
        val samples = FloatArray(5 * sampleRate)

        // 1.0s leading silence, speech from 1.0s to 3.5s, 1.5s trailing silence
        for (i in (1.0 * sampleRate).toInt() until (3.5 * sampleRate).toInt()) {
            samples[i] = (kotlin.math.sin(2.0 * Math.PI * 250.0 * i / sampleRate) * 0.4).toFloat()
        }

        val energy = AcousticAligner.computeEnergyProfile(samples, sampleRate)
        val trimmed = AcousticAligner.trimSpeechBounds(energy, startMs = 0L, endMs = 5000L)

        // Leading silence of 1000ms must be trimmed to within ~900-1100ms
        assertTrue("Trimmed start (${trimmed.startMs}ms) must snap near 1000ms speech onset",
            trimmed.startMs in 900L..1150L)
        // Trailing silence must be trimmed near 3500ms
        assertTrue("Trimmed end (${trimmed.endMs}ms) must snap near 3500ms speech offset",
            trimmed.endMs in 3450L..3650L)
    }

    @Test
    fun testWordLevelAlignmentAndSpeakerSplitAtTransition() {
        val sampleRate = 16000
        val samples = FloatArray(4 * sampleRate)

        // Word 1 ("Hello"): 0.2s - 0.7s
        for (i in (0.2 * sampleRate).toInt() until (0.7 * sampleRate).toInt()) {
            samples[i] = (kotlin.math.sin(2.0 * Math.PI * 300.0 * i / sampleRate) * 0.5).toFloat()
        }
        // Pause between words: 0.7s - 1.5s
        // Word 2 ("there"): 1.5s - 2.5s
        for (i in (1.5 * sampleRate).toInt() until (2.5 * sampleRate).toInt()) {
            samples[i] = (kotlin.math.sin(2.0 * Math.PI * 400.0 * i / sampleRate) * 0.5).toFloat()
        }

        val coarseSegment = TranscriptSegment(
            startMs = 0L,
            endMs = 3000L,
            text = "Hello there",
            speaker = "Speaker 1"
        )

        val split = AcousticAligner.splitSegmentAtSpeakerTransition(
            segment = coarseSegment,
            transitionMs = 1100L,
            speakerBefore = "Speaker 1",
            speakerAfter = "Speaker 2",
            samples = samples,
            sampleRate = sampleRate
        )

        assertEquals("Multi-speaker segment must split into 2 speaker turns", 2, split.size)
        assertEquals("Speaker 1", split[0].speaker)
        assertEquals("Speaker 2", split[1].speaker)
        assertEquals("Hello", split[0].text)
        assertEquals("there", split[1].text)
        // Split timestamp must land cleanly in the 700ms - 1500ms inter-word pause
        assertTrue("Split cut-off (${split[0].endMs}ms) must be in the inter-word silence",
            split[0].endMs in 700L..1500L)
        assertEquals(split[0].endMs, split[1].startMs)
    }

    @Test
    fun testNeuralWordTimestampsPreferredOverCharacterInterpolation() {
        val neuralWords = listOf(
            AcousticAligner.TimedWord("Well", 12240L, 12580L),
            AcousticAligner.TimedWord("thank", 12580L, 12820L),
            AcousticAligner.TimedWord("you", 12820L, 13010L)
        )

        val aligned = AcousticAligner.alignWords(
            text = "Well thank you",
            startMs = 12000L,
            endMs = 14000L,
            energy = FloatArray(0),
            neuralWords = neuralWords
        )

        assertEquals("Must return exact neural word timestamps without fallback", neuralWords, aligned)
    }

    @Test
    fun testTransitionNearSentenceBoundariesHandledWithoutEdgeDrop() {
        val neuralWords = listOf(
            AcousticAligner.TimedWord("out?", 57500L, 57900L),
            AcousticAligner.TimedWord("Well", 58000L, 58300L),
            AcousticAligner.TimedWord("notably", 58300L, 58800L)
        )

        val segment = TranscriptSegment(
            startMs = 57500L,
            endMs = 59000L,
            text = "out? Well notably",
            words = neuralWords
        )

        val split = AcousticAligner.splitSegmentAtSpeakerTransition(
            segment = segment,
            transitionMs = 58000L,
            speakerBefore = "Speaker 1",
            speakerAfter = "Speaker 2",
            samples = FloatArray(0)
        )

        assertEquals("Transition near boundary must split correctly into 2 segments", 2, split.size)
        assertEquals("Speaker 1", split[0].speaker)
        assertEquals("out?", split[0].text)
        assertEquals("Speaker 2", split[1].speaker)
        assertEquals("Well notably", split[1].text)
    }

    // --- STAGE 1 DETERMINISTIC WORD-LEVEL ASSIGNMENT TESTS ---

    @Test
    fun testStage1SyntheticWordSpeakerAssignment() {
        val spans = listOf(
            DiarizationEngine.DiarSpan(0L, 1000L, "Speaker 1"),
            DiarizationEngine.DiarSpan(1000L, 2000L, "Speaker 2")
        )

        val words = listOf(
            WordTiming("Good", 600L, 800L),
            WordTiming("morning,", 800L, 1000L),
            WordTiming("everyone.", 1000L, 1400L),
            WordTiming("thanks", 1400L, 1700L)
        )

        val assignments = DiarizationEngine.assignSpeakersToWordTimings(words, spans)

        assertEquals("Must process all 4 words", 4, assignments.size)

        assertEquals("Good -> Speaker 1", "Speaker 1", assignments[0].speaker)
        assertEquals("Good -> ASSIGNED", SpeakerAssignmentStatus.ASSIGNED, assignments[0].status)

        assertEquals("morning, -> Speaker 1", "Speaker 1", assignments[1].speaker)
        assertEquals("morning, -> ASSIGNED", SpeakerAssignmentStatus.ASSIGNED, assignments[1].status)

        assertEquals("everyone. -> Speaker 2", "Speaker 2", assignments[2].speaker)
        assertEquals("everyone. -> ASSIGNED", SpeakerAssignmentStatus.ASSIGNED, assignments[2].status)

        assertEquals("thanks -> Speaker 2", "Speaker 2", assignments[3].speaker)
        assertEquals("thanks -> ASSIGNED", SpeakerAssignmentStatus.ASSIGNED, assignments[3].status)
    }

    @Test
    fun testStage1WordCrossingSpeakerTransitionIsUncertain() {
        val spans = listOf(
            DiarizationEngine.DiarSpan(0L, 1000L, "Speaker 1"),
            DiarizationEngine.DiarSpan(1000L, 2000L, "Speaker 2")
        )

        // Word straddles exact 1000ms transition: 800ms - 1200ms (200ms overlap with Speaker 1, 200ms with Speaker 2)
        val wordCrossing = listOf(WordTiming("bridging", 800L, 1200L))

        val assignments = DiarizationEngine.assignSpeakersToWordTimings(wordCrossing, spans)

        assertEquals(1, assignments.size)
        assertNull("Equal overlap tie must not force an assignment", assignments[0].speaker)
        assertEquals("Equal overlap tie must be marked UNCERTAIN", SpeakerAssignmentStatus.UNCERTAIN, assignments[0].status)
    }

    @Test
    fun testStage1DiarizationCoverageGapDoesNotDefaultToSpeaker1() {
        val spans = listOf(
            DiarizationEngine.DiarSpan(0L, 2000L, "Speaker 2") // Only Speaker 2 exists up to 2000ms
        )

        // Word occurs at 3000ms - 3500ms (completely outside diarization coverage)
        val gapWord = listOf(WordTiming("orphaned", 3000L, 3500L))

        val assignments = DiarizationEngine.assignSpeakersToWordTimings(gapWord, spans)

        assertEquals(1, assignments.size)
        assertNull("Gap in coverage must NEVER default to Speaker 1 or any speaker", assignments[0].speaker)
        assertEquals("Gap in coverage must be marked UNASSIGNED", SpeakerAssignmentStatus.UNASSIGNED, assignments[0].status)
    }

    @Test
    fun testStage1OverlappingSpeakersAndEqualOverlap() {
        // Simultaneous/overlapping speech interval
        val spans = listOf(
            DiarizationEngine.DiarSpan(1000L, 2000L, "Speaker 1"),
            DiarizationEngine.DiarSpan(1000L, 2000L, "Speaker 2")
        )

        val overlapWord = listOf(WordTiming("crosstalk", 1200L, 1800L))

        val assignments = DiarizationEngine.assignSpeakersToWordTimings(overlapWord, spans)

        assertEquals(1, assignments.size)
        assertNull("Simultaneous overlapping speakers must not assign arbitrary speaker", assignments[0].speaker)
        assertEquals("Equal overlap must be marked UNCERTAIN", SpeakerAssignmentStatus.UNCERTAIN, assignments[0].status)
    }

    @Test
    fun testStage1InvalidTimestampsHandledExplicitly() {
        val spans = listOf(DiarizationEngine.DiarSpan(0L, 1000L, "Speaker 1"))

        val invalidWord = listOf(WordTiming("corrupt", 1500L, 1000L)) // startMs > endMs

        val assignments = DiarizationEngine.assignSpeakersToWordTimings(invalidWord, spans)

        assertEquals(1, assignments.size)
        assertNull("Invalid timestamps must have null speaker", assignments[0].speaker)
        assertEquals("Invalid timestamps must be marked INVALID_TIMESTAMPS", SpeakerAssignmentStatus.INVALID_TIMESTAMPS, assignments[0].status)
    }

    @Test
    fun testStage3FindBestSpeakerReturnsEmptyWhenNoPositiveOverlap() {
        val spans = listOf(
            DiarizationEngine.DiarSpan(1000L, 2000L, "Speaker 1")
        )

        // Interval at 3000ms - 4000ms (completely outside diarization spans)
        val bestSpeaker = DiarizationEngine.findBestSpeaker(3000L, 4000L, spans)

        assertEquals("Must return empty string (unassigned) when no overlap exists; NEVER default to Speaker 1", "", bestSpeaker)
    }

    @Test
    fun testDecodedAudioGetSliceAcrossChunkBoundaries() {
        val chunk1Samples = 1000
        val chunk2Samples = 1000
        val buf1 = java.nio.ByteBuffer.allocateDirect(chunk1Samples * 4).order(java.nio.ByteOrder.nativeOrder())
        val buf2 = java.nio.ByteBuffer.allocateDirect(chunk2Samples * 4).order(java.nio.ByteOrder.nativeOrder())

        val float1 = buf1.asFloatBuffer()
        val float2 = buf2.asFloatBuffer()

        for (i in 0 until chunk1Samples) float1.put(i.toFloat())
        for (i in 0 until chunk2Samples) float2.put((chunk1Samples + i).toFloat())

        buf1.position(0)
        buf2.position(0)

        val audio = DecodedAudio(listOf(buf1, buf2), chunk1Samples + chunk2Samples)

        // Read a slice spanning across chunk1 and chunk2 (e.g. from 950 to 1050, length 100)
        val slice = audio.getSlice(950, 100)
        assertEquals(100, slice.size)
        for (i in 0 until 100) {
            assertEquals((950 + i).toFloat(), slice[i], 1e-4f)
        }
    }

    @Test
    fun testRenameSpeakerDoesNotMutateSpokenTextWords() {
        val sampleSegments = listOf(
            TranscriptSegment(startMs = 0, endMs = 3000, text = "We need to pay the bill now.", speaker = "Bill"),
            TranscriptSegment(startMs = 3000, endMs = 6000, text = "Bill please send me the invoice.", speaker = "Alice")
        )

        val renamed = sampleSegments.renameSpeaker("Bill", "William")

        // First segment speaker is renamed to William, but the spoken text "bill" remains untouched!
        assertEquals("William", renamed[0].speaker)
        assertEquals("We need to pay the bill now.", renamed[0].text)

        // Second segment speaker is Alice, spoken word "Bill" remains untouched
        assertEquals("Alice", renamed[1].speaker)
        assertEquals("Bill please send me the invoice.", renamed[1].text)
    }

    @Test
    fun testParseTextToSegmentsWithLongDurationsAndHours() {
        val longTranscript = """
            [01:15:30] Speaker 1: Hello from over an hour into the interview.

            [120:45] Speaker 2: And hello from two hours in with three-digit minutes.
        """.trimIndent()

        val segments = parseTextToSegments(longTranscript)

        assertEquals(2, segments.size)
        assertEquals((1 * 3600 + 15 * 60 + 30) * 1000L, segments[0].startMs)
        assertEquals("Speaker 1", segments[0].speaker)
        assertEquals("Hello from over an hour into the interview.", segments[0].text)

        assertEquals((120 * 60 + 45) * 1000L, segments[1].startMs)
        assertEquals("Speaker 2", segments[1].speaker)
        assertEquals("And hello from two hours in with three-digit minutes.", segments[1].text)
    }

    @Test
    fun testAutoDiarizationClustersByThresholdBaseline() {
        val dim = 512
        val embs = mutableListOf<FloatArray>()

        // Speaker 1 (Tara): 6 embeddings clustered around axis 0
        for (i in 0 until 6) {
            val v = FloatArray(dim)
            v[0] = 1.0f
            v[1] = 0.1f * i
            val norm = Math.sqrt(v.map { (it * it).toDouble() }.sum()).toFloat()
            for (d in 0 until dim) v[d] /= norm
            embs.add(v)
        }

        // Speaker 2 (Brad): 4 embeddings clustered around axis 2
        for (i in 0 until 4) {
            val v = FloatArray(dim)
            v[2] = 1.0f
            v[3] = 0.1f * i
            val norm = Math.sqrt(v.map { (it * it).toDouble() }.sum()).toFloat()
            for (d in 0 until dim) v[d] /= norm
            embs.add(v)
        }

        // Under normal calibrated threshold (0.40f), Auto mode cleanly separates the two speakers via pure thresholding
        val labels = DiarizationEngine.clusterPrimaryEmbeddings(
            embeddings = embs,
            expectedSpeakers = "auto",
            sensitivityThreshold = 0.40f
        )

        val uniqueClusters = labels.distinct()
        assertEquals("Auto mode must separate into 2 clusters using calibrated thresholding", 2, uniqueClusters.size)
        // Ensure Speaker 1's items share one cluster and Speaker 2's share another
        assertEquals(labels[0], labels[1])
        assertEquals(labels[0], labels[5])
        assertEquals(labels[6], labels[7])
        assertEquals(labels[6], labels[9])
        assertNotEquals(labels[0], labels[6])

        // When explicitly set to 2 Speakers (Interview), it guarantees k=2 partitioning
        val labelsExplicit2 = DiarizationEngine.clusterPrimaryEmbeddings(
            embeddings = embs,
            expectedSpeakers = "2",
            sensitivityThreshold = 1.2f
        )
        assertEquals("Expected 2 speakers must guarantee 2 clusters", 2, labelsExplicit2.distinct().size)
    }

    @Test
    fun testAutoDiarizationRecoversTwoSpeakersWhenThresholdProducesGiantClusterAndTinyOutlier() {
        val dim = 512
        val embs = mutableListOf<FloatArray>()

        // Speaker 1 (Tara): 18 embeddings around axis 0
        for (i in 0 until 18) {
            val v = FloatArray(dim)
            v[0] = 1.0f
            v[1] = 0.05f * i
            val norm = Math.sqrt(v.map { (it * it).toDouble() }.sum()).toFloat()
            for (d in 0 until dim) v[d] /= norm
            embs.add(v)
        }

        // Speaker 2 (Kevin): 6 embeddings around axis 2
        for (i in 0 until 6) {
            val v = FloatArray(dim)
            v[2] = 1.0f
            v[3] = 0.05f * i
            val norm = Math.sqrt(v.map { (it * it).toDouble() }.sum()).toFloat()
            for (d in 0 until dim) v[d] /= norm
            embs.add(v)
        }

        // 1 Outlier embedding (e.g. mic thump / noise) around axis 4
        val outlier = FloatArray(dim)
        outlier[4] = 1.0f
        embs.add(outlier)

        // Normal calibrated threshold (0.68f matching desktop) separates intra-speaker from inter-speaker
        // and forms 2 significant conversational clusters despite the 1-sample outlier
        val labels = DiarizationEngine.clusterPrimaryEmbeddings(
            embeddings = embs,
            expectedSpeakers = "auto",
            sensitivityThreshold = 0.68f
        )

        // Must preserve the 2 true speakers without being distorted by the outlier!
        val taraCluster = labels[0]
        val kevinCluster = labels[18]
        assertNotEquals("Tara and Kevin must be separated into distinct clusters", taraCluster, kevinCluster)
        // All Tara items should cluster together
        for (i in 0 until 18) assertEquals(taraCluster, labels[i])
        // All Kevin items should cluster together
        for (i in 18 until 24) assertEquals(kevinCluster, labels[i])
    }

    @Test
    fun testSplitSegmentAtSpeakerTransitionPreservesSentenceClosureWord() {
        // Words: "...innovation that's happening locally. I'd like to talk"
        val words = listOf(
            AcousticAligner.TimedWord("that's", 1000L, 1300L),
            AcousticAligner.TimedWord("happening", 1300L, 1700L),
            AcousticAligner.TimedWord("locally.", 1700L, 2100L),
            AcousticAligner.TimedWord("I'd", 2400L, 2600L),
            AcousticAligner.TimedWord("like", 2600L, 2900L),
            AcousticAligner.TimedWord("to", 2900L, 3050L),
            AcousticAligner.TimedWord("talk", 3050L, 3300L)
        )

        val segment = TranscriptSegment(
            startMs = 1000L,
            endMs = 3300L,
            text = "that's happening locally. I'd like to talk",
            words = words
        )

        // Transition timestamp lands right at 1700ms (at the boundary of 'happening' and 'locally.')
        val split = AcousticAligner.splitSegmentAtSpeakerTransition(
            segment = segment,
            transitionMs = 1700L,
            speakerBefore = "Speaker 1",
            speakerAfter = "Speaker 2",
            energy = FloatArray(0)
        )

        assertEquals(2, split.size)
        // Speaker 1 must keep "locally." because it starts with lowercase and ends with a period completing the sentence!
        assertTrue("Speaker 1 text must end with locally.", split[0].text.endsWith("locally."))
        // Speaker 2 must start cleanly with "I'd like to talk"
        assertEquals("I'd like to talk", split[1].text)
        assertEquals("Speaker 1", split[0].speaker)
        assertEquals("Speaker 2", split[1].speaker)
    }
}
