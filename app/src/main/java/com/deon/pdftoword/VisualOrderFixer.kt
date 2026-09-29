package com.deon.pdftoword

import com.tom_roush.pdfbox.text.TextPosition

/**
 * Converts visual-order glyph sequences to logical order.
 *
 * The subsetter emits glyphs left-to-right as drawn: pre-base matra marks
 * (െ/േ/ൈ) and subjoined consonants (vattus) come BEFORE their base
 * consonant. Unicode logical order needs them after.
 *
 * Operates on (correctedUnicode, TextPosition) pairs so style info (size,
 * bold) travels with the glyph while it moves.
 */
object VisualOrderFixer {

    private val PRE_BASE = UnicodeCorrector.PRE_BASE_MATRAS
    private val VATTU = UnicodeCorrector.VATTU_MARKER
    private const val VIRAMA = UnicodeCorrector.VIRAMA
    private const val RA = UnicodeCorrector.RA
    private const val AA_SIGN = UnicodeCorrector.AA_SIGN

    private fun isBaseUnit(u: String): Boolean {
        if (u.isEmpty()) return false
        val c = u[0]
        // Malayalam consonants (0D15-0D39) incl. conjunct units like "ന്റ",
        // or independent vowels (0D05-0D14).
        return (c in '\u0D15'..'\u0D39') || (c in '\u0D05'..'\u0D14')
    }

    /**
     * Reorders one stripper writeString() batch. Input: per-glyph
     * (corrected unicode, TextPosition). Output: reordered pairs.
     */
    fun reorder(pairs: List<Pair<String, TextPosition>>): List<Pair<String, TextPosition>> {
        if (pairs.isEmpty()) return pairs

        // Pass 1: vattus. [vattu][base] -> [base + ് + ര] (single unit).
        // The vattu is always immediately before its base in the stream.
        val afterVattu = ArrayList<Pair<String, TextPosition>>(pairs.size)
        var i = 0
        while (i < pairs.size) {
            val (u, tp) = pairs[i]
            if (u.length == 1 && u[0] == VATTU && i + 1 < pairs.size) {
                val (nextU, nextTp) = pairs[i + 1]
                if (isBaseUnit(nextU)) {
                    afterVattu.add(nextU + "$VIRAMA$RA" to nextTp)
                    i += 2
                    continue
                }
            }
            if (u.length == 1 && u[0] == VATTU) {
                afterVattu.add(RA.toString() to tp) // vattu with no base: bare ര
            } else {
                afterVattu.add(u to tp)
            }
            i++
        }

        // Pass 2: pre-base matras. [matra][base] -> [base][matra].
        // After pass 1 the matra is always immediately before its base.
        val afterMatra = ArrayList<Pair<String, TextPosition>>(afterVattu.size)
        i = 0
        while (i < afterVattu.size) {
            val (u, tp) = afterVattu[i]
            if (u.length == 1 && u[0] in PRE_BASE && i + 1 < afterVattu.size) {
                val (nextU, nextTp) = afterVattu[i + 1]
                if (isBaseUnit(nextU)) {
                    afterMatra.add(nextU to nextTp)
                    afterMatra.add(u to tp)
                    i += 2
                    continue
                }
            }
            afterMatra.add(u to tp)
            i++
        }

        // Pass 3: combine split vowel signs. [െ/േ/ൈ][ാ] -> [ൊ/ോ/ൌ].
        val combined = ArrayList<Pair<String, TextPosition>>(afterMatra.size)
        i = 0
        while (i < afterMatra.size) {
            val (u, tp) = afterMatra[i]
            if (u.length == 1 && u[0] in PRE_BASE && i + 1 < afterMatra.size) {
                val (nextU, _) = afterMatra[i + 1]
                if (nextU.length == 1 && nextU[0] == AA_SIGN) {
                    val c = when (u[0]) {
                        '\u0D46' -> '\u0D4A' // ൊ
                        '\u0D47' -> '\u0D4B' // ോ
                        '\u0D48' -> '\u0D4C' // ൌ
                        else -> null
                    }
                    if (c != null) {
                        combined.add(c.toString() to tp)
                        i += 2
                        continue
                    }
                }
            }
            combined.add(u to tp)
            i++
        }

        return combined
    }
}
