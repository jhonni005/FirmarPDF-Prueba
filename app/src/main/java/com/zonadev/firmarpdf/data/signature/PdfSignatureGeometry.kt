package com.zonadev.firmarpdf.data.signature

import kotlin.math.min

/** Adapta la transformación centrada del LazyColumn de FirmarPDF, sin recortar la página. */
fun previewPageBounds(
    pageIndex: Int, itemOffset: Int, itemHeight: Int, viewportStartOffset: Int,
    viewportWidth: Float, viewportHeight: Float, padding: Float,
    scale: Float, translationX: Float, translationY: Float,
): PdfSignaturePageBounds = PdfSignaturePageBounds(
    pageIndex,
    PdfSignatureBounds(
        left = viewportWidth / 2f + (padding - viewportWidth / 2f) * scale + translationX,
        top = viewportHeight / 2f + (itemOffset - viewportStartOffset - viewportHeight / 2f) * scale + translationY,
        width = (viewportWidth - padding * 2) * scale,
        height = itemHeight * scale,
    ),
)

fun signatureTargetForViewport(pages: List<PdfSignaturePageBounds>, viewport: PdfSignatureBounds): VisibleSignatureTarget? =
    pages.mapNotNull { page -> page.bounds.intersection(viewport)?.let { VisibleSignatureTarget(page, it) } }
        .maxWithOrNull(compareBy<VisibleSignatureTarget> { it.visibleBounds.width * it.visibleBounds.height }
            .thenBy { -kotlin.math.abs(it.visibleBounds.centerY - viewport.centerY) })

fun placementBounds(placement: PdfSignaturePlacement, page: PdfSignatureBounds) = PdfSignatureBounds(
    page.left + placement.x * page.width, page.top + placement.y * page.height,
    placement.width * page.width, placement.height * page.height,
)

/** Parte visible también en horizontal: el zoom del visor no se restablece al insertar. */
fun createInitialSignaturePlacement(
    id: String, signatureId: String, signatureAspectRatio: Float, target: VisibleSignatureTarget,
): PdfSignaturePlacement {
    require(signatureAspectRatio.isFinite() && signatureAspectRatio > 0f)
    val page = target.page.bounds
    val visible = target.visibleBounds
    val width = minOf(page.width * .35f, visible.width * .8f, visible.height * .6f * signatureAspectRatio)
    val height = width / signatureAspectRatio
    return PdfSignaturePlacement(
        id, signatureId, target.page.pageIndex,
        ((visible.centerX - width / 2f - page.left) / page.width).coerceIn(0f, 1f),
        ((visible.centerY - height / 2f - page.top) / page.height).coerceIn(0f, 1f),
        width / page.width, height / page.height,
    )
}

/** Adaptado del lector: mayor solapamiento; en huecos o extremos, borde más cercano. */
fun signaturePlacementForDrop(
    placement: PdfSignaturePlacement, dragged: PdfSignatureBounds, pages: List<PdfSignaturePageBounds>,
): PdfSignaturePlacement? {
    if (!dragged.isValid) return null
    val candidates = pages.filter { it.bounds.isValid && it.pageIndex >= 0 }
    fun overlap(page: PdfSignaturePageBounds) = dragged.intersection(page.bounds)?.let { it.width * it.height } ?: 0f
    fun distance(page: PdfSignaturePageBounds): Float {
        val dx = dragged.centerX - dragged.centerX.coerceIn(page.bounds.left, page.bounds.right)
        val dy = dragged.centerY - dragged.centerY.coerceIn(page.bounds.top, page.bounds.bottom)
        return dx * dx + dy * dy
    }
    val target = candidates.minWithOrNull(
        compareByDescending<PdfSignaturePageBounds>(::overlap).thenBy(::distance)
            .thenBy { if (it.pageIndex == placement.pageIndex) 0 else 1 }.thenBy { it.pageIndex },
    ) ?: return null
    val page = target.bounds
    val scale = min(1f, min(page.width / dragged.width, page.height / dragged.height))
    val width = (dragged.width * scale / page.width).coerceIn(0f, 1f)
    val height = (dragged.height * scale / page.height).coerceIn(0f, 1f)
    return placement.copy(
        pageIndex = target.pageIndex,
        x = ((dragged.centerX - page.left) / page.width - width / 2f).coerceIn(0f, 1f - width),
        y = ((dragged.centerY - page.top) / page.height - height / 2f).coerceIn(0f, 1f - height),
        width = width, height = height,
    )
}

fun moveSignaturePlacement(placement: PdfSignaturePlacement, dx: Float, dy: Float) = placement.copy(
    x = (placement.x + dx).coerceIn(0f, 1f - placement.width),
    y = (placement.y + dy).coerceIn(0f, 1f - placement.height),
)

/** Mantiene la proporción normalizada de la página; ancla la esquina superior izquierda. */
fun resizeSignaturePlacement(placement: PdfSignaturePlacement, deltaWidth: Float): PdfSignaturePlacement {
    val ratio = placement.height / placement.width
    val maxWidth = min(1f - placement.x, (1f - placement.y) / ratio).coerceAtLeast(.0001f)
    val width = (placement.width + deltaWidth).coerceIn(min(.04f, maxWidth), maxWidth)
    return placement.copy(width = width, height = width * ratio)
}
