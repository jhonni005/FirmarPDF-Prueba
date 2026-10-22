package com.zonadev.firmarpdf.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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

@Composable
fun HomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier,
        // El FAB mide al menos 56 dp y tiene margen inferior: dejar libre la última fila.
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium) }
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
        state.selectedPdf?.let { pdf ->
            item {
                Text(stringResource(R.string.selected_pdf_title), style = MaterialTheme.typography.titleLarge)
            }
            item(key = "selected_pdf") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pdf.displayName ?: stringResource(R.string.pdf_without_name), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.pdf_pending_signature))
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.signed_documents_title), style = MaterialTheme.typography.titleLarge)
        }
        when (val signedPdfs = state.signedPdfs) {
            SignedPdfsState.Loading -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.history_loading))
                }
            }
            SignedPdfsState.Error -> item {
                Column {
                    Text(stringResource(R.string.history_error))
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            is SignedPdfsState.Content -> {
                if (signedPdfs.results.isEmpty()) {
                    item { Text(stringResource(R.string.history_empty)) }
                }
                items(signedPdfs.results, key = { "signed_${it.id}" }) { result ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(result.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.signed_at, DateFormat.getDateFormat(context).format(Date(result.signedAt))))
                        Text(stringResource(when (result.resultKind) {
                            ResultKind.NEW_DOCUMENT -> R.string.result_new_document
                            ResultKind.UPDATED_SOURCE -> R.string.result_updated_source
                        }))
                    }
                }
            }
        }
    }
}
