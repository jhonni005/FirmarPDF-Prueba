package com.zonadev.firmarpdf.data.recentpdf

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentPdfDao {
    @Query("SELECT * FROM recent_pdfs ORDER BY lastOpenedAt DESC, uri ASC")
    fun observeRecents(): Flow<List<RecentPdfEntity>>

    @Upsert suspend fun upsert(pdf: RecentPdfEntity)

    @Query("SELECT MAX(lastOpenedAt) FROM recent_pdfs")
    suspend fun lastOpenOrder(): Long?

    @Query("UPDATE recent_pdfs SET unavailable = 1 WHERE uri = :uri")
    suspend fun markUnavailable(uri: String)

    @Query("DELETE FROM recent_pdfs WHERE uri = :uri")
    suspend fun remove(uri: String)
}
