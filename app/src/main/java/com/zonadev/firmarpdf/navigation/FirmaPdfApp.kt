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
import com.zonadev.firmarpdf.ui.pdf.PdfDateViewModel
import com.zonadev.firmarpdf.ui.pdf.PdfDrawingViewModel
import com.zonadev.firmarpdf.ui.pdf.PdfExportViewModel
import com.zonadev.firmarpdf.app.IncomingPdfViewModel
import com.zonadev.firmarpdf.ui.home.HomeScreen
import com.zonadev.firmarpdf.ui.home.HomeViewModel
import com.zonadev.firmarpdf.ui.signature.CreateSignatureScreen
import com.zonadev.firmarpdf.ui.signature.CreateSignatureViewModel
import com.zonadev.firmarpdf.ui.home.HomeNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val PREVIEW = "preview/{documentUri}"
    const val SIGNATURE = "signature"
}

@Composable
fun FirmaPdfApp(container: AppContainer, incoming: IncomingPdfViewModel? = null) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory {
        initializer {
            HomeViewModel(container.signedPdfRepository, container.selectedPdfReader, createSavedStateHandle(), container.recentPdfRepository)
        }
    })
    val state by homeViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    fun openPreview(uri: Uri) {
        val current = navController.currentBackStackEntry
        if (current?.destination?.route == Routes.PREVIEW && current.arguments?.getString("documentUri") == uri.toString()) return
        // Argumentos diferentes necesitan su propio ViewModel/borrador; no reutilizar singleTop.
        navController.navigate("preview/${Uri.encode(uri.toString())}")
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
    val incomingUri = incoming?.pending?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(incomingUri, route) {
        val value = incomingUri ?: return@LaunchedEffect
        if (route == null || route == Routes.SIGNATURE) return@LaunchedEffect
        if (backStackEntry?.arguments?.getString("documentUri") != value) homeViewModel.openRecent(value)
        incoming?.consume(value)
    }
    DisposableEffect(activity, route) {
        if (route != null) activity?.requestedOrientation =
            if (route == Routes.SIGNATURE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), homeViewModel::onPdfSelected)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.selectionError) {
        if (state.selectionError != null && route != Routes.HOME) {
            snackbar.showSnackbar(context.getString(R.string.selection_unreadable))
        }
    }
    HomeNavigationDrawer(drawer, route == Routes.HOME, snackbar) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
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
                        onOpenRecent = homeViewModel::openRecent,
                        loadLocation = { container.selectedPdfReader.location(context.applicationContext, Uri.parse(it)) },
                        onRetryRecents = homeViewModel::loadRecents,
                        loadThumbnail = container.pdfThumbnailCache::load,
                        onOpenMenu = { scope.launch { drawer.open() } },
                    )
                }
                composable(Routes.PREVIEW, arguments = listOf(navArgument("documentUri") { type = NavType.StringType })) { entry ->
                    val previewViewModel: PdfPreviewViewModel = viewModel(factory = viewModelFactory {
                        initializer {
                            PdfPreviewViewModel(
                                container.contentResolver,
                                { container.pdfiumCore },
                                Uri.parse(checkNotNull(entry.arguments?.getString("documentUri"))),
                                container.recentPdfRepository,
                            )
                        }
                    })
                    val previewState by previewViewModel.uiState.collectAsStateWithLifecycle()
                    val signatureViewModel: PdfSignatureViewModel = viewModel(factory = viewModelFactory {
                        initializer { PdfSignatureViewModel(container.savedSignatureStore) }
                    })
                    val dateViewModel: PdfDateViewModel = viewModel(factory = viewModelFactory { initializer { PdfDateViewModel() } })
                    val drawingViewModel: PdfDrawingViewModel = viewModel(factory = viewModelFactory { initializer { PdfDrawingViewModel() } })
                    val exportViewModel: PdfExportViewModel = viewModel(factory = viewModelFactory {
                        initializer {
                            val sourceUri = Uri.parse(checkNotNull(entry.arguments?.getString("documentUri")))
                            PdfExportViewModel(container.pdfExporter, sourceUri)
                        }
                    })
                    PdfPreviewScreen(
                        state = previewState,
                        signatures = signatureViewModel,
                        dates = dateViewModel,
                        drawing = drawingViewModel,
                        export = exportViewModel,
                        onRetry = previewViewModel::reload,
                        onBack = { navController.popBackStack() },
                        onGoHome = { navController.popBackStack(Routes.HOME, false) },
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
}
