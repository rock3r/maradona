package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.FlowchartLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.model.ArrowHead
import dev.maradona.model.Direction
import dev.maradona.model.EdgeStroke
import dev.maradona.model.Flowchart
import dev.maradona.model.MaradonaNodeClickEvent
import kotlin.math.roundToInt

private class LayoutHolder {
    var layout: DiagramLayout? = null
}

/**
 * Custom layout that measures node composables, runs the Sugiyama layout
 * with those measured sizes, then places nodes, edge labels, and subgraph
 * labels. Edge paths are drawn in an underlay behind the nodes.
 */
@Composable
fun MaradonaLayout(
    chart: Flowchart,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    modifier: Modifier = Modifier,
    autoDirection: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(),
    /** Width available in the parent, in px at the current density, for
     * [autoDirection]. Callers that measure this layout unbounded (for
     * fit-to-size zooming) must pass it explicitly. */
    availableWidthPx: Int = Int.MAX_VALUE,
    /** Optional callback invoked when a flowchart node is clicked/activated. */
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    /** Reports the computed layout (the intermediate representation the oracle
     * compares against mermaid's SVG geometry, and the renderer against its own
     * pixels). Called whenever the layout is recomputed. */
    onLayout: (DiagramLayout) -> Unit = {},
) {
    val holder = remember { LayoutHolder() }
    val connections = remember(chart) { connectionDescriptions(chart) }

    Layout(
        modifier = modifier
            .semantics {
                isTraversalGroup = true
                contentDescription =
                    "Flowchart. ${chart.nodes.size} nodes, ${chart.edges.size} connections."
            }
            .drawBehind {
                holder.layout?.let { drawDiagramUnderlay(it, theme) }
            },
        content = {
            for ((index, node) in chart.nodes.withIndex()) {
                MaradonaNode(
                    node = node,
                    theme = theme,
                    modifier = Modifier.layoutId("node:${node.id}"),
                    connectionsDescription = connections[node.id],
                    traversalOrder = index,
                    onNodeClick = onNodeClick,
                )
            }
            for ((index, edge) in chart.edges.withIndex()) {
                val label = edge.label ?: continue
                BasicText(
                    text = annotatedLabel(label),
                    modifier = Modifier
                        .layoutId("elabel:$index")
                        .background(theme.edgeLabelBackground)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics {
                            contentDescription =
                                "Connection from ${plainLabel(chart, edge.from)} to ${plainLabel(chart, edge.to)}: ${label.replace("*", "")}"
                        },
                    style = TextStyle(
                        color = theme.edgeLabelText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.edgeLabelFontSize,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            for (sg in chart.subgraphs) {
                BasicText(
                    text = sg.label,
                    modifier = Modifier.layoutId("sg:${sg.id}"),
                    style = TextStyle(
                        color = theme.subgraphText,
                        fontFamily = theme.fontFamily,
                        fontSize = theme.subgraphFontSize,
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        val maxNodeWidth = 240.dp.roundToPx()
        val nodeConstraints = Constraints(maxWidth = maxNodeWidth)
        val nodePlaceables = HashMap<String, androidx.compose.ui.layout.Placeable>()
        val edgeLabelPlaceables = HashMap<Int, androidx.compose.ui.layout.Placeable>()
        val subgraphPlaceables = HashMap<String, androidx.compose.ui.layout.Placeable>()

        for (measurable in measurables) {
            val id = measurable.layoutId as? String ?: continue
            when {
                id.startsWith("node:") ->
                    nodePlaceables[id.removePrefix("node:")] = measurable.measure(nodeConstraints)
                id.startsWith("elabel:") ->
                    edgeLabelPlaceables[id.removePrefix("elabel:").toInt()] =
                        measurable.measure(Constraints(maxWidth = 180.dp.roundToPx()))
                id.startsWith("sg:") ->
                    subgraphPlaceables[id.removePrefix("sg:")] = measurable.measure(Constraints())
            }
        }

        val nodeSizes = nodePlaceables.mapValues { DiagramSize(it.value.width.toFloat(), it.value.height.toFloat()) }
        val edgeLabelSizes = chart.edges.mapIndexed { index, _ ->
            edgeLabelPlaceables[index]?.let { DiagramSize(it.width.toFloat(), it.height.toFloat()) }
        }

        // Measured sizes are in px at the current density, so scale the gaps too.
        val renderDensity = density
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
        var result = FlowchartLayout.layout(
            chart = chart,
            nodeSize = { nodeSizes[it.id] ?: DiagramSize(0f, 0f) },
            edgeLabelSize = { edge -> edgeLabelSizes[chart.edges.indexOf(edge)] },
            options = scaledOptions,
        )
        // Pele-style adaption: on narrow widths a horizontal chart goes vertical.
        val breakpoint = 640.dp.roundToPx()
        val effectiveMaxWidth = minOf(
            if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
            availableWidthPx,
        )
        if (
            autoDirection &&
            (chart.direction == Direction.LR || chart.direction == Direction.RL) &&
            layoutOptions.directionOverride == null &&
            effectiveMaxWidth in 1 until breakpoint &&
            result.width > effectiveMaxWidth
        ) {
            result = FlowchartLayout.layout(
                chart = chart,
                nodeSize = { nodeSizes[it.id] ?: DiagramSize(0f, 0f) },
                edgeLabelSize = { edge -> edgeLabelSizes[chart.edges.indexOf(edge)] },
                options = scaledOptions.copy(directionOverride = Direction.TB),
            )
        }
        holder.layout = result
        onLayout(result)

        layout(result.width.roundToInt(), result.height.roundToInt()) {
            for (positioned in result.nodes) {
                nodePlaceables[positioned.node.id]?.place(
                    positioned.rect.x.roundToInt(),
                    positioned.rect.y.roundToInt(),
                )
            }
            for ((index, positioned) in result.edges.withIndex()) {
                val rect = positioned.labelRect ?: continue
                // The layout engine preserves chart edge order.
                edgeLabelPlaceables[index]?.place(
                    rect.x.roundToInt(),
                    rect.y.roundToInt(),
                )
            }
            for (sg in result.subgraphs) {
                val placeable = subgraphPlaceables[sg.subgraph.id] ?: continue
                placeable.place(
                    (sg.rect.centerX - placeable.width / 2f).roundToInt(),
                    (sg.rect.y + 2.dp.roundToPx()).roundToInt(),
                )
            }
        }
    }
}

private fun plainLabel(chart: Flowchart, id: String): String =
    (chart.nodes.firstOrNull { it.id == id }?.label ?: id).replace("*", "")

private fun connectionDescriptions(chart: Flowchart): Map<String, String> {
    val out = HashMap<String, MutableList<String>>()
    for (node in chart.nodes) out[node.id] = mutableListOf()
    for (edge in chart.edges) {
        if (edge.stroke == EdgeStroke.Invisible) continue
        val labelPart = edge.label?.let { " (${it.replace("*", "")})" } ?: ""
        val fromLabel = plainLabel(chart, edge.from)
        val toLabel = plainLabel(chart, edge.to)
        when {
            edge.startHead != ArrowHead.None && edge.endHead != ArrowHead.None -> {
                out[edge.from]?.add("connects bidirectionally with $toLabel$labelPart")
                out[edge.to]?.add("connects bidirectionally with $fromLabel$labelPart")
            }
            edge.startHead == ArrowHead.None && edge.endHead == ArrowHead.None -> {
                out[edge.from]?.add("connected with $toLabel$labelPart")
                out[edge.to]?.add("connected with $fromLabel$labelPart")
            }
            edge.startHead != ArrowHead.None && edge.endHead == ArrowHead.None -> {
                out[edge.to]?.add("goes to $fromLabel$labelPart")
                out[edge.from]?.add("comes from $toLabel$labelPart")
            }
            else -> {
                out[edge.from]?.add("goes to $toLabel$labelPart")
                out[edge.to]?.add("comes from $fromLabel$labelPart")
            }
        }
    }
    return out.mapValues { it.value.joinToString("; ") }
}
