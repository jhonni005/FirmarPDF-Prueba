package com.zonadev.firmarpdf.data.pdf

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement
import com.zonadev.firmarpdf.data.signature.SavedSignatureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Siempre produce otro archivo. No abre el origen en escritura ni recibe una ruta de salida. */
class PdfExporter(
    private val context: Context,
    private val signatures: SavedSignatureStore,
    private val reader: SelectedPdfReader,
    private val history: PdfExportHistory,
) {
    private val mutex = Mutex()
    private val resolver get() = context.contentResolver

    suspend fun export(source: Uri, draft: PdfExportDraft, progress: (PdfExportStage) -> Unit): PdfExportResult = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw PdfExportException(PdfExportError.BUSY)
        val start = SystemClock.elapsedRealtime()
        val elapsed = LongArray(PdfExportStage.entries.size)
        var stage = PdfExportStage.SOURCE
        var inputSize = -1L
        var outputSize = -1L
        var success = false
        var saveCallMs = 0L
        var historyMs = 0L
        var destination: PublicPdfDestination? = null
        var document: PDDocument? = null
        var input: InputStream? = null
        val jobContext = coroutineContext
        suspend fun <T> measured(next: PdfExportStage, block: suspend () -> T): T {
            stage = next
            progress(next)
            val clock = SystemClock.elapsedRealtime()
            return try { block() } finally { elapsed[next.ordinal] += SystemClock.elapsedRealtime() - clock }
        }
        try {
            require(draft.hasAnnotations)
            var name: String? = null
            measured(PdfExportStage.SOURCE) {
                name = reader.metadata(source).displayName
                inputSize = runCatching {
                    resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                    } ?: -1L
                }.getOrDefault(-1L)
                input = resolver.openInputStream(source) ?: throw PdfExportException(PdfExportError.SOURCE)
            }
            val doc = measured(PdfExportStage.LOAD) {
                checkNotNull(input).use { raw ->
                    abortable(raw) { PDDocument.load(cancellableInput(raw, jobContext), memory()).also { document = it } }
                }.also { input = null }
            }
            ensureActive()
            val permissions = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("Perms")) as? COSDictionary
            if (doc.signatureDictionaries.isNotEmpty() || permissions?.containsKey(COSName.getPDFName("DocMDP")) == true) {
                throw PdfExportException(PdfExportError.CERTIFIED)
            }
            if (doc.isEncrypted || !doc.currentAccessPermission.canModify()) throw PdfExportException(PdfExportError.PROTECTED)
            if (doc.numberOfPages <= 0 || doc.numberOfPages != draft.expectedPageCount) throw PdfExportException(PdfExportError.INVALID)
            val expectedPages = doc.numberOfPages
            measured(PdfExportStage.ANNOTATE) { append(doc, draft) }
            measured(PdfExportStage.SAVE) {
                ensureActive()
                val target = PublicPdfDestination.create(context, name)
                destination = target
                val raw = resolver.openOutputStream(target.uri, "w") ?: throw PdfExportException(PdfExportError.WRITE)
                raw.use {
                    abortable(it) {
                    val checked = object : FilterOutputStream(it) {
                        override fun write(b: Int) { jobContext.ensureActive(); out.write(b); outputSize = maxOf(0L, outputSize) + 1 }
                        override fun write(b: ByteArray, off: Int, len: Int) { jobContext.ensureActive(); out.write(b, off, len); outputSize = maxOf(0L, outputSize) + len }
                    }
                    val saveClock = SystemClock.elapsedRealtime()
                    try { doc.save(checked) } finally { saveCallMs = SystemClock.elapsedRealtime() - saveClock }
                    }
                }
                doc.close()
                document = null
            }
            measured(PdfExportStage.PUBLISH) {
                val target = checkNotNull(destination)
                if (outputSize <= 0L) throw PdfExportException(PdfExportError.VERIFY)
                (resolver.openInputStream(target.uri) ?: throw PdfExportException(PdfExportError.VERIFY)).use { raw ->
                    abortable(raw) { PDDocument.load(cancellableInput(raw, jobContext), memory()).use { verified ->
                        if (verified.numberOfPages != expectedPages) throw PdfExportException(PdfExportError.VERIFY)
                    } }
                }
                ensureActive()
                // Tras publicar, cancelar o fallar Room nunca debe borrar un resultado válido.
                withContext(NonCancellable) {
                    val uri = target.publish()
                    val published = PdfExportResult(uri, target.name, recorded = true)
                    val historyClock = SystemClock.elapsedRealtime()
                    val result = try { history.record(published); published
                    } catch (_: Exception) { published.copy(recorded = false)
                    } catch (_: OutOfMemoryError) { published.copy(recorded = false)
                    } finally { historyMs = SystemClock.elapsedRealtime() - historyClock }
                    success = true
                    result
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: PdfExportException) { throw failure
        } catch (_: InvalidPasswordException) { throw PdfExportException(PdfExportError.PROTECTED)
        } catch (_: OutOfMemoryError) { throw PdfExportException(PdfExportError.MEMORY)
        } catch (_: Exception) {
            jobContext.ensureActive()
            throw PdfExportException(when (stage) {
                PdfExportStage.SOURCE -> PdfExportError.SOURCE
                PdfExportStage.LOAD -> PdfExportError.INVALID
                PdfExportStage.ANNOTATE -> PdfExportError.ANNOTATIONS
                PdfExportStage.SAVE -> PdfExportError.WRITE
                PdfExportStage.PUBLISH -> PdfExportError.VERIFY
            })
        } finally {
            runCatching { input?.close() }
            runCatching { document?.close() }
            runCatching { destination?.discardUnpublished() }
            mutex.unlock()
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                Log.d("PdfExportTiming", "sourceMs=${elapsed[0]} loadMs=${elapsed[1]} annotateMs=${elapsed[2]} " +
                    "saveMs=$saveCallMs writeStageMs=${elapsed[3]} verifyPublishMs=${elapsed[4]-historyMs} historyMs=$historyMs totalMs=${SystemClock.elapsedRealtime()-start} " +
                    "inputBytes=$inputSize outputBytes=$outputSize access=stream success=$success")
            }
        }
    }

    private fun memory() = MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(context.cacheDir)

    /** Cerrar desde otro trabajador permite desbloquear también una lectura de proveedor al cancelar. */
    private suspend fun <T> abortable(stream: Closeable, block: () -> T): T = coroutineScope {
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
            }
        }
        try { block() } finally {
            closer.cancel()
            withContext(NonCancellable) { closer.join() }
        }
    }

    private fun cancellableInput(input: InputStream, job: CoroutineContext) = object : FilterInputStream(input) {
        override fun read(): Int { job.ensureActive(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { job.ensureActive(); return super.read(b, off, len) }
    }

    private suspend fun append(doc: PDDocument, draft: PdfExportDraft) {
        val images = mutableMapOf<String, PDImageXObject>()
        for (id in draft.signatures.map { it.signatureId }.distinct()) {
            coroutineContext.ensureActive()
            val bitmap = signatures.load(id) ?: throw PdfExportException(PdfExportError.ANNOTATIONS)
            try { images[id] = LosslessFactory.createFromImage(doc, bitmap) } finally { bitmap.recycle() }
        }
        val pages = (draft.signatures.map { it.pageIndex } + draft.dates.map { it.placement.pageIndex } + draft.strokes.map { it.pageIndex }).toSortedSet()
        for (index in pages) {
            coroutineContext.ensureActive()
            if (index !in 0 until doc.numberOfPages) throw PdfExportException(PdfExportError.INVALID)
            val page = doc.getPage(index)
            val crop = page.cropBox ?: page.mediaBox
            val geometry = PdfExportPage(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                content.addRect(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height)
                content.clip()
                // Mismo orden base que las capas visuales: dibujo debajo de firmas/fechas.
                for (stroke in draft.strokes.filter { it.pageIndex == index }) {
                    coroutineContext.ensureActive()
                    drawStroke(content, geometry, stroke)
                }
                fun drawImage(image: PDImageXObject, placement: PdfSignaturePlacement) {
                    val matrix = geometry.image(placement.x, placement.y, placement.width, placement.height)
                    content.drawImage(image, Matrix(matrix.a, matrix.b, matrix.c, matrix.d, matrix.e, matrix.f))
                }
                for (placement in draft.signatures.filter { it.pageIndex == index }) drawImage(checkNotNull(images[placement.signatureId]), placement)
                for (date in draft.dates.filter { it.placement.pageIndex == index }) {
                    val bitmap = date.image ?: throw PdfExportException(PdfExportError.ANNOTATIONS)
                    drawImage(LosslessFactory.createFromImage(doc, bitmap), date.placement)
                }
            }
        }
    }

    private fun drawStroke(content: PDPageContentStream, page: PdfExportPage, stroke: PdfExportStroke) {
        if (stroke.points.isEmpty()) return
        val width = stroke.width * page.visualWidth
        require(width.isFinite() && width > 0)
        content.saveGraphicsState()
        try {
            content.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                strokingAlphaConstant = Color.alpha(stroke.color) / 255f
                nonStrokingAlphaConstant = Color.alpha(stroke.color) / 255f
            })
            content.setStrokingColor(Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
            content.setNonStrokingColor(Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
            content.setLineWidth(width); content.setLineCapStyle(1); content.setLineJoinStyle(1)
            val first = stroke.points.first()
            val start = page.point(first.x, first.y)
            if (stroke.points.all { it == first }) {
                val r = width / 2f; val k = r * .55228475f; val x = start.x; val y = start.y
                content.moveTo(x+r, y)
                content.curveTo(x+r, y+k, x+k, y+r, x, y+r)
                content.curveTo(x-k, y+r, x-r, y+k, x-r, y)
                content.curveTo(x-r, y-k, x-k, y-r, x, y-r)
                content.curveTo(x+k, y-r, x+r, y-k, x+r, y)
                content.fill()
            } else {
                content.moveTo(start.x, start.y)
                stroke.points.drop(1).forEach { val point = page.point(it.x, it.y); content.lineTo(point.x, point.y) }
                content.stroke()
            }
        } finally { content.restoreGraphicsState() }
    }
}
