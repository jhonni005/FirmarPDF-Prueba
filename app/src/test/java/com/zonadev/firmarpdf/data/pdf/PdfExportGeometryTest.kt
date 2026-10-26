package com.zonadev.firmarpdf.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfExportGeometryTest {
    @Test fun mapsVisualCornersWithCropOffsetForAllQuarterTurns() {
        val expected = mapOf(
            0 to listOf(10f to 120f, 210f to 120f, 10f to 20f),
            90 to listOf(10f to 20f, 10f to 120f, 210f to 20f),
            180 to listOf(210f to 20f, 10f to 20f, 210f to 120f),
            270 to listOf(210f to 120f, 210f to 20f, 10f to 120f),
        )
        for ((rotation, corners) in expected) {
            val page = PdfExportPage(10f, 20f, 200f, 100f, rotation)
            listOf(page.point(0f, 0f), page.point(1f, 0f), page.point(0f, 1f)).zip(corners).forEach { (point, pair) ->
                assertEquals(pair.first, point.x, .001f); assertEquals(pair.second, point.y, .001f)
            }
        }
    }

    @Test fun imageAxesStayOrientedOnRotatedCrop() {
        val matrix = PdfExportPage(10f, 20f, 200f, 100f, 90).image(.2f, .3f, .4f, .2f)
        assertEquals(0f, matrix.a, .001f); assertEquals(40f, matrix.b, .001f)
        assertEquals(-40f, matrix.c, .001f); assertEquals(0f, matrix.d, .001f)
        assertEquals(110f, matrix.e, .001f); assertEquals(40f, matrix.f, .001f)
    }

    @Test fun rotationNormalizationAndLineWidthUseVisualWidth() {
        assertEquals(100f, PdfExportPage(0f, 0f, 200f, 100f, -90).visualWidth, .001f)
        assertEquals(PdfExportPage(0f, 0f, 200f, 100f, 90).point(.3f, .7f),
            PdfExportPage(0f, 0f, 200f, 100f, 450).point(.3f, .7f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteCoordinates() { PdfExportPage(0f, 0f, 200f, 100f, 0).point(Float.NaN, .5f) }
}
