package org.scrib.transcriber

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * High-accuracy, offline neural speaker diarization engine for Android.
 * Runs WeSpeaker CAM++ ONNX model directly via ONNX Runtime with:
 * - 85 Hz highpass filtering to eliminate room rumble / proximity bass disparity
 * - Per-chunk peak audio normalization for loud/distant speaker parity
 * - Short-utterance (< 0.8s) isolation to prevent phantom clusters ("Yeah." interjections)
 * - Padded context centroid matching for short interjections
 * - Expected speaker constraints (Auto, 2 Speakers Interview, Solo, 3+ Group)
 * - Calibrated AHC clustering matching D:\rtvs3 desktop specifications
 */
object DiarizationEngine {

    private const val TAG = "DiarizationEngine"
    private const val SAMPLE_RATE = 16000
    private const val FRAME_LEN = 400     // 25ms at 16kHz
    private const val FRAME_SHIFT = 160   // 10ms at 16kHz
    private const val N_FFT = 512
    private const val NUM_MEL_BINS = 80
    private const val LOW_FREQ = 20.0
    private const val HIGH_FREQ = 8000.0
    private const val PREEMPH_COEFF = 0.97f

    // Precomputed Mel filterbank weights: 80 bins x 257 FFT power spectrum bins
    private val melWeights: Array<FloatArray> by lazy { buildMelWeights() }
    private val hammingWindow: FloatArray by lazy {
        FloatArray(FRAME_LEN) { i ->
            (0.54 - 0.46 * cos(2.0 * Math.PI * i / (FRAME_LEN - 1))).toFloat()
        }
    }

    suspend fun diarize(
        context: Context,
        audio: DecodedAudio,
        sampleRate: Int = 16000,
        segments: List<TranscriptSegment>,
        onProgress: (Float) -> Unit = {}
    ): List<TranscriptSegment> = withContext(Dispatchers.Default) {
        if (segments.isEmpty() || audio.sampleCount == 0) return@withContext segments

        val sentences = segments.toSentences(audio, sampleRate)
        if (sentences.isEmpty()) return@withContext segments

        val modelPath = ModelManager.wespeakerModelPath(context)
        if (modelPath == null || !File(modelPath).exists()) {
            Log.w(TAG, "WeSpeaker model not found at $modelPath; marking speakers as unassigned")
            return@withContext unassignedSpeakerAssignments(sentences)
        }

        try {
            onProgress(0.1f)
            val diarizedSpans = runNeuralDiarization(modelPath, audio, sampleRate, sentences, context, onProgress)
            if (diarizedSpans.isEmpty()) {
                return@withContext unassignedSpeakerAssignments(sentences)
            }

            // Stage 2: Process sentences with Stage 1 Word-Level Speaker Assignment
            val resultSegments = mutableListOf<TranscriptSegment>()
            for (s in sentences) {
                if (s.words.isNotEmpty()) {
                    val wordTimings = s.words.map { WordTiming(it.word, it.startMs, it.endMs) }
                    val assignments = assignSpeakersToWordTimings(wordTimings, diarizedSpans)

                    val resolvedSpeakers = ArrayList<String>(s.words.size)
                    var runningSpeaker: String? = null
                    for (i in assignments.indices) {
                        val assign = assignments[i]
                        val timedWord = s.words[i]
                        val wordSpk = assign.speaker ?: findBestSpeaker(timedWord.startMs, timedWord.endMs, diarizedSpans)
                        val spk = wordSpk.ifBlank {
                            runningSpeaker ?: s.speaker.ifBlank { findBestSpeaker(s.startMs, s.endMs, diarizedSpans) }
                        }
                        resolvedSpeakers.add(spk)
                        if (spk.isNotBlank()) runningSpeaker = spk
                    }

                    val smoothedSpeakers = smoothShortSpeakerRuns(s.words, resolvedSpeakers)

                    var currentSpeaker: String? = null
                    var segStartMs = s.startMs
                    val currentWords = mutableListOf<AcousticAligner.TimedWord>()

                    for (i in s.words.indices) {
                        val spk = smoothedSpeakers[i]
                        val timedWord = s.words[i]

                        if (currentSpeaker == null) {
                            currentSpeaker = spk
                            segStartMs = timedWord.startMs
                            currentWords.add(timedWord)
                        } else if (spk == currentSpeaker) {
                            currentWords.add(timedWord)
                        } else {
                            val segEndMs = timedWord.startMs
                            if (currentWords.isNotEmpty()) {
                                resultSegments.add(
                                    TranscriptSegment(
                                        startMs = segStartMs,
                                        endMs = segEndMs,
                                        text = currentWords.joinToString(" ") { it.word },
                                        speaker = currentSpeaker,
                                        startsParagraph = resultSegments.isNotEmpty(),
                                        words = currentWords.toList()
                                    )
                                )
                            }
                            currentSpeaker = spk
                            segStartMs = timedWord.startMs
                            currentWords.clear()
                            currentWords.add(timedWord)
                        }
                    }

                    if (currentWords.isNotEmpty() && currentSpeaker != null) {
                        resultSegments.add(
                            TranscriptSegment(
                                startMs = segStartMs,
                                endMs = s.words.last().endMs,
                                text = currentWords.joinToString(" ") { it.word },
                                speaker = currentSpeaker,
                                startsParagraph = resultSegments.isNotEmpty(),
                                words = currentWords.toList()
                            )
                        )
                    }
                } else {
                    val intersectingSpans = diarizedSpans.filter { span ->
                        val overlap = max(0L, min(s.endMs, span.endMs) - max(s.startMs, span.startMs))
                        overlap >= 200L
                    }
                    val intersectingSpeakers = intersectingSpans.map { it.speaker }.distinct()

                    if (intersectingSpeakers.size >= 2) {
                        val firstSpeaker = findBestSpeaker(s.startMs, s.startMs + min(1000L, s.endMs - s.startMs), diarizedSpans)
                        val transition = intersectingSpans.firstOrNull {
                            it.speaker != firstSpeaker && it.startMs > s.startMs && it.startMs < s.endMs
                        }

                        if (transition != null) {
                            val secondSpeaker = transition.speaker
                            val dummySeg = TranscriptSegment(startMs = s.startMs, endMs = s.endMs, text = s.text, words = s.words)
                            val splitSegs = AcousticAligner.splitSegmentAtSpeakerTransition(
                                segment = dummySeg,
                                transitionMs = transition.startMs,
                                speakerBefore = firstSpeaker,
                                speakerAfter = secondSpeaker
                            )
                            resultSegments.addAll(splitSegs)
                            continue
                        }
                    }

                    val bestSpeaker = findBestSpeaker(s.startMs, s.endMs, diarizedSpans).ifBlank { s.speaker }
                    resultSegments.add(
                        TranscriptSegment(
                            startMs = s.startMs,
                            endMs = s.endMs,
                            text = s.text,
                            speaker = bestSpeaker,
                            words = s.words
                        )
                    )
                }
            }

            onProgress(1.0f)
            return@withContext resultSegments
        } catch (e: Throwable) {
            Log.e(TAG, "Neural diarization error: ${e.message}", e)
            return@withContext unassignedSpeakerAssignments(sentences)
        }
    }

    suspend fun diarize(
        context: Context,
        samples: FloatArray,
        sampleRate: Int = 16000,
        segments: List<TranscriptSegment>,
        onProgress: (Float) -> Unit = {}
    ): List<TranscriptSegment> {
        val byteBuf = java.nio.ByteBuffer.allocateDirect(samples.size * 4).order(java.nio.ByteOrder.nativeOrder())
        byteBuf.asFloatBuffer().put(samples)
        byteBuf.position(0)
        val decoded = DecodedAudio(listOf(byteBuf), samples.size)
        return diarize(context, decoded, sampleRate, segments, onProgress)
    }

    data class DiarSpan(val startMs: Long, val endMs: Long, val speaker: String)

    /**
     * Stage 1: Deterministic Word-to-Speaker Assignment based purely on temporal overlap.
     *
     * Calculates time overlap between each word's actual interval [startMs, endMs] and diarization spans.
     * Assigns a speaker ONLY when temporal evidence >= minOverlapMs and a clear dominant speaker exists.
     * Returns UNASSIGNED / UNCERTAIN when evidence is insufficient or tied; NEVER defaults to Speaker 1.
     */
    fun assignSpeakersToWordTimings(
        words: List<WordTiming>,
        diarSpans: List<DiarSpan>,
        minOverlapMs: Long = 50L,
        ambiguousThresholdRatio: Float = 0.8f
    ): List<WordSpeakerAssignment> {
        if (words.isEmpty()) return emptyList()

        return words.map { word ->
            // Validate timestamps
            if (word.endMs < word.startMs || word.startMs < 0L) {
                return@map WordSpeakerAssignment(
                    word = word,
                    speaker = null,
                    status = SpeakerAssignmentStatus.INVALID_TIMESTAMPS,
                    confidence = 0.0f
                )
            }

            val wordDur = word.endMs - word.startMs
            if (wordDur == 0L || diarSpans.isEmpty()) {
                return@map WordSpeakerAssignment(
                    word = word,
                    speaker = null,
                    status = SpeakerAssignmentStatus.UNASSIGNED,
                    confidence = 0.0f
                )
            }

            // Calculate overlap duration for each speaker
            val speakerOverlaps = mutableMapOf<String, Long>()
            for (span in diarSpans) {
                val overlap = max(0L, min(word.endMs, span.endMs) - max(word.startMs, span.startMs))
                if (overlap > 0L) {
                    speakerOverlaps[span.speaker] = (speakerOverlaps[span.speaker] ?: 0L) + overlap
                }
            }

            if (speakerOverlaps.isEmpty()) {
                return@map WordSpeakerAssignment(
                    word = word,
                    speaker = null,
                    status = SpeakerAssignmentStatus.UNASSIGNED,
                    confidence = 0.0f
                )
            }

            // Find top overlapping speakers
            val sorted = speakerOverlaps.entries.sortedByDescending { it.value }
            val topSpeaker = sorted[0].key
            val topOverlap = sorted[0].value

            // Check if top overlap meets minimum evidence threshold
            if (topOverlap < minOverlapMs) {
                return@map WordSpeakerAssignment(
                    word = word,
                    speaker = null,
                    status = SpeakerAssignmentStatus.UNASSIGNED,
                    confidence = topOverlap.toFloat() / max(1L, wordDur)
                )
            }

            // Check for tie or ambiguous overlap ratio with second speaker
            if (sorted.size >= 2) {
                val secondOverlap = sorted[1].value
                if (secondOverlap == topOverlap || (secondOverlap.toFloat() / topOverlap) >= ambiguousThresholdRatio) {
                    return@map WordSpeakerAssignment(
                        word = word,
                        speaker = null,
                        status = SpeakerAssignmentStatus.UNCERTAIN,
                        confidence = topOverlap.toFloat() / max(1L, wordDur)
                    )
                }
            }

            // Unambiguous dominant speaker found
            WordSpeakerAssignment(
                word = word,
                speaker = topSpeaker,
                status = SpeakerAssignmentStatus.ASSIGNED,
                confidence = (topOverlap.toFloat() / max(1L, wordDur)).coerceIn(0.0f, 1.0f)
            )
        }
    }


    /**
     * Absorbs transient sub-turn speaker flickers (< minTurnDurationMs based solely on neural
     * token timestamps) into adjacent speaker runs within the same sentence so single trailing
     * words never break into phantom speaker turns.
     */
    internal fun smoothShortSpeakerRuns(
        words: List<AcousticAligner.TimedWord>,
        speakers: List<String>,
        minTurnDurationMs: Long = 650L
    ): List<String> {
        if (words.size <= 1 || words.size != speakers.size) return speakers

        data class Run(val speaker: String, val startIdx: Int, val endIdx: Int)

        val runs = mutableListOf<Run>()
        var runStart = 0
        for (i in 1..speakers.size) {
            if (i == speakers.size || speakers[i] != speakers[runStart]) {
                runs.add(Run(speakers[runStart], runStart, i))
                runStart = i
            }
        }
        if (runs.size <= 1) return speakers

        fun runDurationMs(r: Run): Long =
            max(0L, words[r.endIdx - 1].endMs - words[r.startIdx].startMs)

        while (runs.size > 1) {
            var shortestIdx = -1
            var shortestDur = Long.MAX_VALUE
            for (i in runs.indices) {
                val dur = runDurationMs(runs[i])
                if (dur < minTurnDurationMs && dur < shortestDur) {
                    shortestDur = dur
                    shortestIdx = i
                }
            }
            if (shortestIdx == -1) break

            val targetIdx = when {
                shortestIdx == 0 -> 1
                shortestIdx == runs.lastIndex -> shortestIdx - 1
                runDurationMs(runs[shortestIdx - 1]) >= runDurationMs(runs[shortestIdx + 1]) -> shortestIdx - 1
                else -> shortestIdx + 1
            }
            val absorbed = runs[shortestIdx]
            val target = runs[targetIdx]
            runs[targetIdx] = Run(
                speaker = target.speaker,
                startIdx = min(target.startIdx, absorbed.startIdx),
                endIdx = max(target.endIdx, absorbed.endIdx)
            )
            runs.removeAt(shortestIdx)

            // Coalesce any newly adjacent runs sharing the same speaker
            var j = 0
            while (j + 1 < runs.size) {
                if (runs[j].speaker == runs[j + 1].speaker) {
                    runs[j] = Run(runs[j].speaker, runs[j].startIdx, runs[j + 1].endIdx)
                    runs.removeAt(j + 1)
                } else {
                    j++
                }
            }
        }

        val result = ArrayList<String>(speakers.size)
        for (i in speakers.indices) result.add(speakers[i])
        for (r in runs) {
            for (i in r.startIdx until r.endIdx) {
                result[i] = r.speaker
            }
        }
        return result
    }

    internal fun findBestSpeaker(startMs: Long, endMs: Long, spans: List<DiarSpan>): String {
        var bestOverlap = 0L
        var bestSpeaker: String? = null
        for (span in spans) {
            val overlap = max(0L, min(endMs, span.endMs) - max(startMs, span.startMs))
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestSpeaker = span.speaker
            }
        }
        return bestSpeaker ?: ""
    }

    private fun unassignedSpeakerAssignments(sentences: List<TimedSentence>): List<TranscriptSegment> {
        return sentences.map { s ->
            TranscriptSegment(startMs = s.startMs, endMs = s.endMs, text = s.text, speaker = "", words = s.words)
        }
    }

    private fun runNeuralDiarization(
        modelPath: String,
        audio: DecodedAudio,
        sampleRate: Int,
        sentences: List<TimedSentence>,
        context: Context,
        onProgress: (Float) -> Unit
    ): List<DiarSpan> {
        val primaryChunks = mutableListOf<TimedSentence>()
        val shortChunks = mutableListOf<TimedSentence>()

        for (s in sentences) {
            val durMs = s.endMs - s.startMs
            if (durMs < 800L) {
                // Short fragments (< 0.8s, like "Yeah.") are isolated to prevent skewing primary clusters
                shortChunks.add(s)
            } else if (durMs >= 6000L) {
                // Window turns >= 6.0s into full 3.0s slices (1.5s hop) with a full-width aligned tail
                // so trailing 1.0s silence-heavy stubs cannot form spurious outlier clusters.
                var currStart = s.startMs
                while (currStart + 3000L < s.endMs) {
                    primaryChunks.add(s.copy(startMs = currStart, endMs = currStart + 3000L))
                    currStart += 1500L
                }
                val tailStart = max(s.startMs, s.endMs - 3000L)
                if (primaryChunks.isEmpty() || primaryChunks.last().endMs < s.endMs) {
                    primaryChunks.add(s.copy(startMs = tailStart, endMs = s.endMs))
                }
            } else {
                // Cohesive 0.8s - 6.0s turns use extractSegmentEmbedding (1.5s / 0.75s sub-window centroid)
                primaryChunks.add(s)
            }
        }

        // If there are too few primary chunks, include short chunks to have sufficient data
        if (primaryChunks.size < 2 && shortChunks.isNotEmpty()) {
            primaryChunks.addAll(shortChunks)
            shortChunks.clear()
        }

        if (primaryChunks.isEmpty()) return emptyList()

        val totalSamples = audio.sampleCount
        val totalAudioMs = (totalSamples.toLong() * 1000L / sampleRate)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)
        val session = env.createSession(modelPath, opts)

        try {
            val primaryEmbeddings = mutableListOf<FloatArray>()
            val validPrimaryChunks = mutableListOf<TimedSentence>()
            val highpass = HighpassFilter85Hz()

            val totalPrimary = primaryChunks.size
            for ((idx, chunk) in primaryChunks.withIndex()) {
                val startSample = ((chunk.startMs * sampleRate) / 1000).toInt().coerceIn(0, totalSamples)
                val endSample = ((chunk.endMs * sampleRate) / 1000).toInt().coerceIn(startSample, totalSamples)
                val length = endSample - startSample
                if (length < FRAME_LEN) continue

                // Zero-copy windowing directly from native direct byte buffers
                val rawSlice = audio.getSlice(startSample, length)

                // Normalize audio: 85Hz highpass + peak normalization
                val normalizedAudio = prepareAudioChunk(rawSlice, highpass)

                val emb = extractSegmentEmbedding(session, env, normalizedAudio, sampleRate)
                if (emb != null) {
                    primaryEmbeddings.add(emb)
                    validPrimaryChunks.add(chunk)
                }

                if (idx % 2 == 0 || idx == totalPrimary - 1) {
                    onProgress(0.65f + 0.30f * (idx.toFloat() / totalPrimary))
                }
            }

            if (primaryEmbeddings.isEmpty()) {
                return emptyList()
            }

            val expectedSpeakers = ModelManager.expectedSpeakersKey(context).lowercase().trim()
            val sensitivityThreshold = ModelManager.diarizationSensitivityThreshold(context)
            Log.i(TAG, "Clustering ${primaryEmbeddings.size} primary voiceprints (expected=$expectedSpeakers, sensThreshold=$sensitivityThreshold)...")

            val primaryLabels = clusterPrimaryEmbeddings(primaryEmbeddings, expectedSpeakers, sensitivityThreshold)

            // Compute centroids for each formed primary cluster
            val uniqueClusterIds = primaryLabels.distinct().sorted()

            val centroids = mutableMapOf<Int, FloatArray>()
            for (clusterId in uniqueClusterIds) {
                val clusterEmbs = primaryEmbeddings.filterIndexed { i, _ -> primaryLabels[i] == clusterId }
                centroids[clusterId] = computeCentroid(clusterEmbs)
            }

            // In mixed audio (interviews, field tape, Zoom calls), every valid primary speech chunk (>= 0.8s)
            // represents a genuine speaker turn. Preserve all valid clusters without forced outlier erasure.
            val refinedLabels = primaryLabels

            // Assign short utterances (< 0.8s, e.g. "Yeah.") to the closest centroid using padded audio context
            val allSpans = mutableListOf<DiarSpan>()
            for (i in validPrimaryChunks.indices) {
                allSpans.add(DiarSpan(validPrimaryChunks[i].startMs, validPrimaryChunks[i].endMs, "Cluster_${refinedLabels[i]}"))
            }

            for (shortChunk in shortChunks) {
                // Pad by 500ms on each side to give sufficient acoustic context
                val paddedStartMs = max(0L, shortChunk.startMs - 500L)
                val paddedEndMs = min(totalAudioMs, shortChunk.endMs + 500L)

                val startSample = ((paddedStartMs * sampleRate) / 1000).toInt().coerceIn(0, totalSamples)
                val endSample = ((paddedEndMs * sampleRate) / 1000).toInt().coerceIn(startSample, totalSamples)
                val length = endSample - startSample
                if (length < FRAME_LEN) {
                    allSpans.add(DiarSpan(shortChunk.startMs, shortChunk.endMs, "Cluster_${primaryLabels.firstOrNull() ?: 0}"))
                    continue
                }

                val rawSlice = audio.getSlice(startSample, length)
                val normalizedAudio = prepareAudioChunk(rawSlice, highpass)

                val emb = extractEmbedding(session, env, normalizedAudio)
                val assignedCluster = if (emb != null && centroids.isNotEmpty()) {
                    centroids.maxByOrNull { (_, centroid) -> dotProduct(emb, centroid) }?.key ?: primaryLabels[0]
                } else {
                    primaryLabels.firstOrNull() ?: 0
                }
                allSpans.add(DiarSpan(shortChunk.startMs, shortChunk.endMs, "Cluster_$assignedCluster"))
            }

            // Sort spans chronologically and assign canonical Speaker 1, Speaker 2 labels
            allSpans.sortBy { it.startMs }
            val labelMap = mutableMapOf<String, String>()
            var speakerCounter = 1
            for (span in allSpans) {
                if (!labelMap.containsKey(span.speaker)) {
                    labelMap[span.speaker] = "Speaker ${speakerCounter++}"
                }
            }

            return allSpans.map { span ->
                span.copy(speaker = labelMap[span.speaker] ?: "Speaker 1")
            }
        } finally {
            try { session.close() } catch (_: Throwable) {}
            try { opts.close() } catch (_: Throwable) {}
        }
    }

    internal fun clusterPrimaryEmbeddings(
        embeddings: List<FloatArray>,
        expectedSpeakers: String,
        sensitivityThreshold: Float
    ): IntArray {
        val n = embeddings.size
        if (n <= 1) return IntArray(n) { 0 }

        if (expectedSpeakers == "1" || expectedSpeakers == "solo") {
            return IntArray(n) { 0 }
        }

        // Determine target k or distance cutoff
        when {
            expectedSpeakers == "2" || expectedSpeakers == "interview" -> {
                return clusterAhcFixedK(embeddings, k = min(2, n))
            }
            expectedSpeakers.startsWith("3") || expectedSpeakers == "panel" -> {
                return clusterAhcThreshold(embeddings, sensitivityThreshold, minK = min(3, n))
            }
            else -> {
                // Auto mode: pure acoustic distance threshold AHC matching desktop D:\rtvs3
                return clusterAhcThreshold(embeddings, sensitivityThreshold, minK = 1)
            }
        }
    }

    internal fun clusterAhcFixedK(embeddings: List<FloatArray>, k: Int): IntArray {
        val n = embeddings.size
        if (n <= k) return IntArray(n) { it }
        val dim = embeddings[0].size

        val sumVectors = Array(n) { i -> embeddings[i].clone() }
        val members = Array(n) { i -> mutableListOf(i) }
        val active = BooleanArray(n) { true }
        var activeCount = n

        // Average Linkage (UPGMA): mean pairwise cosine distance between cluster members
        fun avgDist(i: Int, j: Int): Float {
            val sI = sumVectors[i]
            val sJ = sumVectors[j]
            var dot = 0.0f
            for (d in 0 until dim) {
                dot += sI[d] * sJ[d]
            }
            val countI = members[i].size
            val countJ = members[j].size
            val avgSim = dot / (countI * countJ)
            return 1.0f - avgSim.coerceIn(-1.0f, 1.0f)
        }

        val distMatrix = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = avgDist(i, j)
                distMatrix[i][j] = d
                distMatrix[j][i] = d
            }
        }

        val nearest = IntArray(n) { -1 }
        val minDist = FloatArray(n) { Float.MAX_VALUE }

        fun updateNearest(i: Int) {
            var bestJ = -1
            var bestD = Float.MAX_VALUE
            val rowI = distMatrix[i]
            for (j in 0 until n) {
                if (j != i && active[j]) {
                    val d = rowI[j]
                    if (d < bestD) {
                        bestD = d
                        bestJ = j
                    }
                }
            }
            nearest[i] = bestJ
            minDist[i] = bestD
        }

        for (i in 0 until n) {
            updateNearest(i)
        }

        while (activeCount > k) {
            var globalMinD = Float.MAX_VALUE
            var bestI = -1
            for (i in 0 until n) {
                if (active[i] && minDist[i] < globalMinD) {
                    globalMinD = minDist[i]
                    bestI = i
                }
            }

            if (bestI == -1) break
            val bestJ = nearest[bestI]
            if (bestJ == -1 || !active[bestJ]) {
                updateNearest(bestI)
                continue
            }

            val sI = sumVectors[bestI]
            val sJ = sumVectors[bestJ]
            for (d in 0 until dim) {
                sI[d] += sJ[d]
            }
            members[bestI].addAll(members[bestJ])
            active[bestJ] = false
            activeCount--

            val rowI = distMatrix[bestI]
            for (idx in 0 until n) {
                if (idx != bestI && active[idx]) {
                    val d = avgDist(bestI, idx)
                    rowI[idx] = d
                    distMatrix[idx][bestI] = d
                    if (nearest[idx] == bestJ || nearest[idx] == bestI) {
                        updateNearest(idx)
                    } else if (d < minDist[idx]) {
                        minDist[idx] = d
                        nearest[idx] = bestI
                    }
                }
            }
            updateNearest(bestI)
        }

        val labels = IntArray(n)
        var clusterCounter = 0
        for (i in 0 until n) {
            if (active[i]) {
                for (m in members[i]) {
                    labels[m] = clusterCounter
                }
                clusterCounter++
            }
        }
        return labels
    }

    internal fun clusterAhcThreshold(embeddings: List<FloatArray>, threshold: Float, minK: Int = 1): IntArray {
        val n = embeddings.size
        if (n <= minK) return IntArray(n) { 0 }
        val dim = embeddings[0].size

        val sumVectors = Array(n) { i -> embeddings[i].clone() }
        val members = Array(n) { i -> mutableListOf(i) }
        val active = BooleanArray(n) { true }
        var activeCount = n

        // Average Linkage (UPGMA): mean pairwise cosine distance between cluster members
        fun avgDist(i: Int, j: Int): Float {
            val sI = sumVectors[i]
            val sJ = sumVectors[j]
            var dot = 0.0f
            for (d in 0 until dim) {
                dot += sI[d] * sJ[d]
            }
            val countI = members[i].size
            val countJ = members[j].size
            val avgSim = dot / (countI * countJ)
            return 1.0f - avgSim.coerceIn(-1.0f, 1.0f)
        }

        val distMatrix = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = avgDist(i, j)
                distMatrix[i][j] = d
                distMatrix[j][i] = d
            }
        }

        val nearest = IntArray(n) { -1 }
        val minDist = FloatArray(n) { Float.MAX_VALUE }

        fun updateNearest(i: Int) {
            var bestJ = -1
            var bestD = Float.MAX_VALUE
            val rowI = distMatrix[i]
            for (j in 0 until n) {
                if (j != i && active[j]) {
                    val d = rowI[j]
                    if (d < bestD) {
                        bestD = d
                        bestJ = j
                    }
                }
            }
            nearest[i] = bestJ
            minDist[i] = bestD
        }

        for (i in 0 until n) {
            updateNearest(i)
        }

        val mergeHistory = mutableListOf<Float>()
        while (activeCount > minK) {
            var globalMinD = Float.MAX_VALUE
            var bestI = -1
            for (i in 0 until n) {
                if (active[i] && minDist[i] < globalMinD) {
                    globalMinD = minDist[i]
                    bestI = i
                }
            }

            if (globalMinD > threshold || bestI == -1) break

            // Adaptive knee-point detection in Auto mode (minK == 1):
            // Only halt merges below threshold once distance enters the true inter-speaker band (> 0.55f)
            // so normal intra-speaker pitch/room variation (0.42f-0.55f) still coalesces cleanly.
            if (minK == 1 && mergeHistory.size >= 2 && globalMinD > 0.55f) {
                val lastD = mergeHistory.last()
                val delta = globalMinD - lastD
                val prevAvgStep = (lastD - mergeHistory.first()) / mergeHistory.size
                if (delta >= 0.14f && delta > 2.0f * max(0.02f, prevAvgStep)) {
                    Log.i(TAG, "Adaptive knee-point detected at distance=$globalMinD (prev=$lastD, delta=$delta). Halting merges at $activeCount clusters.")
                    break
                }
            }
            mergeHistory.add(globalMinD)

            val bestJ = nearest[bestI]
            if (bestJ == -1 || !active[bestJ]) {
                updateNearest(bestI)
                continue
            }

            val sI = sumVectors[bestI]
            val sJ = sumVectors[bestJ]
            for (d in 0 until dim) {
                sI[d] += sJ[d]
            }
            members[bestI].addAll(members[bestJ])
            active[bestJ] = false
            activeCount--

            val rowI = distMatrix[bestI]
            for (idx in 0 until n) {
                if (idx != bestI && active[idx]) {
                    val d = avgDist(bestI, idx)
                    rowI[idx] = d
                    distMatrix[idx][bestI] = d
                    if (nearest[idx] == bestJ || nearest[idx] == bestI) {
                        updateNearest(idx)
                    } else if (d < minDist[idx]) {
                        minDist[idx] = d
                        nearest[idx] = bestI
                    }
                }
            }
            updateNearest(bestI)
        }

        val labels = IntArray(n)
        var clusterCounter = 0
        for (i in 0 until n) {
            if (active[i]) {
                for (m in members[i]) {
                    labels[m] = clusterCounter
                }
                clusterCounter++
            }
        }
        return labels
    }

    private fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings[0].size
        val sum = DoubleArray(dim)
        for (e in embeddings) {
            for (d in 0 until dim) sum[d] += e[d]
        }
        var normSq = 0.0
        for (x in sum) normSq += x * x
        val norm = sqrt(normSq)
        val centroid = FloatArray(dim)
        if (norm > 1e-12) {
            for (d in 0 until dim) centroid[d] = (sum[d] / norm).toFloat()
        }
        return centroid
    }

    // 85 Hz Highpass Filter + Active-Speech RMS Normalization for balanced dynamic range
    // across on-mic, distant off-mic, and AGC-compressed Zoom/VoIP audio
    internal fun prepareAudioChunk(rawPcm: FloatArray, highpass: HighpassFilter85Hz): FloatArray {
        val filtered = highpass.filter(rawPcm)
        if (filtered.isEmpty()) return filtered

        var maxPeak = 0.0f
        for (x in filtered) {
            val a = abs(x)
            if (a > maxPeak) maxPeak = a
        }
        if (maxPeak < 1e-4f) return filtered

        // Active speech energy calculation (samples >= 10% of peak, ignoring ambient pauses)
        val silenceThresh = maxPeak * 0.10f
        var sumSq = 0.0
        var activeCount = 0
        for (x in filtered) {
            val a = abs(x)
            if (a >= silenceThresh) {
                sumSq += (a * a).toDouble()
                activeCount++
            }
        }

        val activeRms = if (activeCount > 0) sqrt(sumSq / activeCount).toFloat() else maxPeak
        val targetRms = 0.10f
        var scale = if (activeRms > 1e-4f) targetRms / activeRms else 0.75f / maxPeak

        // Prevent clipping if transients exceed 0.95
        if (maxPeak * scale > 0.95f) {
            scale = 0.95f / maxPeak
        }

        for (i in filtered.indices) {
            filtered[i] *= scale
        }
        return filtered
    }

    // Slices longer speech segments into 1.2s sub-windows with 0.6s hop and averages their embeddings,
    // matching desktop extract_embeddings_batched (worker_diarization_engine.py) for stable voiceprints.
    internal fun extractSegmentEmbedding(
        session: OrtSession,
        env: OrtEnvironment,
        audio: FloatArray,
        sampleRate: Int = 16000
    ): FloatArray? {
        val windowSamples = (1.5f * sampleRate).toInt()
        val stepSamples = (0.75f * sampleRate).toInt()
        val minSegmentSamples = (0.5f * sampleRate).toInt()

        if (audio.size <= (1.8f * sampleRate).toInt()) {
            return extractEmbedding(session, env, audio)
        }

        val subEmbs = mutableListOf<FloatArray>()
        var winStart = 0
        while (winStart + minSegmentSamples < audio.size) {
            val winEnd = min(winStart + windowSamples, audio.size)
            val winLen = winEnd - winStart
            if (winLen >= FRAME_LEN) {
                val subSlice = FloatArray(winLen)
                System.arraycopy(audio, winStart, subSlice, 0, winLen)
                val emb = extractEmbedding(session, env, subSlice)
                if (emb != null) {
                    subEmbs.add(emb)
                }
            }
            winStart += stepSamples
        }

        if (subEmbs.isEmpty()) {
            return extractEmbedding(session, env, audio)
        }

        return computeCentroid(subEmbs)
    }

    internal fun extractEmbedding(
        session: OrtSession,
        env: OrtEnvironment,
        audio: FloatArray
    ): FloatArray? {
        val feats = computeFbank(audio)
        if (feats.isEmpty()) return null

        val numFrames = feats.size
        val flatFeats = FloatArray(numFrames * NUM_MEL_BINS)
        for (f in 0 until numFrames) {
            System.arraycopy(feats[f], 0, flatFeats, f * NUM_MEL_BINS, NUM_MEL_BINS)
        }

        val shape = longArrayOf(1L, numFrames.toLong(), NUM_MEL_BINS.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatFeats), shape)
        return try {
            val output = session.run(mapOf("feats" to tensor))
            try {
                @Suppress("UNCHECKED_CAST")
                val embRaw = (output[0].value as Array<FloatArray>)[0]
                normalizeL2(embRaw)
            } finally {
                output.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            tensor.close()
        }
    }

    private fun normalizeL2(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq)
        if (norm < 1e-12) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = (v[i] / norm).toFloat()
        return out
    }

    internal fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        for (i in a.indices) dot += a[i] * b[i]
        return max(0.0f, 1.0f - dot.coerceIn(-1.0f, 1.0f))
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // 2nd-order Butterworth 85 Hz Highpass Filter at 16 kHz
    class HighpassFilter85Hz {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        init {
            val fc = 85.0
            val fs = 16000.0
            val w0 = 2.0 * Math.PI * fc / fs
            val alpha = sin(w0) / (2.0 * 0.7071)
            val cosw0 = cos(w0)

            val a0Raw = 1.0 + alpha
            b0 = ((1.0 + cosw0) / 2.0) / a0Raw
            b1 = (-(1.0 + cosw0)) / a0Raw
            b2 = ((1.0 + cosw0) / 2.0) / a0Raw
            a1 = (-2.0 * cosw0) / a0Raw
            a2 = (1.0 - alpha) / a0Raw
        }

        fun filter(input: FloatArray): FloatArray {
            val out = FloatArray(input.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in input.indices) {
                val x0 = input[i].toDouble()
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x0
                y2 = y1
                y1 = y0
                out[i] = y0.toFloat()
            }
            return out
        }
    }

    // Exact Kaldi-compliant 80-bin Mel Fbank extractor with pre-emphasis (0.97),
    // per-frame DC offset removal, Hamming window, and Cepstral Mean Subtraction (CMS)
    internal fun computeFbank(pcm: FloatArray): List<FloatArray> {
        val numFrames = (pcm.size - FRAME_LEN) / FRAME_SHIFT + 1
        if (numFrames <= 0) return emptyList()

        val frames = ArrayList<FloatArray>(numFrames)
        val channelSums = DoubleArray(NUM_MEL_BINS)

        val real = DoubleArray(N_FFT)
        val imag = DoubleArray(N_FFT)
        val power = FloatArray(N_FFT / 2 + 1)
        val frameBuf = FloatArray(FRAME_LEN)

        for (f in 0 until numFrames) {
            val start = f * FRAME_SHIFT

            // 1. Remove DC offset per frame (matches Kaldi remove_dc_offset=True)
            var frameSum = 0.0f
            for (i in 0 until FRAME_LEN) {
                val sample = pcm[start + i] * 32768.0f
                frameBuf[i] = sample
                frameSum += sample
            }
            val frameMean = frameSum / FRAME_LEN
            for (i in 0 until FRAME_LEN) {
                frameBuf[i] -= frameMean
            }

            // 2. Pre-emphasis: x[i] - 0.97 * x[i-1] with replication of x[0]
            real[0] = (frameBuf[0] - PREEMPH_COEFF * frameBuf[0]).toDouble() * hammingWindow[0]
            for (i in 1 until FRAME_LEN) {
                real[i] = (frameBuf[i] - PREEMPH_COEFF * frameBuf[i - 1]).toDouble() * hammingWindow[i]
            }
            for (i in FRAME_LEN until N_FFT) {
                real[i] = 0.0
            }
            imag.fill(0.0)

            // 3. FFT Radix-2
            fftRadix2(real, imag)

            // 4. Power spectrum
            for (k in 0..N_FFT / 2) {
                power[k] = (real[k] * real[k] + imag[k] * imag[k]).toFloat()
            }

            // 5. Mel filterbank dot product + Log energy
            val frameFeats = FloatArray(NUM_MEL_BINS)
            for (m in 0 until NUM_MEL_BINS) {
                var melEnergy = 0.0f
                val w = melWeights[m]
                for (k in 0..N_FFT / 2) {
                    val weight = w[k]
                    if (weight > 0.0f) {
                        melEnergy += weight * power[k]
                    }
                }
                val logEnergy = ln(max(1e-10f, melEnergy))
                frameFeats[m] = logEnergy
                channelSums[m] += logEnergy.toDouble()
            }
            frames.add(frameFeats)
        }

        // 6. Cepstral Mean Subtraction (CMS) across frames
        for (m in 0 until NUM_MEL_BINS) {
            val mean = (channelSums[m] / numFrames).toFloat()
            for (f in 0 until numFrames) {
                frames[f][m] -= mean
            }
        }

        return frames
    }

    private fun fftRadix2(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * Math.PI / len
            val wStepR = cos(angle)
            val wStepI = sin(angle)

            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until half) {
                    val pos = i + k + half
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[pos] * wR - imag[pos] * wI
                    val vI = real[pos] * wI + imag[pos] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[pos] = uR - vR
                    imag[pos] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    wI = wR * wStepI + wI * wStepR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    // Exact Kaldi Mel filterbank evaluation matching torchaudio.compliance.kaldi.get_mel_banks
    private fun buildMelWeights(): Array<FloatArray> {
        val numBins = NUM_MEL_BINS
        val numFftBins = N_FFT / 2
        val fftBinWidth = SAMPLE_RATE.toDouble() / N_FFT
        val melLow = 1127.0 * ln(1.0 + LOW_FREQ / 700.0)
        val melHigh = 1127.0 * ln(1.0 + HIGH_FREQ / 700.0)
        val melDelta = (melHigh - melLow) / (numBins + 1)

        val weights = Array(numBins) { FloatArray(N_FFT / 2 + 1) }
        for (bin in 0 until numBins) {
            val leftMel = melLow + bin * melDelta
            val centerMel = melLow + (bin + 1.0) * melDelta
            val rightMel = melLow + (bin + 2.0) * melDelta

            for (k in 0 until numFftBins) {
                val freq = k * fftBinWidth
                val mel = 1127.0 * ln(1.0 + freq / 700.0)
                val upSlope = (mel - leftMel) / (centerMel - leftMel)
                val downSlope = (rightMel - mel) / (rightMel - centerMel)
                val w = max(0.0, min(upSlope, downSlope))
                weights[bin][k] = w.toFloat()
            }
            // weights[bin][256] = 0.0f
        }
        return weights
    }
}
