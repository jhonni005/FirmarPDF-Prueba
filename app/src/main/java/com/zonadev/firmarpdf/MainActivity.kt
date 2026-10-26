package com.zonadev.firmarpdf

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import com.zonadev.firmarpdf.app.IncomingPdfViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zonadev.firmarpdf.app.FirmaPdfApplication
import com.zonadev.firmarpdf.navigation.FirmaPdfApp
import com.zonadev.firmarpdf.ui.theme.FirmarPDFTheme

class MainActivity : ComponentActivity() {
    private val incoming by lazy { ViewModelProvider(this)[IncomingPdfViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receivePdf(intent)
        setContent {
            FirmarPDFTheme {
                FirmaPdfApp((application as FirmaPdfApplication).container, incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receivePdf(intent)
    }

    private fun receivePdf(input: Intent?) {
        if (input == null || input.type?.substringBefore(';')?.trim()?.lowercase() != "application/pdf") return
        @Suppress("DEPRECATION")
        val uri = runCatching { when (input.action) {
            Intent.ACTION_VIEW -> input.data ?: input.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_SEND -> input.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: input.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: input.data
            else -> null
        } }.getOrNull()
        if (uri?.scheme == "content" || uri?.scheme == "file") incoming.receive(checkNotNull(uri))
        // Se mantienen intent/data/ClipData y sus concesiones temporales en la Activity.
    }
}

