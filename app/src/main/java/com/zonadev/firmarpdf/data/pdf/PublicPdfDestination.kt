package com.zonadev.firmarpdf.data.pdf

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** Solo posee salidas creadas aquí; nunca acepta un destino ni un fichero de origen. */
internal class PublicPdfDestination private constructor(
    private val context: Context,
    private val resolver: ContentResolver,
    var uri: Uri,
    var name: String,
    private val pendingFile: File? = null,
) {
    private var published = false
    // Categoría fija para comparar mediciones; no identifica archivo, URI ni proveedor.
    val timingKind: String get() = if (pendingFile == null) "media-store" else "legacy-file"

    suspend fun publish(): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) throw IOException()
            published = true
            name = queryName(resolver, uri) ?: name
        } else {
            val pending = checkNotNull(pendingFile)
            var candidate = File(pending.parentFile, name)
            val stem = name.removeSuffix(".pdf")
            var suffix = 1
            while (!candidate.createNewFile()) candidate = File(pending.parentFile, "${stem}_${suffix++}.pdf")
            // Sustituye únicamente nuestra reserva vacía, nunca un archivo anterior.
            if (!pending.renameTo(candidate)) { candidate.delete(); throw IOException() }
            name = candidate.name
            uri = Uri.fromFile(candidate)
            published = true
            val indexed = try { withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Uri?> { continuation ->
                    MediaScannerConnection.scanFile(context, arrayOf(candidate.path), arrayOf("application/pdf")) { _, scanned ->
                        if (continuation.isActive) continuation.resume(scanned)
                    }
                }
            } } catch (_: Exception) { null }
            if (indexed != null) uri = indexed
        }
        return uri
    }

    fun discardUnpublished() {
        if (published) return
        if (pendingFile != null) pendingFile.delete()
        else runCatching { resolver.delete(uri, null, null) }
    }

    companion object {
        fun create(context: Context, sourceName: String?): PublicPdfDestination {
            val resolver = context.contentResolver
            val stem = sourceName.orEmpty().substringBeforeLast('.', sourceName.orEmpty())
                .replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().take(100).ifBlank { "documento" }
            val desired = "${stem}_signed.pdf"
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                var name = desired
                var suffix = 1
                fun exists(value: String): Boolean = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("Documents/FirmarPdf/", value), null)?.use { it.moveToFirst() } ?: false
                while (exists(name)) name = "${stem}_signed_${suffix++}.pdf"
                // MediaProvider reserva un fichero nuevo y resuelve también carreras con otras apps.
                val uri = resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/FirmarPdf/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: throw IOException()
                return PublicPdfDestination(context, resolver, uri, name)
            }
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FirmarPdf")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            val pending = File.createTempFile(".firmarpdf-", ".pending", directory)
            return PublicPdfDestination(context, resolver, Uri.fromFile(pending), desired, pending)
        }

        private fun queryName(resolver: ContentResolver, uri: Uri): String? = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
