package org.scrib.transcriber

import androidx.compose.ui.graphics.Color
import java.util.zip.CRC32

/**
 * Accessible adaptive speaker color palette ported from Radio & TV Story Segmenter (RTVS3).
 *
 * Provides a transcription-optimized 8-color paired palette designed for maximum legibility
 * across both light/print media (DOCX, PDF, Light Mode UI) and dark environments (Dark Mode UI).
 */
data class SpeakerColor(
    val name: String,
    val lightHex: String,
    val darkHex: String,
    val highContrastHex: String,
    val lightColor: Color,
    val darkColor: Color
)

object SpeakerPalette {

    val PALETTE: List<SpeakerColor> = listOf(
        SpeakerColor("Sapphire Blue", "#1A56DB", "#60A5FA", "#00FFFF", Color(0xFF1A56DB), Color(0xFF60A5FA)),
        SpeakerColor("Rose Crimson",  "#BE123C", "#FB7185", "#FF69B4", Color(0xFFBE123C), Color(0xFFFB7185)),
        SpeakerColor("Emerald Green", "#047857", "#34D399", "#00FF00", Color(0xFF047857), Color(0xFF34D399)),
        SpeakerColor("Royal Violet",  "#6D28D9", "#A78BFA", "#E879F9", Color(0xFF6D28D9), Color(0xFFA78BFA)),
        SpeakerColor("Amber Ochre",   "#B45309", "#FBBF24", "#FFFF00", Color(0xFFB45309), Color(0xFFFBBF24)),
        SpeakerColor("Deep Teal",     "#0E7490", "#22D3EE", "#87CEFA", Color(0xFF0E7490), Color(0xFF22D3EE)),
        SpeakerColor("Berry Magenta", "#A21CAF", "#E879F9", "#FF00FF", Color(0xFFA21CAF), Color(0xFFE879F9)),
        SpeakerColor("Slate Indigo",  "#4338CA", "#818CF8", "#818CF8", Color(0xFF4338CA), Color(0xFF818CF8))
    )

    private val NUMERIC_PATTERN = Regex("""(?:speaker[_\s]*|spk[_\s]*|s)(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Derive a deterministic 0..7 index from an explicit index, speaker label, or hash.
     */
    fun getSpeakerIndex(speaker: String, explicitIndex: Int? = null): Int {
        if (explicitIndex != null && explicitIndex >= 0) {
            return explicitIndex % PALETTE.size
        }

        val clean = speaker.trim()
        if (clean.isEmpty()) return 0

        // Match numeric patterns: SPEAKER_01, Speaker 2, S03, 1, etc.
        val match = NUMERIC_PATTERN.find(clean)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null) {
                return num % PALETTE.size
            }
        }

        if (clean.all { it.isDigit() }) {
            val num = clean.toIntOrNull()
            if (num != null) {
                return num % PALETTE.size
            }
        }

        val crc = CRC32()
        crc.update(clean.lowercase().toByteArray(Charsets.UTF_8))
        return (crc.value % PALETTE.size).toInt()
    }

    fun getSpeakerColor(speaker: String, explicitIndex: Int? = null): SpeakerColor {
        val idx = getSpeakerIndex(speaker, explicitIndex)
        return PALETTE[idx]
    }
}

/**
 * Maintains sequential appearance-order color assignments for a document or transcript screen.
 * Matches RTVS3 SpeakerColorAssigner behavior.
 */
class SpeakerColorAssigner {
    private val assigned = mutableMapOf<String, Int>()
    private var nextIdx = 0

    fun getIndex(speaker: String): Int {
        val clean = speaker.trim()
        if (clean.isEmpty()) return 0
        return assigned.getOrPut(clean) {
            (nextIdx++ % SpeakerPalette.PALETTE.size)
        }
    }

    fun getColor(speaker: String): SpeakerColor {
        val idx = getIndex(speaker)
        return SpeakerPalette.PALETTE[idx]
    }

    fun getComposeColor(speaker: String, isDark: Boolean): Color {
        val sc = getColor(speaker)
        return if (isDark) sc.darkColor else sc.lightColor
    }

    fun getHex(speaker: String, isDark: Boolean = false): String {
        val sc = getColor(speaker)
        return if (isDark) sc.darkHex else sc.lightHex
    }
}
