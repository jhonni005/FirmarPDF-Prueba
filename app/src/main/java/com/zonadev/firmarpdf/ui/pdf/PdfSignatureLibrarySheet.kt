package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import kotlinx.coroutines.CancellationException

/** Estructura adaptada de la biblioteca del lector; dos columnas y PNG por ID. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfSignatureLibrarySheet(
    state: PdfSignatureUiState,
    model: PdfSignatureViewModel,
    onSelect: (String) -> Unit,
) {
    var pendingDeletion by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme.copy(
        surface = Color.White, onSurface = Color(0xFF202124),
        surfaceVariant = Color(0xFFF3F3F8), onSurfaceVariant = Color(0xFF555560),
        error = Color(0xFFB3261E),
    )
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .82f
    MaterialTheme(colorScheme = colors) {
        ModalBottomSheet(
            onDismissRequest = model::dismissLibrary,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White,
            contentColor = colors.onSurface,
            scrimColor = Color.Black.copy(alpha = .48f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(width = 30.dp, height = 4.dp, color = Color(0xFF49454F)) },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.signature_library_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.signature_library_subtitle), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    FilledTonalIconButton(
                        onClick = model::createSignature, enabled = !state.busy,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFEADDFA), contentColor = Color(0xFF2C1B47),
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.signature_new))
                    }
                }
                Spacer(Modifier.height(18.dp))
                state.error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.refresh() }, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
                }
                when {
                    state.loading -> Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.signature_library_loading), Modifier.padding(top = 8.dp))
                    }
                    state.savedIds.isEmpty() -> Text(stringResource(R.string.signature_library_empty), Modifier.padding(vertical = 24.dp))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(state.savedIds, key = { _, id -> id }) { index, id ->
                            SignatureLibraryItem(
                                id, index + 1, model, state.busy,
                                onSelect = { onSelect(id) },
                                onDelete = { pendingDeletion = id },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = model::createSignature, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = CircleShape, border = BorderStroke(1.dp, Color(0xFF747478)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null)
                    Text(stringResource(R.string.signature_new), Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        pendingDeletion?.let { id ->
            AlertDialog(
                onDismissRequest = { pendingDeletion = null },
                title = { Text(stringResource(R.string.signature_delete_title)) },
                text = { Text(stringResource(R.string.signature_delete_message)) },
                confirmButton = {
                    TextButton(onClick = { pendingDeletion = null; model.deleteSavedSignature(id) }, enabled = !state.busy) {
                        Text(stringResource(R.string.signature_delete))
                    }
                },
                dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun SignatureLibraryItem(
    id: String, number: Int, model: PdfSignatureViewModel, busy: Boolean,
    onSelect: () -> Unit, onDelete: () -> Unit,
) {
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember(id, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, id, retry) {
        value = null
        try {
            value = model.thumbnail(id)
            failed = value == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            failed = true
        } catch (_: Exception) {
            failed = true
        }
    }
    val description = stringResource(R.string.signature_thumbnail, number)
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = !busy && bitmap != null, onClick = onSelect)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(18.dp), color = Color(0xFFFCF0F1),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).padding(8.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color.White)) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            } ?: if (failed) {
                Column(Modifier.align(Alignment.Center).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.signature_thumbnail_error), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++ }, enabled = !busy) { Text(stringResource(R.string.retry)) }
                }
            } else {
                CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), strokeWidth = 2.dp)
            }
            IconButton(
                onClick = onDelete, enabled = !busy,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp).background(Color.White.copy(alpha = .9f), RoundedCornerShape(12.dp)),
            ) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.signature_delete_saved, number), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
