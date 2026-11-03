package org.scrib.transcriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val sharedVm: TranscriberViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on the first creation: a recreation (rotation) must not restart the work.
        if (savedInstanceState == null) {
            maybeTranscribeShared(intent)
        }
        setContent {
            val vm: TranscriberViewModel = viewModel()
            val state by vm.state.collectAsState()
            val recording by vm.recording.collectAsState()
            val transcription by vm.transcription.collectAsState()

            LaunchedEffect(recording) {
                if (recording != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            TranscriberTheme(themeMode = state.appTheme) {
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            vm.refresh()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val batchPicker = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        val files = uris.map { uri ->
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            uri to (queryDisplayName(this@MainActivity, uri) ?: "audio.mp3")
                        }
                        vm.queueBatchItems(files)
                    }
                }

                var showAbout by rememberSaveable { mutableStateOf(false) }
                if (showAbout) {
                    BackHandler { showAbout = false }
                    AboutScreen(onBack = { showAbout = false })
                } else {
                    TranscriberScreen(
                        state = state,
                        onDownload = vm::download,
                        onCancel = vm::cancel,
                        onUse = vm::activate,
                        onDelete = vm::delete,
                        onAddUrl = vm::addCustom,
                        onImport = vm::importModel,
                        onImportExternal = vm::importExternalModel,
                        onSelfTest = vm::selfTest,
                        onBenchmarkFile = vm::benchmarkFile,
                        onCancelBenchmark = vm::cancelBenchmark,
                        onPickLanguage = vm::setupForLanguage,
                        onLiveStats = vm::setLiveStats,
                        onParagraphWordThresholdChange = vm::setParagraphWordThreshold,
                        onDiarizationSensitivityChange = vm::setDiarizationSensitivity,
                        onExpectedSpeakersChange = vm::setExpectedSpeakers,
                        onSelectAppTheme = vm::setAppTheme,
                        onSelectDefaultFormat = vm::setDefaultExportFormat,
                        onRenameSpeaker = vm::renameSpeaker,
                        onRenameSpeakerInstance = vm::renameSpeakerInstance,
                        onDeleteSpeakerLabel = vm::deleteSpeakerLabel,
                        onInsertSpeakerLabel = vm::insertSpeakerLabel,
                        onInsertSpeakerLabelAtTimestamp = vm::insertSpeakerLabelAtTimestamp,
                        onReplaceTextInstance = vm::replaceTextInstance,
                        onReplaceAllText = vm::replaceAllText,
                        onUpdateTurnBlockText = vm::updateTurnBlockText,
                        onToggleTimestamps = vm::toggleTimestamps,
                        onToggleSpeakers = vm::toggleSpeakers,
                        onColorizeSpeakerLabelsChange = vm::setColorizeSpeakerLabels,
                        onToggleColorizeSpeakers = vm::toggleTranscriptColorizeSpeakers,
                        onImportSherpa = vm::importSherpa,
                        sherpaPlugin = SherpaPlugins.plugin,
                        endpointPlugin = EndpointPlugins.plugin,
                        onEndpointChanged = vm::refresh,
                        recording = recording,
                        onStartRecording = vm::startRecording,
                        onStopRecording = vm::stopRecording,
                        onCancelRecording = vm::cancelRecording,
                        transcription = transcription,
                        onTranscribeFile = vm::transcribeFile,
                        onCancelTranscription = vm::cancelTranscription,
                        onDismissTranscription = vm::dismissTranscription,
                        onSaveTranscript = vm::saveTranscript,
                        onTranscriptFormat = vm::setTranscriptFormat,
                        onSaveToHistory = vm::saveTranscriptToHistory,
                        onDeleteHistory = vm::deleteHistoryEntry,
                        onDeleteHistoryEntries = vm::deleteHistoryEntries,
                        onRenameHistory = vm::renameHistoryEntry,
                        onSaveHistoryFile = { _, uri, text, format, segments, showTimestamps, showSpeakers, colorizeSpeakerLabels, fileName ->
                            vm.exportTranscriptFile(
                                uri = uri,
                                fileName = fileName,
                                segments = segments,
                                format = format,
                                showTimestamps = showTimestamps,
                                showSpeakers = showSpeakers,
                                colorSpeakers = colorizeSpeakerLabels,
                                fallbackText = text
                            )
                        },
                        onUpdateHistoryEntry = vm::updateHistoryEntry,
                        onUpdateHistoryColorize = vm::updateHistoryEntryColorize,
                        onTogglePinned = vm::togglePinned,
                        batchState = BatchQueueManager.state.collectAsState().value,
                        onPickBatchFiles = { batchPicker.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/opus", "application/octet-stream")) },
                        onStartBatch = vm::startBatchProcessing,
                        onRemoveBatchItem = BatchQueueManager::removeItem,
                        benchmarkRuns = state.benchmarkRuns,
                        onAbout = { showAbout = true }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeTranscribeShared(intent)
    }

    // The platform hands a backgrounded app silence instead of the microphone, so a take that
    // would go on filling with nothing is finished here — a rotation is not leaving the app.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            sharedVm.stopRecording()
        }
    }

    // A file arriving through the system share sheet goes straight into transcription. Without a
    // model there is nothing to run it with — the first-run screen then explains the download.
    private fun maybeTranscribeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: return
        if (!ModelManager.hasActiveModel(this)) {
            return
        }
        sharedVm.transcribeFile(uri, queryDisplayName(this, uri))
    }
}
