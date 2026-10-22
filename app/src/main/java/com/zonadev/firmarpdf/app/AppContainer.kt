package com.zonadev.firmarpdf.app

import android.content.Context
import androidx.room.Room
import com.zonadev.firmarpdf.data.pdf.SelectedPdfReader
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfDatabase
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfRepository
import io.legere.pdfiumandroid.PdfiumCore

class AppContainer(context: Context) {
    // Referencia directa comprobada al compilar; el motor no se inicia hasta usarlo.
    val pdfiumCore: PdfiumCore by lazy { PdfiumCore(context.applicationContext) }

    private val database = Room.databaseBuilder(
        context.applicationContext,
        SignedPdfDatabase::class.java,
        "signed_pdf_history.db",
    ).build()

    val signedPdfRepository = SignedPdfRepository(database.signedPdfDao())
    val selectedPdfReader = SelectedPdfReader(context.applicationContext.contentResolver)
}
