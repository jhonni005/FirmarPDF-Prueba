package com.zonadev.firmarpdf.app

import android.content.Context
import java.io.File
import com.zonadev.firmarpdf.data.signature.SavedSignatureStore
import androidx.room.Room
import com.zonadev.firmarpdf.data.pdf.SelectedPdfReader
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabase
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfRepository
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfRepository
import com.zonadev.firmarpdf.data.recentpdf.PdfThumbnailCache
import com.zonadev.firmarpdf.data.pdf.PdfExportHistory
import com.zonadev.firmarpdf.data.pdf.PdfExporter
import com.zonadev.firmarpdf.data.pdf.PdfiumSignatureExporter
import kotlinx.coroutines.sync.Mutex
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.LoggerInterface
import io.legere.pdfiumandroid.util.Config

class AppContainer(context: Context) {
    val savedSignatureStore = SavedSignatureStore(File(context.applicationContext.filesDir, "signature"))
    // Referencia directa comprobada al compilar; el motor no se inicia hasta usarlo.
    val pdfiumCore: PdfiumCore by lazy {
        PdfiumCore(context.applicationContext, Config(logger = object : LoggerInterface {
            override fun d(tag: String, message: String?) = Unit
            override fun e(tag: String, t: Throwable?, message: String?) = Unit
        }))
    }
    val contentResolver = context.applicationContext.contentResolver

    private val database = Room.databaseBuilder(
        context.applicationContext,
        SignedPdfDatabase::class.java,
        "signed_pdf_history.db",
    ).addMigrations(SignedPdfDatabase.MIGRATION_1_2).build()

    val signedPdfRepository = SignedPdfRepository(database.signedPdfDao())
    val selectedPdfReader = SelectedPdfReader(context.applicationContext.contentResolver)
    val recentPdfRepository = RecentPdfRepository(database.recentPdfDao(), contentResolver, selectedPdfReader)
    private val exportMutex = Mutex()
    private val exportHistory = PdfExportHistory(database, selectedPdfReader)
    val pdfExporter = PdfExporter(context.applicationContext, savedSignatureStore, selectedPdfReader, exportHistory, exportMutex)
    val pdfiumSignatureExporter = PdfiumSignatureExporter(context.applicationContext, savedSignatureStore, selectedPdfReader,
        exportHistory, { pdfiumCore }, exportMutex)
    val pdfThumbnailCache = PdfThumbnailCache(File(context.applicationContext.cacheDir, "recent_pdf_thumbnails"), contentResolver) { pdfiumCore }
}
