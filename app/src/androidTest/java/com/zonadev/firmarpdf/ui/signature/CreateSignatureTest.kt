package com.zonadev.firmarpdf.ui.signature

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zonadev.firmarpdf.data.signature.*
import com.zonadev.firmarpdf.ui.theme.FirmarPDFTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CreateSignatureTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "signature_test_${UUID.randomUUID()}")

    @After fun clean() { directory.listFiles()?.forEach { it.delete() }; directory.delete() }

    @Test fun canvasUndoDiscardClearAndSave() {
        val model = CreateSignatureViewModel(SignatureStore(directory))
        var returned = false
        compose.setContent { FirmarPDFTheme { CreateSignatureScreen(model, { returned = true }, Modifier.requiredHeight(400.dp)) } }
        compose.onNodeWithText("Guardar firma").assertIsDisplayed()
        compose.onNodeWithText("Guardar firma").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Negro").assertIsSelected()
        fun draw() {
            compose.onNodeWithContentDescription("Lienzo para dibujar la firma con el dedo").performTouchInput {
                down(Offset(width * .2f, height * .3f))
                moveTo(Offset(width * .8f, height * .7f))
                up()
            }
        }
        draw()
        compose.onNodeWithText("Guardar firma").assertIsEnabled()
        compose.onNodeWithText("Deshacer").performClick()
        compose.onNodeWithText("Guardar firma").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Azul").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Grosor de línea").performSemanticsAction(SemanticsActions.SetProgress) { it(5.5f) }
        compose.runOnIdle { assertEquals(5.5f, model.thickness) }
        draw()
        compose.onNodeWithText("Volver").performClick()
        compose.onNodeWithText("¿Descartar la firma?").assertIsDisplayed()
        compose.onNodeWithText("Seguir dibujando").performClick()
        compose.onNodeWithText("Borrar").performClick()
        compose.onNodeWithText("Guardar firma").assertIsNotEnabled()
        draw()
        compose.onNodeWithText("Guardar firma").performClick()
        compose.waitUntil(5_000) { returned }
        val bitmap = BitmapFactory.decodeFile(File(directory, "signature.png").path)
        try {
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { Color.alpha(it) > 0 })
        } finally { bitmap.recycle() }
    }

    @Test fun eachStrokeKeepsItsSettingsAndFailedSaveKeepsTheDrawing() {
        directory.writeText("Block directory creation to simulate storage failure")
        val model = CreateSignatureViewModel(SignatureStore(directory))
        compose.setContent { FirmarPDFTheme { CreateSignatureScreen(model, {}) } }
        compose.runOnIdle {
            model.begin(SignaturePoint(.2f, .2f), .01f); model.finish()
            model.color = Color.RED; model.thickness = 8f
            model.begin(SignaturePoint(.8f, .8f), .02f); model.finish()
            assertEquals(Color.BLACK, model.strokes[0].color)
            assertEquals(.01f, model.strokes[0].width)
            assertEquals(Color.RED, model.strokes[1].color)
            model.save(1f)
        }
        compose.waitUntil(5_000) { model.error && !model.saving }
        compose.onNodeWithText("No se pudo guardar la firma. Tu dibujo se conserva; vuelve a intentarlo.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, model.strokes.size) }
    }

    @Test fun pngRetainsBothColorsAndReplacesPreviousSignature() = runBlocking {
        val store = SignatureStore(directory)
        val first = SignatureStroke(Color.BLACK, .02f, listOf(SignaturePoint(.2f, .2f)))
        val second = SignatureStroke(Color.RED, .04f, listOf(SignaturePoint(.8f, .8f)))
        store.save(listOf(first), 1f)
        store.save(listOf(first, second), 1f)
        val bitmap = BitmapFactory.decodeFile(File(directory, "signature.png").path)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { it == Color.BLACK })
            assertTrue(pixels.any { it == Color.RED })
            assertTrue(pixels.any { Color.alpha(it) == 0 })
        } finally { bitmap.recycle() }
        assertEquals(listOf("signature.png"), directory.list()?.toList())
    }
}
