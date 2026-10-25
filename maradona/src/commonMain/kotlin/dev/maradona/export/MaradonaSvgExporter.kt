package dev.maradona.export

import androidx.compose.ui.graphics.Color
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.DiagramSize
import dev.maradona.layout.FlowchartLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.NodeOutline
import dev.maradona.layout.NodeSizing
import dev.maradona.model.ArrowHead
import dev.maradona.model.EdgeStroke
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import dev.maradona.model.NodeShape
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import dev.maradona.render.MaradonaTheme
import dev.maradona.render.parseCssColor
import dev.maradona.render.plainTextLabel
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure Kotlin multiplatform SVG exporter for Maradona flowchart diagrams.
 *
 * Generates standalone, standards-compliant `<svg>` documents styled with [MaradonaTheme]
 * tokens without requiring an active display window or platform-specific graphics context,
 * while avoiding speculative duplication of the non-flowchart Compose diagram renderers.
 */
object MaradonaSvgExporter {

    /**
     * Parses [source] and exports a Mermaid flowchart diagram to a standalone SVG string.
     *
     * @throws UnsupportedOperationException if [source] parses to a non-flowchart diagram family.
     */
    fun exportToSvg(
        source: String,
        theme: MaradonaTheme = MaradonaTheme.light(),
        options: LayoutOptions = LayoutOptions(),
    ): String {
        return when (val model = MaradonaParser.parse(source)) {
            is MaradonaDiagramModel.FlowchartModel -> {
                val layout = FlowchartLayout.layout(
                    chart = model.chart,
                    nodeSize = { defaultFlowNodeSize(it, theme) },
                    edgeLabelSize = { e -> e.label?.let { defaultTextSize(it, theme.edgeLabelFontSize.value) } },
                    options = options,
                )
                exportFlowchartToSvg(model.chart, layout, theme)
            }
            else -> throw UnsupportedOperationException(
                "Standalone SVG export currently supports flowchart diagrams (got ${model::class.simpleName}); render other diagram families through MaradonaDiagram.",
            )
        }
    }

    /**
     * Exports a pre-laid-out [Flowchart] and [DiagramLayout] to a standalone SVG string.
     */
    fun exportFlowchartToSvg(
        chart: Flowchart,
        layout: DiagramLayout,
        theme: MaradonaTheme = MaradonaTheme.light(),
    ): String {
        val w = ceil(layout.width).toInt().coerceAtLeast(1)
        val h = ceil(layout.height).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h">""").append('\n')
        sb.append("""  <rect width="100%" height="100%" fill="${theme.background.toSvgHex()}"/>""").append('\n')

        // 1. Subgraph containers
        for (psg in layout.subgraphs) {
            val r = psg.rect
            sb.append("""  <g class="subgraph" data-id="${escapeXml(psg.subgraph.id)}">""").append('\n')
            sb.append(
                """    <rect x="${fmt(r.x)}" y="${fmt(r.y)}" width="${fmt(r.width)}" height="${fmt(r.height)}" rx="6" fill="${theme.subgraphFill.toSvgHex()}" stroke="${theme.subgraphBorder.toSvgHex()}" stroke-width="1"/>""",
            ).append('\n')
            val sgLines = plainTextLabel(psg.subgraph.label).split('\n')
            val sgFont = theme.subgraphFontSize.value
            val sgLineH = sgFont * 1.3f
            for ((idx, line) in sgLines.withIndex()) {
                val ly = r.y + 18f + idx * sgLineH
                sb.append(
                    """    <text x="${fmt(r.centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="${theme.subgraphText.toSvgHex()}" font-family="sans-serif" font-size="${fmt(sgFont)}" font-weight="600">${escapeXml(line)}</text>""",
                ).append('\n')
            }
            sb.append("""  </g>""").append('\n')
        }

        // 2. Edges and arrowheads
        for (pe in layout.edges) {
            if (pe.edge.stroke == EdgeStroke.Invisible || pe.points.size < 2) continue
            val edgeColor = (parseCssColor(pe.edge.style?.stroke) ?: theme.edge).toSvgHex()
            val strokeW = pe.edge.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull()
                ?: if (pe.edge.stroke == EdgeStroke.Thick) 3.5f else 1.5f
            val dashAttr = if (pe.edge.stroke == EdgeStroke.Dotted) """ stroke-dasharray="4,4"""" else ""
            val d = buildSvgEdgePath(pe.points)
            sb.append("""  <path class="edge" d="$d" fill="none" stroke="$edgeColor" stroke-width="${fmt(strokeW)}"$dashAttr/>""").append('\n')

            if (pe.edge.endHead != ArrowHead.None) {
                val tip = pe.points.last()
                val prev = pe.points.dropLast(1).lastOrNull { it.x != tip.x || it.y != tip.y } ?: pe.points[pe.points.size - 2]
                appendSvgArrowHead(sb, tip, prev, pe.edge.endHead, edgeColor, theme.background.toSvgHex())
            }
            if (pe.edge.startHead != ArrowHead.None) {
                val tip = pe.points.first()
                val next = pe.points.drop(1).firstOrNull { it.x != tip.x || it.y != tip.y } ?: pe.points[1]
                appendSvgArrowHead(sb, tip, next, pe.edge.startHead, edgeColor, theme.background.toSvgHex())
            }

            val lr = pe.labelRect
            val lbl = pe.edge.label
            if (lr != null && !lbl.isNullOrBlank()) {
                sb.append(
                    """  <rect x="${fmt(lr.x)}" y="${fmt(lr.y)}" width="${fmt(lr.width)}" height="${fmt(lr.height)}" rx="3" fill="${theme.edgeLabelBackground.toSvgHex()}"/>""",
                ).append('\n')
                appendMultilineText(
                    sb = sb,
                    indent = "  ",
                    centerX = lr.centerX,
                    centerY = lr.centerY,
                    lines = plainTextLabel(lbl).split('\n'),
                    colorHex = theme.edgeLabelText.toSvgHex(),
                    fontSize = theme.edgeLabelFontSize.value,
                )
            }
        }

        // 3. Nodes (with optional <a> links and <title> tooltips)
        for (pn in layout.nodes) {
            val node = pn.node
            val r = pn.rect
            val fill = (parseCssColor(node.style?.fill) ?: theme.nodeFill).toSvgHex()
            val stroke = (parseCssColor(node.style?.stroke) ?: theme.nodeBorder).toSvgHex()
            val textColor = (parseCssColor(node.style?.textColor) ?: theme.nodeText).toSvgHex()
            val strokeW = node.style?.strokeWidth?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 1f
            val url = node.action?.url?.takeIf { it.isNotBlank() }
            val tooltip = node.action?.tooltip?.takeIf { it.isNotBlank() }

            if (url != null) {
                val targetAttr = node.action.target?.let { """ target="${escapeXml(it)}"""" } ?: ""
                sb.append("""  <a href="${escapeXml(url)}"$targetAttr>""").append('\n')
            }
            sb.append("""  <g class="node" data-id="${escapeXml(node.id)}">""").append('\n')
            if (tooltip != null) {
                sb.append("""    <title>${escapeXml(tooltip)}</title>""").append('\n')
            }
            appendSvgNodeShape(sb, node.shape, r, fill, stroke, strokeW)
            val fontSize = theme.nodeFontSize.value
            val lines = wrapSvgLines(plainTextLabel(node.label), fontSize, NodeSizing.WRAPPING_WIDTH)
            appendMultilineText(
                sb = sb,
                indent = "    ",
                centerX = r.centerX,
                centerY = r.centerY,
                lines = lines,
                colorHex = textColor,
                fontSize = fontSize,
            )
            sb.append("""  </g>""").append('\n')
            if (url != null) {
                sb.append("""  </a>""").append('\n')
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun wrapSvgLines(text: String, fontSp: Float, maxWidth: Float): List<String> {
        val maxChars = max(8, (maxWidth / (fontSp * 0.62f)).toInt())
        val out = mutableListOf<String>()
        for (rawLine in text.split('\n')) {
            if (rawLine.length <= maxChars) {
                out += rawLine
                continue
            }
            val words = rawLine.split(' ')
            val cur = StringBuilder()
            for (word in words) {
                if (cur.isNotEmpty() && cur.length + 1 + word.length <= maxChars) {
                    cur.append(' ').append(word)
                    continue
                }
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.setLength(0)
                }
                if (word.length <= maxChars) {
                    cur.append(word)
                } else {
                    var pos = 0
                    while (pos < word.length) {
                        val end = min(word.length, pos + maxChars)
                        val chunk = word.substring(pos, end)
                        if (end < word.length) out += chunk else cur.append(chunk)
                        pos = end
                    }
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return if (out.isEmpty()) listOf("") else out
    }

    private fun defaultFlowNodeSize(node: FlowNode, theme: MaradonaTheme): DiagramSize {
        val fontSize = theme.nodeFontSize.value
        val lines = wrapSvgLines(plainTextLabel(node.label), fontSize, NodeSizing.WRAPPING_WIDTH)
        val maxCols = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val textW = min(NodeSizing.WRAPPING_WIDTH, maxCols * fontSize * 0.62f)
        val textH = lines.size * fontSize * 1.5f
        val labelBox = NodeSizing.labelBox(node.shape, textW, textH)
        return NodeSizing.nodeBox(node.shape, labelBox.width, labelBox.height)
    }

    private fun defaultTextSize(text: String, fontSp: Float): DiagramSize {
        val plain = plainTextLabel(text)
        val lines = plain.split('\n')
        val maxCols = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        return DiagramSize(
            width = max(24f, maxCols * fontSp * 0.62f + 8f),
            height = max(18f, lines.size * fontSp * 1.4f + 4f),
        )
    }

    private fun appendSvgNodeShape(
        sb: StringBuilder,
        shape: NodeShape,
        r: DiagramRect,
        fill: String,
        stroke: String,
        strokeW: Float,
    ) {
        val x = fmt(r.x)
        val y = fmt(r.y)
        val w = fmt(r.width)
        val h = fmt(r.height)
        val sw = fmt(strokeW)
        when (shape) {
            NodeShape.Rect ->
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            NodeShape.RoundedRect ->
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" rx="6" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            NodeShape.Stadium -> {
                val rx = fmt(r.height / 2f)
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" rx="$rx" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Subroutine -> {
                val inset = min(10f, r.width * 0.15f)
                sb.append("""    <rect x="$x" y="$y" width="$w" height="$h" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.x + inset)}" y1="$y" x2="${fmt(r.x + inset)}" y2="${fmt(r.bottom)}" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <line x1="${fmt(r.right - inset)}" y1="$y" x2="${fmt(r.right - inset)}" y2="${fmt(r.bottom)}" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Circle -> {
                val rad = fmt(min(r.width, r.height) / 2f)
                sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="$rad" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.DoubleCircle -> {
                val outerR = min(r.width, r.height) / 2f
                val innerR = max(2f, outerR - 5f)
                sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(outerR)}" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <circle cx="${fmt(r.centerX)}" cy="${fmt(r.centerY)}" r="${fmt(innerR)}" fill="none" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Cylinder -> {
                val rx = r.width / 2f
                val ry = (rx / (2.5f + r.width / 50f)).coerceAtMost(r.height * 0.25f)
                val topY = r.y + ry
                val botY = r.bottom - ry
                val bodyD = "M ${fmt(r.x)} ${fmt(topY)} L ${fmt(r.x)} ${fmt(botY)} A ${fmt(rx)} ${fmt(ry)} 0 0 0 ${fmt(r.right)} ${fmt(botY)} L ${fmt(r.right)} ${fmt(topY)} A ${fmt(rx)} ${fmt(ry)} 0 0 0 ${fmt(r.x)} ${fmt(topY)} Z"
                sb.append("""    <path d="$bodyD" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""    <ellipse cx="${fmt(r.centerX)}" cy="${fmt(topY)}" rx="${fmt(rx)}" ry="${fmt(ry)}" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
            NodeShape.Diamond,
            NodeShape.Hexagon,
            NodeShape.Parallelogram,
            NodeShape.ParallelogramAlt,
            NodeShape.Trapezoid,
            NodeShape.TrapezoidAlt,
            NodeShape.Asymmetric,
            -> {
                val pts = NodeOutline.polygonPoints(shape, r).joinToString(" ") { (px, py) ->
                    "${fmt(r.x + px)},${fmt(r.y + py)}"
                }
                sb.append("""    <polygon points="$pts" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
            }
        }
    }

    private fun buildSvgEdgePath(points: List<DiagramPoint>): String {
        val sb = StringBuilder()
        sb.append("M ${fmt(points[0].x)} ${fmt(points[0].y)}")
        for (i in 1 until points.size) {
            sb.append(" L ${fmt(points[i].x)} ${fmt(points[i].y)}")
        }
        return sb.toString()
    }

    private fun appendSvgArrowHead(
        sb: StringBuilder,
        tip: DiagramPoint,
        from: DiagramPoint,
        head: ArrowHead,
        color: String,
        bgColor: String,
    ) {
        val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
        when (head) {
            ArrowHead.Arrow -> {
                val len = 9.0
                val spread = 0.45
                val x1 = tip.x - (len * cos(angle - spread)).toFloat()
                val y1 = tip.y - (len * sin(angle - spread)).toFloat()
                val x2 = tip.x - (len * cos(angle + spread)).toFloat()
                val y2 = tip.y - (len * sin(angle + spread)).toFloat()
                sb.append("""  <polygon points="${fmt(tip.x)},${fmt(tip.y)} ${fmt(x1)},${fmt(y1)} ${fmt(x2)},${fmt(y2)}" fill="$color"/>""").append('\n')
            }
            ArrowHead.Circle -> {
                sb.append("""  <circle cx="${fmt(tip.x)}" cy="${fmt(tip.y)}" r="4" fill="$bgColor" stroke="$color" stroke-width="1.5"/>""").append('\n')
            }
            ArrowHead.Cross -> {
                val d = 4f
                sb.append("""  <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y - d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y + d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
                sb.append("""  <line x1="${fmt(tip.x - d)}" y1="${fmt(tip.y + d)}" x2="${fmt(tip.x + d)}" y2="${fmt(tip.y - d)}" stroke="$color" stroke-width="1.8"/>""").append('\n')
            }
            ArrowHead.None -> Unit
        }
    }

    private fun appendMultilineText(
        sb: StringBuilder,
        indent: String,
        centerX: Float,
        centerY: Float,
        lines: List<String>,
        colorHex: String,
        fontSize: Float,
    ) {
        val lineH = fontSize * 1.35f
        val startY = centerY - ((lines.size - 1) * lineH) / 2f + fontSize * 0.35f
        for ((idx, textLine) in lines.withIndex()) {
            val ly = startY + idx * lineH
            sb.append(
                """$indent<text x="${fmt(centerX)}" y="${fmt(ly)}" text-anchor="middle" fill="$colorHex" font-family="sans-serif" font-size="${fmt(fontSize)}">${escapeXml(textLine)}</text>""",
            ).append('\n')
        }
    }

    private fun Color.toSvgHex(): String {
        if (alpha <= 0.004f) return "none"
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        if (alpha < 0.996f) {
            return "rgba($r,$g,$b,${fmt(alpha)})"
        }
        return "#" +
            r.toString(16).padStart(2, '0').uppercase() +
            g.toString(16).padStart(2, '0').uppercase() +
            b.toString(16).padStart(2, '0').uppercase()
    }

    private fun fmt(v: Float): String {
        val rounded = (v * 100f).roundToInt() / 100f
        val intPart = rounded.toInt()
        return if (rounded == intPart.toFloat()) intPart.toString() else rounded.toString()
    }

    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val code = ch.code
            if (ch.isHighSurrogate()) {
                if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    sb.append(ch).append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (ch.isLowSurrogate()) {
                i++
                continue
            }
            val validXml10 = code == 0x09 || code == 0x0A || code == 0x0D ||
                (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
            if (!validXml10) {
                i++
                continue
            }
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
