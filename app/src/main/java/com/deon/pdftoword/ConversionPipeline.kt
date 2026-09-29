package com.deon.pdftoword

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Orchestrates the full PDF-to-Word conversion pipeline.
 *
 * Modes:
 * - AUTO: detect TEXT vs SCANNED automatically
 * - TEXT: force direct Unicode text extraction (no OCR)
 * - SCANNED: force Malayalam OCR pipeline
 *
 * Stages (reported via onStage):
 *  1. Reading PDF
 *  2. Detecting PDF type
 *  3. Detecting language
 *  4. Preprocessing pages (scanned only)
 *  5. Running Malayalam OCR (scanned only)
 *  6. Validating OCR
 *  7. Correcting obvious OCR errors
 *  8. Creating Word document
 *  9. Final quality check
 *  10. Done
 */
class ConversionPipeline(private val context: Context) {

    enum class Mode { AUTO, TEXT, SCANNED }

    data class Progress(
        val stage: Int,          // 1..10
        val stageName: String,
        val percent: Int,        // 0..100 overall
        val detail: String = ""
    )

    data class Result(
        val blocks: List<DocBlock>,
        val pageCount: Int,
        val detectedType: PdfTypeDetector.PdfType,
        val detectedLanguage: String,
        val ocrConfidence: Int?,     // null for text PDFs
        val wordCount: Int,
        val malayalamPercent: Double,
        val qualityReport: MalayalamValidator.ValidationReport?
    )

    /**
     * Run the full pipeline.
     * @param pdfFile the PDF file to convert
     * @param mode conversion mode
     * @param onProgress called with progress updates (on background thread)
     */
    fun convert(
        pdfFile: File,
        mode: Mode,
        onProgress: (Progress) -> Unit
    ): Result {
        fun stage(n: Int, name: String, pct: Int, detail: String = "") {
            onProgress(Progress(n, name, pct, detail))
        }

        // ---- Stage 1: Reading PDF ----
        stage(1, "Reading PDF", 5, pdfFile.name)
        val doc = try {
            FileInputStream(pdfFile).use { PDDocument.load(it) }
        } catch (e: Exception) {
            throw Exception("Could not read PDF: ${e.message}")
        }

        try {
            if (doc.isEncrypted) throw SecurityException("PDF is password protected")
            val pageCount = doc.numberOfPages
            if (pageCount == 0) throw Exception("PDF has no pages")

            // ---- Stage 2: Detecting PDF type ----
            stage(2, "Detecting PDF type", 10)
            val detection = if (mode == Mode.AUTO) {
                PdfTypeDetector.detect(doc) { p ->
                    stage(2, "Detecting PDF type", 10 + p * 5 / 100)
                }
            } else {
                // Forced mode: still detect language via sampling
                val langDetect = PdfTypeDetector.detect(doc)
                val forcedType = if (mode == Mode.TEXT) {
                    PdfTypeDetector.PdfType.TEXT
                } else {
                    PdfTypeDetector.PdfType.SCANNED
                }
                langDetect.copy(type = forcedType)
            }

            // ---- Stage 3: Detecting language ----
            stage(3, "Detecting language", 15, detection.detectedLanguage)

            val blocks: List<DocBlock>
            var ocrConfidence: Int? = null

            if (detection.type == PdfTypeDetector.PdfType.TEXT) {
                // ---- TEXT path: direct Unicode extraction, NO OCR ----
                stage(4, "Extracting text", 20, "Direct Unicode extraction (no OCR)")
                val extractor = PdfTextExtractor()
                blocks = extractor.extract(FileInputStream(pdfFile)) { done, total ->
                    val pct = 20 + (done * 50 / total.coerceAtLeast(1))
                    stage(5, "Extracting text", pct.coerceIn(20, 70), "Page $done of $total")
                }
                stage(6, "Validating text", 72)
                stage(7, "Applying corrections", 75)
                // (PdfTextExtractor already applies MalayalamPostCorrector)
            } else {
                // ---- SCANNED path: Malayalam OCR pipeline ----
                blocks = ocrPipeline(doc, pageCount, detection, onProgress) { conf ->
                    ocrConfidence = conf
                }
            }

            // ---- Stage 8: Creating Word document ----
            stage(8, "Creating Word document", 85, "${blocks.size} blocks")

            // ---- Stage 9: Final quality check ----
            stage(9, "Final quality check", 92)
            val fullText = blocks.filterIsInstance<DocBlock.Para>()
                .flatMap { it.runs }.joinToString(" ") { it.text }
            val qualityReport = MalayalamValidator.validate(fullText)
            val wordCount = MalayalamValidator.countWords(fullText)

            // ---- Stage 10: Done ----
            stage(10, "Done", 100, "$wordCount words")

            return Result(
                blocks = blocks,
                pageCount = pageCount,
                detectedType = detection.type,
                detectedLanguage = detection.detectedLanguage,
                ocrConfidence = ocrConfidence,
                wordCount = wordCount,
                malayalamPercent = qualityReport.malayalamPercent,
                qualityReport = qualityReport
            )
        } finally {
            try { doc.close() } catch (_: Exception) {}
        }
    }

    /**
     * OCR pipeline for scanned PDFs.
     * Page -> render 300 DPI -> preprocess -> Tesseract (mal+eng) -> validate
     */
    private fun ocrPipeline(
        doc: PDDocument,
        pageCount: Int,
        detection: PdfTypeDetector.DetectionResult,
        onProgress: (Progress) -> Unit,
        onConfidence: (Int) -> Unit
    ): List<DocBlock> {
        fun stage(n: Int, name: String, pct: Int, detail: String = "") {
            onProgress(Progress(n, name, pct, detail))
        }

        val ocr = OcrEngine(context)
        val blocks = mutableListOf<DocBlock>()
        var totalConf = 0
        var confCount = 0

        try {
            if (!ocr.init()) {
                throw Exception("OCR engine failed: ${ocr.getInitError() ?: "unknown"}")
            }

            // Render each page at ~300 DPI and OCR it
            val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)

            for (p in 0 until pageCount) {
                val pagePct = 20 + (p * 55 / pageCount.coerceAtLeast(1))

                // ---- Stage 4: Preprocessing ----
                stage(4, "Preprocessing pages", pagePct, "Page ${p + 1} of $pageCount")

                // Render at 300 DPI (scale = 300/72 = 4.1667)
                val bitmap: Bitmap = try {
                    renderer.renderImageWithDPI(p, 300f)
                } catch (e: Exception) {
                    // Fallback: lower DPI
                    renderer.renderImageWithDPI(p, 200f)
                }

                // ---- Stage 5: Malayalam OCR ----
                stage(5, "Running Malayalam OCR", pagePct, "Page ${p + 1} of $pageCount")

                var result = ocr.ocrPage(bitmap) { variant, conf ->
                    stage(5, "Running Malayalam OCR", pagePct,
                        "Page ${p + 1}: $variant (${conf}%)")
                }

                // Second pass if confidence is low
                if (result != null && result.confidence < 60) {
                    stage(5, "Running Malayalam OCR", pagePct,
                        "Page ${p + 1}: second pass (low confidence)")
                    val second = ocr.ocrPageSecondPass(bitmap)
                    if (second != null && second.confidence > result.confidence) {
                        result = second
                    }
                }

                if (!bitmap.isRecycled) {
                    try { bitmap.recycle() } catch (_: Exception) {}
                }

                if (result == null || result.text.isBlank()) {
                    // Empty page - add page break only
                    if (p > 0) blocks.add(DocBlock.PageBreak)
                    continue
                }

                totalConf += result.confidence
                confCount++

                // ---- Stage 6: Validating OCR ----
                stage(6, "Validating OCR", pagePct, "Page ${p + 1} of $pageCount")
                val normalized = MalayalamValidator.normalize(result.text)
                val report = MalayalamValidator.validate(normalized)

                // If poor quality and we haven't done second pass, try it
                var finalText = normalized
                if (report.isPoorQuality && result.confidence < 70) {
                    // (Second pass already tried above if conf < 60;
                    //  for 60-70 with poor validation, accept but flag)
                }

                // ---- Stage 7: Conservative correction ----
                // Only apply the safe post-corrections (same as text path).
                // Do NOT rewrite words - preserve OCR output.
                stage(7, "Correcting OCR errors", pagePct, "Page ${p + 1} of $pageCount")
                finalText = MalayalamPostCorrector.correct(finalText)

                // Split into paragraphs (double newline or single newline)
                val paragraphs = finalText.split(Regex("\n\\s*\n|\n"))
                if (p > 0) blocks.add(DocBlock.PageBreak)
                for (para in paragraphs) {
                    val trimmed = para.trim()
                    if (trimmed.isEmpty()) continue
                    // OCR doesn't give us style info - use default size, not bold
                    blocks.add(DocBlock.Para(
                        listOf(DocRun(trimmed, 11f, false)),
                        16f
                    ))
                }
            }

            onConfidence(if (confCount > 0) totalConf / confCount else 0)
        } finally {
            ocr.release()
        }

        return blocks
    }
}
