package com.zonadev.firmarpdf.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.signedpdf.ResultKind
import java.util.Date
import android.graphics.Bitmap
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfEntity

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenRecent: (String) -> Unit,
    loadLocation: suspend (String) -> String?,
    onRetryRecents: () -> Unit,
    loadThumbnail: suspend (RecentPdfEntity) -> Bitmap?,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier,
        // El FAB mide al menos 56 dp y tiene margen inferior: dejar libre la última fila.
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenMenu) { Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.drawer_open)) }
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (state.checkingSelection) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.selection_checking))
                }
            }
        }
        state.selectionError?.let { error ->
            item {
                Text(
                    stringResource(when (error) {
                        PdfSelectionError.UNREADABLE -> R.string.selection_unreadable
                        PdfSelectionError.PICKER_UNAVAILABLE -> R.string.pdf_picker_unavailable
                    }),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item { Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleLarge) }
        when (val recent = state.recentPdfs) {
            RecentPdfsState.Loading -> item { CircularProgressIndicator() }
            RecentPdfsState.Error -> item {
                Column {
                    Text(stringResource(R.string.recent_error))
                    OutlinedButton(onClick = onRetryRecents) { Text(stringResource(R.string.retry)) }
                }
            }
            is RecentPdfsState.Content -> {
                if (recent.results.isEmpty()) item { Text(stringResource(R.string.recent_empty)) }
                items(recent.results, key = { "recent_${it.uri}" }) { pdf ->
                    RecentPdfRow(pdf, loadThumbnail, loadLocation, { onOpenRecent(pdf.uri) })
                }
            }
        }
    }
}
