package com.gestionmagasin.pro.utils

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.gestionmagasin.pro.data.ArticleEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.util.Locale

/** Valeurs par défaut : une sauvegarde ancienne ou éditée à la main (champ manquant) reste restaurable. */
data class BackupArticleDto(
    val barcode: String = "",
    val designation: String = "",
    val refProduit: String = "",
    val price: Long = 0L,
    val oldPrice: Long = 0L,
    val rayon: String = "",
    val categorie: String = "",
    val marque: String = "",
    val stock: Int = 1,
    val photoBase64: String = "",
    val visualHash: String = "",
    val avgRgb: String = ""
)

/** Erreur d'import avec un message directement affichable à l'utilisateur. */
class ImportFormatException(message: String) : Exception(message)

object ImportExportBackupHelper {

    private fun openOut(context: Context, uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Impossible d'écrire dans le fichier choisi.")

    // ------------------------------------------------------------------ EXPORTS

    fun exportToExcel(context: Context, uri: Uri, articles: List<ArticleEntity>) {
        val rows = ArrayList<List<Any?>>()
        rows.add(listOf("Code-barres", "Désignation", "Réf. produit", "Prix (FCFA)", "Rayon", "Catégorie", "Marque", "Stock"))
        articles.forEach { a ->
            rows.add(listOf(a.barcode, a.designation, a.refProduit, a.price, a.rayon, a.categorie, a.marque, a.stock))
        }
        openOut(context, uri).use { out ->
            OfficeLite.writeXlsx(rows, out, "Articles", listOf(16, 52, 14, 13, 14, 16, 14, 8))
        }
    }

    fun exportToWord(context: Context, uri: Uri, articles: List<ArticleEntity>) {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Code-barres", "Désignation", "Réf.", "Prix", "Rayon", "Catégorie", "Marque"))
        articles.forEach { a ->
            rows.add(
                listOf(
                    "CB : ${a.barcode}",
                    a.designation,
                    a.refProduit.ifEmpty { "-" },
                    OcrAndLabelParser.formatOrcaLabelPrice(a.price),
                    a.rayon,
                    a.categorie,
                    a.marque
                )
            )
        }
        openOut(context, uri).use { out ->
            OfficeLite.writeDocxTable("BASE DE DONNÉES ARTICLES (${articles.size} articles)", rows, out)
        }
    }

    fun exportToPdf(context: Context, uri: Uri, articles: List<ArticleEntity>) {
        val pdfDocument = PdfDocument()
        try {
            val paint = Paint().apply { textSize = 9f; color = Color.BLACK; isAntiAlias = true }
            val boldPaint = Paint().apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK; isAntiAlias = true }

            val itemsPerPage = 28
            val totalPages = ((articles.size + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)

            for (p in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(842, 595, p + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawText("CATALOGUE ARTICLES — Page ${p + 1}/$totalPages", 24f, 28f, boldPaint)
                canvas.drawText("Code-barres | Désignation | Réf. | Prix | Rayon / Catégorie / Marque", 24f, 46f, boldPaint)

                var y = 66f
                val slice = articles.drop(p * itemsPerPage).take(itemsPerPage)
                for (art in slice) {
                    val line = "CB : ${art.barcode} | ${art.designation.take(42)} | ${art.refProduit.ifEmpty { "-" }} | " +
                        "${OcrAndLabelParser.formatOrcaLabelPrice(art.price)} | ${art.rayon}/${art.categorie}/${art.marque}"
                    canvas.drawText(line, 24f, y, paint)
                    y += 18f
                }
                pdfDocument.finishPage(page)
            }

            openOut(context, uri).use { out -> pdfDocument.writeTo(out) }
        } finally {
            pdfDocument.close()
        }
    }

    // ------------------------------------------------------------------ IMPORT

    /**
     * Importe des articles depuis un .xlsx ou un .docx (colonnes : code-barres, désignation, réf., prix,
     * rayon, catégorie, marque[, stock]). La 1re ligne est ignorée si c'est un en-tête.
     */
    fun importFromDocument(context: Context, uri: Uri, fileName: String): List<ArticleEntity> {
        val lower = fileName.lowercase(Locale.ROOT)
        val table: List<List<String>> = when {
            lower.endsWith(".xlsx") || lower.endsWith(".xlsm") -> {
                context.contentResolver.openInputStream(uri)?.use { OfficeLite.readXlsx(it) }
                    ?: throw ImportFormatException("Impossible de lire le fichier.")
            }
            lower.endsWith(".docx") -> {
                val tables = context.contentResolver.openInputStream(uri)?.use { OfficeLite.readDocxTables(it) }
                    ?: throw ImportFormatException("Impossible de lire le fichier.")
                tables.flatMap { it }
            }
            lower.endsWith(".xls") ->
                throw ImportFormatException("Ancien format .xls non pris en charge : enregistrez le fichier au format .xlsx puis réessayez.")
            else ->
                throw ImportFormatException("Format non pris en charge. Choisissez un fichier .xlsx ou .docx.")
        }
        return parseArticleRows(table)
    }

    /** Transforme des lignes de texte en articles (testable sans Android). */
    internal fun parseArticleRows(table: List<List<String>>): List<ArticleEntity> {
        val result = LinkedHashMap<String, ArticleEntity>()
        for ((index, row) in table.withIndex()) {
            if (index == 0 && looksLikeHeader(row)) continue
            val bc = cleanCode(row.getOrElse(0) { "" })
            val des = row.getOrElse(1) { "" }.trim().uppercase(Locale.ROOT)
            if (bc.isEmpty() || des.isEmpty()) continue

            val ref = cleanCode(row.getOrElse(2) { "" }).uppercase(Locale.ROOT)
            val price = parsePrice(row.getOrElse(3) { "" })
            val rayon = row.getOrElse(4) { "" }.trim().uppercase(Locale.ROOT).ifEmpty { "GÉNÉRAL" }
            val cat = row.getOrElse(5) { "" }.trim().uppercase(Locale.ROOT).ifEmpty { "GÉNÉRAL" }
            val marque = row.getOrElse(6) { "" }.trim().uppercase(Locale.ROOT).ifEmpty { "ORCA" }
            val stock = row.getOrElse(7) { "" }.trim().replace(',', '.').toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 1

            result[bc] = ArticleEntity(
                barcode = bc,
                designation = des,
                refProduit = ref,
                price = price,
                rayon = rayon,
                categorie = cat,
                marque = marque,
                stock = stock
            )
        }
        return result.values.toList()
    }

    private fun looksLikeHeader(row: List<String>): Boolean {
        val c0 = stripAccents(row.getOrElse(0) { "" }).lowercase(Locale.ROOT)
        val c1 = stripAccents(row.getOrElse(1) { "" }).lowercase(Locale.ROOT)
        return c0.contains("code") || c0.startsWith("cb") || c0.contains("ean") || c1.contains("designation")
    }

    private fun stripAccents(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Nettoie un code issu d'Excel : "CB : 351113", "351113.0", "3.51113E5", astérisques... */
    internal fun cleanCode(raw: String): String {
        var s = raw.replace("CB :", "", ignoreCase = true).replace("CB:", "", ignoreCase = true)
            .replace("*", "").replace('\u00A0', ' ').trim()
        if (Regex("^\\d+\\.0+$").matches(s)) s = s.substringBefore('.')
        else if (Regex("^\\d+(\\.\\d+)?[eE][+-]?\\d+$").matches(s)) {
            s = runCatching { BigDecimal(s).toBigInteger().toString() }.getOrDefault(s)
        }
        return s.replace(" ", "")
    }

    /**
     * Prix en FCFA depuis un texte : "7800", "7800.0", "7.800F", "4 975 CFA", "29.500 FCFA".
     * (L'ancienne version supprimait le point de "7800.0" et lisait 78000.)
     */
    internal fun parsePrice(raw: String): Long {
        var s = raw.uppercase(Locale.ROOT).replace("FCFA", "").replace("CFA", "").replace("F", "")
            .replace('\u00A0', ' ').replace('\u202F', ' ').trim()
        if (s.isEmpty()) return 0L
        if (Regex("^\\d{1,3}([. ]\\d{3})+$").matches(s)) {          // séparateurs de milliers
            return s.replace(".", "").replace(" ", "").toLongOrNull() ?: 0L
        }
        s = s.replace(" ", "").replace(',', '.')
        return s.toDoubleOrNull()?.let { Math.round(it) } ?: 0L
    }

    // ------------------------------------------------------------------ SAUVEGARDE COMPLÈTE JSON

    fun exportFullBackupJson(context: Context, uri: Uri, articles: List<ArticleEntity>) {
        val dtos = articles.map { art ->
            BackupArticleDto(
                barcode = art.barcode,
                designation = art.designation,
                refProduit = art.refProduit,
                price = art.price,
                oldPrice = art.oldPrice,
                rayon = art.rayon,
                categorie = art.categorie,
                marque = art.marque,
                stock = art.stock,
                photoBase64 = ImageAndVisualSearchHelper.fileToBase64(art.photoPath),
                visualHash = art.visualHash,
                avgRgb = art.avgRgb
            )
        }
        val json = Gson().toJson(dtos)
        openOut(context, uri).use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { writer -> writer.write(json) }
        }
    }

    fun restoreFullBackupJson(context: Context, uri: Uri): List<ArticleEntity> {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw ImportFormatException("Impossible de lire le fichier de sauvegarde.")
        val type = object : TypeToken<List<BackupArticleDto>>() {}.type
        val parsed: List<BackupArticleDto>? = try {
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            throw ImportFormatException("Ce fichier n'est pas une sauvegarde GestionMagasin valide.")
        }
        val dtos: List<BackupArticleDto> = parsed ?: emptyList()

        return dtos.filter { it.barcode.isNotBlank() }.map { dto ->
            val restoredPath = ImageAndVisualSearchHelper.base64ToInternalFile(context, dto.photoBase64, dto.barcode)
            ArticleEntity(
                barcode = dto.barcode,
                designation = dto.designation.uppercase(Locale.ROOT),
                refProduit = dto.refProduit.uppercase(Locale.ROOT),
                price = dto.price,
                oldPrice = dto.oldPrice,
                rayon = dto.rayon.uppercase(Locale.ROOT),
                categorie = dto.categorie.uppercase(Locale.ROOT),
                marque = dto.marque.uppercase(Locale.ROOT),
                stock = dto.stock,
                photoPath = restoredPath,
                visualHash = dto.visualHash,
                avgRgb = dto.avgRgb
            )
        }
    }
}
