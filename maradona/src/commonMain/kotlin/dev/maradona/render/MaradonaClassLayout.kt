package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ClassBoxMetrics
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.ClassLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
import dev.maradona.model.ClassClassifier
import dev.maradona.model.ClassDiagram
import dev.maradona.model.ClassMember
import dev.maradona.model.ClassNode
import dev.maradona.model.ClassRelationEnd
import dev.maradona.model.Direction
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class ClassLayoutHolder {
    var layout: ClassDiagramLayout? = null
    var naturalLayout: ClassDiagramLayout? = null
    var adaptedTbLayout: ClassDiagramLayout? = null
    var hdrSizes: Map<String, DiagramSize> = emptyMap()
    var attrSizes: Map<String, DiagramSize> = emptyMap()
    var methSizes: Map<String, DiagramSize> = emptyMap()
    var metricsById: Map<String, ClassBoxMetrics> = emptyMap()
    var relLabelSizes: Map<Int, DiagramSize> = emptyMap()
    var relFromSizes: Map<Int, DiagramSize> = emptyMap()
    var relToSizes: Map<Int, DiagramSize> = emptyMap()
    var noteSizes: Map<Int, DiagramSize> = emptyMap()
    var nsLabelSizes: Map<String, DiagramSize> = emptyMap()
    var titleSize: DiagramSize? = null
    var lastDensity: Float = Float.NaN
    var lastFontScale: Float = Float.NaN
    var lastFontResolutionToken: Any? = null
    var lastMonospaceFontResolutionToken: Any? = null
    var visibleViewport: DiagramRect? = null
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
    visibleViewport: DiagramRect? = null,
    viewportResolver: ((layoutWidth: Float, layoutHeight: Float) -> DiagramRect?)? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    onLayout: (ClassDiagramLayout) -> Unit = {},
) {
    val holder = remember(diagram, theme, layoutOptions) { ClassLayoutHolder() }
    val uriHandler = LocalUriHandler.current
    val relationDescriptions = remember(diagram) { classRelationDescriptions(diagram) }
    val textMeasurer = rememberTextMeasurer()
    val densityObj = LocalDensity.current
    val renderDensity = densityObj.density
    val renderFontScale = densityObj.fontScale
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = listOf(
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.SemiBold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold).value,
        fontFamilyResolver.resolve(fontFamily = theme.fontFamily, fontStyle = FontStyle.Italic).value,
    )
    val monoFontResolutionToken = fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value

    var observedBoundedWidthPx by remember(diagram, theme, layoutOptions) { mutableIntStateOf(Int.MAX_VALUE) }
    var boundedWidthObserved by remember(diagram, theme, layoutOptions) { mutableStateOf(false) }
    val effectiveAvailableWidthPx = minOf(availableWidthPx, observedBoundedWidthPx)

    val naturalLayout = run {
        val cachedNatural = holder.naturalLayout
        if (
            cachedNatural != null &&
            abs(holder.lastDensity - renderDensity) < 1e-4f &&
            abs(holder.lastFontScale - renderFontScale) < 1e-4f &&
            holder.lastFontResolutionToken == fontResolutionToken &&
            holder.lastMonospaceFontResolutionToken == monoFontResolutionToken
        ) {
            cachedNatural
        } else {
            computePremeasuredClassLayout(
                diagram = diagram,
                theme = theme,
                layoutOptions = layoutOptions,
                densityObj = densityObj,
                textMeasurer = textMeasurer,
            ).also { pre ->
                holder.naturalLayout = pre.naturalLayout
                holder.adaptedTbLayout = null
                holder.hdrSizes = pre.hdrSizes
                holder.attrSizes = pre.attrSizes
                holder.methSizes = pre.methSizes
                holder.metricsById = pre.metricsById
                holder.relLabelSizes = pre.relLabelSizes
                holder.relFromSizes = pre.relFromSizes
                holder.relToSizes = pre.relToSizes
                holder.noteSizes = pre.noteSizes
                holder.nsLabelSizes = pre.nsLabelSizes
                holder.titleSize = pre.titleSize
                holder.lastDensity = renderDensity
                holder.lastFontScale = renderFontScale
                holder.lastFontResolutionToken = fontResolutionToken
                holder.lastMonospaceFontResolutionToken = monoFontResolutionToken
            }.naturalLayout
        }
    }

    fun resolveAdaptedTbLayout(): ClassDiagramLayout {
        holder.adaptedTbLayout?.let { return it }
        val scaledOptions = with(layoutOptions) {
            copy(
                nodeGap = nodeGap * renderDensity,
                layerGap = layerGap * renderDensity,
                padding = padding * renderDensity,
                subgraphPaddingX = subgraphPaddingX * renderDensity,
                subgraphPaddingY = subgraphPaddingY * renderDensity,
                density = renderDensity,
                directionOverride = Direction.TB,
            )
        }
        return ClassLayout.layout(
            diagram = diagram,
            classBoxSize = { holder.metricsById.getValue(it.id) },
            relationLabelSize = { idx, _ -> holder.relLabelSizes[idx] },
            cardinalitySize = { idx, isFrom, _ ->
                (if (isFrom) holder.relFromSizes[idx] else holder.relToSizes[idx]) ?: DiagramSize(0f, 0f)
            },
            noteBoxSize = { idx, _ -> holder.noteSizes[idx] ?: DiagramSize(0f, 0f) },
            namespaceLabelSize = { ns -> holder.nsLabelSizes[ns.id] ?: DiagramSize(0f, 0f) },
            titleSize = holder.titleSize,
            options = scaledOptions,
        ).also { holder.adaptedTbLayout = it }
    }

    val breakpointPx = with(densityObj) { 640.dp.roundToPx() }
    val wantVerticalInComposition = autoDirection &&
        (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
        layoutOptions.directionOverride == null &&
        effectiveAvailableWidthPx in 1 until breakpointPx &&
        naturalLayout.width > effectiveAvailableWidthPx
    val precomputedLayout = if (wantVerticalInComposition) {
        resolveAdaptedTbLayout()
    } else {
        naturalLayout
    }
    holder.layout = precomputedLayout
    val effectiveViewport = visibleViewport
        ?: viewportResolver?.invoke(precomputedLayout.width, precomputedLayout.height)
    val needsParentWidthProbe = autoDirection &&
        (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
        layoutOptions.directionOverride == null &&
        availableWidthPx == Int.MAX_VALUE &&
        !boundedWidthObserved
    val activeCullViewport = if (needsParentWidthProbe) null else effectiveViewport
    holder.visibleViewport = activeCullViewport

    val positionedClassById = remember(precomputedLayout) {
        precomputedLayout.classes.associateBy { it.node.id }
    }
    val positionedNamespaceById = remember(precomputedLayout) {
        precomputedLayout.namespaces.associateBy { it.namespace.id }
    }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                contentDescription =
                    "Class diagram. ${diagram.classes.size} classes, ${diagram.relations.size} relations."
                val vp = holder.visibleViewport
                if (vp != null) {
                    val offscreenEvents = buildList {
                        for (c in diagram.classes) {
                            val pc = positionedClassById[c.id] ?: continue
                            if (rectIntersectsViewport(pc.rect, vp, margin = 8f)) continue
                            val plainClassTitle = plainTextLabel(c.displayTitle)
                            val clickEvent = c.action?.toClickEvent(c.id, plainClassTitle)
                                ?: if (onNodeClick != null) MaradonaNodeClickEvent(nodeId = c.id, label = plainClassTitle) else null
                            if (clickEvent != null) add(clickEvent)
                        }
                    }
                    val actions = buildOffscreenCustomActions(offscreenEvents, onNodeClick, uriHandler)
                    if (actions.isNotEmpty()) {
                        customActions = actions
                    }
                }
            }
            .drawBehind {
                holder.layout?.let { drawClassUnderlay(it, diagram, theme, holder.visibleViewport) }
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
                if (activeCullViewport != null) {
                    val pc = positionedClassById[c.id]
                    if (pc != null && !rectIntersectsViewport(pc.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
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
                if (activeCullViewport != null) {
                    val pn = precomputedLayout.notes.getOrNull(idx)
                    if (pn != null && !rectIntersectsViewport(pn.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
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
                val prel = precomputedLayout.relations.getOrNull(idx)
                if (!rel.label.isNullOrBlank()) {
                    val lr = prel?.labelRect
                    val lblVis = activeCullViewport == null ||
                        lr == null ||
                        rectIntersectsViewport(lr, activeCullViewport, margin = 8f)
                    if (lblVis) {
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
                }
                if (!rel.fromCardinality.isNullOrBlank()) {
                    val fcr = prel?.fromCardinalityRect
                    val fVis = activeCullViewport == null ||
                        fcr == null ||
                        rectIntersectsViewport(fcr, activeCullViewport, margin = 8f)
                    if (fVis) {
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
                }
                if (!rel.toCardinality.isNullOrBlank()) {
                    val tcr = prel?.toCardinalityRect
                    val tVis = activeCullViewport == null ||
                        tcr == null ||
                        rectIntersectsViewport(tcr, activeCullViewport, margin = 8f)
                    if (tVis) {
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
            }
            for (ns in diagram.namespaces) {
                if (activeCullViewport != null) {
                    val pns = positionedNamespaceById[ns.id]
                    if (pns != null && !rectIntersectsViewport(pns.rect, activeCullViewport, margin = 8f)) {
                        continue
                    }
                }
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

        val effectiveHdrSizes = HashMap<String, DiagramSize>(holder.hdrSizes).apply {
            for ((k, pl) in hdrPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveAttrSizes = HashMap<String, DiagramSize>(holder.attrSizes).apply {
            for ((k, pl) in attrPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveMethSizes = HashMap<String, DiagramSize>(holder.methSizes).apply {
            for ((k, pl) in methPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveRelLabelSizes = HashMap<Int, DiagramSize>(holder.relLabelSizes).apply {
            for ((k, pl) in relLabelPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveRelFromSizes = HashMap<Int, DiagramSize>(holder.relFromSizes).apply {
            for ((k, pl) in relFromPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveRelToSizes = HashMap<Int, DiagramSize>(holder.relToSizes).apply {
            for ((k, pl) in relToPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveNoteSizes = HashMap<Int, DiagramSize>(holder.noteSizes).apply {
            for ((k, pl) in notePlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveNsLabelSizes = HashMap<String, DiagramSize>(holder.nsLabelSizes).apply {
            for ((k, pl) in nsPlaceables) this[k] = DiagramSize(pl.width.toFloat(), pl.height.toFloat())
        }
        val effectiveTitleSize = titlePlaceable?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
            ?: holder.titleSize

        fun computeMetrics(c: ClassNode): ClassBoxMetrics {
            val hdr = effectiveHdrSizes[c.id]
            val attr = effectiveAttrSizes[c.id]
            val meth = effectiveMethSizes[c.id]
            val hdrW = hdr?.width ?: 0f
            val hdrH = hdr?.height ?: 0f
            val attrW = attr?.width ?: 0f
            val attrH = attr?.height ?: 0f
            val methW = meth?.width ?: 0f
            val methH = meth?.height ?: 0f
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
            relationLabelSize = { idx, _ -> effectiveRelLabelSizes[idx] },
            cardinalitySize = { idx, isFrom, _ ->
                (if (isFrom) effectiveRelFromSizes[idx] else effectiveRelToSizes[idx]) ?: DiagramSize(0f, 0f)
            },
            noteBoxSize = { idx, _ -> effectiveNoteSizes[idx] ?: DiagramSize(0f, 0f) },
            namespaceLabelSize = { ns -> effectiveNsLabelSizes[ns.id] ?: DiagramSize(0f, 0f) },
            titleSize = effectiveTitleSize,
            options = opts,
        )

        val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        if (!boundedWidthObserved || observedBoundedWidthPx != boundedWidth) {
            observedBoundedWidthPx = boundedWidth
            boundedWidthObserved = true
        }
        val effectiveMaxWidth = minOf(boundedWidth, availableWidthPx)
        val breakpoint = 640.dp.roundToPx()
        val measuredNatural = if (activeCullViewport == null) runLayout(scaledOptions) else naturalLayout
        val wantVerticalAtMeasure = autoDirection &&
            (diagram.direction == Direction.LR || diagram.direction == Direction.RL) &&
            layoutOptions.directionOverride == null &&
            effectiveMaxWidth in 1 until breakpoint &&
            measuredNatural.width > effectiveMaxWidth

        val result = if (activeCullViewport != null && wantVerticalAtMeasure != wantVerticalInComposition) {
            precomputedLayout
        } else if (wantVerticalAtMeasure) {
            if (activeCullViewport == null) {
                runLayout(scaledOptions.copy(directionOverride = Direction.TB)).also {
                    holder.adaptedTbLayout = it
                }
            } else {
                resolveAdaptedTbLayout()
            }
        } else {
            measuredNatural
        }
        if (activeCullViewport == null) {
            holder.hdrSizes = effectiveHdrSizes
            holder.attrSizes = effectiveAttrSizes
            holder.methSizes = effectiveMethSizes
            holder.metricsById = metricsById
            holder.relLabelSizes = effectiveRelLabelSizes
            holder.relFromSizes = effectiveRelFromSizes
            holder.relToSizes = effectiveRelToSizes
            holder.noteSizes = effectiveNoteSizes
            holder.nsLabelSizes = effectiveNsLabelSizes
            holder.titleSize = effectiveTitleSize
            holder.naturalLayout = measuredNatural
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

        val edgeMargin = 20f * renderDensity
        val drawnEdges = if (activeCullViewport == null) {
            result.relations.size
        } else {
            result.relations.count { rel ->
                polylineIntersectsViewport(rel.points, activeCullViewport, margin = edgeMargin)
            }
        }
        val drawnSubgraphs = if (activeCullViewport == null) {
            result.namespaces.size
        } else {
            result.namespaces.count { rectIntersectsViewport(it.rect, activeCullViewport, margin = 8f) }
        }
        onViewportStats(
            MaradonaViewportStats(
                composedNodes = boxPlaceables.size,
                totalNodes = diagram.classes.size,
                composedEdgeLabels = relLabelPlaceables.size,
                totalEdgeLabels = diagram.relations.count { !it.label.isNullOrBlank() },
                drawnEdges = drawnEdges,
                totalEdges = result.relations.size,
                drawnSubgraphs = drawnSubgraphs,
                totalSubgraphs = result.namespaces.size,
            ),
        )

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

private data class PremeasuredClassResult(
    val naturalLayout: ClassDiagramLayout,
    val hdrSizes: Map<String, DiagramSize>,
    val attrSizes: Map<String, DiagramSize>,
    val methSizes: Map<String, DiagramSize>,
    val metricsById: Map<String, ClassBoxMetrics>,
    val relLabelSizes: Map<Int, DiagramSize>,
    val relFromSizes: Map<Int, DiagramSize>,
    val relToSizes: Map<Int, DiagramSize>,
    val noteSizes: Map<Int, DiagramSize>,
    val nsLabelSizes: Map<String, DiagramSize>,
    val titleSize: DiagramSize?,
)

private fun computePremeasuredClassLayout(
    diagram: ClassDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): PremeasuredClassResult {
    val renderDensity = densityObj.density
    val padX = 12f * renderDensity
    val padY = 8f * renderDensity
    val emptyCompartmentH = 8f * renderDensity
    val hdrGap = with(densityObj) { 2.dp.roundToPx().toFloat() }
    val memberGap = with(densityObj) { 3.dp.roundToPx().toFloat() }

    val titleSize = diagram.title?.let { title ->
        measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(title),
            style = TextStyle(
                color = theme.nodeText,
                fontFamily = theme.fontFamily,
                fontSize = theme.titleFontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
    }

    val hdrSizes = HashMap<String, DiagramSize>(diagram.classes.size)
    val attrSizes = HashMap<String, DiagramSize>(diagram.classes.size)
    val methSizes = HashMap<String, DiagramSize>(diagram.classes.size)
    val metricsById = HashMap<String, ClassBoxMetrics>(diagram.classes.size)

    fun measureMembers(members: List<ClassMember>, textColor: Color): DiagramSize {
        var maxW = 0f
        var sumH = 0f
        for ((i, member) in members.withIndex()) {
            val sz = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = member.displayText,
                style = TextStyle(
                    color = textColor,
                    fontFamily = theme.fontFamily,
                    fontSize = theme.edgeLabelFontSize,
                    fontStyle = if (member.classifier == ClassClassifier.Abstract) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (member.classifier == ClassClassifier.Static) TextDecoration.Underline else TextDecoration.None,
                ),
            )
            if (sz.width > maxW) maxW = sz.width
            sumH += sz.height
            if (i > 0) sumH += memberGap
        }
        return DiagramSize(maxW, sumH)
    }

    for (c in diagram.classes) {
        val textColor = parseCssColor(c.style?.textColor) ?: theme.nodeText
        val annStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.captionFontSize,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
        )
        val titleStyle = TextStyle(
            color = textColor,
            fontFamily = theme.fontFamily,
            fontSize = theme.nodeFontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        val titleText = if (c.genericType.isNullOrBlank()) {
            annotatedLabel(c.label, theme.monospaceFontFamily)
        } else {
            annotatedLabel(c.label, theme.monospaceFontFamily) + AnnotatedString("<${c.genericType}>")
        }
        val titleSz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = titleText,
            style = titleStyle,
        )
        var hdrW = titleSz.width
        var hdrH = titleSz.height
        for (ann in c.annotations) {
            val annSz = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = "«$ann»",
                style = annStyle,
            )
            if (annSz.width > hdrW) hdrW = annSz.width
            hdrH += annSz.height + hdrGap
        }
        val hdrSz = DiagramSize(hdrW, hdrH)
        hdrSizes[c.id] = hdrSz

        val attrSz = if (c.attributes.isNotEmpty()) {
            measureMembers(c.attributes, textColor).also { attrSizes[c.id] = it }
        } else null
        val methSz = if (c.methods.isNotEmpty()) {
            measureMembers(c.methods, textColor).also { methSizes[c.id] = it }
        } else null

        val contentW = maxOf(hdrSz.width, attrSz?.width ?: 0f, methSz?.width ?: 0f)
        val width = max(96f * renderDensity, contentW + padX * 2f)
        val headerHeight = hdrSz.height + padY * 2f
        val showCompartments = !diagram.hideEmptyMembersBox || c.attributes.isNotEmpty() || c.methods.isNotEmpty()
        val attributesHeight = if (!showCompartments) {
            0f
        } else if (c.attributes.isEmpty()) {
            emptyCompartmentH
        } else {
            (attrSz?.height ?: 0f) + padY * 1.5f
        }
        val methodsHeight = if (!showCompartments) {
            0f
        } else if (c.methods.isEmpty()) {
            emptyCompartmentH
        } else {
            (methSz?.height ?: 0f) + padY * 1.5f
        }
        val height = headerHeight + attributesHeight + methodsHeight
        metricsById[c.id] = ClassBoxMetrics(
            width = width,
            height = height,
            headerHeight = headerHeight,
            attributesHeight = attributesHeight,
            methodsHeight = methodsHeight,
        )
    }

    val notePadH = with(densityObj) { 10.dp.roundToPx() * 2 }
    val notePadV = with(densityObj) { 6.dp.roundToPx() * 2 }
    val noteStyle = TextStyle(
        color = theme.noteText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Start,
    )
    val noteSizes = diagram.notes.mapIndexed { idx, note ->
        val sz = measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(note.text),
            style = noteStyle,
        )
        idx to DiagramSize(sz.width + notePadH, sz.height + notePadV)
    }.toMap()

    val relPadH = with(densityObj) { 4.dp.roundToPx() * 2 }
    val relPadV = with(densityObj) { 2.dp.roundToPx() * 2 }
    val cardPadH = with(densityObj) { 2.dp.roundToPx() * 2 }
    val relLabelStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.edgeLabelFontSize,
        textAlign = TextAlign.Center,
    )
    val cardStyle = TextStyle(
        color = theme.edgeLabelText,
        fontFamily = theme.fontFamily,
        fontSize = theme.captionFontSize,
    )
    val relLabelSizes = HashMap<Int, DiagramSize>()
    val relFromSizes = HashMap<Int, DiagramSize>()
    val relToSizes = HashMap<Int, DiagramSize>()
    for ((idx, rel) in diagram.relations.withIndex()) {
        if (!rel.label.isNullOrBlank()) {
            val sz = measureAnnotatedTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = annotatedLabel(rel.label),
                style = relLabelStyle,
            )
            relLabelSizes[idx] = DiagramSize(sz.width + relPadH, sz.height + relPadV)
        }
        if (!rel.fromCardinality.isNullOrBlank()) {
            val sz = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = rel.fromCardinality,
                style = cardStyle,
            )
            relFromSizes[idx] = DiagramSize(sz.width + cardPadH, sz.height)
        }
        if (!rel.toCardinality.isNullOrBlank()) {
            val sz = measurePlainTextSize(
                textMeasurer = textMeasurer,
                densityObj = densityObj,
                text = rel.toCardinality,
                style = cardStyle,
            )
            relToSizes[idx] = DiagramSize(sz.width + cardPadH, sz.height)
        }
    }

    val nsStyle = TextStyle(
        color = theme.subgraphText,
        fontFamily = theme.fontFamily,
        fontSize = theme.subgraphFontSize,
        fontWeight = FontWeight.SemiBold,
    )
    val nsLabelSizes = diagram.namespaces.associate { ns ->
        ns.id to measureAnnotatedTextSize(
            textMeasurer = textMeasurer,
            densityObj = densityObj,
            text = annotatedLabel(ns.label),
            style = nsStyle,
        )
    }

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
    val naturalLayout = ClassLayout.layout(
        diagram = diagram,
        classBoxSize = { metricsById.getValue(it.id) },
        relationLabelSize = { idx, _ -> relLabelSizes[idx] },
        cardinalitySize = { idx, isFrom, _ ->
            (if (isFrom) relFromSizes[idx] else relToSizes[idx]) ?: DiagramSize(0f, 0f)
        },
        noteBoxSize = { idx, _ -> noteSizes[idx] ?: DiagramSize(0f, 0f) },
        namespaceLabelSize = { ns -> nsLabelSizes[ns.id] ?: DiagramSize(0f, 0f) },
        titleSize = titleSize,
        options = scaledOptions,
    )
    return PremeasuredClassResult(
        naturalLayout = naturalLayout,
        hdrSizes = hdrSizes,
        attrSizes = attrSizes,
        methSizes = methSizes,
        metricsById = metricsById,
        relLabelSizes = relLabelSizes,
        relFromSizes = relFromSizes,
        relToSizes = relToSizes,
        noteSizes = noteSizes,
        nsLabelSizes = nsLabelSizes,
        titleSize = titleSize,
    )
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
    viewport: DiagramRect? = null,
) {
    val unit = density
    val cullMargin = 24f * unit

    // 1. Namespace cluster boxes (sorted parent-first in layout.namespaces).
    for (ns in layout.namespaces) {
        val r = ns.rect
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
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
            if (!polylineIntersectsViewport(nPts, viewport, margin = cullMargin)) continue
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
        if (!polylineIntersectsViewport(pts, viewport, margin = cullMargin)) continue
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
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
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
        if (!rectIntersectsViewport(r, viewport, margin = cullMargin)) continue
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

internal fun measureClassLayoutForExport(
    diagram: ClassDiagram,
    theme: MaradonaTheme,
    layoutOptions: LayoutOptions,
    densityObj: Density,
    textMeasurer: TextMeasurer,
): ClassDiagramLayout = computePremeasuredClassLayout(
    diagram = diagram,
    theme = theme,
    layoutOptions = layoutOptions,
    densityObj = densityObj,
    textMeasurer = textMeasurer,
).naturalLayout

