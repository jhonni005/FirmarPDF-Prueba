package com.zonadev.firmarpdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.zonadev.firmarpdf.BuildConfig
import com.zonadev.firmarpdf.data.signature.SavedSignatureStore
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfSaveDiagnostics
import io.legere.pdfiumandroid.PdfWriteCallback
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Experimental, signatures only. Owns a read-only descriptor/document, independent of the viewer.
 * It never pre-copies the input, uses PDFBox, or reloads the generated PDF.
 */
class PdfiumSignatureExporter(
    private val context: Context,
    private val signatures: SavedSignatureStore,
    private val reader: SelectedPdfReader,
    private val history: PdfExportHistory,
    private val core: () -> PdfiumCore,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun export(
        source: Uri,
        draft: PdfExportDraft,
        saveMode: PdfiumSaveMode = PdfiumSaveMode.FULL,
        progress: (PdfExportStage) -> Unit,
    ): PdfExportResult = withContext(Dispatchers.IO) {
        pdfiumDraftError(draft)?.let { throw PdfExportException(it) }
        if (!mutex.tryLock()) throw PdfExportException(PdfExportError.BUSY)
        val start = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
        val timings = LongArray(7) // Nanoseconds: open, images, insert, write stage, close, publish, history.
        var callbackCalls = 0L
        var callbackBytes = 0L
        var callbackSmallWrites = 0L
        var callbackNanos = 0L
        var callbackDuringSaveNanos = 0L
        var saveCallNanos = 0L
        var saveIo: PdfSaveIoMetrics? = null
        var nativeTiming: PdfSaveDiagnostics? = null
        var stage = PdfExportStage.SOURCE
        var category = "UNKNOWN"
        var success = false
        var inputBytes = -1L
        var outputBytes = -1L
        var descriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null
        var destination: PublicPdfDestination? = null
        val images = mutableMapOf<String, Bitmap>()
        val jobContext = coroutineContext

        suspend fun <T> measured(index: Int, block: suspend () -> T): T {
            if (!BuildConfig.DEBUG) return block()
            val clock = SystemClock.elapsedRealtimeNanos()
            return try { block() } finally { timings[index] += SystemClock.elapsedRealtimeNanos() - clock }
        }
        fun closeResources() {
            // Always close the descriptor even if native close fails.
            try { document?.close() } finally {
                document = null
                try { descriptor?.close() } finally {
                    descriptor = null
                    images.values.forEach { it.recycle() }
                    images.clear()
                }
            }
        }
        try {
            var sourceName: String? = null
            val doc = measured(0) {
                progress(PdfExportStage.SOURCE)
                sourceName = reader.metadata(source).displayName
                ensureActive()
                val fd = context.contentResolver.openFileDescriptor(source, "r")
                    ?: throw PdfExportException(PdfExportError.SOURCE)
                descriptor = fd
                inputBytes = fd.statSize
                stage = PdfExportStage.LOAD
                progress(stage)
                core().newDocument(fd).also {
                    document = it
                    ensureActive()
                    if (BuildConfig.DEBUG) it.setSaveDiagnosticsEnabled(true)
                    if (it.getPageCount() <= 0 || it.getPageCount() != draft.expectedPageCount)
                        throw PdfExportException(PdfExportError.INVALID)
                    when (it.getSignatureExportRestriction()) {
                        1 -> throw PdfExportException(PdfExportError.PROTECTED)
                        2 -> throw PdfExportException(PdfExportError.CERTIFIED)
                    }
                }
            }
            stage = PdfExportStage.ANNOTATE
            progress(stage)
            measured(1) {
                for (id in draft.signatures.map { it.signatureId }.distinct()) {
                    ensureActive()
                    val loaded = signatures.load(id) ?: throw PdfExportException(PdfExportError.ANNOTATIONS)
                    // Full PNG, no thumbnail. Own pixels: never borrow the viewer's bitmap.
                    val image = if (loaded.config == Bitmap.Config.ARGB_8888) loaded else {
                        try { loaded.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw PdfExportException(PdfExportError.ANNOTATIONS)
                        } finally { loaded.recycle() }
                    }
                    try { images[id] = image } catch (failure: Throwable) { image.recycle(); throw failure }
                }
            }
            measured(2) {
                for ((pageIndex, placements) in draft.signatures.groupBy { it.pageIndex }) {
                    ensureActive()
                    if (pageIndex !in 0 until draft.expectedPageCount) throw PdfExportException(PdfExportError.INVALID)
                    doc.openPage(pageIndex).use { page ->
                        val box = page.getPageBoundingBox()
                        val turns = page.getPageRotation()
                        for (placement in placements) {
                            ensureActive()
                            val matrix = pdfiumSignatureMatrix(placement, box.left, box.bottom, box.right, box.top, turns)
                            page.insertImage(checkNotNull(images[placement.signatureId]), matrix.a.toDouble(), matrix.b.toDouble(),
                                matrix.c.toDouble(), matrix.d.toDouble(), matrix.e.toDouble(), matrix.f.toDouble())
                        }
                        ensureActive()
                        page.generateContent() // Once per modified page, after all its images.
                    }
                }
            }
            stage = PdfExportStage.SAVE
            progress(stage)
            measured(3) {
                ensureActive()
                val target = PublicPdfDestination.create(context, sourceName)
                destination = target
                val raw = context.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw PdfExportException(PdfExportError.WRITE)
                raw.use {
                    abortable(it) {
                        val checked = PdfSaveOutputStream(it, checkActive = { jobContext.ensureActive() },
                            debugNanoTime = if (BuildConfig.DEBUG) ({ SystemClock.elapsedRealtimeNanos() }) else null)
                        try {
                            checked.use { output ->
                                var callbackFailure: Throwable? = null
                                val callback = object : PdfWriteCallback {
                                    override fun WriteBlock(data: ByteArray?): Int {
                                        val clock = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
                                        if (BuildConfig.DEBUG) {
                                            callbackCalls++
                                            callbackBytes += data?.size?.toLong() ?: 0L
                                            if (data != null && data.size < 1024) callbackSmallWrites++
                                        }
                                        try {
                                            if (callbackFailure != null) return 0
                                            return try {
                                                jobContext.ensureActive()
                                                output.write(data ?: throw IOException("Missing save block"))
                                                1
                                            } catch (failure: Throwable) {
                                                callbackFailure = failure
                                                0 // Never let a failed write become a successful PDFium callback.
                                            }
                                        } finally {
                                            if (BuildConfig.DEBUG) callbackNanos += SystemClock.elapsedRealtimeNanos() - clock
                                        }
                                    }
                                }
                                val saveClock = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
                                val callbacksBeforeSave = callbackNanos
                                val saved = try {
                                    doc.saveAsCopy(callback, saveMode.saveFlags)
                                } finally {
                                    if (BuildConfig.DEBUG) {
                                        // Capture only callbacks inside this call; the final buffer drain is later.
                                        saveCallNanos += SystemClock.elapsedRealtimeNanos() - saveClock
                                        callbackDuringSaveNanos += callbackNanos - callbacksBeforeSave
                                        nativeTiming = doc.lastSaveDiagnostics
                                    }
                                }
                                callbackFailure?.let { throw it }
                                if (!saved) throw PdfExportException(PdfExportError.WRITE)
                                jobContext.ensureActive()
                            } // Flushes the 64 KiB buffer and closes the sink, also detecting late failures.
                        } finally {
                            outputBytes = checked.bytesWritten
                            // checked.use has already attempted its final flush/close, even on failure.
                            if (BuildConfig.DEBUG) saveIo = checked.metrics()
                        }
                    }
                }
            }
            measured(4) { closeResources() }
            ensureActive()
            if (outputBytes <= 0L) throw PdfExportException(PdfExportError.VERIFY)
            stage = PdfExportStage.PUBLISH
            progress(stage)
            // Publication and history are completed together. Never delete an already published PDF.
            withContext(NonCancellable) {
                val target = checkNotNull(destination)
                val uri = measured(5) { target.publish() }
                val published = PdfExportResult(uri, target.name, recorded = true)
                val result = measured(6) {
                    try { history.record(published); published
                    } catch (_: Exception) { published.copy(recorded = false)
                    } catch (_: OutOfMemoryError) { published.copy(recorded = false) }
                }
                success = true
                category = "NONE"
                result
            }
        } catch (cancelled: CancellationException) {
            category = "CANCELLED"
            throw cancelled
        } catch (failure: PdfExportException) {
            category = failure.reason.name
            throw failure
        } catch (_: PdfPasswordException) {
            category = "PROTECTED"
            throw PdfExportException(PdfExportError.PROTECTED)
        } catch (_: OutOfMemoryError) {
            category = "MEMORY"
            throw PdfExportException(PdfExportError.MEMORY)
        } catch (_: LinkageError) {
            category = "NATIVE"
            throw PdfExportException(PdfExportError.INVALID)
        } catch (_: Exception) {
            if (!jobContext.isActive) { category = "CANCELLED"; jobContext.ensureActive() }
            val error = when (stage) {
                PdfExportStage.SOURCE -> PdfExportError.SOURCE
                PdfExportStage.LOAD -> PdfExportError.INVALID
                PdfExportStage.ANNOTATE -> PdfExportError.ANNOTATIONS
                PdfExportStage.SAVE -> PdfExportError.WRITE
                PdfExportStage.PUBLISH -> PdfExportError.VERIFY
            }
            category = error.name
            throw PdfExportException(error)
        } finally {
            runCatching { closeResources() }
            runCatching { destination?.discardUnpublished() }
            mutex.unlock()
            if (BuildConfig.DEBUG) {
                val io = saveIo ?: PdfSaveIoMetrics()
                // Preserve historical saveMs = complete write stage. These are nested, not additive times.
                val saveOtherNanos = (timings[3] - callbackDuringSaveNanos).coerceAtLeast(0L)
                val saveCallOtherNanos = (saveCallNanos - callbackDuringSaveNanos).coerceAtLeast(0L)
                Log.d("PdfiumExportTiming",
                    "openMs=${timings[0] / 1_000_000L} imagesMs=${timings[1] / 1_000_000L} " +
                        "insertMs=${timings[2] / 1_000_000L} saveMs=${timings[3] / 1_000_000L} " +
                        "closeMs=${timings[4] / 1_000_000L} publishMs=${timings[5] / 1_000_000L} " +
                        "historyMs=${timings[6] / 1_000_000L} totalMs=${(SystemClock.elapsedRealtimeNanos()-start) / 1_000_000L} " +
                        "inputBytes=$inputBytes outputBytes=$outputBytes success=$success failure=$category " +
                        "callbackCalls=$callbackCalls callbackBytes=$callbackBytes callbackSmallWrites=$callbackSmallWrites " +
                        "callbackMs=${callbackNanos / 1_000_000L} destWriteCalls=${io.destinationWriteCalls} " +
                        "destWriteMs=${io.writeNanos / 1_000_000L} destFlushCloseMs=${io.flushCloseNanos / 1_000_000L} " +
                        "bufferBytes=${PdfSaveOutputStream.BUFFER_BYTES} saveOtherMs=${saveOtherNanos / 1_000_000L} " +
                        "saveCallMs=${saveCallNanos / 1_000_000L} saveCallOtherMs=${saveCallOtherNanos / 1_000_000L} " +
                        "destination=${destination?.timingKind ?: "unopened"} " +
                        "saveLockWaitMs=${(nativeTiming?.saveLockWaitNanos ?: 0L) / 1_000_000L} " +
                        "nativeSaveMs=${(nativeTiming?.nativeSaveNanos ?: 0L) / 1_000_000L} " +
                        "sourceReadCalls=${nativeTiming?.sourceReadCalls ?: 0L} sourceReadBytes=${nativeTiming?.sourceReadBytes ?: 0L} " +
                        "sourceReadSmallCalls=${nativeTiming?.sourceReadSmallCalls ?: 0L} " +
                        "sourceReadMs=${(nativeTiming?.sourceReadNanos ?: 0L) / 1_000_000L} " +
                        "preadCalls=${nativeTiming?.preadCalls ?: 0L} shortReads=${nativeTiming?.shortReads ?: 0L} " +
                        "readErrors=${nativeTiming?.readErrors ?: 0L} " +
                        "nativeWriteCallbackMs=${(nativeTiming?.nativeWriteCallbackNanos ?: 0L) / 1_000_000L} " +
                        "jniArrayPrepareMs=${(nativeTiming?.jniArrayPrepareNanos ?: 0L) / 1_000_000L} " +
                        "nativeTimingAvailable=${nativeTiming != null} " +
                        "requestedSaveMode=${saveMode.timingValue} saveFlags=${saveMode.saveFlags}")
            }
        }
    }

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
}
