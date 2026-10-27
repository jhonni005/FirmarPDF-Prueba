package com.zonadev.firmarpdf.ui.pdf

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.zonadev.firmarpdf.data.pdf.*

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.BuildConfig
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
import kotlinx.coroutines.launch

@Composable
fun PdfPreviewScreen(
    state: PdfPreviewState, signatures: PdfSignatureViewModel, dates: PdfDateViewModel, drawing: PdfDrawingViewModel,
    export: PdfExportViewModel,
    onRetry: () -> Unit, onBack: () -> Unit, onGoHome: () -> Unit, onCreateSignature: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exportState by export.uiState.collectAsStateWithLifecycle()
    val signatureDraft by signatures.uiState.collectAsStateWithLifecycle()
    val dateDraft by dates.uiState.collectAsStateWithLifecycle()
    val drawingDraft by drawing.uiState.collectAsStateWithLifecycle()
    val draft = PdfExportDraft(
        signatureDraft.placements.toList(),
        dateDraft.dates.map { PdfExportDate(it.placement, dateDraft.images[it.placement.id]) },
        drawingDraft.strokes.map { PdfExportStroke(it.pageIndex, it.color, it.width, it.points.toList()) },
        (state as? PdfPreviewState.Ready)?.session?.pageCount ?: 0,
    )
    val saving = exportState is PdfExportUiState.Saving
    val context = LocalContext.current
    // null selects PDFBox; keep the requested PDFium mode across the Android 9 permission dialog.
    var pendingPdfiumSaveMode by rememberSaveable { mutableStateOf<PdfiumSaveMode?>(null) }
    var pendingEmbedPdf by rememberSaveable { mutableStateOf(false) }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val saveMode = pendingPdfiumSaveMode
            if (pendingEmbedPdf) export.saveEmbedPdf(draft)
            else if (saveMode != null) export.savePdfium(draft, saveMode) else export.save(draft)
        } else export.permissionDenied()
    }
    var discard by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        if (saving || drawingDraft.active != null || export.hasUnsaved(draft)) discard = true else onBack()
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.pdf_discard_title)) },
        text = { Text(stringResource(R.string.pdf_discard_message)) },
        confirmButton = { TextButton(onClick = { export.cancel(); discard = false; onBack() }) { Text(stringResource(R.string.signature_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) } },
    )
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
            TextButton(onClick = {
                pendingPdfiumSaveMode = null
                pendingEmbedPdf = false
                if (Build.VERSION.SDK_INT == 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else export.save(draft)
            }, enabled = state is PdfPreviewState.Ready && draft.hasAnnotations && !saving && !signatureDraft.busy && drawingDraft.active == null) {
                Text(stringResource(R.string.pdf_save))
            }
        }
        if (BuildConfig.DEBUG) for (saveMode in PdfiumSaveMode.entries) {
            TextButton(onClick = {
                if (export.checkPdfiumDraft(draft)) {
                    pendingEmbedPdf = false
                    pendingPdfiumSaveMode = saveMode
                    if (Build.VERSION.SDK_INT == 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else export.savePdfium(draft, saveMode)
                }
            }, enabled = state is PdfPreviewState.Ready && !saving && !signatureDraft.busy && drawingDraft.active == null,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                Text(stringResource(when (saveMode) {
                    PdfiumSaveMode.FULL -> R.string.pdf_save_pdfium
                    PdfiumSaveMode.INCREMENTAL -> R.string.pdf_save_pdfium_incremental
                }))
            }
        }
        if (export.embedPdfAvailable) {
            TextButton(onClick = {
                if (export.checkEmbedPdfDraft(draft)) {
                    pendingEmbedPdf = true
                    pendingPdfiumSaveMode = null
                    if (Build.VERSION.SDK_INT == 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else export.saveEmbedPdf(draft)
                }
            }, enabled = state is PdfPreviewState.Ready && draft.hasAnnotations && !saving && !signatureDraft.busy && drawingDraft.active == null,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.pdf_save_embedpdf_selective))
            }
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
            is PdfPreviewState.Ready -> {
                if (state.recentSaveFailed) Text(stringResource(R.string.recent_record_error), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                Box(Modifier.weight(1f)) {
                    key(state.session) { PdfPages(state.session, signatures, dates, drawing) }
                    if (saving) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .95f)).pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                            }
                        }, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                val savingState = exportState as PdfExportUiState.Saving
                                Text(stringResource(when {
                                    savingState.embedPdf && savingState.stage == PdfExportStage.ANNOTATE -> R.string.embedpdf_export_annotate
                                    savingState.pdfium && savingState.stage == PdfExportStage.ANNOTATE -> R.string.pdfium_export_annotate
                                    else -> exportProgressText(savingState.stage)
                                }))
                                TextButton(onClick = export::cancel) { Text(stringResource(R.string.cancel)) }
                            }
                        }
                    }
                }
            }
        }
        (exportState as? PdfExportUiState.Error)?.let { failure ->
            Text(stringResource(exportErrorText(failure.reason)), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = export::dismiss) { Text(stringResource(R.string.dismiss_error)) }
        }
    }
    (exportState as? PdfExportUiState.Saved)?.result?.let { result ->
        AlertDialog(onDismissRequest = export::dismiss,
            title = { Text(stringResource(R.string.pdf_saved_title)) },
            text = { Column {
                Text(stringResource(R.string.pdf_saved_message, result.displayName))
                if (!result.recorded) Text(stringResource(R.string.pdf_saved_history_failed))
            } },
            confirmButton = { TextButton(onClick = onGoHome) { Text(stringResource(R.string.pdf_go_home)) } },
            dismissButton = { TextButton(onClick = export::dismiss) { Text(stringResource(R.string.pdf_stay)) } },
        )
    }
}

@Composable
private fun PdfPages(session: PdfPreviewSession, signatures: PdfSignatureViewModel, dates: PdfDateViewModel, drawing: PdfDrawingViewModel) {
    val list = rememberLazyListState()
    val zoom = rememberSaveable(saver = PdfPreviewZoomState.Saver) { PdfPreviewZoomState() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf<PlacementDrag?>(null) }
    val state by signatures.uiState.collectAsStateWithLifecycle()
    val dateState by dates.uiState.collectAsStateWithLifecycle()
    val drawState by drawing.uiState.collectAsStateWithLifecycle()
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
    fun isDate(id: String) = dateState.dates.any { it.placement.id == id }
    val selectedId = dateState.selectedId ?: state.selectedId
    val actions = PdfPlacementActions(
        select = { id ->
            if (isDate(id)) { signatures.clearSelection(); dates.select(id) }
            else { dates.select(null); signatures.selectPlacement(id) }
        },
        move = { id, dx, dy -> if (isDate(id)) dates.move(id, dx, dy) else signatures.movePlacement(id, dx, dy) },
        resize = { id, delta -> if (isDate(id)) dates.resize(id, delta) else signatures.resizePlacement(id, delta) },
        remove = { id -> if (isDate(id)) dates.remove(id) else signatures.removePlacement(id) },
    )
    val allPlacements = state.placements + dateState.dates.map { it.placement }
    val visuals = allPlacements.sortedBy { it.id == selectedId }.mapNotNull { placement ->
        pageBounds.firstOrNull { it.pageIndex == placement.pageIndex }
            ?.let { PlacementVisual(placement, placementBounds(placement, it.bounds), dateState.dates.firstOrNull { date -> date.placement.id == placement.id }?.text) }
    }
    val gestures = PdfPlacementGestureCallbacks(
        viewportEnabled = !state.selecting,
        hitTest = { point -> if (state.busy || drawState.drawing) null else placementHitTest(visuals, selectedId, point, handleSize) },
        onPress = { actions.select(it.visual.placement.id) },
        onStart = { hit ->
            drag = PlacementDrag(hit, hit.visual.bounds, hit.pointer)
            scope.launch { list.stopScroll() }
        },
        onMove = { delta, pointer ->
            drag?.let { active ->
                if (active.hit.control == PlacementControl.RESIZE) {
                    val page = pages().firstOrNull { it.pageIndex == active.hit.visual.placement.pageIndex }
                    if (page != null) {
                        val ratio = active.bounds.height / active.bounds.width
                        actions.resize(active.hit.visual.placement.id, (delta.x + delta.y / ratio) / (2f * page.bounds.width))
                    }
                    drag = active.copy(pointer = pointer)
                } else drag = active.copy(bounds = active.bounds.translated(delta.x, delta.y), pointer = pointer)
            }
        },
        onEnd = {
            drag?.takeUnless { it.hit.control == PlacementControl.RESIZE }?.let { active ->
                signaturePlacementForDrop(active.hit.visual.placement, active.bounds, pages())?.let {
                    if (isDate(it.id)) dates.finishDrag(it) else signatures.finishPlacementDrag(it)
                }
            }
            drag = null
        },
        onCancel = { drag = null },
        onTap = { if (it.control == PlacementControl.REMOVE) actions.remove(it.visual.placement.id) },
    )
    val drawingGestures = PdfDrawingGestureCallbacks(
        enabled = drawState.drawing,
        begin = { point ->
            val page = pages().firstOrNull { it.bounds.contains(point.x, point.y) }
            if (page != null) {
                val normalized = com.zonadev.firmarpdf.data.signature.SignaturePoint(
                    ((point.x - page.bounds.left) / page.bounds.width).coerceIn(0f, 1f),
                    ((point.y - page.bounds.top) / page.bounds.height).coerceIn(0f, 1f),
                )
                drawing.begin(page.pageIndex, normalized, with(density) { drawState.thickness.dp.toPx() } / page.bounds.width)
            }
        },
        append = { point ->
            val active = drawing.uiState.value.active
            val page = pages().firstOrNull { it.pageIndex == active?.pageIndex }
            if (page != null) drawing.append(com.zonadev.firmarpdf.data.signature.SignaturePoint(
                ((point.x - page.bounds.left) / page.bounds.width).coerceIn(0f, 1f),
                ((point.y - page.bounds.top) / page.bounds.height).coerceIn(0f, 1f),
            ))
        },
        finish = drawing::finishStroke,
    )
    // El detector no vive en la página de origen: puede salir de composición durante este desplazamiento.
    LaunchedEffect(drag?.hit?.visual?.placement?.id, drag?.hit?.control) {
        if (drag == null || drag?.hit?.control == PlacementControl.RESIZE) return@LaunchedEffect
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
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { viewport = it; zoom.clampTo(it) }
                .pdfPreviewGestures(zoom, list, gestures, drawingGestures),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.x
                    translationY = zoom.y
                },
                userScrollEnabled = zoom.scale <= 1.01f && drag == null && !state.selecting && !drawState.drawing,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(session.pageCount, key = { it }) { index ->
                    PdfPage(session, index, (viewport.width - padding * 2).coerceAtLeast(0), state.pageAspects[index], signatures::pageReady)
                }
            }
            PdfDrawingOverlay(drawState, pageBounds, Modifier.fillMaxSize())
            PdfPlacementOverlay(visuals, state.images + dateState.images, selectedId, actions, drag, Modifier.fillMaxSize())
        }
        state.error?.takeIf { !state.libraryVisible }?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = signatures::clearError) { Text(stringResource(R.string.dismiss_error)) }
            }
        }
        dateState.error?.takeIf { !dateState.sheetVisible }?.let {
            TextButton(onClick = dates::clearError) { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        if (drawState.drawing) {
            Text(stringResource(R.string.drawing_hint), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            PdfDrawingControls(drawState, drawing)
        }
        PdfEditorToolbar(
            enabled = !state.busy && !drawState.drawing && target() != null,
            loading = state.selecting,
            hasDraft = allPlacements.isNotEmpty() || drawState.strokes.isNotEmpty(),
            onSignature = { dates.select(null); signatures.openLibrary() },
            onDate = { signatures.clearSelection(); dates.openSheet() },
            onDraw = {
                signatures.clearSelection(); dates.select(null)
                scope.launch { list.stopScroll(); drawing.start() }
            },
        )
    }
    if (state.libraryVisible) PdfSignatureLibrarySheet(state, signatures) { id ->
        scope.launch {
            list.stopScroll()
            signatures.selectSavedSignature(id, target())
        }
    }
    if (dateState.sheetVisible) PdfDateFormatSheet(dates::dismissSheet, { format ->
        scope.launch { list.stopScroll(); dates.choose(format, target()) }
    }, dateState.error)
}

private fun exportProgressText(stage: PdfExportStage) = when (stage) {
    PdfExportStage.SOURCE -> R.string.pdf_export_source
    PdfExportStage.LOAD -> R.string.pdf_export_load
    PdfExportStage.ANNOTATE -> R.string.pdf_export_annotate
    PdfExportStage.SAVE -> R.string.pdf_export_write
    PdfExportStage.PUBLISH -> R.string.pdf_export_verify
}

private fun exportErrorText(reason: PdfExportError) = when (reason) {
    PdfExportError.EMBEDPDF_SIGNATURES_ONLY -> R.string.embedpdf_export_signatures_only
    PdfExportError.EMBEDPDF_UNAVAILABLE -> R.string.embedpdf_export_unavailable
    PdfExportError.NO_SIGNATURES -> R.string.pdfium_export_no_signatures
    PdfExportError.PDFIUM_SIGNATURES_ONLY -> R.string.pdfium_export_signatures_only
    PdfExportError.SOURCE -> R.string.pdf_export_source_error
    PdfExportError.PROTECTED -> R.string.pdf_export_protected
    PdfExportError.CERTIFIED -> R.string.pdf_export_certified
    PdfExportError.INVALID -> R.string.pdf_export_invalid
    PdfExportError.ANNOTATIONS -> R.string.pdf_export_annotations_error
    PdfExportError.WRITE -> R.string.pdf_export_write_error
    PdfExportError.VERIFY -> R.string.pdf_export_verify_error
    PdfExportError.BUSY -> R.string.pdf_export_busy
    PdfExportError.MEMORY -> R.string.pdf_export_memory
    PdfExportError.PERMISSION -> R.string.pdf_export_permission
}
