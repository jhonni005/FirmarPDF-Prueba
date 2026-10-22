package com.zonadev.firmarpdf.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SignedPdfDao {
    @Query("SELECT * FROM signed_pdfs ORDER BY signedAt DESC, id DESC")
    fun observeResults(): Flow<List<SignedPdfEntity>>

    // Solo resultados nuevos. Un URI repetido se rechaza; no se borra una fila anterior.
    @Insert
    suspend fun insert(result: SignedPdfEntity): Long
}
