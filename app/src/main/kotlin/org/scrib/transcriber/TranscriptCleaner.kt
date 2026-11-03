package org.scrib.transcriber

import java.util.regex.Pattern

/**
 * Port of d:\rtvs3\transcript_cleaner.py for mobile Transcriber.
 * 
 * Cleans transcripts by:
 * 1. Collapsing degenerate repeating word/token loops (e.g. single-word stutters and multi-word runaway loops).
 * 2. Scrubbing standard ASR silence hallucination phrases (e.g. "thanks for watching", "please subscribe").
 * 3. Trimming trailing degenerate hallucination tails at the end of recordings.
 * 4. Normalizing repeated acoustic brackets ([music] [music]) and repeated punctuation.
 */
object TranscriptCleaner {

    private val HALLUCINATION_PATTERNS = listOf(
        Pattern.compile("""(?i)\b(?:subtitles\s+by\s+(?:the\s+)?amara\.org(?:\s+community)?|subtitles\s+by\b.*|sous-titres\s+faits\s+par\b.*)"""),
        Pattern.compile("""(?i)\b(?:transcribed\s+by\b.*|transcription\s+by\b.*)"""),
        Pattern.compile("""(?i)\b(?:thank\s+you\s+for\s+watching(?:\s+this\s+video)?(?:\s+and\s+listening)?|thanks\s+for\s+watching(?:\s+this\s+video)?)\b[.!?]?"""),
        Pattern.compile("""(?i)\b(?:please\s+)?(?:like\s+and\s+)?subscribe(?:\s+to\s+(?:our|the|my)\s+channel)?\b[.!?]?"""),
        Pattern.compile("""(?i)\bdon\'?t\s+forget\s+to\s+like\s+and\s+subscribe\b[.!?]?"""),
        Pattern.compile("""(?i)\b(?:see\s+you\s+in\s+the\s+next\s+video|see\s+you\s+next\s+time)\b[.!?]?""")
    )

    private val REPEATED_BRACKET_PATTERN = Pattern.compile(
        """(?i)(\[(?:music|applause|laughter|silence|inaudible|whispering|coughing|screaming|bell|cheering|background\s+music)\]\s*){2,}"""
    )

    private val REPEATED_PAREN_PATTERN = Pattern.compile(
        """(?i)(\((?:music|applause|laughter|silence|inaudible|whispering|coughing|screaming|bell|cheering|background\s+music)\)\s*){2,}"""
    )

    private val REPEATED_MUSIC_NOTE_PATTERN = Pattern.compile("""(?:[♪♫♩♬]\s*){2,}""")

    private val REPEATED_PUNCTUATION_PATTERN = Pattern.compile("""([.?!,;:-]\s*){4,}""")

    /**
     * Collapses degenerate repeating word/token loops in transcription text.
     * Matches desktop collapse_repeating_ngrams in transcript_cleaner.py.
     */
    fun collapseRepeatingNgrams(text: String, maxN: Int = 8, minRepeats: Int = 3): String {
        if (text.isBlank()) return ""
        val cleaned = text.trim()
        val tokens = cleaned.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.size < 4) return cleaned

        var currentTokens = tokens
        val numTokens = currentTokens.size
        val effectiveMaxN = minOf(maxN, numTokens / 2)

        for (n in effectiveMaxN downTo 1) {
            var i = 0
            val newTokens = mutableListOf<String>()
            var changed = false

            while (i < currentTokens.size) {
                if (i + n > currentTokens.size) {
                    newTokens.addAll(currentTokens.subList(i, currentTokens.size))
                    break
                }

                val pattern = currentTokens.subList(i, i + n).map { it.lowercase().trimEnd('.', ',', '!', '?', ';', ':') }

                var repeatCount = 1
                var cursor = i + n
                while (cursor + n <= currentTokens.size) {
                    val candidate = currentTokens.subList(cursor, cursor + n).map { it.lowercase().trimEnd('.', ',', '!', '?', ';', ':') }
                    if (candidate == pattern) {
                        repeatCount++
                        cursor += n
                    } else {
                        break
                    }
                }

                val threshold = if (n <= 2) minRepeats else 2
                if (repeatCount >= threshold) {
                    val keepReps = if (n == 1 && repeatCount > 2 && currentTokens[i].length <= 4) 2 else 1
                    for (k in 0 until (n * keepReps)) {
                        newTokens.add(currentTokens[i + k])
                    }
                    // Preserve any trailing punctuation from the end of the collapsed loop (e.g. "yeah,")
                    val lastRepeatedToken = currentTokens[cursor - 1]
                    val trailingPunct = lastRepeatedToken.takeLastWhile { it in ".,!?;:" }
                    if (trailingPunct.isNotEmpty() && newTokens.isNotEmpty()) {
                        val lastKeptIdx = newTokens.size - 1
                        newTokens[lastKeptIdx] = newTokens[lastKeptIdx].trimEnd('.', ',', '!', '?', ';', ':') + trailingPunct
                    }
                    i = cursor
                    changed = true
                } else {
                    newTokens.add(currentTokens[i])
                    i++
                }
            }

            if (changed) {
                currentTokens = newTokens
            }
        }

        var collapsed = currentTokens.joinToString(" ")
        collapsed = collapsed.replace(Regex("""\s+([.,!?;:])"""), "$1")
        return collapsed.trim()
    }

    /**
     * Strips standard ASR hallucination phrases and repetitive acoustic tags.
     */
    fun stripHallucinationPhrases(text: String): String {
        if (text.isBlank()) return ""
        var res = text.trim()

        res = REPEATED_BRACKET_PATTERN.matcher(res).replaceAll { mr -> mr.group(1).trim() + " " }
        res = REPEATED_PAREN_PATTERN.matcher(res).replaceAll { mr -> mr.group(1).trim() + " " }
        res = REPEATED_MUSIC_NOTE_PATTERN.matcher(res).replaceAll("♪ ")
        res = REPEATED_PUNCTUATION_PATTERN.matcher(res).replaceAll("... ")

        for (pattern in HALLUCINATION_PATTERNS) {
            val matcher = pattern.matcher(res)
            val trimmedForMatch = res.trim(' ', '.', ',', '!', '?', ';', ':')
            if (pattern.matcher(trimmedForMatch).matches()) {
                return ""
            }
            res = matcher.replaceAll("")
        }

        return res.trim()
    }

    /**
     * Inspects trailing segments and removes runaway hallucination loops or end-of-audio tags at the file tail.
     */
    fun trimTrailingDegenerateTail(segments: List<TranscriptSegment>, minTailRepeats: Int = 3): List<TranscriptSegment> {
        if (segments.size < 2) return segments
        val result = segments.toMutableList()

        // 1. Strip trailing segments that are pure hallucination phrases
        while (result.isNotEmpty()) {
            val lastText = result.last().text.trim()
            val scrubbed = stripHallucinationPhrases(lastText)
            if (scrubbed.isBlank()) {
                result.removeAt(result.size - 1)
            } else {
                break
            }
        }

        // 2. Truncate trailing identical degenerate loops (e.g. 3+ identical segments at the end)
        if (result.size >= minTailRepeats) {
            val lastTextNorm = result.last().text.trim().lowercase()
            var repeatCount = 0
            for (idx in result.size - 1 downTo 0) {
                if (result[idx].text.trim().lowercase() == lastTextNorm) {
                    repeatCount++
                } else {
                    break
                }
            }
            if (repeatCount >= minTailRepeats) {
                val toKeep = result.size - repeatCount + 1
                return result.subList(0, toKeep)
            }
        }

        return result
    }

    /**
     * Comprehensive scrubber for transcript segments:
     * 1. Strips hallucination phrases.
     * 2. Collapses repeating word/phrase loops.
     * 3. Trims trailing degenerate silence tails.
     */
    fun scrubSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.isEmpty()) return emptyList()

        val cleaned = mutableListOf<TranscriptSegment>()
        var prevTextNorm = ""

        for (seg in segments) {
            var text = seg.text.trim()
            text = stripHallucinationPhrases(text)
            if (text.isNotBlank()) {
                text = collapseRepeatingNgrams(text)
            }
            if (text.isBlank()) continue

            val textNorm = text.lowercase().trimEnd('.', ',', '!', '?', ';', ':')
            if (textNorm.isNotEmpty() && textNorm == prevTextNorm && cleaned.isNotEmpty()) {
                // Merge duplicate adjacent segment by extending the duration
                val lastIdx = cleaned.size - 1
                cleaned[lastIdx] = cleaned[lastIdx].copy(
                    endMs = maxOf(cleaned[lastIdx].endMs, seg.endMs)
                )
                continue
            }

            cleaned.add(seg.copy(text = text))
            prevTextNorm = textNorm
        }

        return trimTrailingDegenerateTail(cleaned)
    }
}
