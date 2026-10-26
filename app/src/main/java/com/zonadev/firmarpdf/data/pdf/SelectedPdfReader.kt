package com.zonadev.firmarpdf.data.pdf

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class SelectedPdf(val uri: Uri, val displayName: String?)
data class PdfMetadata(val displayName: String?, val modifiedAt: Long?)

class SelectedPdfReader(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri): SelectedPdf = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Document stream unavailable")
        // Comprobar lectura y cerrar el flujo, sin copiar ni interpretar el PDF.
        stream.use { it.read() }
        SelectedPdf(uri, metadata(uri).displayName)
    }

    suspend fun metadata(uri: Uri): PdfMetadata = withContext(Dispatchers.IO) {
        fun column(name: String): String? = try {
            resolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(name)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
        } catch (_: RuntimeException) { null }
        // Consultas independientes: un proveedor puede admitir el nombre pero no last_modified.
        PdfMetadata(
            column(OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() },
            column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull()?.takeIf { it > 0L },
        )
    }
}
