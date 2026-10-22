package com.zonadev.firmarpdf.ui.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zonadev.firmarpdf.data.pdf.SelectedPdfReader
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabase
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val viewModels = ViewModelStore()
    private val fixtures = mutableListOf<File>()
    private lateinit var database: SignedPdfDatabase
    private lateinit var repository: SignedPdfRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SignedPdfDatabase::class.java).build()
        repository = SignedPdfRepository(database.signedPdfDao())
        val factory = viewModelFactory {
            initializer { HomeViewModel(repository, SelectedPdfReader(context.contentResolver), SavedStateHandle()) }
        }
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(viewModels, factory)[HomeViewModel::class.java]
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { viewModels.clear() }
        database.close()
        fixtures.forEach { it.delete() }
    }

    @Test
    fun cancelAndReplacementKeepSelectionSeparateFromSignedResults() = runBlocking {
        select(null)
        assertNull(viewModel.uiState.value.selectedPdf)
        val firstUri = readableFixture()
        select(firstUri)
        awaitSelection(firstUri)
        select(null)
        assertEquals(firstUri, viewModel.uiState.value.selectedPdf?.uri)

        val secondUri = readableFixture()
        select(secondUri)
        awaitSelection(secondUri)
        assertTrue(withTimeout(5_000) { repository.results.first() }.isEmpty())
    }

    @Test
    fun unreadableSelectionReportsErrorAndKeepsThePreviousReadableOne() = runBlocking {
        val readableUri = readableFixture()
        select(readableUri)
        awaitSelection(readableUri)
        select(Uri.parse("content://com.zonadev.firmarpdf.nonexistent.provider/missing.pdf"))
        val error = withTimeout(5_000) {
            viewModel.uiState.first { !it.checkingSelection && it.selectionError != null }
        }
        assertEquals(PdfSelectionError.UNREADABLE, error.selectionError)
        assertEquals(readableUri, error.selectedPdf?.uri)
        assertTrue(withTimeout(5_000) { repository.results.first() }.isEmpty())
    }

    private fun readableFixture(): Uri {
        // Solo ejercita acceso al flujo; no se valida ni procesa estructura PDF en esta etapa.
        val file = File.createTempFile("pdf_selection_test_", ".pdf", context.cacheDir)
        fixtures += file
        file.writeText("%PDF-1.4\n%%EOF")
        return Uri.fromFile(file)
    }

    private fun select(uri: Uri?) {
        instrumentation.runOnMainSync { viewModel.onPdfSelected(uri) }
    }

    private suspend fun awaitSelection(uri: Uri) = withTimeout(5_000) {
        viewModel.uiState.first { !it.checkingSelection && it.selectedPdf?.uri == uri }
    }
}
