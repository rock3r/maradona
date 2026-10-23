package dev.maradona.parse

import dev.maradona.model.Direction
import dev.maradona.model.ElementItem
import dev.maradona.model.RequirementDiagram
import dev.maradona.model.RequirementItem
import dev.maradona.model.RequirementKind
import dev.maradona.model.RequirementNodeStyle
import dev.maradona.model.RequirementRelation
import dev.maradona.model.RequirementRelationType
import dev.maradona.model.RequirementRisk
import dev.maradona.model.RequirementVerifyMethod

/**
 * Hand-written parser for Mermaid `requirementDiagram` diagrams.
 *
 * Supports:
 * - `requirementDiagram` header, YAML frontmatter, `%%{init}%%` directives, `title`, `accTitle`, `accDescr`
 * - `direction TB|TD|BT|LR|RL`
 * - `requirement`, `functionalRequirement`, `interfaceRequirement`, `performanceRequirement`,
 *   `physicalRequirement`, and `designConstraint` blocks (`id`, `text`, `risk`, `verifymethod`)
 * - `element` blocks (`type`, `docRef`)
 * - Relationships `<src> - <type> -> <dst>` and `<dst> <- <type> - <src>` (`contains`, `copies`,
 *   `derives`, `satisfies`, `verifies`, `refines`, `traces`)
 * - `classDef`, `class`, inline `:::className`, and `style` statements
 */
object RequirementParser {

    private class MutableRequirement(
        val name: String,
        val kind: RequirementKind,
        var requirementId: String? = null,
        var text: String? = null,
        var risk: RequirementRisk? = null,
        var verifyMethod: RequirementVerifyMethod? = null,
        val classes: MutableList<String> = mutableListOf("default"),
        var inlineStyle: RequirementNodeStyle? = null,
    )

    private class MutableElement(
        val name: String,
        var type: String? = null,
        var docRef: String? = null,
        val classes: MutableList<String> = mutableListOf("default"),
        var inlineStyle: RequirementNodeStyle? = null,
    )

    private sealed interface ActiveBlock {
        data class ReqBlock(val req: MutableRequirement) : ActiveBlock
        data class ElemBlock(val elem: MutableElement) : ActiveBlock
    }

    fun parse(source: String): RequirementDiagram {
        var frontmatterTitle: String? = null
        var direction = Direction.TB

        val initMatch = Regex("""%%\{.*?\}%%""", setOf(RegexOption.DOT_MATCHES_ALL)).find(source)
        if (initMatch != null) {
            val initBody = initMatch.value
            val titleMatch = Regex(
                """['"]?title['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)')""",
                RegexOption.IGNORE_CASE,
            ).find(initBody)
            if (titleMatch != null) {
                val rawTitle = titleMatch.groupValues[1].ifEmpty { titleMatch.groupValues[2] }
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\\\", "\\")
                    .trim()
                if (rawTitle.isNotEmpty()) {
                    frontmatterTitle = normalizeText(rawTitle)
                }
            }
        }

        val rawLines = FlowchartParser.stripDirectives(source).lines()
        val bodyLines = mutableListOf<String>()
        var idx = 0
        while (idx < rawLines.size && stripLineComment(rawLines[idx]).trim().isEmpty()) {
            idx++
        }
        if (idx < rawLines.size && rawLines[idx].trim() == "---") {
            idx++
            var activeFmQuote: Char? = null
            while (idx < rawLines.size) {
                val rawFm = rawLines[idx]
                idx++
                val trimmedFm = rawFm.trim()
                if (activeFmQuote == null && (trimmedFm == "---" || trimmedFm == "...")) break
                val cleanFm = GanttParser.stripYamlCommentWithQuoteState(rawFm, activeFmQuote)
                activeFmQuote = cleanFm.second
                val fmLine = cleanFm.first.trim()
                if (fmLine.isEmpty()) continue
                val colon = fmLine.indexOf(':')
                if (colon > 0) {
                    val key = fmLine.substring(0, colon).trim().lowercase()
                    val value = unquote(fmLine.substring(colon + 1).trim())
                    when (key) {
                        "title" -> if (value.isNotEmpty()) frontmatterTitle = normalizeText(value)
                    }
                }
            }
        }
        while (idx < rawLines.size) {
            bodyLines += rawLines[idx]
            idx++
        }

        val mergedLines = mergeMultilineQuotedLines(bodyLines)

        var headerSeen = false
        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        val requirements = LinkedHashMap<String, MutableRequirement>()
        val elements = LinkedHashMap<String, MutableElement>()
        val relations = mutableListOf<RequirementRelation>()
        val classDefs = LinkedHashMap<String, RequirementNodeStyle>()
        val pendingClasses = LinkedHashMap<String, MutableList<String>>()
        val pendingStyles = LinkedHashMap<String, RequirementNodeStyle>()

        var activeBlock: ActiveBlock? = null

        for (rawLine in mergedLines) {
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

            if (activeBlock == null) {
                val accBlockMatch = Regex("""^accDescr\s*\{(.*)$""", RegexOption.IGNORE_CASE).find(rawTrimmed)
                if (accBlockMatch != null) {
                    headerSeen = true
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
            }

            val commentFree = stripLineComment(rawLine).trim()
            if (commentFree.isEmpty()) continue

            if (activeBlock != null) {
                val closeIdx = findCharOutsideQuotes(commentFree, '}')
                if (closeIdx >= 0) {
                    val beforeClose = commentFree.substring(0, closeIdx).trim()
                    if (beforeClose.isNotEmpty()) {
                        parseBlockBodyEntries(activeBlock, beforeClose)
                    }
                    val afterClose = commentFree.substring(closeIdx + 1).trim()
                    activeBlock = null
                    if (afterClose.isNotEmpty()) {
                        throw IllegalArgumentException("Unexpected trailing tokens after '}': $commentFree")
                    }
                } else {
                    parseBlockBodyEntries(activeBlock, commentFree)
                }
                continue
            }

            if (!headerSeen) {
                val headerMatch = Regex("""^requirementDiagram$""", RegexOption.IGNORE_CASE).find(commentFree)
                if (headerMatch != null) {
                    headerSeen = true
                    continue
                }
                if (commentFree.lowercase().startsWith("requirementdiagram")) {
                    throw IllegalArgumentException("Unexpected tokens on requirementDiagram header line: $commentFree")
                }
                headerSeen = true
            }

            val dirMatch = Regex("""^direction\s+(TB|TD|BT|LR|RL)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (dirMatch != null) {
                direction = when (dirMatch.groupValues[1].uppercase()) {
                    "BT" -> Direction.BT
                    "LR" -> Direction.LR
                    "RL" -> Direction.RL
                    else -> Direction.TB
                }
                continue
            }

            val titleMatch = Regex("""^title(?:\s+|:)(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (titleMatch != null) {
                val after = titleMatch.groupValues[1].trim()
                if (after.isNotEmpty()) chartTitle = normalizeText(after)
                continue
            }
            val accTitleMatch = Regex("""^accTitle\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (accTitleMatch != null) {
                accTitle = normalizeText(accTitleMatch.groupValues[1].trim())
                continue
            }
            val accDescrMatch = Regex("""^accDescr\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (accDescrMatch != null) {
                accDescr = normalizeText(accDescrMatch.groupValues[1].trim())
                continue
            }

            // Requirement block header: `<reqKind> <name>[:::<classes>] { ... }`
            val reqMatch = Regex(
                """^(requirement|functionalRequirement|interfaceRequirement|performanceRequirement|physicalRequirement|designConstraint)\s+(.+)$""",
                RegexOption.IGNORE_CASE,
            ).find(commentFree)
            if (reqMatch != null) {
                val kind = parseRequirementKind(reqMatch.groupValues[1])
                val rest = reqMatch.groupValues[2].trim()
                val braceIdx = findCharOutsideQuotes(rest, '{')
                if (braceIdx < 0) {
                    throw IllegalArgumentException("Missing '{' in requirement declaration: $commentFree")
                }
                val nameAndClasses = rest.substring(0, braceIdx).trim()
                val (reqName, clsList) = splitNameAndClasses(nameAndClasses)
                if (reqName in elements) {
                    throw IllegalArgumentException("Requirement '$reqName' conflicts with an element of the same name")
                }
                val req = requirements.getOrPut(reqName) {
                    MutableRequirement(name = reqName, kind = kind)
                }
                for (c in clsList) {
                    if (c !in req.classes) req.classes += c
                }
                val afterBrace = rest.substring(braceIdx + 1).trim()
                val closeIdx = findCharOutsideQuotes(afterBrace, '}')
                if (closeIdx >= 0) {
                    val inner = afterBrace.substring(0, closeIdx).trim()
                    if (inner.isNotEmpty()) {
                        parseBlockBodyEntries(ActiveBlock.ReqBlock(req), inner)
                    }
                    val trailing = afterBrace.substring(closeIdx + 1).trim()
                    if (trailing.isNotEmpty()) {
                        throw IllegalArgumentException("Unexpected trailing tokens after '}': $commentFree")
                    }
                } else {
                    if (afterBrace.isNotEmpty()) {
                        parseBlockBodyEntries(ActiveBlock.ReqBlock(req), afterBrace)
                    }
                    activeBlock = ActiveBlock.ReqBlock(req)
                }
                continue
            }

            // Element block header: `element <name>[:::<classes>] { ... }`
            val elemMatch = Regex("""^element\s+(.+)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (elemMatch != null) {
                val rest = elemMatch.groupValues[1].trim()
                val braceIdx = findCharOutsideQuotes(rest, '{')
                if (braceIdx < 0) {
                    throw IllegalArgumentException("Missing '{' in element declaration: $commentFree")
                }
                val nameAndClasses = rest.substring(0, braceIdx).trim()
                val (elemName, clsList) = splitNameAndClasses(nameAndClasses)
                if (elemName in requirements) {
                    throw IllegalArgumentException("Element '$elemName' conflicts with a requirement of the same name")
                }
                val elem = elements.getOrPut(elemName) {
                    MutableElement(name = elemName)
                }
                for (c in clsList) {
                    if (c !in elem.classes) elem.classes += c
                }
                val afterBrace = rest.substring(braceIdx + 1).trim()
                val closeIdx = findCharOutsideQuotes(afterBrace, '}')
                if (closeIdx >= 0) {
                    val inner = afterBrace.substring(0, closeIdx).trim()
                    if (inner.isNotEmpty()) {
                        parseBlockBodyEntries(ActiveBlock.ElemBlock(elem), inner)
                    }
                    val trailing = afterBrace.substring(closeIdx + 1).trim()
                    if (trailing.isNotEmpty()) {
                        throw IllegalArgumentException("Unexpected trailing tokens after '}': $commentFree")
                    }
                } else {
                    if (afterBrace.isNotEmpty()) {
                        parseBlockBodyEntries(ActiveBlock.ElemBlock(elem), afterBrace)
                    }
                    activeBlock = ActiveBlock.ElemBlock(elem)
                }
                continue
            }

            // `classDef <ids> <styles>`
            val classDefMatch = Regex("""^classDef\s+(.+)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (classDefMatch != null) {
                val (targetPart, stylePart) = splitTargetListAndRest(classDefMatch.groupValues[1])
                    ?: throw IllegalArgumentException("Invalid classDef syntax in requirementDiagram: $commentFree")
                val names = splitCommaOutsideQuotes(targetPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                val style = parseNodeStyle(stylePart)
                for (n in names) {
                    classDefs[n] = classDefs[n]?.merge(style) ?: style
                }
                continue
            }

            // `class <ids> <classNames>`
            val classMatch = Regex("""^class\s+(.+)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (classMatch != null) {
                val (targetPart, clsPart) = splitTargetListAndRest(classMatch.groupValues[1])
                    ?: throw IllegalArgumentException("Invalid class syntax in requirementDiagram: $commentFree")
                val targetIds = splitCommaOutsideQuotes(targetPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                val clsNames = splitCommaOutsideQuotes(clsPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                for (id in targetIds) {
                    val list = pendingClasses.getOrPut(id) { mutableListOf() }
                    for (c in clsNames) {
                        if (c !in list) list += c
                    }
                }
                continue
            }

            // `style <ids> <styles>`
            val styleMatch = Regex("""^style\s+(.+)$""", RegexOption.IGNORE_CASE).find(commentFree)
            if (styleMatch != null) {
                val (targetPart, stylePart) = splitTargetListAndRest(styleMatch.groupValues[1])
                    ?: throw IllegalArgumentException("Invalid style syntax in requirementDiagram: $commentFree")
                val targetIds = splitCommaOutsideQuotes(targetPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                val st = parseNodeStyle(stylePart)
                for (id in targetIds) {
                    pendingStyles[id] = pendingStyles[id]?.merge(st) ?: st
                }
                continue
            }

            // Standalone `<ids>:::<classNames>`
            val tripleColonIdx = findTokenOutsideQuotes(commentFree, ":::")
            if (tripleColonIdx > 0 && findTokenOutsideQuotes(commentFree, "->") < 0 && findTokenOutsideQuotes(commentFree, "<-") < 0) {
                val targetPart = commentFree.substring(0, tripleColonIdx).trim()
                val clsPart = commentFree.substring(tripleColonIdx + 3).trim()
                val targetIds = splitCommaOutsideQuotes(targetPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                val clsNames = splitCommaOutsideQuotes(clsPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                if (targetIds.isEmpty() || clsNames.isEmpty()) {
                    throw IllegalArgumentException("Invalid class assignment in requirementDiagram: $commentFree")
                }
                for (id in targetIds) {
                    val list = pendingClasses.getOrPut(id) { mutableListOf() }
                    for (c in clsNames) {
                        if (c !in list) list += c
                    }
                }
                continue
            }

            // Relationships: `<src> - <rel> -> <dst>` or `<dst> <- <rel> - <src>`
            relations += parseRelationLine(commentFree)
        }

        if (inAccDescrBlock) {
            throw IllegalArgumentException("Unclosed accDescr '{' block in requirementDiagram")
        }
        if (activeBlock != null) {
            throw IllegalArgumentException("Unclosed '{' block in requirementDiagram")
        }

        // Apply pending classes and styles to requirements and elements.
        for ((id, clsList) in pendingClasses) {
            requirements[id]?.let { r ->
                for (c in clsList) if (c !in r.classes) r.classes += c
            }
            elements[id]?.let { e ->
                for (c in clsList) if (c !in e.classes) e.classes += c
            }
        }
        for ((id, st) in pendingStyles) {
            requirements[id]?.let { r -> r.inlineStyle = r.inlineStyle?.merge(st) ?: st }
            elements[id]?.let { e -> e.inlineStyle = e.inlineStyle?.merge(st) ?: st }
        }

        fun resolveEffectiveStyle(classes: List<String>, inlineStyle: RequirementNodeStyle?): RequirementNodeStyle? {
            var acc: RequirementNodeStyle? = null
            for (c in classes) {
                val cd = classDefs[c] ?: continue
                acc = acc?.merge(cd) ?: cd
            }
            acc = if (acc != null) acc.merge(inlineStyle) else inlineStyle
            return acc
        }

        val finalReqs = requirements.values.map { r ->
            RequirementItem(
                name = r.name,
                kind = r.kind,
                requirementId = r.requirementId,
                text = r.text,
                risk = r.risk,
                verifyMethod = r.verifyMethod,
                classes = r.classes.toList(),
                style = resolveEffectiveStyle(r.classes, r.inlineStyle),
            )
        }
        val finalElems = elements.values.map { e ->
            ElementItem(
                name = e.name,
                type = e.type,
                docRef = e.docRef,
                classes = e.classes.toList(),
                style = resolveEffectiveStyle(e.classes, e.inlineStyle),
            )
        }

        return RequirementDiagram(
            requirements = finalReqs,
            elements = finalElems,
            relations = relations,
            direction = direction,
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private fun parseBlockBodyEntries(block: ActiveBlock, line: String) {
        val parts = splitSemicolonOutsideQuotes(line)
        for (rawPart in parts) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            val colon = findCharOutsideQuotes(part, ':')
            if (colon <= 0) {
                throw IllegalArgumentException("Expected 'key: value' inside requirement/element block, got: $part")
            }
            val key = part.substring(0, colon).trim().lowercase()
            val rawValue = part.substring(colon + 1).trim()
            val value = normalizeText(rawValue)
            if (value.isEmpty()) {
                throw IllegalArgumentException("Empty value for '$key' in requirementDiagram: $part")
            }
            when (block) {
                is ActiveBlock.ReqBlock -> when (key) {
                    "id" -> block.req.requirementId = value
                    "text" -> block.req.text = value
                    "risk" -> block.req.risk = parseRisk(value)
                    "verifymethod" -> block.req.verifyMethod = parseVerifyMethod(value)
                    else -> throw IllegalArgumentException("Unknown requirement attribute '$key' in: $part")
                }
                is ActiveBlock.ElemBlock -> when (key) {
                    "type" -> block.elem.type = value
                    "docref" -> block.elem.docRef = value
                    else -> throw IllegalArgumentException("Unknown element attribute '$key' in: $part")
                }
            }
        }
    }

    private fun parseRelationLine(line: String): RequirementRelation {
        // Forward: `<src> - <type> -> <dst>`
        val rightArrowIdx = findTokenOutsideQuotes(line, "->")
        if (rightArrowIdx > 0) {
            val leftOfArrow = line.substring(0, rightArrowIdx).trim()
            val dstRaw = line.substring(rightArrowIdx + 2).trim()
            val dashIdx = findLastCharOutsideQuotes(leftOfArrow, '-')
            if (dashIdx <= 0) {
                throw IllegalArgumentException("Invalid requirement relationship syntax: $line")
            }
            val srcName = normalizeText(leftOfArrow.substring(0, dashIdx).trim())
            val relKeyword = leftOfArrow.substring(dashIdx + 1).trim()
            val dstName = normalizeText(dstRaw)
            if (srcName.isEmpty() || dstName.isEmpty()) {
                throw IllegalArgumentException("Empty endpoint in requirement relationship: $line")
            }
            val relType = parseRelationType(relKeyword)
            return RequirementRelation(
                type = relType,
                source = srcName,
                target = dstName,
            )
        }

        // Reverse: `<dst> <- <type> - <src>`
        val leftArrowIdx = findTokenOutsideQuotes(line, "<-")
        if (leftArrowIdx > 0) {
            val dstRaw = line.substring(0, leftArrowIdx).trim()
            val rightOfArrow = line.substring(leftArrowIdx + 2).trim()
            val dashIdx = findCharOutsideQuotes(rightOfArrow, '-')
            if (dashIdx <= 0) {
                throw IllegalArgumentException("Invalid reverse requirement relationship syntax: $line")
            }
            val relKeyword = rightOfArrow.substring(0, dashIdx).trim()
            val srcName = normalizeText(rightOfArrow.substring(dashIdx + 1).trim())
            val dstName = normalizeText(dstRaw)
            if (srcName.isEmpty() || dstName.isEmpty()) {
                throw IllegalArgumentException("Empty endpoint in requirement relationship: $line")
            }
            val relType = parseRelationType(relKeyword)
            return RequirementRelation(
                type = relType,
                source = srcName,
                target = dstName,
            )
        }

        throw IllegalArgumentException("Unexpected statement in requirementDiagram: $line")
    }

    private fun splitNameAndClasses(raw: String): Pair<String, List<String>> {
        val tripleColon = findTokenOutsideQuotes(raw, ":::")
        val namePart = if (tripleColon >= 0) raw.substring(0, tripleColon).trim() else raw.trim()
        val classesPart = if (tripleColon >= 0) raw.substring(tripleColon + 3).trim() else ""
        val name = normalizeText(namePart)
        if (name.isEmpty()) {
            throw IllegalArgumentException("Empty requirement or element name in: $raw")
        }
        val classes = if (classesPart.isNotEmpty()) {
            splitCommaOutsideQuotes(classesPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return name to classes
    }

    private fun parseNodeStyle(spec: String): RequirementNodeStyle {
        var fill: String? = null
        var stroke: String? = null
        var strokeWidth: Float? = null
        var textColor: String? = null
        var fontWeightBold: Boolean? = null

        for (rawEntry in splitStyleEntriesOutsideParens(spec)) {
            val entry = rawEntry.trim().removeSuffix(";").trim()
            if (entry.isEmpty()) continue
            val colon = entry.indexOf(':')
            if (colon <= 0) continue
            val key = entry.substring(0, colon).trim().lowercase()
            val value = entry.substring(colon + 1).trim().removeSuffix(";").trim()
            if (value.isEmpty()) continue
            when (key) {
                "fill", "background", "background-color" -> fill = value
                "stroke", "border-color" -> stroke = value
                "color" -> textColor = value
                "stroke-width" -> {
                    val sw = value.removeSuffix("px").trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid stroke-width '$value' in requirementDiagram")
                    if (!sw.isFinite() || sw < 0f) {
                        throw IllegalArgumentException("stroke-width must be finite and non-negative: $value")
                    }
                    strokeWidth = sw
                }
                "font-weight" -> {
                    val vLower = value.lowercase()
                    if (vLower == "bold" || vLower == "700" || vLower == "800" || vLower == "900") {
                        fontWeightBold = true
                    } else if (vLower == "normal" || vLower == "regular" || vLower == "400" || vLower == "300") {
                        fontWeightBold = false
                    }
                }
            }
        }
        return RequirementNodeStyle(
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            textColor = textColor,
            fontWeightBold = fontWeightBold,
        )
    }

    private fun splitTargetListAndRest(spec: String): Pair<String, String>? {
        val s = spec.trim()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
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
            if (!inQuotes && c.isWhitespace()) {
                // Check if this whitespace is merely padding around a comma in the target list.
                var prevNonSpace = i - 1
                while (prevNonSpace >= 0 && s[prevNonSpace].isWhitespace()) prevNonSpace--
                var nextNonSpace = i
                while (nextNonSpace < s.length && s[nextNonSpace].isWhitespace()) nextNonSpace++
                val commaBefore = prevNonSpace >= 0 && s[prevNonSpace] == ','
                val commaAfter = nextNonSpace < s.length && s[nextNonSpace] == ','
                if (!commaBefore && !commaAfter) {
                    val left = s.substring(0, i).trim()
                    val right = s.substring(nextNonSpace).trim()
                    if (left.isEmpty() || right.isEmpty()) return null
                    return left to right
                }
                i = nextNonSpace
                continue
            }
            i++
        }
        return null
    }

    private fun mergeMultilineQuotedLines(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var inAccBlock = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (!inQuotes) {
                if (inAccBlock) {
                    out += rawLine
                    if (trimmed.contains('}')) inAccBlock = false
                    continue
                }
                if (Regex("""^accDescr\s*\{""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                    out += rawLine
                    if (!trimmed.contains('}')) inAccBlock = true
                    continue
                }
            }

            val clean = stripLineCommentWithQuoteState(rawLine, startInQuotes = inQuotes)
            if (!inQuotes) {
                if (clean.trim().isEmpty()) continue
                current.setLength(0)
                current.append(clean)
            } else {
                current.append(' ').append(clean.trim())
            }

            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                if (c == '\\' && inQuotes && i + 1 < clean.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                }
                i++
            }
            if (!inQuotes) {
                out += current.toString()
                current.setLength(0)
            }
        }
        if (inQuotes) {
            throw IllegalArgumentException("Unclosed double quote in requirementDiagram")
        }
        return out
    }

    private fun stripLineCommentWithQuoteState(line: String, startInQuotes: Boolean): String {
        if (!startInQuotes) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#") || trimmed.startsWith("%")) return ""
        }
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
            if (!inQuotes && i + 1 < line.length && c == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun splitStyleEntriesOutsideParens(spec: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var parenDepth = 0
        for (c in spec) {
            when (c) {
                '(' -> {
                    parenDepth++
                    sb.append(c)
                }
                ')' -> {
                    if (parenDepth > 0) parenDepth--
                    sb.append(c)
                }
                ',', ';' -> if (parenDepth == 0) {
                    out += sb.toString()
                    sb.setLength(0)
                } else {
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    private fun parseRequirementKind(raw: String): RequirementKind =
        when (raw.trim().lowercase()) {
            "requirement" -> RequirementKind.Requirement
            "functionalrequirement" -> RequirementKind.FunctionalRequirement
            "interfacerequirement" -> RequirementKind.InterfaceRequirement
            "performancerequirement" -> RequirementKind.PerformanceRequirement
            "physicalrequirement" -> RequirementKind.PhysicalRequirement
            "designconstraint" -> RequirementKind.DesignConstraint
            else -> throw IllegalArgumentException("Unknown requirement type: $raw")
        }

    private fun parseRisk(raw: String): RequirementRisk =
        when (raw.trim().lowercase()) {
            "low" -> RequirementRisk.Low
            "medium" -> RequirementRisk.Medium
            "high" -> RequirementRisk.High
            else -> throw IllegalArgumentException("Invalid requirement risk '$raw' (expected low, medium, or high)")
        }

    private fun parseVerifyMethod(raw: String): RequirementVerifyMethod =
        when (raw.trim().lowercase()) {
            "analysis" -> RequirementVerifyMethod.Analysis
            "demonstration" -> RequirementVerifyMethod.Demonstration
            "inspection" -> RequirementVerifyMethod.Inspection
            "test" -> RequirementVerifyMethod.Test
            else -> throw IllegalArgumentException(
                "Invalid verifyMethod '$raw' (expected analysis, demonstration, inspection, or test)",
            )
        }

    private fun parseRelationType(raw: String): RequirementRelationType =
        when (raw.trim().lowercase()) {
            "contains" -> RequirementRelationType.Contains
            "copies" -> RequirementRelationType.Copies
            "derives" -> RequirementRelationType.Derives
            "satisfies" -> RequirementRelationType.Satisfies
            "verifies" -> RequirementRelationType.Verifies
            "refines" -> RequirementRelationType.Refines
            "traces" -> RequirementRelationType.Traces
            else -> throw IllegalArgumentException("Invalid requirement relationship type: '$raw'")
        }

    private fun findCharOutsideQuotes(s: String, target: Char): Int {
        var inQuotes = false
        var i = 0
        while (i < s.length) {
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
            if (!inQuotes && c == target) return i
            i++
        }
        return -1
    }

    private fun findLastCharOutsideQuotes(s: String, target: Char): Int {
        var inQuotes = false
        var lastIdx = -1
        var i = 0
        while (i < s.length) {
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
            if (!inQuotes && c == target) lastIdx = i
            i++
        }
        return lastIdx
    }

    private fun findTokenOutsideQuotes(s: String, token: String): Int {
        var inQuotes = false
        var i = 0
        while (i <= s.length - token.length) {
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
            if (!inQuotes && s.startsWith(token, i)) return i
            i++
        }
        return -1
    }

    private fun splitCommaOutsideQuotes(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                sb.append(s[i]).append(s[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inQuotes && c == ',') {
                out += sb.toString()
                sb.setLength(0)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

    private fun splitSemicolonOutsideQuotes(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                sb.append(s[i]).append(s[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }
            if (!inQuotes && c == ';') {
                out += sb.toString()
                sb.setLength(0)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        out += sb.toString()
        return out
    }

    private fun stripLineComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("%")) {
            return ""
        }
        var inQuotes = false
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
            if (!inQuotes && i + 1 < line.length && c == '%' && line[i + 1] == '%') {
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
        val u = unquote(s)
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(u, " ")
        return brReplaced.trim()
    }
}
