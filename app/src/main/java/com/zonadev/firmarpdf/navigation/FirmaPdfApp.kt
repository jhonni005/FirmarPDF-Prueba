package com.zonadev.firmarpdf.navigation

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.app.AppContainer
import com.zonadev.firmarpdf.ui.signing.PdfSigningScreen
import com.zonadev.firmarpdf.ui.home.HomeScreen
import com.zonadev.firmarpdf.ui.home.HomeViewModel
import com.zonadev.firmarpdf.ui.signature.CreateSignatureScreen

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val SIGNATURE = "signature"
}

@Composable
fun FirmaPdfApp(container: AppContainer) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory {
        initializer {
            HomeViewModel(container.signedPdfRepository, container.selectedPdfReader, createSavedStateHandle())
        }
    })
    val state by homeViewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), homeViewModel::onPdfSelected)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (backStackEntry?.destination?.route == Routes.HOME) {
                ExtendedFloatingActionButton(onClick = {
                    try {
                        pdfPicker.launch(arrayOf("application/pdf"))
                    } catch (_: ActivityNotFoundException) {
                        homeViewModel.onPickerUnavailable()
                    }
                }) {
                    Text(stringResource(R.string.sign_pdf_title))
                }
            }
        },
    ) { innerPadding ->
        NavHost(navController, startDestination = Routes.HOME, modifier = Modifier.padding(innerPadding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    state = state,
                    onRetry = homeViewModel::loadHistory,
                )
            }
            composable(Routes.EDITOR) {
                PdfSigningScreen(
                    onCreateSignature = { navController.navigate(Routes.SIGNATURE) { launchSingleTop = true } },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SIGNATURE) {
                CreateSignatureScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
