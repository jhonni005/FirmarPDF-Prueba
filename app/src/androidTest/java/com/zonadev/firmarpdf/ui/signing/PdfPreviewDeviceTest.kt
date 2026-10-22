package com.zonadev.firmarpdf.ui.signing

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zonadev.firmarpdf.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prueba de laboratorio opcional: requiere los PDF sintéticos en la carpeta SAF actual.
 * Ejecutar con -e previewDeviceFixtures true después de preparar Multipagina.pdf y Grande.pdf.
 * Interactúa con DocumentsUI y MotionEvent reales, sin acceso al ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class PdfPreviewDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun safPinchDoubleTapScrollReopenChangeAndRotate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("previewDeviceFixtures") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                tap("Firmar PDF")
                tap("Multipagina.pdf")
                await { exists("Vista previa") && exists("Página 1 de 6") }
                val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY()
                pinch(centerX, centerY)
                await { zoomPercent() > 150 }
                val enlarged = zoomPercent()
                swipe(centerX, centerY, centerX - 200, centerY - 300)
                assertTrue(zoomPercent() >= enlarged - 2)
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                SystemClock.sleep(700)
                await { zoomPercent() > 150 }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                SystemClock.sleep(700)
                await { zoomPercent() > 150 }
                repeat(3) {
                    if (zoomPercent() != 100) doubleTap(centerX, centerY)
                    SystemClock.sleep(150)
                }
                await { zoomPercent() == 100 }
                scrollToPage("Página 6 de 6", bounds)
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                SystemClock.sleep(700)
                await { exists("Vista previa") }
                tap("Volver")
                await { exists("Inicio") && exists("Multipagina.pdf") }
                SystemClock.sleep(500)
                assertTrue(!exists("Vista previa"))
                tap("Multipagina.pdf")
                await { exists("Vista previa") }
                tap("Volver")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                SystemClock.sleep(700)
                tap("Firmar PDF")
                tap("Grande.pdf")
                await { exists("Vista previa") && exists("Página 1 de 8") }
                val portrait = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
                scrollToPage("Página 8 de 8", portrait)
                tap("Volver")
                await { exists("Inicio") && exists("Grande.pdf") }
                assertTrue(exists("Todavía no tienes documentos firmados."))
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    private fun find(label: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
        return null
    }

    private fun exists(label: String) = find(label) != null

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for device UI", condition())
    }

    private fun tap(label: String) {
        await { exists(label) }
        val node = checkNotNull(find(label))
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        touch(bounds.exactCenterX(), bounds.exactCenterY())
        SystemClock.sleep(300)
    }

    private fun zoomPercent(): Int {
        fun read(node: AccessibilityNodeInfo?): Int? {
            if (node == null) return null
            val text = node.text?.toString().orEmpty()
            Regex("Zoom: (\\d+)%").find(text)?.let { return it.groupValues[1].toInt() }
            for (i in 0 until node.childCount) read(node.getChild(i))?.let { return it }
            return null
        }
        return read(automation.rootInActiveWindow) ?: 0
    }

    private fun scrollToPage(label: String, bounds: Rect) {
        repeat(20) {
            if (exists(label)) return
            swipe(bounds.exactCenterX(), bounds.bottom * 0.8f, bounds.exactCenterX(), bounds.bottom * 0.3f)
            SystemClock.sleep(300)
        }
        assertTrue("Last page must become visible", exists(label))
    }

    private fun touch(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(x to y))
        SystemClock.sleep(20)
        event(down, MotionEvent.ACTION_UP, listOf(x to y))
    }

    private fun doubleTap(x: Float, y: Float) {
        touch(x, y)
        SystemClock.sleep(70)
        touch(x, y)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(x1 to y1))
        for (i in 1..20) {
            SystemClock.sleep(12)
            event(down, MotionEvent.ACTION_MOVE, listOf((x1 + (x2-x1)*i/20) to (y1 + (y2-y1)*i/20)))
        }
        event(down, MotionEvent.ACTION_UP, listOf(x2 to y2))
    }

    private fun pinch(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf((x-80) to y))
        event(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf((x-80) to y, (x+80) to y))
        for (i in 1..20) {
            SystemClock.sleep(16)
            val distance = 80f + i * 8
            event(down, MotionEvent.ACTION_MOVE, listOf((x-distance) to y, (x+distance) to y))
        }
        event(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf((x-240) to y, (x+240) to y))
        event(down, MotionEvent.ACTION_UP, listOf((x-240) to y))
    }

    private fun event(down: Long, action: Int, points: List<Pair<Float, Float>>) {
        val properties = points.indices.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply { x = px; y = py; pressure = 1f; size = 1f } }.toTypedArray()
        val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(automation.injectInputEvent(motion, true)) } finally { motion.recycle() }
    }
}
