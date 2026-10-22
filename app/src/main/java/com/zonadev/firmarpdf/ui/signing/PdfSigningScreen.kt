package com.zonadev.firmarpdf.ui.signing

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.pdf.PdfPreviewError
import com.zonadev.firmarpdf.data.pdf.PdfPreviewException
import com.zonadev.firmarpdf.data.pdf.PdfPreviewSession
import kotlinx.coroutines.CancellationException

@Composable
fun PdfSigningScreen(state: PdfPreviewState, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, onCreateSignature: () -> Unit = {}) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        if (state is PdfPreviewState.Ready) TextButton(onClick = onCreateSignature) {
            Text(stringResource(R.string.signature_title))
        }
        when (state) {
            PdfPreviewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.preview_loading))
                }
            }
            is PdfPreviewState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(previewErrorText(state.reason)))
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            is PdfPreviewState.Ready -> key(state.session) { PdfPages(state.session) }
        }
    }
}

@Composable
private fun PdfPages(session: PdfPreviewSession) {
    val list = rememberLazyListState()
    val visiblePage by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    val zoom = rememberSaveable(saver = PdfPreviewZoomState.Saver) { PdfPreviewZoomState() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val gutters = with(LocalDensity.current) { 24.dp.roundToPx() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.preview_page, visiblePage, session.pageCount))
            Text(stringResource(R.string.preview_zoom, (zoom.scale * 100).toInt()))
        }
        Box(
            Modifier.fillMaxSize().clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { viewportWidth = it.width; zoom.clampTo(it) }
                .pdfPreviewGestures(zoom, list),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.x
                    translationY = zoom.y
                },
                userScrollEnabled = zoom.scale <= 1.01f,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(session.pageCount, key = { it }) { index ->
                    PdfPage(session, index, (viewportWidth - gutters).coerceAtLeast(0))
                }
            }
        }
    }
}

private sealed interface PageImageState {
    data object Loading : PageImageState
    data class Ready(val bitmap: Bitmap) : PageImageState
    data class Error(val reason: PdfPreviewError) : PageImageState
}

@Composable
private fun PdfPage(session: PdfPreviewSession, index: Int, width: Int) {
    var retry by remember(session, index) { mutableIntStateOf(0) }
    val state by produceState<PageImageState>(PageImageState.Loading, session, index, width, retry) {
        value = PageImageState.Loading
        if (width <= 0) return@produceState
        try {
            value = PageImageState.Ready(session.renderPage(index, width))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PdfPreviewException) {
            value = PageImageState.Error(failure.reason)
        }
    }
    val bitmap = (state as? PageImageState.Ready)?.bitmap
    Box(
        Modifier.fillMaxWidth().aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: 0.7071f).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        when (val image = state) {
            PageImageState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.preview_page_loading), color = Color.Black)
            }
            is PageImageState.Error -> Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(previewErrorText(image.reason)), color = Color.Black)
                OutlinedButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
            }
            is PageImageState.Ready -> Image(
                bitmap = image.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.preview_page, index + 1, session.pageCount),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@StringRes
private fun previewErrorText(error: PdfPreviewError): Int = when (error) {
    PdfPreviewError.INACCESSIBLE -> R.string.preview_inaccessible
    PdfPreviewError.INVALID -> R.string.preview_invalid
    PdfPreviewError.PROTECTED -> R.string.preview_protected
    PdfPreviewError.MEMORY -> R.string.preview_memory
    PdfPreviewError.RENDER -> R.string.preview_render_error
}
