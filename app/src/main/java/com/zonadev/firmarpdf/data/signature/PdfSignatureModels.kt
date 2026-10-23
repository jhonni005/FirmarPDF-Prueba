package com.zonadev.firmarpdf.data.signature

/** Rectángulo relativo a la página visual completa, con origen arriba a la izquierda. */
data class PdfSignaturePlacement(
    val id: String,
    val signatureId: String,
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Píxeles transitorios del viewport, nunca la posición definitiva del borrador. */
data class PdfSignatureBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
    val isValid get() = left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() && width > 0f && height > 0f

    fun translated(dx: Float, dy: Float) = copy(left = left + dx, top = top + dy)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun intersection(other: PdfSignatureBounds): PdfSignatureBounds? {
        val x = maxOf(left, other.left)
        val y = maxOf(top, other.top)
        val w = minOf(right, other.right) - x
        val h = minOf(bottom, other.bottom) - y
        return if (w > 0f && h > 0f) PdfSignatureBounds(x, y, w, h) else null
    }
}

data class PdfSignaturePageBounds(val pageIndex: Int, val bounds: PdfSignatureBounds)
data class VisibleSignatureTarget(val page: PdfSignaturePageBounds, val visibleBounds: PdfSignatureBounds)
