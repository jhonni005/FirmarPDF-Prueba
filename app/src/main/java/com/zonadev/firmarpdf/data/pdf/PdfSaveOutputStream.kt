package com.zonadev.firmarpdf.data.pdf

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/** Contadores de llamadas, sin conservar bytes del documento ni identificar el destino. */
internal data class PdfSaveIoMetrics(
    val writeNanos: Long = 0,
    val flushCloseNanos: Long = 0,
    val pdfWriteCalls: Long = 0,
    val pdfSmallWrites: Long = 0,
    val destinationWriteCalls: Long = 0,
)

/** PDFBox cierra este stream dentro de save(OutputStream); ese cierre vacía el búfer. */
internal class PdfSaveOutputStream(
    destination: OutputStream,
    private val checkActive: () -> Unit,
    private val debugNanoTime: (() -> Long)? = null,
    bufferBytes: Int = BUFFER_BYTES,
) : OutputStream() {
    private var closed = false
    private var writeNanos = 0L
    private var flushCloseNanos = 0L
    private var pdfWriteCalls = 0L
    private var pdfSmallWrites = 0L
    private var destinationWriteCalls = 0L
    var bytesWritten = 0L
        private set

    // Medir debajo del búfer: solo las llamadas efectivas al stream del proveedor/archivo.
    private val sink = object : OutputStream() {
        override fun write(b: Int) = writeDestination(1) { destination.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = writeDestination(len) { destination.write(b, off, len) }
        override fun flush() {
            checkActive()
            flushOrClose { destination.flush() }
        }
        override fun close() = flushOrClose { destination.close() }
    }
    private val buffered = BufferedOutputStream(sink, bufferBytes)

    override fun write(b: Int) {
        beforeWrite(1)
        buffered.write(b)
    }

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        beforeWrite(len)
        buffered.write(b, off, len)
    }

    override fun flush() {
        requireOpen()
        checkActive()
        buffered.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        // Incluso si se cancela o falla flush, cerrar el destino. No volcar bytes al cancelar.
        try {
            checkActive()
            buffered.flush()
        } finally {
            sink.close()
        }
    }

    fun metrics() = PdfSaveIoMetrics(writeNanos, flushCloseNanos, pdfWriteCalls, pdfSmallWrites, destinationWriteCalls)

    private fun requireOpen() {
        if (closed) throw IOException("Output stream closed")
    }

    private fun beforeWrite(length: Int) {
        requireOpen()
        checkActive()
        if (debugNanoTime != null && length > 0) {
            pdfWriteCalls++
            if (length <= SMALL_WRITE_BYTES) pdfSmallWrites++
        }
    }

    private inline fun writeDestination(length: Int, write: () -> Unit) {
        checkActive()
        val clock = debugNanoTime
        if (clock == null) {
            write()
            bytesWritten += length
        } else {
            if (length > 0) destinationWriteCalls++
            val start = clock()
            try {
                write()
                bytesWritten += length
            } finally {
                writeNanos += clock() - start
            }
        }
    }

    private inline fun flushOrClose(operation: () -> Unit) {
        val clock = debugNanoTime
        if (clock == null) operation() else {
            val start = clock()
            try { operation() } finally { flushCloseNanos += clock() - start }
        }
    }

    companion object {
        const val BUFFER_BYTES = 64 * 1024
        private const val SMALL_WRITE_BYTES = 1024
    }
}
