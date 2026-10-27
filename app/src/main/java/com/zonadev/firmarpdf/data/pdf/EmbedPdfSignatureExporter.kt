package com.zonadev.firmarpdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.zonadev.firmarpdf.BuildConfig
import com.zonadev.firmarpdf.data.signature.SavedSignatureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/** Separate engine and a real single-thread executor, not Dispatchers.IO/limitedParallelism. */
class EmbedPdfSignatureExporter(
    private val context: Context,
    private val signatures: SavedSignatureStore,
    private val reader: SelectedPdfReader,
    private val history: PdfExportHistory,
    private val mutex: Mutex,
) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EmbedPDF-export").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val available: Boolean get() = EmbedPdfNativeBridge.available

    suspend fun export(source: Uri, draft: PdfExportDraft, progress: (PdfExportStage) -> Unit): PdfExportResult {
        val start = SystemClock.elapsedRealtimeNanos()
        return withContext(worker) {
            val native = LongArray(12) // Shared index contract documented alongside the native bridge.
            var sourceNanos = 0L
            var imageNanos = 0L
            var flushCloseNanos = 0L
            var publishNanos = 0L
            var historyNanos = 0L
            var locked = false
            var success = false
            var failure = "UNKNOWN"
            var stage = PdfExportStage.SOURCE
            var descriptor: ParcelFileDescriptor? = null
            var destination: PublicPdfDestination? = null
            var output: PdfSaveOutputStream? = null
            var outputBytes = -1L
            var inputBytes = -1L
            val images = mutableMapOf<String, Bitmap>()
            val placements = draft.signatures.sortedBy { it.pageIndex }
            val job = coroutineContext
            fun closeOutput() {
                val stream = output ?: return
                val clock = SystemClock.elapsedRealtimeNanos()
                try { stream.close() } finally {
                    flushCloseNanos += SystemClock.elapsedRealtimeNanos() - clock
                    outputBytes = stream.bytesWritten
                    output = null
                }
            }
            try {
                pdfiumDraftError(draft)?.let { throw PdfExportException(
                    if (it == PdfExportError.PDFIUM_SIGNATURES_ONLY) PdfExportError.EMBEDPDF_SIGNATURES_ONLY else it) }
                if (!available) throw PdfExportException(PdfExportError.EMBEDPDF_UNAVAILABLE)
                if (!mutex.tryLock()) throw PdfExportException(PdfExportError.BUSY)
                locked = true
                job.ensureActive()
                var sourceName: String? = null
                val sourceClock = SystemClock.elapsedRealtimeNanos()
                try {
                    progress(stage)
                    sourceName = reader.metadata(source).displayName
                    descriptor = context.contentResolver.openFileDescriptor(source, "r")
                        ?: throw PdfExportException(PdfExportError.SOURCE)
                    inputBytes = descriptor!!.statSize
                } finally { sourceNanos += SystemClock.elapsedRealtimeNanos() - sourceClock }
                stage = PdfExportStage.ANNOTATE
                val imageClock = SystemClock.elapsedRealtimeNanos()
                try {
                    for (id in placements.map { it.signatureId }.distinct()) {
                        job.ensureActive()
                        // The store's load suspends on its own IO dispatcher. No native handle exists
                        // yet. Assign ownership before honoring cancellation, then recycle in finally.
                        withContext(NonCancellable) {
                            val loaded = signatures.load(id) ?: throw PdfExportException(PdfExportError.ANNOTATIONS)
                            val image = if (loaded.config == Bitmap.Config.ARGB_8888) loaded else {
                                try { loaded.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: throw PdfExportException(PdfExportError.ANNOTATIONS)
                                } finally { loaded.recycle() }
                            }
                            try {
                                if (image.colorSpace?.isSrgb == false) throw PdfExportException(PdfExportError.ANNOTATIONS)
                                images[id] = image
                            } catch (error: Throwable) { image.recycle(); throw error }
                        }
                    }
                } finally { imageNanos += SystemClock.elapsedRealtimeNanos() - imageClock }
                job.ensureActive()
                val callbacks = object : EmbedPdfNativeCallbacks {
                    override fun checkActive() { job.ensureActive() }
                    override fun phase(value: Int) {
                        checkActive()
                        stage = when (value) { 0 -> PdfExportStage.LOAD; 1 -> PdfExportStage.ANNOTATE; else -> PdfExportStage.SAVE }
                        progress(stage)
                    }
                    override fun bitmap(index: Int) = checkNotNull(images[placements[index].signatureId])
                    override fun alphaMode(index: Int): Int = bitmap(index).let {
                        if (!it.hasAlpha()) 1 else if (it.isPremultiplied) 0 else 2
                    }
                    override fun matrix(index: Int, left: Float, bottom: Float, right: Float, top: Float, turns: Int): DoubleArray {
                        checkActive()
                        val m = pdfiumSignatureMatrix(placements[index], left, bottom, right, top, turns)
                        return doubleArrayOf(m.a.toDouble(), m.b.toDouble(), m.c.toDouble(), m.d.toDouble(), m.e.toDouble(), m.f.toDouble())
                    }
                    override fun beginOutput() {
                        checkActive()
                        // Invoked only AFTER the layer committed, before original+delta are written.
                        val target = PublicPdfDestination.create(context, sourceName)
                        destination = target
                        val raw = context.contentResolver.openOutputStream(target.uri, "w")
                            ?: throw PdfExportException(PdfExportError.WRITE)
                        try { output = PdfSaveOutputStream(raw, checkActive = { job.ensureActive() }) }
                        catch (error: Throwable) { runCatching { raw.close() }; throw error }
                    }
                    override fun writeBlock(data: ByteArray) {
                        checkActive()
                        (output ?: throw IOException("Output not opened")).write(data)
                    }
                }
                // A SINGLE synchronous call: thread init -> base/layer/transaction -> edit ->
                // original copy + delta -> native close/shutdown. No coroutine suspension inside.
                val code = EmbedPdfNativeBridge.export(checkNotNull(descriptor).fd, draft.expectedPageCount,
                    placements.map { it.pageIndex }.toIntArray(), callbacks, native)
                if (native[6] > 0) inputBytes = native[6]
                if (code != 0) throw PdfExportException(when (code) {
                    1 -> PdfExportError.SOURCE; 2 -> PdfExportError.PROTECTED; 3 -> PdfExportError.CERTIFIED
                    5 -> PdfExportError.ANNOTATIONS; 6 -> PdfExportError.WRITE; 7 -> PdfExportError.MEMORY
                    else -> PdfExportError.INVALID
                })
                job.ensureActive()
                closeOutput() // Must detect flush/close failure before publication or history.
                if (native[7] != inputBytes || native[8] <= 0 || inputBytes > Long.MAX_VALUE - native[8] ||
                    outputBytes != inputBytes + native[8]) throw PdfExportException(PdfExportError.VERIFY)
                descriptor?.close()
                descriptor = null
                job.ensureActive()
                stage = PdfExportStage.PUBLISH
                progress(stage)
                val target = checkNotNull(destination)
                val publishClock = SystemClock.elapsedRealtimeNanos()
                val uri = try { target.publish() } finally { publishNanos += SystemClock.elapsedRealtimeNanos() - publishClock }
                job.ensureActive()
                val published = PdfExportResult(uri, target.name, recorded = true)
                val historyClock = SystemClock.elapsedRealtimeNanos()
                val result = try {
                    try { history.record(published); published }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { published.copy(recorded = false) }
                    catch (_: OutOfMemoryError) { published.copy(recorded = false) }
                } finally { historyNanos += SystemClock.elapsedRealtimeNanos() - historyClock }
                success = true
                failure = "NONE"
                result
            } catch (cancelled: CancellationException) {
                failure = "CANCELLED"
                throw cancelled
            } catch (error: PdfExportException) {
                failure = error.reason.name
                throw error
            } catch (_: OutOfMemoryError) {
                failure = "MEMORY"
                throw PdfExportException(PdfExportError.MEMORY)
            } catch (_: LinkageError) {
                failure = "NATIVE_UNAVAILABLE"
                throw PdfExportException(PdfExportError.EMBEDPDF_UNAVAILABLE)
            } catch (_: Exception) {
                if (!job.isActive) { failure = "CANCELLED"; job.ensureActive() }
                val reason = when (stage) {
                    PdfExportStage.SOURCE -> PdfExportError.SOURCE
                    PdfExportStage.LOAD -> PdfExportError.INVALID
                    PdfExportStage.ANNOTATE -> PdfExportError.ANNOTATIONS
                    PdfExportStage.SAVE -> PdfExportError.WRITE
                    PdfExportStage.PUBLISH -> PdfExportError.VERIFY
                }
                failure = reason.name
                throw PdfExportException(reason)
            } finally {
                // This executes on the same worker, only after synchronous native cleanup finished.
                runCatching { closeOutput() }
                runCatching { descriptor?.close() }
                images.values.forEach { it.recycle() }
                runCatching { destination?.discardUnpublished() }
                if (locked) mutex.unlock()
                if (native[6] > 0) inputBytes = native[6]
                if (BuildConfig.DEBUG) Log.d("EmbedPdfExportTiming",
                    "engineRevision=${EmbedPdfNativeBridge.ENGINE_REVISION} openMs=${(sourceNanos + native[0]) / 1_000_000} " +
                        "imagesMs=${(imageNanos + native[10]) / 1_000_000} layerTransactionMs=${native[1] / 1_000_000} " +
                        "insertContentMs=${native[2] / 1_000_000} copyOriginalMs=${native[3] / 1_000_000} " +
                        "deltaSaveMs=${native[4] / 1_000_000} flushCloseMs=${flushCloseNanos / 1_000_000} " +
                        "nativeCloseMs=${native[5] / 1_000_000} publishMs=${publishNanos / 1_000_000} " +
                        "historyMs=${historyNanos / 1_000_000} totalMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000} " +
                        "inputBytes=$inputBytes copiedOriginalBytes=${native[7]} deltaBytes=${native[8]} outputBytes=$outputBytes " +
                        "success=$success failure=$failure destination=${destination?.timingKind ?: "unopened"}")
            }
        }
    }
}
