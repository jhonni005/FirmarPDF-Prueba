package com.zonadev.firmarpdf.app

import android.content.Context
import java.io.File
import com.zonadev.firmarpdf.data.signature.SignatureStore
import androidx.room.Room
import com.zonadev.firmarpdf.data.pdf.SelectedPdfReader
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabase
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfRepository
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.LoggerInterface
import io.legere.pdfiumandroid.util.Config

class AppContainer(context: Context) {
    val signatureStore = SignatureStore(File(context.applicationContext.filesDir, "signature"))
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
    ).build()

    val signedPdfRepository = SignedPdfRepository(database.signedPdfDao())
    val selectedPdfReader = SelectedPdfReader(context.applicationContext.contentResolver)
}
