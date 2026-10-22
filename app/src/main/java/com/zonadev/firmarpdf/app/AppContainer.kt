package com.zonadev.firmarpdf.app

import android.content.Context
import androidx.room.Room
import com.zonadev.firmarpdf.data.history.HistoryDatabase
import com.zonadev.firmarpdf.data.history.HistoryRepository

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        HistoryDatabase::class.java,
        "signed_pdf_history.db",
    ).build()

    val historyRepository = HistoryRepository(database.signedPdfDao())
}
