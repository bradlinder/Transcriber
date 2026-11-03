package org.scrib.transcriber

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TranscriptHistory {

    data class Entry(
        val id: String,
        val savedAtMs: Long,
        val fileName: String,
        val modelName: String?,
        val text: String,
        val segments: List<TranscriptSegment>,
        val colorizeSpeakerLabels: Boolean? = null
    )

    fun save(context: Context, transcript: TranscribeUi): Entry {
        val entryId = transcript.historyId?.takeIf { it.isNotBlank() }
            ?: (System.currentTimeMillis().toString(36) + "-" + (0..9999).random())
        val entry = Entry(
            id = entryId,
            savedAtMs = System.currentTimeMillis(),
            fileName = transcript.fileName,
            modelName = transcript.modelName,
            text = transcript.text,
            segments = transcript.segments,
            colorizeSpeakerLabels = transcript.colorizeSpeakerLabels
        )
        write(context, entry)
        return entry
    }

    fun load(context: Context): List<Entry> =
        dir(context).listFiles()
            ?.mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            ?.sortedByDescending { it.savedAtMs }
            ?: emptyList()

    fun delete(context: Context, id: String) {
        try {
            file(context, id).delete()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't drop the kept transcript", e)
        }
    }

    fun renameEntry(context: Context, id: String, newName: String) {
        try {
            val existing = file(context, id)
            if (!existing.exists()) return
            val entry = read(existing) ?: return
            val cleanName = newName.trim().ifBlank { entry.fileName }
            val updatedEntry = entry.copy(fileName = cleanName)
            write(context, updatedEntry)
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't rename history entry $id", e)
        }
    }

    fun updateEntry(context: Context, id: String, updatedSegments: List<TranscriptSegment>) {
        try {
            val existing = file(context, id)
            if (!existing.exists()) return
            val entry = read(existing) ?: return
            val newText = updatedSegments.format(TranscriptFormat.TXT)
            val updatedEntry = entry.copy(text = newText, segments = updatedSegments)
            write(context, updatedEntry)
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't update history entry $id", e)
        }
    }

    fun updateColorize(context: Context, id: String, colorize: Boolean) {
        try {
            val existing = file(context, id)
            if (!existing.exists()) return
            val entry = read(existing) ?: return
            val updatedEntry = entry.copy(colorizeSpeakerLabels = colorize)
            write(context, updatedEntry)
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't update history entry colorize $id", e)
        }
    }

    private fun write(context: Context, entry: Entry) {
        try {
            val segments = JSONArray()
            entry.segments.forEach {
                segments.put(
                    JSONObject()
                        .put(START, it.startMs)
                        .put(END, it.endMs)
                        .put(TEXT, it.text)
                        .put(PARAGRAPH, it.startsParagraph)
                        .put(SPEAKER, it.speaker)
                )
            }
            val json = JSONObject()
                .put(ID, entry.id)
                .put(SAVED_AT, entry.savedAtMs)
                .put(NAME, entry.fileName)
                .put(MODEL, entry.modelName.orEmpty())
                .put(TEXT, entry.text)
                .put(SEGMENTS, segments)
            if (entry.colorizeSpeakerLabels != null) {
                json.put(COLORIZE_SPEAKERS, entry.colorizeSpeakerLabels)
            }
            dir(context).mkdirs()
            file(context, entry.id).writeText(json.toString())
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't keep the transcript", e)
        }
    }

    private fun read(file: File): Entry? {
        val json = JSONObject(file.readText())
        val text = json.optString(TEXT)
        if (text.isBlank()) {
            return null
        }
        val array = json.optJSONArray(SEGMENTS) ?: JSONArray()
        val segments = ArrayList<TranscriptSegment>(array.length())
        for (i in 0 until array.length()) {
            val segment = array.getJSONObject(i)
            segments.add(
                TranscriptSegment(
                    segment.optLong(START),
                    segment.optLong(END),
                    segment.optString(TEXT),
                    segment.optBoolean(PARAGRAPH),
                    speaker = segment.optString(SPEAKER)
                )
            )
        }
        return Entry(
            id = json.optString(ID).ifBlank { file.nameWithoutExtension },
            savedAtMs = json.optLong(SAVED_AT),
            fileName = json.optString(NAME),
            modelName = json.optString(MODEL).takeIf { it.isNotBlank() },
            text = text,
            segments = segments,
            colorizeSpeakerLabels = if (json.has(COLORIZE_SPEAKERS)) json.getBoolean(COLORIZE_SPEAKERS) else null
        )
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun file(context: Context, id: String) = File(dir(context), "$id.json")

    private const val LOG_TAG = "TranscriptHistory"
    private const val DIR = "history"
    private const val ID = "id"
    private const val SAVED_AT = "savedAt"
    private const val NAME = "name"
    private const val MODEL = "model"
    private const val TEXT = "text"
    private const val SEGMENTS = "segments"
    private const val START = "start"
    private const val END = "end"
    private const val PARAGRAPH = "paragraph"
    private const val SPEAKER = "speaker"
    private const val COLORIZE_SPEAKERS = "colorizeSpeakerLabels"
}
