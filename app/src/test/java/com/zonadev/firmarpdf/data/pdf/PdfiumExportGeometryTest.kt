package com.zonadev.firmarpdf.data.pdf

import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement
import org.junit.Assert.*
import org.junit.Test

class PdfiumExportGeometryTest {
    private val placement = PdfSignaturePlacement("placement", "signature", 0, .1f, .2f, .3f, .1f)
    private val draft = PdfExportDraft(listOf(placement), emptyList(), emptyList(), 1)

    @Test fun rawBoundingBoxAndQuarterTurnsMapTheWholeUnitImage() {
        // Effective CropBox/MediaBox intersection, with nonzero offsets.
        val expected = listOf(
            floatArrayOf(120f, 0f, 0f, 60f, 60f, 450f),
            floatArrayOf(0f, 180f, -40f, 0f, 140f, 90f),
            floatArrayOf(-120f, 0f, 0f, -60f, 380f, 210f),
            floatArrayOf(0f, -180f, 40f, 0f, 300f, 570f),
        )
        for (turns in 0..3) {
            val m = pdfiumSignatureMatrix(placement, 20f, 30f, 420f, 630f, turns)
            assertArrayEquals(expected[turns], floatArrayOf(m.a, m.b, m.c, m.d, m.e, m.f), .001f)
            assertTrue("Image matrix must stay invertible, including 90/270", m.a * m.d - m.b * m.c > 0f)
        }
    }

    @Test fun topLeftAndBottomRightKeepOffsetsWithNoSecondVerticalFlip() {
        val fullPage = placement.copy(x = 0f, y = 0f, width = 1f, height = 1f)
        val m = pdfiumSignatureMatrix(fullPage, -40f, 25f, 160f, 325f, 0)
        assertEquals(-40f, m.e, .001f)
        assertEquals(25f, m.f, .001f)
        assertEquals(160f, m.a + m.e, .001f)
        assertEquals(325f, m.d + m.f, .001f)
    }

    @Test fun rejectsInvalidBoxesRotationsAndPlacements() {
        assertThrows(IllegalArgumentException::class.java) { pdfiumSignatureMatrix(placement, -1f, -1f, -1f, -1f, 0) }
        assertThrows(IllegalArgumentException::class.java) { pdfiumSignatureMatrix(placement, 0f, 0f, 100f, 100f, 90) }
        assertThrows(IllegalArgumentException::class.java) { pdfiumSignatureMatrix(placement.copy(x = Float.NaN), 0f, 0f, 100f, 100f, 0) }
        assertThrows(IllegalArgumentException::class.java) { pdfiumSignatureMatrix(placement.copy(width = 0f), 0f, 0f, 100f, 100f, 0) }
    }

    @Test fun emptyDraftNeedsASignature() {
        assertEquals(PdfExportError.NO_SIGNATURES, pdfiumDraftError(draft.copy(signatures = emptyList())))
        assertNull(pdfiumDraftError(draft))
    }

    @Test fun datesOrDrawingsAreRejectedInsteadOfSilentlyDropped() {
        assertEquals(PdfExportError.PDFIUM_SIGNATURES_ONLY,
            pdfiumDraftError(draft.copy(dates = listOf(PdfExportDate(placement, null)))))
        assertEquals(PdfExportError.PDFIUM_SIGNATURES_ONLY,
            pdfiumDraftError(draft.copy(strokes = listOf(PdfExportStroke(0, 0, .01f, emptyList())))))
    }
}
