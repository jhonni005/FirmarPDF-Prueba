package com.zonadev.firmarpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zonadev.firmarpdf.app.FirmaPdfApplication
import com.zonadev.firmarpdf.navigation.FirmaPdfApp
import com.zonadev.firmarpdf.ui.theme.FirmarPDFTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FirmarPDFTheme {
                FirmaPdfApp((application as FirmaPdfApplication).container)
            }
        }
    }
}

