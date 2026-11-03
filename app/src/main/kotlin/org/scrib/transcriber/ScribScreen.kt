package org.scrib.transcriber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

internal data class Actions(
    val onDownload: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onUse: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onAddUrl: (String) -> Unit,
    val onImport: (Uri, String?) -> Unit,
    val onSelfTest: () -> Unit,
    val onTogglePinned: (String) -> Unit
)

private enum class TranscriberTab(val labelRes: Int, val icon: Int) {
    Home(R.string.nav_home, R.drawable.ic_tab_home),
    Record(R.string.nav_record, R.drawable.ic_tab_transcripts),
    File(R.string.nav_transcribe, R.drawable.ic_tab_graphic_eq),
    Settings(R.string.nav_settings, R.drawable.ic_tab_settings)
}

@Composable
fun TranscriberScreen(
    state: TranscriberUiState,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddUrl: (String) -> Unit,
    onImport: (Uri, String?) -> Unit,
    onImportExternal: (Uri, String?) -> Unit,
    onSelfTest: () -> Unit,
    onBenchmarkFile: (Uri, String?) -> Unit,
    onCancelBenchmark: () -> Unit,
    onPickLanguage: (LanguageOption) -> Unit,
    onLiveStats: (Boolean) -> Unit,
    onImportSherpa: (String, String, List<String>?, Map<String, Uri>, (Throwable?) -> Unit) -> Unit,
    sherpaPlugin: SherpaPlugin?,
    endpointPlugin: EndpointPlugin?,
    onEndpointChanged: () -> Unit,
    recording: RecordingUi?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    transcription: TranscribeUi?,
    onTranscribeFile: (Uri, String?) -> Unit,
    onCancelTranscription: () -> Unit,
    onDismissTranscription: () -> Unit,
    onSaveTranscript: (Uri) -> Unit,
    onTranscriptFormat: (TranscriptFormat) -> Unit,
    onSaveToHistory: () -> Unit,
    onDeleteHistory: (String) -> Unit,
    onDeleteHistoryEntries: (Set<String>) -> Unit = {},
    onRenameHistory: (String, String) -> Unit = { _, _ -> },
    onSaveHistoryFile: (String, Uri, String, TranscriptFormat, List<TranscriptSegment>, Boolean, Boolean, Boolean, String) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onTogglePinned: (String) -> Unit,
    onParagraphWordThresholdChange: (Int) -> Unit = {},
    onDiarizationSensitivityChange: (String) -> Unit = {},
    onExpectedSpeakersChange: (String) -> Unit = {},
    onSelectAppTheme: (String) -> Unit = {},
    onSelectDefaultFormat: (String) -> Unit = {},
    onColorizeSpeakerLabelsChange: (Boolean) -> Unit = {},
    onRenameSpeaker: (String, String) -> Unit = { _, _ -> },
    onRenameSpeakerInstance: (Int, String) -> Unit = { _, _ -> },
    onDeleteSpeakerLabel: (Int) -> Unit = {},
    onInsertSpeakerLabel: (Int, String) -> Unit = { _, _ -> },
    onInsertSpeakerLabelAtTimestamp: (Long, String) -> Unit = { _, _ -> },
    onReplaceTextInstance: (Int, String, String) -> Unit = { _, _, _ -> },
    onReplaceAllText: (String, String) -> Unit = { _, _ -> },
    onUpdateTurnBlockText: (Int, String, String) -> Unit = { _, _, _ -> },
    onToggleTimestamps: (Boolean) -> Unit = {},
    onToggleSpeakers: (Boolean) -> Unit = {},
    onToggleColorizeSpeakers: (Boolean) -> Unit = {},
    onUpdateHistoryEntry: (String, List<TranscriptSegment>) -> Unit = { _, _ -> },
    onUpdateHistoryColorize: (String, Boolean) -> Unit = { _, _ -> },
    batchState: BatchBatchState = BatchBatchState(),
    onPickBatchFiles: () -> Unit = {},
    onStartBatch: () -> Unit = {},
    onRemoveBatchItem: (String) -> Unit = {},
    benchmarkRuns: List<BenchmarkRun>,
    onAbout: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val actions = Actions(onDownload, onCancel, onUse, onDelete, onAddUrl, onImport, onSelfTest, onTogglePinned)
    var tab by rememberSaveable { mutableStateOf(TranscriberTab.Home) }
    var showAdd by remember { mutableStateOf(false) }
    var showLanguages by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showBenchmarks by rememberSaveable { mutableStateOf(false) }
    var openHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var prevBatchCount by remember { mutableStateOf(0) }
    var isTranscriptionMinimized by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(transcription?.running) {
        if (transcription?.running == true) {
            isTranscriptionMinimized = false
        }
    }

    LaunchedEffect(batchState.items.size) {
        if (batchState.items.size > prevBatchCount && batchState.items.isNotEmpty()) {
            showBatchDialog = true
        }
        prevBatchCount = batchState.items.size
    }

    val context = LocalContext.current
    var modelsExpanded by remember { mutableStateOf(SectionState.standardModels(context)) }
    var customExpanded by remember { mutableStateOf(SectionState.customModels(context)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, queryDisplayName(context, uri))
    }
    val externalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportExternal(uri, queryDisplayName(context, uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onTranscribeFile(uri, queryDisplayName(context, uri))
    }
    val benchmarkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onBenchmarkFile(uri, queryDisplayName(context, uri))
        }
    }
    // Already granted, and the contract answers without showing anything; a refusal is reported by
    // the recording attempt itself.
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStartRecording()
    }
    // Asked for at the first run, where the shade is about to become the only place the progress
    // shows. A refusal costs the notification, not the run.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val started = transcription != null && !state.benchmarkBatch.running
    LaunchedEffect(started) {
        if (started && android.os.Build.VERSION.SDK_INT >= 33) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(tab != TranscriberTab.Home) { tab = TranscriberTab.Home }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Box(Modifier.weight(1f).statusBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                AppBar(onAbout, showAbout = tab == TranscriberTab.Home || tab == TranscriberTab.Settings)
                when (tab) {
                    TranscriberTab.Home -> HomeTab(
                        state = state,
                        actions = actions,
                        onOpenTab = { tab = it },
                        onOpenEntry = { openHistoryId = it.id }
                    )
                    TranscriberTab.Record -> RecordTab(
                        state,
                        actions,
                        onOpenTab = { tab = it },
                        onRecord = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) },
                        onOpenEntry = { openHistoryId = it.id },
                        onDeleteEntry = onDeleteHistory,
                        onDeleteEntries = onDeleteHistoryEntries,
                        onRenameEntry = onRenameHistory
                    )
                    TranscriberTab.File -> FileTab(
                        state,
                        actions,
                        batchState = batchState,
                        onOpenTab = { tab = it },
                        onRecord = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) },
                        onPickSingle = { audioPicker.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/opus", "application/octet-stream")) },
                        onPickBatch = onPickBatchFiles,
                        onOpenBatchQueue = { showBatchDialog = true }
                    )
                    TranscriberTab.Settings -> SettingsTab(
                        state = state,
                        actions = actions,
                        modelsExpanded = modelsExpanded,
                        customExpanded = customExpanded,
                        onParagraphWordThresholdChange = onParagraphWordThresholdChange,
                        onDiarizationSensitivityChange = onDiarizationSensitivityChange,
                        onExpectedSpeakersChange = onExpectedSpeakersChange,
                        onSelectAppTheme = onSelectAppTheme,
                        onSelectDefaultFormat = onSelectDefaultFormat,
                        onColorizeSpeakerLabelsChange = onColorizeSpeakerLabelsChange,
                        onToggleStandard = {
                            val next = !modelsExpanded
                            modelsExpanded = next
                            SectionState.setStandardModels(context, next)
                        },
                        onToggleCustom = {
                            val next = !customExpanded
                            customExpanded = next
                            SectionState.setCustomModels(context, next)
                        },
                        sherpaPlugin = sherpaPlugin,
                        endpointPlugin = endpointPlugin,
                        onEndpointChanged = onEndpointChanged,
                        onImportSherpa = onImportSherpa,
                        onPickLanguage = { showLanguages = true },
                        onAddCustom = { showAdd = true },
                        onImportBin = { picker.launch(arrayOf("*/*")) },
                        onImportExternal = { externalPicker.launch(arrayOf("*/*")) },
                        onRequestDelete = { deleteTarget = it },
                        onLiveStats = onLiveStats,
                        onOpenDictionary = { openDictionary(context) },
                        onOpenBenchmarks = {
                            if (BenchmarkStore.isFullscreen(context)) {
                                context.startActivity(Intent(context, BenchmarkHistoryActivity::class.java))
                            } else {
                                showBenchmarks = true
                            }
                        },
                        onSelfTest = onSelfTest,
                        onBenchmarkFile = { benchmarkPicker.launch(arrayOf("audio/*", "video/*")) },
                        onCancelBenchmark = onCancelBenchmark,
                        transcription = transcription,
                        recording = recording
                    )
                }
            }
        }
        if (isTranscriptionMinimized && transcription != null && !state.benchmarkBatch.running) {
            TranscriptionMiniBar(
                transcription = transcription,
                onExpand = { isTranscriptionMinimized = false },
                onCancel = onCancelTranscription,
                onDismiss = onDismissTranscription
            )
        }
        if (transcription == null || state.benchmarkBatch.running || isTranscriptionMinimized) {
            BottomBar(tab) { nextTab ->
                tab = nextTab
                if (transcription?.running == true) {
                    isTranscriptionMinimized = true
                }
            }
        }
    }

    if (showAdd) {
        AddUrlDialog(onDismiss = { showAdd = false }, onConfirm = { showAdd = false; onAddUrl(it) })
    }
    if (showLanguages) {
        LanguageDialog(onDismiss = { showLanguages = false }, onPick = { showLanguages = false; onPickLanguage(it) })
    }
    if (showBenchmarks) {
        BenchmarkHistoryDialog(
            benchmarkRuns,
            onDismiss = { showBenchmarks = false },
            onOpenFullscreen = {
                showBenchmarks = false
                BenchmarkStore.setFullscreen(context, true)
                context.startActivity(Intent(context, BenchmarkHistoryActivity::class.java))
            }
        )
    }
    recording?.let { r ->
        RecordingDialog(r, remoteHost = state.remoteHost, onStop = onStopRecording, onCancel = onCancelRecording)
    }
    if (transcription?.error?.contains("Low storage space") == true) {
        AlertDialog(
            onDismissRequest = onDismissTranscription,
            title = { Text("Low Storage Space") },
            text = {
                Text(
                    "Transcribing this recording requires temporary storage space, but your device is running low on available internal storage.\n\nPlease free up space on your device before transcribing."
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissTranscription) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    if (showBatchDialog && batchState.items.isNotEmpty()) {
        BatchQueueDialog(
            batchState = batchState,
            onClose = { showBatchDialog = false },
            onClear = {
                BatchQueueManager.clear()
                showBatchDialog = false
            },
            onStartBatch = onStartBatch,
            onPickBatchFiles = onPickBatchFiles,
            onRemoveItem = onRemoveBatchItem,
            onOpenItem = { item ->
                if (item.resultText != null) {
                    val entry = TranscriptHistory.Entry(
                        id = item.id,
                        savedAtMs = System.currentTimeMillis(),
                        fileName = item.fileName,
                        modelName = null,
                        text = item.resultText,
                        segments = item.segments
                    )
                    openHistoryId = entry.id
                }
            }
        )
    }

    if (transcription != null && !state.benchmarkBatch.running && !isTranscriptionMinimized) {
        transcription?.let { t ->
            TranscriptionDialog(
                t,
                onCancel = onCancelTranscription,
                onClose = {
                    if (t.running) {
                        isTranscriptionMinimized = true
                    } else {
                        if (t.error == null && t.text.isNotBlank()) {
                            onSaveToHistory()
                        } else {
                            onDismissTranscription()
                        }
                    }
                },
                onSave = onSaveTranscript,
                onFormat = onTranscriptFormat,
                onSaveToHistory = onSaveToHistory,
                onRenameSpeaker = onRenameSpeaker,
                onRenameSpeakerInstance = onRenameSpeakerInstance,
                onDeleteSpeakerLabel = onDeleteSpeakerLabel,
                onInsertSpeakerLabel = onInsertSpeakerLabel,
                onInsertSpeakerLabelAtTimestamp = onInsertSpeakerLabelAtTimestamp,
                onReplaceTextInstance = onReplaceTextInstance,
                onReplaceAllText = onReplaceAllText,
                onUpdateTurnBlockText = onUpdateTurnBlockText,
                onToggleTimestamps = onToggleTimestamps,
                onToggleSpeakers = onToggleSpeakers,
                onToggleColorizeSpeakers = onToggleColorizeSpeakers,
                onOpenBatchQueue = { showBatchDialog = true }
            )
        }
    }

    openHistoryId?.let { id ->
        state.history.firstOrNull { it.id == id }?.let { entry ->
            HistoryEntryDialog(
                entry = entry,
                defaultExportFormat = state.defaultExportFormat,
                defaultColorizeSpeakers = state.colorizeSpeakerLabels,
                onClose = { openHistoryId = null },
                onSaveFile = { uri, format, segments, showTimestamps, showSpeakers, colorizeSpeakers, text ->
                    onSaveHistoryFile(id, uri, text, format, segments, showTimestamps, showSpeakers, colorizeSpeakers, entry.fileName)
                },
                onUpdateEntry = onUpdateHistoryEntry,
                onUpdateColorize = onUpdateHistoryColorize
            )
        }
    }
    deleteTarget?.let { target ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_model_q)) },
            text = { Text(sherpaPlugin?.displayNameFor(context, target) ?: target.removePrefix(ModelManager.EXTERNAL_PREFIX)) },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun HomeTab(
    state: TranscriberUiState,
    actions: Actions,
    onOpenTab: (TranscriberTab) -> Unit,
    onOpenEntry: (TranscriptHistory.Entry) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item {
            if (state.firstRun) {
                NudgeCard(state, actions, onOpenSettings = { onOpenTab(TranscriberTab.Settings) })
            } else {
                StatusCard(state, onOpenSettings = { onOpenTab(TranscriberTab.Settings) })
            }
        }
        if (!state.firstRun) {
            val quick = (state.standard + state.custom + state.sherpaRows).filter { it.id in state.pinnedIds }
            if (quick.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.quick_access_title),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp)
                    )
                }
                items(quick, key = { it.id }) { row ->
                    QuickModelCard(row, actions)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Transcripts",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = cs.onSurfaceVariant
                    )
                    if (state.history.isNotEmpty()) {
                        TextButton(onClick = { onOpenTab(TranscriberTab.Record) }) {
                            Text("View All (${state.history.size}) →", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (state.history.isEmpty()) {
                item {
                    Surface(
                        color = cs.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No transcripts yet",
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                color = cs.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Record live audio or transcribe a file from the Transcribe tab to see saved transcripts here.",
                                fontSize = 12.5.sp,
                                color = cs.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                val recents = state.history.take(4)
                items(recents, key = { it.id }) { entry ->
                    RecentTranscriptCard(entry = entry, onClick = { onOpenEntry(entry) })
                }
            }
        }
    }
}

@Composable
private fun RecentTranscriptCard(entry: TranscriptHistory.Entry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clickableRow(RoundedCornerShape(14.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.ic_tab_transcripts),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.fileName,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.text.take(80).replace('\n', ' ').trim(),
                    fontSize = 11.5.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                historyTimestamp(entry.savedAtMs),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun QuickModelCard(row: ModelRow, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    val active = row.state == RowState.Active
    Surface(
        color = if (active) cs.primaryContainer else cs.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) cs.primary else Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { actions.onUse(row.id) }
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.name,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (active) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.badge,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (active) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant
                )
            }
            IconButton(onClick = { actions.onTogglePinned(row.id) }) {
                Icon(
                    painterResource(R.drawable.ic_star_filled),
                    contentDescription = stringResource(R.string.quick_access_remove),
                    tint = if (active) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun RecordTab(
    state: TranscriberUiState,
    actions: Actions,
    onOpenTab: (TranscriberTab) -> Unit,
    onRecord: () -> Unit,
    onOpenEntry: (TranscriptHistory.Entry) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onDeleteEntries: (Set<String>) -> Unit = {},
    onRenameEntry: (String, String) -> Unit = { _, _ -> }
) {
    if (state.firstRun) {
        SingleCardColumn { NudgeCard(state, actions, onOpenSettings = { onOpenTab(TranscriberTab.Settings) }) }
        return
    }
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedIds = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf<String>() }
    var showBulkDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds.clear()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            if (!isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.history_section_title),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                        color = cs.primary
                    )
                }
            } else {
                Surface(
                    color = cs.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedIds.clear()
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    contentDescription = "Cancel",
                                    tint = cs.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                "${selectedIds.size} selected",
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (selectedIds.size == state.history.size) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(state.history.map { it.id })
                                }
                            }) {
                                Text(
                                    if (selectedIds.size == state.history.size) "Deselect All" else "Select All",
                                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = {
                                    val selectedEntries = state.history.filter { selectedIds.contains(it.id) }
                                    if (selectedEntries.isNotEmpty()) {
                                        val shareText = selectedEntries.joinToString("\n\n" + "=".repeat(40) + "\n\n") { entry ->
                                            "--- ${entry.fileName} ---\n\n${entry.text}"
                                        }
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "Transcripts (${selectedEntries.size})")
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Transcripts"))
                                    }
                                },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_share),
                                    contentDescription = "Share",
                                    tint = if (selectedIds.isNotEmpty()) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.38f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = { showBulkDeleteConfirm = true },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_delete),
                                    contentDescription = "Delete",
                                    tint = if (selectedIds.isNotEmpty()) cs.error else cs.onSurfaceVariant.copy(alpha = 0.38f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.history.isEmpty()) {
            item {
                Text(
                    "No saved transcripts yet. Transcribe an audio file or start a recording from the Home tab to view saved transcripts here.",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            }
        } else {
            items(state.history, key = { it.id }) { entry ->
                val isSelected = selectedIds.contains(entry.id)
                HistoryRowCard(
                    entry = entry,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onToggleSelect = {
                        if (isSelected) selectedIds.remove(entry.id) else selectedIds.add(entry.id)
                    },
                    onEnterSelection = {
                        isSelectionMode = true
                        selectedIds.clear()
                        selectedIds.add(entry.id)
                    },
                    onOpen = { onOpenEntry(entry) },
                    onDelete = { onDeleteEntry(entry.id) },
                    onRename = { newName -> onRenameEntry(entry.id, newName) }
                )
            }
        }
    }

    if (showBulkDeleteConfirm && selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete ${selectedIds.size} Transcripts?") },
            text = { Text("Are you sure you want to delete the selected transcripts? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntries(selectedIds.toSet())
                    selectedIds.clear()
                    isSelectionMode = false
                    showBulkDeleteConfirm = false
                }) {
                    Text("Delete", color = cs.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRowCard(
    entry: TranscriptHistory.Entry,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit = {},
    showMetadata: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(entry.fileName) { mutableStateOf(entry.fileName) }

    Surface(
        color = if (isSelected) cs.primaryContainer.copy(alpha = 0.45f) else cs.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else {
                        onOpen()
                    }
                },
                onLongClick = {
                    if (!isSelectionMode) {
                        onEnterSelection()
                    } else {
                        onToggleSelect()
                    }
                }
            )
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.fileName,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            historyTimestamp(entry.savedAtMs),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant
                        )
                    }

                    if (!isSelectionMode) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurfaceVariant)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open") },
                                    onClick = { menuExpanded = false; onOpen() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menuExpanded = false
                                        renameText = entry.fileName
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { menuExpanded = false; onDelete() }
                                )
                            }
                        }
                    }
                }

                if (showMetadata) {
                    val uniqueSpeakers = remember(entry.segments) { entry.segments.uniqueSpeakers() }
                    if (uniqueSpeakers.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = if (isSelectionMode) onToggleSelect else onOpen,
                                label = { Text("${uniqueSpeakers.size} Speakers", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    entry.text,
                    fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Transcript") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = renameText.trim()
                        if (clean.isNotBlank()) {
                            onRename(clean)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun historyTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

@Composable
private fun FileTab(
    state: TranscriberUiState,
    actions: Actions,
    batchState: BatchBatchState,
    onOpenTab: (TranscriberTab) -> Unit,
    onRecord: () -> Unit,
    onPickSingle: () -> Unit,
    onPickBatch: () -> Unit,
    onOpenBatchQueue: () -> Unit
) {
    if (state.firstRun) {
        SingleCardColumn { NudgeCard(state, actions, onOpenSettings = { onOpenTab(TranscriberTab.Settings) }) }
        return
    }
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RecordAndTranscribeButton(onRecord)
        TranscribeFileButton(onPickSingle)
        BatchTranscribeButton(
            onClick = onPickBatch,
            label = "Batch Transcribe Audio Files"
        )
        if (batchState.items.isNotEmpty()) {
            BatchQueueButton(
                onClick = onOpenBatchQueue,
                label = "View Active Batch Queue (${batchState.completedCount}/${batchState.items.size} Done)"
            )
        }
        Text(
            stringResource(R.string.transcribe_background_hint),
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
            lineHeight = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AppThemeEntry(currentTheme: String, onSelectTheme: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("App Theme", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (key, label) ->
                    val selected = currentTheme.equals(key, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectTheme(key) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultExportFormatEntry(currentFormat: String, onSelectFormat: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Default Export Format", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("docx", "txt", "srt", "vtt", "lrc").forEach { fmt ->
                    val selected = currentFormat.equals(fmt, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFormat(fmt) },
                        label = { Text(fmt.uppercase(), fontSize = 11.5.sp) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    state: TranscriberUiState,
    actions: Actions,
    modelsExpanded: Boolean,
    customExpanded: Boolean,
    onToggleStandard: () -> Unit,
    onToggleCustom: () -> Unit,
    sherpaPlugin: SherpaPlugin?,
    endpointPlugin: EndpointPlugin?,
    onEndpointChanged: () -> Unit,
    onImportSherpa: (String, String, List<String>?, Map<String, Uri>, (Throwable?) -> Unit) -> Unit,
    onPickLanguage: () -> Unit,
    onAddCustom: () -> Unit,
    onImportBin: () -> Unit,
    onImportExternal: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onLiveStats: (Boolean) -> Unit,
    onParagraphWordThresholdChange: (Int) -> Unit = {},
    onDiarizationSensitivityChange: (String) -> Unit = {},
    onExpectedSpeakersChange: (String) -> Unit = {},
    onSelectAppTheme: (String) -> Unit = {},
    onSelectDefaultFormat: (String) -> Unit = {},
    onColorizeSpeakerLabelsChange: (Boolean) -> Unit = {},
    onOpenDictionary: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onSelfTest: () -> Unit,
    onBenchmarkFile: () -> Unit,
    onCancelBenchmark: () -> Unit,
    transcription: TranscribeUi?,
    recording: RecordingUi?
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)
    ) {
        if (state.firstRun) {
            item { NudgeCard(state, actions) }
        }
        if (BuildConfig.LOCAL_MODELS) {
            item {
                Text(
                    stringResource(R.string.models_explainer),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp)
                )
            }
            item { LanguageEntry(onPickLanguage) }
            item { StandardHeader(expanded = modelsExpanded, onToggle = onToggleStandard) }
            if (modelsExpanded) {
                items(state.standard.size) { i -> ModelRowCard(state.standard[i], actions, onRequestDelete) }
            }
        }
        if (sherpaPlugin != null) {
            item {
                sherpaPlugin.SettingsBlocks(
                    rows = state.sherpaRows,
                    busy = state.sherpaBusy,
                    onUse = actions.onUse,
                    onDelete = actions.onDelete,
                    onRequestDelete = onRequestDelete,
                    onImportSherpa = onImportSherpa
                )
            }
        }
        if (endpointPlugin != null) {
            item { endpointPlugin.SettingsBlocks(onChanged = onEndpointChanged) }
        }
        if (BuildConfig.LOCAL_MODELS) {
            item { CustomHeader(expanded = customExpanded, onToggle = onToggleCustom) }
            if (customExpanded) {
                if (state.custom.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.custom_empty),
                            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                            lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                } else {
                    items(state.custom.size) { i -> ModelRowCard(state.custom[i], actions, onRequestDelete) }
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton("+", stringResource(R.string.add_from_hf), onAddCustom)
                    AddButton("↥", stringResource(R.string.import_bin), onImportBin)
                    AddButton("↧", stringResource(R.string.import_external), onImportExternal)
                }
            }
        }
        if (state.statusMsg.isNotEmpty()) {
            item { StatusBox(state.statusMsg, state.statusError) }
        }
        if (BuildConfig.LOCAL_MODELS) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onSelfTest) {
                        Text(stringResource(R.string.run_self_test), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    }
                }
            }
            item {
                BenchmarkBatchControl(
                    state = state.benchmarkBatch,
                    enabled = state.benchmarkModelCount > 0 &&
                        !state.benchmarkBatch.running &&
                        transcription == null && recording == null,
                    onClick = onBenchmarkFile,
                    onCancel = onCancelBenchmark
                )
            }
            val batch = state.benchmarkBatch
            if (batch.total > 0 && !batch.running &&
                (batch.completed > 0 || batch.failed > 0 || batch.cancelled)) {
                item {
                    val text = when {
                        batch.cancelled -> stringResource(R.string.benchmark_batch_cancelled, batch.completed, batch.total)
                        batch.failed > 0 -> stringResource(
                            R.string.benchmark_batch_partial,
                            batch.completed - batch.failed,
                            batch.total,
                            batch.failed
                        )
                        else -> stringResource(R.string.benchmark_batch_done, batch.completed, batch.total)
                    }
                    StatusBox(text, batch.cancelled || batch.failed > 0)
                }
            }
        }
        item {
            Text(
                "Theme & Export Format Options",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
        }
        item { AppThemeEntry(state.appTheme, onSelectAppTheme) }
        item { DefaultExportFormatEntry(state.defaultExportFormat, onSelectDefaultFormat) }

        item {
            Text(
                "Transcript Layout & Speaker Options",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
        }
        item { ParagraphWordThresholdEntry(state.paragraphWordThreshold, onParagraphWordThresholdChange) }
        item { ExpectedSpeakersEntry(state.expectedSpeakersKey, onExpectedSpeakersChange) }
        item { DiarizationSensitivityEntry(state.diarizationSensitivityKey, onDiarizationSensitivityChange) }
        item { ColorizeSpeakerLabelsEntry(state.colorizeSpeakerLabels, onColorizeSpeakerLabelsChange) }
        item { LiveStatsEntry(state.liveStats, onLiveStats) }

        item {
            Text(
                "Custom Vocabulary & Dictionary",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
        }
        item { DictionaryEntry(state.dictionaryEnabled, onOpenDictionary) }

        item {
            Text(
                "Tools & Diagnostics",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
        }
        if (BuildConfig.LOCAL_MODELS) {
            item { BenchmarkEntry(state.benchmarkRuns, onOpenBenchmarks) }
        }
    }
}

@Composable
private fun SingleCardColumn(content: @Composable () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item { content() }
    }
}

@Composable
private fun ServiceCard(title: String, subtitle: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BottomBar(selected: TranscriberTab, onSelect: (TranscriberTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        Surface(color = cs.surfaceContainer) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
                TranscriberTab.entries.forEach { t ->
                    val on = t == selected
                    val columnInteraction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(
                                interactionSource = columnInteraction,
                                indication = null
                            ) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(width = 48.dp, height = 32.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) cs.primaryContainer else Color.Transparent)
                                .clickable { onSelect(t) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(t.icon),
                                contentDescription = stringResource(t.labelRes),
                                tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(t.labelRes),
                            fontSize = 10.sp, lineHeight = 11.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) cs.primary else cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun recordHint(remoteHost: String?): String =
    if (remoteHost != null) stringResource(R.string.record_hint_remote, remoteHost)
    else stringResource(R.string.record_hint)

@Composable
private fun AppBar(onAbout: () -> Unit, showAbout: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground, letterSpacing = (-0.6).sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            if (showAbout) {
                TextButton(onClick = onAbout) {
                    Text(stringResource(R.string.about), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                }
            }
        }
    }
}

@Composable
private fun Bar(h: androidx.compose.ui.unit.Dp, color: Color, alpha: Float) {
    Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = alpha)))
}

@Composable
private fun StatusCard(state: TranscriberUiState, onOpenSettings: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(cs.primary))
                Text(stringResource(if (BuildConfig.LOCAL_MODELS) R.string.active_model else R.string.active_endpoint), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            if (state.activeName != null) {
                Text(state.activeName, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface, letterSpacing = (-0.5).sp)
            } else {
                Text(stringResource(if (BuildConfig.LOCAL_MODELS) R.string.no_active_model else R.string.no_active_endpoint), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(if (BuildConfig.LOCAL_MODELS) R.string.pick_model_hint else R.string.pick_endpoint_hint), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 2.dp)) {
                        Text(stringResource(R.string.nav_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                    }
                }
            }
            PrivacyLine(cs.onSurfaceVariant, topBorder = true, remoteHost = state.remoteHost)
        }
    }
}

@Composable
private fun NudgeCard(state: TranscriberUiState, actions: Actions, onOpenSettings: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val base = state.standard.firstOrNull { it.recommended }
    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.setup_transcription), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
            Text(
                stringResource(if (BuildConfig.LOCAL_MODELS) R.string.nudge_body else R.string.nudge_remote_body),
                fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (BuildConfig.LOCAL_MODELS) {
                Surface(
                    color = cs.primary, shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickableRow(RoundedCornerShape(22.dp)) { base?.let { actions.onDownload(it.id) } }
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (base != null) stringResource(R.string.download_named_size, base.name, base.sizeMb) else stringResource(R.string.action_download),
                            fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary
                        )
                    }
                }
            } else if (onOpenSettings != null) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.nav_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
            }
            PrivacyLine(cs.onPrimaryContainer.copy(alpha = 0.9f), topBorder = false, remoteHost = null)
        }
    }
}

@Composable
private fun PrivacyLine(textColor: Color, topBorder: Boolean, remoteHost: String?) {
    val text = when {
        remoteHost != null -> stringResource(R.string.privacy_remote, remoteHost)
        topBorder -> stringResource(R.string.privacy_full)
        else -> stringResource(R.string.privacy_short)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = if (topBorder) 13.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text,
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor, lineHeight = 17.sp
        )
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    top: Dp = 0.dp,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = top, bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chevron(expanded = expanded, color = cs.onSurfaceVariant)
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            trailing()
        }
    }
}

@Composable
private fun StandardHeader(expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionHeader(
        title = stringResource(R.string.standard_models),
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(5, 8, 11, 14).forEach { Box(Modifier.width(3.dp).height(it.dp).clip(RoundedCornerShape(1.dp)).background(cs.primary)) }
            }
            Text(stringResource(R.string.size), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(start = 4.dp))
        }
    )
}

@Composable
private fun CustomHeader(expanded: Boolean, onToggle: () -> Unit) {
    SectionHeader(
        title = stringResource(R.string.custom_models),
        expanded = expanded,
        onToggle = onToggle,
        top = 8.dp,
    )
}

@Composable
internal fun Chevron(expanded: Boolean, color: Color) {
    // Next to all-caps text the ink sits above the line-box center, so the mark is nudged up
    // to meet the caps rather than the box.
    Canvas(Modifier.size(12.dp).offset(y = -1.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            if (expanded) {
                moveTo(size.width * 0.15f, size.height * 0.35f)
                lineTo(size.width * 0.5f, size.height * 0.7f)
                lineTo(size.width * 0.85f, size.height * 0.35f)
            } else {
                moveTo(size.width * 0.35f, size.height * 0.15f)
                lineTo(size.width * 0.7f, size.height * 0.5f)
                lineTo(size.width * 0.35f, size.height * 0.85f)
            }
        }
        drawPath(path, color, style = stroke)
    }
}

@Composable
private fun QualityBars(tier: Int) {
    val cs = MaterialTheme.colorScheme
    val on = cs.primary
    val off = cs.outlineVariant.copy(alpha = 0.5f)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 5) {
            val h = (5 + i * 3.2f).dp
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.5.dp)).background(if (i < tier) on else off))
        }
    }
}

@Composable
internal fun ModelRowCard(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = row.state == RowState.Active
    val bg = if (active) cs.surfaceContainerHigh else cs.surface
    val onBg = cs.onSurface
    val onBgVar = cs.onSurfaceVariant
    val sizePart = if (row.custom) stringResource(R.string.badge_custom) else stringResource(R.string.size_mb, row.sizeMb)
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (active) 2.5.dp else 1.dp, if (active) cs.primary else cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            row.name,
                            fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = onBg,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        if (active) Pill(stringResource(R.string.active_pill), cs.primary, cs.onPrimary, Modifier.align(Alignment.CenterVertically))
                        if (row.recommended && !active) Pill(stringResource(R.string.recommended).uppercase(), cs.primary, cs.onPrimary, Modifier.align(Alignment.CenterVertically))
                    }
                    Text("${row.badge} · $sizePart", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = onBgVar, modifier = Modifier.padding(top = 4.dp))
                    if (row.state == RowState.Failed) {
                        Text(stringResource(R.string.row_download_failed), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.error, modifier = Modifier.padding(top = 5.dp))
                    }
                }
                if (row.state != RowState.Downloading) {
                    Spacer(Modifier.width(12.dp))
                    QualityBars(row.tier)
                }
            }
            Spacer(Modifier.height(12.dp))
            ActionZone(row, actions, onRequestDelete)
        }
    }
}

@Composable
private fun ActionZone(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (row.state) {
        RowState.NotDownloaded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip(stringResource(R.string.action_download), cs.primaryContainer, cs.onPrimaryContainer) { actions.onDownload(row.id) }
        }
        RowState.Failed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip(stringResource(R.string.action_retry), cs.errorContainer, cs.onErrorContainer) { actions.onDownload(row.id) }
        }
        RowState.Downloading -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.downloading_pct, row.progress), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(bottom = 6.dp))
                LinearProgressIndicator(
                    progress = { (row.progress.coerceIn(0, 100)) / 100f },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = cs.primary, trackColor = cs.outlineVariant
                )
            }
            TextButton(onClick = { actions.onCancel(row.id) }) { Text(stringResource(R.string.action_cancel), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
        }
        RowState.Installed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PinToggle(row.pinned, row.id, actions)
            TextButton(onClick = { onRequestDelete(row.id) }) { Text(stringResource(R.string.action_delete), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.action_use), cs.primaryContainer, cs.onPrimaryContainer) { actions.onUse(row.id) }
        }
        RowState.Active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PinToggle(row.pinned, row.id, actions)
            TextButton(onClick = { onRequestDelete(row.id) }) { Text(stringResource(R.string.action_delete), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PinToggle(pinned: Boolean, id: String, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    val starColor = Color(0xFFF59E0B)
    IconButton(onClick = { actions.onTogglePinned(id) }) {
        Icon(
            painterResource(if (pinned) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
            contentDescription = stringResource(if (pinned) R.string.quick_access_remove else R.string.quick_access_add),
            tint = if (pinned) starColor else cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(color = bg, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickableRow(RoundedCornerShape(20.dp), onClick)) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun AddButton(glyph: String, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, cs.outline),
        modifier = Modifier.fillMaxWidth().clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(glyph, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        }
    }
}

@Composable
private fun StatusBox(msg: String, error: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (error) cs.errorContainer else cs.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
    ) {
        Text(
            msg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
            color = if (error) cs.onErrorContainer else cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.primary, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(cs.onPrimary))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.record_and_transcribe), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)
        }
    }
}

@Composable
private fun RecordingDialog(r: RecordingUi, remoteHost: String?, onStop: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        // Neither a tap outside nor Back may drop a take by accident: the two buttons are the way out.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recording)) },
        text = {
            Column {
                Text(
                    elapsed(r.elapsedMs), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace, color = cs.onSurface
                )
                Spacer(Modifier.height(14.dp))
                LevelMeter(r.levels)
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(if (remoteHost != null) R.string.record_hint_remote else R.string.record_hint),
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// The app's own bars, driven by the microphone: one per sampled level, newest on the right, and a
// square root so ordinary speech still moves them.
@Composable
private fun LevelMeter(levels: List<Float>) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (i in 0 until METER_BARS) {
            val level = levels.getOrElse(levels.size - METER_BARS + i) { 0f }
            val height = 3.dp + 29.dp * sqrt(level.coerceIn(0f, 1f))
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(2.dp))
                    .background(cs.primary.copy(alpha = if (level > 0f) 1f else 0.35f))
            )
        }
    }
}

private const val METER_BARS = 28

private fun elapsed(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun RecordAndTranscribeButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.tertiaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_tab_mic),
                contentDescription = null,
                tint = cs.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.record_and_transcribe),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun TranscribeFileButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.primaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.transcribe_audio_file), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun BatchTranscribeButton(onClick: () -> Unit, label: String = "Batch Transcribe Audio Files") {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.secondaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
        }
    }
}

@Composable
private fun BatchQueueButton(onClick: () -> Unit, label: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.tertiaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
        }
    }
}

@Composable
private fun BenchmarkBatchControl(
    state: BenchmarkBatchState,
    enabled: Boolean,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (state.running) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                stringResource(
                    R.string.benchmark_batch_running,
                    (state.completed + 1).coerceAtMost(state.total),
                    state.total,
                    state.currentModel.orEmpty()
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = {
                    if (state.total > 0) state.completed.toFloat() / state.total else 0f
                },
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(3.dp)),
                color = cs.primary,
                trackColor = cs.outlineVariant
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.action_cancel), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    } else {
        TextButton(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text(
                stringResource(R.string.benchmark_all_models),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant
            )
        }
    }
}

// Pre-fills the save dialog with the transcript name and, when the provider supports it, opens
// the picker at the source recording's location — SAF cannot write there without the user's pick.
private class CreateTranscriptDocument(private val initialUri: Uri?, mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
}

// Document providers rewrite a file name whose extension disagrees with the type they were asked
// for — .srt would come back as .srt.txt. Asking for the type the platform itself maps the
// extension to keeps the name, and an unmapped one passes as an opaque file rather than as text.
private fun transcriptMimeType(format: TranscriptFormat): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(format.extension) ?: "application/octet-stream"

@Composable
private fun AllSpeakersRenameDialog(
    segments: List<TranscriptSegment>,
    onDismiss: () -> Unit,
    onConfirmRename: (oldName: String, newName: String) -> Unit
) {
    val uniqueSpeakers = remember(segments) { segments.uniqueSpeakers() }
    val nameMap = remember(uniqueSpeakers) {
        mutableStateMapOf<String, String>().apply {
            uniqueSpeakers.forEach { spk -> put(spk, spk) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Speakers", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Edit speaker names to update all occurrences throughout the transcript:",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (uniqueSpeakers.isEmpty()) {
                    Text("No speaker labels detected in this transcript.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    uniqueSpeakers.forEach { oldName ->
                        val currentVal = nameMap[oldName] ?: oldName
                        OutlinedTextField(
                            value = currentVal,
                            onValueChange = { newVal -> nameMap[oldName] = newVal },
                            label = { Text("Original: $oldName") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    uniqueSpeakers.forEach { oldName ->
                        val updated = nameMap[oldName]?.trim()
                        if (!updated.isNullOrBlank() && updated != oldName) {
                            onConfirmRename(oldName, updated)
                        }
                    }
                    onDismiss()
                }
            ) {
                Text("Apply Names")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DisplayOptionsDialog(
    segments: List<TranscriptSegment>,
    format: TranscriptFormat,
    showTimestamps: Boolean,
    showSpeakers: Boolean,
    colorizeSpeakerLabels: Boolean = false,
    onFormat: (TranscriptFormat) -> Unit,
    onToggleTimestamps: (Boolean) -> Unit,
    onToggleSpeakers: (Boolean) -> Unit,
    onToggleColorizeSpeakers: (Boolean) -> Unit = {},
    onOpenSpeakerEditor: () -> Unit,
    onDismiss: () -> Unit
) {
    val uniqueSpeakers = remember(segments) { segments.uniqueSpeakers() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display & Export Options", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Export Format:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                FormatPicker(format, onFormat)

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Display Timestamps [MM:SS]", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = showTimestamps, onCheckedChange = onToggleTimestamps)
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Display Speaker Labels", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = showSpeakers, onCheckedChange = onToggleSpeakers)
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Colorize Speaker Labels",
                        fontSize = 13.5.sp,
                        color = if (showSpeakers) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Switch(
                        checked = colorizeSpeakerLabels,
                        onCheckedChange = onToggleColorizeSpeakers,
                        enabled = showSpeakers
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        onDismiss()
                        onOpenSpeakerEditor()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit Speaker Names (${if (uniqueSpeakers.isEmpty()) "Auto" else "${uniqueSpeakers.size} Speakers"})")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun SpeakerActionDialog(
    initialSpeaker: String,
    segmentIndex: Int,
    segments: List<TranscriptSegment> = emptyList(),
    onDismiss: () -> Unit,
    onRenameInstance: (Int, String) -> Unit,
    onRenameAll: (oldName: String, newName: String) -> Unit,
    onDeleteLabel: (Int) -> Unit,
    onInsertLabel: (Int, String) -> Unit
) {
    var speakerInput by remember { mutableStateOf(initialSpeaker) }
    val knownSpeakers = remember(segments) { segments.uniqueSpeakers() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialSpeaker.isNotBlank()) "Speaker Options: $initialSpeaker" else "Add Speaker Label",
                fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (knownSpeakers.isNotEmpty()) {
                    Text(
                        "Select Preexisting Speaker:",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        knownSpeakers.forEach { spk ->
                            Surface(
                                color = if (speakerInput == spk) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.clickableRow(RoundedCornerShape(12.dp)) {
                                    speakerInput = spk
                                }
                            ) {
                                Text(
                                    spk,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (speakerInput == spk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Text(
                    "Or Enter Custom Name:",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = speakerInput,
                    onValueChange = { speakerInput = it },
                    label = { Text("Speaker Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    "Apply Change To:",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (speakerInput.isNotBlank()) {
                                if (initialSpeaker.isBlank()) {
                                    onInsertLabel(segmentIndex, speakerInput)
                                } else {
                                    onRenameInstance(segmentIndex, speakerInput)
                                }
                            }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("This Instance Only", fontSize = 11.5.sp)
                    }

                    if (initialSpeaker.isNotBlank()) {
                        Button(
                            onClick = {
                                if (speakerInput.isNotBlank()) {
                                    onRenameAll(initialSpeaker, speakerInput)
                                }
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("All Instances", fontSize = 11.5.sp)
                        }
                    }
                }

                if (initialSpeaker.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            onDeleteLabel(segmentIndex)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Speaker Label", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SelectOrAddSpeakerDialog(
    segments: List<TranscriptSegment>,
    onDismiss: () -> Unit,
    onSelectSpeaker: (String) -> Unit
) {
    val knownSpeakers = remember(segments) { segments.uniqueSpeakers() }
    var customInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign Speaker Label", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (knownSpeakers.isNotEmpty()) {
                    Text("Select Existing Speaker:", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    knownSpeakers.forEach { spk ->
                        OutlinedButton(
                            onClick = {
                                onSelectSpeaker(spk)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Text(spk, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Text("Or Add New Speaker:", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text("New Speaker Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (customInput.isNotBlank()) {
                        onSelectSpeaker(customInput.trim())
                        onDismiss()
                    }
                },
                enabled = customInput.isNotBlank()
            ) {
                Text("Assign")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun FindReplaceBar(
    searchQuery: String,
    replaceQuery: String,
    matchCount: Int,
    currentMatchIndex: Int,
    onSearchChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onReplaceInstance: () -> Unit,
    onReplaceAll: () -> Unit,
    onCloseSearch: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    label = { Text("Find Text", fontSize = 11.5.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    if (searchQuery.isBlank()) "0 matches"
                    else if (matchCount == 0) "No matches"
                    else "${currentMatchIndex + 1} / $matchCount",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurfaceVariant
                )

                IconButton(onClick = onPrevMatch, enabled = matchCount > 0) {
                    Text("▲", fontSize = 12.sp, color = if (matchCount > 0) cs.primary else cs.outline)
                }

                IconButton(onClick = onNextMatch, enabled = matchCount > 0) {
                    Text("▼", fontSize = 12.sp, color = if (matchCount > 0) cs.primary else cs.outline)
                }

                IconButton(onClick = onCloseSearch) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Close Search", tint = cs.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = replaceQuery,
                    onValueChange = onReplaceChange,
                    label = { Text("Replace With", fontSize = 11.5.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = onReplaceInstance,
                    enabled = matchCount > 0 && searchQuery.isNotBlank(),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("Replace", fontSize = 11.sp)
                }

                Spacer(Modifier.width(4.dp))

                Button(
                    onClick = onReplaceAll,
                    enabled = matchCount > 0 && searchQuery.isNotBlank(),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("Replace All", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun BatchQueueDialog(
    batchState: BatchBatchState,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onStartBatch: () -> Unit,
    onPickBatchFiles: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onOpenItem: (BatchItem) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Multi-File Batch Queue", fontSize = 16.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!batchState.running) {
                    TextButton(onClick = onPickBatchFiles) {
                        Text("+ Add Files", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (batchState.items.isNotEmpty()) {
                    Text(
                        "${batchState.completedCount} / ${batchState.items.size} Done",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) {
                if (batchState.running) {
                    LinearProgressIndicator(
                        progress = { (batchState.completedCount.toFloat() / maxOf(1, batchState.items.size)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                }

                if (batchState.items.isEmpty()) {
                    Text(
                        "No audio files queued. Tap '+ Add Files' to select audio recordings.",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(batchState.items, key = { it.id }) { item ->
                            Surface(
                                color = cs.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.fileName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = cs.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val (statusLabel, statusColor) = when (item.status) {
                                                BatchItemStatus.QUEUED -> "Queued" to cs.onSurfaceVariant
                                                BatchItemStatus.PROCESSING -> "Transcribing ${item.percent}%..." to cs.primary
                                                BatchItemStatus.COMPLETED -> "Completed" to MaterialTheme.colorScheme.primary
                                                BatchItemStatus.FAILED -> "Failed: ${item.error ?: "Error"}" to cs.error
                                                BatchItemStatus.SKIPPED -> "Skipped" to cs.outline
                                            }
                                            Text(statusLabel, fontSize = 11.5.sp, color = statusColor, fontWeight = FontWeight.Medium)
                                        }
                                    }

                                    if (item.status == BatchItemStatus.COMPLETED) {
                                        TextButton(onClick = { onOpenItem(item) }) {
                                            Text("View", fontSize = 12.sp)
                                        }
                                    } else if (!batchState.running) {
                                        IconButton(onClick = { onRemoveItem(item.id) }) {
                                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove", tint = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!batchState.running && batchState.items.any { it.status == BatchItemStatus.QUEUED }) {
                Button(onClick = onStartBatch) {
                    Text("Start Batch (${batchState.items.count { it.status == BatchItemStatus.QUEUED }} Files)")
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!batchState.running && batchState.items.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear Queue", color = cs.error)
                    }
                }
                TextButton(onClick = onClose) {
                    Text(if (batchState.running) "Run Background" else "Close")
                }
            }
        }
    )
}

@Composable
private fun EditTurnTextDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSaveText: (newText: String) -> Unit
) {
    var textInput by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Paragraph Text", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Edit text for this turn/paragraph:",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (textInput.isNotBlank() && textInput != initialText) {
                        onSaveText(textInput.trim())
                    }
                    onDismiss()
                }
            ) {
                Text("Save Text")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TurnParagraphActionDialog(
    block: TranscriptTurnBlock,
    onDismiss: () -> Unit,
    onEditText: () -> Unit,
    onInsertSpeakerAtTimestamp: (Long) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showSplitSentencePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paragraph Options (${block.speaker.ifBlank { "Unassigned" }})", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "\"${block.text}\"",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                if (showSplitSentencePicker && block.sentences.size > 1) {
                    Text(
                        "Select sentence to insert speaker label & split paragraph:",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    block.sentences.forEach { s ->
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onInsertSpeakerAtTimestamp(s.startMs)
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Text(
                                s.text,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Button(
                        onClick = {
                            if (block.sentences.size > 1) {
                                showSplitSentencePicker = true
                            } else {
                                onDismiss()
                                onInsertSpeakerAtTimestamp(block.startMs)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text("Insert Speaker Label / Split Turn")
                    }

                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onEditText()
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text("Edit Full Paragraph Text")
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.text))
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text("Copy Paragraph Text")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class MatchOccurrence(
    val blockIndex: Int,
    val startIndex: Int,
    val endIndex: Int
)

@Composable
private fun FullScreenTranscriptScreen(
    title: String,
    formattedText: String,
    segments: List<TranscriptSegment>,
    format: TranscriptFormat,
    showTimestamps: Boolean,
    showSpeakers: Boolean,
    colorizeSpeakerLabels: Boolean = false,
    running: Boolean = false,
    error: String? = null,
    metrics: TranscriptionMetrics? = null,
    progressPercent: Int = -1,
    etaMs: Long? = null,
    canUndo: Boolean = false,
    onUndo: (() -> Unit)? = null,
    onClose: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onSaveFile: ((Uri) -> Unit)? = null,
    onFormat: (TranscriptFormat) -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onRenameSpeakerInstance: (Int, String) -> Unit = { _, _ -> },
    onDeleteSpeakerLabel: (Int) -> Unit = {},
    onInsertSpeakerLabel: (Int, String) -> Unit = { _, _ -> },
    onInsertSpeakerLabelAtTimestamp: (Long, String) -> Unit = { _, _ -> },
    onReplaceTextInstance: (Int, String, String) -> Unit = { _, _, _ -> },
    onReplaceAllText: (String, String) -> Unit = { _, _ -> },
    onUpdateTurnBlockText: (Int, String, String) -> Unit = { _, _, _ -> },
    onSaveFullText: ((String) -> Unit)? = null,
    onToggleTimestamps: (Boolean) -> Unit,
    onToggleSpeakers: (Boolean) -> Unit,
    onToggleColorizeSpeakers: (Boolean) -> Unit = {},
    onSaveToHistory: (() -> Unit)? = null,
    onOpenBatchQueue: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showAllSpeakersDialog by rememberSaveable { mutableStateOf(false) }
    var showDisplayOptionsDialog by rememberSaveable { mutableStateOf(false) }
    var showFindReplaceBar by rememberSaveable { mutableStateOf(false) }
    var isFullDocumentEditing by rememberSaveable { mutableStateOf(false) }
    var fullDocumentFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(formattedText)) }
    var lastSavedText by rememberSaveable { mutableStateOf(formattedText) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val editUndoStack = remember { mutableStateListOf<TextFieldValue>() }

    var justSavedFeedback by remember { mutableStateOf(false) }

    val hasUnsavedEdits = isFullDocumentEditing && fullDocumentFieldValue.text != lastSavedText

    fun saveInPlace() {
        if (hasUnsavedEdits) {
            onSaveFullText?.invoke(fullDocumentFieldValue.text)
            lastSavedText = fullDocumentFieldValue.text
            justSavedFeedback = true
        }
    }

    LaunchedEffect(fullDocumentFieldValue.text, isFullDocumentEditing) {
        if (isFullDocumentEditing && fullDocumentFieldValue.text != lastSavedText) {
            delay(2000L)
            saveInPlace()
        }
    }

    LaunchedEffect(justSavedFeedback) {
        if (justSavedFeedback) {
            delay(1500L)
            justSavedFeedback = false
        }
    }

    DisposableEffect(isFullDocumentEditing) {
        onDispose {
            if (isFullDocumentEditing && fullDocumentFieldValue.text != lastSavedText) {
                onSaveFullText?.invoke(fullDocumentFieldValue.text)
                lastSavedText = fullDocumentFieldValue.text
            }
        }
    }

    fun attemptClose() {
        if (hasUnsavedEdits) {
            showUnsavedDialog = true
        } else {
            onSaveToHistory?.invoke()
            onClose()
        }
    }

    fun cancelEditing() {
        if (hasUnsavedEdits) {
            showUnsavedDialog = true
        } else {
            isFullDocumentEditing = false
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Discard Unsaved Edits?", fontWeight = FontWeight.Bold) },
            text = { Text("You have unsaved edits in this transcript. Would you like to save them before exiting?") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onSaveFullText?.invoke(fullDocumentFieldValue.text)
                    lastSavedText = fullDocumentFieldValue.text
                    isFullDocumentEditing = false
                    onSaveToHistory?.invoke()
                    onClose()
                }) {
                    Text("Save & Exit", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        isFullDocumentEditing = false
                        fullDocumentFieldValue = TextFieldValue(lastSavedText)
                        onClose()
                    }) {
                        Text("Discard", color = cs.error)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text("Keep Editing")
                    }
                }
            }
        )
    }

    // 1. Close Find & Replace bar first if active
    BackHandler(enabled = showFindReplaceBar) {
        showFindReplaceBar = false
    }

    // 2. If editing with unsaved changes, prompt before discarding; if no changes, exit edit mode
    BackHandler(enabled = !showFindReplaceBar && isFullDocumentEditing) {
        cancelEditing()
    }

    // 3. Normal close
    BackHandler(enabled = !showFindReplaceBar && !isFullDocumentEditing, onBack = { attemptClose() })

    LaunchedEffect(formattedText) {
        if (!isFullDocumentEditing) {
            fullDocumentFieldValue = TextFieldValue(formattedText)
            lastSavedText = formattedText
        }
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var replaceQuery by rememberSaveable { mutableStateOf("") }
    var currentMatchIndex by rememberSaveable { mutableStateOf(0) }
    var activeTargetBlock by remember { mutableStateOf<TranscriptTurnBlock?>(null) }
    var activeParagraphBlock by remember { mutableStateOf<TranscriptTurnBlock?>(null) }
    var editingTurnSegmentIndex by remember { mutableStateOf<Int?>(null) }
    var editingTurnInitialText by remember { mutableStateOf("") }
    var pendingTimestampForSpeaker by remember { mutableStateOf<Long?>(null) }

    activeParagraphBlock?.let { block ->
        TurnParagraphActionDialog(
            block = block,
            onDismiss = { activeParagraphBlock = null },
            onEditText = {
                editingTurnSegmentIndex = block.segmentIndex
                editingTurnInitialText = block.text
            },
            onInsertSpeakerAtTimestamp = { ts ->
                pendingTimestampForSpeaker = ts
            }
        )
    }

    editingTurnSegmentIndex?.let { segIdx ->
        EditTurnTextDialog(
            initialText = editingTurnInitialText,
            onDismiss = { editingTurnSegmentIndex = null },
            onSaveText = { newText ->
                onUpdateTurnBlockText(segIdx, editingTurnInitialText, newText)
            }
        )
    }

    activeTargetBlock?.let { block ->
        SpeakerActionDialog(
            initialSpeaker = block.speaker,
            segmentIndex = block.segmentIndex,
            segments = segments,
            onDismiss = { activeTargetBlock = null },
            onRenameInstance = onRenameSpeakerInstance,
            onRenameAll = onRenameSpeaker,
            onDeleteLabel = onDeleteSpeakerLabel,
            onInsertLabel = onInsertSpeakerLabel
        )
    }

    pendingTimestampForSpeaker?.let { ts ->
        SelectOrAddSpeakerDialog(
            segments = segments,
            onDismiss = { pendingTimestampForSpeaker = null },
            onSelectSpeaker = { spk ->
                onInsertSpeakerLabelAtTimestamp(ts, spk)
            }
        )
    }

    if (showAllSpeakersDialog) {
        AllSpeakersRenameDialog(
            segments = segments,
            onDismiss = { showAllSpeakersDialog = false },
            onConfirmRename = onRenameSpeaker
        )
    }

    if (showDisplayOptionsDialog) {
        DisplayOptionsDialog(
            segments = segments,
            format = format,
            showTimestamps = showTimestamps,
            showSpeakers = showSpeakers,
            colorizeSpeakerLabels = colorizeSpeakerLabels,
            onFormat = onFormat,
            onToggleTimestamps = onToggleTimestamps,
            onToggleSpeakers = onToggleSpeakers,
            onToggleColorizeSpeakers = onToggleColorizeSpeakers,

            onOpenSpeakerEditor = { showAllSpeakersDialog = true },
            onDismiss = { showDisplayOptionsDialog = false }
        )
    }

    val baseName = title.substringBeforeLast('.').ifBlank { "transcript" }
    val saveContract = remember(format) { CreateTranscriptDocument(null, transcriptMimeType(format)) }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        if (uri != null && onSaveFile != null) {
            onSaveFile(uri)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background
    ) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = cs.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isFullDocumentEditing) {
                        IconButton(onClick = { cancelEditing() }) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = "Cancel Editing",
                                tint = cs.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (hasUnsavedEdits) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = cs.errorContainer.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable { saveInPlace() }
                                ) {
                                    Text(
                                        "Unsaved *",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = cs.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else if (justSavedFeedback) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = cs.primaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_action_check),
                                            contentDescription = null,
                                            tint = cs.onPrimaryContainer,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            "Saved",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = cs.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                if (editUndoStack.isNotEmpty()) {
                                    val previous = editUndoStack.removeAt(editUndoStack.size - 1)
                                    fullDocumentFieldValue = previous
                                }
                            },
                            enabled = editUndoStack.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_undo),
                                contentDescription = "Undo",
                                tint = if (editUndoStack.isNotEmpty()) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(onClick = { showFindReplaceBar = !showFindReplaceBar }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_search),
                                contentDescription = "Search & Replace",
                                tint = if (showFindReplaceBar) cs.primary else cs.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            if (hasUnsavedEdits) {
                                onSaveFullText?.invoke(fullDocumentFieldValue.text)
                                lastSavedText = fullDocumentFieldValue.text
                            }
                            isFullDocumentEditing = false
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_check),
                                contentDescription = "Save Document",
                                tint = cs.primary
                            )
                        }
                    } else {
                        if (running) {
                            IconButton(onClick = { attemptClose() }) {
                                Icon(
                                    painterResource(R.drawable.ic_chevron_down),
                                    contentDescription = "Minimize",
                                    tint = cs.onSurface
                                )
                            }
                        }
                        Text(
                            title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = {
                            fullDocumentFieldValue = TextFieldValue(formattedText)
                            lastSavedText = formattedText
                            editUndoStack.clear()
                            isFullDocumentEditing = true
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_edit),
                                contentDescription = "Edit Document",
                                tint = cs.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showFindReplaceBar = !showFindReplaceBar }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_search),
                                contentDescription = "Search & Replace",
                                tint = if (showFindReplaceBar) cs.primary else cs.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = "More Options",
                                    tint = cs.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Entire Text") },
                                    onClick = {
                                        showOverflowMenu = false
                                        clipboard.setText(AnnotatedString(formattedText))
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_action_copy), contentDescription = null, tint = cs.onSurfaceVariant)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share Transcript") },
                                    onClick = {
                                        showOverflowMenu = false
                                        val send = Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, formattedText)
                                            .putExtra(Intent.EXTRA_SUBJECT, baseName)
                                        context.startActivity(Intent.createChooser(send, null))
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_action_share), contentDescription = null, tint = cs.onSurfaceVariant)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Transcript Options") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDisplayOptionsDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_tab_settings), contentDescription = null, tint = cs.onSurfaceVariant)
                                    }
                                )
                                if (canUndo && onUndo != null) {
                                    DropdownMenuItem(
                                        text = { Text("Undo Speaker Edit") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onUndo.invoke()
                                        },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_action_undo), contentDescription = null, tint = cs.primary)
                                        }
                                    )
                                }
                                if (onOpenBatchQueue != null) {
                                    DropdownMenuItem(
                                        text = { Text("Batch Queue") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onOpenBatchQueue()
                                        },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_tab_graphic_eq), contentDescription = null, tint = cs.primary)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val listState = rememberLazyListState()
            var isBottomBarVisible by remember { mutableStateOf(true) }
            var previousIndex by remember { mutableStateOf(0) }
            var previousOffset by remember { mutableStateOf(0) }

            LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
                val curIndex = listState.firstVisibleItemIndex
                val curOffset = listState.firstVisibleItemScrollOffset
                if (curIndex > previousIndex || (curIndex == previousIndex && curOffset > previousOffset + 20)) {
                    isBottomBarVisible = false
                } else if (curIndex < previousIndex || (curIndex == previousIndex && curOffset < previousOffset - 20)) {
                    isBottomBarVisible = true
                }
                previousIndex = curIndex
                previousOffset = curOffset
            }
            val turnBlocks = remember(segments, showTimestamps, showSpeakers) {
                if (segments.isNotEmpty()) segments.toTurnBlocks() else emptyList()
            }
            val colorAssigner = remember(turnBlocks) {
                SpeakerColorAssigner().apply {
                    turnBlocks.forEach { getIndex(it.speaker) }
                }
            }

            val editMatches = remember(fullDocumentFieldValue.text, searchQuery) {
                if (searchQuery.isBlank()) emptyList<MatchOccurrence>()
                else {
                    val list = mutableListOf<MatchOccurrence>()
                    val text = fullDocumentFieldValue.text
                    var start = 0
                    while (start < text.length) {
                        val idx = text.indexOf(searchQuery, start, ignoreCase = true)
                        if (idx < 0) break
                        list.add(MatchOccurrence(0, idx, idx + searchQuery.length))
                        start = idx + searchQuery.length
                    }
                    list
                }
            }

            val blockMatches = remember(turnBlocks, searchQuery) {
                if (searchQuery.isBlank()) emptyList<MatchOccurrence>()
                else {
                    val list = mutableListOf<MatchOccurrence>()
                    turnBlocks.forEachIndexed { blockIdx, block ->
                        if (block.speaker.isNotBlank()) {
                            val sIdx = block.speaker.indexOf(searchQuery, 0, ignoreCase = true)
                            if (sIdx >= 0) {
                                list.add(MatchOccurrence(blockIdx, -1, -1))
                            }
                        }
                        var start = 0
                        while (start < block.text.length) {
                            val idx = block.text.indexOf(searchQuery, start, ignoreCase = true)
                            if (idx < 0) break
                            list.add(MatchOccurrence(blockIdx, idx, idx + searchQuery.length))
                            start = idx + searchQuery.length
                        }
                    }
                    list
                }
            }

            val activeMatches = if (isFullDocumentEditing) editMatches else blockMatches

            LaunchedEffect(currentMatchIndex, blockMatches, isFullDocumentEditing) {
                if (!isFullDocumentEditing && blockMatches.isNotEmpty() && currentMatchIndex in blockMatches.indices) {
                    val targetBlock = blockMatches[currentMatchIndex].blockIndex
                    listState.animateScrollToItem(targetBlock)
                }
            }

            LaunchedEffect(currentMatchIndex, editMatches, isFullDocumentEditing) {
                if (isFullDocumentEditing && editMatches.isNotEmpty() && currentMatchIndex in editMatches.indices) {
                    val occ = editMatches[currentMatchIndex]
                    fullDocumentFieldValue = fullDocumentFieldValue.copy(
                        selection = TextRange(occ.startIndex, occ.endIndex)
                    )
                }
            }

            val editVisualTransformation = remember(searchQuery, currentMatchIndex, fullDocumentFieldValue.text, editMatches, cs) {
                VisualTransformation { text ->
                    if (searchQuery.isBlank() || editMatches.isEmpty()) {
                        TransformedText(text, OffsetMapping.Identity)
                    } else {
                        val raw = text.text
                        val annotated = buildAnnotatedString {
                            var lastIndex = 0
                            editMatches.forEachIndexed { mIdx, occ ->
                                if (occ.startIndex > lastIndex && occ.startIndex <= raw.length) {
                                    append(raw.substring(lastIndex, occ.startIndex))
                                }
                                val isCurrent = (mIdx == currentMatchIndex)
                                withStyle(
                                    SpanStyle(
                                        background = if (isCurrent) cs.primary else cs.secondaryContainer,
                                        color = if (isCurrent) cs.onPrimary else cs.onSecondaryContainer,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                ) {
                                    if (occ.startIndex < raw.length) {
                                        append(raw.substring(occ.startIndex, minOf(occ.endIndex, raw.length)))
                                    }
                                }
                                lastIndex = minOf(occ.endIndex, raw.length)
                            }
                            if (lastIndex < raw.length) {
                                append(raw.substring(lastIndex))
                            }
                        }
                        TransformedText(annotated, OffsetMapping.Identity)
                    }
                }
            }

            if (showFindReplaceBar) {
                FindReplaceBar(
                    searchQuery = searchQuery,
                    replaceQuery = replaceQuery,
                    matchCount = activeMatches.size,
                    currentMatchIndex = currentMatchIndex.coerceIn(0, maxOf(0, activeMatches.size - 1)),
                    onSearchChange = {
                        searchQuery = it
                        currentMatchIndex = 0
                    },
                    onReplaceChange = { replaceQuery = it },
                    onPrevMatch = {
                        if (activeMatches.isNotEmpty()) {
                            currentMatchIndex = (currentMatchIndex - 1 + activeMatches.size) % activeMatches.size
                        }
                    },
                    onNextMatch = {
                        if (activeMatches.isNotEmpty()) {
                            currentMatchIndex = (currentMatchIndex + 1) % activeMatches.size
                        }
                    },
                    onReplaceInstance = {
                        if (isFullDocumentEditing) {
                            if (searchQuery.isNotBlank() && editMatches.isNotEmpty() && currentMatchIndex in editMatches.indices) {
                                val occ = editMatches[currentMatchIndex]
                                val text = fullDocumentFieldValue.text
                                val newText = text.substring(0, occ.startIndex) + replaceQuery + text.substring(occ.endIndex)
                                if (editUndoStack.size >= 50) editUndoStack.removeAt(0)
                                editUndoStack.add(fullDocumentFieldValue)
                                fullDocumentFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(occ.startIndex, occ.startIndex + replaceQuery.length)
                                )
                            }
                        } else {
                            if (searchQuery.isNotBlank() && blockMatches.isNotEmpty() && currentMatchIndex in blockMatches.indices) {
                                val occ = blockMatches[currentMatchIndex]
                                val block = turnBlocks.getOrNull(occ.blockIndex)
                                if (block != null) {
                                    if (occ.startIndex == -1) {
                                        onRenameSpeakerInstance(block.segmentIndex, replaceQuery)
                                    } else {
                                        val oldText = block.text
                                        val newText = oldText.substring(0, occ.startIndex) + replaceQuery + oldText.substring(occ.endIndex)
                                        onUpdateTurnBlockText(block.segmentIndex, oldText, newText)
                                    }
                                }
                            }
                        }
                    },
                    onReplaceAll = {
                        if (isFullDocumentEditing) {
                            if (searchQuery.isNotBlank() && editMatches.isNotEmpty()) {
                                val newText = fullDocumentFieldValue.text.replace(
                                    Regex(Regex.escape(searchQuery), RegexOption.IGNORE_CASE),
                                    replaceQuery
                                )
                                if (editUndoStack.size >= 50) editUndoStack.removeAt(0)
                                editUndoStack.add(fullDocumentFieldValue)
                                fullDocumentFieldValue = TextFieldValue(text = newText)
                            }
                        } else {
                            if (searchQuery.isNotBlank() && blockMatches.isNotEmpty()) {
                                onReplaceAllText(searchQuery, replaceQuery)
                            }
                        }
                    },
                    onCloseSearch = { showFindReplaceBar = false }
                )
            }

            if (running) {
                Surface(
                    color = cs.primaryContainer,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    progressPercent < 65 -> "Stage 1/3: Speech-to-Text Transcription..."
                                    progressPercent < 95 -> "Stage 2/3: WeSpeaker CAM++ Speaker Diarization..."
                                    else -> "Stage 3/3: Formatting Transcript & Time Markers..."
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = cs.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            if (progressPercent >= 0) {
                                Text(
                                    text = if (etaMs != null) "${progressPercent}% (${remaining(context, etaMs)})" else "${progressPercent}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cs.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (progressPercent >= 0) {
                            LinearProgressIndicator(
                                progress = { progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize()) {
                when {
                    error != null -> {
                        Surface(
                            color = cs.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Transcription Error",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onErrorContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    error,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                    color = cs.onErrorContainer
                                )
                            }
                        }
                    }
                    running && formattedText.isEmpty() -> {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Transcription in progress… Live text will appear here.",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                    isFullDocumentEditing -> {
                        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                            Text(
                                "Full-Document Editor — edit text freely, tap ✓ when done:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = cs.primary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = fullDocumentFieldValue,
                                onValueChange = { newVal ->
                                    if (newVal.text != fullDocumentFieldValue.text) {
                                        if (editUndoStack.size >= 50) editUndoStack.removeAt(0)
                                        editUndoStack.add(fullDocumentFieldValue)
                                    }
                                    fullDocumentFieldValue = newVal
                                },
                                visualTransformation = editVisualTransformation,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onPreviewKeyEvent { event ->
                                        if (event.isCtrlPressed && event.key == Key.S && event.type == KeyEventType.KeyDown) {
                                            saveInPlace()
                                            true
                                        } else false
                                    },
                                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurface)
                            )
                        }
                    }
                    else -> {
                        if (turnBlocks.isNotEmpty()) {
                            SelectionContainer {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp)
                                ) {
                                    itemsIndexed(turnBlocks, key = { idx, block -> "${block.segmentIndex}_${block.startMs}_$idx" }) { blockIdx, block ->
                                        val timeLabel = if (showTimestamps) "[${clockMMSS(block.startMs)}] " else ""
                                        val blockOccurrences = blockMatches.filter { it.blockIndex == blockIdx }
                                        val annotatedText = buildAnnotatedString {
                                            val text = block.text
                                            if (blockOccurrences.isEmpty()) {
                                                append(text)
                                            } else {
                                                var lastIndex = 0
                                                blockOccurrences.forEach { occ ->
                                                    val globalIdx = blockMatches.indexOf(occ)
                                                    val isCurrent = (globalIdx == currentMatchIndex)
                                                    if (occ.startIndex > lastIndex) {
                                                        append(text.substring(lastIndex, occ.startIndex))
                                                    }
                                                    withStyle(
                                                        SpanStyle(
                                                            background = if (isCurrent) cs.primary else cs.secondaryContainer,
                                                            color = if (isCurrent) cs.onPrimary else cs.onSecondaryContainer,
                                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    ) {
                                                        append(text.substring(occ.startIndex, occ.endIndex))
                                                    }
                                                    lastIndex = occ.endIndex
                                                }
                                                if (lastIndex < text.length) {
                                                    append(text.substring(lastIndex))
                                                }
                                            }
                                        }

                                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    if (showTimestamps) {
                                                        Text(
                                                            timeLabel,
                                                            fontSize = 12.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = cs.primary
                                                        )
                                                    }
                                                    val isSpeakerMatch = (blockMatches.getOrNull(currentMatchIndex)?.let { it.blockIndex == blockIdx && it.startIndex == -1 } == true)
                                                    if (showSpeakers && (block.showSpeakerLabel || block.speaker.isBlank())) {
                                                        Surface(
                                                            color = when {
                                                                isSpeakerMatch -> cs.primary
                                                                colorizeSpeakerLabels && block.speaker.isNotBlank() -> {
                                                                    val sc = colorAssigner.getColor(block.speaker)
                                                                    if (cs.surface.luminance() < 0.5f) sc.darkColor.copy(alpha = 0.22f) else sc.lightColor.copy(alpha = 0.14f)
                                                                }
                                                                block.speaker.isNotBlank() -> cs.primaryContainer
                                                                else -> cs.surfaceContainerHigh
                                                            },
                                                            shape = RoundedCornerShape(12.dp),
                                                            modifier = Modifier.clickableRow(RoundedCornerShape(12.dp)) {
                                                                activeTargetBlock = block
                                                            }
                                                        ) {
                                                            Text(
                                                                if (block.speaker.isNotBlank()) "${block.speaker}:" else "+ Speaker Label",
                                                                fontSize = 12.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = when {
                                                                    isSpeakerMatch -> cs.onPrimary
                                                                    colorizeSpeakerLabels && block.speaker.isNotBlank() -> {
                                                                        val sc = colorAssigner.getColor(block.speaker)
                                                                        if (cs.surface.luminance() < 0.5f) sc.darkColor else sc.lightColor
                                                                    }
                                                                    block.speaker.isNotBlank() -> cs.onPrimaryContainer
                                                                    else -> cs.onSurfaceVariant
                                                                },
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                IconButton(
                                                    onClick = { activeParagraphBlock = block },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        painterResource(R.drawable.ic_action_edit),
                                                        contentDescription = "Paragraph Options",
                                                        tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = annotatedText,
                                                fontSize = 14.sp,
                                                lineHeight = 21.sp,
                                                color = cs.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            SelectionContainer {
                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Text(
                                            formattedText,
                                            fontSize = 14.sp,
                                            lineHeight = 21.sp,
                                            color = cs.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val shouldShowBottomBar = !isFullDocumentEditing && (running || !listState.isScrollInProgress || isBottomBarVisible || !listState.canScrollForward || listState.firstVisibleItemIndex == 0)

            androidx.compose.animation.AnimatedVisibility(
                visible = shouldShowBottomBar,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                Surface(
                    color = cs.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (running && onCancel != null) {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                                border = BorderStroke(1.dp, cs.error.copy(alpha = 0.5f))
                            ) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Cancel Transcription", color = cs.error, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { attemptClose() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (running) "Minimize" else stringResource(R.string.action_close))
                                }

                                if (!running && formattedText.isNotBlank() && onSaveFile != null) {
                                    Button(
                                        onClick = { saveLauncher.launch("$baseName.${format.extension}") },
                                        modifier = Modifier.weight(1.2f)
                                    ) {
                                        Text("Export ${format.extension.uppercase()}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TranscriptionMiniBar(
    transcription: TranscribeUi,
    onExpand: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showCancelConfirm by remember { mutableStateOf(false) }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel transcription?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to cancel '${transcription.fileName}'? Progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    onCancel()
                }) {
                    Text("Cancel Transcription", color = cs.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep Going")
                }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onExpand() },
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (transcription.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = cs.primary
                        )
                    } else if (transcription.error != null) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = "Error",
                            tint = cs.error,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_action_check),
                            contentDescription = "Complete",
                            tint = cs.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            transcription.fileName,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val statusText = when {
                            transcription.running -> {
                                val pct = if (transcription.percent in 0..100) "${transcription.percent}%" else "Transcribing..."
                                val eta = transcription.etaMs?.let { " • ETA ${remaining(context, it)}" } ?: ""
                                "$pct$eta"
                            }
                            transcription.error != null -> "Failed: ${transcription.error}"
                            else -> "Transcription Complete • Tap to view"
                        }
                        Text(
                            statusText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (transcription.error != null) cs.error else cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (transcription.running) showCancelConfirm = true else onDismiss()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = if (transcription.running) "Cancel" else "Dismiss",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (transcription.running && transcription.percent in 0..100) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { transcription.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHighest
                )
            }
        }
    }
}

@Composable
private fun TranscriptionDialog(
    t: TranscribeUi,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onSave: (Uri) -> Unit,
    onFormat: (TranscriptFormat) -> Unit,
    onSaveToHistory: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit = { _, _ -> },
    onRenameSpeakerInstance: (Int, String) -> Unit = { _, _ -> },
    onDeleteSpeakerLabel: (Int) -> Unit = {},
    onInsertSpeakerLabel: (Int, String) -> Unit = { _, _ -> },
    onInsertSpeakerLabelAtTimestamp: (Long, String) -> Unit = { _, _ -> },
    onReplaceTextInstance: (Int, String, String) -> Unit = { _, _, _ -> },
    onReplaceAllText: (String, String) -> Unit = { _, _ -> },
    onUpdateTurnBlockText: (Int, String, String) -> Unit = { _, _, _ -> },
    onToggleTimestamps: (Boolean) -> Unit = {},
    onToggleSpeakers: (Boolean) -> Unit = {},
    onToggleColorizeSpeakers: (Boolean) -> Unit = {},
    onOpenBatchQueue: (() -> Unit)? = null
) {
    val undoStack = remember { mutableStateListOf<List<TranscriptSegment>>() }

    fun pushUndoAndApply(action: () -> Unit) {
        undoStack.add(t.segments)
        action()
    }

    fun handleUndo() {
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            TranscriptionRun.setSegments(prev)
        }
    }

    FullScreenTranscriptScreen(
        title = t.fileName,
        formattedText = t.formatted,
        segments = t.segments,
        format = t.format,
        showTimestamps = t.showTimestamps,
        showSpeakers = t.showSpeakers,
        colorizeSpeakerLabels = t.colorizeSpeakerLabels,
        running = t.running,
        error = t.error,
        metrics = t.metrics,
        progressPercent = t.percent,
        etaMs = t.etaMs,
        canUndo = undoStack.isNotEmpty(),
        onUndo = { handleUndo() },
        onClose = onClose,
        onCancel = onCancel,
        onSaveFile = onSave,
        onFormat = onFormat,
        onRenameSpeaker = { old, new -> pushUndoAndApply { onRenameSpeaker(old, new) } },
        onRenameSpeakerInstance = { idx, new -> pushUndoAndApply { onRenameSpeakerInstance(idx, new) } },
        onDeleteSpeakerLabel = { idx -> pushUndoAndApply { onDeleteSpeakerLabel(idx) } },
        onInsertSpeakerLabel = { idx, new -> pushUndoAndApply { onInsertSpeakerLabel(idx, new) } },
        onInsertSpeakerLabelAtTimestamp = { ts, new -> pushUndoAndApply { onInsertSpeakerLabelAtTimestamp(ts, new) } },
        onReplaceTextInstance = { idx, f, r -> pushUndoAndApply { onReplaceTextInstance(idx, f, r) } },
        onReplaceAllText = { f, r -> pushUndoAndApply { onReplaceAllText(f, r) } },
        onUpdateTurnBlockText = { idx, o, n -> pushUndoAndApply { onUpdateTurnBlockText(idx, o, n) } },
        onSaveFullText = { newFullText ->
            pushUndoAndApply {
                TranscriptionRun.setSegments(updateSegmentsFromText(t.segments, newFullText))
            }
        },
        onToggleTimestamps = onToggleTimestamps,
        onToggleSpeakers = onToggleSpeakers,
            onToggleColorizeSpeakers = onToggleColorizeSpeakers,
        onSaveToHistory = if (!t.running && t.error == null && t.text.isNotBlank()) onSaveToHistory else null,
        onOpenBatchQueue = onOpenBatchQueue
    )
}

@Composable
private fun HistoryEntryDialog(
    entry: TranscriptHistory.Entry,
    defaultExportFormat: String,
    defaultColorizeSpeakers: Boolean = false,
    onClose: () -> Unit,
    onSaveFile: (Uri, TranscriptFormat, List<TranscriptSegment>, Boolean, Boolean, Boolean, String) -> Unit,
    onUpdateEntry: (String, List<TranscriptSegment>) -> Unit,
    onUpdateColorize: (String, Boolean) -> Unit = { _, _ -> }
) {
    var currentSegments by remember {
        mutableStateOf(
            if (entry.segments.isNotEmpty()) entry.segments else parseTextToSegments(entry.text)
        )
    }
    val undoStack = remember { mutableStateListOf<List<TranscriptSegment>>() }
    var currentFormat by remember { mutableStateOf(TranscriptFormat.fromString(defaultExportFormat)) }
    var showTimestamps by remember { mutableStateOf(true) }
    var showSpeakers by remember { mutableStateOf(true) }
    var colorizeSpeakerLabels by remember(entry.id, entry.colorizeSpeakerLabels) { mutableStateOf(entry.colorizeSpeakerLabels ?: defaultColorizeSpeakers) }

    fun pushUndoAndApply(newSegments: List<TranscriptSegment>) {
        undoStack.add(currentSegments)
        currentSegments = newSegments
        onUpdateEntry(entry.id, currentSegments)
    }

    fun handleUndo() {
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            currentSegments = prev
            onUpdateEntry(entry.id, currentSegments)
        }
    }

    val formattedText = remember(currentSegments, currentFormat, showTimestamps, showSpeakers) {
        if (currentSegments.isNotEmpty()) {
            currentSegments.format(currentFormat, 70, showTimestamps, showSpeakers)
        } else {
            entry.text
        }
    }

    FullScreenTranscriptScreen(
        title = entry.fileName,
        formattedText = formattedText,
        segments = currentSegments,
        format = currentFormat,
        showTimestamps = showTimestamps,
        showSpeakers = showSpeakers,
        colorizeSpeakerLabels = colorizeSpeakerLabels,
        canUndo = undoStack.isNotEmpty(),
        onUndo = { handleUndo() },
        onClose = onClose,
        onSaveFile = { uri -> onSaveFile(uri, currentFormat, currentSegments, showTimestamps, showSpeakers, colorizeSpeakerLabels, formattedText) },
        onFormat = { currentFormat = it },
        onRenameSpeaker = { old, new ->
            pushUndoAndApply(currentSegments.renameSpeaker(old, new))
        },
        onRenameSpeakerInstance = { idx, new ->
            pushUndoAndApply(currentSegments.renameSpeakerInstance(idx, new))
        },
        onDeleteSpeakerLabel = { idx ->
            pushUndoAndApply(currentSegments.deleteSpeakerLabel(idx))
        },
        onInsertSpeakerLabel = { idx, new ->
            pushUndoAndApply(currentSegments.insertSpeakerLabel(idx, new))
        },
        onInsertSpeakerLabelAtTimestamp = { ts, new ->
            pushUndoAndApply(currentSegments.insertSpeakerLabelAtTimestamp(ts, new))
        },
        onReplaceTextInstance = { idx, f, r ->
            pushUndoAndApply(currentSegments.replaceTextInstance(idx, f, r))
        },
        onReplaceAllText = { f, r ->
            pushUndoAndApply(currentSegments.replaceAllText(f, r))
        },
        onUpdateTurnBlockText = { idx, o, n ->
            pushUndoAndApply(currentSegments.updateTurnBlockText(idx, o, n))
        },
        onSaveFullText = { newFullText ->
            pushUndoAndApply(updateSegmentsFromText(currentSegments, newFullText))
        },
        onToggleTimestamps = { showTimestamps = it },
        onToggleSpeakers = { showSpeakers = it },
        onToggleColorizeSpeakers = {
            colorizeSpeakerLabels = it
            onUpdateColorize(entry.id, it)
        }
    )
}

@Composable
private fun StatsLine(t: TranscribeUi) {
    if (!t.metrics.hasData) {
        return
    }
    val metrics = t.metrics
    val rtf = rtfText(metrics)
    Column {
        if (!t.modelName.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.transcribe_stats_model, t.modelName),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(
                R.string.transcribe_stats_line,
                elapsed(metrics.totalMs),
                elapsedOrDash(metrics.decodeMs),
                elapsed(metrics.modelLoadMs),
                elapsedOrDash(metrics.inferenceMs),
                elapsedOrDash(metrics.audioDurationMs),
                rtf,
                memoryText(metrics.pssMb),
                memoryText(metrics.peakPssMb),
                memoryText(metrics.freeRamMb)
            ),
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (metrics.modelPssMb > 0) {
            Text(
                text = stringResource(
                    R.string.transcribe_stats_model_memory,
                    memoryText(metrics.modelPssMb),
                    memoryText(metrics.modelMemoryDeltaMb)
                ),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun memoryText(megabytes: Int): String =
    if (megabytes >= 1024) {
        stringResource(R.string.transcribe_stats_gb, megabytes / 1024f)
    } else {
        stringResource(R.string.transcribe_stats_mb, megabytes)
    }

@Composable
private fun memoryOrDash(megabytes: Int): String = if (megabytes > 0) memoryText(megabytes) else "—"

private fun elapsedOrDash(ms: Long): String = if (ms > 0L) elapsed(ms) else "—"

private fun rtfText(metrics: TranscriptionMetrics): String =
    metrics.rtf?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

// The engine only starts reporting once decoding is done and the model is loaded, so the bar spins
// until then rather than sitting at a misleading zero. The line underneath doubles as the promise
// that walking away is safe — the run keeps going in the notification.
@Composable
private fun RunProgress(t: TranscribeUi) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (t.percent < 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { t.percent.coerceIn(0, 100) / 100f },
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (t.etaMs != null) {
                    stringResource(R.string.transcribing_pct_eta, t.percent, remaining(context, t.etaMs))
                } else {
                    stringResource(R.string.transcribing_pct, t.percent)
                },
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.primary
            )
        }
        if (t.metrics.hasData) {
            Spacer(Modifier.height(6.dp))
            StatsLine(t)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.transcribe_background_hint),
            fontSize = 11.5.sp, color = cs.onSurfaceVariant, lineHeight = 16.sp
        )
    }
}

// Plain text, or one of the timestamped formats. The pick drives the view as well as the export,
// so what is copied or saved is what was on screen.
@Composable
private fun FormatPicker(selected: TranscriptFormat, onSelect: (TranscriptFormat) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        TranscriptFormat.entries.forEach { format ->
            val on = format == selected
            Surface(
                color = if (on) cs.primary else cs.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clickableRow(RoundedCornerShape(20.dp)) { onSelect(format) }
            ) {
                Text(
                    format.name, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LanguageEntry(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lang_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(R.string.lang_entry_sub), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveStatsEntry(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { onToggle(!enabled) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.live_stats_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    if (enabled) stringResource(R.string.live_stats_entry_on)
                    else stringResource(R.string.live_stats_entry_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { onToggle(it) })
        }
    }
}

@Composable
private fun ParagraphWordThresholdEntry(threshold: Int, onThresholdChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Paragraph Word Break Threshold", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    Text("Breaks paragraph after sentence ends when ~$threshold words are spoken.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Text("$threshold words", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = threshold.toFloat(),
                onValueChange = { onThresholdChange(it.toInt()) },
                valueRange = 20f..200f,
                steps = 17,
                colors = SliderDefaults.colors(thumbColor = cs.primary, activeTrackColor = cs.primary)
            )
        }
    }
}

@Composable
private fun ExpectedSpeakersEntry(expectedKey: String, onExpectedChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val options = listOf(
        "auto" to "Auto (Dynamic separation)",
        "2" to "2 Speakers (Interview / Dialogue)",
        "1" to "1 Speaker (Solo)",
        "3+" to "3+ Speakers (Panel / Group)"
    )

    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Expected Speakers", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Text("Enforces speaker partitioning to separate dialogues cleanly.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (key, label) ->
                    val selected = key == expectedKey
                    Surface(
                        color = if (selected) cs.primaryContainer else cs.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onExpectedChange(key) }
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) cs.onPrimaryContainer else cs.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            if (selected) {
                                Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiarizationSensitivityEntry(sensitivityKey: String, onSensitivityChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val options = listOf(
        "low" to "Low (Loose — Merges similar voices)",
        "normal" to "Normal (Balanced default)",
        "high" to "High (Strict — Keeps voices separate)",
        "very_high" to "Very High (Aggressive separation)",
        "ultra" to "Ultra (Extreme strictness)"
    )

    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Speaker Separation Sensitivity", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Text("Controls acoustic distance threshold for auto-detecting speakers.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (key, label) ->
                    val selected = key == sensitivityKey
                    Surface(
                        color = if (selected) cs.primaryContainer else cs.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSensitivityChange(key) }
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) cs.onPrimaryContainer else cs.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            if (selected) {
                                Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorizeSpeakerLabelsEntry(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { onToggle(!enabled) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Colorize Speaker Labels", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    if (enabled) "Display each speaker name in a distinct color in transcript view and DOCX export"
                    else "Speaker labels are shown in standard theme accent color and monochrome DOCX export",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { onToggle(it) })
        }
    }
}

@Composable
private fun DictionaryEntry(enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dict_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    stringResource(if (enabled) R.string.dict_entry_on else R.string.dict_entry_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BenchmarkEntry(runs: List<BenchmarkRun>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val latest = runs.firstOrNull()
    val subtitle = if (latest == null) {
        stringResource(R.string.benchmark_history_empty)
    } else {
        stringResource(
            R.string.benchmark_latest,
            latest.modelName ?: "—",
            rtfText(latest.metrics),
            memoryText(latest.metrics.peakPssMb)
        )
    }
    Surface(
        color = cs.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.benchmark_history), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BenchmarkHistoryDialog(
    runs: List<BenchmarkRun>,
    onDismiss: () -> Unit,
    onOpenFullscreen: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.benchmark_history), modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenFullscreen) {
                    Icon(
                        painterResource(R.drawable.ic_fullscreen),
                        contentDescription = stringResource(R.string.benchmark_fullscreen),
                        tint = cs.primary
                    )
                }
            }
        },
        text = {
            if (runs.isEmpty()) {
                Text(stringResource(R.string.benchmark_history_empty), fontSize = 13.sp, color = cs.onSurfaceVariant)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    runs.forEach { run ->
                        BenchmarkRunCard(run, Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
internal fun BenchmarkRunCard(run: BenchmarkRun, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        run.modelName ?: "—",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        run.fileName,
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    benchmarkTimestamp(run.timestampMs),
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_rtf),
                    rtfText(run.metrics),
                    Modifier.weight(1f)
                )
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_load),
                    elapsed(run.metrics.modelLoadMs),
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_model_pss),
                    memoryOrDash(run.metrics.modelPssMb),
                    Modifier.weight(1f)
                )
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_peak),
                    memoryOrDash(run.metrics.peakPssMb),
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_model_delta),
                    memoryOrDash(run.metrics.modelMemoryDeltaMb),
                    Modifier.weight(1f)
                )
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_free),
                    memoryOrDash(run.metrics.freeRamMb),
                    Modifier.weight(1f)
                )
            }
        }
    }
}

private fun benchmarkTimestamp(timestampMs: Long): String {
    val date = Date(timestampMs)
    return if (DateUtils.isToday(timestampMs)) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    } else {
        DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }
}

@Composable
private fun BenchmarkMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Every custom key occupies this height whether it is being read or edited, so switching one to
// an edit field never resizes the row the list has already placed.
private val ROW_HEIGHT = 56.dp

@Composable
private fun LanguageDialog(onDismiss: () -> Unit, onPick: (LanguageOption) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.your_language)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.lang_dialog_hint),
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp
                )
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    LanguageCatalog.LANGUAGES.forEach { lang ->
                        Column(
                            Modifier.fillMaxWidth().clickableRow(RectangleShape) { onPick(lang) }.padding(vertical = 10.dp)
                        ) {
                            Text(stringResource(lang.nameRes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Text(stringResource(lang.noteRes), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hf_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.hf_dialog_body), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, placeholder = { Text(stringResource(R.string.hf_placeholder)) })
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(stringResource(R.string.action_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private fun Modifier.clickableRow(shape: Shape, onClick: () -> Unit): Modifier =
    this.clip(shape).clickable(onClick = onClick)

private fun openDictionary(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent().setClassName(
                context.packageName,
                "org.scrib.transcriber.DictionaryActivity"
            )
        )
    }
}

internal fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
}.getOrNull()
