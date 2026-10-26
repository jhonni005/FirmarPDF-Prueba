package com.zonadev.firmarpdf.data.pdf

import io.legere.pdfiumandroid.PdfDocument

/** Requested PDFium mode; a successful call does not verify the effective file format. */
enum class PdfiumSaveMode(val saveFlags: Int, val timingValue: String) {
    FULL(PdfDocument.FPDF_NO_INCREMENTAL, "full"),
    INCREMENTAL(PdfDocument.FPDF_INCREMENTAL, "incremental"),
}
