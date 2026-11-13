package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.nio.ByteOrder
import kotlin.math.roundToLong

class SherpaTranscriptionEngine private constructor(private val appContext: Context) : TranscriptionEngine {

    private data class DecodedWindow(
        val text: String,
        val tokens: List<AcousticAligner.NeuralToken> = emptyList()
    )

    private val nativeLock = Any()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var onlineRecognizer: OnlineRecognizer? = null

    @Volatile
    private var loadedId: String? = null

    @Volatile
    private var loadedLanguage: String? = null

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
            callback.onTranscriptionError(transcriptionError(ErrorType.MODEL_NOT_AVAILABLE, e.message))
        } catch (e: DecodeException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.DECODE_FAILED, e.message))
        } catch (e: Throwable) {
            callback.onTranscriptionError(transcriptionError(ErrorType.UNEXPECTED, e.message))
        }
    }

    override fun openStream(request: StreamRequest?, callback: ITranscriptionCallback): AudioStream {
        val language = request?.languageHint ?: activeLanguage()
        return AudioStream(request, callback) { samples, sampleCount, _, _, _ ->
            val chunk = FloatArray(sampleCount)
            val floats = samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            floats.position(0)
            floats.get(chunk, 0, sampleCount)
            val decoded = decodeWindow(chunk, request?.languageHint)
            val text = Dictionary.applyReplacements(appContext, decoded.text)
            Log.d(TAG, "stream chunk: $sampleCount samples -> ${text.length} chars")
            TranscribedSegment(text, language)
        }
    }

    private fun activeLanguage(): String? =
        activeModelInfo()?.languages?.singleOrNull()

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
        return transcribePcm(
            pcm = pcm,
            languageHint = languageHint,
            cancellation = cancellation,
            onProgress = onProgress,
            onPartial = onPartial,
            onMetrics = onMetrics,
            audioDurationMs = audioDurationMs,
            decodeMs = 0L
        )
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
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            if (pcm.sampleCount == 0) {
                throw DecodeException("No audio decoded")
            }
            val audioDurationMs = pcm.sampleCount.toLong() * 1000L / SAMPLE_RATE
            onMetrics(TranscriptionMetrics(audioDurationMs = audioDurationMs, decodeMs = decodeMs))
            return transcribePcm(
                pcm = pcm,
                languageHint = languageHint,
                cancellation = cancellation,
                onProgress = onProgress,
                onPartial = onPartial,
                onMetrics = onMetrics,
                audioDurationMs = audioDurationMs,
                decodeMs = decodeMs
            )
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
        onMetrics: (TranscriptionMetrics) -> Unit,
        audioDurationMs: Long,
        decodeMs: Long
    ): List<TranscriptSegment> {
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        val inferenceStartedAt = SystemClock.elapsedRealtime()
        var modelLoadMs = 0L
        var modelPssMb = 0
        var modelMemoryDeltaMb = 0
        fun reportProgress(percent: Int) {
            onProgress(percent)
            onMetrics(
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    decodeMs = decodeMs,
                    inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt,
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb
                )
            )
        }
        try {
            val beforeLoad = processMemory(appContext)
            val modelLoadStartedAt = SystemClock.elapsedRealtime()
            val alreadyLoaded = synchronized(nativeLock) {
                ensureRecognizerLocked(languageHint)
            }
            val afterLoad = processMemory(appContext)
            modelLoadMs = if (alreadyLoaded) 0L else SystemClock.elapsedRealtime() - modelLoadStartedAt
            modelPssMb = afterLoad.pssMb
            modelMemoryDeltaMb = if (alreadyLoaded) 0 else maxOf(0, afterLoad.pssMb - beforeLoad.pssMb)
            onMetrics(
                TranscriptionMetrics(
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb
                )
            )
            val vadPath = ModelManager.sherpaVadModelPath(appContext)
            return if (vadPath != null && pcm.sampleCount > WINDOW_SAMPLES) {
                transcribeWithVad(pcm, vadPath, languageHint, cancellation, ::reportProgress, onPartial)
            } else {
                transcribeWindows(pcm, languageHint, cancellation, ::reportProgress, onPartial)
            }
        } finally {
            onMetrics(
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    decodeMs = decodeMs,
                    inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt,
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb
                )
            )
        }
    }

    private fun transcribeWindows(
        pcm: DecodedAudio,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        val sampleCount = pcm.sampleCount
        val windowRanges = if (sampleCount <= WINDOW_SAMPLES) {
            listOf(0 to sampleCount)
        } else {
            val energy = AcousticAligner.computeEnergyProfile(pcm, SAMPLE_RATE)
            AcousticAligner.planPauseSnappedWindowRanges(
                totalSamples = sampleCount,
                maxWindowSamples = WINDOW_SAMPLES,
                minWindowSamples = minOf(WINDOW_SAMPLES, MIN_WINDOW_SAMPLES),
                energy = energy,
                sampleRate = SAMPLE_RATE
            )
        }
        val segments = ArrayList<TranscriptSegment>(windowRanges.size)
        val cumulative = StringBuilder()
        for (i in windowRanges.indices) {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val (offset, endSample) = windowRanges[i]
            val length = endSample - offset
            if (length <= 0) continue

            val windowAudio = FloatArray(length)
            val readCount = pcm.readSlice(offset, length, windowAudio)
            if (readCount <= 0) continue
            val samplesForDecode = if (readCount == length) windowAudio else windowAudio.copyOf(readCount)

            val decoded = decodeWindow(samplesForDecode, languageHint)
            val text = Dictionary.applyReplacements(appContext, decoded.text)
            if (text.isNotEmpty()) {
                val startMs = offset.toLong() * 1000L / SAMPLE_RATE
                val endMs = (offset + readCount).toLong() * 1000L / SAMPLE_RATE
                val timedWords = buildSegmentWords(decoded.tokens, startMs, endMs, text)
                segments.add(
                    TranscriptSegment(
                        startMs = startMs,
                        endMs = endMs,
                        text = if (segments.isEmpty()) text else " $text",
                        words = timedWords
                    )
                )
                if (cumulative.isNotEmpty()) {
                    cumulative.append(' ')
                }
                cumulative.append(text)
                onPartial(cumulative.toString())
            }
            onProgress(((i + 1) * 100) / windowRanges.size)
        }
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        return markParagraphs(segments, emptyList())
    }

    private fun transcribeWithVad(
        pcm: DecodedAudio,
        vadPath: String,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        val sampleCount = pcm.sampleCount
        val vad = try {
            Vad(
                null,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadPath,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = VAD_MIN_SILENCE_S,
                        minSpeechDuration = VAD_MIN_SPEECH_S,
                        windowSize = VAD_WINDOW_SAMPLES,
                        maxSpeechDuration = VAD_MAX_SPEECH_S
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu"
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Sherpa VAD could not load", e)
            return transcribeWindows(pcm, languageHint, cancellation, onProgress, onPartial)
        }
        val segments = ArrayList<TranscriptSegment>()
        val speech = ArrayList<SpeechSpan>()
        val cumulative = StringBuilder()
        fun appendSegment(start: Int, samples: FloatArray) {
            if (samples.isEmpty()) {
                return
            }
            val decoded = decodeWindow(samples, languageHint)
            val text = Dictionary.applyReplacements(appContext, decoded.text)
            if (text.isEmpty()) {
                return
            }
            val startMs = start.toLong() * 1000L / SAMPLE_RATE
            val endMs = (start + samples.size).toLong() * 1000L / SAMPLE_RATE
            val timedWords = buildSegmentWords(decoded.tokens, startMs, endMs, text)
            segments.add(
                TranscriptSegment(
                    startMs = startMs,
                    endMs = endMs,
                    text = if (segments.isEmpty()) text else " $text",
                    words = timedWords
                )
            )
            speech.add(SpeechSpan(startMs, endMs))
            if (cumulative.isNotEmpty()) {
                cumulative.append(' ')
            }
            cumulative.append(text)
            onPartial(cumulative.toString())
        }
        // The detector consumes 512 samples at a time, so a naive report here fires once every 32 ms
        // of audio — tens of thousands of times on a long recording, each one waking the metrics
        // sampler and the UI for a percent that has not moved. Only the changes are worth a callback.
        var reportedPercent = -1
        val chunkBuf = FloatArray(VAD_WINDOW_SAMPLES)
        try {
            var offset = 0
            while (offset < sampleCount) {
                if (cancellation.isCancelled) {
                    throw CancelledException()
                }
                val length = minOf(VAD_WINDOW_SAMPLES, sampleCount - offset)
                val readCount = pcm.readSlice(offset, length, chunkBuf)
                if (readCount <= 0) break
                val chunk = if (readCount == chunkBuf.size) chunkBuf else chunkBuf.copyOf(readCount)
                vad.acceptWaveform(chunk)
                while (!vad.empty()) {
                    val segment = vad.front()
                    appendSegment(segment.start, segment.samples)
                    vad.pop()
                }
                offset += readCount
                val percent = ((offset.toLong() * 100) / sampleCount).toInt()
                if (percent != reportedPercent) {
                    reportedPercent = percent
                    onProgress(percent)
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val segment = vad.front()
                appendSegment(segment.start, segment.samples)
                vad.pop()
            }
        } finally {
            vad.release()
        }
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        onProgress(100)
        return markParagraphs(segments, speech)
    }

    private fun buildSegmentWords(
        windowTokens: List<AcousticAligner.NeuralToken>,
        segmentStartMs: Long,
        segmentEndMs: Long,
        segmentText: String
    ): List<AcousticAligner.TimedWord> {
        if (windowTokens.isEmpty()) return emptyList()
        val shiftedTokens = windowTokens.map { tok ->
            AcousticAligner.NeuralToken(
                text = tok.text,
                startMs = (segmentStartMs + tok.startMs).coerceIn(segmentStartMs, maxOf(segmentStartMs, segmentEndMs)),
                endMs = (segmentStartMs + tok.endMs).coerceIn(segmentStartMs, maxOf(segmentStartMs, segmentEndMs))
            )
        }
        val assembled = AcousticAligner.assembleSubwordTokensToWords(shiftedTokens) { raw ->
            Dictionary.applyReplacements(appContext, raw)
        }
        if (assembled.isEmpty()) return emptyList()
        return AcousticAligner.reconcileWordsWithSegmentText(segmentText, assembled, segmentStartMs, segmentEndMs)
    }

    private fun extractNeuralTokens(
        tokens: Array<String>,
        timestamps: FloatArray,
        durations: FloatArray,
        windowDurationMs: Long
    ): List<AcousticAligner.NeuralToken> {
        if (tokens.isEmpty() || timestamps.size != tokens.size) return emptyList()
        if (tokens.size > 1 && timestamps.all { it <= 0f }) return emptyList()

        val hasDurations = durations.size == tokens.size
        val out = ArrayList<AcousticAligner.NeuralToken>(tokens.size)
        for (i in tokens.indices) {
            val startMs = (timestamps[i].toDouble() * 1000.0).roundToLong().coerceIn(0L, windowDurationMs)
            val nextStartMs = if (i + 1 < timestamps.size) {
                (timestamps[i + 1].toDouble() * 1000.0).roundToLong().coerceIn(startMs, windowDurationMs)
            } else {
                windowDurationMs
            }
            val rawEndMs = if (hasDurations && durations[i] > 0f) {
                startMs + (durations[i].toDouble() * 1000.0).roundToLong()
            } else if (nextStartMs > startMs) {
                nextStartMs
            } else {
                startMs + 80L
            }
            val endMs = rawEndMs.coerceIn(startMs, windowDurationMs)
            out.add(AcousticAligner.NeuralToken(tokens[i], startMs, endMs))
        }
        return out
    }

    private fun decodeWindow(samples: FloatArray, languageHint: String? = null): DecodedWindow {
        val windowDurationMs = maxOf(1L, samples.size.toLong() * 1000L / SAMPLE_RATE)
        synchronized(nativeLock) {
            val info = activeModelInfo() ?: throw ModelNotAvailableException()
            ensureRecognizerLocked(languageHint)
            if (SherpaModel.isStreaming(info.modelType)) {
                val active = onlineRecognizer ?: throw ModelNotAvailableException()
                val stream = active.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()
                    while (active.isReady(stream)) {
                        active.decode(stream)
                    }
                    val result = active.getResult(stream)
                    val tokens = extractNeuralTokens(result.tokens, result.timestamps, FloatArray(0), windowDurationMs)
                    return DecodedWindow(result.text.trim(), tokens)
                } finally {
                    try {
                        stream.release()
                    } catch (ignore: Exception) {
                    }
                }
            }
            val active = recognizer ?: throw ModelNotAvailableException()
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                active.decode(stream)
                val result = active.getResult(stream)
                val tokens = extractNeuralTokens(result.tokens, result.timestamps, result.durations, windowDurationMs)
                return DecodedWindow(result.text.trim(), tokens)
            } finally {
                try {
                    stream.release()
                } catch (ignore: Exception) {
                }
            }
        }
    }

    override fun releaseModel() {
        synchronized(nativeLock) {
            try {
                recognizer?.release()
            } catch (ignore: Exception) {
            }
            try {
                onlineRecognizer?.release()
            } catch (ignore: Exception) {
            }
            recognizer = null
            onlineRecognizer = null
            loadedId = null
            loadedLanguage = null
        }
    }

    override fun capabilities(): TranscriberCapabilities {
        val info = activeModelInfo()
        val languages = info?.languages
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = ENGINE_ID
        capabilities.engineVersion = appVersion()
        capabilities.supportedLanguages = languages?.toTypedArray()
        capabilities.autoDetectLanguage = languages == null || languages.size != 1
        capabilities.cancellable = true
        capabilities.modelReady = info != null &&
            SherpaModel.isComplete(SherpaModel.dirFor(appContext, info.id), info)
        capabilities.streaming = true
        return capabilities
    }

    private fun activeModelInfo(): SherpaModelInfo? {
        val id = ModelManager.activeFileName(appContext) ?: return null
        return SherpaModel.byId(appContext, id)
    }

    private fun appVersion(): String? = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (ignore: Exception) {
        null
    }

    private fun ensureRecognizerLocked(languageHint: String? = null): Boolean {
        val info = activeModelInfo()
            ?: throw ModelNotAvailableException()
        val dir = SherpaModel.dirFor(appContext, info.id)
        if (!SherpaModel.isComplete(dir, info)) {
            throw ModelNotAvailableException("Sherpa model files are incomplete")
        }
        try {
            SherpaModel.validateMetadata(dir, info)
        } catch (e: IllegalArgumentException) {
            throw ModelNotAvailableException(e.message)
        }
        val language = when (info.modelType) {
            SherpaModel.TYPE_SENSE_VOICE -> SherpaModel.senseVoiceLanguage(info, languageHint)
            SherpaModel.TYPE_CANARY -> SherpaModel.canaryLanguage(info, languageHint)
            else -> null
        }
        val streaming = SherpaModel.isStreaming(info.modelType)
        if (loadedId == info.id && loadedLanguage == language) {
            if (streaming && onlineRecognizer != null) {
                return true
            }
            if (!streaming && recognizer != null) {
                return true
            }
        }
        try {
            recognizer?.release()
        } catch (ignore: Exception) {
        }
        try {
            onlineRecognizer?.release()
        } catch (ignore: Exception) {
        }
        recognizer = null
        onlineRecognizer = null
        loadedId = null
        loadedLanguage = null
        var last: Exception? = null
        for (provider in listOf("nnapi", "cpu")) {
            try {
                if (streaming) {
                    onlineRecognizer = OnlineRecognizer(
                        null,
                        SherpaModel.buildOnlineConfig(dir, info, provider)
                    )
                } else {
                    recognizer = OfflineRecognizer(
                        null,
                        SherpaModel.buildConfig(dir, info, provider, languageHint)
                    )
                }
                loadedId = info.id
                loadedLanguage = language
                Log.i(TAG, "Sherpa provider=$provider — OK")
                return false
            } catch (e: Exception) {
                Log.w(TAG, "Sherpa provider '$provider' failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IllegalStateException("All Sherpa providers failed")
    }

    companion object {
        private const val TAG = "SherpaEngine"

        const val ENGINE_ID = "sherpa-onnx"

        const val SAMPLE_RATE = 16000

        const val WINDOW_SAMPLES = SAMPLE_RATE * 30
        const val MIN_WINDOW_SAMPLES = SAMPLE_RATE * 24
        const val VAD_WINDOW_SAMPLES = 512

        // Aligned with TranscribeVad (v0.69): sensitive onset retention with 28s max continuous span
        private const val VAD_THRESHOLD = 0.25f
        private const val VAD_MIN_SILENCE_S = 0.50f
        private const val VAD_MIN_SPEECH_S = 0.10f
        private const val VAD_MAX_SPEECH_S = 28.0f

        @Volatile
        private var instance: SherpaTranscriptionEngine? = null

        fun getInstance(context: Context): SherpaTranscriptionEngine {
            return instance ?: synchronized(this) {
                instance ?: SherpaTranscriptionEngine(context.applicationContext).also { instance = it }
            }
        }

        // Called when a model is deleted while loaded, so the dead files are never touched again.
        fun forgetInstance(id: String) {
            synchronized(this) {
                if (instance?.loadedId == id) {
                    instance?.releaseModel()
                }
            }
        }
    }
}
