package com.zonadev.firmarpdf.data.pdf

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.os.Environment
import android.content.Context
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
        if (uri.scheme == "file") {
            val file = uri.path?.let { java.io.File(it) }
            if (file?.isFile == true) return@withContext PdfMetadata(file.name, file.lastModified().takeIf { it > 0L })
        }
        fun column(name: String): String? = try {
            resolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(name)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
        } catch (_: RuntimeException) { null }
        // Consultas independientes: un proveedor puede admitir el nombre pero no last_modified.
        PdfMetadata(
            column(OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() },
            if (uri.authority == "media") column(MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()?.takeIf { it > 0L }?.times(1000L)
            else column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull()?.takeIf { it > 0L },
        )
    }

    /** Solo rutas relativas publicadas por MediaStore, nuestro fichero legado o la etiqueta del proveedor. */
    suspend fun location(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.authority == "media" && android.os.Build.VERSION.SDK_INT >= 29) {
            val relative = try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.let { path -> path + it.getString(1).orEmpty() } else null
                }
            } catch (_: Exception) { null }
            if (!relative.isNullOrBlank()) return@withContext relative
        }
        if (uri.scheme == "file") {
            @Suppress("DEPRECATION")
            val folder = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FirmarPdf").path + "/"
            val path = uri.path
            if (path != null && path.startsWith(folder)) return@withContext "Documents/FirmarPdf/" + path.removePrefix(folder)
        }
        try {
            uri.authority?.let { context.packageManager.resolveContentProvider(it, 0)?.loadLabel(context.packageManager)?.toString() }
        } catch (_: Exception) { null }
    }
}
