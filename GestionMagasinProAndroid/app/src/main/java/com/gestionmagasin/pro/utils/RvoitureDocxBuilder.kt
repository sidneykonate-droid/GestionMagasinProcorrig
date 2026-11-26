package com.gestionmagasin.pro.utils

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Texte d'une étiquette : prix déjà formaté (ex. "7.800F") et code-barres. */
data class LabelText(val price: String, val barcode: String)

/**
 * Génère le fichier Word d'étiquettes à partir du modèle original RVOITURE.docx.
 *
 * Le modèle contient des pages de 14 cadres (2 colonnes x 7 lignes), chacun étant un groupe
 * `<wpg:grpSp>` avec deux zones de texte : [0] = prix, [1] = "CB : code".
 *
 * Règles :
 *  - le design (cadres arrondis, logo, polices Arial Black) est conservé tel quel ;
 *  - chaque emplacement reçoit son étiquette, ou est SUPPRIMÉ du XML s'il est vide
 *    (emplacements avant le "départ A4" et emplacements après la dernière étiquette) ;
 *  - seules les pages nécessaires sont écrites (aucune page vide à la fin) ;
 *  - la partie de repli VML (mc:Fallback) est retirée : elle contiendrait les anciennes valeurs d'exemple.
 *
 * Cette classe n'utilise aucune API Android : elle est exécutable et testable sur une JVM classique.
 * NB : dans les littéraux Kotlin, les regex utilisent des antislashs doublés (\\s, \\d...).
 */
object RvoitureDocxBuilder {

    const val SLOTS_PER_PAGE = 14

    private val PARA_TAG = Regex("<(/?)w:p(?=[\\s>/])[^>]*>")
    private val FALLBACK = Regex("<mc:Fallback>.*?</mc:Fallback>", RegexOption.DOT_MATCHES_ALL)
    private val GROUP = Regex("<wpg:grpSp>.*?</wpg:grpSp>", RegexOption.DOT_MATCHES_ALL)
    private val TEXTBOX = Regex("<wps:txbx>.*?</wps:txbx>", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_RUN = Regex("<w:t(?:\\s[^>]*)?>[^<]*</w:t>")
    private val VOLATILE_ATTRS = Regex("\\s(?:w14:paraId|w14:textId|wp14:anchorId|wp14:editId)=\"[^\"]*\"")
    private val SHAPE_ID = Regex("(<(?:wp:docPr|wpg:cNvPr|wps:cNvPr|pic:cNvPr)(?=\\s)[^>]*?\\sid=\")\\d+(\")")

    // Cadre englobant de la page (grpSpPr du <wpg:wgp>) : on en lit chOff/chExt.
    private val WGP_FRAME = Regex(
        "<wpg:wgp>.*?<wpg:grpSpPr>.*?<a:chOff x=\"(-?\\d+)\" y=\"(-?\\d+)\"/><a:chExt cx=\"(\\d+)\" cy=\"(\\d+)\"/>.*?</wpg:grpSpPr>",
        RegexOption.DOT_MATCHES_ALL
    )

    private const val FALLBACK_SEPARATOR = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"

    /**
     * @param template flux du fichier modèle RVOITURE.docx
     * @param slots    liste d'emplacements dans l'ordre A4 (gauche, droite, ligne par ligne) ;
     *                 `null` = emplacement vide (supprimé du document)
     * @param out      flux de sortie du .docx généré (fermé à la fin)
     */
    fun build(template: InputStream, slots: List<LabelText?>, out: OutputStream) {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(template).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }
        val docBytes = entries["word/document.xml"]
            ?: throw IllegalStateException("word/document.xml introuvable dans le modèle RVOITURE.docx")
        val newXml = buildDocumentXml(String(docBytes, Charsets.UTF_8), slots)
        entries["word/document.xml"] = newXml.toByteArray(Charsets.UTF_8)

        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    internal fun buildDocumentXml(docXml: String, slots: List<LabelText?>): String {
        val bodyTag = "<w:body>"
        val bodyOpen = docXml.indexOf(bodyTag)
        val sectStart = docXml.lastIndexOf("<w:sectPr")
        check(bodyOpen >= 0 && sectStart > bodyOpen) { "Structure inattendue du modèle RVOITURE.docx" }

        val bodyStart = bodyOpen + bodyTag.length
        val header = docXml.substring(0, bodyStart)
        val bodyInner = docXml.substring(bodyStart, sectStart)
        val tail = docXml.substring(sectStart)

        val paragraphs = splitTopLevelParagraphs(bodyInner)
        val pageTemplate = paragraphs.firstOrNull { it.contains("<wpg:wgp>") }
            ?: throw IllegalStateException("Aucune page d'étiquettes trouvée dans le modèle RVOITURE.docx")
        val separator = paragraphs.firstOrNull { !it.contains("<wpg:wgp>") && it.contains("w:type=\"page\"") }
            ?: FALLBACK_SEPARATOR

        val basePage = FALLBACK.replace(pageTemplate, "")
        val lastUsed = slots.indexOfLast { it != null }
        val pagesNeeded = if (lastUsed < 0) 1 else lastUsed / SLOTS_PER_PAGE + 1

        val sb = StringBuilder(header.length + tail.length + basePage.length * pagesNeeded)
        sb.append(header)
        for (page in 0 until pagesNeeded) {
            if (page > 0) sb.append(separator)
            var slot = page * SLOTS_PER_PAGE
            var removed = false
            val filled = GROUP.replace(basePage) {
                val label = slots.getOrNull(slot)
                slot++
                if (label == null) {
                    removed = true
                    ""
                } else {
                    fillGroup(it.value, label)
                }
            }
            sb.append(if (removed) keepPageFrame(filled) else filled)
        }
        sb.append(tail)

        // Identifiants uniques (les pages sont dupliquées à partir de la même page modèle).
        val cleaned = VOLATILE_ATTRS.replace(sb.toString(), "")
        var nextId = 1000
        return SHAPE_ID.replace(cleaned) { it.groupValues[1] + (nextId++) + it.groupValues[2] }
    }

    /**
     * Quand des cadres sont supprimés, certains logiciels (LibreOffice, WPS) recalculent le cadre du groupe
     * à partir des cadres restants et ÉTIREMENT l'ensemble. On ajoute donc, tout en bas de l'ordre
     * d'empilement, un rectangle totalement invisible (sans remplissage ni contour, jamais imprimé)
     * qui occupe l'emprise d'origine : la mise en page reste identique partout, Word compris.
     */
    private fun keepPageFrame(pageXml: String): String {
        val m = WGP_FRAME.find(pageXml) ?: return pageXml
        val (x, y, cx, cy) = m.destructured
        val keeper = "<wps:wsp><wps:cNvPr id=\"1\" name=\"Zone invisible\"/><wps:cNvSpPr/>" +
            "<wps:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>" +
            "<wps:bodyPr/></wps:wsp>"
        val at = m.range.last + 1
        return pageXml.substring(0, at) + keeper + pageXml.substring(at)
    }

    /** Découpe le corps en paragraphes de premier niveau (les zones de texte contiennent des w:p imbriqués). */
    internal fun splitTopLevelParagraphs(xml: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var start = -1
        for (m in PARA_TAG.findAll(xml)) {
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.value.endsWith("/>")
            if (!closing) {
                if (selfClosing) {
                    if (depth == 0) result.add(m.value)
                    continue
                }
                if (depth == 0) start = m.range.first
                depth++
            } else if (depth > 0) {
                depth--
                if (depth == 0 && start >= 0) {
                    result.add(xml.substring(start, m.range.last + 1))
                    start = -1
                }
            }
        }
        return result
    }

    private fun fillGroup(groupXml: String, label: LabelText): String {
        var boxIndex = 0
        return TEXTBOX.replace(groupXml) {
            val text = when (boxIndex) {
                0 -> xmlEscape(label.price)
                1 -> "CB&#160;:&#160;" + xmlEscape(label.barcode)
                else -> null
            }
            boxIndex++
            if (text == null) it.value else setSingleText(it.value, text)
        }
    }

    /** Met tout le texte dans le premier <w:t> (mise en forme du modèle conservée) et vide les autres. */
    private fun setSingleText(boxXml: String, text: String): String {
        var first = true
        return TEXT_RUN.replace(boxXml) {
            if (first) {
                first = false
                "<w:t xml:space=\"preserve\">$text</w:t>"
            } else {
                "<w:t></w:t>"
            }
        }
    }

    private fun xmlEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
