package com.gestionmagasin.pro.utils

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lecture / écriture minimale de fichiers Excel (.xlsx) et Word (.docx) SANS Apache POI.
 *
 * Pourquoi : les modules OOXML d'Apache POI 5.x dépendent de javax.xml.stream (StAX) et de java.awt,
 * absents d'Android -> plantage à l'exécution et APK très lourd. Ici on n'utilise que java.util.zip et le
 * DOM (org.w3c.dom / javax.xml.parsers), tous présents dans Android ET sur une JVM classique (donc testable).
 *
 * Une cellule est un String (texte) ou un Number (nombre).
 */
object OfficeLite {

    // ------------------------------------------------------------------ ÉCRITURE EXCEL

    fun writeXlsx(rows: List<List<Any?>>, out: OutputStream, sheetName: String = "Articles", columnWidths: List<Int> = emptyList()) {
        val sheet = StringBuilder()
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sheet.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        if (columnWidths.isNotEmpty()) {
            sheet.append("<cols>")
            columnWidths.forEachIndexed { i, w ->
                sheet.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>")
            }
            sheet.append("</cols>")
        }
        sheet.append("<sheetData>")
        rows.forEachIndexed { r, row ->
            sheet.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, value ->
                val ref = columnLetters(c) + (r + 1)
                val style = if (r == 0) " s=\"1\"" else ""
                when (value) {
                    null -> {}
                    is Number -> sheet.append("<c r=\"$ref\"$style><v>$value</v></c>")
                    else -> sheet.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(value.toString())}</t></is></c>")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"
        val rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
        val workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"${xmlEscape(sheetName.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

        zip(
            out,
            linkedMapOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rootRels,
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to workbookRels,
                "xl/styles.xml" to styles,
                "xl/worksheets/sheet1.xml" to sheet.toString()
            )
        )
    }

    // ------------------------------------------------------------------ LECTURE EXCEL

    /** Lit la première feuille d'un .xlsx : liste de lignes, chaque ligne = liste de textes (vides comblés). */
    fun readXlsx(input: InputStream): List<List<String>> {
        val files = readZip(input)
        val shared = files["xl/sharedStrings.xml"]?.let { parseSharedStrings(parse(it)) } ?: emptyList()

        var sheetPath = "xl/worksheets/sheet1.xml"
        val wbBytes = files["xl/workbook.xml"]
        val relsBytes = files["xl/_rels/workbook.xml.rels"]
        if (wbBytes != null && relsBytes != null) {
            val firstSheet = descendants(parse(wbBytes).documentElement, "sheet").firstOrNull()
            val rid = firstSheet?.let { attrByLocalName(it, "id") }
            if (rid != null) {
                val rel = descendants(parse(relsBytes).documentElement, "Relationship")
                    .firstOrNull { it.getAttribute("Id") == rid }
                val target = rel?.getAttribute("Target")
                if (!target.isNullOrEmpty()) {
                    sheetPath = if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
                }
            }
        }
        val sheetBytes = files[sheetPath]
            ?: files.entries.firstOrNull { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }?.value
            ?: throw IllegalArgumentException("Aucune feuille trouvée dans ce fichier Excel.")

        val rows = ArrayList<List<String>>()
        for (rowEl in descendants(parse(sheetBytes).documentElement, "row")) {
            val cells = ArrayList<String>()
            var fallbackCol = 0
            for (c in children(rowEl, "c")) {
                val ref = c.getAttribute("r")
                val col = if (ref.isNotEmpty()) columnIndex(ref) else fallbackCol
                while (cells.size < col) cells.add("")
                val type = c.getAttribute("t")
                val text = when (type) {
                    "s" -> children(c, "v").firstOrNull()?.textContent?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> descendants(c, "t").joinToString("") { it.textContent }
                    else -> children(c, "v").firstOrNull()?.textContent ?: ""
                }
                if (cells.size == col) cells.add(text) else if (col < cells.size) cells[col] = text
                fallbackCol = col + 1
            }
            rows.add(cells)
        }
        return rows
    }

    private fun parseSharedStrings(doc: Document): List<String> =
        children(doc.documentElement, "si").map { si ->
            // concatène les <t> de <si> et de <si><r>, en ignorant les annotations phonétiques <rPh>
            val sb = StringBuilder()
            for (n in si.childNodes.toList()) {
                if (n !is Element) continue
                when (n.localNameOrName()) {
                    "t" -> sb.append(n.textContent)
                    "r" -> children(n, "t").forEach { sb.append(it.textContent) }
                }
            }
            sb.toString()
        }

    // ------------------------------------------------------------------ ÉCRITURE WORD

    /** Document Word : un titre + un tableau (la 1re ligne = en-têtes). Page A4 paysage. */
    fun writeDocxTable(title: String, rows: List<List<String>>, out: OutputStream) {
        val colCount = rows.maxOfOrNull { it.size } ?: 1
        val tableWidth = 15000 // twips (paysage A4 moins marges)
        val colWidth = tableWidth / colCount

        val body = StringBuilder()
        body.append("<w:p><w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr>")
            .append("<w:t xml:space=\"preserve\">${xmlEscape(title)}</w:t></w:r></w:p>")
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"$tableWidth\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            body.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
        }
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
        repeat(colCount) { body.append("<w:gridCol w:w=\"$colWidth\"/>") }
        body.append("</w:tblGrid>")
        rows.forEachIndexed { r, row ->
            body.append("<w:tr>")
            for (c in 0 until colCount) {
                val text = row.getOrElse(c) { "" }
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"$colWidth\" w:type=\"dxa\"/></w:tcPr><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r>")
                body.append(if (r == 0) "<w:rPr><w:b/><w:sz w:val=\"18\"/></w:rPr>" else "<w:rPr><w:sz w:val=\"18\"/></w:rPr>")
                body.append("<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p></w:tc>")
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl><w:p/>")

        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            body +
            "<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>" +
            "<w:pgMar w:top=\"850\" w:right=\"850\" w:bottom=\"850\" w:left=\"850\" w:header=\"400\" w:footer=\"400\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        zip(
            out,
            linkedMapOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rootRels,
                "word/document.xml" to document
            )
        )
    }

    // ------------------------------------------------------------------ LECTURE WORD

    /** Retourne tous les tableaux du document : tableau -> lignes -> cellules (texte). */
    fun readDocxTables(input: InputStream): List<List<List<String>>> {
        val files = readZip(input)
        val docBytes = files["word/document.xml"]
            ?: throw IllegalArgumentException("Ce fichier n'est pas un document Word valide.")
        val root = parse(docBytes).documentElement
        return descendants(root, "tbl").map { tbl ->
            children(tbl, "tr").map { tr ->
                children(tr, "tc").map { tc ->
                    children(tc, "p").joinToString(" ") { p ->
                        descendants(p, "t").joinToString("") { it.textContent }
                    }.trim()
                }
            }
        }
    }

    // ------------------------------------------------------------------ UTILITAIRES

    fun columnLetters(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    /** "B12" -> 1 ; "AA3" -> 26. */
    fun columnIndex(cellRef: String): Int {
        var col = 0
        for (ch in cellRef) {
            if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') col = col * 26 + (ch - 'a' + 1)
            else break
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun xmlEscape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {} // caractères interdits en XML 1.0
                ch.code == 0xFFFE || ch.code == 0xFFFF -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun zip(out: OutputStream, files: Map<String, String>) {
        ZipOutputStream(out).use { zos ->
            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) map[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        if (map.isEmpty()) throw IllegalArgumentException("Fichier vide ou illisible (ce n'est pas un fichier Office .xlsx/.docx).")
        return map
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun org.w3c.dom.NodeList.toList(): List<Node> = (0 until length).map { item(it) }

    private fun Node.localNameOrName(): String = (localName ?: nodeName.substringAfter(':'))

    /** Enfants directs portant ce nom local (indépendant du préfixe d'espace de noms). */
    private fun children(parent: Node, localName: String): List<Element> =
        parent.childNodes.toList().filterIsInstance<Element>().filter { it.localNameOrName() == localName }

    /** Tous les descendants portant ce nom local, dans l'ordre du document. */
    private fun descendants(parent: Node, localName: String): List<Element> {
        val result = ArrayList<Element>()
        fun walk(n: Node) {
            val kids = n.childNodes
            for (i in 0 until kids.length) {
                val k = kids.item(i)
                if (k is Element) {
                    if (k.localNameOrName() == localName) result.add(k)
                    walk(k)
                }
            }
        }
        walk(parent)
        return result
    }

    private fun attrByLocalName(el: Element, local: String): String? {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.localNameOrName() == local) return a.nodeValue
        }
        return null
    }
}
