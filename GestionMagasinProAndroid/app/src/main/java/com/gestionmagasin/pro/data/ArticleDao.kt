package com.gestionmagasin.pro.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY updatedAt DESC")
    fun getAllArticlesFlow(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles ORDER BY updatedAt DESC")
    suspend fun getAllArticles(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE barcode = :barcode LIMIT 1")
    suspend fun getArticleByBarcode(barcode: String): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(article: ArticleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<ArticleEntity>)

    @Delete
    suspend fun delete(article: ArticleEntity)

    @Query("DELETE FROM articles WHERE barcode = :barcode")
    suspend fun deleteByBarcode(barcode: String)

    @Query("DELETE FROM articles WHERE barcode IN (:barcodes)")
    suspend fun deleteByBarcodes(barcodes: List<String>)

    @Query("DELETE FROM articles")
    suspend fun deleteAll()
}
