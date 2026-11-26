package com.gestionmagasin.pro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import com.gestionmagasin.pro.R
import com.gestionmagasin.pro.data.ArticleEntity
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.FileOutputStream

object BarcodeAndPrintHelper {

    private const val LABELS_PER_PAGE = RvoitureDocxBuilder.SLOTS_PER_PAGE // 2 colonnes x 7 lignes, comme RVOITURE.docx

    /** Génère un code-barres Code128 scannable (aperçu dans l'application). */
    fun generateBarcodeBitmap(content: String, width: Int = 600, height: Int = 180): Bitmap {
        val safeContent = content.ifBlank { "000000" }
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(safeContent, BarcodeFormat.CODE_128, width, height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Liste des emplacements A4 : `null` pour chacun des (départ - 1) premiers emplacements déjà utilisés. */
    private fun <T> slotted(items: List<T>, startOffset: Int): List<T?> =
        List(startOffset.coerceIn(1, LABELS_PER_PAGE) - 1) { null } + items

    // ------------------------------------------------------------------ IMPRESSION ANDROID

    /**
     * Impression directe (Wi-Fi / Bluetooth / « Enregistrer en PDF ») fidèle au modèle RVOITURE :
     * grille A4 de 14 étiquettes, avec « départ » (1 à 14) pour une feuille déjà entamée.
     * Seuls les emplacements utilisés sont dessinés.
     */
    fun printLabelsSheet(context: Context, articles: List<ArticleEntity>, startOffset: Int = 1) {
        if (articles.isEmpty()) return
        val slots = slotted(articles, startOffset)
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.logo_orca_deco)
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "Etiquettes_RVOITURE_ORCA_DECO"
        val pageCount = ((slots.size + LABELS_PER_PAGE - 1) / LABELS_PER_PAGE).coerceAtLeast(1)

        printManager.print(jobName, object : PrintDocumentAdapter() {
            private var attributes: PrintAttributes? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                attributes = newAttributes
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                val attrs = attributes
                if (attrs == null) {
                    callback.onWriteFailed("Mise en page indisponible")
                    return
                }
                val doc = PrintedPdfDocument(context, attrs)
                try {
                    for (pageIndex in 0 until pageCount) {
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onWriteCancelled()
                            return
                        }
                        val page = doc.startPage(pageIndex)
                        drawRvoiturePage(page.canvas, slots, pageIndex * LABELS_PER_PAGE, logo)
                        doc.finishPage(page)
                    }
                    FileOutputStream(destination.fileDescriptor).use { out -> doc.writeTo(out) }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } finally {
                    doc.close()
                }
            }
        }, null)
    }

    private fun drawRvoiturePage(canvas: Canvas, slots: List<ArticleEntity?>, startIndex: Int, logo: Bitmap?) {
        val marginX = 36f
        val marginY = 34f
        val cols = 2
        val rows = 7
        val gapX = 18f
        val gapY = 12f
        val cellW = (canvas.width - 2 * marginX - gapX) / cols
        val cellH = (canvas.height - 2 * marginY - (rows - 1) * gapY) / rows

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2F5597")
            strokeWidth = 1.6f
        }
        val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 31f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val cbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val logoFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E30613")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        for (i in 0 until LABELS_PER_PAGE) {
            val article = slots.getOrNull(startIndex + i) ?: continue

            val col = i % cols
            val row = i / cols
            val left = marginX + col * (cellW + gapX)
            val top = marginY + row * (cellH + gapY)

            canvas.drawRoundRect(RectF(left, top, left + cellW, top + cellH), 12f, 12f, borderPaint)

            // Prix : réduit si besoin pour toujours tenir dans le cadre (ex. « 135.000F »)
            val priceText = OcrAndLabelParser.formatOrcaLabelPrice(article.price)
            val maxPriceWidth = cellW - 16f
            pricePaint.textSize = 31f
            val measured = pricePaint.measureText(priceText)
            if (measured > maxPriceWidth) pricePaint.textSize = 31f * maxPriceWidth / measured
            canvas.drawText(priceText, left + cellW / 2f, top + cellH * 0.50f, pricePaint)

            val logoRect = RectF(left + cellW * 0.08f, top + cellH * 0.60f, left + cellW * 0.35f, top + cellH * 0.92f)
            if (logo != null) {
                canvas.drawBitmap(logo, null, logoRect, bitmapPaint)
            } else {
                canvas.drawText("ORCA DECO", logoRect.centerX(), logoRect.centerY() + 4f, logoFallbackPaint)
            }

            canvas.drawText("CB : ${article.barcode}", left + cellW * 0.38f, top + cellH * 0.78f, cbPaint)
        }
    }

    // ------------------------------------------------------------------ EXPORT WORD (RVOITURE.docx)

    /**
     * Génère le Word `Etiquettes_RVOITURE_ORCA.docx` à partir du modèle RVOITURE.docx des assets et l'écrit
     * vers l'URI choisie par l'utilisateur. Les cadres inutilisés (avant le départ A4 et après la dernière
     * étiquette) sont supprimés du fichier, ainsi que les pages vides.
     */
    fun generateWordLabelsDocxToUri(context: Context, articles: List<ArticleEntity>, outputUri: Uri, startOffset: Int = 1) {
        val labels = articles.map { LabelText(OcrAndLabelParser.formatOrcaLabelPrice(it.price), it.barcode) }
        val slots = slotted(labels, startOffset)
        val out = context.contentResolver.openOutputStream(outputUri, "wt")
            ?: throw IllegalStateException("Impossible d'écrire dans le fichier choisi.")
        context.assets.open("RVOITURE.docx").use { template ->
            out.use { RvoitureDocxBuilder.build(template, slots, it) }
        }
    }
}
