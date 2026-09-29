package com.deon.pdftoword

import com.tom_roush.fontbox.ttf.CmapSubtable
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayInputStream

/**
 * Repairs broken ToUnicode CMaps using the embedded font's 'cmap' table
 * as ground truth.
 *
 * Background: some PDF generators (seen in Malayalam current-affairs PDFs)
 * emit corrupt ToUnicode maps — pre-base matra marks (െ/േ/ൈ) map to empty,
 * bare consonants gain spurious vowels (യ→"യേ", പ→"പെ"), and subjoined
 * consonants (vattus like ്്ര) have no mapping at all. The 'cmap' inside
 * the embedded subset font, however, maps each simple glyph to its true
 * Unicode (verified by rendering the glyph outlines).
 *
 * Rules (per CID, assuming CID == GID via Identity CIDToGIDMap):
 *  - cmap has a Unicode for the GID -> use it (fixes marks + spurious
 *    vowels on simple glyphs)
 *  - cmap has none (conjunct / ligature / vattu glyph):
 *      - cross-font consensus override exists -> use it (repairs sporadic
 *        corrupt ligature ToUnicode where the subsetter inserted extra
 *        characters, e.g. ്യ→"ദ്യ", ന്ദ→"ന്ദ്ര"; verified by rendering
 *        glyph outlines)
 *      - ToUnicode empty    -> VATTU_MARKER (subjoined consonant; the only
 *        such glyph seen is the ra-vattu; truly blank glyphs map to "")
 *      - ToUnicode non-empty -> strip spurious െ/േ/ൈ the subsetter appends
 *        (ക്ക→"ക്കേ" becomes "ക്ക")
 *  - anything else -> trust the original ToUnicode
 */
class UnicodeCorrector {

    companion object {
        /** Private-use marker for a subjoined (vattu) consonant glyph. */
        const val VATTU_MARKER = '\uE000'

        /** Pre-base matra marks: െ േ ൈ */
        val PRE_BASE_MATRAS = setOf('\u0D46', '\u0D47', '\u0D48')

        const val VIRAMA = '\u0D4D'
        const val RA = '\u0D30'
        const val AA_SIGN = '\u0D3E' // ാ
    }

    private data class FontInfo(
        val gidToUnicode: Map<Int, Int>?, // GID -> Unicode, null if unavailable
        val identityGid: Boolean
    )

    private val fontInfoCache = HashMap<PDFont, FontInfo?>()
    private val correctionCache = HashMap<Pair<PDFont, Int>, String>()

    /**
     * Cross-font consensus overrides: familyKey -> CID -> corrected unicode.
     * Built by [buildConsensus] from a pre-pass over the document. Only
     * contains CIDs where sibling fonts (same family, same subset CID order)
     * disagreed on the ToUnicode in a way that looks like insertion
     * corruption (shortest is a substring of all others).
     */
    private val consensusOverrides = HashMap<String, HashMap<Int, String>>()

    /** Corrected unicode string for the given font + character code (CID). */
    fun corrected(font: PDFont, code: Int): String {
        return correctionCache.getOrPut(font to code) { compute(font, code) }
    }

    /**
     * Build the cross-font consensus table. [fontToRaw] maps each font to
     * its raw ToUnicode per CID (collected in a pre-pass). Fonts are grouped
     * by family (subset prefix + weight suffix stripped); CIDs are comparable
     * across siblings because the subsetter numbers them in the same order.
     */
    fun buildConsensus(fontToRaw: Map<PDFont, Map<Int, String>>) {
        consensusOverrides.clear()
        correctionCache.clear()
        val byFamily = HashMap<String, MutableList<Map<Int, String>>>()
        for ((font, cidMap) in fontToRaw) {
            byFamily.getOrPut(familyKey(font)) { mutableListOf() }.add(cidMap)
        }
        for ((fam, maps) in byFamily) {
            if (maps.size < 2) continue
            val allCids = maps.flatMap { it.keys }.toSet()
            for (cid in allCids) {
                val raws = maps.mapNotNull { it[cid] }
                if (raws.size < 2) continue
                val stripped = raws
                    .map { it.filter { c -> c !in PRE_BASE_MATRAS } }
                    .toSet()
                if (stripped.size < 2) continue
                // Winner = shortest string contained in every other variant.
                // This is the insertion-corruption signature (e.g. ്യ vs ദ്യ,
                // ന്ദ vs ന്ദ്ര). No substring relationship -> leave alone.
                val winner = stripped.sortedBy { it.length }.firstOrNull { cand ->
                    stripped.all { other -> other.contains(cand) }
                } ?: continue
                consensusOverrides.getOrPut(fam) { HashMap() }[cid] = winner
            }
        }
    }

    /**
     * Family key for consensus grouping: drops the subset tag ("AB12CD+")
     * and a trailing weight/style suffix ("-Bold", "-Italic", ...).
     */
    private fun familyKey(font: PDFont): String {
        val name = try { font.name ?: "" } catch (e: Exception) { "" }
        val base = if ("+" in name) name.substringAfter("+") else name
        return base.replace(
            Regex("-(Bold|Italic|BoldItalic|Light|Medium|Regular|Black)$", RegexOption.IGNORE_CASE),
            ""
        )
    }

    private fun compute(font: PDFont, code: Int): String {
        val fallback = try {
            font.toUnicode(code) ?: ""
        } catch (e: Exception) {
            ""
        }
        val info = getFontInfo(font) ?: return fallback
        if (!info.identityGid) return fallback
        val map = info.gidToUnicode ?: return fallback

        // cmap hit: the font's own Unicode for this glyph is the truth.
        val unicode = map[code]
        if (unicode != null) {
            return String(Character.toChars(unicode))
        }

        // cmap miss: conjunct / ligature / vattu glyph (no Unicode mapping).
        // 1. Cross-font consensus: a sibling font's agreeing mapping wins over
        //    a sporadically corrupt ToUnicode (inserted characters).
        val consensus = consensusOverrides[familyKey(font)]?.get(code)
        if (consensus != null) return consensus

        if (fallback.isEmpty()) {
            // No mapping at all. Blank glyph -> "", else subjoined consonant.
            return if (isGlyphEmpty(font, code)) "" else VATTU_MARKER.toString()
        }
        // Strip spurious pre-base matras the subsetter appends.
        return fallback.filter { it !in PRE_BASE_MATRAS }
    }

    private fun getFontInfo(font: PDFont): FontInfo? {
        if (fontInfoCache.containsKey(font)) return fontInfoCache[font]
        val info = loadFontInfo(font)
        fontInfoCache[font] = info
        return info
    }

    private fun loadFontInfo(font: PDFont): FontInfo? {
        try {
            if (font !is PDType0Font) return null
            val descendant = font.descendantFont ?: return null

            // Glyph data is indexed by GID; only usable when CID == GID.
            val cidToGid = try {
                descendant.cosObject.getDictionaryObject("CIDToGIDMap")
            } catch (e: Exception) {
                null
            }
            val identity = cidToGid is COSName && cidToGid.name == "Identity"

            val ff2 = descendant.fontDescriptor?.fontFile2 ?: return FontInfo(null, false)
            val bytes = ff2.toByteArray()
            val ttf = TTFParser().parse(ByteArrayInputStream(bytes))

            // Build GID -> Unicode from the font's own cmap.
            val gidToUnicode = buildGidMap(ttf)
            return FontInfo(gidToUnicode, identity)
        } catch (e: Exception) {
            return null
        }
    }

    private fun buildGidMap(ttf: com.tom_roush.fontbox.ttf.TrueTypeFont): Map<Int, Int>? {
        try {
            // The Unicode cmap directly maps Unicode -> GID.
            val subtable: CmapSubtable = try {
                ttf.unicodeCmap
            } catch (e: Exception) {
                return null
            } ?: return null

            val map = HashMap<Int, Int>()
            // Invert: for each char code, record GID -> char code.
            for (charCode in 0..0xFFFF) {
                try {
                    val gid = subtable.getGlyphId(charCode)
                    if (gid != 0 && !map.containsKey(gid)) {
                        map[gid] = charCode
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            return map
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * True if the glyph has an empty outline (no contours). Used to tell
     * blank glyphs apart from vattu glyphs when ToUnicode is empty.
     */
    private fun isGlyphEmpty(font: PDFont, code: Int): Boolean {
        try {
            if (font !is PDType0Font) return false
            val descendant = font.descendantFont ?: return false
            val ff2 = descendant.fontDescriptor?.fontFile2 ?: return false
            val bytes = ff2.toByteArray()
            val ttf = TTFParser().parse(ByteArrayInputStream(bytes))
            val loca = try { ttf.indexToLocation } catch (e: Exception) { null } ?: return false
            val offsets = try { loca.offsets } catch (e: Exception) { null } ?: return false
            if (code < 0 || code + 1 >= offsets.size) return false
            return offsets[code] == offsets[code + 1]
        } catch (e: Exception) {
            return false
        }
    }
}
