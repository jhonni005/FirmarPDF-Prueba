package com.zonadev.firmarpdf.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zonadev.firmarpdf.app.AppContainer
import com.zonadev.firmarpdf.ui.editor.EditorScreen
import com.zonadev.firmarpdf.ui.home.HomeScreen
import com.zonadev.firmarpdf.ui.home.HomeViewModel
import com.zonadev.firmarpdf.ui.signature.SignatureScreen

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val SIGNATURE = "signature"
}

@Composable
fun FirmaPdfApp(container: AppContainer) {
    val navController = rememberNavController()
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(navController, startDestination = Routes.HOME, modifier = Modifier.padding(innerPadding)) {
            composable(Routes.HOME) {
                val viewModel: HomeViewModel = viewModel(factory = viewModelFactory {
                    initializer { HomeViewModel(container.historyRepository) }
                })
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onOpenEditor = { navController.navigate(Routes.EDITOR) { launchSingleTop = true } },
                    onCreateSignature = { navController.navigate(Routes.SIGNATURE) { launchSingleTop = true } },
                    onRetry = viewModel::loadHistory,
                )
            }
            composable(Routes.EDITOR) {
                EditorScreen(
                    onCreateSignature = { navController.navigate(Routes.SIGNATURE) { launchSingleTop = true } },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SIGNATURE) {
                SignatureScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
