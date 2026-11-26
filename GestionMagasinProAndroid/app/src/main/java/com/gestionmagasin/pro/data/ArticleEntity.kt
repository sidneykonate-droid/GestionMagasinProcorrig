package com.gestionmagasin.pro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey
    val barcode: String,
    val designation: String,      // Toujours stocké en MAJUSCULES
    val refProduit: String,       // Ex: "99719"
    val price: Long,              // Prix en FCFA (ex: 7800 -> affiché "7.800F" sur l'étiquette)
    val oldPrice: Long = 0L,      // Optionnel : ancien prix barré
    val rayon: String,            // Ex: "JOUETS"
    val categorie: String,        // Ex: "VOITURES"
    val marque: String,           // Ex: "ORCA"
    val stock: Int = 1,
    val photoPath: String = "",   // Chemin interne de la photo compressée
    val visualHash: String = "",  // Empreinte visuelle dHash 64 bits pour recherche par photo
    val avgRgb: String = "",      // Couleur dominante "R,G,B" pour recherche par photo
    val updatedAt: Long = System.currentTimeMillis()
)


data class LabelPrintItem(
    val article: ArticleEntity,
    val quantity: Int = 1
)
