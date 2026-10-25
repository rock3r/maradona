package dev.maradona.parse

import dev.maradona.model.ArrowHead
import dev.maradona.model.Direction
import dev.maradona.model.EdgeStroke
import dev.maradona.model.EdgeStyle
import dev.maradona.model.FlowEdge
import dev.maradona.model.FlowNode
import dev.maradona.model.Flowchart
import dev.maradona.model.NodeAction
import dev.maradona.model.NodeShape
import dev.maradona.model.NodeStyle
import dev.maradona.model.Subgraph

/**
 * Parser for the Mermaid flowchart grammar (`graph` / `flowchart`).
 *
 * Supported: directions, node shapes (classic brackets and `@{ shape: ... }`),
 * chained and grouped (`&`) edges, pipe and inline edge labels,
 * arrow/cross/circle start and end heads, subgraphs (including edges incident
 * on subgraph ids), `style`, `classDef`, `class`, `:::class` and `linkStyle`
 * statements, YAML frontmatter (`--- ... ---`), and `%%{init}%%` directives.
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
        val lines = stripDirectives(source).lines()
        var headerSeen = false
        var inFrontmatter = false
        for ((index, rawLine) in lines.withIndex()) {
            val lineNo = index + 1
            val trimmedRaw = rawLine.trim()
            if (!headerSeen) {
                if (inFrontmatter) {
                    if (trimmedRaw == "---" || trimmedRaw == "...") inFrontmatter = false
                    continue
                }
                if (trimmedRaw == "---") {
                    inFrontmatter = true
                    continue
                }
            }
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
        return state.build()
    }

    /**
     * Strips `%%{ ... }%%` directive blocks (which may span multiple lines) outside
     * quoted strings, preserving line count so error line numbers stay accurate.
     */
    internal fun stripDirectives(source: String): String {
        if (!source.contains("%%{")) return source
        val out = StringBuilder(source.length)
        var i = 0
        var inQuote = false
        while (i < source.length) {
            val c = source[i]
            if (c == '\n') {
                // Node labels do not span raw unescaped newlines outside quotes,
                // and single-line comments end at newline.
                out.append(c)
                i++
            } else if (!inQuote && source.startsWith("%%{", i)) {
                val close = source.indexOf("}%%", i + 3)
                if (close < 0) {
                    out.append(source, i, source.length)
                    break
                }
                for (k in i until close + 3) {
                    if (source[k] == '\n') out.append('\n')
                }
                i = close + 3
            } else if (!inQuote && source.startsWith("%%", i)) {
                // Single-line comment: copy until newline without toggling quote state.
                val nl = source.indexOf('\n', i + 2)
                if (nl < 0) {
                    out.append(source, i, source.length)
                    break
                }
                out.append(source, i, nl + 1)
                i = nl + 1
            } else if (c == '"' && (i == 0 || source[i - 1] != '\\')) {
                inQuote = !inQuote
                out.append(c)
                i++
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
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
        var inDoubleQuote = false
        var inSingleQuote = false
        var metaDepth = 0
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (!inDoubleQuote && !inSingleQuote && line.startsWith("@{", i)) {
                metaDepth++
            } else if (!inDoubleQuote && !inSingleQuote && metaDepth > 0 && c == '}') {
                metaDepth--
            } else if (!inSingleQuote && c == '"') {
                inDoubleQuote = !inDoubleQuote
            } else if (!inDoubleQuote && metaDepth > 0 && c == '\'') {
                inSingleQuote = !inSingleQuote
            }
            if (!inDoubleQuote && !inSingleQuote && c == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
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
        val explicitNodeIds = HashSet<String>()
        val edges = mutableListOf<FlowEdge>()
        val subgraphs = mutableListOf<Subgraph>()
        private val subgraphStack = ArrayDeque<String>()
        private val classDefs = HashMap<String, NodeStyle>()
        private val nodeClasses = HashMap<String, MutableList<String>>()
        private val explicitNodeStyles = HashMap<String, NodeStyle>()
        private val nodeActions = HashMap<String, NodeAction>()
        private var defaultLinkStyle: EdgeStyle? = null
        private val linkStyles = HashMap<Int, EdgeStyle>()
        private val edgeIds = HashSet<String>()

        fun statement(stmt: String, lineNo: Int) {
            val lower = stmt.lowercase()
            when {
                lower == "end" -> {
                    if (subgraphStack.isEmpty()) throw MaradonaParseException(lineNo, "'end' without subgraph")
                    subgraphStack.removeLast()
                }
                lower.startsWith("subgraph") -> subgraph(stmt.substring("subgraph".length).trim(), lineNo)
                lower.startsWith("direction") -> direction(stmt.substring("direction".length).trim(), lineNo)
                lower.startsWith("classdef ") -> classDef(stmt.substring("classdef ".length).trim(), lineNo)
                lower.startsWith("class ") -> classAssign(stmt.substring("class ".length).trim())
                lower.startsWith("linkstyle ") -> linkStyle(stmt.substring("linkstyle ".length).trim())
                lower.startsWith("click ") || lower.startsWith("click\t") -> {
                    ClickDirectiveParser.parseClick(stmt.substring(5).trim())?.let { parsed ->
                        for (id in parsed.nodeIds) {
                            nodeActions[id] = nodeActions[id]?.merge(parsed.action) ?: parsed.action
                        }
                    }
                }
                lower.startsWith("style ") -> style(stmt.substring("style ".length).trim(), lineNo)
                else -> chain(stmt, lineNo)
            }
        }

        fun build(): Flowchart {
            val subgraphIds = subgraphs.map { it.id }.toSet()
            // If an edge references a subgraph id directly (e.g. `outside --> sg1`) and
            // `sg1` was never declared as a real node with its own label/shape, drop the
            // synthetic node entry and keep the subgraph id on the edge so layout can
            // route and attach the edge to the subgraph boundary box.
            val pureSubgraphs = subgraphIds.filter { it !in explicitNodeIds }.toSet()
            for (sgId in pureSubgraphs) {
                nodes.remove(sgId)
            }
            for (eid in edgeIds) {
                if (eid !in explicitNodeIds) nodes.remove(eid)
            }
            val defaultClassStyle = classDefs["default"]
            val resolvedNodes = nodes.values.map { node ->
                var merged: NodeStyle? = defaultClassStyle
                for (cls in nodeClasses[node.id].orEmpty()) {
                    val def = classDefs[cls] ?: continue
                    merged = merged?.merge(def) ?: def
                }
                val explicit = explicitNodeStyles[node.id]
                if (explicit != null) {
                    merged = merged?.merge(explicit) ?: explicit
                }
                val action = nodeActions[node.id]
                if (merged != null || action != null) {
                    node.copy(style = merged ?: node.style, action = action ?: node.action)
                } else {
                    node
                }
            }
            val resolvedEdges = edges.mapIndexed { index, edge ->
                val style = defaultLinkStyle?.merge(linkStyles[index]) ?: linkStyles[index]
                edge.copy(style = style)
            }
            return Flowchart(direction, resolvedNodes, resolvedEdges, subgraphs)
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

        private fun parseNodeStyle(rawProps: String): NodeStyle {
            var style = NodeStyle()
            for (part in rawProps.split(',')) {
                val kv = part.split(':', limit = 2)
                if (kv.size != 2) continue
                val key = kv[0].trim().lowercase()
                val value = kv[1].trim().removeSuffix(";")
                style = when (key) {
                    "fill" -> style.copy(fill = value)
                    "stroke" -> style.copy(stroke = value)
                    "color" -> style.copy(textColor = value)
                    "stroke-width" -> style.copy(strokeWidth = value)
                    else -> style
                }
            }
            return style
        }

        private fun classDef(rest: String, lineNo: Int) {
            val space = rest.indexOfFirst { it.isWhitespace() }
            if (space < 0) throw MaradonaParseException(lineNo, "Malformed classDef statement")
            val names = rest.substring(0, space).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val style = parseNodeStyle(rest.substring(space + 1))
            for (name in names) {
                classDefs[name] = classDefs[name]?.merge(style) ?: style
            }
        }

        private fun classAssign(rest: String) {
            val space = rest.indexOfFirst { it.isWhitespace() }
            if (space < 0) return
            val nodeIds = rest.substring(0, space).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val classNames = rest.substring(space + 1).removeSuffix(";").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (id in nodeIds) {
                val list = nodeClasses.getOrPut(id) { mutableListOf() }
                for (cls in classNames) if (cls !in list) list += cls
            }
        }

        private fun linkStyle(rest: String) {
            val space = rest.indexOfFirst { it.isWhitespace() }
            if (space < 0) return
            val targets = rest.substring(0, space).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            var style = EdgeStyle()
            for (part in rest.substring(space + 1).split(',')) {
                val kv = part.split(':', limit = 2)
                if (kv.size != 2) continue
                val key = kv[0].trim().lowercase()
                val value = kv[1].trim().removeSuffix(";")
                style = when (key) {
                    "stroke" -> style.copy(stroke = value)
                    "stroke-width" -> style.copy(strokeWidth = value)
                    else -> style
                }
            }
            for (t in targets) {
                if (t.equals("default", ignoreCase = true)) {
                    defaultLinkStyle = defaultLinkStyle?.merge(style) ?: style
                } else {
                    val idx = t.toIntOrNull() ?: continue
                    linkStyles[idx] = linkStyles[idx]?.merge(style) ?: style
                }
            }
        }

        private fun style(rest: String, lineNo: Int) {
            val space = rest.indexOfFirst { it.isWhitespace() }
            if (space < 0) throw MaradonaParseException(lineNo, "Malformed style statement")
            val ids = rest.substring(0, space).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val style = parseNodeStyle(rest.substring(space + 1))
            for (id in ids) {
                if (id !in subgraphs.map { it.id }) {
                    register(NodeRef(id, null, null))
                }
                explicitNodeStyles[id] = explicitNodeStyles[id]?.merge(style) ?: style
            }
        }

        /** Parses `A & B --> C --> D & E` style chains, or a lone node. */
        private fun chain(stmt: String, lineNo: Int) {
            var pos = 0
            var previous: List<String> = readGroup(stmt, pos, lineNo).also { pos = it.second }.first
            pos = skipSpaces(stmt, pos)
            if (pos >= stmt.length) {
                // Standalone node statement (e.g. `a1` inside a subgraph): mark as explicit.
                for (id in previous) {
                    if (id !in edgeIds) explicitNodeIds += id
                }
                return
            }
            while (true) {
                pos = skipSpaces(stmt, pos)
                if (pos >= stmt.length) break
                val edge = EdgeReader.read(stmt, pos, lineNo)
                    ?: throw MaradonaParseException(lineNo, "Expected an edge at: ${stmt.substring(pos)}")
                edge.edgeId?.let { edgeIds += it }
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
            if (ref.id in edgeIds && ref.label == null && ref.shape == null) {
                return FlowNode(id = ref.id)
            }
            if (ref.label != null || ref.shape != null) {
                explicitNodeIds += ref.id
            }
            if (ref.className != null) {
                val list = nodeClasses.getOrPut(ref.id) { mutableListOf() }
                if (ref.className !in list) list += ref.className
            }
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
    val className: String? = null,
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
            return withClassSuffix(s, NodeRef(text, text, null, end + 1))
        }
        val idStart = pos
        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '-')) pos++
        if (pos == idStart) throw MaradonaParseException(lineNo, "Expected a node id at: ${s.substring(startPos)}")
        val id = s.substring(idStart, pos)

        // Mermaid v2 `id@{ shape: ..., label: "..." }` metadata block
        if (s.startsWith("@{", pos)) {
            val closeAt = findClose(s, pos + 2, "}", allowSingleQuote = true)
                ?: throw MaradonaParseException(lineNo, "Unclosed '@{' for '$id'")
            val body = s.substring(pos + 2, closeAt)
            val (label, shape) = parseMetadataBlock(body)
            return withClassSuffix(s, NodeRef(id, label, shape, closeAt + 1))
        }

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
            return withClassSuffix(s, NodeRef(id, cleanLabel(s.substring(contentStart, closeAt)), shape, closeAt + 2))
        }

        for (spec in specs) {
            if (s.startsWith(spec.open, pos)) {
                val contentStart = pos + spec.open.length
                val closeAt = findClose(s, contentStart, spec.close)
                    ?: throw MaradonaParseException(lineNo, "Unclosed node shape for '$id'")
                return withClassSuffix(
                    s,
                    NodeRef(id, cleanLabel(s.substring(contentStart, closeAt)), spec.shape, closeAt + spec.close.length),
                )
            }
        }
        if (pos < s.length && s[pos] == '[') {
            val closeAt = findClose(s, pos + 1, "]")
                ?: throw MaradonaParseException(lineNo, "Unclosed '[' for '$id'")
            return withClassSuffix(s, NodeRef(id, cleanLabel(s.substring(pos + 1, closeAt)), NodeShape.Rect, closeAt + 1))
        }
        return withClassSuffix(s, NodeRef(id, null, null, pos))
    }

    private fun withClassSuffix(s: String, ref: NodeRef): NodeRef {
        if (!s.startsWith(":::", ref.endPos)) return ref
        var p = ref.endPos + 3
        val start = p
        while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_' || s[p] == '-')) p++
        val cls = s.substring(start, p).takeIf { it.isNotEmpty() }
        return ref.copy(endPos = p, className = cls)
    }

    private fun parseMetadataBlock(body: String): Pair<String?, NodeShape?> {
        var label: String? = null
        var shape: NodeShape? = null
        var inQuote = false
        var quoteChar = '"'
        val parts = mutableListOf<String>()
        var start = 0
        for (i in body.indices) {
            val c = body[i]
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true
                quoteChar = c
            } else if (inQuote && c == quoteChar) {
                inQuote = false
            } else if (!inQuote && c == ',') {
                parts += body.substring(start, i)
                start = i + 1
            }
        }
        parts += body.substring(start)
        for (part in parts) {
            val kv = part.split(':', limit = 2)
            if (kv.size != 2) continue
            val k = kv[0].trim().lowercase()
            val v = kv[1].trim().removeSurrounding("\"").removeSurrounding("'")
            when (k) {
                "label" -> label = cleanLabel(v)
                "shape" -> shape = mapShapeAlias(v)
            }
        }
        return label to shape
    }

    private fun mapShapeAlias(alias: String): NodeShape? = when (alias.trim().lowercase()) {
        "rect", "rectangle", "proc", "process" -> NodeShape.Rect
        "rounded", "event" -> NodeShape.RoundedRect
        "stadium", "pill", "terminal" -> NodeShape.Stadium
        "subproc", "subprocess", "subroutine", "fr-rect", "framed-rectangle" -> NodeShape.Subroutine
        "cyl", "cylinder", "db", "database" -> NodeShape.Cylinder
        "circle" -> NodeShape.Circle
        "dbl-circ", "double-circle", "fr-circ", "framed-circle" -> NodeShape.DoubleCircle
        "diam", "diamond", "decision" -> NodeShape.Diamond
        "hex", "hexagon", "prepare" -> NodeShape.Hexagon
        "lean-r", "lean-right", "in-out" -> NodeShape.Parallelogram
        "lean-l", "lean-left", "out-in" -> NodeShape.ParallelogramAlt
        "trap-b", "trapezoid-bottom", "priority" -> NodeShape.Trapezoid
        "trap-t", "trapezoid-top", "manual" -> NodeShape.TrapezoidAlt
        "odd", "asymmetric" -> NodeShape.Asymmetric
        else -> null
    }

    private fun findClose(s: String, from: Int, close: String, allowSingleQuote: Boolean = false): Int? {
        var quoteChar: Char? = null
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (quoteChar == null && (c == '"' || (allowSingleQuote && c == '\''))) quoteChar = c
            else if (quoteChar == c) quoteChar = null
            if (quoteChar == null && s.startsWith(close, i)) return i
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
        if (text.length >= 2 && text.first() == '`' && text.last() == '`') {
            text = text.substring(1, text.length - 1)
        }
        text = transformOutsideInlineCode(text) { segment ->
            segment
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)<(?:b|strong)(?:\s(?:[^>"']|"[^"]*"|'[^']*')*)?>"""), "**")
                .replace(Regex("""(?i)</(?:b|strong)\s*>"""), "**")
                .replace(Regex("""(?i)<(?:i|em)(?:\s(?:[^>"']|"[^"]*"|'[^']*')*)?>"""), "*")
                .replace(Regex("""(?i)</(?:i|em)\s*>"""), "*")
                .replace(Regex("""(?i)<(u|ins|s|del|strike|sub|sup|code)(?:\s(?:[^>"']|"[^"]*"|'[^']*')*)?>""")) { "<${it.groupValues[1].lowercase()}>" }
                .replace(Regex("""(?i)</(u|ins|s|del|strike|sub|sup|code)\s*>""")) { "</${it.groupValues[1].lowercase()}>" }
                .replace(Regex("""(?i)</?(?:span|div|p|font|small|mark)(?:\s(?:[^>"']|"[^"]*"|'[^']*')*)?>"""), "")
        }
        text = text
            .replace("&quot;", "\"").replace("#quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("#lt;", "<")
            .replace("&gt;", ">").replace("#gt;", ">")
            .replace("&amp;", "&").replace("#amp;", "&")
            .replace(Regex("&?#(\\d{1,5});")) { m ->
                val code = m.groupValues[1].toIntOrNull()
                if (code != null && code in 32..0xFFFF) code.toChar().toString() else m.value
            }
        return text.trim()
    }

    private fun transformOutsideInlineCode(s: String, transform: (String) -> String): String {
        if ('`' !in s && "<code" !in s.lowercase()) return transform(s)
        val out = StringBuilder()
        val nonCode = StringBuilder()
        var i = 0
        fun flush() {
            if (nonCode.isNotEmpty()) {
                out.append(transform(nonCode.toString()))
                nonCode.clear()
            }
        }
        while (i < s.length) {
            if (s.regionMatches(i, "<img", 0, 4, ignoreCase = true) &&
                i + 4 < s.length && (s[i + 4].isWhitespace() || s[i + 4] == '/' || s[i + 4] == '>')
            ) {
                var j = i + 4
                var quote: Char? = null
                while (j < s.length) {
                    val c = s[j]
                    if (quote != null) {
                        if (c == quote) quote = null
                    } else if (c == '"' || c == '\'') {
                        quote = c
                    } else if (c == '>') {
                        break
                    }
                    j++
                }
                if (j < s.length && s[j] == '>') {
                    nonCode.append(s, i, j + 1)
                    i = j + 1
                    continue
                }
            }
            if (s[i] == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i) {
                    flush()
                    out.append(s, i, close + 1)
                    i = close + 1
                    continue
                }
            }
            if (s.regionMatches(i, "<code", 0, 5, ignoreCase = true) &&
                i + 5 < s.length && (s[i + 5] == '>' || s[i + 5].isWhitespace())
            ) {
                val openEnd = s.indexOf('>', i + 5)
                val close = if (openEnd >= 0) s.indexOf("</code>", openEnd + 1, ignoreCase = true) else -1
                if (openEnd >= 0 && close >= 0) {
                    flush()
                    out.append("<code>")
                    out.append(s, openEnd + 1, close)
                    out.append("</code>")
                    i = close + 7
                    continue
                }
            }
            nonCode.append(s[i])
            i++
        }
        flush()
        return out.toString()
    }
}

internal data class EdgeSpec(
    val stroke: EdgeStroke,
    val startHead: ArrowHead,
    val endHead: ArrowHead,
    val label: String?,
    val endPos: Int,
    val edgeId: String? = null,
)

internal object EdgeReader {

    fun read(s: String, startPos: Int, lineNo: Int): EdgeSpec? {
        var pos = startPos
        while (pos < s.length && s[pos].isWhitespace()) pos++
        if (pos >= s.length) return null
        // Optional Mermaid v11 edge ID prefix, e.g. `e1@-->`
        var edgeId: String? = null
        val atIdx = s.indexOf('@', pos)
        if (atIdx > pos && s.substring(pos, atIdx).all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            edgeId = s.substring(pos, atIdx)
            pos = atIdx + 1
        }
        var startHead = ArrowHead.None
        if (pos + 1 < s.length && (s[pos + 1] == '-' || s[pos + 1] == '=' || s[pos + 1] == '~')) {
            when (s[pos]) {
                '<' -> { startHead = ArrowHead.Arrow; pos++ }
                'o' -> { startHead = ArrowHead.Circle; pos++ }
                'x' -> { startHead = ArrowHead.Cross; pos++ }
            }
        }

        val stroke: EdgeStroke
        var openEnded = false // true for "--", "==", "-." awaiting a label or closer
        when {
            s.startsWith("~~~", pos) -> {
                while (pos < s.length && s[pos] == '~') pos++
                return EdgeSpec(EdgeStroke.Invisible, startHead, ArrowHead.None, null, pos, edgeId)
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
                    return withPipeLabel(s, pos, stroke, startHead, endHead, label, edgeId)
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
        return withPipeLabel(s, pos, stroke, startHead, defaultHead(stroke, endHead, openEnded), label, edgeId)
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
        edgeId: String?,
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
        return EdgeSpec(stroke, startHead, endHead, resultLabel, pos, edgeId)
    }

    private fun headOf(c: Char): ArrowHead = when (c) {
        '>' -> ArrowHead.Arrow
        'x' -> ArrowHead.Cross
        'o' -> ArrowHead.Circle
        else -> ArrowHead.None
    }
}
