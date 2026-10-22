package com.zonadev.firmarpdf.ui.signing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R

@Composable
fun PdfSigningScreen(onCreateSignature: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.editor_preparation))
        OutlinedButton(onClick = onCreateSignature) { Text(stringResource(R.string.signature_title)) }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}
