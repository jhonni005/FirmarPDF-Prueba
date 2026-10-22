package com.zonadev.firmarpdf.data.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min
import kotlin.math.sqrt

enum class PdfPreviewError { INACCESSIBLE, INVALID, PROTECTED, MEMORY, RENDER }

class PdfPreviewException(val reason: PdfPreviewError) : Exception()

/** Una apertura de solo lectura. El documento PDFium es propietario del descriptor. */
class PdfPreviewSession private constructor(private val document: PdfDocument, val pageCount: Int) {
    private data class PageKey(val index: Int, val width: Int)
    private val mutex = Mutex()
    @Volatile private var closing = false
    private var resourcesClosed = false
    private val cache = object : LruCache<PageKey, Bitmap>(
        min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt(),
    ) {
        override fun sizeOf(key: PageKey, value: Bitmap) = value.allocationByteCount
        // No reciclar bitmaps publicados: Compose puede seguir dibujando una referencia.
    }

    suspend fun renderPage(index: Int, viewportWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureActive()
            if (closing) throw CancellationException()
            val key = PageKey(index, viewportWidth.coerceIn(1, 1440))
            cache.get(key)?.let { return@withLock it }
            var unpublished: Bitmap? = null
            try {
                document.openPage(index).use { page ->
                    val width = page.getPageWidthPoint()
                    val height = page.getPageHeightPoint()
                    if (width <= 0 || height <= 0) throw PdfPreviewException(PdfPreviewError.INVALID)
                    // Hasta 2 Mpx / unos 4 MiB por página, también para tamaños extremos.
                    val scale = min(
                        min(key.width.toDouble() / width, 4096.0 / height),
                        sqrt(2_097_152.0 / (width.toDouble() * height)),
                    )
                    val bitmap = Bitmap.createBitmap(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.RGB_565,
                    )
                    unpublished = bitmap
                    page.renderPageBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, true)
                    ensureActive()
                    if (closing) throw CancellationException()
                    cache.put(key, bitmap)
                    unpublished = null
                    bitmap
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PdfPreviewException) {
                throw failure
            } catch (_: OutOfMemoryError) {
                cache.evictAll()
                throw PdfPreviewException(PdfPreviewError.MEMORY)
            } catch (_: Exception) {
                throw PdfPreviewException(PdfPreviewError.RENDER)
            } finally {
                unpublished?.recycle()
            }
        }
    }

    fun requestClose() { closing = true }

    suspend fun close() {
        requestClose()
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (!resourcesClosed) {
                    resourcesClosed = true
                    cache.evictAll()
                    // Todos los PdfPage se cierran con use antes de llegar a este punto.
                    try {
                        runCatching { document.close() }
                    } finally {
                        // PDFium lo cierra normalmente; cubrir también un fallo al cerrar el nativo.
                        runCatching { document.parcelFileDescriptor?.close() }
                        document.parcelFileDescriptor = null
                    }
                }
            }
        }
    }

    companion object {
        /** Bloqueante: llamar solo desde el trabajo IO del ViewModel. No copia el origen. */
        fun open(resolver: ContentResolver, core: () -> PdfiumCore, uri: Uri): PdfPreviewSession {
            val descriptor = try {
                resolver.openFileDescriptor(uri, "r") ?: throw IOException()
            } catch (_: Exception) {
                throw PdfPreviewException(PdfPreviewError.INACCESSIBLE)
            }
            var document: PdfDocument? = null
            var transferred = false
            try {
                document = core().newDocument(descriptor)
                val count = document.getPageCount()
                if (count <= 0) throw PdfPreviewException(PdfPreviewError.INVALID)
                return PdfPreviewSession(document, count).also { transferred = true }
            } catch (failure: PdfPreviewException) {
                throw failure
            } catch (_: PdfPasswordException) {
                throw PdfPreviewException(PdfPreviewError.PROTECTED)
            } catch (_: OutOfMemoryError) {
                throw PdfPreviewException(PdfPreviewError.MEMORY)
            } catch (_: LinkageError) {
                throw PdfPreviewException(PdfPreviewError.RENDER)
            } catch (_: Exception) {
                throw PdfPreviewException(PdfPreviewError.INVALID)
            } finally {
                if (!transferred) {
                    if (document != null) runCatching { document.close() }
                    // También cubre la apertura nativa fallida, sin propietario PDFium.
                    runCatching { descriptor.close() }
                }
            }
        }
    }
}
