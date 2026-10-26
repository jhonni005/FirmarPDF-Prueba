package com.zonadev.firmarpdf.data.pdf

import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement

/** FPDF_GetPageBoundingBox returns the raw MediaBox/CropBox intersection.
 * Its RectF wrapper has top > bottom: these are PDF coordinates, not Android rectangles.
 * PDFium's intrinsic rotation is a count of clockwise quarter turns, not degrees.
 * The existing matrix maps the unit image square to unrotated PDF space exactly once.
 */
internal fun pdfiumSignatureMatrix(
    placement: PdfSignaturePlacement,
    left: Float, bottom: Float, right: Float, top: Float, quarterTurns: Int,
): PdfExportMatrix {
    require(listOf(left, bottom, right, top).all { it.isFinite() } && quarterTurns in 0..3)
    require(listOf(placement.x, placement.y, placement.width, placement.height).all { it.isFinite() })
    require(placement.x >= 0f && placement.y >= 0f && placement.width > 0f && placement.height > 0f)
    require(placement.x + placement.width <= 1.0001f && placement.y + placement.height <= 1.0001f)
    val box = PdfExportPage(left, bottom, right - left, top - bottom, quarterTurns * 90)
    return normalizedSignaturePlacementToPdfMatrix(placement, box)
}

/** No destination or native document may be opened before this check. */
internal fun pdfiumDraftError(draft: PdfExportDraft): PdfExportError? = when {
    draft.dates.isNotEmpty() || draft.strokes.isNotEmpty() -> PdfExportError.PDFIUM_SIGNATURES_ONLY
    draft.signatures.isEmpty() -> PdfExportError.NO_SIGNATURES
    else -> null
}
