package com.zonadev.firmarpdf.ui.pdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfDateFormatSheet(onDismiss: () -> Unit, onSelect: (PdfDateFormat) -> Unit, error: Int?) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(1_000) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.date_format_title), style = MaterialTheme.typography.titleLarge, color = Color.Black)
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(PdfDateFormat.entries) { format ->
                    val recommended = format == PdfDateFormat.DAY_MONTH_YEAR
                    OutlinedButton(
                        onClick = { onSelect(format) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { selected = recommended },
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(if (recommended) 2.dp else 0.dp, if (recommended) MaterialTheme.colorScheme.primary else Color.Transparent),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF0F0F0), contentColor = Color.Black),
                        contentPadding = PaddingValues(8.dp),
                    ) { Text(format.format(today), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
