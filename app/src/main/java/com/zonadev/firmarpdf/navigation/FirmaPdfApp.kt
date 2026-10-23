package com.zonadev.firmarpdf.navigation

import android.content.ActivityNotFoundException
import android.net.Uri
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.app.AppContainer
import com.zonadev.firmarpdf.ui.pdf.PdfPreviewScreen
import com.zonadev.firmarpdf.ui.pdf.PdfPreviewViewModel
import com.zonadev.firmarpdf.ui.pdf.PdfSignatureViewModel
import com.zonadev.firmarpdf.ui.home.HomeScreen
import com.zonadev.firmarpdf.ui.home.HomeViewModel
import com.zonadev.firmarpdf.ui.signature.CreateSignatureScreen
import com.zonadev.firmarpdf.ui.signature.CreateSignatureViewModel

private object Routes {
    const val HOME = "home"
    const val PREVIEW = "preview/{documentUri}"
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
    val lifecycleOwner = LocalLifecycleOwner.current
    fun openPreview(uri: Uri) {
        navController.navigate("preview/${Uri.encode(uri.toString())}") { launchSingleTop = true }
    }
    LaunchedEffect(homeViewModel, navController, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            homeViewModel.previewRequests.collect { openPreview(it) }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val activity = remember(context) {
        var current = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        current as? Activity
    }
    val route = backStackEntry?.destination?.route
    DisposableEffect(activity, route) {
        if (route != null) activity?.requestedOrientation =
            if (route == Routes.SIGNATURE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }
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
                    onOpenSelectedPdf = { state.selectedPdf?.let { openPreview(it.uri) } },
                )
            }
            composable(Routes.PREVIEW, arguments = listOf(navArgument("documentUri") { type = NavType.StringType })) { entry ->
                val previewViewModel: PdfPreviewViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        PdfPreviewViewModel(
                            container.contentResolver,
                            { container.pdfiumCore },
                            Uri.parse(checkNotNull(entry.arguments?.getString("documentUri"))),
                        )
                    }
                })
                val previewState by previewViewModel.uiState.collectAsStateWithLifecycle()
                val signatureViewModel: PdfSignatureViewModel = viewModel(factory = viewModelFactory {
                    initializer { PdfSignatureViewModel(container.savedSignatureStore) }
                })
                PdfPreviewScreen(
                    state = previewState,
                    signatures = signatureViewModel,
                    onRetry = previewViewModel::reload,
                    onBack = { navController.popBackStack() },
                    onCreateSignature = { navController.navigate(Routes.SIGNATURE) { launchSingleTop = true } },
                )
            }
            composable(Routes.SIGNATURE) {
                val signatureModel: CreateSignatureViewModel = viewModel(factory = viewModelFactory {
                    initializer { CreateSignatureViewModel(container.savedSignatureStore) }
                })
                CreateSignatureScreen(model = signatureModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
