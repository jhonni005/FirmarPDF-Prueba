package com.zonadev.firmarpdf.ui.pdf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R

@Composable
internal fun PdfEditorToolbar(
    enabled: Boolean, loading: Boolean, hasDraft: Boolean,
    onSignature: () -> Unit, onDate: () -> Unit, onDraw: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onSignature, enabled = enabled) { Text(stringResource(R.string.signature_action)) }
                TextButton(onClick = onDate, enabled = enabled) { Text(stringResource(R.string.date_action)) }
                TextButton(onClick = onDraw, enabled = enabled) { Text(stringResource(R.string.drawing_action)) }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (hasDraft) Text(stringResource(R.string.signature_visual_draft), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
