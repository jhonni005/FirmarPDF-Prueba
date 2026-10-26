package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/** Imagen de presentación en memoria; no se escribe en biblioteca, caché ni PDF. */
internal fun renderPdfDate(text: String): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f; typeface = Typeface.DEFAULT }
    val metrics = paint.fontMetrics
    val bitmap = Bitmap.createBitmap(ceil(paint.measureText(text) + 16).toInt(), ceil(metrics.descent - metrics.ascent + 16).toInt(), Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawText(text, 8f, 8f - metrics.ascent, paint)
    return bitmap
}
