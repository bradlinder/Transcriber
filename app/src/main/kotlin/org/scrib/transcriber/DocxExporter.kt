package org.scrib.transcriber

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates valid, 100% compliant Microsoft Word OpenXML (.docx) files.
 * Compatible with Google Docs, Microsoft Word, LibreOffice, and Android file viewers.
 *
 * Supports RTVS3-aligned distinct speaker color formatting for professional transcription exports.
 */
object DocxExporter {

    fun exportToDocx(
        destFile: File,
        title: String,
        segments: List<TranscriptSegment>,
        wordThreshold: Int = 70,
        showTimestamps: Boolean = true,
        showSpeakers: Boolean = true,
        colorSpeakers: Boolean = false
    ) {
        val turnBlocks = if (segments.isNotEmpty()) segments.toTurnBlocks(wordThreshold) else emptyList()
        val colorAssigner = if (colorSpeakers) SpeakerColorAssigner() else null

        val docXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">\n")
            append("  <w:body>\n")

            // Title Header
            append("    <w:p>\n")
            append("      <w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>\n")
            append("      <w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr><w:t>")
            append(escapeXml(title))
            append("</w:t></w:r>\n")
            append("    </w:p>\n")

            if (turnBlocks.isEmpty()) {
                val formattedText = segments.paragraphsWithTimestamps(wordThreshold, showTimestamps, showSpeakers)
                val paragraphs = formattedText.split("\n\n").filter { it.isNotBlank() }
                for (pText in paragraphs) {
                    append("    <w:p>\n")
                    append("      <w:pPr><w:spacing w:after=\"200\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>\n")
                    append("      <w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
                    append(escapeXml(pText))
                    append("</w:t></w:r>\n")
                    append("    </w:p>\n")
                }
            } else {
                for (block in turnBlocks) {
                    val pText = block.text.trim()
                    if (pText.isBlank() && block.speaker.isBlank()) continue

                    append("    <w:p>\n")
                    append("      <w:pPr><w:spacing w:after=\"200\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>\n")

                    // 1. Timestamp run (muted gray #787878 matching RTVS3 RGBColor(120, 120, 120))
                    if (showTimestamps) {
                        append("      <w:r><w:rPr><w:color w:val=\"787878\"/><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
                        append("[${escapeXml(clockMMSS(block.startMs))}] ")
                        append("</w:t></w:r>\n")
                    }

                    // 2. Speaker label run (bold + optional RTVS3 light-mode hex color)
                    val showSpeakerLabel = showSpeakers && block.showSpeakerLabel && block.speaker.isNotBlank()
                    if (showSpeakerLabel) {
                        val spk = block.speaker.trim()
                        val colorAttr = if (colorSpeakers && colorAssigner != null) {
                            val hex = colorAssigner.getHex(spk, isDark = false).removePrefix("#")
                            "<w:color w:val=\"$hex\"/>"
                        } else ""
                        append("      <w:r><w:rPr><w:b/>${colorAttr}<w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
                        append(escapeXml(spk) + ": ")
                        append("</w:t></w:r>\n")
                    }

                    // 3. Body text run
                    if (pText.isNotBlank()) {
                        append("      <w:r><w:rPr><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">")
                        append(escapeXml(pText))
                        append("</w:t></w:r>\n")
                    }

                    append("    </w:p>\n")
                }
            }

            append("    <w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>\n")
            append("  </w:body>\n")
            append("</w:document>")
        }

        val contentTypesXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                "  <Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>\n" +
                "</Types>"

        val relsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>\n" +
                "</Relationships>"

        val docRelsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "</Relationships>"

        if (destFile.exists()) {
            destFile.delete()
        }

        FileOutputStream(destFile).use { fos ->
            BufferedOutputStream(fos).use { bos ->
                ZipOutputStream(bos).use { zipOut ->
                    zipOut.setMethod(ZipOutputStream.DEFLATED)

                    // 1. [Content_Types].xml
                    zipOut.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zipOut.write(contentTypesXml.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    // 2. _rels/.rels
                    zipOut.putNextEntry(ZipEntry("_rels/.rels"))
                    zipOut.write(relsXml.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    // 3. word/_rels/document.xml.rels
                    zipOut.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                    zipOut.write(docRelsXml.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    // 4. word/document.xml
                    zipOut.putNextEntry(ZipEntry("word/document.xml"))
                    zipOut.write(docXml.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    zipOut.finish()
                    zipOut.flush()
                }
            }
        }
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
