package dev.maradona.parse

import dev.maradona.model.MindmapDiagram
import dev.maradona.model.MindmapNode
import dev.maradona.model.MindmapShape
import dev.maradona.model.NodeStyle

/**
 * Hand-written parser for Mermaid `mindmap` sources.
 *
 * Supports:
 * - `mindmap` header, YAML frontmatter (`title`), `%%{init}%%` directives,
 *   `title`, `accTitle`, and `accDescr`
 * - Indentation-driven tree hierarchy (spaces and tabs, tolerant of uneven sibling indentation)
 * - Node shapes:
 *   - `Default` (`Plain text`)
 *   - `Square` (`id[Label]` or `[Label]`)
 *   - `Rounded` (`id(Label)` or `(Label)`)
 *   - `Circle` (`id((Label))` or `((Label))`)
 *   - `Bang` (`id))Label((` or `))Label((`)
 *   - `Cloud` (`id)Label(` or `)Label(`)
 *   - `Hexagon` (`id{{Label}}` or `{{Label}}`)
 * - Multi-line markdown labels (`id["`**Bold** and *italic*\nsecond line`"]`)
 * - `::icon(...)` and `:::class1 class2` decorators (both standalone lines and inline)
 * - `classDef` (including `classDef default`), `class`, and `style`
 */
object MindmapParser {

    private class MutableMindmapNode(
        val id: String,
        var label: String,
        var shape: MindmapShape,
        val parentId: String?,
        val indent: Int,
        var icon: String? = null,
        val classes: MutableList<String> = mutableListOf(),
        var inlineStyle: NodeStyle? = null,
    )

    fun parse(source: String): MindmapDiagram {
        var frontmatterTitle: String? = null
        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            while (idx < rawLines.size) {
                val fmLine = rawLines[idx].trim()
                idx++
                if (fmLine == "---" || fmLine == "...") break
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val value = unquote(fmLine.substring(colon + 1).trim())
                    if (key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = value
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        val joinedLines = joinMultilineQuotedLines(bodyLines)

        var headerSeen = false
        var title: String? = frontmatterTitle
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val nodes = mutableListOf<MutableMindmapNode>()
        val usedIds = HashSet<String>()
        val indentStack = ArrayDeque<MutableMindmapNode>()
        val classDefs = HashMap<String, NodeStyle>()
        val pendingClasses = HashMap<String, MutableList<String>>()
        val pendingStyles = HashMap<String, NodeStyle>()
        var nodeCounter = 0

        fun allocateId(preferred: String?): String {
            val base = preferred?.trim()?.takeIf { it.isNotEmpty() } ?: "mm_${++nodeCounter}"
            if (usedIds.add(base)) return base
            var suffix = 2
            while (!usedIds.add("${base}_$suffix")) {
                suffix++
            }
            return "${base}_$suffix"
        }

        for (rawLine in joinedLines) {
            val clean = stripLineComment(rawLine)
            val trimmed = clean.trim()
            if (inAccDescrBlock) {
                if (trimmed == "}") {
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else {
                    accDescrLines += trimmed
                }
                continue
            }
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()

            if (!headerSeen) {
                if (lower == "mindmap" || lower.startsWith("mindmap ")) {
                    headerSeen = true
                    continue
                }
                headerSeen = true
            }

            if (lower.startsWith("title ") || lower.startsWith("title:")) {
                title = normalizeLabel(trimmed.substring(6).trim())
                continue
            }
            if (lower.startsWith("acctitle:")) {
                accTitle = normalizeLabel(trimmed.substring("acctitle:".length).trim())
                continue
            }
            if (lower.startsWith("accdescr:")) {
                accDescr = normalizeLabel(trimmed.substring("accdescr:".length).trim())
                continue
            }
            if (lower.startsWith("accdescr") && trimmed.endsWith("{")) {
                inAccDescrBlock = true
                accDescrLines.clear()
                continue
            }

            if (lower.startsWith("classdef ")) {
                val rest = trimmed.substring("classDef ".length).trim()
                val sp = rest.indexOfFirst { it.isWhitespace() }
                if (sp > 0) {
                    val names = rest.substring(0, sp).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val style = parseNodeStyle(rest.substring(sp + 1).trim())
                    for (n in names) classDefs[n] = style
                }
                continue
            }
            if (lower.startsWith("class ")) {
                val rest = trimmed.substring("class ".length).trim()
                val parts = rest.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val targets = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val clsNames = parts.drop(1).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                    for (t in targets) {
                        val existing = nodes.firstOrNull { it.id == t || it.label == t }
                        if (existing != null) existing.classes += clsNames
                        else pendingClasses.getOrPut(t) { mutableListOf() }.addAll(clsNames)
                    }
                }
                continue
            }
            if (lower.startsWith("style ")) {
                val rest = trimmed.substring("style ".length).trim()
                val sp = rest.indexOfFirst { it.isWhitespace() }
                if (sp > 0) {
                    val target = rest.substring(0, sp).trim()
                    val style = parseNodeStyle(rest.substring(sp + 1).trim())
                    val existing = nodes.firstOrNull { it.id == target || it.label == target }
                    if (existing != null) existing.inlineStyle = style
                    else pendingStyles[target] = style
                }
                continue
            }

            // Decorator line: `::icon(fa fa-book)`
            val iconMatch = Regex("""^::icon\(([^)]*)\)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (iconMatch != null) {
                nodes.lastOrNull()?.icon = iconMatch.groupValues[1].trim()
                continue
            }

            // Decorator line: `:::urgent large`
            if (trimmed.startsWith(":::")) {
                val clsList = trimmed.removePrefix(":::")
                    .split(Regex("[\\s,]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                nodes.lastOrNull()?.classes?.addAll(clsList)
                continue
            }

            val indent = measureIndent(clean)
            val (withoutIcon, inlineIcon) = extractInlineIcon(trimmed)
            val (nodeSpecText, inlineClasses) = splitInlineClasses(withoutIcon)
            val parsedSpec = parseNodeSpec(nodeSpecText)

            while (indentStack.isNotEmpty() && indentStack.last().indent >= indent) {
                indentStack.removeLast()
            }
            // If a second root-level node appears, attach it under the first root so the mindmap stays a single connected tree.
            val parent = indentStack.lastOrNull() ?: nodes.firstOrNull()
            val id = allocateId(parsedSpec.explicitId ?: parsedSpec.rawLabel.takeIf { !it.contains(' ') && !it.contains('\n') })
            val node = MutableMindmapNode(
                id = id,
                label = parsedSpec.label,
                shape = parsedSpec.shape,
                parentId = parent?.id,
                indent = indent,
                icon = inlineIcon,
            )
            node.classes += inlineClasses
            pendingClasses[id]?.let { node.classes += it }
            pendingClasses[node.label]?.let { node.classes += it }
            pendingStyles[id]?.let { node.inlineStyle = it }
            pendingStyles[node.label]?.let { node.inlineStyle = it }
            nodes += node
            indentStack.addLast(node)
        }

        val finalNodes = nodes.map { n ->
            MindmapNode(
                id = n.id,
                label = n.label,
                shape = n.shape,
                parentId = n.parentId,
                icon = n.icon,
                classes = n.classes.distinct(),
                style = resolveStyle(n.classes, n.inlineStyle, classDefs),
            )
        }

        return MindmapDiagram(
            nodes = finalNodes,
            title = title,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private data class ParsedNodeSpec(
        val explicitId: String?,
        val rawLabel: String,
        val label: String,
        val shape: MindmapShape,
    )

    private val SHAPE_DELIMITERS = listOf(
        Triple("((", "))", MindmapShape.Circle),
        Triple("))", "((", MindmapShape.Bang),
        Triple("{{", "}}", MindmapShape.Hexagon),
        Triple("[", "]", MindmapShape.Square),
        Triple("(", ")", MindmapShape.Rounded),
        Triple(")", "(", MindmapShape.Cloud),
    )

    private fun parseNodeSpec(text: String): ParsedNodeSpec {
        val s = text.trim()
        for ((open, close, shape) in SHAPE_DELIMITERS) {
            if (s.endsWith(close)) {
                val openIdx = findShapeOpenerOutsideQuotes(s, open, close)
                if (openIdx >= 0) {
                    val prefix = s.substring(0, openIdx).trim().takeIf { it.isNotEmpty() }
                    val inner = s.substring(openIdx + open.length, s.length - close.length).trim()
                    val norm = normalizeLabel(inner)
                    return ParsedNodeSpec(
                        explicitId = prefix?.let(::unquote),
                        rawLabel = norm,
                        label = norm,
                        shape = shape,
                    )
                }
            }
        }
        val norm = normalizeLabel(s)
        return ParsedNodeSpec(
            explicitId = null,
            rawLabel = norm,
            label = norm,
            shape = MindmapShape.Default,
        )
    }

    private fun findShapeOpenerOutsideQuotes(s: String, open: String, close: String): Int {
        val limit = s.length - close.length - open.length
        var inQuotes = false
        var i = 0
        while (i <= limit) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && s.startsWith(open, i)) {
                val prefix = s.substring(0, i).trim()
                // Prefix must be empty or a valid identifier (no spaces outside quotes).
                if (prefix.isEmpty() || !prefix.any { it.isWhitespace() }) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun joinMultilineQuotedLines(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (raw in lines) {
            val (clean, nextInQuotes) = stripLineCommentWithState(raw, inQuotes)
            if (!inQuotes) {
                sb.setLength(0)
                sb.append(clean)
            } else {
                sb.append('\n').append(clean.trim())
            }
            inQuotes = nextInQuotes
            if (!inQuotes) {
                out += sb.toString()
            }
        }
        if (inQuotes && sb.isNotEmpty()) {
            out += sb.toString()
        }
        return out
    }

    private fun measureIndent(line: String): Int {
        var count = 0
        for (c in line) {
            when (c) {
                ' ' -> count += 1
                '\t' -> count += 4
                else -> break
            }
        }
        return count
    }

    private fun extractInlineIcon(raw: String): Pair<String, String?> {
        var inQuotes = false
        var i = 0
        while (i <= raw.length - 8) {
            val c = raw[i]
            if (c == '\\' && inQuotes && i + 1 < raw.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (
                !inQuotes &&
                (i == 0 || raw[i - 1] != ':') &&
                raw.regionMatches(i, "::icon(", 0, 7, ignoreCase = true)
            ) {
                val closeIdx = raw.indexOf(')', i + 7)
                if (closeIdx >= i + 7) {
                    val icon = raw.substring(i + 7, closeIdx).trim()
                    val before = raw.substring(0, i).trimEnd()
                    val after = raw.substring(closeIdx + 1).trimStart()
                    val combined = if (before.isNotEmpty() && after.isNotEmpty()) "$before $after" else before + after
                    return combined.trim() to icon
                }
            }
            i++
        }
        return raw to null
    }

    private fun splitInlineClasses(raw: String): Pair<String, List<String>> {
        var inQuotes = false
        var i = 0
        while (i <= raw.length - 3) {
            val c = raw[i]
            if (c == '\\' && inQuotes && i + 1 < raw.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && raw.startsWith(":::", i)) {
                val base = raw.substring(0, i).trim()
                val classes = raw.substring(i + 3)
                    .split(Regex("[\\s,]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                return base to classes
            }
            i++
        }
        return raw to emptyList()
    }

    private fun stripLineComment(line: String): String =
        stripLineCommentWithState(line, startInQuotes = false).first

    private fun stripLineCommentWithState(line: String, startInQuotes: Boolean): Pair<String, Boolean> {
        var inQuotes = startInQuotes
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                i++
                continue
            }
            if (!inQuotes && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i) to inQuotes
            }
            i++
        }
        return line to inQuotes
    }

    private fun unquote(s: String): String {
        var t = s.trim()
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            t = t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (t.length >= 2 && t.startsWith('`') && t.endsWith('`')) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private fun normalizeLabel(s: String): String {
        val unq = unquote(s)
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        // Convert markdown **bold** and *italic* to HTML tags supported by annotatedLabel.
        val withBold = Regex("""\*\*(.+?)\*\*""").replace(unq) { "<b>${it.groupValues[1]}</b>" }
        return Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""").replace(withBold) { "<i>${it.groupValues[1]}</i>" }
    }

    private fun parseNodeStyle(spec: String): NodeStyle {
        var fill: String? = null
        var stroke: String? = null
        var strokeWidth: String? = null
        var color: String? = null
        for (part in spec.split(',')) {
            val kv = part.split(':', limit = 2)
            if (kv.size != 2) continue
            val k = kv[0].trim().lowercase()
            val v = kv[1].trim()
            when (k) {
                "fill", "background", "background-color" -> fill = v
                "stroke", "border-color" -> stroke = v
                "stroke-width" -> strokeWidth = v
                "color" -> color = v
            }
        }
        return NodeStyle(
            fill = fill,
            stroke = stroke,
            textColor = color,
            strokeWidth = strokeWidth,
        )
    }

    private fun resolveStyle(
        classes: List<String>,
        inline: NodeStyle?,
        classDefs: Map<String, NodeStyle>,
    ): NodeStyle? {
        var acc: NodeStyle? = classDefs["default"]
        for (c in classes) {
            val def = classDefs[c] ?: continue
            acc = acc?.merge(def) ?: def
        }
        if (inline != null) {
            acc = acc?.merge(inline) ?: inline
        }
        return acc
    }
}
