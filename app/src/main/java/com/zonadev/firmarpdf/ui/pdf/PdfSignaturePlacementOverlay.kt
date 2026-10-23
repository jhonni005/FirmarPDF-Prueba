package com.zonadev.firmarpdf.ui.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.signature.PdfSignatureBounds
import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement
import kotlin.math.roundToInt

internal enum class SignatureControl { BODY, MOVE, RESIZE, REMOVE }
internal data class SignatureVisual(val placement: PdfSignaturePlacement, val bounds: PdfSignatureBounds)
internal data class SignatureHit(val visual: SignatureVisual, val control: SignatureControl, val pointer: Offset)
internal data class SignatureDrag(val hit: SignatureHit, val bounds: PdfSignatureBounds, val pointer: Offset)

/** Todas las coordenadas del gesto son del viewport; el detector vive fuera de las páginas perezosas. */
internal class PdfSignatureGestureCallbacks(
    val viewportEnabled: Boolean,
    val hitTest: (Offset) -> SignatureHit?,
    val onPress: (SignatureHit) -> Unit,
    val onStart: (SignatureHit) -> Unit,
    val onMove: (Offset, Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
    val onTap: (SignatureHit) -> Unit,
)

internal fun signatureHitTest(
    visuals: List<SignatureVisual>, selectedId: String?, point: Offset, handleSize: Float,
): SignatureHit? {
    val selected = visuals.firstOrNull { it.placement.id == selectedId }
    if (selected != null) {
        val b = selected.bounds
        for ((control, corner) in listOf(
            SignatureControl.REMOVE to Offset(b.right, b.top),
            SignatureControl.RESIZE to Offset(b.right, b.bottom),
            SignatureControl.MOVE to Offset(b.left, b.top),
        )) {
            if (PdfSignatureBounds(corner.x - handleSize / 2, corner.y - handleSize / 2, handleSize, handleSize).contains(point.x, point.y)) {
                return SignatureHit(selected, control, point)
            }
        }
    }
    return visuals.asReversed().firstOrNull { it.bounds.contains(point.x, point.y) }
        ?.let { SignatureHit(it, SignatureControl.BODY, point) }
}

/** Adapta borde, controles y vista de arrastre del lector; medidas de pantalla independientes del zoom. */
@Composable
internal fun PdfSignaturePlacementOverlay(
    visuals: List<SignatureVisual>, state: PdfSignatureUiState, model: PdfSignatureViewModel,
    drag: SignatureDrag?, modifier: Modifier = Modifier,
) {
    val moving = drag?.takeUnless { it.hit.control == SignatureControl.RESIZE }
    Box(modifier) {
        visuals.forEach { visual ->
            if (visual.placement.id != moving?.hit?.visual?.placement?.id) {
                SignatureVisualContent(visual, state, model, interactive = true)
            }
        }
        moving?.let { SignatureVisualContent(it.hit.visual.copy(bounds = it.bounds), state, model, interactive = false) }
    }
}

@Composable
private fun SignatureVisualContent(
    visual: SignatureVisual, state: PdfSignatureUiState, model: PdfSignatureViewModel, interactive: Boolean,
) {
    val density = LocalDensity.current
    val placement = visual.placement
    val bounds = visual.bounds
    val selected = placement.id == state.selectedId
    val description = stringResource(R.string.signature_placed, placement.pageIndex + 1)
    val moveLeft = stringResource(R.string.signature_move_left)
    val moveRight = stringResource(R.string.signature_move_right)
    val moveUp = stringResource(R.string.signature_move_up)
    val moveDown = stringResource(R.string.signature_move_down)
    val grow = stringResource(R.string.signature_grow)
    val shrink = stringResource(R.string.signature_shrink)
    val remove = stringResource(R.string.signature_remove_placement)
    val actions = listOf(
        CustomAccessibilityAction(moveLeft) { model.movePlacement(placement.id, -.02f, 0f); true },
        CustomAccessibilityAction(moveRight) { model.movePlacement(placement.id, .02f, 0f); true },
        CustomAccessibilityAction(moveUp) { model.movePlacement(placement.id, 0f, -.02f); true },
        CustomAccessibilityAction(moveDown) { model.movePlacement(placement.id, 0f, .02f); true },
        CustomAccessibilityAction(grow) { model.resizePlacement(placement.id, .02f); true },
        CustomAccessibilityAction(shrink) { model.resizePlacement(placement.id, -.02f); true },
        CustomAccessibilityAction(remove) { model.removePlacement(placement.id); true },
    )
    Box(
        Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .then(if (selected) Modifier.border(1.5.dp, Color.Red) else Modifier)
            .then(if (interactive) Modifier.semantics {
                contentDescription = description
                this.selected = selected
                onClick { model.selectPlacement(placement.id); true }
                customActions = actions
            } else Modifier),
    ) {
        state.images[placement.signatureId]?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
    if (selected) {
        PlacementHandle(bounds.left, bounds.top, R.drawable.ic_move, stringResource(R.string.signature_move),
            interactive, actions.take(4), onClick = { model.selectPlacement(placement.id) })
        PlacementHandle(bounds.right, bounds.bottom, R.drawable.ic_resize, stringResource(R.string.signature_resize),
            interactive, actions.subList(4, 6), onClick = { model.resizePlacement(placement.id, .02f) })
        PlacementHandle(bounds.right, bounds.top, R.drawable.ic_delete, remove,
            interactive, emptyList(), onClick = { model.removePlacement(placement.id) })
    }
}

@Composable
private fun PlacementHandle(
    x: Float, y: Float, icon: Int, description: String,
    interactive: Boolean, actions: List<CustomAccessibilityAction>, onClick: () -> Unit,
) {
    val halfSize = with(LocalDensity.current) { 24.dp.toPx() }
    Box(
        Modifier.offset { IntOffset((x - halfSize).roundToInt(), (y - halfSize).roundToInt()) }.size(48.dp)
            .then(if (interactive) Modifier.semantics {
                contentDescription = description
                role = Role.Button
                this.onClick { onClick(); true }
                customActions = actions
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = Color.White)
        }
    }
}
