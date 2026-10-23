package com.zonadev.firmarpdf.data.signature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil
import kotlin.math.floor

/** Coordenadas y grosor relativos al ancho del lienzo; no contiene fondo. */
fun drawSignature(canvas: Canvas, strokes: List<SignatureStroke>, width: Float, height: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.forEach { stroke ->
        if (stroke.points.isEmpty()) return@forEach
        paint.color = stroke.color
        paint.strokeWidth = stroke.width * width
        val first = stroke.points.first()
        if (stroke.points.all { it == first }) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x * width, first.y * height, paint.strokeWidth / 2, paint)
        } else {
            paint.style = Paint.Style.STROKE
            val path = Path().apply {
                moveTo(first.x * width, first.y * height)
                stroke.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
            }
            canvas.drawPath(path, paint)
        }
    }
}

/** El llamador es responsable de liberar el bitmap devuelto. */
fun renderSignature(strokes: List<SignatureStroke>, aspectRatio: Float): Bitmap {
    require(strokes.isNotEmpty() && strokes.all { it.points.isNotEmpty() })
    require(aspectRatio.isFinite() && aspectRatio > 0)
    val width = minOf(1600f, 2400f * aspectRatio).toInt().coerceAtLeast(1)
    val height = (width / aspectRatio).toInt().coerceIn(1, 2400)
    var left = width.toFloat(); var top = height.toFloat(); var right = 0f; var bottom = 0f
    strokes.forEach { stroke ->
        val padding = stroke.width * width / 2 + 8
        stroke.points.forEach {
            left = minOf(left, it.x * width - padding); right = maxOf(right, it.x * width + padding)
            top = minOf(top, it.y * height - padding); bottom = maxOf(bottom, it.y * height + padding)
        }
    }
    val x = floor(left).toInt().coerceIn(0, width - 1)
    val y = floor(top).toInt().coerceIn(0, height - 1)
    val w = (ceil(right).toInt().coerceIn(x + 1, width) - x)
    val h = (ceil(bottom).toInt().coerceIn(y + 1, height) - y)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.translate(-x.toFloat(), -y.toFloat())
        drawSignature(canvas, strokes, width.toFloat(), height.toFloat())
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}
