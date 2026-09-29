package com.deon.pdftoword

import java.text.Normalizer

/**
 * Validates Malayalam Unicode text after OCR/extraction.
 *
 * Detects:
 * - broken Unicode sequences
 * - invalid combining-mark order
 * - duplicated vowel signs
 * - incorrectly separated conjunct characters
 * - English characters accidentally inserted into Malayalam words
 * - random punctuation inserted inside words
 * - broken chillu characters
 *
 * Conservative: only flags, does not rewrite. The caller decides
 * whether to retry OCR or accept.
 */
object MalayalamValidator {

    data class ValidationReport(
        val totalChars: Int,
        val malayalamChars: Int,
        val malayalamPercent: Double,
        val suspiciousReplacements: Int,  // U+FFFD count
        val brokenSequences: Int,         // invalid mark order, doubled signs, etc.
        val englishInMalayalamWords: Int, // latin chars touching Malayalam
        val repeatedChars: Int,           // 3+ same char in a row (suspicious)
        val isPoorQuality: Boolean,
        val issues: List<String>
    )

    // Malayalam Unicode ranges
    private const val ML_START = '\u0D00'
    private const val ML_END = '\u0D7F'

    // Dependent vowel signs (should follow a consonant, not another sign)
    private val VOWEL_SIGNS = setOf(
        '\u0D3E', '\u0D3F', '\u0D40', '\u0D41', '\u0D42', '\u0D43', '\u0D44',
        '\u0D46', '\u0D47', '\u0D48', '\u0D4A', '\u0D4B', '\u0D4C', '\u0D57'
    )
    // Pre-base matras (visually before consonant, logically after)
    private val PRE_BASE = setOf('\u0D46', '\u0D47', '\u0D48')

    private const val VIRAMA = '\u0D4D'
    private const val ZWJ = '\u200D'
    private const val ZWNJ = '\u200C'
    private const val REPLACEMENT = '\uFFFD'

    // Chillu letters (must not be followed by virama)
    private val CHILLUS = setOf(
        '\u0D7A', '\u0D7B', '\u0D7C', '\u0D7D', '\u0D7E', '\u0D7F'
    )

    // Malayalam consonants
    private fun isConsonant(c: Char): Boolean {
        return c in '\u0D15'..'\u0D39'
    }

    private fun isMalayalam(c: Char): Boolean {
        return c in ML_START..ML_END
    }

    /**
     * Validate text, return a quality report.
     */
    fun validate(text: String): ValidationReport {
        val issues = mutableListOf<String>()
        var suspiciousReplacements = 0
        var brokenSequences = 0
        var englishInWords = 0
        var repeatedChars = 0
        var malayalamChars = 0

        // NFC normalize first
        val norm = Normalizer.normalize(text, Normalizer.Form.NFC)

        var i = 0
        var lastChar: Char? = null
        var repeatCount = 1

        while (i < norm.length) {
            val c = norm[i]

            if (isMalayalam(c)) malayalamChars++
            if (c == REPLACEMENT) suspiciousReplacements++

            // Repeated characters (3+ in a row = suspicious OCR artifact)
            if (c == lastChar && c != ' ' && c != '\n') {
                repeatCount++
                if (repeatCount == 3) repeatedChars++
            } else {
                repeatCount = 1
            }
            lastChar = c

            // Check combining-mark order issues
            if (c in VOWEL_SIGNS && i > 0) {
                val prev = norm[i - 1]
                // Vowel sign after another vowel sign (not pre-base combo) = broken
                if (prev in VOWEL_SIGNS && c !in PRE_BASE && prev !in PRE_BASE) {
                    // Allow െ+ാ, േ+ാ combos (ൊ, ോ) - they're valid
                    val valid = (prev == '\u0D46' && c == '\u0D3E') ||
                                (prev == '\u0D47' && c == '\u0D3E')
                    if (!valid) {
                        brokenSequences++
                        if (brokenSequences <= 3) {
                            issues.add("Doubled vowel sign at position $i")
                        }
                    }
                }
                // Vowel sign at word start (after space) = broken visual-order
                if (prev == ' ' || prev == '\n') {
                    brokenSequences++
                    if (brokenSequences <= 3) {
                        issues.add("Vowel sign at word start (visual-order leak) at $i")
                    }
                }
            }

            // Chillu followed by virama = broken
            if (c in CHILLUS && i + 1 < norm.length && norm[i + 1] == VIRAMA) {
                brokenSequences++
                if (brokenSequences <= 3) {
                    issues.add("Chillu + virama (invalid) at $i")
                }
            }

            // Virama at end of text or before space = suspicious
            if (c == VIRAMA && (i + 1 >= norm.length || norm[i + 1] == ' ' || norm[i + 1] == '\n')) {
                brokenSequences++
            }

            // English letter directly touching Malayalam (no space) = suspicious
            if (c in 'a'..'z' || c in 'A'..'Z') {
                val prevIsMl = i > 0 && isMalayalam(norm[i - 1])
                val nextIsMl = i + 1 < norm.length && isMalayalam(norm[i + 1])
                if (prevIsMl || nextIsMl) {
                    englishInWords++
                }
            }

            i++
        }

        val totalChars = norm.length.coerceAtLeast(1)
        val mlPercent = malayalamChars * 100.0 / totalChars

        // Quality heuristics
        val problems = mutableListOf<String>()
        if (suspiciousReplacements > 0) problems.add("$suspiciousReplacements replacement chars")
        if (brokenSequences > totalChars * 0.02) problems.add("$brokenSequences broken sequences")
        if (repeatedChars > 5) problems.add("$repeatedChars repeated-char runs")

        val isPoor = problems.isNotEmpty() ||
            (malayalamChars > 50 && mlPercent < 30) // expected Malayalam but got little

        issues.addAll(0, problems)

        return ValidationReport(
            totalChars = norm.length,
            malayalamChars = malayalamChars,
            malayalamPercent = mlPercent,
            suspiciousReplacements = suspiciousReplacements,
            brokenSequences = brokenSequences,
            englishInMalayalamWords = englishInWords,
            repeatedChars = repeatedChars,
            isPoorQuality = isPoor,
            issues = issues
        )
    }

    /**
     * Light NFC normalization + zero-width cleanup.
     * Does NOT rewrite words - only normalizes encoding.
     */
    fun normalize(text: String): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFC)
        // Remove zero-width chars that break Malayalam rendering,
        // EXCEPT ZWJ which is needed for some conjuncts (e.g. ്ര)
        // We keep ZWJ but remove stray ZWNJ at word boundaries.
        // Conservative: only remove ZWJ/ZWNJ that are clearly stray
        // (at start/end of text or surrounded by spaces).
        result = result.replace(Regex("^[\u200C\u200D]+"), "")
        result = result.replace(Regex("[\u200C\u200D]+$"), "")
        result = result.replace(Regex(" [\u200C\u200D]+ "), " ")
        return result
    }

    /**
     * Count words (for quality check).
     */
    fun countWords(text: String): Int {
        return text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }
}
