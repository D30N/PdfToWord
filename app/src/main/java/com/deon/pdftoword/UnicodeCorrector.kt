package com.deon.pdftoword

import com.tom_roush.fontbox.ttf.CmapSubtable
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
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
     * Document-level ML-Karthika map: font NAME -> (code -> Unicode).
     * Built by [buildMlKarthikaMap] from the COS /Differences arrays.
     * This is the reliable path for ML-Karthika Type1 fonts.
     * Keyed by font name (String) to avoid PDFont equality issues.
     */
    private val mlKarthikaCodeMap = HashMap<String, Map<Int, String>>()

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
     * Document-level scan for ML-Karthika Type1 fonts. Builds a direct
     * (font object -> code -> Unicode) map by parsing each font's
     * /Encoding /Differences array from the COS dictionary.
     *
     * This is the RELIABLE path: it does not depend on PDFBox's Encoding
     * API working correctly for these fonts. Call once per document
     * before extraction.
     */
    fun buildMlKarthikaMap(doc: PDDocument) {
        mlKarthikaCodeMap.clear()
        try {
            val seenNames = HashSet<String>()
            for (page in doc.pages) {
                val resources = try { page.resources } catch (e: Exception) { continue }
                    ?: continue
                val fontNames = try { resources.fontNames } catch (e: Exception) { continue }
                    ?: continue
                for (fontName in fontNames) {
                    val font = try { resources.getFont(fontName) } catch (e: Exception) { null }
                        ?: continue
                    val name = try { font.name ?: "" } catch (e: Exception) { "" }
                    if (!name.contains("ML-Karthika", ignoreCase = true)) continue
                    if (!seenNames.add(name)) continue
                    val isBold = name.contains("Bold", ignoreCase = true)
                    val codeMap = parseDifferencesToUnicode(font, isBold) ?: continue
                    if (codeMap.isNotEmpty()) {
                        mlKarthikaCodeMap[name] = codeMap
                    }
                }
            }
        } catch (e: Exception) {
            // Non-fatal: per-glyph lookup will try other paths.
        }
    }

    /**
     * Parses a font's /Encoding /Differences array and returns
     * code -> Unicode string, using [MlKarthikaMap] for glyph names.
     */
    private fun parseDifferencesToUnicode(font: PDFont, isBold: Boolean): Map<Int, String>? {
        return try {
            val cosDict = font.cosObject as? COSDictionary ?: return null
            val encObj = cosDict.getDictionaryObject(COSName.getPDFName("Encoding"))
                ?: return null
            val encDict = encObj as? COSDictionary ?: return null
            val diffArray = encDict.getCOSArray(COSName.getPDFName("Differences"))
                ?: return null

            val result = HashMap<Int, String>()
            var curCode = -1
            val iter = diffArray.iterator()
            while (iter.hasNext()) {
                val obj = iter.next()
                when (obj) {
                    is COSNumber -> curCode = obj.intValue()
                    is COSName -> {
                        // getName() returns the raw glyph name (e.g. "nbspace").
                        // toString() returns "COSName{nbspace}" — NOT usable.
                        val glyphName = try { obj.getName() } catch (e: Exception) { null }
                        if (glyphName == null) {
                            curCode++
                            continue
                        }
                        val unicode = if (isBold) {
                            MlKarthikaMap.BOLD[glyphName]
                                ?: MlKarthikaMap.NORMAL[glyphName]
                        } else {
                            MlKarthikaMap.NORMAL[glyphName]
                        }
                        if (unicode != null && curCode >= 0) {
                            result[curCode] = unicode
                        }
                        curCode++
                    }
                }
            }
            result
        } catch (e: Exception) {
            null
        }
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

    /**
     * ML-Karthika Type1 font lookup: these fonts (GBIDGA+ML-Karthika-Normal,
     * GBIDGB+ML-Karthika-Bold) use a fake Latin /Differences encoding with
     * corrupt ToUnicode. We map by glyph name using the reverse-engineered
     * table in [MlKarthikaMap]. Returns null if not an ML-Karthika font or
     * the glyph is not in the map (caller falls back to ToUnicode).
     *
     * The glyph name is read DIRECTLY from the font's /Encoding /Differences
     * array in the COS dictionary (most reliable across PDFBox forks),
     * with the high-level Encoding API as fallback.
     */
    private fun mlKarthikaLookup(font: PDFont, code: Int): String? {
        try {
            if (font !is PDType1Font) return null
            val name = try { font.name ?: "" } catch (e: Exception) { "" }
            if (!name.contains("ML-Karthika", ignoreCase = true)) return null
            val isBold = name.contains("Bold", ignoreCase = true)

            // 1. Try reading /Differences directly from COS dictionary.
            var glyphName: String? = readDifferencesGlyphName(font, code)

            // 2. Fallback: high-level Encoding API.
            // Note: Encoding.getName(code) already returns the glyph name String.
            if (glyphName == null) {
                glyphName = try {
                    val encoding = font.encoding ?: return null
                    encoding.getName(code)
                } catch (e: Exception) { null }
            }
            if (glyphName == null) return null

            // Try bold map first, fallback to normal map.
            if (isBold) {
                MlKarthikaMap.BOLD[glyphName]?.let { return it }
            }
            return MlKarthikaMap.NORMAL[glyphName]
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Reads the glyph name for [code] directly from the font's
     * /Encoding /Differences array in the COS dictionary.
     */
    private fun readDifferencesGlyphName(font: PDType1Font, code: Int): String? {
        return try {
            val cosDict = font.cosObject ?: return null
            val encObj = cosDict.getDictionaryObject(COSName.getPDFName("Encoding"))
                ?: return null
            val encDict = encObj as? com.tom_roush.pdfbox.cos.COSDictionary
                ?: return null
            val diffArray = encDict.getCOSArray(COSName.getPDFName("Differences"))
                ?: return null

            // Walk the Differences array: [code, /name, /name, ..., code, /name, ...]
            var curCode = -1
            val iter = diffArray.iterator()
            while (iter.hasNext()) {
                val obj = iter.next()
                if (obj is com.tom_roush.pdfbox.cos.COSNumber) {
                    curCode = obj.intValue()
                } else if (obj is COSName) {
                    if (curCode == code) {
                        // getName() returns raw name; toString() gives "COSName{name}".
                        return try { obj.getName() } catch (e: Exception) { null }
                    }
                    curCode++
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun compute(font: PDFont, code: Int): String {
        // ML-Karthika Type1 fonts: document-level map (built from COS
        // /Differences) is the reliable path. ToUnicode is corrupt.
        // Keyed by font name to avoid PDFont equality issues.
        val fontName = try { font.name ?: "" } catch (e: Exception) { "" }
        if (fontName.contains("ML-Karthika", ignoreCase = true)) {
            mlKarthikaCodeMap[fontName]?.get(code)?.let { return it }
        }

        // Per-glyph fallback (tries Encoding API).
        val mlk = mlKarthikaLookup(font, code)
        if (mlk != null) return mlk

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
