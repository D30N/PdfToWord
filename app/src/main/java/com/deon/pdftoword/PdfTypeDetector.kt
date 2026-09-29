package com.deon.pdftoword

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Detects whether a PDF contains real/selectable text or is scanned (image-based).
 *
 * Strategy: extract raw text per page with PDFBox. If the average extractable
 * characters per page is above a threshold, treat as a text PDF. Otherwise
 * treat as scanned and route to the OCR pipeline.
 */
object PdfTypeDetector {

    enum class PdfType {
        TEXT,    // Real selectable text - extract directly, NO OCR
        SCANNED  // Image-based - needs Malayalam OCR pipeline
    }

    data class DetectionResult(
        val type: PdfType,
        val pageCount: Int,
        val avgCharsPerPage: Double,
        val pagesWithText: Int,
        val detectedLanguage: String  // "ml", "en", "ml+en", "unknown"
    )

    /** Minimum average chars/page to consider it a text PDF. */
    private const val TEXT_THRESHOLD = 50.0

    /**
     * Analyze the document and decide TEXT vs SCANNED.
     * @param onProgress callback(0..100) for the detection stage.
     */
    fun detect(
        doc: PDDocument,
        onProgress: ((Int) -> Unit)? = null
    ): DetectionResult {
        val pageCount = doc.numberOfPages
        if (pageCount == 0) {
            return DetectionResult(PdfType.SCANNED, 0, 0.0, 0, "unknown")
        }

        val stripper = PDFTextStripper()
        var totalChars = 0
        var pagesWithText = 0
        var malayalamChars = 0
        var latinChars = 0

        // Sample up to 10 pages (first, middle, last spread) for speed on big PDFs.
        val samplePages = samplePageIndexes(pageCount, 10)

        for ((i, pageIdx) in samplePages.withIndex()) {
            try {
                stripper.startPage = pageIdx + 1
                stripper.endPage = pageIdx + 1
                val text = stripper.getText(doc) ?: ""
                val clean = text.trim()
                totalChars += clean.length
                if (clean.length >= 20) pagesWithText++

                // Language sampling
                for (c in clean.take(2000)) {
                    when (c) {
                        in '\u0D00'..'\u0D7F' -> malayalamChars++
                        in 'a'..'z', in 'A'..'Z' -> latinChars++
                    }
                }
            } catch (_: Exception) {
                // Treat unreadable page as empty
            }
            onProgress?.invoke(((i + 1) * 100 / samplePages.size).coerceIn(0, 100))
        }

        val avgChars = if (samplePages.isNotEmpty()) {
            totalChars.toDouble() / samplePages.size
        } else 0.0

        // Heuristic: if most sampled pages have real text, it's a text PDF.
        val textRatio = pagesWithText.toDouble() / samplePages.size.coerceAtLeast(1)
        val type = if (avgChars >= TEXT_THRESHOLD && textRatio >= 0.5) {
            PdfType.TEXT
        } else {
            PdfType.SCANNED
        }

        val language = when {
            malayalamChars > 0 && latinChars > 0 -> "ml+en"
            malayalamChars > 0 -> "ml"
            latinChars > 0 -> "en"
            else -> "unknown"
        }

        return DetectionResult(type, pageCount, avgChars, pagesWithText, language)
    }

    /** Spread sample pages across the document. */
    private fun samplePageIndexes(pageCount: Int, maxSamples: Int): List<Int> {
        if (pageCount <= maxSamples) return (0 until pageCount).toList()
        val step = pageCount.toDouble() / maxSamples
        return (0 until maxSamples).map { (it * step).toInt().coerceIn(0, pageCount - 1) }
            .distinct()
    }
}
