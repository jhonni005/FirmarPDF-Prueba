package com.zonadev.firmarpdf.data.pdf

import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.zonadev.firmarpdf.app.FirmaPdfApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPreviewSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = (context.applicationContext as FirmaPdfApplication).container
    private val fixtures = mutableListOf<File>()

    @After
    fun tearDown() { fixtures.forEach { it.delete() } }

    @Test
    fun rendersDifferentPageSizesWithinTheBitmapLimit() = runBlocking(Dispatchers.IO) {
        val file = fixture()
        val session = open(file)
        try {
            assertEquals(2, session.pageCount)
            val portrait = session.renderPage(0, 1080)
            val landscape = session.renderPage(1, 1440)
            assertEquals(0.5, portrait.width.toDouble() / portrait.height, 0.01)
            assertEquals(2.0, landscape.width.toDouble() / landscape.height, 0.01)
            assertTrue(portrait.width.toLong() * portrait.height <= 2_097_152)
            assertTrue(landscape.allocationByteCount <= 4 * 1024 * 1024)
        } finally { session.close() }
    }

    @Test
    fun closeReleasesTheSourceDescriptorAndPreventsFurtherRendering() = runBlocking(Dispatchers.IO) {
        val file = fixture()
        repeat(5) {
            val session = open(file)
            try {
                assertTrue(sourceDescriptors(file) > 0)
                session.renderPage(0, 600)
            } finally { session.close() }
            assertEquals(0, sourceDescriptors(file))
            try {
                session.renderPage(0, 600)
                fail("Closed preview must reject rendering")
            } catch (_: CancellationException) { }
        }
    }

    @Test
    fun invalidAndInaccessibleInputsHaveSanitizedCategories() {
        val invalid = File.createTempFile("preview_invalid_test_", ".pdf", context.cacheDir)
        fixtures += invalid
        invalid.writeText("invalid test fixture")
        assertFailure(PdfPreviewError.INVALID) { open(invalid) }
        assertEquals(0, sourceDescriptors(invalid))
        assertFailure(PdfPreviewError.INACCESSIBLE) {
            PdfPreviewSession.open(container.contentResolver, { container.pdfiumCore }, Uri.parse("content://com.zonadev.firmarpdf.missing.provider/missing"))
        }
    }

    @Test
    fun passwordProtectedInputIsReportedWithoutKeepingItsDescriptor() {
        val protected = fixture(protected = true)
        assertFailure(PdfPreviewError.PROTECTED) { open(protected) }
        assertEquals(0, sourceDescriptors(protected))
    }

    private fun fixture(protected: Boolean = false): File {
        val file = File.createTempFile("preview_session_test_", ".pdf", context.cacheDir)
        fixtures += file
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle(300f, 600f)))
            document.addPage(PDPage(PDRectangle(600f, 300f)))
            if (protected) document.protect(StandardProtectionPolicy("owner-test", "user-test", AccessPermission()))
            document.save(file)
        }
        return file
    }

    private fun open(file: File) = PdfPreviewSession.open(container.contentResolver, { container.pdfiumCore }, Uri.fromFile(file))

    private fun sourceDescriptors(file: File): Int {
        val source = Os.stat(file.path)
        return File("/proc/self/fd").listFiles().orEmpty().count {
            val target = runCatching { Os.stat(it.path) }.getOrNull()
            target != null && target.st_dev == source.st_dev && target.st_ino == source.st_ino
        }
    }

    private fun assertFailure(reason: PdfPreviewError, operation: () -> PdfPreviewSession) {
        try {
            operation()
            fail("Expected a sanitized preview error")
        } catch (failure: PdfPreviewException) {
            assertEquals(reason, failure.reason)
            assertEquals(null, failure.message)
        }
    }
}
