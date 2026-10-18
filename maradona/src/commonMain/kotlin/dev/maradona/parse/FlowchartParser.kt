package dev.maradona.parse

import dev.maradona.model.ArrowHead
import dev.maradona.model.Direction
import dev.maradona.model.EdgeStroke
import dev.maradona.model.FlowEdge
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import dev.maradona.model.NodeShape
import dev.maradona.model.NodeStyle
import dev.maradona.model.Subgraph

/**
 * Parser for the Mermaid flowchart grammar (`graph` / `flowchart`).
 *
 * Supported: directions, node shapes, chained and grouped (`&`) edges,
 * pipe and inline edge labels, arrow/cross/circle heads, subgraphs,
 * `style` statements. `classDef`/`class`/`click`/`linkStyle` and
 * `%%{init}%%` directives are accepted and ignored in v1.
 */
object FlowchartParser {

    private const val MAX_SOURCE_LENGTH = 50_000
    private const val MAX_NODES = 5_000
    private const val MAX_EDGES = 5_000

    fun parse(source: String): Flowchart {
        if (source.length > MAX_SOURCE_LENGTH) {
            throw MaradonaParseException(1, "Source exceeds $MAX_SOURCE_LENGTH characters")
        }
        val state = State()
        val lines = source.lines()
        var headerSeen = false
        for ((index, rawLine) in lines.withIndex()) {
            val lineNo = index + 1
            var line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            if (!headerSeen) {
                line = consumeHeader(line, lineNo, state)
                headerSeen = true
                if (line.isEmpty()) continue
            }
            for (statement in splitStatements(line)) {
                val stmt = statement.trim()
                if (stmt.isNotEmpty()) state.statement(stmt, lineNo)
            }
        }
        if (!headerSeen) throw MaradonaParseException(1, "Expected 'flowchart' or 'graph'")
        if (state.nodes.size > MAX_NODES) throw MaradonaParseException(1, "Too many nodes")
        if (state.edges.size > MAX_EDGES) throw MaradonaParseException(1, "Too many edges")
        return Flowchart(state.direction, state.nodes.values.toList(), state.edges, state.subgraphs)
    }

    private fun consumeHeader(line: String, lineNo: Int, state: State): String {
        val lower = line.lowercase()
        val keyword = when {
            lower.startsWith("flowchart") -> "flowchart"
            lower.startsWith("graph") -> "graph"
            else -> throw MaradonaParseException(lineNo, "Expected 'flowchart' or 'graph', got: ${line.take(40)}")
        }
        var rest = line.substring(keyword.length).trim()
        val dirToken = rest.takeWhile { it.isLetter() }.uppercase()
        val dir = when (dirToken) {
            "TB", "TD" -> Direction.TB
            "BT" -> Direction.BT
            "LR" -> Direction.LR
            "RL" -> Direction.RL
            "" -> Direction.TB
            else -> throw MaradonaParseException(lineNo, "Unknown direction '$dirToken'")
        }
        state.direction = dir
        if (dirToken.isNotEmpty()) rest = rest.substring(dirToken.length).trim()
        return rest
    }

    private fun stripComment(line: String): String {
        var inQuote = false
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            if (!inQuote && c == '%' && line[i + 1] == '%') return line.substring(0, i)
            i++
        }
        return line
    }

    /** Splits on `;` outside quotes and brackets. */
    private fun splitStatements(line: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var start = 0
        for (i in line.indices) {
            when (val c = line[i]) {
                '"' -> inQuote = !inQuote
                '[', '(', '{' -> if (!inQuote) depth++
                ']', ')', '}' -> if (!inQuote && depth > 0) depth--
                ';' -> if (!inQuote && depth == 0) {
                    out += line.substring(start, i)
                    start = i + 1
                }
                else -> Unit
            }
        }
        out += line.substring(start)
        return out
    }

    private class State {
        var direction: Direction = Direction.TB
        val nodes = LinkedHashMap<String, FlowNode>()
        val edges = mutableListOf<FlowEdge>()
        val subgraphs = mutableListOf<Subgraph>()
        private val subgraphStack = ArrayDeque<String>()

        fun statement(stmt: String, lineNo: Int) {
            val lower = stmt.lowercase()
            when {
                lower == "end" -> {
                    if (subgraphStack.isEmpty()) throw MaradonaParseException(lineNo, "'end' without subgraph")
                    subgraphStack.removeLast()
                }
                lower.startsWith("subgraph") -> subgraph(stmt.substring("subgraph".length).trim(), lineNo)
                lower.startsWith("direction") -> direction(stmt.substring("direction".length).trim(), lineNo)
                lower.startsWith("classdef") || lower.startsWith("class ") ||
                    lower.startsWith("click") || lower.startsWith("linkstyle") -> Unit // accepted, ignored in v1
                lower.startsWith("style ") -> style(stmt.substring("style ".length).trim(), lineNo)
                else -> chain(stmt, lineNo)
            }
        }

        private fun subgraph(rest: String, lineNo: Int) {
            if (rest.isEmpty()) throw MaradonaParseException(lineNo, "Subgraph needs an id")
            val ref = NodeRefReader.read(rest, 0, lineNo)
            val sg = Subgraph(
                id = ref.id,
                label = ref.label ?: ref.id,
                parentId = subgraphStack.lastOrNull(),
            )
            subgraphs += sg
            subgraphStack.addLast(sg.id)
        }

        private fun direction(rest: String, lineNo: Int) {
            val dir = when (rest.uppercase()) {
                "TB", "TD" -> Direction.TB
                "BT" -> Direction.BT
                "LR" -> Direction.LR
                "RL" -> Direction.RL
                else -> throw MaradonaParseException(lineNo, "Unknown direction '$rest'")
            }
            val current = subgraphStack.lastOrNull()
            if (current == null) {
                direction = dir
            } else {
                val idx = subgraphs.indexOfLast { it.id == current }
                if (idx >= 0) subgraphs[idx] = subgraphs[idx].copy(direction = dir)
            }
        }

        private fun style(rest: String, lineNo: Int) {
            val space = rest.indexOf(' ')
            if (space < 0) throw MaradonaParseException(lineNo, "Malformed style statement")
            val id = rest.substring(0, space).trim()
            var style = NodeStyle()
            for (part in rest.substring(space + 1).split(',')) {
                val kv = part.split(':', limit = 2)
                if (kv.size != 2) continue
                val key = kv[0].trim().lowercase()
                val value = kv[1].trim()
                style = when (key) {
                    "fill" -> style.copy(fill = value)
                    "stroke" -> style.copy(stroke = value)
                    "color" -> style.copy(textColor = value)
                    else -> style
                }
            }
            val node = register(NodeRef(id, null, null))
            nodes[id] = node.copy(style = style)
        }

        /** Parses `A & B --> C --> D & E` style chains, or a lone node. */
        private fun chain(stmt: String, lineNo: Int) {
            var pos = 0
            var previous: List<String> = readGroup(stmt, pos, lineNo).also { pos = it.second }.first
            while (true) {
                pos = skipSpaces(stmt, pos)
                if (pos >= stmt.length) break
                val edge = EdgeReader.read(stmt, pos, lineNo)
                    ?: throw MaradonaParseException(lineNo, "Expected an edge at: ${stmt.substring(pos)}")
                pos = edge.endPos
                val group = readGroup(stmt, pos, lineNo)
                pos = group.second
                for (from in previous) for (to in group.first) {
                    edges += FlowEdge(from, to, edge.label, edge.stroke, edge.startHead, edge.endHead)
                }
                previous = group.first
            }
        }

        private fun readGroup(stmt: String, startPos: Int, lineNo: Int): Pair<List<String>, Int> {
            var pos = skipSpaces(stmt, startPos)
            val ids = mutableListOf<String>()
            while (true) {
                val ref = NodeRefReader.read(stmt, pos, lineNo)
                register(ref)
                ids += ref.id
                pos = skipSpaces(stmt, ref.endPos)
                if (pos < stmt.length && stmt[pos] == '&') {
                    pos = skipSpaces(stmt, pos + 1)
                } else break
            }
            return ids to pos
        }

        private fun register(ref: NodeRef): FlowNode {
            val existing = nodes[ref.id]
            val node = when {
                existing == null -> FlowNode(
                    id = ref.id,
                    label = ref.label ?: ref.id,
                    shape = ref.shape ?: NodeShape.Rect,
                    subgraphId = subgraphStack.lastOrNull(),
                )
                ref.label != null || ref.shape != null -> existing.copy(
                    label = ref.label ?: existing.label,
                    shape = ref.shape ?: existing.shape,
                )
                else -> existing
            }
            nodes[ref.id] = node
            return node
        }

        private fun skipSpaces(s: String, pos: Int): Int {
            var i = pos
            while (i < s.length && s[i].isWhitespace()) i++
            return i
        }
    }
}

internal data class NodeRef(
    val id: String,
    val label: String?,
    val shape: NodeShape?,
    val endPos: Int = 0,
)

internal object NodeRefReader {

    private data class ShapeSpec(val open: String, val close: String, val shape: NodeShape)

    // Longest / most specific openers first. Slash shapes disambiguated by closer below.
    private val specs = listOf(
        ShapeSpec("(((", ")))", NodeShape.DoubleCircle),
        ShapeSpec("((", "))", NodeShape.Circle),
        ShapeSpec("([", "])", NodeShape.Stadium),
        ShapeSpec("[[", "]]", NodeShape.Subroutine),
        ShapeSpec("[(", ")]", NodeShape.Cylinder),
        ShapeSpec("{{", "}}", NodeShape.Hexagon),
        ShapeSpec("{", "}", NodeShape.Diamond),
        ShapeSpec(">", "]", NodeShape.Asymmetric),
        ShapeSpec("(", ")", NodeShape.RoundedRect),
    )

    fun read(s: String, startPos: Int, lineNo: Int): NodeRef {
        var pos = startPos
        while (pos < s.length && s[pos].isWhitespace()) pos++
        if (pos >= s.length) throw MaradonaParseException(lineNo, "Expected a node id")
        if (s[pos] == '"') { // quoted id, e.g. subgraph "My Label"
            val end = s.indexOf('"', pos + 1)
            if (end < 0) throw MaradonaParseException(lineNo, "Unterminated quote")
            val text = s.substring(pos + 1, end)
            return NodeRef(text, text, null, end + 1)
        }
        val idStart = pos
        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '-')) pos++
        if (pos == idStart) throw MaradonaParseException(lineNo, "Expected a node id at: ${s.substring(startPos)}")
        val id = s.substring(idStart, pos)

        // Slash shapes share openers: [/..\] trapezoid, [/.../] parallelogram, etc.
        if (s.startsWith("[/", pos) || s.startsWith("[\\", pos)) {
            val openSlash = s[pos + 1] == '/'
            val contentStart = pos + 2
            val fwd = s.indexOf("/]", contentStart)
            val bwd = s.indexOf("\\]", contentStart)
            val closeAt = listOf(fwd, bwd).filter { it >= 0 }.minOrNull()
                ?: throw MaradonaParseException(lineNo, "Unclosed node shape for '$id'")
            val closeSlash = closeAt == fwd
            val shape = when {
                openSlash && closeSlash -> NodeShape.Parallelogram
                !openSlash && !closeSlash -> NodeShape.ParallelogramAlt
                openSlash -> NodeShape.Trapezoid
                else -> NodeShape.TrapezoidAlt
            }
            return NodeRef(id, cleanLabel(s.substring(contentStart, closeAt)), shape, closeAt + 2)
        }

        for (spec in specs) {
            if (s.startsWith(spec.open, pos)) {
                val contentStart = pos + spec.open.length
                val closeAt = findClose(s, contentStart, spec.close)
                    ?: throw MaradonaParseException(lineNo, "Unclosed node shape for '$id'")
                return NodeRef(id, cleanLabel(s.substring(contentStart, closeAt)), spec.shape, closeAt + spec.close.length)
            }
        }
        if (pos < s.length && s[pos] == '[') {
            val closeAt = findClose(s, pos + 1, "]")
                ?: throw MaradonaParseException(lineNo, "Unclosed '[' for '$id'")
            return NodeRef(id, cleanLabel(s.substring(pos + 1, closeAt)), NodeShape.Rect, closeAt + 1)
        }
        return NodeRef(id, null, null, pos)
    }

    private fun findClose(s: String, from: Int, close: String): Int? {
        var inQuote = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '"') inQuote = !inQuote
            if (!inQuote && s.startsWith(close, i)) return i
            i++
        }
        return null
    }

    /**
     * Normalises a raw label. `<b>`/`<i>` become `**`/`*` markers that the
     * node composable turns into [androidx.compose.ui.text.AnnotatedString] spans.
     */
    fun cleanLabel(raw: String): String {
        var text = raw.trim()
        if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            text = text.substring(1, text.length - 1).replace("\\\"", "\"")
        }
        text = text
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)<b>"), "**").replace(Regex("(?i)</b>"), "**")
            .replace(Regex("(?i)<strong>"), "**").replace(Regex("(?i)</strong>"), "**")
            .replace(Regex("(?i)<i>"), "*").replace(Regex("(?i)</i>"), "*")
            .replace(Regex("(?i)<em>"), "*").replace(Regex("(?i)</em>"), "*")
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return text.trim()
    }
}

internal data class EdgeSpec(
    val stroke: EdgeStroke,
    val startHead: ArrowHead,
    val endHead: ArrowHead,
    val label: String?,
    val endPos: Int,
)

internal object EdgeReader {

    fun read(s: String, startPos: Int, lineNo: Int): EdgeSpec? {
        var pos = startPos
        while (pos < s.length && s[pos].isWhitespace()) pos++
        if (pos >= s.length) return null
        var startHead = ArrowHead.None
        if (s[pos] == '<') { startHead = ArrowHead.Arrow; pos++ }

        val stroke: EdgeStroke
        var openEnded = false // true for "--", "==", "-." awaiting a label or closer
        when {
            s.startsWith("~~~", pos) -> {
                while (pos < s.length && s[pos] == '~') pos++
                return EdgeSpec(EdgeStroke.Invisible, startHead, ArrowHead.None, null, pos)
            }
            s.startsWith("-.", pos) -> { stroke = EdgeStroke.Dotted; pos += 2; openEnded = true }
            s[pos] == '=' -> {
                var count = 0
                while (pos < s.length && s[pos] == '=') { pos++; count++ }
                if (count < 2) return null
                stroke = EdgeStroke.Thick; openEnded = count == 2
            }
            s[pos] == '-' -> {
                var count = 0
                while (pos < s.length && s[pos] == '-') { pos++; count++ }
                if (count < 2) return null
                stroke = EdgeStroke.Solid; openEnded = count == 2
            }
            else -> return null
        }

        // Dotted closer: "-.->" / "-.-"
        if (stroke == EdgeStroke.Dotted && pos < s.length && s[pos] == '-') {
            pos++; openEnded = false
        }

        var label: String? = null
        var endHead = ArrowHead.None

        // Inline text label: "-- text -->", "-. text .->", "== text ==>"
        if (openEnded && pos < s.length && s[pos].isWhitespace()) {
            val closers = when (stroke) {
                EdgeStroke.Dotted -> listOf(".->", ".-x", ".-o", ".-")
                EdgeStroke.Thick -> listOf("==>", "==x", "==o", "===")
                else -> listOf("-->", "--x", "--o", "---")
            }
            var bestAt = -1
            var bestCloser = ""
            for (closer in closers) {
                val at = s.indexOf(" $closer", pos)
                if (at >= 0 && (bestAt < 0 || at < bestAt)) { bestAt = at; bestCloser = closer }
            }
            if (bestAt > pos) {
                val text = s.substring(pos, bestAt).trim()
                if (text.isNotEmpty()) {
                    label = NodeRefReader.cleanLabel(text)
                    endHead = headOf(bestCloser.last())
                    pos = bestAt + 1 + bestCloser.length
                    return withPipeLabel(s, pos, stroke, startHead, endHead, label)
                }
            }
        }

        if (pos < s.length) {
            endHead = headOf(s[pos])
            if (endHead != ArrowHead.None) pos++
            else if (stroke != EdgeStroke.Dotted && openEnded) {
                // "-- B" without a closing core is not a valid edge to a labelled target.
                // Fall through: treat as open edge; caller reads the node next.
                endHead = ArrowHead.None
            }
        }
        if (stroke == EdgeStroke.Solid && !openEnded && endHead == ArrowHead.None && startHead == ArrowHead.None) {
            // plain "---"
        }
        return withPipeLabel(s, pos, stroke, startHead, defaultHead(stroke, endHead, openEnded), label)
    }

    private fun defaultHead(stroke: EdgeStroke, parsed: ArrowHead, openEnded: Boolean): ArrowHead {
        if (parsed != ArrowHead.None) return parsed
        // "-->" already consumed '>'; "---" stays None. For 2-dash forms without a
        // head character (rare), Mermaid treats them as open links.
        return ArrowHead.None
    }

    private fun withPipeLabel(
        s: String, posIn: Int, stroke: EdgeStroke,
        startHead: ArrowHead, endHead: ArrowHead, label: String?,
    ): EdgeSpec {
        var pos = posIn
        while (pos < s.length && s[pos].isWhitespace()) pos++
        var resultLabel = label
        if (pos < s.length && s[pos] == '|') {
            val close = s.indexOf('|', pos + 1)
            if (close >= 0) {
                resultLabel = NodeRefReader.cleanLabel(s.substring(pos + 1, close))
                pos = close + 1
            }
        }
        // "-->" should be an arrow: if the char before pos was '>', headOf handled it.
        return EdgeSpec(stroke, startHead, endHead, resultLabel, pos)
    }

    private fun headOf(c: Char): ArrowHead = when (c) {
        '>' -> ArrowHead.Arrow
        'x' -> ArrowHead.Cross
        'o' -> ArrowHead.Circle
        else -> ArrowHead.None
    }
}
