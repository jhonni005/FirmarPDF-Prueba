package com.zonadev.firmarpdf.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import com.zonadev.firmarpdf.data.history.ResultKind
import java.util.Date

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenEditor: () -> Unit,
    onCreateSignature: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium) }
        item { Text(stringResource(R.string.sign_pdf_title), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.home_preparation)) }
        item {
            Button(onClick = onOpenEditor) { Text(stringResource(R.string.open_editor)) }
        }
        item {
            OutlinedButton(onClick = onCreateSignature) { Text(stringResource(R.string.signature_title)) }
        }
        item {
            Text(stringResource(R.string.signed_documents_title), style = MaterialTheme.typography.titleLarge)
        }
        when (state) {
            HomeUiState.Loading -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.history_loading))
                }
            }
            HomeUiState.Error -> item {
                Column {
                    Text(stringResource(R.string.history_error))
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            is HomeUiState.Content -> {
                if (state.results.isEmpty()) {
                    item { Text(stringResource(R.string.history_empty)) }
                }
                items(state.results, key = { it.id }) { result ->
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
