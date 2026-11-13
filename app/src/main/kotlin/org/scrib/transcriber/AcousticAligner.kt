package org.scrib.transcriber

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Acoustic Speech Alignment and Pause Snapping Engine.
 *
 * Replaces coarse character-ratio interpolation with sample-accurate energy profile analysis.
 * Uses 25ms Short-Time Energy (STE) frames at 10ms hops (100 fps) to:
 * 1. Trim leading and trailing Whisper segment padding to actual speech onset and offset.
 * 2. Snap inter-sentence cut-offs to the acoustic silence/pause valleys between speakers.
 * 3. Provide word-level boundary alignment and split multi-speaker segments at precise word pauses.
 */
object AcousticAligner {

    data class TimedSpan(val startMs: Long, val endMs: Long)
    data class TimedWord(val word: String, val startMs: Long, val endMs: Long)
    data class NeuralToken(val text: String, val startMs: Long, val endMs: Long)

    const val FRAME_LEN_MS = 25
    const val FRAME_SHIFT_MS = 10

    /**
     * Assembles subword BPE/SentencePiece neural tokens into canonical TimedWords while preserving
     * word boundaries (leading space, SentencePiece U+2581, newline, or standalone whitespace tokens).
     */
    fun assembleSubwordTokensToWords(
        tokens: List<NeuralToken>,
        replace: (String) -> String = { it }
    ): List<TimedWord> {
        if (tokens.isEmpty()) return emptyList()

        val words = mutableListOf<TimedWord>()
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

            val startsNewWord = pendingWordBreak ||
                rawText.startsWith(" ") ||
                rawText.startsWith("\u2581") ||
                rawText.startsWith("\n") ||
                currentWord.isEmpty()

            if (startsNewWord && currentWord.isNotEmpty()) {
                val fullWord = replace(currentWord.toString()).trim()
                if (fullWord.isNotEmpty()) {
                    words.add(TimedWord(fullWord, wordStartMs, max(wordStartMs, wordEndMs)))
                }
                currentWord.clear()
                wordStartMs = -1L
            }
            pendingWordBreak = false

            val cleanTok = rawText.removePrefix("\u2581").trimStart()
            if (cleanTok.isEmpty()) continue

            if (wordStartMs < 0L) {
                wordStartMs = tok.startMs
            }
            wordEndMs = max(wordEndMs, tok.endMs)
            currentWord.append(cleanTok)
        }

        if (currentWord.isNotEmpty()) {
            val fullWord = replace(currentWord.toString()).trim()
            if (fullWord.isNotEmpty()) {
                words.add(TimedWord(fullWord, wordStartMs, max(wordStartMs, wordEndMs)))
            }
        }

        return words
    }

    /**
     * Computes smoothed frame energy profile (100 frames/sec at 10ms shift).
     */
    fun computeEnergyProfile(samples: FloatArray, sampleRate: Int = 16000): FloatArray {
        val frameLen = (FRAME_LEN_MS * sampleRate) / 1000
        val frameShift = (FRAME_SHIFT_MS * sampleRate) / 1000
        val numFrames = max(0, (samples.size - frameLen) / frameShift + 1)
        if (numFrames == 0) return FloatArray(0)

        val rawEnergy = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val start = f * frameShift
            var sumSq = 0.0f
            for (i in 0 until frameLen) {
                val s = samples[start + i]
                sumSq += s * s
            }
            rawEnergy[f] = sumSq / frameLen
        }

        // 3-frame triangular smoothing (0.25, 0.5, 0.25) to smooth vowel micro-ripples
        val smoothed = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val prev = if (f > 0) rawEnergy[f - 1] else rawEnergy[f]
            val curr = rawEnergy[f]
            val next = if (f + 1 < numFrames) rawEnergy[f + 1] else rawEnergy[f]
            smoothed[f] = 0.25f * prev + 0.5f * curr + 0.25f * next
        }
        return smoothed
    }

    /**
     * Computes smoothed frame energy profile directly from DecodedAudio chunks in blocks.
     */
    fun computeEnergyProfile(audio: DecodedAudio, sampleRate: Int = 16000): FloatArray {
        val frameLen = (FRAME_LEN_MS * sampleRate) / 1000
        val frameShift = (FRAME_SHIFT_MS * sampleRate) / 1000
        val totalSamples = audio.sampleCount
        val numFrames = max(0, (totalSamples - frameLen) / frameShift + 1)
        if (numFrames == 0) return FloatArray(0)

        val rawEnergy = FloatArray(numFrames)
        val frameBuf = FloatArray(frameLen)
        for (f in 0 until numFrames) {
            val sampleStart = f * frameShift
            val readCount = audio.readSlice(sampleStart, frameLen, frameBuf)
            var sumSq = 0.0f
            for (i in 0 until readCount) {
                val s = frameBuf[i]
                sumSq += s * s
            }
            rawEnergy[f] = if (readCount > 0) sumSq / readCount else 0.0f
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

    /**
     * Trims leading and trailing silence/padding to tightly wrap around spoken audio.
     */
    fun trimSpeechBounds(
        energy: FloatArray,
        startMs: Long,
        endMs: Long,
        frameShiftMs: Int = FRAME_SHIFT_MS
    ): TimedSpan {
        if (energy.isEmpty() || endMs <= startMs) return TimedSpan(startMs, endMs)

        val startFrame = (startMs / frameShiftMs).toInt().coerceIn(0, energy.size)
        val endFrame = (endMs / frameShiftMs).toInt().coerceIn(startFrame, energy.size)
        if (endFrame - startFrame < 5) return TimedSpan(startMs, endMs)

        var minE = Float.MAX_VALUE
        var maxE = 0.0f
        for (f in startFrame until endFrame) {
            val e = energy[f]
            if (e < minE) minE = e
            if (e > maxE) maxE = e
        }

        val dynamicRange = maxE - minE
        if (dynamicRange < 1e-6f) return TimedSpan(startMs, endMs)

        val speechThreshold = minE + 0.08f * dynamicRange

        // Search onset from startFrame forward (up to 1.8 seconds)
        val maxOnsetSearch = min(endFrame, startFrame + 180)
        var onsetFrame = startFrame
        for (f in startFrame until maxOnsetSearch) {
            if (energy[f] >= speechThreshold) {
                onsetFrame = max(startFrame, f - 4) // 40ms acoustic lead-in
                break
            }
        }

        // Search offset from endFrame backward (up to 1.8 seconds)
        val minOffsetSearch = max(onsetFrame, endFrame - 180)
        var offsetFrame = endFrame
        for (f in endFrame - 1 downTo minOffsetSearch) {
            if (energy[f] >= speechThreshold) {
                offsetFrame = min(endFrame, f + 4) // 40ms acoustic ring-out
                break
            }
        }

        val trimmedStart = onsetFrame.toLong() * frameShiftMs
        val trimmedEnd = offsetFrame.toLong() * frameShiftMs
        return if (trimmedEnd > trimmedStart) {
            TimedSpan(trimmedStart, trimmedEnd)
        } else {
            TimedSpan(startMs, endMs)
        }
    }

    /**
     * Snaps an estimated boundary to the acoustic pause (lowest energy trough) within searchWindowMs.
     */
    fun snapToAcousticPause(
        energy: FloatArray,
        nominalMs: Long,
        minBoundMs: Long,
        maxBoundMs: Long,
        searchWindowMs: Long = 1500L,
        frameShiftMs: Int = FRAME_SHIFT_MS
    ): Long {
        if (energy.isEmpty()) return nominalMs

        val searchStartMs = max(minBoundMs, nominalMs - searchWindowMs)
        val searchEndMs = min(maxBoundMs, nominalMs + searchWindowMs)
        val fStart = (searchStartMs / frameShiftMs).toInt().coerceIn(0, energy.size)
        val fEnd = (searchEndMs / frameShiftMs).toInt().coerceIn(fStart, energy.size)
        if (fEnd <= fStart) return nominalMs

        val nominalFrame = (nominalMs / frameShiftMs).toInt().coerceIn(fStart, fEnd)

        var minScore = Float.MAX_VALUE
        var bestFrame = nominalFrame

        var maxEnergyInWindow = 0.0f
        for (f in fStart until fEnd) {
            if (energy[f] > maxEnergyInWindow) maxEnergyInWindow = energy[f]
        }

        for (f in fStart until fEnd) {
            val e = energy[f]
            val distRatio = abs(f - nominalFrame).toFloat() / max(1, fEnd - fStart)
            // Penalty for distance from nominal (0.15 factor balances text proportion vs acoustic trough)
            val score = e + 0.15f * maxEnergyInWindow * distRatio
            if (score < minScore) {
                minScore = score
                bestFrame = f
            }
        }

        return bestFrame.toLong() * frameShiftMs
    }

    /**
     * Aligns sentences within a coarse segment to exact acoustic onset, offset, and pause boundaries.
     */
    fun alignSentences(
        rawSentences: List<String>,
        startMs: Long,
        endMs: Long,
        samples: FloatArray,
        sampleRate: Int = 16000,
        speaker: String = "",
        words: List<TimedWord> = emptyList()
    ): List<TimedSentence> {
        val energy = if (samples.isNotEmpty()) computeEnergyProfile(samples, sampleRate) else FloatArray(0)
        return alignSentencesWithEnergy(rawSentences, startMs, endMs, energy, speaker, words)
    }

    fun alignSentencesWithEnergy(
        rawSentences: List<String>,
        startMs: Long,
        endMs: Long,
        energy: FloatArray,
        speaker: String = "",
        words: List<TimedWord> = emptyList()
    ): List<TimedSentence> {
        val filtered = rawSentences.map { it.trim() }.filter { it.isNotBlank() }
        if (filtered.isEmpty()) return emptyList()

        if (words.isNotEmpty()) {
            if (filtered.size == 1) {
                val baseSpan = if (energy.isNotEmpty()) trimSpeechBounds(energy, startMs, endMs) else TimedSpan(startMs, endMs)
                val sStart = min(baseSpan.startMs, words.first().startMs)
                val sEnd = max(baseSpan.endMs, words.last().endMs)
                return listOf(
                    TimedSentence(
                        startMs = sStart,
                        endMs = max(sStart, sEnd),
                        text = filtered[0],
                        speaker = speaker,
                        words = words
                    )
                )
            }

            val partitioned = partitionNeuralWordsAcrossSentences(filtered, words)
            val result = ArrayList<TimedSentence>(filtered.size)
            var fallbackCursorMs = min(startMs, words.first().startMs)
            val maxBoundMs = max(endMs, words.last().endMs)

            for (i in filtered.indices) {
                val sWords = partitioned[i]
                if (sWords.isNotEmpty()) {
                    val sStart = sWords.first().startMs
                    val sEnd = max(sStart, sWords.last().endMs)
                    result.add(
                        TimedSentence(
                            startMs = sStart,
                            endMs = sEnd,
                            text = filtered[i],
                            speaker = speaker,
                            words = sWords
                        )
                    )
                    fallbackCursorMs = max(fallbackCursorMs, sEnd)
                } else {
                    val sStart = fallbackCursorMs
                    val nextAnchor = (i + 1 until filtered.size)
                        .firstOrNull { partitioned[it].isNotEmpty() }
                        ?.let { partitioned[it].first().startMs }
                        ?: maxBoundMs
                    val sEnd = max(sStart + 50L, min(maxBoundMs, nextAnchor))
                    val fallbackWords = if (energy.isNotEmpty()) {
                        alignWords(filtered[i], sStart, sEnd, energy)
                    } else {
                        alignWords(filtered[i], sStart, sEnd, FloatArray(0))
                    }
                    result.add(
                        TimedSentence(
                            startMs = sStart,
                            endMs = sEnd,
                            text = filtered[i],
                            speaker = speaker,
                            words = fallbackWords
                        )
                    )
                    fallbackCursorMs = sEnd
                }
            }
            return result
        }

        val allWords = if (energy.isNotEmpty()) alignWords(filtered.joinToString(" "), startMs, endMs, energy) else emptyList()

        if (energy.isEmpty()) {
            val duration = maxOf(1L, endMs - startMs)
            val totalChars = maxOf(1, filtered.sumOf { it.length })
            var currentOffsetMs = startMs
            return filtered.map { sText ->
                val sentenceLen = sText.length
                val sentenceDuration = (duration.toDouble() * sentenceLen / totalChars).toLong()
                val sEndMs = minOf(endMs, currentOffsetMs + sentenceDuration)
                val sWords = allWords.filter { w -> w.startMs >= currentOffsetMs - 100L && w.endMs <= sEndMs + 100L }
                val s = TimedSentence(startMs = currentOffsetMs, endMs = sEndMs, text = sText, speaker = speaker, words = sWords)
                currentOffsetMs = sEndMs
                s
            }
        }

        val trimmedSpan = trimSpeechBounds(energy, startMs, endMs)

        if (filtered.size == 1) {
            val sWords = if (allWords.isNotEmpty()) allWords else alignWords(filtered[0], trimmedSpan.startMs, trimmedSpan.endMs, energy)
            return listOf(
                TimedSentence(
                    startMs = trimmedSpan.startMs,
                    endMs = trimmedSpan.endMs,
                    text = filtered[0],
                    speaker = speaker,
                    words = sWords
                )
            )
        }

        val totalChars = maxOf(1, filtered.sumOf { it.length })
        val activeDuration = maxOf(100L, trimmedSpan.endMs - trimmedSpan.startMs)
        val boundaries = mutableListOf<Long>()
        boundaries.add(trimmedSpan.startMs)

        var accumulatedChars = 0
        for (i in 0 until filtered.size - 1) {
            accumulatedChars += filtered[i].length
            val nominalMs = trimmedSpan.startMs + (activeDuration.toDouble() * accumulatedChars / totalChars).toLong()
            val minBound = boundaries.last() + 200L
            val remainingChars = totalChars - accumulatedChars
            val maxBound = trimmedSpan.endMs - (activeDuration.toDouble() * remainingChars / totalChars).toLong() + 1000L

            val pauseMs = snapToAcousticPause(
                energy = energy,
                nominalMs = nominalMs,
                minBoundMs = minBound,
                maxBoundMs = max(minBound, maxBound),
                searchWindowMs = min(1500L, activeDuration / filtered.size)
            )
            boundaries.add(pauseMs)
        }
        boundaries.add(trimmedSpan.endMs)

        val result = mutableListOf<TimedSentence>()
        for (i in filtered.indices) {
            val sStart = boundaries[i]
            val sEnd = max(sStart + 100L, boundaries[i + 1])
            val sWords = alignWords(filtered[i], sStart, sEnd, energy)
            result.add(
                TimedSentence(
                    startMs = sStart,
                    endMs = sEnd,
                    text = filtered[i],
                    speaker = speaker,
                    words = sWords
                )
            )
        }
        return result
    }

    /**
     * Partitions neural TimedWords sequentially and exhaustively across sentences so zero words
     * are dropped or duplicated at sentence boundaries even when speaking rates vary across turns.
     */
    internal fun partitionNeuralWordsAcrossSentences(
        sentences: List<String>,
        words: List<TimedWord>
    ): List<List<TimedWord>> {
        val result = List(sentences.size) { mutableListOf<TimedWord>() }
        if (sentences.isEmpty() || words.isEmpty()) return result
        if (sentences.size == 1) {
            result[0].addAll(words)
            return result
        }

        val whitespaceRegex = Regex("""\s+""")
        val sentenceWordCounts = sentences.map { s ->
            s.trim().split(whitespaceRegex).count { it.isNotBlank() }
        }
        if (sentenceWordCounts.sum() == words.size) {
            var cursor = 0
            for (i in sentences.indices) {
                val count = sentenceWordCounts[i]
                for (k in 0 until count) {
                    result[i].add(words[cursor++])
                }
            }
            return result
        }

        fun nonSpaceLen(s: String): Int {
            var c = 0
            for (ch in s) if (!ch.isWhitespace()) c++
            return maxOf(1, c)
        }

        val totalSentenceChars = sentences.sumOf { nonSpaceLen(it) }.coerceAtLeast(1)
        val totalWordChars = words.sumOf { nonSpaceLen(it.word) }.coerceAtLeast(1)

        var wordCursor = 0
        var accSentenceChars = 0
        var accWordChars = 0

        for (i in sentences.indices) {
            accSentenceChars += nonSpaceLen(sentences[i])
            if (i == sentences.lastIndex) {
                while (wordCursor < words.size) {
                    result[i].add(words[wordCursor++])
                }
                break
            }

            val targetWordChars = (accSentenceChars.toDouble() * totalWordChars / totalSentenceChars)
            while (wordCursor < words.size) {
                val wLen = nonSpaceLen(words[wordCursor].word)
                val distWithout = abs(accWordChars - targetWordChars)
                val distWith = abs((accWordChars + wLen) - targetWordChars)
                if (distWith <= distWithout || (result[i].isEmpty() && wordCursor < words.size - (sentences.lastIndex - i))) {
                    result[i].add(words[wordCursor])
                    accWordChars += wLen
                    wordCursor++
                } else {
                    break
                }
            }
        }
        return result
    }

    /**
     * Aligns words within a sentence to exact neural word timestamps or acoustic energy troughs.
     * Prefers genuine neural word timestamps from Whisper when available.
     */
    fun alignWords(
        text: String,
        startMs: Long,
        endMs: Long,
        energy: FloatArray,
        neuralWords: List<TimedWord> = emptyList()
    ): List<TimedWord> {
        if (neuralWords.isNotEmpty()) {
            return neuralWords
        }

        val rawWords = text.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return emptyList()
        if (rawWords.size == 1 || energy.isEmpty()) {
            return listOf(TimedWord(rawWords[0], startMs, endMs))
        }

        val totalChars = maxOf(1, rawWords.sumOf { it.length })
        val duration = maxOf(50L, endMs - startMs)
        val wordBounds = mutableListOf<Long>()
        wordBounds.add(startMs)

        var accChars = 0
        for (i in 0 until rawWords.size - 1) {
            accChars += rawWords[i].length
            val nominalMs = startMs + (duration.toDouble() * accChars / totalChars).toLong()
            val minBound = wordBounds.last() + 50L
            val maxBound = endMs - 50L

            val pauseMs = snapToAcousticPause(
                energy = energy,
                nominalMs = nominalMs,
                minBoundMs = minBound,
                maxBoundMs = maxBound,
                searchWindowMs = min(400L, duration / rawWords.size)
            )
            wordBounds.add(pauseMs)
        }
        wordBounds.add(endMs)

        val timedWords = mutableListOf<TimedWord>()
        for (i in rawWords.indices) {
            val wStart = wordBounds[i]
            val wEnd = max(wStart + 20L, wordBounds[i + 1])
            timedWords.add(TimedWord(rawWords[i], wStart, wEnd))
        }
        return timedWords
    }

    /**
     * Splits a coarse segment at a detected speaker transition time, snapping cleanly to the nearest
     * inter-word acoustic pause.
     */
    fun splitSegmentAtSpeakerTransition(
        segment: TranscriptSegment,
        transitionMs: Long,
        speakerBefore: String,
        speakerAfter: String,
        samples: FloatArray,
        sampleRate: Int = 16000
    ): List<TranscriptSegment> {
        val energy = if (samples.isNotEmpty()) computeEnergyProfile(samples, sampleRate) else FloatArray(0)
        return splitSegmentAtSpeakerTransition(segment, transitionMs, speakerBefore, speakerAfter, energy)
    }

    fun splitSegmentAtSpeakerTransition(
        segment: TranscriptSegment,
        transitionMs: Long,
        speakerBefore: String,
        speakerAfter: String,
        energy: FloatArray = FloatArray(0)
    ): List<TranscriptSegment> {
        val timedWords = alignWords(segment.text, segment.startMs, segment.endMs, energy, segment.words)
        if (timedWords.size < 2) {
            return listOf(segment.copy(speaker = speakerBefore))
        }

        // Find the word boundary closest to transitionMs, snapped to energy trough
        var splitIndex = 1
        var minDiff = Long.MAX_VALUE
        for (i in 1 until timedWords.size) {
            val diff = abs(timedWords[i].startMs - transitionMs)
            if (diff < minDiff) {
                minDiff = diff
                splitIndex = i
            }
        }

        // If the candidate right word starts with lowercase and ends with sentence-terminating punctuation
        // while the left word has no punctuation (e.g. "happening" -> "locally."), the terminating word
        // belongs to the preceding speaker's sentence. Shift splitIndex past it so the sentence closes cleanly.
        if (splitIndex < timedWords.size) {
            val currWord = timedWords[splitIndex].word.trim()
            val prevWord = timedWords[splitIndex - 1].word.trim()
            val prevHasPunct = prevWord.endsWith('.') || prevWord.endsWith('?') || prevWord.endsWith('!')
            val currEndsPunct = currWord.endsWith('.') || currWord.endsWith('?') || currWord.endsWith('!')
            val currStartsLower = currWord.isNotEmpty() && currWord[0].isLowerCase()
            if (!prevHasPunct && currEndsPunct && currStartsLower && splitIndex + 1 < timedWords.size) {
                splitIndex++
            }
        }

        val leftWords = timedWords.subList(0, splitIndex)
        val rightWords = timedWords.subList(splitIndex, timedWords.size)

        val leftText = leftWords.joinToString(" ") { it.word }
        val rightText = rightWords.joinToString(" ") { it.word }

        val splitMs = if (energy.isNotEmpty()) {
            snapToAcousticPause(
                energy = energy,
                nominalMs = timedWords[splitIndex].startMs,
                minBoundMs = timedWords[splitIndex - 1].startMs,
                maxBoundMs = timedWords[splitIndex].endMs,
                searchWindowMs = 600L
            )
        } else {
            timedWords[splitIndex].startMs
        }

        val seg1 = TranscriptSegment(
            startMs = segment.startMs,
            endMs = splitMs,
            text = leftText,
            startsParagraph = segment.startsParagraph,
            speaker = speakerBefore,
            words = leftWords
        )
        val seg2 = TranscriptSegment(
            startMs = splitMs,
            endMs = segment.endMs,
            text = rightText,
            startsParagraph = true,
            speaker = speakerAfter,
            words = rightWords
        )
        return listOf(seg1, seg2)
    }

    /**
     * Plans [startSample, endSample) windows of length <= maxWindowSamples, snapping non-final cuts
     * to the lowest-energy Short-Time Energy acoustic pause inside [minWindowSamples, maxWindowSamples]
     * instead of slicing mid-word at rigid 30.000s intervals.
     */
    fun planPauseSnappedWindowRanges(
        totalSamples: Int,
        maxWindowSamples: Int,
        minWindowSamples: Int,
        energy: FloatArray,
        sampleRate: Int = 16000
    ): List<Pair<Int, Int>> {
        if (totalSamples <= 0) return emptyList()
        if (totalSamples <= maxWindowSamples || maxWindowSamples <= 0) {
            return listOf(0 to totalSamples)
        }

        val safeMinWindow = minWindowSamples.coerceIn(1, maxWindowSamples)
        val ranges = mutableListOf<Pair<Int, Int>>()
        var offset = 0

        while (offset < totalSamples) {
            val remaining = totalSamples - offset
            if (remaining <= maxWindowSamples) {
                ranges.add(offset to totalSamples)
                break
            }

            val minEndSample = offset + safeMinWindow
            val maxEndSample = offset + maxWindowSamples
            var cutSample = maxEndSample

            if (energy.isNotEmpty() && maxEndSample > minEndSample) {
                val minBoundMs = minEndSample.toLong() * 1000L / sampleRate
                val maxBoundMs = maxEndSample.toLong() * 1000L / sampleRate
                val nominalMs = (minBoundMs + maxBoundMs) / 2L
                val searchWindowMs = (maxBoundMs - minBoundMs) / 2L + 100L
                val snappedMs = snapToAcousticPause(
                    energy = energy,
                    nominalMs = nominalMs,
                    minBoundMs = minBoundMs,
                    maxBoundMs = maxBoundMs,
                    searchWindowMs = searchWindowMs
                )
                cutSample = ((snappedMs * sampleRate) / 1000L).toInt().coerceIn(minEndSample, maxEndSample)
            }

            ranges.add(offset to cutSample)
            offset = cutSample
        }
        return ranges
    }

    /**
     * Assigns every TimedWord deterministically to the single segment with maximum temporal overlap
     * (falling back to nearest segment distance while preserving monotonic order), eliminating
     * +/-100ms boundary drops and adjacent-segment word duplications.
     */
    fun assignTimedWordsExclusively(
        words: List<TimedWord>,
        segmentBounds: List<Pair<Long, Long>>
    ): List<List<TimedWord>> {
        val result = List(segmentBounds.size) { mutableListOf<TimedWord>() }
        if (words.isEmpty() || segmentBounds.isEmpty()) return result
        if (segmentBounds.size == 1) {
            result[0].addAll(words)
            return result
        }

        var minSegIdx = 0
        for (w in words) {
            val midMs = (w.startMs + w.endMs) / 2L
            var bestIdx = minSegIdx
            var bestOverlap = -1L
            var bestDist = Long.MAX_VALUE

            for (i in minSegIdx until segmentBounds.size) {
                val (sStart, sEnd) = segmentBounds[i]
                val overlap = minOf(w.endMs, sEnd) - maxOf(w.startMs, sStart)
                if (overlap > 0L) {
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        bestIdx = i
                    }
                } else if (bestOverlap <= 0L) {
                    val dist = when {
                        midMs < sStart -> sStart - midMs
                        midMs > sEnd -> midMs - sEnd
                        else -> 0L
                    }
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = i
                    }
                }
            }
            result[bestIdx].add(w)
            minSegIdx = bestIdx
        }
        return result
    }

    /**
     * Reconciles neural word timings against a segment's canonical whitespace-delimited words.
     * Prevents glued BPE/SentencePiece tokens (e.g. missing spaces after punctuation) from propagating
     * downstream while preserving exact neural token timestamps for every word.
     */
    fun reconcileWordsWithSegmentText(
        segmentText: String,
        timedWords: List<TimedWord>,
        segStartMs: Long,
        segEndMs: Long
    ): List<TimedWord> {
        val textWords = segmentText.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (textWords.isEmpty() || timedWords.isEmpty()) return timedWords

        if (textWords.size == timedWords.size) {
            return textWords.mapIndexed { i, tw -> timedWords[i].copy(word = tw) }
        }

        fun nonSpaceLen(s: String): Int {
            var c = 0
            for (ch in s) if (!ch.isWhitespace()) c++
            return maxOf(1, c)
        }

        val tokenStarts = IntArray(timedWords.size)
        val tokenEnds = IntArray(timedWords.size)
        var totalTokChars = 0
        for (i in timedWords.indices) {
            tokenStarts[i] = totalTokChars
            totalTokChars += nonSpaceLen(timedWords[i].word)
            tokenEnds[i] = totalTokChars
        }

        val totalTextChars = textWords.sumOf { nonSpaceLen(it) }.coerceAtLeast(1)
        val out = ArrayList<TimedWord>(textWords.size)
        var textCursor = 0
        var tokHint = 0

        for (tw in textWords) {
            val twLen = nonSpaceLen(tw)
            val qStart = (textCursor.toDouble() * totalTokChars / totalTextChars)
            val qEnd = ((textCursor + twLen).toDouble() * totalTokChars / totalTextChars)

            while (tokHint + 1 < timedWords.size && tokenEnds[tokHint] <= qStart) {
                tokHint++
            }

            var firstTok = tokHint
            var lastTok = tokHint
            for (j in tokHint until timedWords.size) {
                if (tokenStarts[j] < qEnd && tokenEnds[j] > qStart) {
                    if (j < firstTok) firstTok = j
                    lastTok = j
                } else if (tokenStarts[j] >= qEnd) {
                    break
                }
            }

            val tStart = timedWords[firstTok].startMs
            val tEnd = timedWords[lastTok].endMs
            val wordStart: Long
            val wordEnd: Long
            if (firstTok == lastTok && tokenEnds[firstTok] > tokenStarts[firstTok]) {
                val tokSpanChars = (tokenEnds[firstTok] - tokenStarts[firstTok]).toDouble()
                val relStart = ((qStart - tokenStarts[firstTok]) / tokSpanChars).coerceIn(0.0, 1.0)
                val relEnd = ((qEnd - tokenStarts[firstTok]) / tokSpanChars).coerceIn(relStart, 1.0)
                val tokDur = maxOf(0L, tEnd - tStart)
                wordStart = tStart + (tokDur * relStart).toLong()
                wordEnd = maxOf(wordStart, tStart + (tokDur * relEnd).toLong())
            } else {
                wordStart = tStart
                wordEnd = maxOf(tStart, tEnd)
            }

            val clampedStart = wordStart.coerceIn(segStartMs, maxOf(segStartMs, segEndMs))
            val clampedEnd = wordEnd.coerceAtLeast(clampedStart)
            out.add(TimedWord(tw, clampedStart, clampedEnd))
            textCursor += twLen
        }
        return out
    }
}

