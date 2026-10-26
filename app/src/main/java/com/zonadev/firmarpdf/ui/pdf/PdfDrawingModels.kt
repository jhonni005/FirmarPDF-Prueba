package com.zonadev.firmarpdf.ui.pdf

import com.zonadev.firmarpdf.data.signature.SignaturePoint

data class PdfPageStroke(val id: String, val pageIndex: Int, val color: Int, val width: Float, val points: List<SignaturePoint>)
data class PdfDrawingUiState(
    val drawing: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val thickness: Float = 3f,
    val strokes: List<PdfPageStroke> = emptyList(),
    val active: PdfPageStroke? = null,
)
