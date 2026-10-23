package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.pdf.PdfPreviewError
import com.zonadev.firmarpdf.data.pdf.PdfPreviewException
import com.zonadev.firmarpdf.data.pdf.PdfPreviewSession
import com.zonadev.firmarpdf.data.signature.PdfSignatureBounds
import com.zonadev.firmarpdf.data.signature.placementBounds
import com.zonadev.firmarpdf.data.signature.previewPageBounds
import com.zonadev.firmarpdf.data.signature.signaturePlacementForDrop
import com.zonadev.firmarpdf.data.signature.signatureTargetForViewport
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PdfPreviewScreen(
    state: PdfPreviewState, signatures: PdfSignatureViewModel,
    onRetry: () -> Unit, onBack: () -> Unit, onCreateSignature: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, signatures) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) signatures.onViewerResumed()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(signatures) { signatures.createRequests.collect { onCreateSignature() } }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
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
            is PdfPreviewState.Ready -> key(state.session) { PdfPages(state.session, signatures) }
        }
    }
}

@Composable
private fun PdfPages(session: PdfPreviewSession, signatures: PdfSignatureViewModel) {
    val list = rememberLazyListState()
    val zoom = rememberSaveable(saver = PdfPreviewZoomState.Saver) { PdfPreviewZoomState() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf<SignatureDrag?>(null) }
    val state by signatures.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val padding = with(density) { 12.dp.roundToPx() }
    val handleSize = with(density) { 48.dp.toPx() }
    val viewportBounds = PdfSignatureBounds(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
    // offsets de LazyColumn incluyen su espacio de contenido, no el origen físico del viewport.
    fun pages() = list.layoutInfo.let { layout ->
        layout.visibleItemsInfo.filter { it.index in state.pageAspects }.map {
            previewPageBounds(
                it.index, it.offset, it.size, layout.viewportStartOffset,
                viewport.width.toFloat(), viewport.height.toFloat(), padding.toFloat(), zoom.scale, zoom.x, zoom.y,
            )
        }.filter { it.bounds.intersection(viewportBounds) != null }
    }
    fun target() = signatureTargetForViewport(pages(), viewportBounds)
    val pageBounds = pages()
    val visuals = state.placements.sortedBy { it.id == state.selectedId }.mapNotNull { placement ->
        pageBounds.firstOrNull { it.pageIndex == placement.pageIndex }
            ?.let { SignatureVisual(placement, placementBounds(placement, it.bounds)) }
    }
    val gestures = PdfSignatureGestureCallbacks(
        viewportEnabled = !state.selecting,
        hitTest = { point -> if (state.busy) null else signatureHitTest(visuals, state.selectedId, point, handleSize) },
        onPress = { signatures.selectPlacement(it.visual.placement.id) },
        onStart = { hit ->
            drag = SignatureDrag(hit, hit.visual.bounds, hit.pointer)
            scope.launch { list.stopScroll() }
        },
        onMove = { delta, pointer ->
            drag?.let { active ->
                if (active.hit.control == SignatureControl.RESIZE) {
                    val page = pages().firstOrNull { it.pageIndex == active.hit.visual.placement.pageIndex }
                    if (page != null) {
                        val ratio = active.bounds.height / active.bounds.width
                        signatures.resizePlacement(active.hit.visual.placement.id, (delta.x + delta.y / ratio) / (2f * page.bounds.width))
                    }
                    drag = active.copy(pointer = pointer)
                } else drag = active.copy(bounds = active.bounds.translated(delta.x, delta.y), pointer = pointer)
            }
        },
        onEnd = {
            drag?.takeUnless { it.hit.control == SignatureControl.RESIZE }?.let { active ->
                signaturePlacementForDrop(active.hit.visual.placement, active.bounds, pages())?.let(signatures::finishPlacementDrag)
            }
            drag = null
        },
        onCancel = { drag = null },
        onTap = { if (it.control == SignatureControl.REMOVE) signatures.removePlacement(it.visual.placement.id) },
    )
    // El detector no vive en la página de origen: puede salir de composición durante este desplazamiento.
    LaunchedEffect(drag?.hit?.visual?.placement?.id, drag?.hit?.control) {
        if (drag == null || drag?.hit?.control == SignatureControl.RESIZE) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val pointer = drag?.pointer ?: break
            val edge = minOf(with(density) { 64.dp.toPx() }, viewport.height / 4f).coerceAtLeast(1f)
            val direction = when {
                pointer.y < edge -> -((edge - pointer.y) / edge).coerceIn(0f, 1f)
                pointer.y > viewport.height - edge -> ((pointer.y - viewport.height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            val delta = direction * with(density) { 550.dp.toPx() } * seconds
            if (zoom.scale > 1.01f) zoom.pan(Offset(0f, -delta), viewport, list)
            else list.dispatchRawDelta(delta)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.preview_page, (target()?.page?.pageIndex ?: list.firstVisibleItemIndex) + 1, session.pageCount))
            Text(stringResource(R.string.preview_zoom, (zoom.scale * 100).toInt()))
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { viewport = it; zoom.clampTo(it) }
                .pdfPreviewGestures(zoom, list, gestures),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.x
                    translationY = zoom.y
                },
                userScrollEnabled = zoom.scale <= 1.01f && drag == null && !state.selecting,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(session.pageCount, key = { it }) { index ->
                    PdfPage(session, index, (viewport.width - padding * 2).coerceAtLeast(0), state.pageAspects[index], signatures::pageReady)
                }
            }
            PdfSignaturePlacementOverlay(visuals, state, signatures, drag, Modifier.fillMaxSize())
        }
        state.error?.takeIf { !state.libraryVisible }?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = signatures::clearError) { Text(stringResource(R.string.dismiss_error)) }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = signatures::openLibrary, enabled = !state.busy && target() != null) {
                    Icon(painterResource(R.drawable.ic_signature), null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.signature_action))
                }
                if (state.selecting) CircularProgressIndicator(Modifier.size(24.dp))
                if (state.placements.isNotEmpty()) Text(
                    stringResource(R.string.signature_visual_draft), Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    if (state.libraryVisible) PdfSignatureLibrarySheet(state, signatures) { id ->
        scope.launch {
            list.stopScroll()
            signatures.selectSavedSignature(id, target())
        }
    }
}

private sealed interface PageImageState {
    data object Loading : PageImageState
    data class Ready(val bitmap: Bitmap) : PageImageState
    data class Error(val reason: PdfPreviewError) : PageImageState
}

@Composable
private fun PdfPage(session: PdfPreviewSession, index: Int, width: Int, knownAspect: Float?, onReady: (Int, Float) -> Unit) {
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
private fun previewErrorText(error: PdfPreviewError): Int = when (error) {
    PdfPreviewError.INACCESSIBLE -> R.string.preview_inaccessible
    PdfPreviewError.INVALID -> R.string.preview_invalid
    PdfPreviewError.PROTECTED -> R.string.preview_protected
    PdfPreviewError.MEMORY -> R.string.preview_memory
    PdfPreviewError.RENDER -> R.string.preview_render_error
}
