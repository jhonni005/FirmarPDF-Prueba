package com.zonadev.firmarpdf.data.recentpdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.zonadev.firmarpdf.data.pdf.PdfPreviewSession
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Un documento por vez; solo la primera página y PNG pequeños en caché privada descartable. */
class PdfThumbnailCache(private val directory: File, private val resolver: ContentResolver, private val core: () -> PdfiumCore) {
    private val mutex = Mutex()

    suspend fun load(pdf: RecentPdfEntity): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (pdf.unavailable) return@withLock null
            var session: PdfPreviewSession? = null
            var temporary: File? = null
            try {
                if (!directory.isDirectory && !directory.mkdirs()) return@withLock null
                val key = "${pdf.uri}:${pdf.documentModifiedAt ?: pdf.lastOpenedAt}"
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = File(directory, "$hash.png")
                if (file.isFile) BitmapFactory.decodeFile(file.path)?.let { return@withLock it }
                session = PdfPreviewSession.open(resolver, core, Uri.parse(pdf.uri))
                val page = session.renderPage(0, 180)
                val factor = minOf(1f, 180f / maxOf(page.width, page.height))
                val small = Bitmap.createScaledBitmap(page, (page.width * factor).roundToInt().coerceAtLeast(1), (page.height * factor).roundToInt().coerceAtLeast(1), true)
                try {
                    val pending = File.createTempFile("thumb-", ".tmp", directory)
                    temporary = pending
                    pending.outputStream().use { check(small.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    check(pending.renameTo(file))
                } finally {
                    if (small !== page) small.recycle()
                }
                // Caché técnica de 8 MiB, independiente del número de recientes.
                var bytes = directory.listFiles()?.sumOf { it.length() } ?: 0L
                directory.listFiles()?.filter { it != file }?.sortedBy { it.lastModified() }?.forEach {
                    val size = it.length()
                    if (bytes > 8L * 1024 * 1024 && it.delete()) bytes -= size
                }
                BitmapFactory.decodeFile(file.path)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: OutOfMemoryError) { null
            } catch (_: Exception) { null
            } finally {
                temporary?.delete()
                session?.close()
            }
        }
    }
}
