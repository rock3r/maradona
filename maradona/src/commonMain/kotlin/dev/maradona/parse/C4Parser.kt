package dev.maradona.parse

import dev.maradona.model.C4Boundary
import dev.maradona.model.C4BoundaryKind
import dev.maradona.model.C4Config
import dev.maradona.model.C4Diagram
import dev.maradona.model.C4DiagramType
import dev.maradona.model.C4Element
import dev.maradona.model.C4ElementKind
import dev.maradona.model.C4ElementStyle
import dev.maradona.model.C4RelKind
import dev.maradona.model.C4RelStyle
import dev.maradona.model.C4Relationship
import dev.maradona.model.Direction

/**
 * Hand-written parser for Mermaid C4 diagrams (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`).
 *
 * Supports:
 * - Headers (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`), `direction TB|TD|BT|LR|RL`,
 *   YAML frontmatter, `%%{init}%%` configuration (`title`, `c4ShapeInRow`, `c4BoundaryInRow`, `diagramMarginX`, `diagramMarginY`),
 *   `title`, `accTitle`, and `accDescr`
 * - Elements: `Person`, `Person_Ext`, `System`, `System_Ext`, `SystemDb`, `SystemDb_Ext`, `SystemQueue`, `SystemQueue_Ext`,
 *   `Container`, `Container_Ext`, `ContainerDb`, `ContainerDb_Ext`, `ContainerQueue`, `ContainerQueue_Ext`,
 *   `Component`, `Component_Ext`, `ComponentDb`, `ComponentDb_Ext`, `ComponentQueue`, `ComponentQueue_Ext`
 * - Boundaries and Deployment Nodes (`{ ... }`): `Boundary`, `Enterprise_Boundary`, `System_Boundary`,
 *   `Container_Boundary`, `Deployment_Node`, `Node`, `Node_L`, `Node_R`
 * - Relationships: `Rel`, `BiRel`, `Rel_Back`, `Rel_U`/`Rel_Up`, `Rel_D`/`Rel_Down`, `Rel_L`/`Rel_Left`, `Rel_R`/`Rel_Right`, `RelIndex`
 * - Styling & Layout overrides: `UpdateElementStyle`, `UpdateBoundaryStyle`, `UpdateRelStyle`, `UpdateLayoutConfig`
 *   (both positional and `$key="value"` named arguments)
 */
object C4Parser {

    private class MutableConfig(
        var c4ShapeInRow: Int = 4,
        var c4BoundaryInRow: Int = 2,
        var diagramMarginX: Float = 24f,
        var diagramMarginY: Float = 24f,
    ) {
        fun toImmutable(): C4Config = C4Config(
            c4ShapeInRow = c4ShapeInRow,
            c4BoundaryInRow = c4BoundaryInRow,
            diagramMarginX = diagramMarginX,
            diagramMarginY = diagramMarginY,
        )
    }

    private data class ParsedArgs(
        val positional: List<String>,
        val named: Map<String, String>,
    ) {
        fun get(posIndex: Int, vararg names: String): String? {
            for (n in names) {
                val v = named[n.lowercase().removePrefix("$")]
                if (v != null) return v
            }
            return positional.getOrNull(posIndex)?.takeIf { it.isNotEmpty() }
        }
    }

    fun parse(source: String): C4Diagram {
        var frontmatterTitle: String? = null
        val config = MutableConfig()

        val (strippedSource, initDirectives) = stripAndExtractActiveInitDirectives(source)
        val rawLines = strippedSource.lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            val sectionStack = mutableListOf<Pair<Int, String>>()
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val indent = cleanFm.first.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                while (sectionStack.isNotEmpty() && sectionStack.last().first >= indent) {
                    sectionStack.removeAt(sectionStack.lastIndex)
                }
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    var rawVal = fmLine.substring(colon + 1).trim()
                    if (rawVal.isEmpty()) {
                        sectionStack += indent to key
                        continue
                    }
                    if (rawVal.startsWith("{")) {
                        var braceBalance = countUnquotedBraceDelta(rawVal)
                        val flowBuilder = StringBuilder(rawVal)
                        while (braceBalance > 0 && idx < rawLines.size) {
                            val nextFm = rawLines[idx]
                            val nextTrimmed = nextFm.trim()
                            if (activeFmQuote == null && (nextTrimmed == "---" || nextTrimmed == "...")) break
                            idx++
                            val nextClean = GanttParser.stripYamlCommentWithQuoteState(nextFm, activeFmQuote)
                            activeFmQuote = nextClean.second
                            val nextPart = nextClean.first.trim()
                            if (nextPart.isNotEmpty()) {
                                flowBuilder.append(' ').append(nextPart)
                                braceBalance += countUnquotedBraceDelta(nextPart)
                            }
                        }
                        rawVal = flowBuilder.toString().trim()
                    }
                    val path = sectionStack.map { it.second }
                    val inC4Config = path.isEmpty() ||
                        path == listOf("c4") ||
                        path == listOf("config", "c4")
                    if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                        if ((key == "c4" && (path.isEmpty() || path == listOf("config"))) || (key == "config" && path.isEmpty())) {
                            parseInitDirective(rawVal, config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                            }
                        }
                        continue
                    }
                    val value = unquote(rawVal)
                    if (path.isEmpty() && key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = normalizeText(value)
                    } else if (inC4Config) {
                        applyConfigEntry(config, key, value) { t ->
                            if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeText(t)
                        }
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        for (initDirective in initDirectives) {
            parseInitDirective(initDirective, config) { t ->
                if (t.isNotEmpty()) frontmatterTitle = normalizeText(t)
            }
        }

        var headerSeen = false
        var diagramType = C4DiagramType.CONTEXT
        var direction = Direction.TB
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val elementsById = LinkedHashMap<String, C4Element>()
        val boundariesById = LinkedHashMap<String, C4Boundary>()
        val relationships = mutableListOf<C4Relationship>()
        val elementStyles = LinkedHashMap<String, C4ElementStyle>()
        val relStyles = LinkedHashMap<Pair<String, String>, C4RelStyle>()
        val boundaryStack = mutableListOf<String>()
        var pendingBoundaryId: String? = null

        var lineIdx = 0
        while (lineIdx < bodyLines.size) {
            val rawLine = bodyLines[lineIdx]
            lineIdx++
            val rawTrimmed = rawLine.trim()
            if (inAccDescrBlock) {
                val closeIdx = rawTrimmed.indexOf('}')
                if (closeIdx >= 0) {
                    val beforeClose = rawTrimmed.substring(0, closeIdx).trim()
                    if (beforeClose.isNotEmpty()) accDescrLines += beforeClose
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else if (rawTrimmed.isNotEmpty()) {
                    accDescrLines += rawTrimmed
                }
                continue
            }

            val commentFree = stripLineComment(rawLine)
            var trimmed = commentFree.trim()
            if (trimmed.isEmpty()) continue

            if (!headerSeen) {
                val headerMatch = Regex(
                    """^(C4Context|C4Container|C4Component|C4Dynamic|C4Deployment)(?:\s+title(?:\s+|:)\s*(.*)|\s*)?$""",
                    RegexOption.IGNORE_CASE,
                ).find(trimmed)
                if (headerMatch != null) {
                    headerSeen = true
                    diagramType = when (headerMatch.groupValues[1].lowercase()) {
                        "c4context" -> C4DiagramType.CONTEXT
                        "c4container" -> C4DiagramType.CONTAINER
                        "c4component" -> C4DiagramType.COMPONENT
                        "c4dynamic" -> C4DiagramType.DYNAMIC
                        "c4deployment" -> C4DiagramType.DEPLOYMENT
                        else -> C4DiagramType.CONTEXT
                    }
                    val inlineTitle = headerMatch.groupValues[2].trim()
                    if (inlineTitle.isNotEmpty()) {
                        chartTitle = normalizeText(inlineTitle)
                    }
                    continue
                }
                throw IllegalArgumentException("Expected C4Context, C4Container, C4Component, C4Dynamic, or C4Deployment header, got: $trimmed")
            }

            // Handle standalone `{` after a boundary declaration on the previous line
            while (trimmed.startsWith("{")) {
                val pending = pendingBoundaryId
                    ?: throw IllegalArgumentException("Unexpected '{' without preceding C4 boundary declaration")
                boundaryStack += pending
                pendingBoundaryId = null
                trimmed = trimmed.substring(1).trim()
            }
            while (trimmed.startsWith("}")) {
                if (boundaryStack.isEmpty()) {
                    throw IllegalArgumentException("Unmatched '}' in C4 diagram")
                }
                boundaryStack.removeAt(boundaryStack.lastIndex)
                trimmed = trimmed.substring(1).trim()
            }
            if (trimmed.isEmpty()) continue

            val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (accBlockMatch != null) {
                val afterOpen = accBlockMatch.groupValues[1]
                val closeIdx = afterOpen.indexOf('}')
                if (closeIdx >= 0) {
                    accDescr = normalizeText(afterOpen.substring(0, closeIdx).trim())
                } else {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    val firstLine = afterOpen.trim()
                    if (firstLine.isNotEmpty()) accDescrLines += firstLine
                }
                continue
            }

            val dirMatch = Regex("""^direction\s+(TB|TD|BT|LR|RL)\s*;?$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (dirMatch != null) {
                direction = when (dirMatch.groupValues[1].uppercase()) {
                    "TB", "TD" -> Direction.TB
                    "BT" -> Direction.BT
                    "LR" -> Direction.LR
                    "RL" -> Direction.RL
                    else -> Direction.TB
                }
                continue
            }

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (titleMatch != null) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }
            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (accTitleMatch != null) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (accDescrMatch != null) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }

            // If this line starts a macro call whose parentheses span multiple lines, join until balanced.
            var statement = trimmed
            while (countUnquotedParenDelta(statement) > 0 && lineIdx < bodyLines.size) {
                val nextLine = stripLineComment(bodyLines[lineIdx]).trim()
                lineIdx++
                if (nextLine.isNotEmpty()) {
                    statement = "$statement $nextLine"
                }
            }

            // Strip trailing `}` braces that close boundary blocks at the end of a statement line.
            var trailingCloseBraces = 0
            while (statement.endsWith("}")) {
                val beforeBrace = statement.dropLast(1).trimEnd()
                // Only treat trailing `}` as a block close if parentheses and quotes are balanced.
                if (countUnquotedParenDelta(beforeBrace) == 0) {
                    trailingCloseBraces++
                    statement = beforeBrace
                } else {
                    break
                }
            }

            var opensBlock = false
            if (statement.endsWith("{")) {
                val beforeOpen = statement.dropLast(1).trimEnd()
                if (countUnquotedParenDelta(beforeOpen) == 0) {
                    opensBlock = true
                    statement = beforeOpen
                }
            }

            statement = statement.removeSuffix(";").trim()
            if (statement.isNotEmpty()) {
                val currentParent = boundaryStack.lastOrNull()
                val createdBoundaryId = parseMacroStatement(
                    statement = statement,
                    currentParent = currentParent,
                    elementsById = elementsById,
                    boundariesById = boundariesById,
                    relationships = relationships,
                    elementStyles = elementStyles,
                    relStyles = relStyles,
                    config = config,
                )
                if (createdBoundaryId != null) {
                    if (opensBlock) {
                        boundaryStack += createdBoundaryId
                        pendingBoundaryId = null
                    } else {
                        pendingBoundaryId = createdBoundaryId
                    }
                } else if (opensBlock) {
                    throw IllegalArgumentException("Unexpected '{' on non-boundary C4 statement: $statement")
                }
            }

            repeat(trailingCloseBraces) {
                if (pendingBoundaryId != null) {
                    pendingBoundaryId = null
                } else if (boundaryStack.isNotEmpty()) {
                    boundaryStack.removeAt(boundaryStack.lastIndex)
                } else {
                    throw IllegalArgumentException("Unmatched '}' in C4 diagram")
                }
            }
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in C4 diagram")
        }
        if (!headerSeen) {
            throw IllegalArgumentException("Expected C4 diagram header")
        }
        if (boundaryStack.isNotEmpty()) {
            throw IllegalArgumentException("Unclosed boundary '${boundaryStack.last()}' in C4 diagram")
        }

        // Apply element/boundary style overrides and relationship style overrides.
        val finalElements = elementsById.values.map { el ->
            val st = elementStyles[el.id]
            if (st != null) el.copy(style = el.style?.merge(st) ?: st) else el
        }
        val finalBoundaries = boundariesById.values.map { b ->
            val st = elementStyles[b.id]
            if (st != null) b.copy(style = b.style?.merge(st) ?: st) else b
        }
        val finalRelationships = relationships.map { rel ->
            val st = relStyles[rel.from to rel.to]
            if (st != null) rel.copy(style = rel.style?.merge(st) ?: st) else rel
        }

        return C4Diagram(
            type = diagramType,
            direction = direction,
            elements = finalElements,
            boundaries = finalBoundaries,
            relationships = finalRelationships,
            config = config.toImmutable(),
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun parseMacroStatement(
        statement: String,
        currentParent: String?,
        elementsById: LinkedHashMap<String, C4Element>,
        boundariesById: LinkedHashMap<String, C4Boundary>,
        relationships: MutableList<C4Relationship>,
        elementStyles: LinkedHashMap<String, C4ElementStyle>,
        relStyles: LinkedHashMap<Pair<String, String>, C4RelStyle>,
        config: MutableConfig,
    ): String? {
        val openParen = statement.indexOf('(')
        val closeParen = statement.lastIndexOf(')')
        if (openParen <= 0 || closeParen < openParen || closeParen != statement.length - 1) {
            throw IllegalArgumentException("Unrecognized C4 statement: $statement")
        }
        val macroName = statement.substring(0, openParen).trim()
        val rawArgStr = statement.substring(openParen + 1, closeParen)
        val args = parseArguments(rawArgStr)

        when (val lower = macroName.lowercase()) {
            "person", "person_ext",
            "system", "system_ext", "systemdb", "systemdb_ext", "systemqueue", "systemqueue_ext", -> {
                val kind = when (lower) {
                    "person" -> C4ElementKind.PERSON
                    "person_ext" -> C4ElementKind.PERSON_EXT
                    "system" -> C4ElementKind.SYSTEM
                    "system_ext" -> C4ElementKind.SYSTEM_EXT
                    "systemdb" -> C4ElementKind.SYSTEM_DB
                    "systemdb_ext" -> C4ElementKind.SYSTEM_DB_EXT
                    "systemqueue" -> C4ElementKind.SYSTEM_QUEUE
                    else -> C4ElementKind.SYSTEM_QUEUE_EXT
                }
                val id = args.get(0, "alias")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing alias in $macroName: $statement")
                val label = args.get(1, "label") ?: id
                val descr = args.get(2, "descr", "description")
                val sprite = args.get(3, "sprite")
                val tags = args.get(4, "tags")
                val link = args.get(5, "link")
                elementsById[id] = C4Element(
                    id = id,
                    label = normalizeText(label),
                    kind = kind,
                    technology = args.named["techn"] ?: args.named["technology"],
                    description = descr?.let { normalizeText(it) },
                    sprite = sprite,
                    tags = tags,
                    link = link,
                    parentBoundaryId = currentParent,
                )
                return null
            }

            "container", "container_ext", "containerdb", "containerdb_ext", "containerqueue", "containerqueue_ext",
            "component", "component_ext", "componentdb", "componentdb_ext", "componentqueue", "componentqueue_ext", -> {
                val kind = when (lower) {
                    "container" -> C4ElementKind.CONTAINER
                    "container_ext" -> C4ElementKind.CONTAINER_EXT
                    "containerdb" -> C4ElementKind.CONTAINER_DB
                    "containerdb_ext" -> C4ElementKind.CONTAINER_DB_EXT
                    "containerqueue" -> C4ElementKind.CONTAINER_QUEUE
                    "containerqueue_ext" -> C4ElementKind.CONTAINER_QUEUE_EXT
                    "component" -> C4ElementKind.COMPONENT
                    "component_ext" -> C4ElementKind.COMPONENT_EXT
                    "componentdb" -> C4ElementKind.COMPONENT_DB
                    "componentdb_ext" -> C4ElementKind.COMPONENT_DB_EXT
                    "componentqueue" -> C4ElementKind.COMPONENT_QUEUE
                    else -> C4ElementKind.COMPONENT_QUEUE_EXT
                }
                val id = args.get(0, "alias")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing alias in $macroName: $statement")
                val label = args.get(1, "label") ?: id
                val techn = args.get(2, "techn", "technology")
                val descr = args.get(3, "descr", "description")
                val sprite = args.get(4, "sprite")
                val tags = args.get(5, "tags")
                val link = args.get(6, "link")
                elementsById[id] = C4Element(
                    id = id,
                    label = normalizeText(label),
                    kind = kind,
                    technology = techn?.let { normalizeText(it) },
                    description = descr?.let { normalizeText(it) },
                    sprite = sprite,
                    tags = tags,
                    link = link,
                    parentBoundaryId = currentParent,
                )
                return null
            }

            "boundary", "enterprise_boundary", "system_boundary", "container_boundary",
            "deployment_node", "deployment_node_l", "deployment_node_r", "node", "node_l", "node_r", -> {
                val (bKind, defaultType) = when (lower) {
                    "enterprise_boundary" -> C4BoundaryKind.ENTERPRISE to "ENTERPRISE"
                    "system_boundary" -> C4BoundaryKind.SYSTEM to "SYSTEM"
                    "container_boundary" -> C4BoundaryKind.CONTAINER to "CONTAINER"
                    "deployment_node", "deployment_node_l", "deployment_node_r", "node", "node_l", "node_r" ->
                        C4BoundaryKind.DEPLOYMENT_NODE to "NODE"
                    else -> C4BoundaryKind.GENERIC to "BOUNDARY"
                }
                val id = args.get(0, "alias")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing alias in $macroName: $statement")
                val label = args.get(1, "label") ?: id
                val customType = when (bKind) {
                    C4BoundaryKind.GENERIC, C4BoundaryKind.DEPLOYMENT_NODE -> args.get(2, "type", "techn") ?: defaultType
                    else -> args.named["type"] ?: defaultType
                }
                val descr = if (bKind == C4BoundaryKind.DEPLOYMENT_NODE) {
                    args.get(3, "descr", "description")
                } else {
                    args.named["descr"] ?: args.named["description"]
                }
                boundariesById[id] = C4Boundary(
                    id = id,
                    label = normalizeText(label),
                    kind = bKind,
                    type = normalizeText(customType),
                    description = descr?.let { normalizeText(it) },
                    parentBoundaryId = currentParent,
                )
                return id
            }

            "rel", "birel", "rel_back",
            "rel_u", "rel_up", "rel_d", "rel_down",
            "rel_l", "rel_left", "rel_r", "rel_right", -> {
                val relKind = when (lower) {
                    "birel" -> C4RelKind.BI_REL
                    "rel_back" -> C4RelKind.REL_BACK
                    "rel_u", "rel_up" -> C4RelKind.REL_UP
                    "rel_d", "rel_down" -> C4RelKind.REL_DOWN
                    "rel_l", "rel_left" -> C4RelKind.REL_LEFT
                    "rel_r", "rel_right" -> C4RelKind.REL_RIGHT
                    else -> C4RelKind.REL
                }
                val from = args.get(0, "from")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing 'from' in $macroName: $statement")
                val to = args.get(1, "to")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing 'to' in $macroName: $statement")
                val label = args.get(2, "label") ?: ""
                val techn = args.get(3, "techn", "technology")
                val descr = args.get(4, "descr", "description")
                relationships += C4Relationship(
                    from = from,
                    to = to,
                    label = normalizeText(label),
                    technology = techn?.let { normalizeText(it) },
                    description = descr?.let { normalizeText(it) },
                    kind = relKind,
                    index = args.named["index"],
                )
                return null
            }

            "relindex" -> {
                val explicitIdx = args.named["index"]
                val pos = args.positional
                val (relIndex, offset) = if (explicitIdx != null) {
                    explicitIdx to 0
                } else if (pos.size >= 4) {
                    pos[0] to 1
                } else {
                    (relationships.size + 1).toString() to 0
                }
                val from = args.get(offset, "from")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing 'from' in RelIndex: $statement")
                val to = args.get(offset + 1, "to")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing 'to' in RelIndex: $statement")
                val label = args.get(offset + 2, "label") ?: ""
                val techn = args.get(offset + 3, "techn", "technology")
                val descr = args.get(offset + 4, "descr", "description")
                relationships += C4Relationship(
                    from = from,
                    to = to,
                    label = normalizeText(label),
                    technology = techn?.let { normalizeText(it) },
                    description = descr?.let { normalizeText(it) },
                    kind = C4RelKind.REL_INDEX,
                    index = relIndex,
                )
                return null
            }

            "updateelementstyle", "updateboundarystyle" -> {
                val targetId = args.get(0, "elementname", "alias")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing elementName in $macroName: $statement")
                val bgColor = args.get(1, "bgcolor")
                val fontColor = args.get(2, "fontcolor")
                val borderColor = args.get(3, "bordercolor")
                val shadowingRaw = args.get(4, "shadowing")
                val shape = args.get(5, "shape")
                val shadowing = when (shadowingRaw?.lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
                val st = C4ElementStyle(
                    fontColor = fontColor,
                    bgColor = bgColor,
                    borderColor = borderColor,
                    shadowing = shadowing,
                    shape = shape,
                )
                elementStyles[targetId] = elementStyles[targetId]?.merge(st) ?: st
                return null
            }

            "updaterelstyle" -> {
                val from = args.get(0, "from")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing 'from' in UpdateRelStyle: $statement")
                val to = args.get(1, "to")?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Missing 'to' in UpdateRelStyle: $statement")
                val textColor = args.get(2, "textcolor")
                val lineColor = args.get(3, "linecolor")
                val offsetX = args.get(4, "offsetx")?.removeSuffix("px")?.toFloatOrNull()?.takeIf { it.isFinite() }
                val offsetY = args.get(5, "offsety")?.removeSuffix("px")?.toFloatOrNull()?.takeIf { it.isFinite() }
                val key = from to to
                val st = C4RelStyle(
                    textColor = textColor,
                    lineColor = lineColor,
                    offsetX = offsetX,
                    offsetY = offsetY,
                )
                relStyles[key] = relStyles[key]?.merge(st) ?: st
                return null
            }

            "updatelayoutconfig" -> {
                val shapeInRowStr = args.get(0, "c4shapeinrow")
                val boundaryInRowStr = args.get(1, "c4boundaryinrow")
                if (shapeInRowStr != null) {
                    val v = shapeInRowStr.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid c4ShapeInRow '$shapeInRowStr' in UpdateLayoutConfig")
                    if (v <= 0) throw IllegalArgumentException("c4ShapeInRow must be > 0, got $v")
                    config.c4ShapeInRow = v
                }
                if (boundaryInRowStr != null) {
                    val v = boundaryInRowStr.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid c4BoundaryInRow '$boundaryInRowStr' in UpdateLayoutConfig")
                    if (v <= 0) throw IllegalArgumentException("c4BoundaryInRow must be > 0, got $v")
                    config.c4BoundaryInRow = v
                }
                return null
            }

            else -> throw IllegalArgumentException("Unrecognized C4 macro '$macroName' in: $statement")
        }
    }

    private fun parseArguments(rawArgs: String): ParsedArgs {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var activeQuote: Char? = null
        var i = 0
        while (i < rawArgs.length) {
            val c = rawArgs[i]
            if (c == '\\' && activeQuote != null && i + 1 < rawArgs.length) {
                cur.append(rawArgs[i]).append(rawArgs[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
                cur.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                cur.append(c)
                i++
                continue
            }
            if (activeQuote == null && c == ',') {
                tokens += cur.toString().trim()
                cur.clear()
                i++
                continue
            }
            cur.append(c)
            i++
        }
        if (activeQuote != null) {
            throw IllegalArgumentException("Unclosed quote in C4 macro arguments: ($rawArgs)")
        }
        val last = cur.toString().trim()
        if (last.isNotEmpty() || tokens.isNotEmpty()) {
            tokens += last
        }

        val positional = mutableListOf<String>()
        val named = LinkedHashMap<String, String>()
        for (tok in tokens) {
            val eqIdx = findUnquotedEquals(tok)
            if (eqIdx > 0 && tok.substring(0, eqIdx).trim().startsWith("$")) {
                val key = tok.substring(0, eqIdx).trim().removePrefix("$").lowercase()
                val value = unquote(tok.substring(eqIdx + 1).trim())
                named[key] = value
            } else {
                positional += unquote(tok)
            }
        }
        return ParsedArgs(positional = positional, named = named)
    }

    private fun findUnquotedEquals(s: String): Int {
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
            } else if (activeQuote == c) {
                activeQuote = null
            } else if (activeQuote == null && c == '=') {
                return i
            }
            i++
        }
        return -1
    }

    private fun applyConfigEntry(
        config: MutableConfig,
        key: String,
        value: String,
        onTitle: (String) -> Unit,
    ) {
        when (key) {
            "title" -> onTitle(value)
            "c4shapeinrow" -> {
                val v = value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid C4 c4ShapeInRow '$value'")
                if (v <= 0) throw IllegalArgumentException("C4 c4ShapeInRow must be > 0, got $v")
                config.c4ShapeInRow = v
            }
            "c4boundaryinrow" -> {
                val v = value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid C4 c4BoundaryInRow '$value'")
                if (v <= 0) throw IllegalArgumentException("C4 c4BoundaryInRow must be > 0, got $v")
                config.c4BoundaryInRow = v
            }
            "diagrammarginx" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid C4 diagramMarginX '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("C4 diagramMarginX must be >= 0, got $value")
                config.diagramMarginX = v
            }
            "diagrammarginy" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid C4 diagramMarginY '$value'")
                if (!v.isFinite() || v < 0f) throw IllegalArgumentException("C4 diagramMarginY must be >= 0, got $value")
                config.diagramMarginY = v
            }
        }
    }

    private fun parseInitDirective(
        initDirective: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        for (blockMatch in Regex(
            """['"]?c4['"]?\s*:\s*\{([^{}]*)\}""",
            RegexOption.IGNORE_CASE,
        ).findAll(initDirective)) {
            parseJsonKeyValuePairs(blockMatch.groupValues[1], config, onTitle)
        }
        val topLevel = Regex("""['"]?[A-Za-z0-9_-]+['"]?\s*:\s*\{[^{}]*\}""").replace(initDirective, "")
        parseJsonKeyValuePairs(topLevel, config, onTitle)
    }

    private fun parseJsonKeyValuePairs(
        segment: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        val pairRegex = Regex(
            """['"]?([A-Za-z0-9_]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([A-Za-z0-9_.+#$%-]+))""",
        )
        for (m in pairRegex.findAll(segment)) {
            val key = m.groupValues[1].lowercase()
            val rawVal = when {
                m.groups[2] != null -> m.groupValues[2]
                m.groups[3] != null -> m.groupValues[3]
                else -> m.groupValues[4]
            }.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\").trim()
            applyConfigEntry(config, key, rawVal, onTitle)
        }
    }

    private fun countUnquotedBraceDelta(s: String): Int {
        var delta = 0
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
            } else if (activeQuote == c) {
                activeQuote = null
            } else if (activeQuote == null) {
                if (c == '{') delta++
                else if (c == '}') delta--
            }
            i++
        }
        return delta
    }

    private fun countUnquotedParenDelta(s: String): Int {
        var delta = 0
        var activeQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && activeQuote != null && i + 1 < s.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || c == '\'')) {
                activeQuote = c
            } else if (activeQuote == c) {
                activeQuote = null
            } else if (activeQuote == null) {
                if (c == '(') delta++
                else if (c == ')') delta--
            }
            i++
        }
        return delta
    }

    private fun isQuoteOpeningContext(s: String, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == '\t')) {
            j--
        }
        return j < 0 || s[j] == '\n' || s[j] == '\r' || s[j] == ':' || s[j] == '{' || s[j] == ',' || s[j] == '(' || s[j] == '='
    }

    private fun stripAndExtractActiveInitDirectives(source: String): Pair<String, List<String>> {
        val stripped = StringBuilder(source.length)
        val results = mutableListOf<String>()
        var i = 0
        var activeQuote: Char? = null
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && activeQuote != null && i + 1 < source.length) {
                stripped.append(source[i]).append(source[i + 1])
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(source, i)))) {
                activeQuote = c
                stripped.append(c)
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                stripped.append(c)
                i++
                continue
            }
            if (activeQuote == null) {
                if (source.startsWith("%%{", i)) {
                    val endIdx = source.indexOf("}%%", i + 3)
                    if (endIdx >= 0) {
                        results += source.substring(i, endIdx + 3)
                        i = endIdx + 3
                        continue
                    }
                }
                if (c == '#' || c == '%') {
                    val nl = source.indexOf('\n', i)
                    if (nl < 0) {
                        stripped.append(source, i, source.length)
                        break
                    }
                    stripped.append(source, i, nl + 1)
                    i = nl + 1
                    continue
                }
            }
            stripped.append(c)
            i++
        }
        return stripped.toString() to results
    }

    private fun stripLineComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("%%")) return ""
        var activeQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && activeQuote != null && i + 1 < line.length) {
                i += 2
                continue
            }
            if (activeQuote == null && (c == '"' || (c == '\'' && isQuoteOpeningContext(line, i)))) {
                activeQuote = c
                i++
                continue
            }
            if (activeQuote == c) {
                activeQuote = null
                i++
                continue
            }
            if (activeQuote == null && line.startsWith("%%", i)) {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .trim()
        }
        return t
    }

    private fun normalizeText(s: String): String {
        val u = unquote(s).replace("\\n", "\n")
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(u, "\n")
        return brReplaced.trim()
    }
}
