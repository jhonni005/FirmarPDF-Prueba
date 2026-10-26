package com.zonadev.firmarpdf.data.recentpdf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.zonadev.firmarpdf.data.pdf.SelectedPdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecentPdfRepository(
    private val dao: RecentPdfDao,
    private val resolver: ContentResolver,
    private val reader: SelectedPdfReader,
) {
    private val mutex = Mutex()
    val recents = dao.observeRecents()

    /** Llamar exclusivamente después de abrir correctamente el documento con PDFium. */
    suspend fun recordOpened(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val metadata = reader.metadata(uri)
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Algunos proveedores solo conceden lectura temporal.
            } catch (_: IllegalArgumentException) { }
            val order = maxOf(System.currentTimeMillis(), (dao.lastOpenOrder() ?: 0L) + 1L)
            dao.upsert(RecentPdfEntity(uri.toString(), metadata.displayName.orEmpty(), metadata.modifiedAt, order))
        }
    }

    suspend fun markUnavailable(uri: Uri) = dao.markUnavailable(uri.toString())
    suspend fun remove(uri: String) = dao.remove(uri)
}
