package com.zonadev.firmarpdf.ui.signature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.signature.SignaturePoint
import com.zonadev.firmarpdf.data.signature.drawSignature
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.round
import java.util.Locale

@Composable
fun CreateSignatureScreen(model: CreateSignatureViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var discard by rememberSaveable { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    fun requestExit() {
        if (model.saving) return
        if (model.strokes.isNotEmpty() || model.active != null) discard = true else onBack()
    }
    BackHandler { requestExit() }
    LaunchedEffect(model) { model.saved.collect { onBack() } }
    val palette = listOf(
        0xFF000000 to R.string.ink_black, 0xFF424242 to R.string.ink_gray,
        0xFF1565C0 to R.string.ink_blue, 0xFF0D274D to R.string.ink_navy,
        0xFFD32F2F to R.string.ink_red, 0xFF2E7D32 to R.string.ink_green,
        0xFF00838F to R.string.ink_teal, 0xFF7B1FA2 to R.string.ink_purple,
        0xFFAD1457 to R.string.ink_pink, 0xFF795548 to R.string.ink_brown,
    )
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.signature_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { requestExit() }, enabled = !model.saving) { Text(stringResource(R.string.back)) }
        }
        Text(stringResource(R.string.signature_choose_color), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(palette.size) { index ->
                val (argb, label) = palette[index]
                val selected = model.color == argb.toInt()
                val description = stringResource(label)
                Box(Modifier.size(48.dp)
                    .selectable(selected, enabled = !model.saving, role = Role.RadioButton, onClick = { model.color = argb.toInt() })
                    .semantics { contentDescription = description }
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .padding(6.dp).background(Color(argb), CircleShape))
            }
        }
        val thicknessText = String.format(Locale.forLanguageTag("es"), stringResource(R.string.signature_thickness_value), model.thickness)
        Text(stringResource(R.string.signature_thickness, thicknessText), style = MaterialTheme.typography.titleMedium)
        val sliderDescription = stringResource(R.string.signature_line_width)
        Slider(value = model.thickness, onValueChange = { model.thickness = round(it * 2) / 2 },
            valueRange = 1f..10f, steps = 17, enabled = !model.saving,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = sliderDescription; stateDescription = thicknessText })
        val canvasDescription = stringResource(R.string.signature_canvas)
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color.White)
            .border(1.dp, Color.LightGray).clipToBounds()
            .semantics { contentDescription = canvasDescription }
            .onSizeChanged { canvasSize = it }
            .pointerInput(model, model.saving) {
                if (model.saving) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun point(x: Float, y: Float) = SignaturePoint((x / size.width).coerceIn(0f, 1f), (y / size.height).coerceIn(0f, 1f))
                    model.begin(point(down.position.x, down.position.y), with(density) { model.thickness.dp.toPx() } / size.width)
                    down.consume()
                    var complete = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            model.append(point(change.position.x, change.position.y))
                            change.consume()
                            if (!change.pressed) { complete = true; break }
                        }
                    } finally { model.finish(cancelled = !complete) }
                }
            }) {
            val strokes = model.strokes + listOfNotNull(model.active)
            drawIntoCanvas { drawSignature(it.nativeCanvas, strokes, size.width, size.height) }
        }
        if (model.error) Text(stringResource(R.string.signature_save_error), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = model::undo, enabled = model.strokes.isNotEmpty() && !model.saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.signature_undo)) }
            OutlinedButton(onClick = model::clear, enabled = model.strokes.isNotEmpty() && !model.saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.signature_clear)) }
        }
        Button(onClick = { model.save(canvasSize.width.toFloat() / canvasSize.height.coerceAtLeast(1)) },
            enabled = model.strokes.isNotEmpty() && model.active == null && !model.saving && canvasSize.width > 0 && canvasSize.height > 0,
            modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (model.saving) R.string.signature_saving else R.string.signature_save))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.signature_discard_title)) },
        text = { Text(stringResource(R.string.signature_discard_message)) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text(stringResource(R.string.signature_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.signature_keep_drawing)) } })
}
