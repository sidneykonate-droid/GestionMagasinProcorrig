package com.gestionmagasin.pro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import com.gestionmagasin.pro.data.ArticleEntity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VisualSignature(
    val dhash: String,
    val avgRgb: String
)

data class VisualMatchResult(
    val article: ArticleEntity,
    val similarityPercent: Int
)

object ImageAndVisualSearchHelper {

    fun createTempCameraUri(context: Context): Uri {
        val cacheDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun saveCompressedPhotoAndSignature(context: Context, sourceUri: Uri, barcodeHint: String): Pair<String, VisualSignature>? {
        return try {
            val original = decodeUpright(context, sourceUri, 1600) ?: return null
            val resized = resizeBitmap(original, 800)
            val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
            val outFile = File(photosDir, "${safeFileName(barcodeHint)}_${System.currentTimeMillis()}.jpg")

            FileOutputStream(outFile).use { fos ->
                resized.compress(Bitmap.CompressFormat.JPEG, 78, fos)
            }
            Pair(outFile.absolutePath, computeVisualSignature(resized))
        } catch (e: Exception) {
            null
        }
    }

    fun computeVisualSignatureFromUri(context: Context, sourceUri: Uri): VisualSignature? {
        return try {
            decodeUpright(context, sourceUri, 800)?.let { computeVisualSignature(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun safeFileName(hint: String): String =
        hint.replace(Regex("[^A-Za-z0-9]"), "_").ifEmpty { "art" }

    /**
     * Décode l'image en limitant sa taille (évite le plantage mémoire sur les photos 12-50 Mpx) et la remet
     * à l'endroit selon l'orientation EXIF (sinon les photos de l'appareil s'enregistrent couchées).
     */
    private fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    fun computeVisualSignature(bitmap: Bitmap): VisualSignature {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val sb = StringBuilder(64)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pLeft = scaled.getPixel(x, y)
                val pRight = scaled.getPixel(x + 1, y)

                val lumLeft = 0.299 * Color.red(pLeft) + 0.587 * Color.green(pLeft) + 0.114 * Color.blue(pLeft)
                val lumRight = 0.299 * Color.red(pRight) + 0.587 * Color.green(pRight) + 0.114 * Color.blue(pRight)

                sb.append(if (lumLeft > lumRight) '1' else '0')

                rSum += Color.red(pLeft)
                gSum += Color.green(pLeft)
                bSum += Color.blue(pLeft)
            }
        }
        val avgR = (rSum / 64).toInt()
        val avgG = (gSum / 64).toInt()
        val avgB = (bSum / 64).toInt()
        return VisualSignature(dhash = sb.toString(), avgRgb = "$avgR,$avgG,$avgB")
    }

    fun findMatchingArticlesByPhoto(querySig: VisualSignature, articles: List<ArticleEntity>): List<VisualMatchResult> {
        return articles.mapNotNull { art ->
            var sigHash = art.visualHash
            var sigRgb = art.avgRgb

            if (sigHash.length != 64 && art.photoPath.isNotEmpty() && File(art.photoPath).exists()) {
                val bmp = BitmapFactory.decodeFile(art.photoPath)
                if (bmp != null) {
                    val computed = computeVisualSignature(bmp)
                    sigHash = computed.dhash
                    sigRgb = computed.avgRgb
                }
            }

            if (sigHash.length != 64) return@mapNotNull null

            val score = computeSimilarityScore(querySig, VisualSignature(sigHash, sigRgb))
            VisualMatchResult(art, score)
        }.sortedByDescending { it.similarityPercent }.take(5)
    }

    private fun computeSimilarityScore(sigA: VisualSignature, sigB: VisualSignature): Int {
        var diffBits = 0
        for (i in 0 until 64) {
            if (sigA.dhash[i] != sigB.dhash[i]) diffBits++
        }
        val structureScore = ((64.0 - diffBits) / 64.0) * 100.0

        val rgbA = sigA.avgRgb.split(",").mapNotNull { it.toIntOrNull() }
        val rgbB = sigB.avgRgb.split(",").mapNotNull { it.toIntOrNull() }
        val colorScore = if (rgbA.size == 3 && rgbB.size == 3) {
            val dr = (rgbA[0] - rgbB[0]).toDouble()
            val dg = (rgbA[1] - rgbB[1]).toDouble()
            val db = (rgbA[2] - rgbB[2]).toDouble()
            val dist = sqrt(dr * dr + dg * dg + db * db)
            max(0.0, (1.0 - dist / 441.67) * 100.0)
        } else {
            structureScore
        }

        return (structureScore * 0.65 + colorScore * 0.35).roundToInt().coerceIn(0, 100)
    }

    private fun resizeBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = w.toFloat() / h.toFloat()
        val newW: Int
        val newH: Int
        if (w > h) {
            newW = maxDim
            newH = (maxDim / ratio).roundToInt()
        } else {
            newH = maxDim
            newW = (maxDim * ratio).roundToInt()
        }
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    fun fileToBase64(path: String): String {
        if (path.isEmpty()) return ""
        val f = File(path)
        if (!f.exists()) return ""
        return Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    fun base64ToInternalFile(context: Context, base64: String, barcode: String): String {
        if (base64.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
            val outFile = File(photosDir, "${safeFileName(barcode)}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath
        } catch (e: Exception) {
            ""
        }
    }
}
