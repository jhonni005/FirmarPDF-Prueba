package io.legere.pdfiumandroid

/** Immutable snapshot of one save on one document, before document close.
 * Durations are monotonic nanoseconds, not milliseconds or CPU time.
 * Native read/write callback durations are nested in nativeSaveNanos.
 */
class PdfSaveDiagnostics internal constructor(
    val saveLockWaitNanos: Long,
    val nativeSaveNanos: Long,
    val sourceReadCalls: Long,
    val sourceReadBytes: Long,
    val sourceReadSmallCalls: Long,
    val sourceReadNanos: Long,
    val preadCalls: Long,
    val shortReads: Long,
    val readErrors: Long,
    val nativeWriteCallbackNanos: Long,
    val jniArrayPrepareNanos: Long,
)
