package dev.maradona.render

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.C4DiagramLayout
import dev.maradona.layout.C4Layout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.C4BoundaryKind
import dev.maradona.model.C4Diagram
import dev.maradona.model.C4Element
import dev.maradona.model.C4ElementKind
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class C4LayoutHolder {
    var layout: C4DiagramLayout? = null
}

private data class C4CardColors(
    val fill: Color,
    val border: Color,
    val text: Color,
)

private fun resolveC4ElementColors(element: C4Element, theme: MaradonaTheme): C4CardColors {
    val p = theme.c4Palette
    val defaultColors = when (element.kind) {
        C4ElementKind.PERSON -> C4CardColors(
            fill = p.personFill,
            border = p.personBorder,
            text = p.personText,
        )
        C4ElementKind.PERSON_EXT -> C4CardColors(
            fill = p.externalFill,
            border = p.externalBorder,
            text = p.externalText,
        )
        C4ElementKind.SYSTEM,
        C4ElementKind.SYSTEM_DB,
        C4ElementKind.SYSTEM_QUEUE, -> C4CardColors(
            fill = p.systemFill,
            border = p.systemBorder,
            text = p.systemText,
        )
        C4ElementKind.SYSTEM_EXT,
        C4ElementKind.SYSTEM_DB_EXT,
        C4ElementKind.SYSTEM_QUEUE_EXT, -> C4CardColors(
            fill = p.externalFill,
            border = p.externalBorder,
            text = p.externalText,
        )
        C4ElementKind.CONTAINER,
        C4ElementKind.CONTAINER_DB,
        C4ElementKind.CONTAINER_QUEUE, -> C4CardColors(
            fill = p.containerFill,
            border = p.containerBorder,
            text = p.containerText,
        )
        C4ElementKind.CONTAINER_EXT,
        C4ElementKind.CONTAINER_DB_EXT,
        C4ElementKind.CONTAINER_QUEUE_EXT, -> C4CardColors(
            fill = p.externalFill,
            border = p.externalBorder,
            text = p.externalText,
        )
        C4ElementKind.COMPONENT,
        C4ElementKind.COMPONENT_DB,
        C4ElementKind.COMPONENT_QUEUE, -> C4CardColors(
            fill = p.componentFill,
            border = p.componentBorder,
            text = p.componentText,
        )
        C4ElementKind.COMPONENT_EXT,
        C4ElementKind.COMPONENT_DB_EXT,
        C4ElementKind.COMPONENT_QUEUE_EXT, -> C4CardColors(
            fill = p.externalFill,
            border = p.externalBorder,
            text = p.externalText,
        )
    }

    val customBg = parseCssColor(element.style?.bgColor)
    val customBorder = parseCssColor(element.style?.borderColor)
    val customText = parseCssColor(element.style?.fontColor)
    return C4CardColors(
        fill = customBg ?: defaultColors.fill,
        border = customBorder ?: customBg?.copy(alpha = 0.85f) ?: defaultColors.border,
        text = customText ?: defaultColors.text,
    )
}

/**
 * Custom Compose [Layout] for Mermaid C4 diagrams (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`).
 */
@Composable
fun MaradonaC4Layout(
    diagram: C4Diagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    layoutOptions: LayoutOptions = LayoutOptions(),
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onLayout: (C4DiagramLayout) -> Unit = {},
) {
    val holder = remember { C4LayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val isDark = theme.isDark
    val relDescriptions = remember(diagram) {
        val labelById = diagram.elements.associate { it.id to it.label }
        val out = HashMap<String, MutableList<String>>()
        for (el in diagram.elements) out[el.id] = mutableListOf()
        for (rel in diagram.relationships) {
            val toName = labelById[rel.to] ?: rel.to
            val fromName = labelById[rel.from] ?: rel.from
            val lbl = rel.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            out[rel.from]?.add("connects to $toName$lbl")
            out[rel.to]?.add("connected from $fromName$lbl")
        }
        out.mapValues { it.value.joinToString("; ") }
    }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                val accParts = listOfNotNull(
                    diagram.accTitle?.takeIf { it.isNotBlank() },
                    diagram.accDescr?.takeIf { it.isNotBlank() },
                )
                contentDescription = if (accParts.isNotEmpty()) {
                    accParts.joinToString(". ")
                } else {
                    "C4 ${diagram.type.name.lowercase()} diagram. ${diagram.elements.size} elements, ${diagram.boundaries.size} boundaries, ${diagram.relationships.size} relationships."
                }
            }
            .drawBehind {
                holder.layout?.let { drawC4Underlay(it, theme, isDark) }
            },
        content = {
            if (!diagram.title.isNullOrBlank()) {
                BasicText(
                    text = annotatedLabel(diagram.title),
                    modifier = Modifier.layoutId("title"),
                    style = TextStyle(
                        color = theme.nodeText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.titleFontSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }

            for (b in diagram.boundaries) {
                val hdrColor = parseCssColor(b.style?.fontColor) ?: theme.nodeText
                Column(
                    modifier = Modifier
                        .layoutId("bnd_hdr:${b.id}")
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    BasicText(
                        text = annotatedLabel(b.label),
                        style = TextStyle(
                            color = hdrColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(12.5f),
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    BasicText(
                        text = "[${b.type}]",
                        style = TextStyle(
                            color = hdrColor.copy(alpha = 0.78f),
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10f),
                        ),
                    )
                }
            }

            for ((elIdx, el) in diagram.elements.withIndex()) {
                val colors = resolveC4ElementColors(el, theme)
                val topPad = when {
                    el.kind.isPerson -> 26.dp
                    el.kind.isDatabase -> 16.dp
                    else -> 12.dp
                }
                val desc = el.description?.let { ": $it" } ?: ""
                val relDesc = relDescriptions[el.id]
                val baseDesc = buildString {
                    append("${el.kind.defaultTypeTag} ${el.label}$desc")
                    if (!relDesc.isNullOrBlank()) append(". $relDesc")
                }
                val clickEvent = if (!el.link.isNullOrBlank() || onNodeClick != null) {
                    MaradonaNodeClickEvent(
                        nodeId = el.id,
                        label = el.label,
                        url = el.link,
                        tooltip = el.description,
                    )
                } else {
                    null
                }
                Column(
                    modifier = Modifier
                        .layoutId("el:${el.id}")
                        .widthIn(min = 172.dp, max = 240.dp)
                        .padding(start = 14.dp, end = 14.dp, top = topPad, bottom = 12.dp)
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = elIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicText(
                        text = el.typeHeaderText,
                        style = TextStyle(
                            color = colors.text.copy(alpha = 0.88f),
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(10f),
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    val elDisplayLabel = if (!el.sprite.isNullOrBlank()) {
                        "${resolveMermaidIconGlyph(el.sprite)} ${el.label}"
                    } else {
                        el.label
                    }
                    BasicText(
                        text = annotatedLabel(elDisplayLabel, theme.monospaceFontFamily),
                        style = TextStyle(
                            color = colors.text,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.scaledSp(13.5f),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    if (!el.description.isNullOrBlank()) {
                        BasicText(
                            text = annotatedLabel(el.description),
                            style = TextStyle(
                                color = colors.text.copy(alpha = 0.92f),
                                fontFamily = theme.fontFamily,
                                fontSize = theme.captionFontSize,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
            }

            for ((idx, rel) in diagram.relationships.withIndex()) {
                val hasText = rel.label.isNotBlank() || !rel.technology.isNullOrBlank() || !rel.index.isNullOrBlank()
                if (hasText) {
                    val txtColor = parseCssColor(rel.style?.textColor) ?: theme.nodeText
                    val prefix = if (!rel.index.isNullOrBlank()) "(${rel.index}) " else ""
                    Column(
                        modifier = Modifier
                            .layoutId("rel_lbl:$idx")
                            .widthIn(max = 200.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        if (prefix.isNotEmpty() || rel.label.isNotBlank()) {
                            BasicText(
                                text = annotatedLabel("$prefix${rel.label}".trim()),
                                style = TextStyle(
                                    color = txtColor,
                                    fontFamily = theme.fontFamily,
                                    fontSize = theme.scaledSp(10.5f),
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                        if (!rel.technology.isNullOrBlank()) {
                            BasicText(
                                text = "[${rel.technology}]",
                                style = TextStyle(
                                    color = txtColor.copy(alpha = 0.8f),
                                    fontFamily = theme.fontFamily,
                                    fontSize = theme.scaledSp(9.5f),
                                    fontStyle = FontStyle.Italic,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()

        var titlePlaceable: Placeable? = null
        val bndHdrMeasurables = HashMap<String, Measurable>()
        val elMeasurables = HashMap<String, Measurable>()
        val relLblMeasurables = HashMap<Int, Measurable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("bnd_hdr:") -> bndHdrMeasurables[id.removePrefix("bnd_hdr:")] = m
                id.startsWith("el:") -> elMeasurables[id.removePrefix("el:")] = m
                id.startsWith("rel_lbl:") -> {
                    val idx = id.removePrefix("rel_lbl:").toIntOrNull() ?: continue
                    relLblMeasurables[idx] = m
                }
            }
        }

        val bndHdrPlaceables = bndHdrMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val elPlaceables = elMeasurables.mapValues { (_, m) -> m.measure(unbounded) }
        val relLblPlaceables = relLblMeasurables.mapValues { (_, m) -> m.measure(unbounded) }

        val elementSizes = elPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val boundaryHeaderSizes = bndHdrPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }
        val relLabelSizes = relLblPlaceables.mapValues { (_, p) ->
            DiagramSize(p.width.toFloat(), p.height.toFloat())
        }

        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                density = renderDensity,
            )
        }

        val layout = C4Layout.layout(
            diagram = diagram,
            elementSizes = elementSizes,
            boundaryHeaderSizes = boundaryHeaderSizes,
            relLabelSizes = relLabelSizes,
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = scaledOptions,
        )
        holder.layout = layout
        onLayout(layout)

        val wPx = layout.width.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val hPx = layout.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(wPx, hPx) {
            val tr = layout.titleRect
            if (tr != null && titlePlaceable != null) {
                titlePlaceable.place(tr.x.roundToInt(), tr.y.roundToInt())
            }

            for (pb in layout.boundaries) {
                val p = bndHdrPlaceables[pb.boundary.id] ?: continue
                p.place(pb.headerRect.x.roundToInt(), pb.headerRect.y.roundToInt())
            }

            for (pe in layout.elements) {
                val p = elPlaceables[pe.element.id] ?: continue
                p.place(
                    (pe.rect.centerX - p.width / 2f).roundToInt(),
                    (pe.rect.centerY - p.height / 2f).roundToInt(),
                )
            }

            for (pr in layout.relationships) {
                val lr = pr.labelRect ?: continue
                val p = relLblPlaceables[pr.relationshipIndex] ?: continue
                p.place(
                    (lr.centerX - p.width / 2f).roundToInt(),
                    (lr.centerY - p.height / 2f).roundToInt(),
                )
            }
        }
    }
}

private fun DrawScope.drawC4Underlay(
    layout: C4DiagramLayout,
    theme: MaradonaTheme,
    isDark: Boolean,
) {
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)

    // 1. Boundaries (outermost to innermost)
    for (pb in layout.boundaries) {
        val b = pb.boundary
        val r = pb.rect
        val customBg = parseCssColor(b.style?.bgColor)
        val customBorder = parseCssColor(b.style?.borderColor)
        val borderColor = customBorder ?: theme.subgraphBorder
        val fillColor = customBg ?: if (b.kind == C4BoundaryKind.DEPLOYMENT_NODE) {
            theme.subgraphFill.copy(alpha = if (isDark) 0.65f else 0.72f)
        } else {
            theme.subgraphFill.copy(alpha = if (isDark) 0.35f else 0.45f)
        }
        val cr = CornerRadius(6f * density, 6f * density)
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Fill,
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            cornerRadius = cr,
            style = Stroke(
                width = 1.6f * density,
                pathEffect = if (b.kind == C4BoundaryKind.DEPLOYMENT_NODE) null else dashEffect,
            ),
        )
    }

    // 2. Elements
    for (pe in layout.elements) {
        val el = pe.element
        val r = pe.rect
        val colors = resolveC4ElementColors(el, theme)
        when {
            el.kind.isPerson -> drawPersonElement(r, colors)
            el.kind.isDatabase -> drawDatabaseElement(r, colors)
            el.kind.isQueue -> drawQueueElement(r, colors)
            else -> {
                val cr = CornerRadius(8f * density, 8f * density)
                drawRoundRect(
                    color = colors.fill,
                    topLeft = Offset(r.x, r.y),
                    size = Size(r.width, r.height),
                    cornerRadius = cr,
                    style = Fill,
                )
                drawRoundRect(
                    color = colors.border,
                    topLeft = Offset(r.x, r.y),
                    size = Size(r.width, r.height),
                    cornerRadius = cr,
                    style = Stroke(width = 1.6f * density),
                )
            }
        }
    }

    // 3. Relationships
    val defaultRelLineColor = theme.edge.copy(alpha = 0.75f)
    val relDash = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    for (pr in layout.relationships) {
        val pts = pr.points
        if (pts.size < 2) continue
        val lineColor = parseCssColor(pr.relationship.style?.lineColor) ?: defaultRelLineColor
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            if (pts.size == 3) {
                quadraticTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y)
            } else {
                for (i in 1 until pts.size) {
                    lineTo(pts[i].x, pts[i].y)
                }
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 1.6f * density, pathEffect = relDash),
        )

        if (pr.endArrow) {
            val prev = pts[pts.size - 2]
            val tip = pts.last()
            drawRelArrowhead(from = prev, to = tip, color = lineColor)
        }
        if (pr.startArrow) {
            val next = pts[1]
            val tip = pts.first()
            drawRelArrowhead(from = next, to = tip, color = lineColor)
        }

        val lr = pr.labelRect
        if (lr != null) {
            val cr = CornerRadius(4f * density, 4f * density)
            drawRoundRect(
                color = theme.background.copy(alpha = 0.92f),
                topLeft = Offset(lr.x, lr.y),
                size = Size(lr.width, lr.height),
                cornerRadius = cr,
                style = Fill,
            )
            drawRoundRect(
                color = lineColor.copy(alpha = 0.45f),
                topLeft = Offset(lr.x, lr.y),
                size = Size(lr.width, lr.height),
                cornerRadius = cr,
                style = Stroke(width = 1f * density),
            )
        }
    }
}

private fun DrawScope.drawPersonElement(
    r: DiagramRect,
    colors: C4CardColors,
) {
    val headRadius = 11f * density
    val bodyTop = r.y + headRadius * 1.55f
    val bodyHeight = max(20f, r.bottom - bodyTop)
    val cr = CornerRadius(12f * density, 12f * density)

    drawRoundRect(
        color = colors.fill,
        topLeft = Offset(r.x, bodyTop),
        size = Size(r.width, bodyHeight),
        cornerRadius = cr,
        style = Fill,
    )
    drawRoundRect(
        color = colors.border,
        topLeft = Offset(r.x, bodyTop),
        size = Size(r.width, bodyHeight),
        cornerRadius = cr,
        style = Stroke(width = 1.6f * density),
    )

    val headCenter = Offset(r.centerX, r.y + headRadius + 2f * density)
    drawCircle(
        color = colors.fill,
        radius = headRadius,
        center = headCenter,
        style = Fill,
    )
    drawCircle(
        color = colors.border,
        radius = headRadius,
        center = headCenter,
        style = Stroke(width = 1.6f * density),
    )
}

private fun DrawScope.drawDatabaseElement(
    r: DiagramRect,
    colors: C4CardColors,
) {
    val capRy = (r.height * 0.11f).coerceIn(6f * density, 14f * density)
    val bodyPath = Path().apply {
        moveTo(r.x, r.y + capRy)
        lineTo(r.x, r.bottom - capRy)
        quadraticTo(r.centerX, r.bottom + capRy * 0.85f, r.right, r.bottom - capRy)
        lineTo(r.right, r.y + capRy)
        close()
    }
    drawPath(bodyPath, color = colors.fill, style = Fill)
    drawPath(bodyPath, color = colors.border, style = Stroke(width = 1.6f * density))

    drawOval(
        color = colors.fill,
        topLeft = Offset(r.x, r.y),
        size = Size(r.width, capRy * 2f),
        style = Fill,
    )
    drawOval(
        color = colors.border,
        topLeft = Offset(r.x, r.y),
        size = Size(r.width, capRy * 2f),
        style = Stroke(width = 1.6f * density),
    )
}

private fun DrawScope.drawQueueElement(
    r: DiagramRect,
    colors: C4CardColors,
) {
    val capRx = (r.height * 0.22f).coerceIn(8f * density, 18f * density)
    val cr = CornerRadius(capRx, r.height / 2f)
    drawRoundRect(
        color = colors.fill,
        topLeft = Offset(r.x, r.y),
        size = Size(r.width, r.height),
        cornerRadius = cr,
        style = Fill,
    )
    drawRoundRect(
        color = colors.border,
        topLeft = Offset(r.x, r.y),
        size = Size(r.width, r.height),
        cornerRadius = cr,
        style = Stroke(width = 1.6f * density),
    )
    drawOval(
        color = colors.border,
        topLeft = Offset(r.right - capRx * 2f, r.y),
        size = Size(capRx * 2f, r.height),
        style = Stroke(width = 1.3f * density),
    )
}

private fun DrawScope.drawRelArrowhead(
    from: DiagramPoint,
    to: DiagramPoint,
    color: Color,
) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val len = 9f * density
    val spread = 0.45f
    val x1 = to.x - len * cos(angle - spread)
    val y1 = to.y - len * sin(angle - spread)
    val x2 = to.x - len * cos(angle + spread)
    val y2 = to.y - len * sin(angle + spread)
    val p = Path().apply {
        moveTo(to.x, to.y)
        lineTo(x1, y1)
        lineTo(x2, y2)
        close()
    }
    drawPath(p, color = color, style = Fill)
}
