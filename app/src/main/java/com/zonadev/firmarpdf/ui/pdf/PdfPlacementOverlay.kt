package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
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

internal enum class PlacementControl { BODY, MOVE, RESIZE, REMOVE }
internal data class PlacementVisual(val placement: PdfSignaturePlacement, val bounds: PdfSignatureBounds, val dateText: String? = null)
internal data class PlacementHit(val visual: PlacementVisual, val control: PlacementControl, val pointer: Offset)
internal data class PlacementDrag(val hit: PlacementHit, val bounds: PdfSignatureBounds, val pointer: Offset)

internal class PdfPlacementActions(
    val select: (String) -> Unit,
    val move: (String, Float, Float) -> Unit,
    val resize: (String, Float) -> Unit,
    val remove: (String) -> Unit,
)

/** Todas las coordenadas del gesto son del viewport; el detector vive fuera de las páginas perezosas. */
internal class PdfPlacementGestureCallbacks(
    val viewportEnabled: Boolean,
    val hitTest: (Offset) -> PlacementHit?,
    val onPress: (PlacementHit) -> Unit,
    val onStart: (PlacementHit) -> Unit,
    val onMove: (Offset, Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
    val onTap: (PlacementHit) -> Unit,
)

internal fun placementHitTest(
    visuals: List<PlacementVisual>, selectedId: String?, point: Offset, handleSize: Float,
): PlacementHit? {
    val selected = visuals.firstOrNull { it.placement.id == selectedId }
    if (selected != null) {
        val b = selected.bounds
        for ((control, corner) in listOf(
            PlacementControl.REMOVE to Offset(b.right, b.top),
            PlacementControl.RESIZE to Offset(b.right, b.bottom),
            PlacementControl.MOVE to Offset(b.left, b.top),
        )) {
            if (PdfSignatureBounds(corner.x - handleSize / 2, corner.y - handleSize / 2, handleSize, handleSize).contains(point.x, point.y)) {
                return PlacementHit(selected, control, point)
            }
        }
    }
    return visuals.asReversed().firstOrNull { it.bounds.contains(point.x, point.y) }
        ?.let { PlacementHit(it, PlacementControl.BODY, point) }
}

/** Adapta borde, controles y vista de arrastre del lector; medidas de pantalla independientes del zoom. */
@Composable
internal fun PdfPlacementOverlay(
    visuals: List<PlacementVisual>, images: Map<String, Bitmap>, selectedId: String?, actions: PdfPlacementActions,
    drag: PlacementDrag?, modifier: Modifier = Modifier,
) {
    val moving = drag?.takeUnless { it.hit.control == PlacementControl.RESIZE }
    Box(modifier) {
        visuals.forEach { visual ->
            if (visual.placement.id != moving?.hit?.visual?.placement?.id) {
                PlacementVisualContent(visual, images, selectedId, actions, interactive = true)
            }
        }
        moving?.let { PlacementVisualContent(it.hit.visual.copy(bounds = it.bounds), images, selectedId, actions, interactive = false) }
    }
}

@Composable
private fun PlacementVisualContent(
    visual: PlacementVisual, images: Map<String, Bitmap>, selectedId: String?, actions: PdfPlacementActions, interactive: Boolean,
) {
    val density = LocalDensity.current
    val placement = visual.placement
    val bounds = visual.bounds
    val selected = placement.id == selectedId
    val description = visual.dateText?.let { stringResource(R.string.date_placed, it, placement.pageIndex + 1) }
        ?: stringResource(R.string.signature_placed, placement.pageIndex + 1)
    val moveLeft = stringResource(R.string.signature_move_left)
    val moveRight = stringResource(R.string.signature_move_right)
    val moveUp = stringResource(R.string.signature_move_up)
    val moveDown = stringResource(R.string.signature_move_down)
    val grow = stringResource(R.string.signature_grow)
    val shrink = stringResource(R.string.signature_shrink)
    val remove = stringResource(if (visual.dateText == null) R.string.signature_remove_placement else R.string.date_remove)
    val accessibilityActions = listOf(
        CustomAccessibilityAction(moveLeft) { actions.move(placement.id, -.02f, 0f); true },
        CustomAccessibilityAction(moveRight) { actions.move(placement.id, .02f, 0f); true },
        CustomAccessibilityAction(moveUp) { actions.move(placement.id, 0f, -.02f); true },
        CustomAccessibilityAction(moveDown) { actions.move(placement.id, 0f, .02f); true },
        CustomAccessibilityAction(grow) { actions.resize(placement.id, .02f); true },
        CustomAccessibilityAction(shrink) { actions.resize(placement.id, -.02f); true },
        CustomAccessibilityAction(remove) { actions.remove(placement.id); true },
    )
    Box(
        Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .then(if (selected) Modifier.border(1.5.dp, Color.Red) else Modifier)
            .then(if (interactive) Modifier.semantics {
                contentDescription = description
                this.selected = selected
                onClick { actions.select(placement.id); true }
                customActions = accessibilityActions
            } else Modifier),
    ) {
        images[placement.signatureId]?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
    if (selected) {
        PlacementHandle(bounds.left, bounds.top, R.drawable.ic_move, stringResource(if (visual.dateText == null) R.string.signature_move else R.string.date_move),
            interactive, accessibilityActions.take(4), onClick = { actions.select(placement.id) })
        PlacementHandle(bounds.right, bounds.bottom, R.drawable.ic_resize, stringResource(if (visual.dateText == null) R.string.signature_resize else R.string.date_resize),
            interactive, accessibilityActions.subList(4, 6), onClick = { actions.resize(placement.id, .02f) })
        PlacementHandle(bounds.right, bounds.top, R.drawable.ic_delete, remove,
            interactive, emptyList(), onClick = { actions.remove(placement.id) })
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
