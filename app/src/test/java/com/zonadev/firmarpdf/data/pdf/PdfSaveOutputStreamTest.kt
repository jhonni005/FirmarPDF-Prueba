package com.zonadev.firmarpdf.data.pdf

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

class PdfSaveOutputStreamTest {
    @Test fun coalescesFragmentedWritesAndPreservesLargeBlocksInOrder() {
        val raw = ByteArrayOutputStream()
        val stream = PdfSaveOutputStream(raw, {}, { 0L })
        val small = ByteArray(20_000) { (it % 251).toByte() }
        val large = ByteArray(200_000) { (it % 239).toByte() }
        small.forEach { stream.write(it.toInt() and 255) }
        stream.write(large)
        stream.close()
        assertArrayEquals(small + large, raw.toByteArray())
        assertEquals(220_000L, stream.bytesWritten)
        val metrics = stream.metrics()
        assertEquals(20_001L, metrics.pdfWriteCalls)
        assertEquals(20_000L, metrics.pdfSmallWrites)
        assertTrue(metrics.destinationWriteCalls < 10)
    }

    @Test fun separatesDelegateWriteTimeFromFlushAndCloseAndClosesOnlyOnce() {
        var nanos = 0L
        var closes = 0
        val bytes = ByteArrayOutputStream()
        val raw = object : OutputStream() {
            override fun write(b: Int) { nanos += 2_000_000; bytes.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { nanos += 2_000_000; bytes.write(b, off, len) }
            override fun flush() { nanos += 3_000_000 }
            override fun close() { closes++; nanos += 5_000_000 }
        }
        val stream = PdfSaveOutputStream(raw, {}, { nanos })
        stream.write(1)
        stream.write(byteArrayOf(2, 3))
        stream.close()
        stream.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
        assertEquals(1, closes)
        assertEquals(2_000_000L, stream.metrics().writeNanos)
        assertEquals(8_000_000L, stream.metrics().flushCloseNanos)
        assertEquals(1L, stream.metrics().destinationWriteCalls)
    }

    @Test fun cancellationDiscardsBufferedBytesAndStillClosesDestination() {
        var cancelled = false
        var closed = false
        val raw = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        val stream = PdfSaveOutputStream(raw, { if (cancelled) throw CancellationException() })
        stream.write(byteArrayOf(1, 2, 3))
        cancelled = true
        assertThrows(CancellationException::class.java) { stream.close() }
        assertTrue(closed)
        assertEquals(0, raw.size())
        assertEquals(0L, stream.bytesWritten)
        stream.close()
    }

    @Test fun failedBufferFlushStillClosesDestinationWithoutCountingFailedBytes() {
        var closed = false
        val raw = object : OutputStream() {
            override fun write(b: Int) { throw IOException() }
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException() }
            override fun close() { closed = true }
        }
        val stream = PdfSaveOutputStream(raw, {})
        stream.write(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { stream.close() }
        assertTrue(closed)
        assertEquals(0L, stream.bytesWritten)
    }

    @Test fun keepsByteCountWithoutDebugCountersAndRejectsWritesAfterClose() {
        val raw = ByteArrayOutputStream()
        val stream = PdfSaveOutputStream(raw, {})
        stream.write(byteArrayOf(1, 2, 3))
        stream.flush()
        assertEquals(3L, stream.bytesWritten)
        stream.close()
        assertEquals(PdfSaveIoMetrics(), stream.metrics())
        assertThrows(IOException::class.java) { stream.write(4) }
    }
}
