package com.zonadev.firmarpdf.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Test
import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement

class PdfExportGeometryTest {
    @Test fun signatureMatricesMatchReferenceForOffsetCropAndAllRotations() {
        val placement = PdfSignaturePlacement("placed", "png", 0, .2f, .3f, .4f, .2f)
        val expected = mapOf(
            0 to PdfExportMatrix(80f, 0f, 0f, 20f, 50f, 70f),
            90 to PdfExportMatrix(0f, 40f, -40f, 0f, 110f, 40f),
            180 to PdfExportMatrix(-80f, 0f, 0f, -20f, 170f, 70f),
            270 to PdfExportMatrix(0f, -40f, 40f, 0f, 110f, 100f),
        )
        for ((rotation, matrix) in expected) {
            val actual = normalizedSignaturePlacementToPdfMatrix(placement, PdfExportPage(10f, 20f, 200f, 100f, rotation))
            listOf(matrix.a to actual.a, matrix.b to actual.b, matrix.c to actual.c,
                matrix.d to actual.d, matrix.e to actual.e, matrix.f to actual.f).forEach { (wanted, value) ->
                assertEquals(wanted, value, .001f)
            }
        }
    }

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
