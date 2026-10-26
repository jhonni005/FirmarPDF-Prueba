package com.zonadev.firmarpdf.data.recentpdf

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentPdfDao {
    @Query("SELECT * FROM recent_pdfs ORDER BY lastOpenedAt DESC, uri ASC")
    fun observeRecents(): Flow<List<RecentPdfEntity>>

    @Upsert suspend fun upsert(pdf: RecentPdfEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(pdf: RecentPdfEntity): Long

    @Query("UPDATE recent_pdfs SET displayName = :name, documentModifiedAt = :modifiedAt, unavailable = 0 WHERE uri = :uri")
    suspend fun updateMetadata(uri: String, name: String, modifiedAt: Long?)

    /** El orden se asigna solo al insertar. Una reapertura nunca mueve una fila existente. */
    @Transaction
    suspend fun recordOpened(pdf: RecentPdfEntity) {
        val order = maxOf(pdf.lastOpenedAt, (lastOpenOrder() ?: 0L) + 1L)
        if (insertIfAbsent(pdf.copy(lastOpenedAt = order)) == -1L) {
            updateMetadata(pdf.uri, pdf.displayName, pdf.documentModifiedAt)
        }
    }

    @Query("SELECT MAX(lastOpenedAt) FROM recent_pdfs")
    suspend fun lastOpenOrder(): Long?

    @Query("UPDATE recent_pdfs SET unavailable = 1 WHERE uri = :uri")
    suspend fun markUnavailable(uri: String)

    @Query("DELETE FROM recent_pdfs WHERE uri = :uri")
    suspend fun remove(uri: String)
}
