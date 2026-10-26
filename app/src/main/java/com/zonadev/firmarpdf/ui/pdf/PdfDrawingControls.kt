package com.zonadev.firmarpdf.ui.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import java.util.Locale
import kotlin.math.round

@Composable
internal fun PdfDrawingControls(state: PdfDrawingUiState, model: PdfDrawingViewModel) {
    val enabled = state.active == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0xFF000000 to R.string.ink_black, 0xFF1565C0 to R.string.ink_blue,
                    0xFFD32F2F to R.string.ink_red, 0xFF2E7D32 to R.string.ink_green).forEach { (argb, label) ->
                    val selected = state.color == argb.toInt()
                    val description = stringResource(label)
                    Box(Modifier.size(48.dp).selectable(selected, enabled, Role.RadioButton) { model.setColor(argb.toInt()) }
                        .semantics { contentDescription = description }
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .padding(6.dp).background(Color(argb), CircleShape))
                }
            }
            TextButton(onClick = model::finishTool, enabled = enabled) { Text(stringResource(R.string.drawing_finish)) }
        }
        val width = String.format(Locale.forLanguageTag("es"), stringResource(R.string.signature_thickness_value), state.thickness)
        val description = stringResource(R.string.signature_line_width)
        Text(stringResource(R.string.signature_thickness, width), style = MaterialTheme.typography.labelMedium)
        Slider(state.thickness, { model.setThickness(round(it * 2) / 2) }, enabled = enabled, valueRange = 1f..10f, steps = 17,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description; stateDescription = width })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = model::undo, enabled = enabled && state.strokes.isNotEmpty()) { Text(stringResource(R.string.signature_undo)) }
            TextButton(onClick = model::clear, enabled = enabled && state.strokes.isNotEmpty()) { Text(stringResource(R.string.drawing_clear)) }
        }
    }
}
