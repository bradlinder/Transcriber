package org.scrib.transcriber

import java.util.Locale

// One decoded utterance and where it sits in the recording.
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val startsParagraph: Boolean = false,
    val speaker: String = "",
    val words: List<AcousticAligner.TimedWord> = emptyList()
)

data class TimedSentence(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String = "",
    val words: List<AcousticAligner.TimedWord> = emptyList()
)

// Explicit word timing data structure for Stage 1 word-level speaker assignment
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

enum class SpeakerAssignmentStatus {
    ASSIGNED,
    UNCERTAIN,
    UNASSIGNED,
    INVALID_TIMESTAMPS
}

data class WordSpeakerAssignment(
    val word: WordTiming,
    val speaker: String?,
    val status: SpeakerAssignmentStatus,
    val confidence: Float = 0.0f
)

// A stretch of the recording that held speech, as heard by the VAD.
data class SpeechSpan(val startMs: Long, val endMs: Long)

enum class TranscriptFormat(val extension: String) {
    DOCX("docx"),
    TXT("txt"),
    SRT("srt"),
    VTT("vtt"),
    LRC("lrc");

    companion object {
        fun fromString(value: String): TranscriptFormat {
            return entries.firstOrNull { it.extension.equals(value, ignoreCase = true) } ?: DOCX
        }
    }
}

fun List<TranscriptSegment>.renameSpeaker(oldName: String, newName: String): List<TranscriptSegment> {
    if (oldName.isBlank() || oldName == newName) return this
    val oldTrimmed = oldName.trim()
    val newTrimmed = newName.trim()

    return map { segment ->
        val newSpeaker = if (segment.speaker.equals(oldTrimmed, ignoreCase = true)) newTrimmed else segment.speaker
        segment.copy(speaker = newSpeaker)
    }
}

fun List<TranscriptSegment>.renameSpeakerInstance(segmentIndex: Int, newName: String): List<TranscriptSegment> {
    if (segmentIndex !in indices) return this
    val targetSpeaker = this[segmentIndex].speaker
    val trimmedNewName = newName.trim()
    if (trimmedNewName.isBlank()) return this

    val result = toMutableList()
    var idx = segmentIndex
    while (idx < result.size && (result[idx].speaker == targetSpeaker || result[idx].speaker.isBlank() || idx == segmentIndex)) {
        result[idx] = result[idx].copy(speaker = trimmedNewName)
        idx++
    }
    return result
}

fun List<TranscriptSegment>.deleteSpeakerLabel(segmentIndex: Int): List<TranscriptSegment> {
    if (segmentIndex !in indices) return this
    val targetSpeaker = this[segmentIndex].speaker
    val prevSpeaker = (segmentIndex - 1 downTo 0)
        .map { this[it].speaker }
        .firstOrNull { it.isNotBlank() } ?: ""

    val result = toMutableList()
    var idx = segmentIndex
    while (idx < result.size && (result[idx].speaker == targetSpeaker || result[idx].speaker.isBlank() || idx == segmentIndex)) {
        result[idx] = result[idx].copy(speaker = prevSpeaker)
        idx++
    }
    return result
}

fun List<TranscriptSegment>.insertSpeakerLabel(segmentIndex: Int, newSpeakerName: String): List<TranscriptSegment> {
    if (segmentIndex !in indices || newSpeakerName.isBlank()) return this
    val originalSpeaker = this[segmentIndex].speaker
    val trimmedName = newSpeakerName.trim()

    val result = toMutableList()
    var idx = segmentIndex
    while (idx < result.size && (result[idx].speaker == originalSpeaker || result[idx].speaker.isBlank() || idx == segmentIndex)) {
        result[idx] = result[idx].copy(speaker = trimmedName)
        idx++
    }
    return result
}

fun List<TranscriptSegment>.replaceTextInstance(
    matchIndex: Int,
    findText: String,
    replaceText: String
): List<TranscriptSegment> {
    if (findText.isBlank() || isEmpty()) return this

    var currentMatchCount = 0
    val regex = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)

    return map { seg ->
        val matches = regex.findAll(seg.text).toList()
        if (matches.isEmpty()) {
            seg
        } else {
            var updatedText = seg.text
            var offsetShift = 0

            for (match in matches) {
                if (currentMatchCount == matchIndex) {
                    val start = match.range.first + offsetShift
                    val end = match.range.last + 1 + offsetShift
                    updatedText = updatedText.substring(0, start) + replaceText + updatedText.substring(end)
                    currentMatchCount++
                    break
                }
                currentMatchCount++
            }
            seg.copy(text = updatedText)
        }
    }
}

fun List<TranscriptSegment>.replaceAllText(
    findText: String,
    replaceText: String
): List<TranscriptSegment> {
    if (findText.isBlank() || isEmpty()) return this
    val regex = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)

    return map { seg ->
        val newText = seg.text.replace(regex, replaceText)
        seg.copy(text = newText)
    }
}

fun List<TranscriptSegment>.updateTurnBlockText(
    segmentIndex: Int,
    oldText: String,
    newText: String
): List<TranscriptSegment> {
    if (segmentIndex !in indices) return this
    val result = toMutableList()
    val target = result[segmentIndex]
    result[segmentIndex] = target.copy(text = newText)
    return result
}

fun List<TranscriptSegment>.insertSpeakerLabelAtTimestamp(
    timestampMs: Long,
    newSpeakerName: String
): List<TranscriptSegment> {
    if (isEmpty() || newSpeakerName.isBlank()) return this
    val trimmedName = newSpeakerName.trim()

    val segIdx = indexOfFirst { timestampMs in it.startMs..it.endMs }
        .let { if (it >= 0) it else indexOfFirst { s -> s.startMs >= timestampMs }.coerceAtLeast(0) }

    if (segIdx !in indices) return this
    val targetSeg = this[segIdx]

    if (timestampMs <= targetSeg.startMs + 100L) {
        return insertSpeakerLabel(segIdx, trimmedName)
    }

    val sentences = listOf(targetSeg).toSentences()
    var splitSentenceIdx = sentences.indexOfFirst { timestampMs >= it.startMs && (timestampMs < it.endMs || it == sentences.last()) }
    if (splitSentenceIdx < 0) splitSentenceIdx = 1

    if (splitSentenceIdx == 0) {
        return insertSpeakerLabel(segIdx, trimmedName)
    }

    val leftSentences = sentences.take(splitSentenceIdx)
    val rightSentences = sentences.drop(splitSentenceIdx)

    if (leftSentences.isEmpty() || rightSentences.isEmpty()) {
        return insertSpeakerLabel(segIdx, trimmedName)
    }

    val leftText = leftSentences.joinToString(" ") { it.text }
    val rightText = rightSentences.joinToString(" ") { it.text }

    val leftEndMs = leftSentences.last().endMs
    val rightStartMs = rightSentences.first().startMs

    val segLeft = targetSeg.copy(
        endMs = leftEndMs,
        text = leftText,
        words = targetSeg.words.filter { it.startMs <= leftEndMs }
    )
    val segRight = targetSeg.copy(
        startMs = rightStartMs,
        text = rightText,
        speaker = trimmedName,
        startsParagraph = true,
        words = targetSeg.words.filter { it.startMs >= rightStartMs }
    )

    val result = toMutableList()
    result[segIdx] = segLeft
    result.add(segIdx + 1, segRight)

    val originalSpeaker = targetSeg.speaker
    var idx = segIdx + 2
    while (idx < result.size && (result[idx].speaker == originalSpeaker || result[idx].speaker.isBlank())) {
        result[idx] = result[idx].copy(speaker = trimmedName)
        idx++
    }

    return result
}

data class TranscriptSentenceUnit(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String
)

data class TranscriptTurnBlock(
    val segmentIndex: Int,
    val startMs: Long,
    val speaker: String,
    val text: String,
    val showSpeakerLabel: Boolean = true,
    val sentences: List<TranscriptSentenceUnit> = emptyList()
)

fun List<TranscriptSegment>.toTurnBlocks(wordThreshold: Int = 70): List<TranscriptTurnBlock> {
    val sentences = this.toSentences()
    if (sentences.isEmpty()) return emptyList()

    val rawBlocks = mutableListOf<TranscriptTurnBlock>()
    var accumulatedWords = 0
    var currentSpeaker = sentences[0].speaker
    var blockStartMs = sentences[0].startMs
    var blockStartSegIndex = 0
    val currentBlockSentences = mutableListOf<TimedSentence>()

    fun flushBlock() {
        if (currentBlockSentences.isEmpty()) return
        val units = currentBlockSentences.map {
            TranscriptSentenceUnit(it.startMs, it.endMs, it.text, it.speaker)
        }
        rawBlocks.add(
            TranscriptTurnBlock(
                segmentIndex = blockStartSegIndex,
                startMs = blockStartMs,
                speaker = currentSpeaker,
                text = currentBlockSentences.joinToString(" ") { it.text },
                showSpeakerLabel = true,
                sentences = units
            )
        )
        currentBlockSentences.clear()
        accumulatedWords = 0
    }

    for (s in sentences) {
        val words = s.text.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val speakerChanged = currentSpeaker.isNotBlank() && s.speaker.isNotBlank() && s.speaker != currentSpeaker

        val lastText = currentBlockSentences.lastOrNull()?.text?.trim().orEmpty()
        val endsWithPunct = lastText.isNotEmpty() && (
            lastText.endsWith('.') || lastText.endsWith('?') || lastText.endsWith('!') ||
            lastText.endsWith(".\"") || lastText.endsWith("?\"") || lastText.endsWith("!\"") ||
            lastText.endsWith(".'") || lastText.endsWith("?'") || lastText.endsWith("!'")
        )
        val sStartsWithLower = s.text.trim().firstOrNull()?.isLowerCase() == true

        val naturalSentenceBoundary = endsWithPunct && !sStartsWithLower
        val forceBreakAfterRunon = accumulatedWords >= wordThreshold * 2 && !sStartsWithLower
        val validSpeakerTransition = speakerChanged && (naturalSentenceBoundary || currentBlockSentences.isEmpty() || !sStartsWithLower)

        if (validSpeakerTransition || (accumulatedWords >= wordThreshold && currentBlockSentences.isNotEmpty() && (naturalSentenceBoundary || forceBreakAfterRunon))) {
            flushBlock()
            currentSpeaker = s.speaker
            blockStartMs = s.startMs
            val segIdx = indexOfFirst { it.startMs >= s.startMs }.coerceAtLeast(0)
            blockStartSegIndex = segIdx
        }

        if (currentBlockSentences.isEmpty()) {
            blockStartMs = s.startMs
            currentSpeaker = s.speaker
            val segIdx = indexOfFirst { it.startMs >= s.startMs }.coerceAtLeast(0)
            blockStartSegIndex = segIdx
        }

        currentBlockSentences.add(s)
        accumulatedWords += words.size
    }
    flushBlock()

    var lastPrintedSpeaker = ""
    return rawBlocks.map { b ->
        val shouldShow = b.speaker.isNotBlank() && b.speaker != lastPrintedSpeaker
        if (b.speaker.isNotBlank()) {
            lastPrintedSpeaker = b.speaker
        }
        b.copy(showSpeakerLabel = shouldShow)
    }
}

fun List<TranscriptSegment>.uniqueSpeakers(): List<String> {
    val speakers = mutableSetOf<String>()

    forEach { seg ->
        if (seg.speaker.isNotBlank()) {
            speakers.add(seg.speaker.trim())
        }
    }

    toSentences().forEach { sentence ->
        if (sentence.speaker.isNotBlank()) {
            speakers.add(sentence.speaker.trim())
        }
    }

    val headerRegex = Regex("""(?:\[\d{2}:\d{2}\]\s*)?([^:\n\[\]]{2,30}):""")
    forEach { seg ->
        headerRegex.findAll(seg.text).forEach { match ->
            val spk = match.groupValues[1].trim()
            if (spk.isNotBlank() && !spk.startsWith("http", ignoreCase = true)) {
                speakers.add(spk)
            }
        }
    }

    return speakers.toList().sorted()
}

fun parseTextToSegments(text: String): List<TranscriptSegment> {
    if (text.isBlank()) return emptyList()

    val segments = mutableListOf<TranscriptSegment>()
    val blockRegex = Regex("""\[(?:(\d{1,3}):)?(\d{1,3}):(\d{2})\]\s*(?:([^:]+):\s*)?([\s\S]*)""")
    val speakerRegex = Regex("""^([^:\n]+):\s*([\s\S]*)""")
    val blocks = text.split("\n\n").filter { it.isNotBlank() }

    for (block in blocks) {
        val trimmedBlock = block.trim()
        val match = blockRegex.find(trimmedBlock)
        if (match != null) {
            val hasHours = match.groupValues[1].isNotBlank()
            val startMs = if (hasHours) {
                val hours = match.groupValues[1].toLongOrNull() ?: 0L
                val mins = match.groupValues[2].toLongOrNull() ?: 0L
                val secs = match.groupValues[3].toLongOrNull() ?: 0L
                (hours * 3600 + mins * 60 + secs) * 1000L
            } else {
                val mins = match.groupValues[2].toLongOrNull() ?: 0L
                val secs = match.groupValues[3].toLongOrNull() ?: 0L
                (mins * 60 + secs) * 1000L
            }
            val speaker = match.groupValues[4].trim()
            val content = match.groupValues[5].trim()

            segments.add(
                TranscriptSegment(
                    startMs = startMs,
                    endMs = startMs + 5000L,
                    text = content.ifBlank { trimmedBlock },
                    startsParagraph = true,
                    speaker = speaker
                )
            )
        } else {
            val spkMatch = speakerRegex.find(trimmedBlock)
            if (spkMatch != null) {
                val spk = spkMatch.groupValues[1].trim()
                val content = spkMatch.groupValues[2].trim()
                segments.add(
                    TranscriptSegment(
                        startMs = 0L,
                        endMs = 5000L,
                        text = content.ifBlank { trimmedBlock },
                        startsParagraph = true,
                        speaker = spk
                    )
                )
            } else {
                segments.add(
                    TranscriptSegment(
                        startMs = 0L,
                        endMs = 5000L,
                        text = trimmedBlock,
                        startsParagraph = true,
                        speaker = ""
                    )
                )
            }
        }
    }

    return segments
}

fun List<TranscriptSegment>.format(
    format: TranscriptFormat,
    wordThreshold: Int = 70,
    showTimestamps: Boolean = true,
    showSpeakers: Boolean = true
): String = when (format) {
    TranscriptFormat.TXT, TranscriptFormat.DOCX -> paragraphsWithTimestamps(wordThreshold, showTimestamps, showSpeakers)
    TranscriptFormat.SRT -> mapIndexed { i, s ->
        val prefix = if (showSpeakers && s.speaker.isNotBlank()) "[${s.speaker}] " else ""
        val timeHeader = if (showTimestamps) "${clock(s.startMs, ',')} --> ${clock(s.endMs, ',')}\n" else ""
        "${i + 1}\n$timeHeader$prefix${s.text.trim()}"
    }.joinToString(separator = "\n\n", postfix = "\n")
    TranscriptFormat.VTT -> map { s ->
        val prefix = if (showSpeakers && s.speaker.isNotBlank()) "<v ${s.speaker}>" else ""
        val suffix = if (showSpeakers && s.speaker.isNotBlank()) "</v>" else ""
        val timeHeader = if (showTimestamps) "${clock(s.startMs, '.')} --> ${clock(s.endMs, '.')} \n" else ""
        "$timeHeader$prefix${s.text.trim()}$suffix"
    }.joinToString(separator = "\n\n", prefix = "WEBVTT\n\n", postfix = "\n")
    TranscriptFormat.LRC -> joinToString(separator = "\n", postfix = "\n") {
        val timeHeader = if (showTimestamps) "[${lrcClock(it.startMs)}] " else ""
        val prefix = if (showSpeakers && it.speaker.isNotBlank()) "[${it.speaker}] " else ""
        "$timeHeader$prefix${it.text.trim()}"
    }
}

// Cleans standard ASR silence hallucinations, trailing phrase artifacts, and degenerate repeating n-gram loops.
fun List<TranscriptSegment>.scrubTranscript(): List<TranscriptSegment> =
    TranscriptCleaner.scrubSegments(this)

private const val PARAGRAPH_GAP_MS = 1500L

fun markParagraphs(segments: List<TranscriptSegment>, speech: List<SpeechSpan>): List<TranscriptSegment> {
    if (speech.isEmpty()) {
        return segments.mapIndexed { i, segment ->
            val gap = i > 0 && segment.startMs - segments[i - 1].endMs >= PARAGRAPH_GAP_MS
            if (gap) segment.copy(startsParagraph = true) else segment
        }
    }
    val opens = HashSet<Int>()
    speech.zipWithNext { before, after ->
        if (after.startMs - before.endMs >= PARAGRAPH_GAP_MS) {
            val next = segments.indexOfFirst { it.startMs >= after.startMs }
            if (next > 0) {
                opens.add(next)
            }
        }
    }
    return segments.mapIndexed { i, segment ->
        if (opens.contains(i)) segment.copy(startsParagraph = true) else segment
    }
}

// Splits coarse C++ segments into individual timed sentence units.
fun List<TranscriptSegment>.toSentences(): List<TimedSentence> = toSentencesInternal(null)

fun List<TranscriptSegment>.toSentences(
    audio: DecodedAudio,
    sampleRate: Int = 16000
): List<TimedSentence> {
    val precomputedEnergy = if (audio.sampleCount > 0) {
        AcousticAligner.computeEnergyProfile(audio, sampleRate)
    } else null
    return toSentencesInternal(precomputedEnergy)
}

fun List<TranscriptSegment>.toSentences(
    samples: FloatArray,
    sampleRate: Int = 16000
): List<TimedSentence> {
    val precomputedEnergy = if (samples.isNotEmpty()) {
        AcousticAligner.computeEnergyProfile(samples, sampleRate)
    } else null
    return toSentencesInternal(precomputedEnergy)
}

private fun List<TranscriptSegment>.toSentencesInternal(
    precomputedEnergy: FloatArray?
): List<TimedSentence> {
    val sentences = mutableListOf<TimedSentence>()
    for (seg in this) {
        val trimmed = seg.text.trim()
        if (trimmed.isBlank()) continue

        val regex = Regex("""(?<=[.?!])\s+""")
        val rawSentences = trimmed.split(regex).filter { it.isNotBlank() }
        if (rawSentences.isEmpty()) continue

        val aligned = if (precomputedEnergy != null && precomputedEnergy.isNotEmpty()) {
            AcousticAligner.alignSentencesWithEnergy(
                rawSentences = rawSentences,
                startMs = seg.startMs,
                endMs = seg.endMs,
                energy = precomputedEnergy,
                speaker = seg.speaker,
                words = seg.words
            )
        } else {
            val duration = maxOf(1L, seg.endMs - seg.startMs)
            val totalChars = maxOf(1, trimmed.length)
            var currentOffsetMs = seg.startMs
            rawSentences.map { sText ->
                val sTrimmed = sText.trim()
                val sentenceLen = sTrimmed.length
                val sentenceDuration = (duration.toDouble() * sentenceLen / totalChars).toLong()
                val sEndMs = minOf(seg.endMs, currentOffsetMs + sentenceDuration)
                val s = TimedSentence(startMs = currentOffsetMs, endMs = sEndMs, text = sTrimmed, speaker = seg.speaker)
                currentOffsetMs = sEndMs
                s
            }
        }

        for (s in aligned) {
            val sTrimmed = s.text.trim()
            if (sTrimmed.isEmpty()) continue
            val sameSpeaker = sentences.isNotEmpty() && (seg.speaker.isBlank() || sentences.last().speaker.isBlank() || seg.speaker == sentences.last().speaker)

            val lastIdx = sentences.size - 1
            val last = if (lastIdx >= 0) sentences[lastIdx] else null
            val lastText = last?.text?.trim().orEmpty()
            val lastEndsWithPunct = lastText.isNotEmpty() && (
                lastText.endsWith('.') || lastText.endsWith('?') || lastText.endsWith('!') ||
                lastText.endsWith(".\"") || lastText.endsWith("?\"") || lastText.endsWith("!\"") ||
                lastText.endsWith(".'") || lastText.endsWith("?'") || lastText.endsWith("!'")
            )
            val startsWithLower = sTrimmed.first().isLowerCase()

            // If the preceding sentence ended mid-thought (no punctuation) OR this segment starts with lowercase (continuation across 30s window boundary), merge with last sentence
            val shouldMerge = sameSpeaker && last != null && (!lastEndsWithPunct || startsWithLower)

            if (shouldMerge) {
                val stripTerminalPunct = startsWithLower && lastEndsWithPunct
                val cleanLast = if (stripTerminalPunct) {
                    lastText.trimEnd('.', '?', '!', ',', ';', ':', '"', '\'')
                } else {
                    lastText
                }
                val updatedLastWords = if (stripTerminalPunct && last!!.words.isNotEmpty()) {
                    val lastWordIdx = last.words.lastIndex
                    val prevWord = last.words[lastWordIdx]
                    val strippedWord = prevWord.word.trimEnd('.', '?', '!', ',', ';', ':', '"', '\'')
                    if (strippedWord.isNotEmpty() && strippedWord != prevWord.word) {
                        last.words.toMutableList().apply {
                            this[lastWordIdx] = prevWord.copy(word = strippedWord)
                        }
                    } else {
                        last.words
                    }
                } else {
                    last!!.words
                }

                // If a window boundary cut straddled a single word so the last token of window N
                // and first token of window N+1 are the identical word within 400ms, drop the duplicate lead token.
                val boundaryNormLast = updatedLastWords.lastOrNull()?.word
                    ?.trim('.', '?', '!', ',', ';', ':', '"', '\'')
                    ?.lowercase(Locale.ROOT)
                val boundaryNormFirst = s.words.firstOrNull()?.word
                    ?.trim('.', '?', '!', ',', ';', ':', '"', '\'')
                    ?.lowercase(Locale.ROOT)
                val isAbuttingDuplicateWord = !boundaryNormLast.isNullOrEmpty() &&
                    boundaryNormLast == boundaryNormFirst &&
                    (s.words.first().startMs - updatedLastWords.last().endMs) <= 400L

                val mergedRightWords = if (isAbuttingDuplicateWord) s.words.drop(1) else s.words
                val mergedRightText = if (isAbuttingDuplicateWord) {
                    sTrimmed.split(Regex("""\s+"""), limit = 2).getOrNull(1)?.trim().orEmpty()
                } else {
                    sTrimmed
                }

                sentences[lastIdx] = last.copy(
                    endMs = maxOf(last.endMs, s.endMs),
                    text = if (mergedRightText.isNotEmpty()) "$cleanLast $mergedRightText".trim() else cleanLast,
                    words = updatedLastWords + mergedRightWords
                )
            } else {
                sentences.add(s)
            }
        }
    }
    return sentences
}

// Breaks paragraphs after sentence-ending punctuation (.?!) when wordThreshold (default 70 words) is reached
// OR immediately whenever a speaker change occurs. Prepends time marker [MM:SS] to each paragraph block.
fun List<TranscriptSegment>.paragraphsWithTimestamps(
    wordThreshold: Int = 70,
    showTimestamps: Boolean = true,
    showSpeakers: Boolean = true
): String = buildString {
    val sentences = this@paragraphsWithTimestamps.toSentences()
    if (sentences.isEmpty()) return@buildString

    var accumulatedWords = 0
    var currentSpeaker = sentences[0].speaker
    var lastPrintedSpeaker = ""
    var blockStartMs = sentences[0].startMs
    val currentBlockSentences = mutableListOf<String>()

    fun flushBlock() {
        if (currentBlockSentences.isEmpty()) return
        if (isNotEmpty()) append("\n\n")

        val timeLabel = if (showTimestamps) "[${clockMMSS(blockStartMs)}] " else ""
        val showSpeakerLabel = showSpeakers && currentSpeaker.isNotBlank() && currentSpeaker != lastPrintedSpeaker
        val speakerLabel = if (showSpeakerLabel) "$currentSpeaker: " else ""
        append("$timeLabel$speakerLabel${currentBlockSentences.joinToString(" ")}")

        if (currentSpeaker.isNotBlank()) {
            lastPrintedSpeaker = currentSpeaker
        }

        currentBlockSentences.clear()
        accumulatedWords = 0
    }

    for (s in sentences) {
        val words = s.text.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val sentenceWordCount = words.size

        val speakerChanged = currentSpeaker.isNotBlank() && s.speaker.isNotBlank() && s.speaker != currentSpeaker

        val lastText = currentBlockSentences.lastOrNull()?.trim().orEmpty()
        val endsWithPunct = lastText.isNotEmpty() && (
            lastText.endsWith('.') || lastText.endsWith('?') || lastText.endsWith('!') ||
            lastText.endsWith(".\"") || lastText.endsWith("?\"") || lastText.endsWith("!\"") ||
            lastText.endsWith(".'") || lastText.endsWith("?'") || lastText.endsWith("!'")
        )
        val sStartsWithLower = s.text.trim().firstOrNull()?.isLowerCase() == true

        val naturalSentenceBoundary = endsWithPunct && !sStartsWithLower
        val forceBreakAfterRunon = accumulatedWords >= wordThreshold * 2 && !sStartsWithLower
        val validSpeakerTransition = speakerChanged && (naturalSentenceBoundary || currentBlockSentences.isEmpty() || !sStartsWithLower)

        if (validSpeakerTransition || (accumulatedWords >= wordThreshold && currentBlockSentences.isNotEmpty() && (naturalSentenceBoundary || forceBreakAfterRunon))) {
            flushBlock()
            currentSpeaker = s.speaker
            blockStartMs = s.startMs
        }

        if (currentBlockSentences.isEmpty()) {
            blockStartMs = s.startMs
            currentSpeaker = s.speaker
        }

        currentBlockSentences.add(s.text.trim())
        accumulatedWords += sentenceWordCount
    }

    flushBlock()
}.trim()

fun updateSegmentsFromText(
    originalSegments: List<TranscriptSegment>,
    newText: String
): List<TranscriptSegment> {
    if (newText.isBlank()) return emptyList()
    val parsed = parseTextToSegments(newText)
    if (originalSegments.isEmpty()) return parsed

    val hasTimestamps = parsed.any { it.startMs > 0L }
    if (hasTimestamps) return parsed

    val updated = mutableListOf<TranscriptSegment>()
    val origCount = originalSegments.size
    val parsedCount = parsed.size

    for (i in parsed.indices) {
        val p = parsed[i]
        val origIdx = ((i.toDouble() / maxOf(1, parsedCount)) * origCount).toInt().coerceIn(0, origCount - 1)
        val orig = originalSegments[origIdx]
        val speaker = p.speaker.ifBlank { orig.speaker }
        val startMs = if (i == 0) originalSegments.first().startMs else orig.startMs
        val nextOrig = originalSegments.getOrNull(origIdx + 1)
        val endMs = nextOrig?.startMs ?: orig.endMs

        updated.add(
            orig.copy(
                startMs = startMs,
                endMs = maxOf(startMs + 1000L, endMs),
                text = p.text,
                speaker = speaker,
                startsParagraph = true
            )
        )
    }
    return updated
}

fun clockMMSS(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val totalSeconds = t / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun clock(ms: Long, decimal: Char): String {
    val t = ms.coerceAtLeast(0)
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d%c%03d",
        t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, decimal, t % 1000
    )
}

private fun lrcClock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%02d", t / 60_000, t / 1000 % 60, t % 1000 / 10)
}
