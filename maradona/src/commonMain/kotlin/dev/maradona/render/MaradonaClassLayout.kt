package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ClassBoxMetrics
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.ClassClassifier
import dev.maradona.model.ClassDiagram
import dev.maradona.model.ClassMember
import dev.maradona.model.ClassNode
import dev.maradona.model.ClassRelationEnd
import dev.maradona.model.Direction
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class ClassLayoutHolder {
    var layout: ClassDiagramLayout? = null
}

/**
 * Custom Compose [Layout] for Mermaid `classDiagram` diagrams.
 *
 * Class boxes (header, attributes, and methods compartments), notes,
 * relation labels, cardinality labels, and namespace headers are real
 * Composables with semantics; namespace cluster boxes, relation splines,
 * UML markers (`<|--`, `*--`, `o--`, `-->`, `()--`), and note connectors
 * are drawn on the underlay canvas.
 */
@Composable
fun MaradonaClassLayout(
    diagram: ClassDiagram,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(nodeGap = 48f, layerGap = 54f),
    availableWidthPx: Int = Int.MAX_VALUE,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onLayout: (ClassDiagramLayout) -> Unit = {},
) {
    val holder = remember { ClassLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val relationDescriptions = remember(diagram) { classRelationDescriptions(diagram) }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                contentDescription =
                    "Class diagram. ${diagram.classes.size} classes, ${diagram.relations.size} relations."
            }
            .drawBehind {
                holder.layout?.let { drawClassUnderlay(it, diagram, theme) }
            },
        content = {
            if (diagram.title != null) {
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
            for ((cIdx, c) in diagram.classes.withIndex()) {
                val textColor = parseCssColor(c.style?.textColor) ?: theme.nodeText
                Column(
                    modifier = Modifier.layoutId("chdr:${c.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (ann in c.annotations) {
                        BasicText(
                            text = "«$ann»",
                            style = TextStyle(
                                color = textColor,
                                fontFamily = theme.fontFamily,
                                fontSize = theme.captionFontSize,
                                fontStyle = FontStyle.Italic,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                    val titleText = if (c.genericType.isNullOrBlank()) {
                        annotatedLabel(c.label, theme.monospaceFontFamily)
                    } else {
                        annotatedLabel(c.label, theme.monospaceFontFamily) + AnnotatedString("<${c.genericType}>")
                    }
                    BasicText(
                        text = titleText,
                        style = TextStyle(
                            color = textColor,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.nodeFontSize,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                if (c.attributes.isNotEmpty()) {
                    Column(
                        modifier = Modifier.layoutId("cattr:${c.id}"),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (attr in c.attributes) {
                            MemberLine(attr, textColor, theme)
                        }
                    }
                }
                if (c.methods.isNotEmpty()) {
                    Column(
                        modifier = Modifier.layoutId("cmeth:${c.id}"),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (meth in c.methods) {
                            MemberLine(meth, textColor, theme)
                        }
                    }
                }
                val plainClassTitle = plainTextLabel(c.displayTitle)
                val relDesc = relationDescriptions[c.id]
                val baseDesc = buildString {
                    append("Class $plainClassTitle")
                    if (!relDesc.isNullOrBlank()) append(". $relDesc")
                }
                val clickEvent = c.action?.toClickEvent(c.id, plainClassTitle)
                    ?: if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = c.id, label = plainClassTitle) else null
                Box(
                    modifier = Modifier
                        .layoutId("cbox:${c.id}")
                        .diagramNodeInteraction(
                            baseDescription = baseDesc,
                            traversalOrder = cIdx,
                            event = clickEvent,
                            onNodeClick = onNodeClick,
                            uriHandler = uriHandler,
                        ),
                )
            }
            for ((idx, note) in diagram.notes.withIndex()) {
                Box(
                    modifier = Modifier
                        .layoutId("note:$idx")
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "Note: ${plainTextLabel(note.text)}"
                        },
                ) {
                    BasicText(
                        text = annotatedLabel(note.text),
                        style = TextStyle(
                            color = theme.noteText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            textAlign = TextAlign.Start,
                        ),
                    )
                }
            }
            for ((idx, rel) in diagram.relations.withIndex()) {
                if (!rel.label.isNullOrBlank()) {
                    BasicText(
                        text = annotatedLabel(rel.label),
                        modifier = Modifier
                            .layoutId("rellabel:$idx")
                            .background(theme.edgeLabelBackground.copy(alpha = 0.75f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = TextStyle(
                            color = theme.edgeLabelText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.edgeLabelFontSize,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                if (!rel.fromCardinality.isNullOrBlank()) {
                    BasicText(
                        text = rel.fromCardinality,
                        modifier = Modifier
                            .layoutId("relfrom:$idx")
                            .padding(horizontal = 2.dp),
                        style = TextStyle(
                            color = theme.edgeLabelText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.captionFontSize,
                        ),
                    )
                }
                if (!rel.toCardinality.isNullOrBlank()) {
                    BasicText(
                        text = rel.toCardinality,
                        modifier = Modifier
                            .layoutId("relto:$idx")
                            .padding(horizontal = 2.dp),
                        style = TextStyle(
                            color = theme.edgeLabelText,
                            fontFamily = theme.fontFamily,
                            fontSize = theme.captionFontSize,
                        ),
                    )
                }
            }
            for (ns in diagram.namespaces) {
                BasicText(
                    text = annotatedLabel(ns.label),
                    modifier = Modifier.layoutId("ns:${ns.id}"),
                    style = TextStyle(
                        color = theme.subgraphText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.subgraphFontSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        val renderDensity = density
        val unbounded = Constraints()
        var titlePlaceable: Placeable? = null
        val hdrPlaceables = HashMap<String, Placeable>()
        val attrPlaceables = HashMap<String, Placeable>()
        val methPlaceables = HashMap<String, Placeable>()
        val boxMeasurables = HashMap<String, Measurable>()
        val notePlaceables = HashMap<Int, Placeable>()
        val relLabelPlaceables = HashMap<Int, Placeable>()
        val relFromPlaceables = HashMap<Int, Placeable>()
        val relToPlaceables = HashMap<Int, Placeable>()
        val nsPlaceables = HashMap<String, Placeable>()

        for (m in measurables) {
            val id = m.layoutId as? String ?: continue
            when {
                id == "title" -> titlePlaceable = m.measure(unbounded)
                id.startsWith("chdr:") -> hdrPlaceables[id.removePrefix("chdr:")] = m.measure(unbounded)
                id.startsWith("cattr:") -> attrPlaceables[id.removePrefix("cattr:")] = m.measure(unbounded)
                id.startsWith("cmeth:") -> methPlaceables[id.removePrefix("cmeth:")] = m.measure(unbounded)
                id.startsWith("cbox:") -> boxMeasurables[id.removePrefix("cbox:")] = m
                id.startsWith("note:") -> notePlaceables[id.removePrefix("note:").toInt()] = m.measure(unbounded)
                id.startsWith("rellabel:") -> relLabelPlaceables[id.removePrefix("rellabel:").toInt()] = m.measure(unbounded)
                id.startsWith("relfrom:") -> relFromPlaceables[id.removePrefix("relfrom:").toInt()] = m.measure(unbounded)
                id.startsWith("relto:") -> relToPlaceables[id.removePrefix("relto:").toInt()] = m.measure(unbounded)
                id.startsWith("ns:") -> nsPlaceables[id.removePrefix("ns:")] = m.measure(unbounded)
            }
        }

        val padX = 12f * renderDensity
        val padY = 8f * renderDensity
        val emptyCompartmentH = 8f * renderDensity

        fun computeMetrics(c: ClassNode): ClassBoxMetrics {
            val hdr = hdrPlaceables[c.id]
            val attr = attrPlaceables[c.id]
            val meth = methPlaceables[c.id]
            val hdrW = hdr?.width?.toFloat() ?: 0f
            val hdrH = hdr?.height?.toFloat() ?: 0f
            val attrW = attr?.width?.toFloat() ?: 0f
            val attrH = attr?.height?.toFloat() ?: 0f
            val methW = meth?.width?.toFloat() ?: 0f
            val methH = meth?.height?.toFloat() ?: 0f
            val contentW = maxOf(hdrW, attrW, methW)
            val width = max(96f * renderDensity, contentW + padX * 2f)
            val headerHeight = hdrH + padY * 2f
            val showCompartments = !diagram.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
            val attributesHeight = if (!showCompartments) {
                0f
            } else if (c.attributes.isEmpty()) {
                emptyCompartmentH
            } else {
                attrH + padY * 1.5f
            }
            val methodsHeight = if (!showCompartments) {
                0f
            } else if (c.methods.isEmpty()) {
                emptyCompartmentH
            } else {
                methH + padY * 1.5f
            }
            val height = headerHeight + attributesHeight + methodsHeight
            return ClassBoxMetrics(
                width = width,
                height = height,
                headerHeight = headerHeight,
                attributesHeight = attributesHeight,
                methodsHeight = methodsHeight,
            )
        }

        val metricsById = diagram.classes.associate { it.id to computeMetrics(it) }
        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                padding = padding * renderDensity,
                subgraphPaddingX = subgraphPaddingX * renderDensity,
                subgraphPaddingY = subgraphPaddingY * renderDensity,
                density = renderDensity,
            )
        }

        fun runLayout(opts: LayoutOptions): ClassDiagramLayout = ClassLayout.layout(
            diagram = diagram,
            classBoxSize = { metricsById.getValue(it.id) },
            relationLabelSize = { idx, _ ->
                relLabelPlaceables[idx]?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            },
            cardinalitySize = { idx, isFrom, _ ->
                val pl = if (isFrom) relFromPlaceables[idx] else relToPlaceables[idx]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            noteBoxSize = { idx, _ ->
                val pl = notePlaceables[idx]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            namespaceLabelSize = { ns ->
                val pl = nsPlaceables[ns.id]
                DiagramSize(pl?.width?.toFloat() ?: 0f, pl?.height?.toFloat() ?: 0f)
            },
            titleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) },
            options = opts,
        )

        var result = runLayout(scaledOptions)
        val breakpoint = 640.dp.roundToPx()
        val effectiveMaxWidth = minOf(
            if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
            availableWidthPx,
        )
        if (
            autoDirection &&
            (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
            layoutOptions.directionOverride == null &&
            effectiveMaxWidth in 1 until breakpoint &&
            result.width > effectiveMaxWidth
        ) {
            result = runLayout(scaledOptions.copy(directionOverride = Direction.TB))
        }
        holder.layout = result
        onLayout(result)

        val boxPlaceables = HashMap<String, Placeable>()
        for (pc in result.classes) {
            boxMeasurables[pc.node.id]?.let { m ->
                boxPlaceables[pc.node.id] = m.measure(
                    Constraints.fixed(pc.rect.width.roundToInt(), pc.rect.height.roundToInt()),
                )
            }
        }

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            result.titleRect?.let { r ->
                titlePlaceable?.place(r.x.roundToInt(), r.y.roundToInt())
            }
            for (ns in result.namespaces) {
                val pl = nsPlaceables[ns.namespace.id] ?: continue
                pl.place(
                    (ns.rect.centerX - pl.width / 2f).roundToInt(),
                    (ns.rect.y + 4.dp.roundToPx()).roundToInt(),
                )
            }
            for (pc in result.classes) {
                val id = pc.node.id
                val r = pc.rect
                boxPlaceables[id]?.place(r.x.roundToInt(), r.y.roundToInt())
                hdrPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.centerX - pl.width / 2f).roundToInt(),
                        (r.y + padY).roundToInt(),
                    )
                }
                attrPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.x + padX).roundToInt(),
                        (r.y + pc.headerHeight + padY * 0.75f).roundToInt(),
                    )
                }
                methPlaceables[id]?.let { pl ->
                    pl.place(
                        (r.x + padX).roundToInt(),
                        (r.y + pc.headerHeight + pc.attributesHeight + padY * 0.75f).roundToInt(),
                    )
                }
            }
            for (note in result.notes) {
                notePlaceables[note.index]?.place(
                    note.rect.x.roundToInt(),
                    note.rect.y.roundToInt(),
                )
            }
            for (rel in result.relations) {
                rel.labelRect?.let { r ->
                    relLabelPlaceables[rel.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
                rel.fromCardinalityRect?.let { r ->
                    relFromPlaceables[rel.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
                rel.toCardinalityRect?.let { r ->
                    relToPlaceables[rel.index]?.place(r.x.roundToInt(), r.y.roundToInt())
                }
            }
        }
    }
}

@Composable
private fun MemberLine(
    member: ClassMember,
    textColor: Color,
    theme: MaradonaTheme,
) {
    BasicText(
        text = member.displayText,
        style = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.edgeLabelFontSize,
            fontStyle = if (member.classifier == ClassClassifier.Abstract) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (member.classifier == ClassClassifier.Static) TextDecoration.Underline else TextDecoration.None,
        ),
    )
}

private fun classRelationDescriptions(diagram: ClassDiagram): Map<String, String> {
    val out = HashMap<String, MutableList<String>>()
    for (c in diagram.classes) out[c.id] = mutableListOf()
    for (rel in diagram.relations) {
        val lbl = rel.label?.let { " (${plainTextLabel(it)})" } ?: ""
        out[rel.from]?.add("relates to ${rel.to}$lbl")
        out[rel.to]?.add("related from ${rel.from}$lbl")
    }
    return out.mapValues { it.value.joinToString("; ") }
}

private fun DrawScope.drawClassUnderlay(
    layout: ClassDiagramLayout,
    diagram: ClassDiagram,
    theme: MaradonaTheme,
) {
    val unit = density

    // 1. Namespace cluster boxes (sorted parent-first in layout.namespaces).
    for (ns in layout.namespaces) {
        val r = ns.rect
        drawRect(
            color = theme.subgraphFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = theme.subgraphBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = 1f * unit),
        )
    }

    // 2. Dotted note connectors behind class boxes and notes.
    val dottedEffect = PathEffect.dashPathEffect(floatArrayOf(3f * unit, 3f * unit), 0f)
    for (note in layout.notes) {
        val nPts = note.connectorPoints
        if (nPts.size >= 2) {
            val samples = ClassLayout.basisSplineSamples(nPts)
            val splineHitsObstacle = (0 until samples.lastIndex).any { i ->
                val a = samples[i]
                val b = samples[i + 1]
                layout.namespaces.any { ns ->
                    ns.headerRect.width > 0f && ns.headerRect.height > 0f &&
                        ClassLayout.segmentIntersectsRect(a, b, ns.headerRect)
                } || layout.classes.any { pc ->
                    pc.node.id != note.note.forClassId &&
                        ClassLayout.segmentIntersectsRect(a, b, pc.rect)
                }
            }
            val path = if (!splineHitsObstacle) {
                basisSpline(nPts)
            } else {
                Path().apply {
                    moveTo(nPts.first().x, nPts.first().y)
                    for (k in 1 until nPts.size) {
                        lineTo(nPts[k].x, nPts[k].y)
                    }
                }
            }
            drawPath(
                path = path,
                color = theme.edge,
                style = Stroke(width = 1f * unit, pathEffect = dottedEffect),
            )
        }
    }

    // 3. Relations and UML relation markers.
    val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
    for (rel in layout.relations) {
        val pts = rel.points
        if (pts.size < 2) continue
        val r = rel.relation
        val startTrim = classEndTrim(r.startEnd, unit)
        val endTrim = classEndTrim(r.endEnd, unit)
        val trimmed = trimPath(pts, startBy = startTrim, endBy = endTrim)
        if (trimmed.size >= 2) {
            val samples = ClassLayout.basisSplineSamples(trimmed)
            val splineHitsObstacle = (0 until samples.lastIndex).any { i ->
                val a = samples[i]
                val b = samples[i + 1]
                layout.namespaces.any { ns ->
                    ns.headerRect.width > 0f && ns.headerRect.height > 0f &&
                        ClassLayout.segmentIntersectsRect(a, b, ns.headerRect)
                } || layout.classes.any { pc ->
                    pc.node.id != r.from && pc.node.id != r.to &&
                        ClassLayout.segmentIntersectsRect(a, b, pc.rect)
                } || layout.notes.any { pn ->
                    ClassLayout.segmentIntersectsRect(a, b, pn.rect)
                }
            }
            val path = if (!splineHitsObstacle) {
                basisSpline(trimmed)
            } else {
                Path().apply {
                    moveTo(trimmed.first().x, trimmed.first().y)
                    for (k in 1 until trimmed.size) {
                        lineTo(trimmed[k].x, trimmed[k].y)
                    }
                }
            }
            drawPath(
                path = path,
                color = theme.edge,
                style = Stroke(
                    width = 1.25f * unit,
                    pathEffect = if (r.dashed) dashedEffect else null,
                ),
            )
        }
        val startFrom = pts.drop(1).firstOrNull { it != pts.first() } ?: pts[1]
        val endFrom = pts.dropLast(1).lastOrNull { it != pts.last() } ?: pts[pts.lastIndex - 1]
        drawClassRelationEnd(
            end = r.startEnd,
            tip = pts.first(),
            from = startFrom,
            theme = theme,
            unit = unit,
        )
        drawClassRelationEnd(
            end = r.endEnd,
            tip = pts.last(),
            from = endFrom,
            theme = theme,
            unit = unit,
        )
    }

    // 4. Class boxes and compartment dividers.
    for (pc in layout.classes) {
        val c = pc.node
        val r = pc.rect
        val fill = parseCssColor(c.style?.fill) ?: theme.nodeFill
        val border = parseCssColor(c.style?.stroke) ?: theme.nodeBorder
        val strokeW = (c.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1f) * unit
        drawRect(
            color = fill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = border,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = strokeW),
        )
        val showCompartments = !diagram.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
        if (showCompartments) {
            val div1Y = r.y + pc.headerHeight
            drawLine(
                color = border,
                start = Offset(r.x, div1Y),
                end = Offset(r.right, div1Y),
                strokeWidth = 1f * unit,
            )
            val div2Y = div1Y + pc.attributesHeight
            drawLine(
                color = border,
                start = Offset(r.x, div2Y),
                end = Offset(r.right, div2Y),
                strokeWidth = 1f * unit,
            )
        }
    }

    // 5. Sticky notes (fill and border from theme).
    val noteFill = theme.noteFill
    val noteBorder = theme.noteBorder
    for (note in layout.notes) {
        val r = note.rect
        drawRect(
            color = noteFill,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
        )
        drawRect(
            color = noteBorder,
            topLeft = Offset(r.x, r.y),
            size = Size(r.width, r.height),
            style = Stroke(width = 1f * unit),
        )
    }
}

private fun classEndTrim(end: ClassRelationEnd, unit: Float): Float = when (end) {
    ClassRelationEnd.None -> 0f
    ClassRelationEnd.Extension -> 13.5f * unit
    ClassRelationEnd.Composition -> 13.5f * unit
    ClassRelationEnd.Aggregation -> 13.5f * unit
    ClassRelationEnd.Dependency -> 1f * unit
    ClassRelationEnd.Lollipop -> 12f * unit
}

private fun DrawScope.drawClassRelationEnd(
    end: ClassRelationEnd,
    tip: DiagramPoint,
    from: DiagramPoint,
    theme: MaradonaTheme,
    unit: Float,
) {
    if (end == ClassRelationEnd.None) return
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val ux = cos(angle)
    val uy = sin(angle)
    val px = -uy
    val py = ux
    when (end) {
        ClassRelationEnd.None -> Unit
        ClassRelationEnd.Extension -> {
            val len = 14f * unit
            val halfW = 6.5f * unit
            val baseX = tip.x - ux * len
            val baseY = tip.y - uy * len
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseX + px * halfW, baseY + py * halfW)
                lineTo(baseX - px * halfW, baseY - py * halfW)
                close()
            }
            drawPath(path, color = theme.background)
            drawPath(path, color = theme.edge, style = Stroke(width = 1.25f * unit))
        }
        ClassRelationEnd.Composition, ClassRelationEnd.Aggregation -> {
            val len = 14f * unit
            val halfW = 5.5f * unit
            val midX = tip.x - ux * (len / 2f)
            val midY = tip.y - uy * (len / 2f)
            val tailX = tip.x - ux * len
            val tailY = tip.y - uy * len
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(midX + px * halfW, midY + py * halfW)
                lineTo(tailX, tailY)
                lineTo(midX - px * halfW, midY - py * halfW)
                close()
            }
            if (end == ClassRelationEnd.Composition) {
                drawPath(path, color = theme.edge)
            } else {
                drawPath(path, color = theme.background)
                drawPath(path, color = theme.edge, style = Stroke(width = 1.25f * unit))
            }
        }
        ClassRelationEnd.Dependency -> {
            val len = 10.5f * unit
            val halfW = 5.5f * unit
            val baseX = tip.x - ux * len
            val baseY = tip.y - uy * len
            val path = Path().apply {
                moveTo(baseX + px * halfW, baseY + py * halfW)
                lineTo(tip.x, tip.y)
                lineTo(baseX - px * halfW, baseY - py * halfW)
            }
            drawPath(path, color = theme.edge, style = Stroke(width = 1.5f * unit))
        }
        ClassRelationEnd.Lollipop -> {
            val r = 6f * unit
            val center = Offset(tip.x - ux * r, tip.y - uy * r)
            drawCircle(color = theme.background, radius = r, center = center)
            drawCircle(color = theme.edge, radius = r, center = center, style = Stroke(width = 1.25f * unit))
        }
    }
}
