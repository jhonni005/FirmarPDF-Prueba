package com.zonadev.firmarpdf.data.pdf

data class PdfExportPoint(val x: Float, val y: Float)
data class PdfExportMatrix(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)

/** La vista tiene origen arriba a la izquierda del CropBox ya girado. PDF usa espacio sin girar. */
class PdfExportPage(val left: Float, val bottom: Float, val width: Float, val height: Float, rotation: Int) {
    val rotation = ((rotation % 360) + 360) % 360
    init {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(this.rotation in listOf(0, 90, 180, 270))
    }
    val visualWidth get() = if (rotation == 90 || rotation == 270) height else width

    fun point(x: Float, y: Float): PdfExportPoint {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        return when (rotation) {
            90 -> PdfExportPoint(left + y * width, bottom + x * height)
            180 -> PdfExportPoint(left + (1 - x) * width, bottom + y * height)
            270 -> PdfExportPoint(left + (1 - y) * width, bottom + (1 - x) * height)
            else -> PdfExportPoint(left + x * width, bottom + (1 - y) * height)
        }
    }

    fun image(x: Float, y: Float, w: Float, h: Float): PdfExportMatrix {
        require(w.isFinite() && h.isFinite() && w > 0 && h > 0)
        require(x >= 0 && y >= 0 && x + w <= 1.0001f && y + h <= 1.0001f)
        val origin = point(x, (y + h).coerceAtMost(1f))
        val horizontal = point((x + w).coerceAtMost(1f), (y + h).coerceAtMost(1f))
        val vertical = point(x, y)
        return PdfExportMatrix(horizontal.x-origin.x, horizontal.y-origin.y,
            vertical.x-origin.x, vertical.y-origin.y, origin.x, origin.y)
    }
}
