package com.deon.pdftoword

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** One styled run of text inside a paragraph. colorHex is RRGGBB or null for default (black). */
data class DocRun(val text: String, val sizePt: Float, val bold: Boolean, val colorHex: String? = null)

/** Document blocks in reading order. */
sealed class DocBlock {
    data class Para(val runs: List<DocRun>, val pitchPt: Float) : DocBlock()
    object PageBreak : DocBlock()
}

private data class RawLine(
    val runs: MutableList<DocRun>,
    val y: Float,
    val page: Int
)

/**
 * Extracts text from a PDF via PDFBox's PDFTextStripper, capturing per-character
 * positions, sizes and bold-ness. Lines are merged by baseline (2pt tolerance),
 * paragraph breaks are detected when the inter-line gap exceeds 1.8x the page's
 * normal line pitch (smallest inter-line gap), and page changes become page breaks.
 *
 * Ordering note: with sortByPosition=true the stripper already emits
 * writeString() calls in reading order, so we never assume a y direction —
 * only |gaps| are measured.
 */
class PdfTextExtractor {

    @Throws(Exception::class)
    fun extract(
        input: InputStream,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<DocBlock> {
        PDDocument.load(input).use { doc ->
            if (doc.isEncrypted) throw SecurityException("PDF is password protected")
            val pageCount = doc.numberOfPages
            if (pageCount == 0) return emptyList()
            // Progress spans both stripper passes: pre-pass pages 1..N,
            // main pass pages N+1..2N.
            val total = pageCount * 2

            val corrector = UnicodeCorrector()

            // Build ML-Karthika code->Unicode map from COS /Differences.
            // (Reliable path for ML-Karthika Type1 fonts with corrupt ToUnicode.)
            try {
                corrector.buildMlKarthikaMap(doc)
            } catch (e: Exception) { /* non-fatal */ }

            // Pre-pass: collect raw ToUnicode per (font, CID) so the corrector
            // can build cross-font consensus for sporadically corrupt
            // ligature mappings (same subset CID order across sibling fonts).
            val fontToRaw = HashMap<PDFont, MutableMap<Int, String>>()
            val preStripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    for (tp in textPositions) {
                        val font = tp.font ?: continue
                        val codes = try { tp.characterCodes } catch (e: Exception) { null }
                        val code = if (codes != null && codes.isNotEmpty()) codes[0] else continue
                        val map = fontToRaw.getOrPut(font) { HashMap() }
                        if (!map.containsKey(code)) {
                            map[code] = try { tp.unicode ?: "" } catch (e: Exception) { "" }
                        }
                    }
                }
            }
            preStripper.sortByPosition = true
            preStripper.suppressDuplicateOverlappingText = false
            for (p in 1..pageCount) {
                preStripper.startPage = p
                preStripper.endPage = p
                preStripper.getText(doc)
                onProgress?.invoke(p, total)
            }
            corrector.buildConsensus(fontToRaw)

            val rawLines = mutableListOf<RawLine>()
            val stripper = object : PDFTextStripper() {
                // Per-glyph fill color, captured live in processTextPosition().
                // The graphics state is current for each glyph there; by the
                // time writeString() runs it has moved on (end-of-page state),
                // so reading it in writeString() always returned stale black.
                val tpColor = java.util.IdentityHashMap<TextPosition, String?>()
                // Per-glyph CTM x-scale, captured live for the same reason:
                // this PDFBox port's fontSizeInPt ignores the CTM (WeasyPrint
                // pre-scales the page by 0.75), so raw sizes come out 1.33x too
                // big. Multiplying by the CTM scale gives the true size.
                val tpScale = java.util.IdentityHashMap<TextPosition, Float>()

                override fun processTextPosition(text: TextPosition) {
                    tpColor[text] = readColorHex()
                    tpScale[text] = readCtmScaleX()
                    super.processTextPosition(text)
                }

                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    if (textPositions.isEmpty()) return
                    val page = currentPageNo

                    // 1. Correct each glyph's unicode via glyph-name ground truth
                    //    (repairs corrupt ToUnicode CMaps).
                    val pairs = textPositions.map { tp ->
                        val font = tp.font
                        val codes = try { tp.characterCodes } catch (e: Exception) { null }
                        val code = if (codes != null && codes.isNotEmpty()) codes[0] else -1
                        val u = if (font != null && code >= 0) {
                            try {
                                corrector.corrected(font, code)
                            } catch (e: Exception) {
                                tp.unicode ?: ""
                            }
                        } else {
                            tp.unicode ?: ""
                        }
                        u to tp
                    }

                    // 2. Reorder visual-order glyphs to logical order
                    //    (pre-base matras + vattus are emitted before their base).
                    val reordered = try {
                        VisualOrderFixer.reorder(pairs)
                    } catch (e: Exception) {
                        pairs
                    }

                    // 3. Build styled runs over the reordered glyphs.
                    //    Color is per-glyph (captured live in
                    //    processTextPosition); runs split when it changes.
                    val runs = mutableListOf<DocRun>()
                    val sb = StringBuilder()
                    var curSize = -1f
                    var curBold = false
                    var curColor: String? = null
                    var started = false
                    for ((u, tp) in reordered) {
                        if (u.isEmpty()) continue
                        val scale = tpScale[tp] ?: 1f
                        val size = tp.fontSizeInPt * scale
                        val bold = tp.font?.name?.contains("bold", ignoreCase = true) == true
                        val colorHex = tpColor[tp]
                        val sizeKey = (size * 2).roundToInt() / 2f
                        if (!started || sizeKey != curSize || bold != curBold || colorHex != curColor) {
                            if (sb.isNotEmpty()) {
                                runs.add(DocRun(sb.toString(), curSize, curBold, curColor))
                                sb.clear()
                            }
                            curSize = sizeKey
                            curBold = bold
                            curColor = colorHex
                            started = true
                        }
                        sb.append(u)
                    }
                    if (sb.isNotEmpty()) runs.add(DocRun(sb.toString(), curSize, curBold, curColor))
                    if (runs.isEmpty()) return
                    val mergedText = runs.joinToString("") { it.text }
                    if (mergedText.isBlank()) return
                    val y = textPositions[0].y
                    val last = rawLines.lastOrNull()
                    // Merge stripper line-splits that share (nearly) one baseline.
                    if (last != null && last.page == page && abs(y - last.y) <= 2f) {
                        last.runs.addAll(runs)
                    } else {
                        rawLines.add(RawLine(runs, y, page))
                    }
                }

                /** Live CTM x-scale (1.0 for normal pages). */
                private fun readCtmScaleX(): Float {
                    return try {
                        val ctm = graphicsState?.currentTransformationMatrix ?: return 1f
                        abs(ctm.scaleX).let { if (it == 0f) 1f else it }
                    } catch (e: Exception) {
                        1f
                    }
                }

                /** Live non-stroking (fill) text color as RRGGBB, or null for default black. */
                private fun readColorHex(): String? {
                    return try {
                        val gs = graphicsState ?: return null
                        val color = gs.nonStrokingColor ?: return null
                        val comps = color.components ?: return null
                        val rgb: List<Float> = when (color.colorSpace) {
                            is com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB ->
                                comps.take(3)
                            is com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray -> {
                                val g = comps.getOrElse(0) { 0f }
                                listOf(g, g, g)
                            }
                            // (No PDDeviceCMYK in this PDFBox port; other spaces
                            // fall through to default.)
                            else -> return null
                        }
                        val r = (rgb.getOrElse(0) { 0f }.coerceIn(0f, 1f) * 255).roundToInt()
                        val g = (rgb.getOrElse(1) { 0f }.coerceIn(0f, 1f) * 255).roundToInt()
                        val b = (rgb.getOrElse(2) { 0f }.coerceIn(0f, 1f) * 255).roundToInt()
                        if (r == 0 && g == 0 && b == 0) return null // black = default
                        "%02X%02X%02X".format(r, g, b)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            stripper.sortByPosition = true
            // LegacyPDFStreamEngine (which PDFTextStripper extends) registers
            // NO color operators in this PDFBox port — rg/g/sc ops are silently
            // ignored and the graphics state stays default black. Register them
            // so processTextPosition() sees the live fill color per glyph.
            stripper.addOperator(SetNonStrokingDeviceRGBColor())
            stripper.addOperator(SetNonStrokingDeviceGrayColor())
            stripper.addOperator(SetNonStrokingColor())
            stripper.addOperator(SetNonStrokingColorN())
            stripper.addOperator(SetNonStrokingColorSpace())
            // The PDF's ToUnicode CMap is corrupt: pre-base matras (െ, േ, ൈ)
            // have empty Unicode, so the default duplicate-overlap suppression
            // wrongly drops them as "duplicate overlapping text". Disable it
            // so every glyph reaches the UnicodeCorrector.
            stripper.suppressDuplicateOverlappingText = false
            for (p in 1..pageCount) {
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(doc)
                onProgress?.invoke(pageCount + p, total)
            }

            return MalayalamPostCorrector.correctBlocks(buildBlocks(rawLines))
        }
    }

    private fun buildBlocks(rawLines: List<RawLine>): List<DocBlock> {
        if (rawLines.isEmpty()) return emptyList()
        // Normal line pitch per page = the SMALLEST inter-line gap (typical body
        // leading). Min beats median here: with few lines the median can be a
        // paragraph gap, which would both wreck w:line spacing and hide breaks.
        // Only |gaps| are measured — direction agnostic.
        val pitchByPage = rawLines.groupBy { it.page }.mapValues { (_, lines) ->
            val gaps = lines.zipWithNext { a, b -> abs(b.y - a.y) }
                .filter { it > 2f }
            if (gaps.isEmpty()) 14f else gaps.min()
        }
        val blocks = mutableListOf<DocBlock>()
        var prev: RawLine? = null
        for (line in rawLines) {
            val p = prev
            if (p != null) {
                if (line.page != p.page) {
                    blocks.add(DocBlock.PageBreak)
                } else {
                    val pitch = pitchByPage[line.page] ?: 14f
                    if (abs(line.y - p.y) > pitch * 1.8f) {
                        // Paragraph break: an empty paragraph for visual separation.
                        blocks.add(DocBlock.Para(listOf(DocRun("", 11f, false)), pitch))
                    }
                }
            }
            val pitch = pitchByPage[line.page] ?: 14f
            blocks.add(DocBlock.Para(line.runs.toList(), pitch))
            prev = line
        }
        return blocks
    }
}
