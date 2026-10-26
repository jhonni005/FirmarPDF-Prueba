package com.zonadev.firmarpdf.data.pdf

import android.graphics.Bitmap
import android.net.Uri
import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement
import com.zonadev.firmarpdf.data.signature.SignaturePoint

data class PdfExportDate(val placement: PdfSignaturePlacement, val image: Bitmap?)
data class PdfExportStroke(val pageIndex: Int, val color: Int, val width: Float, val points: List<SignaturePoint>)
data class PdfExportDraft(
    val signatures: List<PdfSignaturePlacement>,
    val dates: List<PdfExportDate>,
    val strokes: List<PdfExportStroke>,
    val expectedPageCount: Int,
) {
    val hasAnnotations get() = signatures.isNotEmpty() || dates.isNotEmpty() || strokes.isNotEmpty()
}
enum class PdfExportStage { SOURCE, LOAD, ANNOTATE, SAVE, PUBLISH }
enum class PdfExportError { SOURCE, PROTECTED, CERTIFIED, INVALID, ANNOTATIONS, WRITE, VERIFY, BUSY, MEMORY, PERMISSION }
class PdfExportException(val reason: PdfExportError) : Exception()
data class PdfExportResult(val uri: Uri, val displayName: String, val recorded: Boolean)
