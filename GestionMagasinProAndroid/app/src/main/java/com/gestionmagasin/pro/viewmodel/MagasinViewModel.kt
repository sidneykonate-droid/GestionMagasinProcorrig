package com.gestionmagasin.pro.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gestionmagasin.pro.data.AppDatabase
import com.gestionmagasin.pro.data.ArticleEntity
import com.gestionmagasin.pro.data.LabelPrintItem
import com.gestionmagasin.pro.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MagasinViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).articleDao()

    val allArticles = dao.getAllArticlesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // État du formulaire (Création OU Modification)
    var editingOriginalBarcode by mutableStateOf<String?>(null)
    var formBarcode by mutableStateOf("")
    var formDesignation by mutableStateOf("")
    var formRefProduit by mutableStateOf("")
    var formPrice by mutableStateOf("")
    var formOldPrice by mutableStateOf("")
    var formRayon by mutableStateOf("")
    var formCategorie by mutableStateOf("")
    var formMarque by mutableStateOf("")
    var formStock by mutableStateOf("1")
    var formPhotoPath by mutableStateOf("")
    var formVisualHash by mutableStateOf("")
    var formAvgRgb by mutableStateOf("")
    var lastOcrRawText by mutableStateOf("")
    private var unsavedPhotoPath: String? = null   // photo ajoutée au formulaire mais pas encore enregistrée

    // État de l'onglet Base de données (Recherche + Filtres + Sélection + Pagination + Suppression)
    var dbSearchQuery by mutableStateOf("")
    var selectedFilterType by mutableStateOf("TOUS")
    var selectedFilterValue by mutableStateOf("")
    var currentDbPage by mutableIntStateOf(1)
    val selectedDbBarcodes = mutableStateListOf<String>()
    var articlePendingDelete by mutableStateOf<ArticleEntity?>(null)
    var showBatchDeleteConfirm by mutableStateOf(false)

    // Aperçu détaillé d'un article (Photo + Infos + Actions rapides)
    var previewArticleForModal by mutableStateOf<ArticleEntity?>(null)

    // File d'impression & export Word RVOITURE
    val labelPrintQueue = mutableStateListOf<LabelPrintItem>()
    var printStartOffset by mutableIntStateOf(1)

    // Détection différence de prix lors d'un scan d'étiquette
    var pendingPriceDiff by mutableStateOf<Pair<ArticleEntity, Long>?>(null)

    // Recherche visuelle par photo
    var visualSearchMatches by mutableStateOf<List<VisualMatchResult>>(emptyList())
    var showVisualSearchModal by mutableStateOf(false)

    // Navigation contrôlée depuis le ViewModel (ex: Modifier -> bascule vers onglet Formulaire)
    var requestedTab by mutableIntStateOf(-1)

    var statusMessage by mutableStateOf<String?>(null)
    var isProcessing by mutableStateOf(false)

    val isEditing: Boolean
        get() = editingOriginalBarcode != null

    init {
        viewModelScope.launch {
            if (dao.getAllArticles().isEmpty()) {
                dao.insertOrUpdate(
                    ArticleEntity(
                        barcode = "351113",
                        designation = "VOITURE JOUET MINIATURE COLLECTION REF 351113",
                        refProduit = "351113",
                        price = 7800L,
                        rayon = "JOUETS",
                        categorie = "VOITURES",
                        marque = "ORCA",
                        stock = 10
                    )
                )
                dao.insertOrUpdate(
                    ArticleEntity(
                        barcode = "350803",
                        designation = "PUZZLE 108PCS 2EN1 DOUBLE FACE 2EN1 50X35CM AVENGER CA12 REF 99719",
                        refProduit = "99719",
                        price = 4975L,
                        rayon = "JOUETS",
                        categorie = "PUZZLES",
                        marque = "AVENGER",
                        stock = 12
                    )
                )
            }
        }
    }

    fun fillDemoExample() {
        editingOriginalBarcode = null
        formBarcode = "351113"
        formPrice = "7800"
        formOldPrice = ""
        formDesignation = "VOITURE JOUET MINIATURE COLLECTION REF 351113"
        formRefProduit = "351113"
        formRayon = "JOUETS"
        formCategorie = "VOITURES"
        formMarque = "ORCA"
        formStock = "10"
        statusMessage = "Exemple modèle ORCA DECO chargé (CB : 351113 - 7.800F)"
    }

    fun resetForm() {
        editingOriginalBarcode = null
        formBarcode = ""
        formDesignation = ""
        formRefProduit = ""
        formPrice = ""
        formOldPrice = ""
        formRayon = ""
        formCategorie = ""
        formMarque = ""
        formStock = "1"
        formPhotoPath = ""
        formVisualHash = ""
        formAvgRgb = ""
        lastOcrRawText = ""
    }

    fun startEditingArticle(article: ArticleEntity) {
        editingOriginalBarcode = article.barcode
        formBarcode = article.barcode
        formDesignation = article.designation
        formRefProduit = article.refProduit
        formPrice = if (article.price > 0L) article.price.toString() else ""
        formOldPrice = if (article.oldPrice > 0L) article.oldPrice.toString() else ""
        formRayon = article.rayon
        formCategorie = article.categorie
        formMarque = article.marque
        formStock = article.stock.toString()
        formPhotoPath = article.photoPath
        formVisualHash = article.visualHash
        formAvgRgb = article.avgRgb
        lastOcrRawText = ""
        previewArticleForModal = null
        showVisualSearchModal = false
        requestedTab = 0
        statusMessage = "Modification de l'article CB : ${article.barcode}"
    }

    fun cancelEditingArticle() {
        deleteFileQuietly(unsavedPhotoPath)
        unsavedPhotoPath = null
        resetForm()
        statusMessage = "Modification annulée."
    }

    private fun deleteFileQuietly(path: String?) {
        if (!path.isNullOrEmpty()) runCatching { File(path).delete() }
    }

    fun removePhotoFromForm() {
        if (formPhotoPath == unsavedPhotoPath) {
            deleteFileQuietly(unsavedPhotoPath)
            unsavedPhotoPath = null
        }
        formPhotoPath = ""
        formVisualHash = ""
        formAvgRgb = ""
        statusMessage = "Photo retirée du formulaire."
    }

    fun attachPhotoToForm(uri: Uri) {
        viewModelScope.launch {
            isProcessing = true
            val res = withContext(Dispatchers.IO) {
                ImageAndVisualSearchHelper.saveCompressedPhotoAndSignature(
                    getApplication(),
                    uri,
                    formBarcode.ifEmpty { "NEW" }
                )
            }
            if (res != null) {
                if (unsavedPhotoPath != null && unsavedPhotoPath != res.first) deleteFileQuietly(unsavedPhotoPath)
                unsavedPhotoPath = res.first
                formPhotoPath = res.first
                formVisualHash = res.second.dhash
                formAvgRgb = res.second.avgRgb
                statusMessage = "Photo ajoutée et optimisée pour l'article !"
            } else {
                statusMessage = "Erreur lors du chargement de la photo."
            }
            isProcessing = false
        }
    }

    fun scanLabelToFillForm(uri: Uri) {
        viewModelScope.launch {
            isProcessing = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    OcrAndLabelParser.extractAndParseFromUri(getApplication(), uri)
                }
                lastOcrRawText = parsed.rawText
                if (parsed.barcode.isNotEmpty() && !isEditing) {
                    val existing = dao.getArticleByBarcode(parsed.barcode)
                    if (existing != null) {
                        // L'article existe déjà en base : basculer automatiquement en mode modification !
                        editingOriginalBarcode = existing.barcode
                        formBarcode = existing.barcode
                        formDesignation = parsed.designation.ifEmpty { existing.designation }.uppercase(Locale.ROOT)
                        formRefProduit = parsed.refProduit.ifEmpty { existing.refProduit }.uppercase(Locale.ROOT)
                        formPrice = if (parsed.price > 0L) parsed.price.toString() else existing.price.toString()
                        formOldPrice = if (parsed.price > 0L && parsed.price != existing.price) existing.price.toString() else existing.oldPrice.toString()
                        formRayon = existing.rayon
                        formCategorie = existing.categorie
                        formMarque = existing.marque
                        formStock = existing.stock.toString()
                        formPhotoPath = existing.photoPath
                        formVisualHash = existing.visualHash
                        formAvgRgb = existing.avgRgb
                        statusMessage = "Article CB : ${existing.barcode} déjà en base — chargé pour modification !"
                        return@launch
                    }
                }
                if (parsed.barcode.isNotEmpty()) formBarcode = parsed.barcode
                if (parsed.price > 0L) formPrice = parsed.price.toString()
                if (parsed.designation.isNotEmpty()) formDesignation = parsed.designation.uppercase(Locale.ROOT)
                if (parsed.refProduit.isNotEmpty()) formRefProduit = parsed.refProduit.uppercase(Locale.ROOT)
                statusMessage = "Informations extraites de l'étiquette ! Vérifiez et enregistrez."
            } catch (e: Exception) {
                statusMessage = "Erreur OCR : ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun saveCurrentArticle() {
        val bc = formBarcode.trim()
        val des = formDesignation.trim().uppercase(Locale.ROOT)
        if (bc.isEmpty() || des.isEmpty()) {
            statusMessage = "Le code-barres et la Désignation sont obligatoires."
            return
        }
        val originalBc = editingOriginalBarcode
        viewModelScope.launch {
            val newPriceVal = formPrice.trim().toLongOrNull() ?: 0L
            val existingBefore = dao.getArticleByBarcode(originalBc ?: bc)
            val computedOldPrice = when {
                formOldPrice.trim().isNotEmpty() -> formOldPrice.trim().toLongOrNull() ?: 0L
                existingBefore != null && existingBefore.price > 0L && existingBefore.price != newPriceVal -> existingBefore.price
                else -> 0L
            }

            // Création : refuser un code-barres déjà présent (sinon l'article existant serait écrasé sans avertissement)
            if (originalBc == null && dao.getArticleByBarcode(bc) != null) {
                statusMessage = "Le code-barres $bc existe déjà en base : ouvrez l'article via « Modifier »."
                return@launch
            }

            // Si l'utilisateur a changé le code-barres lors de la modification, vérifier qu'il n'écrase pas un autre article
            if (originalBc != null && originalBc != bc) {
                val conflict = dao.getArticleByBarcode(bc)
                if (conflict != null) {
                    statusMessage = "Impossible : le code-barres $bc appartient déjà à un autre article."
                    return@launch
                }
                dao.deleteByBarcode(originalBc)
                selectedDbBarcodes.remove(originalBc)
            }

            val updatedArticle = ArticleEntity(
                barcode = bc,
                designation = des,
                refProduit = formRefProduit.trim().uppercase(Locale.ROOT),
                price = newPriceVal,
                oldPrice = computedOldPrice,
                rayon = formRayon.trim().uppercase(Locale.ROOT).ifEmpty { "GÉNÉRAL" },
                categorie = formCategorie.trim().uppercase(Locale.ROOT).ifEmpty { "GÉNÉRAL" },
                marque = formMarque.trim().uppercase(Locale.ROOT).ifEmpty { "ORCA" },
                stock = formStock.trim().toIntOrNull() ?: 1,
                photoPath = formPhotoPath,
                visualHash = formVisualHash,
                avgRgb = formAvgRgb,
                updatedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(updatedArticle)

            // Photo remplacée ou supprimée : nettoyer l'ancien fichier
            val oldPhoto = existingBefore?.photoPath.orEmpty()
            if (oldPhoto.isNotEmpty() && oldPhoto != updatedArticle.photoPath) {
                withContext(Dispatchers.IO) { deleteFileQuietly(oldPhoto) }
            }
            unsavedPhotoPath = null

            // Synchroniser également la file d'impression si l'article modifié s'y trouve
            val lookupBc = originalBc ?: bc
            val queueIdx = labelPrintQueue.indexOfFirst { it.article.barcode == lookupBc }
            if (queueIdx >= 0) {
                labelPrintQueue[queueIdx] = labelPrintQueue[queueIdx].copy(article = updatedArticle)
            }

            statusMessage = if (originalBc != null) {
                "Article CB : ${updatedArticle.barcode} modifié et mis à jour !"
            } else {
                "Article CB : ${updatedArticle.barcode} enregistré en base !"
            }
            resetForm()
            if (originalBc != null) {
                requestedTab = 1
            }
        }
    }

    fun adjustArticleStock(article: ArticleEntity, delta: Int) {
        viewModelScope.launch {
            val newStock = (article.stock + delta).coerceAtLeast(0)
            val updated = article.copy(stock = newStock, updatedAt = System.currentTimeMillis())
            dao.insertOrUpdate(updated)
            if (previewArticleForModal?.barcode == article.barcode) {
                previewArticleForModal = updated
            }
            statusMessage = "Stock ${article.barcode} : $newStock"
        }
    }

    fun confirmDeleteArticle(article: ArticleEntity) {
        viewModelScope.launch {
            if (article.photoPath.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    runCatching { File(article.photoPath).delete() }
                }
            }
            dao.delete(article)
            selectedDbBarcodes.remove(article.barcode)
            labelPrintQueue.removeAll { it.article.barcode == article.barcode }
            if (previewArticleForModal?.barcode == article.barcode) {
                previewArticleForModal = null
            }
            if (editingOriginalBarcode == article.barcode) {
                resetForm()
            }
            articlePendingDelete = null
            statusMessage = "Article CB : ${article.barcode} supprimé."
        }
    }

    fun confirmDeleteSelectedArticles() {
        val toDelete = selectedDbBarcodes.toList()
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            val all = dao.getAllArticles()
            withContext(Dispatchers.IO) {
                all.filter { it.barcode in toDelete && it.photoPath.isNotEmpty() }.forEach {
                    runCatching { File(it.photoPath).delete() }
                }
            }
            dao.deleteByBarcodes(toDelete)
            labelPrintQueue.removeAll { it.article.barcode in toDelete }
            selectedDbBarcodes.clear()
            showBatchDeleteConfirm = false
            statusMessage = "${toDelete.size} article(s) supprimé(s) de la base."
        }
    }

    fun scanLabelInDatabase(uri: Uri) {
        viewModelScope.launch {
            isProcessing = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    OcrAndLabelParser.extractAndParseFromUri(getApplication(), uri)
                }
                if (parsed.barcode.isEmpty()) {
                    statusMessage = "Aucun code-barres détecté sur cette étiquette."
                    return@launch
                }
                val existing = dao.getArticleByBarcode(parsed.barcode)
                if (existing != null) {
                    selectedFilterType = "TOUS"
                    selectedFilterValue = ""
                    dbSearchQuery = existing.barcode
                    previewArticleForModal = existing
                    statusMessage = "Article CB : ${existing.barcode} trouvé en base !"
                } else {
                    selectedFilterType = "TOUS"
                    selectedFilterValue = ""
                    dbSearchQuery = parsed.barcode
                    statusMessage = "Aucun article CB : ${parsed.barcode} trouvé en base."
                }
            } catch (e: Exception) {
                statusMessage = "Erreur scan : ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun scanLabelInPrintQueue(uri: Uri) {
        viewModelScope.launch {
            isProcessing = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    OcrAndLabelParser.extractAndParseFromUri(getApplication(), uri)
                }
                if (parsed.barcode.isEmpty()) {
                    statusMessage = "Aucun code-barres détecté sur cette photo d'étiquette."
                    return@launch
                }

                val existing = dao.getArticleByBarcode(parsed.barcode)
                if (existing != null) {
                    if (parsed.price > 0L && parsed.price != existing.price) {
                        pendingPriceDiff = Pair(existing, parsed.price)
                    } else {
                        enqueueForPrint(existing, 1)
                        statusMessage = "Article CB : ${existing.barcode} trouvé en base et ajouté à l'impression !"
                    }
                } else {
                    val created = ArticleEntity(
                        barcode = parsed.barcode,
                        designation = parsed.designation.ifEmpty { "ARTICLE CB : ${parsed.barcode}" }.uppercase(Locale.ROOT),
                        refProduit = parsed.refProduit.uppercase(Locale.ROOT),
                        price = parsed.price,
                        rayon = "À CLASSER",
                        categorie = "GÉNÉRAL",
                        marque = "ORCA",
                        stock = 1
                    )
                    dao.insertOrUpdate(created)
                    enqueueForPrint(created, 1)
                    statusMessage = "Nouvel article CB : ${created.barcode} créé automatiquement et ajouté à l'impression !"
                }
            } catch (e: Exception) {
                statusMessage = "Erreur scan étiquette : ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun resolvePriceDiff(updatePriceInDb: Boolean) {
        val pair = pendingPriceDiff ?: return
        val (article, newPrice) = pair
        viewModelScope.launch {
            val target = if (updatePriceInDb) {
                val updated = article.copy(price = newPrice, oldPrice = article.price, updatedAt = System.currentTimeMillis())
                dao.insertOrUpdate(updated)
                updated
            } else {
                article
            }
            enqueueForPrint(target, 1)
            statusMessage = if (updatePriceInDb) "Prix mis à jour (${OcrAndLabelParser.formatOrcaLabelPrice(newPrice)}) et ajouté à l'impression !"
            else "Ancien prix conservé et ajouté à l'impression."
            pendingPriceDiff = null
        }
    }

    fun enqueueForPrint(article: ArticleEntity, qty: Int = 1) {
        val idx = labelPrintQueue.indexOfFirst { it.article.barcode == article.barcode }
        if (idx >= 0) {
            val current = labelPrintQueue[idx]
            labelPrintQueue[idx] = current.copy(article = article, quantity = current.quantity + qty)
        } else {
            labelPrintQueue.add(LabelPrintItem(article, qty))
        }
    }

    fun updatePrintQty(barcode: String, delta: Int) {
        val idx = labelPrintQueue.indexOfFirst { it.article.barcode == barcode }
        if (idx < 0) return
        val newQty = labelPrintQueue[idx].quantity + delta
        if (newQty <= 0) labelPrintQueue.removeAt(idx)
        else labelPrintQueue[idx] = labelPrintQueue[idx].copy(quantity = newQty)
    }

    fun removeFromPrintQueue(barcode: String) {
        labelPrintQueue.removeAll { it.article.barcode == barcode }
    }

    fun clearPrintQueue() {
        labelPrintQueue.clear()
        statusMessage = "File d'impression vidée."
    }

    fun searchArticleByPhoto(uri: Uri) {
        viewModelScope.launch {
            isProcessing = true
            try {
                val matches = withContext(Dispatchers.IO) {
                    val sig = ImageAndVisualSearchHelper.computeVisualSignatureFromUri(getApplication(), uri)
                        ?: return@withContext emptyList()
                    val articles = dao.getAllArticles()
                    ImageAndVisualSearchHelper.findMatchingArticlesByPhoto(sig, articles)
                }
                visualSearchMatches = matches
                showVisualSearchModal = true
            } finally {
                isProcessing = false
            }
        }
    }

    fun exportWordLabels(uri: Uri) {
        val expandedArticles = labelPrintQueue.flatMap { item ->
            List(item.quantity) { item.article }
        }
        if (expandedArticles.isEmpty()) {
            statusMessage = "Ajoutez au moins une étiquette avant d'exporter le fichier Word."
            return
        }
        viewModelScope.launch {
            isProcessing = true
            try {
                withContext(Dispatchers.IO) {
                    BarcodeAndPrintHelper.generateWordLabelsDocxToUri(
                        getApplication(),
                        expandedArticles,
                        uri,
                        printStartOffset
                    )
                }
                statusMessage = "Fichier Word RVOITURE (${expandedArticles.size} étiquette(s), sans cadres vides) exporté !"
            } catch (e: Exception) {
                statusMessage = "Erreur export Word RVOITURE : ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    /** Exécute une opération de fichier hors thread principal avec indicateur et message d'erreur lisible. */
    private fun runFileTask(successMessage: () -> String, errorPrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            isProcessing = true
            try {
                withContext(Dispatchers.IO) { block() }
                statusMessage = successMessage()
            } catch (e: ImportFormatException) {
                statusMessage = e.message
            } catch (e: Exception) {
                statusMessage = "$errorPrefix : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun exportExcel(uri: Uri) = runFileTask({ "Export Excel (.xlsx) enregistré !" }, "Erreur export Excel") {
        ImportExportBackupHelper.exportToExcel(getApplication(), uri, dao.getAllArticles())
    }

    fun exportWord(uri: Uri) = runFileTask({ "Export Word (.docx) enregistré !" }, "Erreur export Word") {
        ImportExportBackupHelper.exportToWord(getApplication(), uri, dao.getAllArticles())
    }

    fun exportPdf(uri: Uri) = runFileTask({ "Export PDF (.pdf) enregistré !" }, "Erreur export PDF") {
        ImportExportBackupHelper.exportToPdf(getApplication(), uri, dao.getAllArticles())
    }

    fun importStructuredDocument(uri: Uri, fileName: String) {
        var count = 0
        runFileTask({ if (count == 0) "Aucun article exploitable dans ce fichier." else "$count article(s) importé(s) !" }, "Erreur import") {
            val items = ImportExportBackupHelper.importFromDocument(getApplication(), uri, fileName)
            // Conserver photo, signature visuelle et ancien prix des articles déjà en base
            val merged = items.map { imp ->
                val old = dao.getArticleByBarcode(imp.barcode)
                if (old == null) imp else imp.copy(
                    photoPath = old.photoPath,
                    visualHash = old.visualHash,
                    avgRgb = old.avgRgb,
                    oldPrice = if (imp.price != old.price && old.price > 0L) old.price else old.oldPrice
                )
            }
            dao.insertAll(merged)
            count = merged.size
        }
    }

    fun exportFullBackup(uri: Uri) = runFileTask({ "Sauvegarde locale complète enregistrée !" }, "Erreur sauvegarde") {
        ImportExportBackupHelper.exportFullBackupJson(getApplication(), uri, dao.getAllArticles())
    }

    fun restoreFullBackup(uri: Uri) {
        var count = 0
        runFileTask({ "Restauration réussie : $count article(s) restaurés !" }, "Erreur restauration") {
            val items = ImportExportBackupHelper.restoreFullBackupJson(getApplication(), uri)
            dao.insertAll(items)
            count = items.size
        }
    }
}
