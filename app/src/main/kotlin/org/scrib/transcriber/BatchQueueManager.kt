package org.scrib.transcriber

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BatchItemStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    SKIPPED
}

data class BatchItem(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val status: BatchItemStatus = BatchItemStatus.QUEUED,
    val percent: Int = 0,
    val error: String? = null,
    val resultText: String? = null,
    val segments: List<TranscriptSegment> = emptyList()
)

data class BatchBatchState(
    val running: Boolean = false,
    val items: List<BatchItem> = emptyList(),
    val currentIndex: Int = 0,
    val completedCount: Int = 0
)

object BatchQueueManager {

    private val _state = MutableStateFlow(BatchBatchState())
    val state: StateFlow<BatchBatchState> = _state.asStateFlow()

    fun setItems(items: List<BatchItem>) {
        _state.update { BatchBatchState(items = items) }
    }

    fun removeItem(id: String) {
        _state.update { current ->
            val updated = current.items.filterNot { it.id == id }
            current.copy(items = updated)
        }
    }

    fun updateItemStatus(
        id: String,
        status: BatchItemStatus,
        percent: Int = 0,
        error: String? = null,
        resultText: String? = null,
        segments: List<TranscriptSegment> = emptyList()
    ) {
        _state.update { current ->
            val updated = current.items.map { item ->
                if (item.id == id) {
                    item.copy(
                        status = status,
                        percent = percent,
                        error = error,
                        resultText = resultText ?: item.resultText,
                        segments = if (segments.isNotEmpty()) segments else item.segments
                    )
                } else item
            }
            val completed = updated.count { it.status == BatchItemStatus.COMPLETED }
            current.copy(items = updated, completedCount = completed)
        }
    }

    fun setRunning(running: Boolean) {
        _state.update { it.copy(running = running) }
    }

    fun setCurrentIndex(index: Int) {
        _state.update { it.copy(currentIndex = index) }
    }

    fun clear() {
        _state.value = BatchBatchState()
    }
}
