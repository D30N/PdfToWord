package com.deon.pdftoword

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image preprocessing pipeline for OCR.
 *
 * PDF page -> high-res Bitmap -> grayscale -> contrast -> threshold -> OCR
 *
 * Multiple variants are produced so the OCR engine can pick the one
 * with the highest confidence (second-pass strategy).
 */
object ImagePreprocessor {

    /** A preprocessed variant ready for OCR. */
    data class Variant(
        val name: String,
        val bitmap: Bitmap
    )

    /**
     * Produce OCR-ready variants from a rendered page bitmap.
     * Returns up to 4 variants: original, grayscale, contrast-enhanced, binarized.
     */
    fun preprocess(page: Bitmap): List<Variant> {
        val variants = mutableListOf<Variant>()

        // 1. Original (as rendered)
        variants.add(Variant("original", page))

        // 2. Grayscale
        val gray = toGrayscale(page)
        variants.add(Variant("grayscale", gray))

        // 3. Contrast-enhanced grayscale
        val enhanced = enhanceContrast(gray)
        variants.add(Variant("contrast", enhanced))

        // 4. Binarized (adaptive threshold)
        val binary = adaptiveThreshold(gray)
        variants.add(Variant("binarized", binary))

        return variants
    }

    /** Convert to grayscale using luminance. */
    fun toGrayscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            // ITU-R BT.601 luma
            val y = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(y, y, y)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Enhance contrast with a simple linear stretch + mild S-curve.
     * Skipped automatically if the image already has good contrast.
     */
    fun enhanceContrast(gray: Bitmap): Bitmap {
        val w = gray.width
        val h = gray.height

        // Sample to find min/max (avoid full scan on huge images - sample every 4th pixel)
        val pixels = IntArray(w * h)
        gray.getPixels(pixels, 0, w, 0, 0, w, h)

        var minV = 255
        var maxV = 0
        var i = 0
        while (i < pixels.size) {
            val v = Color.red(pixels[i])
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            i += 4
        }

        val range = maxV - minV
        // If contrast is already good (>150 range), don't aggressively process.
        if (range > 150) {
            return gray
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = if (range > 0) 255.0 / range else 1.0
        for (j in pixels.indices) {
            val v = Color.red(pixels[j])
            // Linear stretch
            var nv = ((v - minV) * scale).roundToInt().coerceIn(0, 255)
            // Mild S-curve for text sharpening
            nv = sCurve(nv)
            pixels[j] = Color.rgb(nv, nv, nv)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun sCurve(v: Int): Int {
        // Gentle S-curve: pushes midtones apart, keeps extremes
        val x = v / 255.0
        val y = x * x * (3 - 2 * x)  // smoothstep
        // Blend 50% original + 50% curve to stay conservative
        return ((v * 0.5 + y * 255 * 0.5).roundToInt()).coerceIn(0, 255)
    }

    /**
     * Adaptive (local) threshold using a box-blur mean.
     * Produces clean black-on-white text for OCR.
     */
    fun adaptiveThreshold(gray: Bitmap): Bitmap {
        val w = gray.width
        val h = gray.height
        val pixels = IntArray(w * h)
        gray.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to int luminance array
        val lum = IntArray(w * h) { Color.red(pixels[it]) }

        // Box blur with radius ~15 (downsampled for speed)
        val radius = 15
        val blurred = boxBlur(lum, w, h, radius)

        val out = IntArray(w * h)
        // Sauvola-like: threshold = mean * (1 - k), k=0.15, conservative
        for (idx in lum.indices) {
            val mean = blurred[idx]
            val threshold = (mean * 0.85).toInt()
            val v = if (lum[idx] < threshold) 0 else 255
            out[idx] = Color.rgb(v, v, v)
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
        return outBmp
    }

    /** Fast separable box blur. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(w * h)
        val dst = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            var sum = 0
            val rowOff = y * w
            // Init window
            for (x in -radius..radius) {
                sum += src[rowOff + x.coerceIn(0, w - 1)]
            }
            for (x in 0 until w) {
                tmp[rowOff + x] = sum / (2 * radius + 1)
                val xOut = (x - radius).coerceIn(0, w - 1)
                val xIn = (x + radius + 1).coerceIn(0, w - 1)
                sum += src[rowOff + xIn] - src[rowOff + xOut]
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var sum = 0
            for (y in -radius..radius) {
                sum += tmp[y.coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) {
                dst[y * w + x] = sum / (2 * radius + 1)
                val yOut = (y - radius).coerceIn(0, h - 1)
                val yIn = (y + radius + 1).coerceIn(0, h - 1)
                sum += tmp[yIn * w + x] - tmp[yOut * w + x]
            }
        }
        return dst
    }

    /**
     * Simple noise removal: despeckle isolated dark pixels.
     * (Used only when the image looks noisy.)
     */
    fun despeckle(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (Color.red(pixels[idx]) < 128) {
                    // Count dark neighbors
                    var dark = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (Color.red(pixels[idx + dy * w + dx]) < 128) dark++
                        }
                    }
                    // Isolated pixel -> make white
                    if (dark <= 1) {
                        out[idx] = Color.WHITE
                    }
                }
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
        return outBmp
    }
}
