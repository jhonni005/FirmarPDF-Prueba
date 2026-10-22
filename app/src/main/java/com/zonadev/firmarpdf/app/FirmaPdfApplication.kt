package com.zonadev.firmarpdf.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class FirmaPdfApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
