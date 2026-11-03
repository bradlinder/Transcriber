package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

enum class TranscriptionRunResult {
    SUCCESS,
    FAILED,
    CANCELLED,
    REJECTED
}

// A run outlives the screen that started it. The work sits on a process-wide scope rather than in
// the view model, so leaving the app neither cancels it nor drops the text already recognised, and
// the foreground service alongside keeps the process off the low-memory killer's list — an hour of
// audio is hundreds of megabytes of native buffers, which is what the system reclaims first.
object TranscriptionRun {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TranscribeUi?>(null)
    val state: StateFlow<TranscribeUi?> = _state

    @Volatile
    private var token: CancellationToken? = null

    val running: Boolean get() = token != null

    // A recording has no place in the user's storage to point the save dialog at, and its file is
    // the app's own — hence source stays null there and onFinished cleans the file up.
    fun start(context: Context, uri: Uri, name: String, source: Uri?, onFinished: () -> Unit = {}): Boolean =
        startInternal(context, uri, name, source, { onFinished() }, persistTranscript = true, allowBatch = false)

    fun startForBenchmark(
        context: Context,
        uri: Uri,
        name: String,
        source: Uri?,
        onFinished: (TranscriptionRunResult) -> Unit
    ): Boolean = startInternal(
        context,
        uri,
        name,
        source,
        onFinished,
        persistTranscript = false,
        allowBatch = true
    )

    private fun startInternal(
        context: Context,
        uri: Uri,
        name: String,
        source: Uri?,
        onFinished: (TranscriptionRunResult) -> Unit,
        persistTranscript: Boolean,
        allowBatch: Boolean
    ): Boolean {
        val app = context.applicationContext
        val cancellation = CancellationToken()
        synchronized(this) {
            if (token != null || (BenchmarkBatchGate.isActive && !allowBatch)) {
                return false
            }
            token = cancellation
        }
        val modelName = ModelManager.activeDisplayName(app)
        if (persistTranscript) TranscriptStore.clear(app)
        val wordThreshold = ModelManager.paragraphWordThreshold(app)
        val defaultFormat = TranscriptFormat.fromString(ModelManager.defaultExportFormat(app))
        val colorizeSpeakers = ModelManager.colorizeSpeakerLabels(app)
        _state.value = TranscribeUi(
            fileName = name,
            text = "",
            running = true,
            error = null,
            sourceUri = source,
            modelName = modelName,
            wordThreshold = wordThreshold,
            format = defaultFormat,
            colorizeSpeakerLabels = colorizeSpeakers
        )
        TranscriptionForegroundService.start(app)
        scope.launch {
            val eta = EtaClock()
            val liveStats = ModelManager.liveStats(app)
            val initialMemory = processMemory(app)
            val startedAt = SystemClock.elapsedRealtime()
            var audioDurationMs = 0L
            var decodeMs = 0L
            var inferenceMs = 0L
            var modelLoadMs = 0L
            var modelPssMb = 0
            var modelMemoryDeltaMb = 0
            var inferenceStartedAt = 0L
            var peakPssMb = initialMemory.pssMb
            var currentPssMb = initialMemory.pssMb
            var freeRamMb = initialMemory.freeRamMb
            val metricsLock = Any()

            fun currentMetrics(): TranscriptionMetrics = synchronized(metricsLock) {
                val now = SystemClock.elapsedRealtime()
                val memory = processMemory(app)
                currentPssMb = memory.pssMb
                freeRamMb = memory.freeRamMb
                peakPssMb = maxOf(peakPssMb, currentPssMb)
                val liveInferenceMs = if (inferenceStartedAt > 0L) {
                    maxOf(inferenceMs, now - inferenceStartedAt)
                } else {
                    inferenceMs
                }
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    decodeMs = decodeMs,
                    inferenceMs = liveInferenceMs,
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb,
                    totalMs = now - startedAt,
                    pssMb = currentPssMb,
                    peakPssMb = peakPssMb,
                    freeRamMb = freeRamMb
                )
            }

            fun publishMetrics() {
                val metrics = currentMetrics()
                _state.update { it?.copy(metrics = metrics) }
            }

            // Sampling every tick is what makes the figures live, and it is the only thing in the run
            // that reaches outside the process. With the setting off the run keeps its timings and
            // takes a single reading when it ends, so the result and the history still have numbers.
            val metricsJob = if (liveStats) launch {
                while (isActive) {
                    publishMetrics()
                    delay(METRICS_INTERVAL_MS)
                }
            } else {
                null
            }
            var result = TranscriptionRunResult.FAILED
            var decodedAudio: DecodedAudio? = null
            try {
                val decodeStartedAt = SystemClock.elapsedRealtime()
                val audio = try {
                    val pfd = app.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw RuntimeException(app.getString(R.string.transcribe_cant_open))
                    try {
                        AudioDecoder.decodeToPcm16kMono(pfd, cancellation, app.cacheDir).also { decodedAudio = it }
                    } finally {
                        try { pfd.close() } catch (_: Throwable) {}
                    }
                } catch (e: CancelledException) {
                    throw e
                } catch (e: LowStorageException) {
                    throw e
                } catch (e: Throwable) {
                    throw DecodeException(e.message ?: "Cannot decode audio")
                }
                val localDecodeMs = SystemClock.elapsedRealtime() - decodeStartedAt

                val rawSegments = TranscriptionEngine.get(app).transcribeDecodedToSegments(
                    audio, "", cancellation,
                    onProgress = { pct ->
                        val scaledPct = (pct * 0.65f).toInt()
                        val left = eta.mark(scaledPct)
                        _state.update { it?.copy(percent = scaledPct, etaMs = left) }
                    },
                    onPartial = { partial -> _state.update { it?.copy(text = partial) } },
                    onMetrics = { metrics ->
                        synchronized(metricsLock) {
                            if (metrics.audioDurationMs > 0L) {
                                audioDurationMs = metrics.audioDurationMs
                                if (inferenceStartedAt == 0L) {
                                    inferenceStartedAt = SystemClock.elapsedRealtime()
                                }
                            }
                            if (localDecodeMs > 0L) {
                                decodeMs = localDecodeMs
                            }
                            if (metrics.inferenceMs > 0L) {
                                inferenceMs = metrics.inferenceMs
                            }
                            if (metrics.modelLoadMs > 0L) {
                                modelLoadMs = metrics.modelLoadMs
                            }
                            if (metrics.modelPssMb > 0) {
                                modelPssMb = metrics.modelPssMb
                            }
                            if (metrics.modelMemoryDeltaMb > 0) {
                                modelMemoryDeltaMb = metrics.modelMemoryDeltaMb
                            }
                        }
                    }
                )

                val rawDiarized = try {
                    val targetAudio = audio
                    DiarizationEngine.diarize(app, targetAudio, 16000, rawSegments) { prog ->
                        val scaledPct = (prog * 100).toInt().coerceIn(65, 95)
                        val left = eta.mark(scaledPct)
                        _state.update { it?.copy(percent = scaledPct, etaMs = left) }
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("TranscriptionRun", "Diarization error: ${e.message}", e)
                    rawSegments
                }

                // Clean standard ASR silence hallucinations and degenerate repeating n-gram loops (desktop transcript_cleaner port)
                val segments = rawDiarized.scrubTranscript()

                val text = segments.format(TranscriptFormat.TXT, wordThreshold)
                if (cancellation.isCancelled) {
                    _state.value = null
                    result = TranscriptionRunResult.CANCELLED
                } else {
                    _state.update {
                        it?.copy(
                            text = text.ifBlank { app.getString(R.string.transcribe_no_speech) },
                            segments = segments, running = false, percent = 100, etaMs = null
                        )
                    }
                    val settled = processMemory(app)
                    synchronized(metricsLock) {
                        modelPssMb = settled.pssMb
                        modelMemoryDeltaMb = maxOf(0, settled.pssMb - initialMemory.pssMb)
                    }
                    publishMetrics()
                    _state.value?.metrics?.let { metrics ->
                        BenchmarkStore.record(
                            app,
                            BenchmarkRun(
                                timestampMs = System.currentTimeMillis(),
                                modelName = modelName,
                                fileName = name,
                                metrics = metrics
                            )
                        )
                    }

                    if (persistTranscript && segments.isNotEmpty()) {
                        _state.value?.let { TranscriptStore.save(app, it) }
                    }
                    result = TranscriptionRunResult.SUCCESS
                }
            } catch (e: Throwable) {
                if (cancellation.isCancelled) {
                    _state.value = null
                    result = TranscriptionRunResult.CANCELLED
                } else {
                    result = TranscriptionRunResult.FAILED
                    _state.update {
                        it?.copy(
                            running = false, etaMs = null,
                            error = e.message ?: app.getString(R.string.transcribe_failed)
                        )
                    }
                    publishMetrics()
                }
            } finally {
                try { decodedAudio?.close() } catch (_: Throwable) {}
                metricsJob?.cancelAndJoin()
                synchronized(this@TranscriptionRun) {
                    if (token === cancellation) token = null
                }
                onFinished(result)
            }
        }
        return true
    }

    fun restore(context: Context) {
        if (token != null || _state.value != null) {
            return
        }
        TranscriptStore.load(context.applicationContext)?.let { _state.value = it }
    }

    fun renameSpeaker(oldName: String, newName: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.renameSpeaker(oldName, newName)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun renameSpeakerInstance(segmentIndex: Int, newName: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.renameSpeakerInstance(segmentIndex, newName)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun deleteSpeakerLabel(segmentIndex: Int) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.deleteSpeakerLabel(segmentIndex)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun insertSpeakerLabel(segmentIndex: Int, newSpeakerName: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.insertSpeakerLabel(segmentIndex, newSpeakerName)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun insertSpeakerLabelAtTimestamp(timestampMs: Long, newSpeakerName: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.insertSpeakerLabelAtTimestamp(timestampMs, newSpeakerName)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun replaceTextInstance(matchIndex: Int, findText: String, replaceText: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.replaceTextInstance(matchIndex, findText, replaceText)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun replaceAllText(findText: String, replaceText: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.replaceAllText(findText, replaceText)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun updateTurnBlockText(segmentIndex: Int, oldText: String, newText: String) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedSegments = current.segments.updateTurnBlockText(segmentIndex, oldText, newText)
            val updatedText = updatedSegments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = updatedSegments)
        }
    }

    fun setSegments(segments: List<TranscriptSegment>) {
        _state.update { current ->
            if (current == null) return@update null
            val updatedText = segments.format(current.format, current.wordThreshold, current.showTimestamps, current.showSpeakers)
            current.copy(text = updatedText, segments = segments)
        }
    }

    fun toggleTimestamps(enabled: Boolean) {
        _state.update { current ->
            current?.copy(showTimestamps = enabled)
        }
    }

    fun toggleSpeakers(enabled: Boolean) {
        _state.update { current ->
            current?.copy(showSpeakers = enabled)
        }
    }

    fun toggleColorizeSpeakers(enabled: Boolean) {
        _state.update { current ->
            current?.copy(colorizeSpeakerLabels = enabled)
        }
    }

    fun setFormat(format: TranscriptFormat) {
        _state.update { current ->
            current?.copy(format = format)
        }
    }

    fun cancel(context: Context) {
        token?.cancel()
        TranscriptStore.clear(context.applicationContext)
        _state.value = null
    }

    fun cancelForBenchmark() {
        token?.cancel()
        _state.value = null
    }

    fun dismiss(context: Context) {
        TranscriptStore.clear(context.applicationContext)
        _state.value = null
    }

    fun clearState() {
        _state.value = null
    }

    fun setWordThreshold(wordThreshold: Int) {
        _state.update { it?.copy(wordThreshold = wordThreshold) }
    }
}

private const val METRICS_INTERVAL_MS = 500L

// Whisper reports how far through the audio it is, and it advances at a steady rate once the model
// is warm — so the time the run has taken to cover the percent so far carries over to what is left.
// The first report starts the clock: everything before it was decoding, at a different speed.
private class EtaClock {

    private var startedAt = 0L
    private var basePercent = -1

    fun mark(percent: Int): Long? {
        val now = SystemClock.elapsedRealtime()
        if (basePercent < 0) {
            if (percent in 0..99) {
                startedAt = now
                basePercent = percent
            }
            return null
        }
        val covered = percent - basePercent
        val elapsed = now - startedAt
        if (covered <= 0 || percent >= 100 || elapsed < SETTLE_MS) {
            return null
        }
        return elapsed * (100 - percent) / covered
    }

    private companion object {
        // Too early on, one slow window skews the estimate into the absurd.
        const val SETTLE_MS = 4000L
    }
}
