package com.deon.pdftoword

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

/**
 * Malayalam OCR engine powered by Tesseract.
 *
 * - Primary language: Malayalam ("mal"), fallback: English ("eng")
 * - Runs OCR on multiple preprocessing variants, picks the best by confidence
 * - Output is NFC-normalized Malayalam Unicode
 *
 * IMPORTANT: This is the PRIMARY text source for scanned PDFs.
 * No LLM reconstruction - only the actual OCR result is used.
 */
class OcrEngine(private val context: Context) {

    data class OcrResult(
        val text: String,
        val confidence: Int,       // 0-100 mean confidence
        val variantName: String,   // which preprocessing won
        val language: String       // "mal", "eng", "mal+en"
    )

    private var tess: TessBaseAPI? = null
    private var initialized = false
    private var initError: String? = null

    /** Tessdata directory (must contain mal.traineddata + eng.traineddata). */
    private fun tessDir(): File {
        return File(context.filesDir, "tessdata").apply { mkdirs() }
    }

    /**
     * Initialize Tesseract. Copies traineddata from assets on first run.
     * Returns true on success.
     */
    fun init(): Boolean {
        if (initialized) return true
        if (initError != null) return false

        try {
            val dir = tessDir()

            // Copy traineddata files from assets if missing
            for (lang in listOf("mal", "eng")) {
                val target = File(dir, "$lang.traineddata")
                if (!target.exists() || target.length() == 0L) {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            val api = TessBaseAPI()
            // Malayalam PRIMARY, English secondary
            val ok = api.init(dir.parent, "mal+eng")
            if (!ok) {
                initError = "Tesseract init failed"
                return false
            }

            // OCR configuration for Malayalam
            // PSM 3 = fully automatic page segmentation (handles headings, paragraphs, blocks)
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            // Preserve inter-word spaces (Malayalam word spacing matters)
            api.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, "")
            // Don't let Tesseract "correct" Malayalam with English dictionaries
            api.setVariable("load_system_dawg", "0")
            api.setVariable("load_freq_dawg", "0")

            tess = api
            initialized = true
            return true
        } catch (e: Exception) {
            initError = "OCR init error: ${e.message}"
            return false
        }
    }

    fun getInitError(): String? = initError

    /**
     * Run OCR on a page bitmap.
     * Tries all preprocessing variants, returns the best by confidence.
     *
     * @param page rendered page bitmap (high-res, ~300 DPI)
     * @param onVariant callback(variantName, confidence) for progress
     */
    fun ocrPage(
        page: Bitmap,
        onVariant: ((String, Int) -> Unit)? = null
    ): OcrResult? {
        if (!init()) return null
        val api = tess ?: return null

        val variants = ImagePreprocessor.preprocess(page)
        var best: OcrResult? = null

        for (variant in variants) {
            try {
                api.setImage(variant.bitmap)
                val text = api.utF8Text ?: ""
                val conf = api.meanConfidence()

                onVariant?.invoke(variant.name, conf)

                // NFC normalize Malayalam Unicode
                val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)

                val lang = detectLanguage(normalized)
                val result = OcrResult(normalized, conf, variant.name, lang)

                // Pick best: highest confidence, tie-break prefers more Malayalam chars
                if (best == null || conf > best.confidence ||
                    (conf == best.confidence && countMalayalam(normalized) > countMalayalam(best.text))
                ) {
                    best = result
                }

                // Early exit: if we got excellent confidence on a clean variant, stop
                if (conf >= 90 && variant.name in listOf("grayscale", "original")) {
                    break
                }
            } catch (_: Exception) {
                // Try next variant
            } finally {
                try { api.clear() } catch (_: Exception) {}
            }

            // Recycle intermediate bitmaps (not the original page)
            if (variant.bitmap != page && !variant.bitmap.isRecycled) {
                try { variant.bitmap.recycle() } catch (_: Exception) {}
            }
        }

        return best
    }

    /**
     * Second-pass: if confidence is low, retry is automatic because
     * ocrPage already tries all 4 variants. This method re-runs with
     * a different page segmentation mode for difficult pages.
     */
    fun ocrPageSecondPass(page: Bitmap): OcrResult? {
        if (!init()) return null
        val api = tess ?: return null

        return try {
            // PSM 6 = assume uniform block of text (good for dense Malayalam pages)
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            val gray = ImagePreprocessor.toGrayscale(page)
            val enhanced = ImagePreprocessor.enhanceContrast(gray)

            api.setImage(enhanced)
            val text = api.utF8Text ?: ""
            val conf = api.meanConfidence()
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)

            // Restore default PSM
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)

            if (!gray.isRecycled) try { gray.recycle() } catch (_: Exception) {}
            if (!enhanced.isRecycled) try { enhanced.recycle() } catch (_: Exception) {}

            OcrResult(normalized, conf, "second-pass", detectLanguage(normalized))
        } catch (_: Exception) {
            null
        } finally {
            try { api.clear() } catch (_: Exception) {}
        }
    }

    /** Release Tesseract resources. */
    fun release() {
        try { tess?.end() } catch (_: Exception) {}
        tess = null
        initialized = false
    }

    // ---------- helpers ----------

    private fun countMalayalam(text: String): Int {
        var n = 0
        for (c in text) {
            if (c in '\u0D00'..'\u0D7F') n++
        }
        return n
    }

    private fun detectLanguage(text: String): String {
        var ml = 0
        var en = 0
        for (c in text.take(2000)) {
            when (c) {
                in '\u0D00'..'\u0D7F' -> ml++
                in 'a'..'z', in 'A'..'Z' -> en++
            }
        }
        return when {
            ml > 0 && en > 0 -> "ml+en"
            ml > 0 -> "mal"
            en > 0 -> "eng"
            else -> "unknown"
        }
    }
}
