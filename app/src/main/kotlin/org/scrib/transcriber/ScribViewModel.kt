package org.scrib.transcriber

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class RowState { NotDownloaded, Downloading, Installed, Active, Failed }

data class ModelRow(
    val id: String,
    val name: String,
    val badge: String,
    val multilingual: Boolean,
    val sizeMb: Int,
    val tier: Int,
    val recommended: Boolean,
    val custom: Boolean,
    val state: RowState,
    val progress: Int,
    val pinned: Boolean = false
)

data class TranscriberUiState(
    val firstRun: Boolean,
    val activeName: String?,
    val standard: List<ModelRow>,
    val sherpaRows: List<ModelRow>,
    val sherpaBusy: Boolean,
    val custom: List<ModelRow>,
    val statusMsg: String,
    val statusError: Boolean,
    val liveStats: Boolean,
    val dictionaryEnabled: Boolean,
    val benchmarkRuns: List<BenchmarkRun>,
    val benchmarkBatch: BenchmarkBatchState,
    val benchmarkModelCount: Int,
    val history: List<TranscriptHistory.Entry>,
    val pinnedIds: Set<String>,
    val remoteHost: String?,
    val paragraphWordThreshold: Int = 70,
    val diarizationSensitivityKey: String = "normal",
    val expectedSpeakersKey: String = "auto",
    val appTheme: String = "system",
    val defaultExportFormat: String = "docx",
    val colorizeSpeakerLabels: Boolean = false
)

// A take in progress: how long it has been running and the recent microphone levels the meter
// draws, newest last.
data class RecordingUi(val elapsedMs: Long, val levels: List<Float>)

data class TranscribeUi(
    val fileName: String,
    val text: String,
    val running: Boolean,
    val error: String?,
    val sourceUri: Uri? = null,
    val modelName: String? = null,
    val metrics: TranscriptionMetrics = TranscriptionMetrics(),
    val segments: List<TranscriptSegment> = emptyList(),
    val format: TranscriptFormat = TranscriptFormat.DOCX,
    val percent: Int = -1,
    val etaMs: Long? = null,
    val wordThreshold: Int = 70,
    val showTimestamps: Boolean = true,
    val showSpeakers: Boolean = true,
    val colorizeSpeakerLabels: Boolean = false,
    val historyId: String? = null
) {
    val formatted: String get() = if (segments.isEmpty()) text else segments.format(format, wordThreshold, showTimestamps, showSpeakers)
}

class TranscriberViewModel(app: Application) : AndroidViewModel(app) {

    private data class Progress(val pct: Int, val model: WhisperModel?)

    private val downloads = ConcurrentHashMap<String, Progress>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var statusMsg = ""
    @Volatile private var statusError = false
    @Volatile private var vadPct = -1
    @Volatile private var benchmarkBatch = BenchmarkBatchState()
    private var vadJob: Job? = null

    @Volatile private var sherpaBusy = false

    private val ctx get() = getApplication<Application>()
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val _state = MutableStateFlow(build())
    val state: StateFlow<TranscriberUiState> = _state

    private fun push() { _state.value = build() }

    private fun build(): TranscriberUiState {
        val installed = if (BuildConfig.LOCAL_MODELS) {
            ModelManager.installedFileNames(ctx).toHashSet()
        } else {
            emptySet()
        }
        val active = ModelManager.activeFileName(ctx)
        val pinnedIds = ModelManager.pinnedModels(ctx)
        fun stateOf(f: String): RowState = when {
            downloads.containsKey(f) -> RowState.Downloading
            failed.contains(f) -> RowState.Failed
            installed.contains(f) -> if (f == active) RowState.Active else RowState.Installed
            else -> RowState.NotDownloaded
        }
        val standard = if (!BuildConfig.LOCAL_MODELS) emptyList() else ModelCatalog.MODELS.map { m ->
            ModelRow(
                id = m.fileName, name = m.displayName,
                badge = str(if (m.multilingual) R.string.badge_multilingual else R.string.badge_english_only),
                multilingual = m.multilingual,
                sizeMb = (m.approxBytes / 1_000_000).toInt(), tier = m.tier,
                recommended = m.recommended, custom = false,
                state = stateOf(m.fileName), progress = downloads[m.fileName]?.pct ?: 0,
                pinned = m.fileName in pinnedIds
            )
        }
        val customFiles = LinkedHashSet<String>()
        if (BuildConfig.LOCAL_MODELS) {
            customFiles.addAll(ModelManager.installedCustomFileNames(ctx))
            downloads.forEach { (f, p) -> if (p.model?.custom == true) customFiles.add(f) }
        }
        val custom = customFiles.map { f ->
            val multi = !ModelCatalog.isEnglishOnly(f)
            ModelRow(
                id = f, name = f.removePrefix("ggml-").removeSuffix(".bin").removeSuffix(".gguf"),
                badge = str(if (multi) R.string.badge_multilingual else R.string.badge_english_only),
                multilingual = multi, sizeMb = 0,
                tier = downloads[f]?.model?.tier ?: 3, recommended = false, custom = true,
                state = stateOf(f), progress = downloads[f]?.pct ?: 0,
                pinned = f in pinnedIds
            )
        }.toMutableList()
        ModelManager.externalModel(ctx)?.let { (name, _) ->
            val id = ModelManager.EXTERNAL_PREFIX + name
            custom.add(
                0,
                ModelRow(
                    id = id, name = name, badge = str(R.string.badge_external),
                    multilingual = !ModelCatalog.isEnglishOnly(name),
                    sizeMb = (ModelManager.externalModelSize(ctx) / 1_000_000).toInt(),
                    tier = 3, recommended = false, custom = false,
                    state = if (id == active) RowState.Active else RowState.Installed, progress = 0,
                    pinned = id in pinnedIds
                )
            )
        }
        val activeFriendly = ModelManager.activeDisplayName(ctx)
        val sherpaRows = (SherpaPlugins.plugin?.modelRows(ctx, active) ?: emptyList())
            .map { if (it.id in pinnedIds) it.copy(pinned = true) else it }
        val endpointReady = EndpointPlugins.plugin?.isActive(ctx) == true
        return TranscriberUiState(
            firstRun = !endpointReady && installed.isEmpty() && downloads.isEmpty() &&
                sherpaRows.isEmpty() && !sherpaBusy && ModelManager.externalModel(ctx) == null,
            activeName = activeFriendly, standard = standard, sherpaRows = sherpaRows, sherpaBusy = sherpaBusy, custom = custom,
            statusMsg = statusMsg, statusError = statusError,
            liveStats = ModelManager.liveStats(ctx),
            dictionaryEnabled = Dictionary.isEnabled(ctx),
            benchmarkRuns = BenchmarkStore.load(ctx),
            benchmarkBatch = benchmarkBatch,
            benchmarkModelCount = if (BuildConfig.LOCAL_MODELS) installed.size + sherpaRows.size else 0,
            history = history(),
            pinnedIds = pinnedIds,
            remoteHost = EndpointPlugins.plugin
                ?.takeIf { it.isActive(ctx) }
                ?.host(ctx)
                ?.takeIf { it.isNotEmpty() },
            paragraphWordThreshold = ModelManager.paragraphWordThreshold(ctx),
            diarizationSensitivityKey = ModelManager.diarizationSensitivityKey(ctx),
            expectedSpeakersKey = ModelManager.expectedSpeakersKey(ctx),
            appTheme = ModelManager.appTheme(ctx),
            defaultExportFormat = ModelManager.defaultExportFormat(ctx),
            colorizeSpeakerLabels = ModelManager.colorizeSpeakerLabels(ctx)
        )
    }

    fun setColorizeSpeakerLabels(enabled: Boolean) {
        ModelManager.setColorizeSpeakerLabels(ctx, enabled)
        TranscriptionRun.toggleColorizeSpeakers(enabled)
        push()
    }

    fun setAppTheme(theme: String) {
        ModelManager.setAppTheme(ctx, theme)
        push()
    }

    fun setDefaultExportFormat(format: String) {
        ModelManager.setDefaultExportFormat(ctx, format)
        TranscriptionRun.setFormat(TranscriptFormat.fromString(format))
        push()
    }

    fun download(fileName: String) {
        val model = ModelCatalog.byFileName(fileName) ?: downloads[fileName]?.model ?: return
        startDownload(model)
    }

    fun importSherpa(
        displayName: String,
        modelType: String,
        languages: List<String>?,
        parts: Map<String, Uri>,
        completed: (Throwable?) -> Unit = {}
    ) {
        if (BenchmarkBatchGate.isActive) {
            completed(IllegalStateException("Benchmark is active"))
            return
        }
        val plugin = SherpaPlugins.plugin
        if (plugin == null) {
            completed(IllegalStateException("Sherpa support is unavailable"))
            return
        }
        if (sherpaBusy) {
            completed(IllegalStateException("Another model import is already running"))
            return
        }
        sherpaBusy = true
        statusMsg = ""; statusError = false
        push()
        viewModelScope.launch {
            var failure: Throwable? = null
            try {
                val id = withContext(Dispatchers.IO) {
                    plugin.importModel(ctx, displayName, modelType, languages, parts)
                }
                if (!ModelManager.hasActiveModel(ctx)) {
                    ModelManager.setActive(ctx, id)
                }
            } catch (e: Throwable) {
                failure = e
                statusMsg = e.message ?: "Sherpa import failed"
                statusError = true
            }
            sherpaBusy = false
            completed(failure)
            push()
        }
    }

    private fun startDownload(model: WhisperModel, activateOnComplete: Boolean = false, statusLabel: String? = null) {
        if (BenchmarkBatchGate.isActive) return
        val f = model.fileName
        if (downloads.containsKey(f)) return
        failed.remove(f)
        downloads[f] = Progress(0, model)
        if (statusLabel != null) { statusMsg = str(R.string.status_ellipsis, statusLabel); statusError = false }
        push()
        val job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.download(ctx, model,
                        onProgress = { done, total ->
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (downloads[f]?.pct != pct) {
                                downloads[f] = Progress(pct, model)
                                if (statusLabel != null && pct >= 0) {
                                    statusMsg = str(R.string.status_pct, statusLabel, pct)
                                    statusError = false
                                }
                                push()
                            }
                        },
                        isCancelled = { !isActive })
                }
                downloads.remove(f); jobs.remove(f)
                if (activateOnComplete) ModelManager.setActive(ctx, f)
                if (statusLabel != null) { statusMsg = str(R.string.status_ready_active, model.displayName); statusError = false }
                push()
            } catch (e: ModelManager.CancelledDownloadException) {
                downloads.remove(f); jobs.remove(f)
                if (statusLabel != null) statusMsg = ""
                push()
            } catch (e: Throwable) {
                downloads.remove(f); jobs.remove(f); failed.add(f)
                statusMsg = str(R.string.status_download_failed_named, model.displayName)
                statusError = true
                push()
            }
        }
        jobs[f] = job
    }

    fun cancel(fileName: String) {
        jobs.remove(fileName)?.cancel()
        downloads.remove(fileName)
        push()
    }

    fun setLiveStats(enabled: Boolean) {
        if (BenchmarkBatchGate.isActive) return
        ModelManager.setLiveStats(ctx, enabled)
        push()
    }

    fun activate(fileName: String) {
        if (BenchmarkBatchGate.isActive) return
        ModelManager.setActive(ctx, fileName)
        releaseSwitchedAwayModel()
        statusMsg = ""; statusError = false
        push()
    }

    fun togglePinned(fileName: String) {
        ModelManager.togglePinned(ctx, fileName)
        push()
    }

    fun setParagraphWordThreshold(threshold: Int) {
        ModelManager.setParagraphWordThreshold(ctx, threshold)
        TranscriptionRun.setWordThreshold(threshold)
        push()
    }

    fun setDiarizationSensitivity(sensKey: String) {
        ModelManager.setDiarizationSensitivity(ctx, sensKey)
        push()
    }

    fun setExpectedSpeakers(key: String) {
        ModelManager.setExpectedSpeakers(ctx, key)
        push()
    }

    fun renameSpeaker(oldName: String, newName: String) {
        TranscriptionRun.renameSpeaker(oldName, newName)
        push()
    }

    fun renameSpeakerInstance(segmentIndex: Int, newName: String) {
        TranscriptionRun.renameSpeakerInstance(segmentIndex, newName)
        push()
    }

    fun deleteSpeakerLabel(segmentIndex: Int) {
        TranscriptionRun.deleteSpeakerLabel(segmentIndex)
        push()
    }

    fun insertSpeakerLabel(segmentIndex: Int, newSpeakerName: String) {
        TranscriptionRun.insertSpeakerLabel(segmentIndex, newSpeakerName)
        push()
    }

    fun insertSpeakerLabelAtTimestamp(timestampMs: Long, newSpeakerName: String) {
        TranscriptionRun.insertSpeakerLabelAtTimestamp(timestampMs, newSpeakerName)
        push()
    }

    fun replaceTextInstance(matchIndex: Int, findText: String, replaceText: String) {
        TranscriptionRun.replaceTextInstance(matchIndex, findText, replaceText)
        push()
    }

    fun replaceAllText(findText: String, replaceText: String) {
        TranscriptionRun.replaceAllText(findText, replaceText)
        push()
    }

    fun updateTurnBlockText(segmentIndex: Int, oldText: String, newText: String) {
        TranscriptionRun.updateTurnBlockText(segmentIndex, oldText, newText)
        push()
    }

    fun toggleTimestamps(enabled: Boolean) {
        TranscriptionRun.toggleTimestamps(enabled)
        push()
    }

    fun toggleSpeakers(enabled: Boolean) {
        TranscriptionRun.toggleSpeakers(enabled)
        push()
    }

    fun queueBatchItems(files: List<Pair<Uri, String>>) {
        val existing = BatchQueueManager.state.value.items
        val newItems = files.mapIndexed { idx, (uri, name) ->
            BatchItem(
                id = "${System.currentTimeMillis()}_$idx",
                uri = uri,
                fileName = name
            )
        }
        BatchQueueManager.setItems(existing + newItems)
    }

    fun startBatchProcessing() {
        val currentBatch = BatchQueueManager.state.value
        if (currentBatch.running || currentBatch.items.isEmpty()) return

        BatchQueueManager.setRunning(true)
        viewModelScope.launch(Dispatchers.IO) {
            val items = BatchQueueManager.state.value.items
            for ((idx, item) in items.withIndex()) {
                if (item.status == BatchItemStatus.COMPLETED) continue

                BatchQueueManager.setCurrentIndex(idx)
                BatchQueueManager.updateItemStatus(item.id, BatchItemStatus.PROCESSING, percent = 5)

                try {
                    val runResult = executeSingleBatchItem(item.id, item.uri, item.fileName)
                    if (runResult != null) {
                        BatchQueueManager.updateItemStatus(
                            item.id,
                            BatchItemStatus.COMPLETED,
                            percent = 100,
                            resultText = runResult.text,
                            segments = runResult.segments
                        )
                        TranscriptHistory.save(ctx, runResult)
                    } else {
                        BatchQueueManager.updateItemStatus(item.id, BatchItemStatus.FAILED, error = "Transcription failed")
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("ScribViewModel", "Batch item ${item.fileName} error: ${e.message}", e)
                    BatchQueueManager.updateItemStatus(item.id, BatchItemStatus.FAILED, error = e.message ?: "Failed")
                }
            }
            BatchQueueManager.setRunning(false)
            historyCache = TranscriptHistory.load(ctx)
            push()
        }
    }

    private suspend fun executeSingleBatchItem(itemId: String, uri: Uri, fileName: String): TranscribeUi? {
        val cancellation = CancellationToken()
        val decodedAudio = try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: throw RuntimeException("Cannot open file descriptor for $uri")
            try {
                AudioDecoder.decodeToPcm16kMono(pfd, cancellation, ctx.cacheDir)
            } finally {
                try { pfd.close() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            android.util.Log.e("ScribViewModel", "Single batch decode error for $fileName: ${e.message}", e)
            return null
        }

        try {
            val rawSegments = try {
                TranscriptionEngine.get(ctx).transcribeDecodedToSegments(
                    decodedAudio, "", cancellation,
                    onProgress = { pct ->
                        val scaledPct = (pct * 0.65f).toInt()
                        BatchQueueManager.updateItemStatus(itemId, BatchItemStatus.PROCESSING, percent = scaledPct)
                    },
                    onPartial = {}
                )
            } catch (e: Throwable) {
                android.util.Log.e("ScribViewModel", "Single batch ASR error for $fileName: ${e.message}", e)
                return null
            }

            if (rawSegments.isEmpty()) {
                return TranscribeUi(
                    fileName = fileName,
                    text = "",
                    running = false,
                    error = null,
                    metrics = TranscriptionMetrics(),
                    segments = emptyList(),
                    format = TranscriptFormat.fromString(ModelManager.defaultExportFormat(ctx)),
                    percent = 100
                )
            }

            val segments = try {
                DiarizationEngine.diarize(ctx, decodedAudio, 16000, rawSegments) { prog ->
                    val scaledPct = 65 + (prog * 30).toInt().coerceIn(0, 30)
                    BatchQueueManager.updateItemStatus(itemId, BatchItemStatus.PROCESSING, percent = scaledPct)
                }
            } catch (e: Throwable) {
                android.util.Log.e("ScribViewModel", "Batch diarization error for $fileName: ${e.message}", e)
                rawSegments
            }

            val wordThreshold = ModelManager.paragraphWordThreshold(ctx)
            val format = TranscriptFormat.fromString(ModelManager.defaultExportFormat(ctx))
            val formatted = segments.format(format, wordThreshold, showTimestamps = true, showSpeakers = true)

            return TranscribeUi(
                fileName = fileName,
                text = formatted,
                running = false,
                error = null,
                metrics = TranscriptionMetrics(),
                segments = segments,
                format = format,
                percent = 100
            )
        } finally {
            try { decodedAudio.close() } catch (_: Throwable) {}
        }
    }

    // Releasing blocks behind a running transcription, so it cannot happen on the main thread.
    private fun releaseSwitchedAwayModel() {
        viewModelScope.launch(Dispatchers.IO) { TranscriptionEngine.releaseAll(ctx) }
    }

    fun setupForLanguage(language: LanguageOption) {
        if (BenchmarkBatchGate.isActive) return
        val f = language.recommendedFileName
        val model = ModelCatalog.byFileName(f) ?: return
        val langName = str(language.nameRes)
        if (ModelManager.installedFileNames(ctx).contains(f)) {
            ModelManager.setActive(ctx, f)
            releaseSwitchedAwayModel()
            statusMsg = str(R.string.status_lang_ready_active, langName, model.displayName)
            statusError = false
            push()
        } else {
            startDownload(
                model, activateOnComplete = true,
                statusLabel = str(R.string.status_lang_downloading, langName, model.displayName)
            )
        }
    }

    fun delete(fileName: String) {
        if (BenchmarkBatchGate.isActive) return
        if (SherpaPlugins.plugin?.deleteModel(ctx, fileName) == true) {
            push()
            return
        }
        ModelManager.delete(ctx, fileName)
        failed.remove(fileName)
        push()
    }

    fun refresh() {
        push()
    }

    fun addCustom(url: String) {
        if (BenchmarkBatchGate.isActive) return
        val model = try {
            ModelManager.customModelFromUrl(url)
        } catch (e: Exception) {
            statusMsg = str(R.string.status_invalid_link); statusError = true; push(); return
        }
        statusMsg = str(R.string.status_fetching_from, hostOf(model.url))
        statusError = false
        startDownload(model)
    }

    fun importModel(uri: Uri, suggestedName: String?) {
        if (BenchmarkBatchGate.isActive) return
        statusMsg = str(R.string.status_importing); statusError = false; push()
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { ModelManager.importFromUri(ctx, uri, suggestedName) }
                statusMsg = str(R.string.status_imported, name); statusError = false
            } catch (e: Throwable) {
                statusMsg = str(R.string.status_import_failed, e.message ?: ""); statusError = true
            }
            push()
        }
    }

    fun importExternalModel(uri: Uri, suggestedName: String?) {
        if (BenchmarkBatchGate.isActive) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ModelManager.useSharedModel(ctx, uri, suggestedName) }
                releaseSwitchedAwayModel()
                statusMsg = if (result.inPlace) {
                    str(R.string.status_external_active, result.name)
                } else {
                    str(R.string.status_imported, result.name)
                }
                statusError = false
            } catch (e: Throwable) {
                statusMsg = str(R.string.status_import_failed, e.message ?: ""); statusError = true
            }
            push()
        }
    }

    fun selfTest() {
        if (BenchmarkBatchGate.isActive) return
        SherpaPlugins.plugin?.selfTestNotice(ctx)?.let {
            statusMsg = it
            statusError = false; push(); return
        }
        val local = LocalModelsPlugins.plugin ?: return
        statusMsg = str(R.string.status_running_selftest); statusError = false; push()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { local.selfTest(ctx) }
            statusMsg = result.message; statusError = result.failed
            push()
        }
    }

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host ?: "huggingface.co"
    } catch (e: Exception) {
        "huggingface.co"
    }

    // The run itself lives outside the view model so that leaving the app cannot take it down.
    val transcription: StateFlow<TranscribeUi?> = TranscriptionRun.state
    private var lastAutoSavedRunKey: String? = null

    init {
        TranscriptionRun.restore(ctx)
        viewModelScope.launch {
            BenchmarkStore.revisions.collect { push() }
        }
        viewModelScope.launch {
            BenchmarkRunner.state.collect {
                benchmarkBatch = it
                push()
            }
        }
        viewModelScope.launch {
            TranscriptionRun.state.collect { current ->
                if (current != null && !current.running && current.error == null && current.text.isNotBlank() && current.percent == 100) {
                    val key = "${current.fileName}_${current.segments.size}_${current.text.length}"
                    if (key != lastAutoSavedRunKey) {
                        lastAutoSavedRunKey = key
                        withContext(Dispatchers.IO) {
                            val savedEntry = TranscriptHistory.save(ctx, current)
                            TranscriptionRun.setHistoryId(savedEntry.id)
                            historyCache = null
                        }
                        push()
                    }
                }
            }
        }
    }

    fun transcribeFile(uri: Uri, displayName: String?) {
        TranscriptionRun.start(ctx, uri, displayName ?: "audio", source = uri)
    }

    fun benchmarkFile(uri: Uri, displayName: String?) {
        BenchmarkRunner.start(ctx, uri, displayName ?: "audio")
    }

    fun cancelBenchmark() {
        BenchmarkRunner.cancel()
    }

    fun setTranscriptFormat(format: TranscriptFormat) {
        TranscriptionRun.setFormat(format)
    }

    fun exportTranscriptFile(
        uri: Uri,
        fileName: String,
        segments: List<TranscriptSegment>,
        format: TranscriptFormat,
        showTimestamps: Boolean = true,
        showSpeakers: Boolean = true,
        colorSpeakers: Boolean = false,
        fallbackText: String = ""
    ) {
        val wordThreshold = ModelManager.paragraphWordThreshold(ctx)
        viewModelScope.launch(Dispatchers.IO) {
            AudioDecoder.cleanStaleTempFiles(ctx.cacheDir)
            val ok = try {
                if (format == TranscriptFormat.DOCX) {
                    val tempFile = File.createTempFile("temp_export_", ".docx", ctx.cacheDir)
                    DocxExporter.exportToDocx(tempFile, fileName, segments, wordThreshold, showTimestamps, showSpeakers, colorSpeakers)
                    ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out) }
                    }
                    tempFile.delete()
                    true
                } else {
                    val text = if (segments.isNotEmpty()) {
                        segments.format(format, wordThreshold, showTimestamps, showSpeakers)
                    } else fallbackText
                    writeText(uri, text, "wt")
                    true
                }
            } catch (e: Throwable) {
                if (format == TranscriptFormat.DOCX) {
                    false
                } else {
                    try {
                        val text = if (segments.isNotEmpty()) {
                            segments.format(format, wordThreshold, showTimestamps, showSpeakers)
                        } else fallbackText
                        writeText(uri, text, "w")
                        true
                    } catch (e2: Throwable) {
                        false
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (ok) R.string.transcript_saved else R.string.transcript_save_failed
                Toast.makeText(ctx, str(msg), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveTranscript(uri: Uri) {
        val t = transcription.value ?: return
        exportTranscriptFile(
            uri = uri,
            fileName = t.fileName,
            segments = t.segments,
            format = t.format,
            showTimestamps = t.showTimestamps,
            showSpeakers = t.showSpeakers,
            colorSpeakers = t.colorizeSpeakerLabels,
            fallbackText = t.formatted
        )
    }

    fun toggleTranscriptColorizeSpeakers(enabled: Boolean) {
        TranscriptionRun.toggleColorizeSpeakers(enabled)
    }

    fun updateHistoryEntry(id: String, updatedSegments: List<TranscriptSegment>) {
        viewModelScope.launch(Dispatchers.IO) {
            TranscriptHistory.updateEntry(ctx, id, updatedSegments)
            historyCache = null
            withContext(Dispatchers.Main) {
                push()
            }
        }
    }

    fun updateHistoryEntryColorize(id: String, colorize: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            TranscriptHistory.updateColorize(ctx, id, colorize)
            historyCache = null
            withContext(Dispatchers.Main) {
                push()
            }
        }
    }

    fun saveTextToUri(uri: Uri, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                writeText(uri, text, "wt")
                true
            } catch (e: Throwable) {
                try {
                    writeText(uri, text, "w")
                    true
                } catch (e2: Throwable) {
                    false
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (ok) R.string.transcript_saved else R.string.transcript_save_failed
                Toast.makeText(ctx, str(msg), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeText(uri: Uri, text: String, mode: String) {
        val out = ctx.contentResolver.openOutputStream(uri, mode)
            ?: throw RuntimeException("Can't open $uri")
        out.bufferedWriter().use { it.write(text) }
    }

    fun cancelTranscription() {
        TranscriptionRun.cancel(ctx)
    }

    fun dismissTranscription() {
        TranscriptionRun.dismiss(ctx)
    }

    private var historyCache: List<TranscriptHistory.Entry>? = null

    private fun history(): List<TranscriptHistory.Entry> =
        historyCache ?: TranscriptHistory.load(ctx).also { historyCache = it }

    fun saveTranscriptToHistory() {
        val transcript = transcription.value ?: return
        if (transcript.running || transcript.error != null || transcript.text.isBlank()) return
        dismissTranscription()
        viewModelScope.launch(Dispatchers.IO) {
            val savedEntry = TranscriptHistory.save(ctx, transcript)
            TranscriptionRun.setHistoryId(savedEntry.id)
            historyCache = null
            withContext(Dispatchers.Main) {
                toast(R.string.history_saved)
                push()
            }
        }
    }

    fun deleteHistoryEntry(id: String) {
        TranscriptHistory.delete(ctx, id)
        historyCache = null
        push()
    }

    fun deleteHistoryEntries(ids: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { TranscriptHistory.delete(ctx, it) }
            historyCache = null
            push()
        }
    }

    fun renameHistoryEntry(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            TranscriptHistory.renameEntry(ctx, id, newName)
            historyCache = null
            push()
        }
    }

    fun saveHistoryEntryToFile(id: String, uri: Uri) {
        val text = history().firstOrNull { it.id == id }?.text ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                writeText(uri, text, "wt")
                true
            } catch (e: Throwable) {
                try {
                    writeText(uri, text, "w")
                    true
                } catch (e2: Throwable) {
                    false
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (ok) R.string.transcript_saved else R.string.transcript_save_failed
                Toast.makeText(ctx, str(msg), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val recorder = VoiceRecorder(ctx)

    private val _recording = MutableStateFlow<RecordingUi?>(null)
    val recording: StateFlow<RecordingUi?> = _recording

    private var recordJob: Job? = null
    private var recordFile: File? = null

    fun startRecording() {
        if (_recording.value != null || TranscriptionRun.running || BenchmarkBatchGate.isActive) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(R.string.record_denied); return
        }
        val file = File(recordingsDir(), str(R.string.record_name, stamp()) + RECORDING_EXTENSION)
        try {
            recorder.start(file)
        } catch (e: Throwable) {
            toast(R.string.record_failed); return
        }
        recordFile = file
        _recording.value = RecordingUi(0, emptyList())
        val startedAt = SystemClock.elapsedRealtime()
        recordJob = viewModelScope.launch {
            while (true) {
                delay(LEVEL_INTERVAL_MS)
                val level = recorder.level()
                val current = _recording.value ?: break
                _recording.value = current.copy(
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    levels = (current.levels + level).takeLast(LEVEL_HISTORY)
                )
            }
        }
    }

    // Stopping hands the take straight to transcription — that is the only reason it was recorded.
    fun stopRecording() {
        val file = endRecording() ?: return
        if (!recorder.stop() || file.length() == 0L) {
            file.delete()
            toast(R.string.record_empty); return
        }
        TranscriptionRun.start(ctx, Uri.fromFile(file), file.nameWithoutExtension, source = null) { file.delete() }
    }

    fun cancelRecording() {
        val file = endRecording() ?: return
        recorder.stop()
        file.delete()
    }

    private fun endRecording(): File? {
        recordJob?.cancel()
        recordJob = null
        _recording.value = null
        val file = recordFile
        recordFile = null
        return file
    }

    // Nothing is kept between runs: a leftover here is from a take whose process died mid-recording.
    private fun recordingsDir(): File {
        val dir = File(ctx.cacheDir, "recordings")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return dir
    }

    private fun stamp(): String = SimpleDateFormat(STAMP_PATTERN, Locale.ROOT).format(Date())

    // The status box sits far below the record button; a take that never started has to say so
    // where the user is looking.
    private fun toast(id: Int) = Toast.makeText(ctx, str(id), Toast.LENGTH_SHORT).show()

    override fun onCleared() {
        super.onCleared()
        cancelRecording()
    }

    private companion object {
        const val LEVEL_INTERVAL_MS = 100L
        const val LEVEL_HISTORY = 28
        const val RECORDING_EXTENSION = ".m4a"

        // Doubles as the name on screen and on the saved transcript, so no colon and no dot.
        const val STAMP_PATTERN = "yyyy-MM-dd HH-mm"
    }
}
