package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgmlTranscriptionEngine(private val appContext: Context) : TranscriptionEngine {

    @Volatile
    private var model: TranscribeModel? = null

    @Volatile
    private var session: TranscribeSession? = null

    @Volatile
    private var loadedPath: String? = null

    override fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    ) {
        try {
            val segments = transcribeToSegments(
                audio,
                request?.languageHint,
                cancellation,
                onPartial = { partial ->
                    try {
                        callback.onTranscriptionProgress(partial)
                    } catch (ignore: Exception) {
                    }
                }
            )
            callback.emitSegments(segments)
            callback.onTranscriptionResult(segments.format(TranscriptFormat.TXT))
        } catch (e: CancelledException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.CANCELLED))
        } catch (e: ModelNotAvailableException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.MODEL_NOT_AVAILABLE))
        } catch (e: DecodeException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.DECODE_FAILED, e.message))
        } catch (e: Throwable) {
            callback.onTranscriptionError(transcriptionError(ErrorType.UNEXPECTED, e.message))
        }
    }

    override fun openStream(request: StreamRequest?, callback: ITranscriptionCallback): AudioStream =
        AudioStream(request, callback) { samples, sampleCount, language, prompt, cancellation ->
            val abort = TranscribeAbort.create()
            try {
                cancellation.onCancel { abort.cancel() }
                val live = session()
                val audio = RunAudio.whole(samples, sampleCount)
                val text = runUtterance(live, model()!!.info, audio, 0, sampleCount, language, abort).text
                val spoken = live.detectedLanguage ?: language
                TranscribedSegment(Dictionary.applyReplacements(appContext, text), spoken)
            } finally {
                abort.close()
            }
        }

    override fun transcribeDecodedToSegments(
        pcm: DecodedAudio,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit
    ): List<TranscriptSegment> {
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        if (pcm.sampleCount == 0) {
            throw DecodeException("No audio decoded")
        }
        val audioDurationMs = pcm.sampleCount.toLong() * 1000L / SAMPLE_RATE
        onMetrics(TranscriptionMetrics(audioDurationMs = audioDurationMs))
        var modelLoadMs = 0L
        var modelPssMb = 0
        var modelMemoryDeltaMb = 0
        val inferenceStartedAt = SystemClock.elapsedRealtime()
        fun reportProgress(percent: Int) {
            onProgress(percent)
            onMetrics(
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt,
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb
                )
            )
        }
        return try {
            transcribePcm(
                pcm,
                languageHint,
                cancellation,
                ::reportProgress,
                onPartial,
                onModelLoad = { metrics ->
                    modelLoadMs = metrics.modelLoadMs
                    modelPssMb = metrics.modelPssMb
                    modelMemoryDeltaMb = metrics.modelMemoryDeltaMb
                    onMetrics(metrics)
                }
            )
        } finally {
            onMetrics(
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt,
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb
                )
            )
        }
    }

    override fun transcribeToSegments(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit
    ): List<TranscriptSegment> {
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val decodeStartedAt = SystemClock.elapsedRealtime()
            val pcm = AudioDecoder.decodeToPcm16kMono(audio, cancellation)
            val decodeMs = SystemClock.elapsedRealtime() - decodeStartedAt
            onMetrics(TranscriptionMetrics(decodeMs = decodeMs))
            return transcribeDecodedToSegments(pcm, languageHint, cancellation, onProgress, onPartial, onMetrics)
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun transcribePcm(
        pcm: DecodedAudio,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onModelLoad: (TranscriptionMetrics) -> Unit
    ): List<TranscriptSegment> {
        val abort = TranscribeAbort.create()
        cancellation.onCancel { abort.cancel() }
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val language = languageHint?.takeIf { it.isNotEmpty() }
            val activeKey = ModelManager.activeModelKey(appContext) ?: throw ModelNotAvailableException()
            val alreadyLoaded = loadedPath == activeKey && session != null
            val beforeLoad = processMemory(appContext)
            val loadStartedAt = SystemClock.elapsedRealtime()
            val live = session()
            val info = model()!!.info
            val afterLoad = processMemory(appContext)
            onModelLoad(
                TranscriptionMetrics(
                    modelLoadMs = if (alreadyLoaded) 0L else SystemClock.elapsedRealtime() - loadStartedAt,
                    modelPssMb = afterLoad.pssMb,
                    modelMemoryDeltaMb = if (alreadyLoaded) 0 else maxOf(0, afterLoad.pssMb - beforeLoad.pssMb)
                )
            )
            val audio = runAudio(pcm, info)
            val window = windowSamples(info)
            if (window in 1 until audio.sampleCount) {
                return transcribeWindows(
                    live, info, audio, window, language, abort, cancellation, onProgress, onPartial
                )
            }
            onProgress(0)
            val result = runUtterance(live, info, audio, 0, audio.sampleCount, language, abort, onPartial)
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            onProgress(100)
            val rawTokens = live.tokens()
            return markParagraphs(
                audio.segmentsOf(result, 0, audio.sampleCount, rawTokens) { text ->
                    Dictionary.applyReplacements(appContext, text)
                },
                emptyList()
            )
        } finally {
            abort.close()
        }
    }

    private fun windowSamples(info: TranscribeModelInfo): Int =
        if (info.longForm || info.streaming) 0 else WINDOW_SAMPLES

    private fun transcribeWindows(
        live: TranscribeSession,
        info: TranscribeModelInfo,
        audio: RunAudio,
        window: Int,
        language: String?,
        abort: TranscribeAbort,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        val windowPlan = planAcousticWindows(audio, window)
        val segments = ArrayList<TranscriptSegment>(windowPlan.size)
        val cumulative = StringBuilder()
        var previousPrompt: String? = null
        for ((offset, length) in windowPlan) {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            onProgress((offset.toLong() * 100L / audio.sampleCount).toInt())
            val prompt = if (info.contextPrompt) previousPrompt else null
            val result = runUtterance(
                live, info, audio, offset, length, language, abort,
                prompt = prompt,
                onPartial = { partial ->
                    cumulative.append(partial)
                    onPartial(cumulative.toString())
                }
            )
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val rawTokens = live.tokens()
            for (segment in audio.segmentsOf(result, offset, length, rawTokens) { text ->
                Dictionary.applyReplacements(appContext, text)
            }) {
                segments.add(if (segments.isEmpty()) segment else segment.copy(text = " ${segment.text}"))
            }
            val trimmedResult = result.text.trim()
            if (trimmedResult.isNotEmpty()) {
                previousPrompt = trimmedResult.takeLast(CONTEXT_PROMPT_MAX_CHARS)
            }
        }
        return markParagraphs(segments, emptyList())
    }

    private fun planAcousticWindows(audio: RunAudio, maxWindowSamples: Int): List<Pair<Int, Int>> {
        if (audio.sampleCount <= maxWindowSamples) {
            return listOf(0 to audio.sampleCount)
        }
        val energy = audio.computeEnergyProfile(SAMPLE_RATE)
        val rawRanges = AcousticAligner.planPauseSnappedWindowRanges(
            totalSamples = audio.sampleCount,
            maxWindowSamples = maxWindowSamples,
            minWindowSamples = minOf(maxWindowSamples, MIN_WINDOW_SAMPLES),
            energy = energy,
            sampleRate = SAMPLE_RATE
        )
        return rawRanges.map { (start, end) -> start to (end - start) }
    }

    private fun runAudio(pcm: DecodedAudio, info: TranscribeModelInfo): RunAudio {
        val path = ModelManager.vadModelPath(appContext)
        if (path == null || info.streaming || !ModelManager.activeModelUsesVad(appContext)) {
            return RunAudio.whole(pcm.samples, pcm.sampleCount)
        }
        val vad = TranscribeVad.open(path) ?: return RunAudio.whole(pcm.samples, pcm.sampleCount)
        val spans = try {
            vad.spans(pcm.samples, pcm.sampleCount)
        } catch (e: Throwable) {
            Log.w("GgmlEngine", "Silence skipping failed, transcribing everything", e)
            return RunAudio.whole(pcm.samples, pcm.sampleCount)
        }
        if (spans.isEmpty()) {
            return RunAudio.whole(pcm.samples, pcm.sampleCount)
        }
        val speech = spans.sumOf { (it.endMs - it.startMs).toDouble() }
        Log.d(
            "GgmlEngine",
            "Skipping silence: ${spans.size} stretch(es), ${(speech / 1000).toInt()}s of " +
                "${pcm.sampleCount * 1000L / SAMPLE_RATE / 1000}s"
        )
        return RunAudio.compact(pcm.samples, pcm.sampleCount, spans)
            ?: RunAudio.whole(pcm.samples, pcm.sampleCount)
    }

    private fun runUtterance(
        live: TranscribeSession,
        info: TranscribeModelInfo,
        audio: RunAudio,
        offset: Int,
        length: Int,
        language: String?,
        abort: TranscribeAbort,
        onPartial: ((String) -> Unit)? = null,
        prompt: String? = null
    ): RunResult {
        val samples = audio.slice(offset, length)
        if (!info.streaming) {
            TranscribeSession.check(live.run(samples, length, language, prompt, false, abort))
            return RunResult(live.fullText.orEmpty(), live.segments())
        }
        live.streamBegin(language, prompt, false, abort)
        try {
            live.streamFeed(samples, length)
            live.streamFinalize()
        } catch (e: Throwable) {
            live.streamReset()
            throw e
        }
        val text = live.fullText ?: live.committedText.orEmpty()
        onPartial?.invoke(text)
        return RunResult(text, live.segments())
    }

    @Synchronized
    override fun releaseModel() {
        TranscribeVad.release()
        session?.release()
        session = null
        model?.release()
        model = null
        loadedPath = null
    }

    override fun capabilities(): TranscriberCapabilities {
        val activeFile = ModelManager.activeFileName(appContext)
        val info = loadedModelInfo()
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = ENGINE_ID
        capabilities.engineVersion = appVersion()
        capabilities.supportedLanguages = info?.languages?.takeIf { it.isNotEmpty() }?.toTypedArray()
        capabilities.autoDetectLanguage = info?.languageDetect ?: true
        capabilities.cancellable = true
        capabilities.modelReady = activeFile != null
        capabilities.streaming = true
        return capabilities
    }

    private fun loadedModelInfo(): TranscribeModelInfo? = try {
        model?.info
    } catch (ignore: Throwable) {
        null
    }

    private fun appVersion(): String? = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (ignore: Exception) {
        null
    }

    @Synchronized
    private fun session(): TranscribeSession {
        session?.let { return it }
        val loaded = model()
        return loaded.openSession(CpuConfig.preferredThreadCount).also { session = it }
    }

    @Synchronized
    private fun model(): TranscribeModel {
        val key = ModelManager.activeModelKey(appContext) ?: throw ModelNotAvailableException()
        model?.let {
            if (loadedPath == key) {
                return it
            }
            releaseModel()
        }
        val active = ModelManager.openActiveModel(appContext) ?: throw ModelNotAvailableException()
        val loaded = TranscribeModel.load(active.path)
        model = loaded
        loadedPath = active.key
        return loaded
    }

    private data class RunResult(val text: String, val segments: List<TranscribeSegmentData>)

    private class RunAudio private constructor(
        private val samples: ByteBuffer,
        val sampleCount: Int,
        private val spanStarts: IntArray?,
        private val spanOffsets: IntArray?
    ) {

        fun slice(offset: Int, length: Int): ByteBuffer {
            val view = samples.duplicate().order(ByteOrder.nativeOrder())
            view.position(offset * 4)
            view.limit((offset + length) * 4)
            return view.slice().order(ByteOrder.nativeOrder())
        }

        fun computeEnergyProfile(sampleRate: Int = SAMPLE_RATE): FloatArray {
            val frameLen = (AcousticAligner.FRAME_LEN_MS * sampleRate) / 1000
            val frameShift = (AcousticAligner.FRAME_SHIFT_MS * sampleRate) / 1000
            val numFrames = maxOf(0, (sampleCount - frameLen) / frameShift + 1)
            if (numFrames == 0) return FloatArray(0)

            val rawEnergy = FloatArray(numFrames)
            val blockFrames = 500
            val floats = samples.duplicate().apply {
                position(0)
                limit(sampleCount * 4)
            }.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val scratch = FloatArray((blockFrames - 1) * frameShift + frameLen)

            var frameOffset = 0
            while (frameOffset < numFrames) {
                val count = minOf(blockFrames, numFrames - frameOffset)
                val firstSample = frameOffset * frameShift
                val neededSamples = minOf(sampleCount - firstSample, (count - 1) * frameShift + frameLen)
                floats.position(firstSample)
                floats.get(scratch, 0, neededSamples)
                for (k in 0 until count) {
                    val base = k * frameShift
                    var sumSq = 0.0f
                    val limit = minOf(frameLen, neededSamples - base)
                    for (i in 0 until limit) {
                        val s = scratch[base + i]
                        sumSq += s * s
                    }
                    rawEnergy[frameOffset + k] = if (limit > 0) sumSq / limit else 0.0f
                }
                frameOffset += count
            }

            val smoothed = FloatArray(numFrames)
            for (f in 0 until numFrames) {
                val prev = if (f > 0) rawEnergy[f - 1] else rawEnergy[f]
                val curr = rawEnergy[f]
                val next = if (f + 1 < numFrames) rawEnergy[f + 1] else rawEnergy[f]
                smoothed[f] = 0.25f * prev + 0.5f * curr + 0.25f * next
            }
            return smoothed
        }

        private fun assembleBpeTokensToWords(
            tokens: List<TranscribeTokenData>,
            offsetMs: Long,
            replace: (String) -> String
        ): List<AcousticAligner.TimedWord> {
            if (tokens.isEmpty()) return emptyList()

            val words = mutableListOf<AcousticAligner.TimedWord>()
            val currentWord = StringBuilder()
            var wordStartMs = -1L
            var wordEndMs = -1L
            var pendingWordBreak = false

            for (tok in tokens) {
                val rawText = tok.text
                if (rawText.isBlank()) {
                    if (rawText.isNotEmpty()) {
                        pendingWordBreak = true
                    }
                    continue
                }

                // BPE / SentencePiece tokens that start new words begin with space, SentencePiece indicator (\u2581), newline, or follow a whitespace-only token
                val startsNewWord = pendingWordBreak ||
                    rawText.startsWith(" ") ||
                    rawText.startsWith("\u2581") ||
                    rawText.startsWith("\n") ||
                    currentWord.isEmpty()

                if (startsNewWord && currentWord.isNotEmpty()) {
                    val fullWord = replace(currentWord.toString()).trim()
                    if (fullWord.isNotEmpty()) {
                        words.add(AcousticAligner.TimedWord(fullWord, originalMsAt(wordStartMs), originalMsAt(wordEndMs)))
                    }
                    currentWord.clear()
                    wordStartMs = -1L
                }
                pendingWordBreak = false

                val cleanTok = rawText.removePrefix("\u2581").trimStart()
                val tokStart = offsetMs + tok.startMs
                val tokEnd = offsetMs + tok.endMs

                if (wordStartMs < 0L) {
                    wordStartMs = tokStart
                }
                wordEndMs = tokEnd
                currentWord.append(cleanTok)
            }

            if (currentWord.isNotEmpty()) {
                val fullWord = replace(currentWord.toString()).trim()
                if (fullWord.isNotEmpty()) {
                    words.add(AcousticAligner.TimedWord(fullWord, originalMsAt(wordStartMs), originalMsAt(wordEndMs)))
                }
            }

            return words
        }

        fun segmentsOf(
            result: RunResult,
            offset: Int,
            length: Int,
            tokens: List<TranscribeTokenData> = emptyList(),
            replace: (String) -> String
        ): List<TranscriptSegment> {
            if (result.text.isBlank()) {
                return emptyList()
            }
            val offsetMs = offset * 1000L / SAMPLE_RATE
            val timedWords = assembleBpeTokensToWords(tokens, offsetMs, replace)

            val segments = result.segments.filter { it.text.isNotBlank() }
            if (segments.isEmpty()) {
                val replacedText = replace(result.text)
                val segStart = originalMs(offset)
                val segEnd = originalMs(offset + length)
                return listOf(
                    TranscriptSegment(
                        segStart,
                        segEnd,
                        replacedText,
                        words = AcousticAligner.reconcileWordsWithSegmentText(replacedText, timedWords, segStart, segEnd)
                    )
                )
            }
            val segBounds = segments.map { seg ->
                originalMsAt(offsetMs + seg.startMs) to originalMsAt(offsetMs + seg.endMs)
            }
            val partitionedWords = AcousticAligner.assignTimedWordsExclusively(timedWords, segBounds)
            return segments.mapIndexed { idx, segment ->
                val (segStart, segEnd) = segBounds[idx]
                val replacedText = replace(segment.text)
                val segWords = AcousticAligner.reconcileWordsWithSegmentText(replacedText, partitionedWords[idx], segStart, segEnd)
                TranscriptSegment(
                    segStart,
                    segEnd,
                    replacedText,
                    words = segWords
                )
            }
        }

        private fun originalMs(sample: Int): Long = originalMsAt(sample.toLong() * 1000L / SAMPLE_RATE)

        private fun originalMsAt(ms: Long): Long {
            val starts = spanStarts ?: return ms
            val offsets = spanOffsets ?: return ms
            val sample = (ms * SAMPLE_RATE / 1000).coerceIn(0, sampleCount.toLong())
            var span = 0
            for (i in starts.indices) {
                if (starts[i] <= sample) {
                    span = i
                } else {
                    break
                }
            }
            return offsets[span] * 1000L / SAMPLE_RATE + (sample - starts[span]) * 1000L / SAMPLE_RATE
        }

        companion object {
            fun whole(samples: ByteBuffer, sampleCount: Int) = RunAudio(samples, sampleCount, null, null)

            fun compact(
                samples: ByteBuffer,
                sampleCount: Int,
                spans: List<VadSpan>
            ): RunAudio? {
                val source = samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                val rawBounds = ArrayList<IntArray>(spans.size)
                for (span in spans) {
                    val from = (span.startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, sampleCount)
                    val to = (span.endMs * SAMPLE_RATE / 1000).toInt().coerceIn(from, sampleCount)
                    if (to > from) {
                        rawBounds.add(intArrayOf(from, to))
                    }
                }
                if (rawBounds.isEmpty()) return null

                // Bridge short conversational pauses (<= 350ms) so soft syllables and breaths are not excised
                val maxBridgeSamples = SAMPLE_RATE * 350 / 1000
                val bounds = ArrayList<IntArray>(rawBounds.size)
                for (b in rawBounds) {
                    if (bounds.isNotEmpty() && b[0] - bounds.last()[1] <= maxBridgeSamples) {
                        bounds.last()[1] = maxOf(bounds.last()[1], b[1])
                    } else {
                        bounds.add(b)
                    }
                }

                var kept = 0L
                for (b in bounds) {
                    kept += b[1] - b[0]
                }
                if (kept >= sampleCount * 0.85) {
                    return null
                }
                val packed = ByteBuffer.allocateDirect((kept * 4).toInt()).order(ByteOrder.nativeOrder())
                val target = packed.asFloatBuffer()
                val starts = IntArray(bounds.size)
                val offsets = IntArray(bounds.size)
                var at = 0
                for (i in bounds.indices) {
                    val from = bounds[i][0]
                    val to = bounds[i][1]
                    starts[i] = at
                    offsets[i] = from
                    target.put(at, source, from, to - from)
                    at += to - from
                }
                return RunAudio(packed, at, starts, offsets)
            }
        }
    }

    private companion object {
        const val ENGINE_ID = "transcribe.cpp"

        const val SAMPLE_RATE = 16000

        const val WINDOW_SAMPLES = SAMPLE_RATE * 30
        const val MIN_WINDOW_SAMPLES = SAMPLE_RATE * 18
        const val CONTEXT_PROMPT_MAX_CHARS = 220
    }
}
