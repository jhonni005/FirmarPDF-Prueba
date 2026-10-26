package com.zonadev.firmarpdf.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import kotlinx.coroutines.launch

@Composable
internal fun HomeNavigationDrawer(
    drawerState: DrawerState, enabled: Boolean, snackbar: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val links = remember(context) { HomeAppLinks(context) }
    val scope = rememberCoroutineScope()
    fun action(block: () -> Int?) {
        scope.launch {
            drawerState.close()
            block()?.let { snackbar.showSnackbar(context.getString(it)) }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) drawerState.close() }
    ModalNavigationDrawer(
        drawerState = drawerState, gesturesEnabled = enabled,
        drawerContent = {
            ModalDrawerSheet {
                Text(stringResource(R.string.app_name), Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_privacy)) }, selected = false,
                    onClick = { action { links.openLegal(R.string.privacy_policy_url) } })
                NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_terms)) }, selected = false,
                    onClick = { action { links.openLegal(R.string.terms_conditions_url) } })
                NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_rate)) }, selected = false,
                    onClick = { action { links.rate() } })
                NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_share)) }, selected = false,
                    onClick = { action { links.share() } })
                Text(stringResource(R.string.app_version, links.version), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
        },
        content = content,
    )
}
