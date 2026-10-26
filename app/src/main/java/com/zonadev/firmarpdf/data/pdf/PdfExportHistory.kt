package com.zonadev.firmarpdf.data.pdf

import androidx.room.withTransaction
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfEntity
import com.zonadev.firmarpdf.data.signedpdf.ResultKind
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabase
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfEntity

/** Archivo publicado primero; estas dos filas se confirman juntas sin cambiar el esquema v2. */
class PdfExportHistory(private val database: SignedPdfDatabase, private val reader: SelectedPdfReader) {
    suspend fun record(result: PdfExportResult) {
        val metadata = reader.metadata(result.uri)
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.signedPdfDao().insert(SignedPdfEntity(
                resultUri = result.uri.toString(), displayName = result.displayName,
                signedAt = now, resultKind = ResultKind.NEW_DOCUMENT,
            ))
            database.recentPdfDao().recordOpened(RecentPdfEntity(
                result.uri.toString(), result.displayName, metadata.modifiedAt, now,
            ))
        }
    }
}
