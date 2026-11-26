package com.gestionmagasin.pro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

data class ParsedLabelData(
    val barcode: String,
    val price: Long,
    val designation: String,
    val refProduit: String,
    val rawText: String
)

object OcrAndLabelParser {

    /**
     * Analyse une photo d'étiquette (même prise à la verticale ou tournée à 90°/270° comme le modèle fourni) :
     * 1) Lit directement les barres du code-barres via ML Kit BarcodeScanning
     * 2) Extrait le texte OCR (en testant automatiquement les rotations si l'étiquette est verticale)
     * 3) Applique les règles exactes du modèle d'étiquette scannée :
     *    - Code-barres : "* 3 5 0 8 0 3 *" (chiffres espacés entre astérisques) -> "350803"
     *    - Désignation : "PUZZLE 108PCS 2EN1 DOUBLE FACE 2EN1 50X35CM AVENGERS CA12 REF 99719"
     *    - Réf. produit : "99719" (même si le numéro est sur la ligne suivante après "REF")
     *    - Prix : "4 975 CFA" -> 4975
     */
    suspend fun extractAndParseFromUri(context: Context, imageUri: Uri): ParsedLabelData {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient()

        // 1. Lecture directe depuis l'URI (respecte l'orientation EXIF)
        val baseImage = InputImage.fromFilePath(context, imageUri)
        var detectedBarcode = ""
        try {
            val barcodes = barcodeScanner.process(baseImage).await()
            detectedBarcode = barcodes.firstOrNull()?.rawValue
                ?.replace("*", "")
                ?.replace(" ", "")
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {}

        var bestParsed = parseRawText(recognizer.process(baseImage).await().text, detectedBarcode)

        // 2. Si l'étiquette a été photographiée à la verticale (rotation 90° ou 270°), on teste les rotations
        if (bestParsed.barcode.isEmpty() || bestParsed.price == 0L || bestParsed.designation.length < 6) {
            val bmp = loadBitmapWithExif(context, imageUri)
            if (bmp != null) {
                for (angle in listOf(90f, 270f, 180f)) {
                    val rotated = rotateBitmap(bmp, angle)
                    val rotInput = InputImage.fromBitmap(rotated, 0)

                    if (detectedBarcode.isEmpty()) {
                        try {
                            val bcs = barcodeScanner.process(rotInput).await()
                            detectedBarcode = bcs.firstOrNull()?.rawValue
                                ?.replace("*", "")
                                ?.replace(" ", "")
                                ?.trim()
                                .orEmpty()
                        } catch (_: Exception) {}
                    }

                    val textRes = recognizer.process(rotInput).await().text
                    val candidate = parseRawText(textRes, detectedBarcode)
                    if (scoreParsed(candidate) > scoreParsed(bestParsed)) {
                        bestParsed = candidate
                    }
                    if (bestParsed.barcode.isNotEmpty() && bestParsed.price > 0L && bestParsed.designation.length >= 8) {
                        break
                    }
                }
            }
        }

        return bestParsed
    }

    private fun scoreParsed(p: ParsedLabelData): Int {
        var s = 0
        if (p.barcode.isNotEmpty()) s += 40
        if (p.price > 0L) s += 30
        if (p.designation.length >= 6) s += 20
        if (p.refProduit.isNotEmpty()) s += 10
        return s
    }

    /**
     * Règles de parsing calibrées sur le modèle d'étiquette scannée (1795554552753.jpg) :
     * - "* 3 5 0 8 0 3 *" -> barcode = "350803"
     * - "4 975 CFA" -> price = 4975
     * - "PUZZLE 108PCS 2EN1 DOUBLE FACE 2EN1 50X35CM AVENGERS CA12 REF 99719"
     *   -> designation = "PUZZLE 108PCS 2EN1 DOUBLE FACE 2EN1 50X35CM AVENGERS CA12 REF 99719"
     *   -> refProduit = "99719"
     */
    fun parseRawText(rawText: String, opticalBarcode: String = ""): ParsedLabelData {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val upperText = rawText.uppercase(Locale.ROOT)
        val joinedUpper = lines.joinToString(" ").uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        // 1. Code-barres : priorité au lecteur optique, sinon "* 3 5 0 8 0 3 *" (chiffres éventuellement espacés), sinon "CB : ..."
        var barcode = opticalBarcode.replace("*", "").replace(" ", "").trim()
        if (barcode.isEmpty()) {
            val starSpacedRegex = Regex("\\*\\s*([0-9A-Z](?:\\s*[0-9A-Z]){3,19})\\s*\\*")
            val cbRegex = Regex("CB\\s*[:.-]?\\s*([0-9A-Z](?:\\s*[0-9A-Z]){3,19})")
            val starMatch = starSpacedRegex.find(upperText)
            val cbMatch = cbRegex.find(upperText)

            if (starMatch != null) {
                barcode = starMatch.groupValues[1].replace("\\s+".toRegex(), "")
            } else if (cbMatch != null) {
                barcode = cbMatch.groupValues[1].replace("\\s+".toRegex(), "")
            } else {
                val numRegex = Regex("\\b(\\d{6,14})\\b")
                val numMatch = numRegex.find(upperText)
                if (numMatch != null) {
                    barcode = numMatch.groupValues[1]
                }
            }
        }

        // 2. Prix : "4 975 CFA", "7.800F", "29.500 FCFA"
        var price = 0L
        val priceCurrencyRegex = Regex("(\\d{1,3}(?:[\\s.]\\d{3})+|\\d{3,6})\\s*(?:FCFA|CFA|F\\b)")
        val priceCurrencyMatch = priceCurrencyRegex.find(upperText)
        if (priceCurrencyMatch != null) {
            price = priceCurrencyMatch.groupValues[1]
                .replace(" ", "")
                .replace(".", "")
                .toLongOrNull() ?: 0L
        } else {
            val spacedPriceRegex = Regex("\\b(\\d{1,3}[\\s.]\\d{3})\\b")
            val spacedMatch = spacedPriceRegex.find(upperText)
            if (spacedMatch != null) {
                price = spacedMatch.groupValues[1]
                    .replace(" ", "")
                    .replace(".", "")
                    .toLongOrNull() ?: 0L
            }
        }

        // 3. Désignation complète en MAJUSCULES (on retire la ligne du prix "4 975 CFA" et la ligne "* 3 5 0 8 0 3 *")
        val starLineRegex = Regex("^\\*.*\\*$")
        val cbLineRegex = Regex("^CB\\s*[:.-]?.*$")
        val purePriceLineRegex = Regex("^\\d{1,3}([\\s.]\\d{3})*\\s*(FCFA|CFA|F)?$")
        val descLines = lines.map { it.uppercase(Locale.ROOT) }.filter { line ->
            !line.matches(starLineRegex) &&
            !line.matches(cbLineRegex) &&
            !line.matches(purePriceLineRegex) &&
            !line.contains("ORCA DECO") &&
            (barcode.isEmpty() || line.replace(" ", "").replace("*", "") != barcode)
        }
        val designation = descLines.joinToString(" ").replace(Regex("\\s+"), " ").trim()

        // 4. Réf. produit située après "REF" (y compris si le code est passé à la ligne suivante comme "REF 99719")
        var refProduit = ""
        val refRegex = Regex("\\bREF[\\s.:#-]*([A-Z0-9-]{3,20})")
        val refMatch = refRegex.find(designation.ifEmpty { joinedUpper })
        if (refMatch != null) {
            refProduit = refMatch.groupValues[1].trim()
        }

        return ParsedLabelData(
            barcode = barcode,
            price = price,
            designation = designation,
            refProduit = refProduit,
            rawText = rawText
        )
    }

    private fun loadBitmapWithExif(context: Context, uri: Uri): Bitmap? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(input)
            input.close()
            if (bmp == null) return null

            val exifInput = context.contentResolver.openInputStream(uri)
            val orientation = if (exifInput != null) {
                val exif = ExifInterface(exifInput)
                val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                exifInput.close()
                o
            } else ExifInterface.ORIENTATION_NORMAL

            val rot = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) rotateBitmap(bmp, rot) else bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Formatage prix officiel modèle ORCA DECO à l'impression (RVOITURE.docx) :
     * Exemple : 4975 -> "4.975F", 7800 -> "7.800F", 29500 -> "29.500F"
     */
    fun formatOrcaLabelPrice(amount: Long): String {
        val symbols = DecimalFormatSymbols(Locale.FRANCE).apply {
            groupingSeparator = '.'
        }
        val df = DecimalFormat("#,###", symbols)
        return "${df.format(amount)}F"
    }

    fun formatFcfa(amount: Long): String {
        return String.format(Locale.FRANCE, "%,d FCFA", amount).replace('\u202F', ' ').replace('\u00A0', ' ')
    }
}
