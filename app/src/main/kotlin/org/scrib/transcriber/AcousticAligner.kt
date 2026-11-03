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

    const val FRAME_LEN_MS = 25
    const val FRAME_SHIFT_MS = 10

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

        val allWords = if (words.isNotEmpty()) words else if (energy.isNotEmpty()) alignWords(rawSentences.joinToString(" "), startMs, endMs, energy) else emptyList()

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
            val sWords = allWords.filter { w -> w.startMs >= sStart - 100L && w.endMs <= sEnd + 100L }
            val finalWords = if (sWords.isNotEmpty()) sWords else alignWords(filtered[i], sStart, sEnd, energy)
            result.add(
                TimedSentence(
                    startMs = sStart,
                    endMs = sEnd,
                    text = filtered[i],
                    speaker = speaker,
                    words = finalWords
                )
            )
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
}

