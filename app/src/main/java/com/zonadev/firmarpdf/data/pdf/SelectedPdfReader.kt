package com.zonadev.firmarpdf.data.pdf

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class SelectedPdf(val uri: Uri, val displayName: String?)

class SelectedPdfReader(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri): SelectedPdf = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Document stream unavailable")
        // Comprobar lectura y cerrar el flujo, sin copiar ni interpretar el PDF.
        stream.use { it.read() }
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) {
                    cursor.getString(column)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (_: RuntimeException) {
            // Un nombre opcional ausente no invalida un documento que se pudo leer.
            null
        }
        SelectedPdf(uri, name)
    }
}
