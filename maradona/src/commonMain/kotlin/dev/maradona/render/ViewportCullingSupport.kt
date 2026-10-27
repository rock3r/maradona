package dev.maradona.render

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Shared viewport-lazy composition and underlay culling helpers used across all 23
 * `Maradona*Layout` renderers.
 */
internal fun rectIntersectsViewport(
    rect: DiagramRect,
    viewport: DiagramRect?,
    margin: Float = 0f,
): Boolean {
    if (viewport == null) return true
    if (margin == 0f) return rect.intersects(viewport)
    return DiagramRect(
        x = rect.x - margin,
        y = rect.y - margin,
        width = rect.width + margin * 2f,
        height = rect.height + margin * 2f,
    ).intersects(viewport)
}

internal fun segmentIntersectsViewport(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    viewport: DiagramRect?,
    margin: Float = 20f,
): Boolean {
    if (viewport == null) return true
    val minX = min(x1, x2) - margin
    val maxX = max(x1, x2) + margin
    val minY = min(y1, y2) - margin
    val maxY = max(y1, y2) + margin
    return DiagramRect(
        x = minX,
        y = minY,
        width = (maxX - minX).coerceAtLeast(1f),
        height = (maxY - minY).coerceAtLeast(1f),
    ).intersects(viewport)
}

internal fun pointsBounds(
    points: List<DiagramPoint>,
    margin: Float = 20f,
): DiagramRect {
    if (points.isEmpty()) return DiagramRect(0f, 0f, 0f, 0f)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (p in points) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    return DiagramRect(
        x = minX - margin,
        y = minY - margin,
        width = (maxX - minX) + margin * 2f,
        height = (maxY - minY) + margin * 2f,
    )
}

internal fun polylineIntersectsViewport(
    points: List<DiagramPoint>,
    viewport: DiagramRect?,
    margin: Float = 20f,
): Boolean {
    if (viewport == null) return true
    if (points.isEmpty()) return false
    return pointsBounds(points, margin).intersects(viewport)
}

internal fun measureAnnotatedTextSize(
    textMeasurer: TextMeasurer,
    densityObj: Density,
    text: AnnotatedString,
    style: TextStyle,
    maxWidthPx: Int = Constraints.Infinity,
    maxLines: Int = Int.MAX_VALUE,
): DiagramSize {
    val res = textMeasurer.measure(
        text = text,
        style = style,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = maxWidthPx),
        density = densityObj,
    )
    return DiagramSize(res.size.width.toFloat(), res.size.height.toFloat())
}

internal fun measurePlainTextSize(
    textMeasurer: TextMeasurer,
    densityObj: Density,
    text: String,
    style: TextStyle,
    maxWidthPx: Int = Constraints.Infinity,
    maxLines: Int = Int.MAX_VALUE,
): DiagramSize = measureAnnotatedTextSize(
    textMeasurer = textMeasurer,
    densityObj = densityObj,
    text = AnnotatedString(text),
    style = style,
    maxWidthPx = maxWidthPx,
    maxLines = maxLines,
)

internal fun buildOffscreenCustomActions(
    offscreenEvents: List<MaradonaNodeClickEvent>,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)?,
    uriHandler: UriHandler,
): List<CustomAccessibilityAction> {
    if (offscreenEvents.isEmpty()) return emptyList()
    return offscreenEvents.mapNotNull { clickEvent ->
        if (onNodeClick == null && clickEvent.url.isNullOrBlank()) return@mapNotNull null
        val display = clickEvent.label.ifBlank { clickEvent.nodeId }
        CustomAccessibilityAction(label = "Activate off-screen node $display") {
            if (onNodeClick != null) {
                onNodeClick(clickEvent)
            } else if (!clickEvent.url.isNullOrBlank()) {
                runCatching { uriHandler.openUri(clickEvent.url) }
            }
            true
        }
    }
}
