package com.deon.pdftoword

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Minimal hand-rolled .docx writer (no Apache POI). Produces a valid
 * Office Open XML package readable by Word / Google Docs / LibreOffice.
 *
 * Mapping from the PDF extraction:
 * - each PDF line -> one w:p paragraph
 * - w:spacing w:lineRule="atLeast" with the measured line pitch (twips),
 *   so the original line spacing is preserved
 * - w:rFonts ascii/hAnsi/cs = "Noto Sans Malayalam" (always on)
 * - w:sz = PDF font size in half-points
 * - w:b when the PDF font name contained "bold"
 */
object DocxWriter {

    private const val FONT = "Noto Sans Malayalam"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun write(blocks: List<DocBlock>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypes().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(packageRels().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(documentRels().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write(stylesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml(blocks).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    // ---------- XML parts ----------

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private fun packageRels() = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun documentRels() = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="$W">
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="$FONT" w:hAnsi="$FONT" w:cs="$FONT"/>
<w:sz w:val="22"/><w:szCs w:val="22"/>
</w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
<w:rPr><w:rFonts w:ascii="$FONT" w:hAnsi="$FONT" w:cs="$FONT"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr>
</w:style>
</w:styles>"""

    private fun documentXml(blocks: List<DocBlock>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="$W"><w:body>""")
        for (b in blocks) {
            when (b) {
                is DocBlock.PageBreak -> {
                    sb.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
                }
                is DocBlock.Para -> appendPara(sb, b)
            }
        }
        sb.append(
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720"/>""" +
                """</w:sectPr></w:body></w:document>"""
        )
        return sb.toString()
    }

    private fun appendPara(sb: StringBuilder, para: DocBlock.Para) {
        // lineRule="atLeast" + measured pitch (twips = pt * 20) preserves PDF line spacing.
        val pitchTwips = ((para.pitchPt.coerceIn(4f, 72f)) * 20).roundToInt()
        sb.append("""<w:p><w:pPr><w:spacing w:lineRule="atLeast" w:line="$pitchTwips"/></w:pPr>""")
        for (run in para.runs) {
            val sz = ((run.sizePt.coerceIn(4f, 72f)) * 2).roundToInt()
            sb.append("<w:r><w:rPr>")
            if (run.bold) sb.append("<w:b/><w:bCs/>")
            if (run.colorHex != null) sb.append("""<w:color w:val="${run.colorHex}"/>""")
            sb.append(
                """<w:rFonts w:ascii="$FONT" w:hAnsi="$FONT" w:cs="$FONT"/>""" +
                    """<w:sz w:val="$sz"/><w:szCs w:val="$sz"/>"""
            )
            sb.append("</w:rPr>")
            val text = clean(run.text)
            sb.append("""<w:t xml:space="preserve">${esc(text)}</w:t>""")
            sb.append("</w:r>")
        }
        sb.append("</w:p>")
    }

    // ---------- text safety ----------

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /** Strip control chars illegal in XML 1.0 (keep tab/LF/CR, though runs never contain them). */
    private fun clean(s: String): String = buildString(s.length) {
        for (ch in s) {
            val c = ch.code
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) continue
            if (c == 0x0A || c == 0x0D) {
                append(' ') // a run must not contain raw newlines
                continue
            }
            append(ch)
        }
    }
}
