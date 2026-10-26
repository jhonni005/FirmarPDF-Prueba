package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.pdf.*
import kotlinx.coroutines.CancellationException

private sealed interface PageImageState {
    data object Loading : PageImageState
    data class Ready(val bitmap: Bitmap) : PageImageState
    data class Error(val reason: PdfPreviewError) : PageImageState
}

@Composable
internal fun PdfPage(session: PdfPreviewSession, index: Int, width: Int, knownAspect: Float?, onReady: (Int, Float) -> Unit) {
    var retry by remember(session, index) { mutableIntStateOf(0) }
    val state by produceState<PageImageState>(PageImageState.Loading, session, index, width, retry) {
        value = PageImageState.Loading
        if (width <= 0) return@produceState
        try {
            val bitmap = session.renderPage(index, width)
            onReady(index, bitmap.width.toFloat() / bitmap.height)
            value = PageImageState.Ready(bitmap)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PdfPreviewException) {
            value = PageImageState.Error(failure.reason)
        }
    }
    val bitmap = (state as? PageImageState.Ready)?.bitmap
    Box(
        Modifier.fillMaxWidth().aspectRatio(knownAspect ?: bitmap?.let { it.width.toFloat() / it.height } ?: 0.7071f).background(Color.White),
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
internal fun previewErrorText(error: PdfPreviewError): Int = when (error) {
    PdfPreviewError.INACCESSIBLE -> R.string.preview_inaccessible
    PdfPreviewError.INVALID -> R.string.preview_invalid
    PdfPreviewError.PROTECTED -> R.string.preview_protected
    PdfPreviewError.MEMORY -> R.string.preview_memory
    PdfPreviewError.RENDER -> R.string.preview_render_error
}
