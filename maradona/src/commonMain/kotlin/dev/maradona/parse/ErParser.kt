package dev.maradona.parse

import dev.maradona.model.Direction
import dev.maradona.model.ErAttribute
import dev.maradona.model.ErCardinality
import dev.maradona.model.ErDiagram
import dev.maradona.model.ErEntity
import dev.maradona.model.ErRelationship
import dev.maradona.model.ErSubgraph
import dev.maradona.model.NodeStyle

/**
 * Hand-written parser for Mermaid `erDiagram` sources.
 *
 * Supports:
 * - `erDiagram` header, YAML frontmatter (`title`), `%%{init}%%` directives,
 *   `title`, `accTitle`, `accDescr`, and `direction TB|TD|BT|LR|RL` (top-level and per subgraph)
 * - Entities (`CUSTOMER`, `LINE-ITEM`, `"Quoted Entity"`, `p[Person]`, `a["Customer Account"]`, `:::className`)
 * - Entity attribute blocks (`type name [PK|FK|UK] ["comment"]`, including `string(99)`, `string[]`, `string?`, `PK, FK`)
 * - Symbolic crow's-foot relationships (`||`, `|o`, `o|`, `}|`, `|{`, `}o`, `o{` with `--` or `..`)
 *   and natural-language relationships (`only one`, `zero or more`, `many(0)`, `1`, `0+`, `1+` with `to` / `optionally to`)
 * - Nested `subgraph id [title] ... end` blocks and relationships incident on subgraph IDs
 * - `classDef` (including `classDef default`), `class`, `cssClass`, `style`, and inline `:::className`
 */
object ErParser {

    private class MutableEntity(
        val id: String,
        var label: String,
        var hasExplicitAlias: Boolean = false,
        val attributes: MutableList<ErAttribute> = mutableListOf(),
        var subgraphId: String? = null,
        var explicitlyOwned: Boolean = false,
        var styleOnlyPlaceholder: Boolean = false,
        val styleClasses: MutableList<String> = mutableListOf(),
        var inlineStyle: NodeStyle? = null,
    )

    private class MutableSubgraph(
        val id: String,
        var label: String,
        val parentId: String?,
        var direction: Direction? = null,
        val styleClasses: MutableList<String> = mutableListOf(),
        var inlineStyle: NodeStyle? = null,
    )

    fun parse(source: String): ErDiagram {
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

        val normalizedLines = expandAttributeBraces(bodyLines)

        var headerSeen = false
        var direction = Direction.TB
        var title: String? = frontmatterTitle
        var accTitle: String? = null
        var accDescr: String? = null
        var inAccDescrBlock = false
        val accDescrLines = mutableListOf<String>()

        var currentAttrEntity: MutableEntity? = null
        val subgraphStack = ArrayDeque<String>()
        val subgraphs = LinkedHashMap<String, MutableSubgraph>()
        val entities = LinkedHashMap<String, MutableEntity>()
        val relationships = mutableListOf<ErRelationship>()
        val classDefs = HashMap<String, NodeStyle>()

        fun currentSubgraphId(): String? = subgraphStack.lastOrNull()

        fun ensureEntity(
            rawToken: String,
            subgraphOverride: String? = currentSubgraphId(),
            isDeclaration: Boolean = false,
            styleOnly: Boolean = false,
        ): MutableEntity {
            val (withoutClass, inlineClasses) = splitInlineClasses(rawToken.trim())
            val (id, explicitAlias) = parseEntityIdAndAlias(withoutClass)
            val existing = entities[id]
            if (existing != null) {
                if (explicitAlias != null) {
                    existing.label = explicitAlias
                    existing.hasExplicitAlias = true
                }
                if (
                    !styleOnly &&
                    (existing.styleOnlyPlaceholder || (isDeclaration && !existing.explicitlyOwned)) &&
                    subgraphOverride != null &&
                    existing.subgraphId == null
                ) {
                    existing.subgraphId = subgraphOverride
                }
                if (!styleOnly) {
                    if (existing.styleOnlyPlaceholder && subgraphOverride != null) {
                        existing.explicitlyOwned = true
                    }
                    existing.styleOnlyPlaceholder = false
                }
                if (isDeclaration) {
                    existing.explicitlyOwned = true
                }
                existing.styleClasses += inlineClasses
                return existing
            }
            val safeSg = if (!styleOnly) subgraphOverride else null
            val created = MutableEntity(
                id = id,
                label = explicitAlias ?: id,
                hasExplicitAlias = explicitAlias != null,
                subgraphId = safeSg,
                explicitlyOwned = !styleOnly && (isDeclaration || safeSg != null),
                styleOnlyPlaceholder = styleOnly,
            )
            created.styleClasses += inlineClasses
            entities[id] = created
            return created
        }

        for (rawLine in normalizedLines) {
            val stripped = stripLineComment(rawLine).trim()
            if (inAccDescrBlock) {
                if (stripped == "}") {
                    accDescr = accDescrLines.joinToString("\n").trim()
                    accDescrLines.clear()
                    inAccDescrBlock = false
                } else {
                    accDescrLines += rawLine.trim()
                }
                continue
            }
            if (currentAttrEntity != null) {
                if (stripped == "}") {
                    currentAttrEntity = null
                } else if (stripped.isNotEmpty()) {
                    currentAttrEntity.attributes += parseAttributesFromLine(stripped)
                }
                continue
            }
            if (stripped.isEmpty()) continue

            for (stmt in splitSemicolonsOutsideQuotes(stripped)) {
                val line = stmt.trim()
                if (line.isEmpty()) continue
                val lower = line.lowercase()

                if (!headerSeen) {
                    if (lower.startsWith("erdiagram")) {
                        headerSeen = true
                        continue
                    }
                    headerSeen = true
                }

                if (lower == "end" || line == "}") {
                    subgraphStack.removeLastOrNull()
                    continue
                }

                if (lower.startsWith("subgraph ")) {
                    val spec = line.substring("subgraph ".length).trim().removeSuffix("{").trim()
                    val (sgId, sgLabel) = parseSubgraphHeader(spec)
                    val parentId = currentSubgraphId()?.takeIf { it != sgId }
                    val sg = subgraphs.getOrPut(sgId) {
                        MutableSubgraph(id = sgId, label = sgLabel, parentId = parentId)
                    }
                    if (sgLabel != sgId) sg.label = sgLabel
                    subgraphStack.addLast(sgId)
                    continue
                }

                if (lower.startsWith("direction ")) {
                    val dirToken = line.substring("direction ".length).trim().uppercase()
                    val parsedDir = when (dirToken) {
                        "TB", "TD" -> Direction.TB
                        "BT" -> Direction.BT
                        "LR" -> Direction.LR
                        "RL" -> Direction.RL
                        else -> null
                    }
                    if (parsedDir != null) {
                        val topSg = currentSubgraphId()
                        if (topSg != null) {
                            subgraphs[topSg]?.direction = parsedDir
                        } else {
                            direction = parsedDir
                        }
                    }
                    continue
                }

                if (lower.startsWith("title ") || lower.startsWith("title:")) {
                    title = normalizeText(unquote(line.substring(6).trim()))
                    continue
                }
                if (lower.startsWith("acctitle:")) {
                    accTitle = normalizeText(unquote(line.substring("acctitle:".length).trim()))
                    continue
                }
                if (lower.startsWith("accdescr:")) {
                    accDescr = normalizeText(unquote(line.substring("accdescr:".length).trim()))
                    continue
                }
                if (lower.startsWith("accdescr") && line.endsWith("{")) {
                    inAccDescrBlock = true
                    accDescrLines.clear()
                    continue
                }

                // Styling directives
                if (lower.startsWith("classdef ")) {
                    val rest = line.substring("classDef ".length).trim()
                    val sp = rest.indexOfFirst { it.isWhitespace() }
                    if (sp > 0) {
                        val names = rest.substring(0, sp).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val style = parseNodeStyle(rest.substring(sp + 1).trim())
                        for (n in names) classDefs[n] = style
                    }
                    continue
                }
                if (lower.startsWith("class ")) {
                    val rest = line.substring("class ".length).trim()
                    val parts = rest.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val targets = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val classNames = parts.drop(1).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                        for (t in targets) {
                            val sub = subgraphs[t]
                            if (sub != null) {
                                sub.styleClasses += classNames
                            } else {
                                ensureEntity(t, styleOnly = true).styleClasses += classNames
                            }
                        }
                    }
                    continue
                }
                if (lower.startsWith("cssclass ")) {
                    val rest = line.substring("cssClass ".length).trim()
                    val m = Regex("""^"([^"]+)"\s+(\S+)$""").find(rest)
                    if (m != null) {
                        val targets = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val cls = m.groupValues[2].trim()
                        for (t in targets) {
                            val sub = subgraphs[t]
                            if (sub != null) sub.styleClasses += cls
                            else ensureEntity(t, styleOnly = true).styleClasses += cls
                        }
                    }
                    continue
                }
                if (lower.startsWith("style ")) {
                    val rest = line.substring("style ".length).trim()
                    val sp = rest.indexOfFirst { it.isWhitespace() }
                    if (sp > 0) {
                        val target = rest.substring(0, sp).trim()
                        val style = parseNodeStyle(rest.substring(sp + 1).trim())
                        val sub = subgraphs[target]
                        if (sub != null) {
                            sub.inlineStyle = style
                        } else {
                            ensureEntity(target, styleOnly = true).inlineStyle = style
                        }
                    }
                    continue
                }

                // Entity attribute block opener: `ENTITY {` or `p[Person] {`
                if (line.endsWith("{") && !containsSymbolicRelationOutsideQuotes(line.dropLast(1))) {
                    val entitySpec = line.dropLast(1).trim()
                    if (entitySpec.isNotEmpty()) {
                        currentAttrEntity = ensureEntity(entitySpec, isDeclaration = true)
                        continue
                    }
                }

                // Relationship statement (symbolic crow's-foot or natural-language)
                val rel = tryParseRelationship(line, subgraphs.keys, currentSubgraphId(), ::ensureEntity)
                if (rel != null) {
                    relationships += rel
                    continue
                }

                // Standalone entity declaration (`CUSTOMER`, `"This ❤ Unicode"`, `p[Person]`, `HOUSE:::someclass`)
                ensureEntity(line, isDeclaration = true)
            }
        }

        // Remove any placeholder entity whose ID is actually a subgraph ID.
        for (sgId in subgraphs.keys) {
            val ent = entities[sgId]
            if (ent != null && ent.attributes.isEmpty()) {
                val sg = subgraphs.getValue(sgId)
                sg.styleClasses += ent.styleClasses
                if (ent.inlineStyle != null) sg.inlineStyle = ent.inlineStyle
                entities.remove(sgId)
            }
        }

        val finalEntities = entities.values.map { e ->
            ErEntity(
                id = e.id,
                label = normalizeText(e.label),
                attributes = e.attributes.toList(),
                subgraphId = e.subgraphId,
                style = resolveStyle(e.styleClasses, e.inlineStyle, classDefs),
            )
        }
        val finalSubgraphs = subgraphs.values.map { sg ->
            ErSubgraph(
                id = sg.id,
                label = normalizeText(sg.label),
                parentId = sg.parentId,
                direction = sg.direction,
                style = resolveStyle(sg.styleClasses, sg.inlineStyle, classDefs),
            )
        }

        return ErDiagram(
            direction = direction,
            entities = finalEntities,
            relationships = relationships,
            subgraphs = finalSubgraphs,
            title = title,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private val SYMBOLIC_REL_REGEX = Regex(
        """^(.*?)\s*(\|o|o\||\|\||\}o|o\{|\}\||\|\{)(--|\.\.)(\|o|o\||\|\||\}o|o\{|\}\||\|\{)\s*(.+)$""",
    )

    private val WORD_CARD_PATTERN =
        """(?:\|o|o\||\|\||\}o|o\{|\}\||\|\{|only\s+one|zero\s+or\s+one|one\s+or\s+zero|zero\s+or\s+more|zero\s+or\s+many|one\s+or\s+more|one\s+or\s+many|many\(0\)|many\(1\)|many|one|0\.\.1|0\.\.\*|1\.\.\*|0\+|1\+|1)"""

    private val WORD_REL_REGEX = Regex(
        """^(\S+|"[^"]+")\s*($WORD_CARD_PATTERN)\s*(optionally\s+to|to|--|\.\.)\s*($WORD_CARD_PATTERN)\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun containsSymbolicRelationOutsideQuotes(s: String): Boolean {
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
            if (!inQuotes && (s.startsWith("--", i) || s.startsWith("..", i))) {
                if (i >= 2) {
                    val left = s.substring(i - 2, i)
                    if (left in setOf("||", "|o", "o|", "}|", "|{", "}o", "o{")) return true
                }
            }
            i++
        }
        return false
    }

    private fun tryParseRelationship(
        line: String,
        subgraphIds: Set<String>,
        currentSgId: String?,
        ensureEntity: (String, String?, Boolean, Boolean) -> MutableEntity,
    ): ErRelationship? {
        // 1. Symbolic crow's-foot syntax (`A ||--o{ B : places`)
        val symMatch = matchSymbolicRelationshipOutsideQuotes(line)
        if (symMatch != null) {
            val (leftRaw, leftCardStr, lineStr, rightCardStr, rightAndLabel) = symMatch
            val colonAt = findColonOutsideQuotes(rightAndLabel)
            val rightRaw = if (colonAt >= 0) rightAndLabel.substring(0, colonAt).trim() else rightAndLabel.trim()
            val labelRaw = if (colonAt >= 0) {
                normalizeText(unquote(rightAndLabel.substring(colonAt + 1).trim())).takeIf { it.isNotEmpty() }
            } else null

            val localSelf = currentSgId != null &&
                parseEntityIdAndAlias(splitInlineClasses(leftRaw).first).first ==
                parseEntityIdAndAlias(splitInlineClasses(rightRaw).first).first
            val fromId = resolveRelEndpoint(leftRaw, subgraphIds, currentSgId, localSelf, ensureEntity)
            val toId = resolveRelEndpoint(rightRaw, subgraphIds, currentSgId, localSelf, ensureEntity)
            return ErRelationship(
                from = fromId,
                to = toId,
                fromCardinality = parseSymbolicCardinality(leftCardStr),
                toCardinality = parseSymbolicCardinality(rightCardStr),
                identifying = lineStr == "--",
                label = labelRaw,
            )
        }

        // 2. Natural-language / mixed syntax (`CAR 1 to zero or more NAMED-DRIVER : allows`, `A }o to one B:uses`)
        val colonAt = findColonOutsideQuotes(line)
        val relBody = if (colonAt >= 0) line.substring(0, colonAt).trim() else line.trim()
        val labelRaw = if (colonAt >= 0) {
            normalizeText(unquote(line.substring(colonAt + 1).trim())).takeIf { it.isNotEmpty() }
        } else null
        val wordMatch = WORD_REL_REGEX.find(relBody)
        if (wordMatch != null) {
            val leftRaw = wordMatch.groupValues[1].trim()
            val leftCard = parseWordCardinality(wordMatch.groupValues[2].trim()) ?: return null
            val connStr = wordMatch.groupValues[3].trim().lowercase()
            val rightCard = parseWordCardinality(wordMatch.groupValues[4].trim()) ?: return null
            val rightRaw = wordMatch.groupValues[5].trim()
            if (rightRaw.isEmpty()) return null

            val localSelf = currentSgId != null &&
                parseEntityIdAndAlias(splitInlineClasses(leftRaw).first).first ==
                parseEntityIdAndAlias(splitInlineClasses(rightRaw).first).first
            val fromId = resolveRelEndpoint(leftRaw, subgraphIds, currentSgId, localSelf, ensureEntity)
            val toId = resolveRelEndpoint(rightRaw, subgraphIds, currentSgId, localSelf, ensureEntity)
            val identifying = connStr == "to" || connStr == "--"
            return ErRelationship(
                from = fromId,
                to = toId,
                fromCardinality = leftCard,
                toCardinality = rightCard,
                identifying = identifying,
                label = labelRaw,
            )
        }

        return null
    }

    private data class SymbolicRelParts(
        val leftEndpoint: String,
        val leftCard: String,
        val connector: String,
        val rightCard: String,
        val rightRest: String,
    )

    private fun matchSymbolicRelationshipOutsideQuotes(line: String): SymbolicRelParts? {
        val cards = setOf("||", "|o", "o|", "}|", "|{", "}o", "o{")
        var inQuotes = false
        var inBrackets = 0
        var i = 0
        while (i <= line.length - 6) {
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
            if (!inQuotes) {
                if (c == '[') inBrackets++
                else if (c == ']' && inBrackets > 0) inBrackets--
                if (inBrackets == 0) {
                    val leftCard = line.substring(i, i + 2)
                    val conn = line.substring(i + 2, i + 4)
                    val rightCard = line.substring(i + 4, i + 6)
                    if (leftCard in cards && (conn == "--" || conn == "..") && rightCard in cards) {
                        val leftEndpoint = line.substring(0, i).trim()
                        val rightRest = line.substring(i + 6).trim()
                        if (leftEndpoint.isNotEmpty() && rightRest.isNotEmpty()) {
                            return SymbolicRelParts(leftEndpoint, leftCard, conn, rightCard, rightRest)
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun resolveRelEndpoint(
        rawToken: String,
        subgraphIds: Set<String>,
        currentSgId: String?,
        localOwnership: Boolean,
        ensureEntity: (String, String?, Boolean, Boolean) -> MutableEntity,
    ): String {
        val (withoutClass, _) = splitInlineClasses(rawToken.trim())
        val (cleanId, _) = parseEntityIdAndAlias(withoutClass)
        if (cleanId in subgraphIds) return cleanId
        return ensureEntity(rawToken, currentSgId, localOwnership, false).id
    }

    private fun parseSymbolicCardinality(token: String): ErCardinality = when (token) {
        "||" -> ErCardinality.ExactlyOne
        "|o", "o|" -> ErCardinality.ZeroOrOne
        "}|", "|{" -> ErCardinality.OneOrMore
        "}o", "o{" -> ErCardinality.ZeroOrMore
        else -> ErCardinality.ExactlyOne
    }

    private fun parseWordCardinality(token: String): ErCardinality? =
        when (token.lowercase().replace(Regex("\\s+"), " ")) {
            "||", "only one", "one", "1" -> ErCardinality.ExactlyOne
            "|o", "o|", "zero or one", "one or zero", "0..1" -> ErCardinality.ZeroOrOne
            "}|", "|{", "one or more", "one or many", "many(1)", "1+", "1..*", "many" -> ErCardinality.OneOrMore
            "}o", "o{", "zero or more", "zero or many", "many(0)", "0+", "0..*" -> ErCardinality.ZeroOrMore
            else -> null
        }

    private fun parseAttributesFromLine(line: String): List<ErAttribute> {
        val tokens = tokenizeAttributeLine(line)
        val out = mutableListOf<ErAttribute>()
        var i = 0
        while (i + 1 < tokens.size) {
            val attrType = tokens[i]
            val attrName = tokens[i + 1]
            if (attrType.startsWith('"') || attrName.startsWith('"')) {
                i++
                continue
            }
            i += 2
            val keys = mutableListOf<String>()
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.startsWith('"')) break
                val parts = t.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty() && parts.all { it in setOf("PK", "FK", "UK") }) {
                    keys += parts
                    i++
                } else {
                    break
                }
            }
            var comment: String? = null
            if (i < tokens.size && tokens[i].startsWith('"')) {
                comment = unquote(tokens[i])
                i++
            }
            out += ErAttribute(
                type = attrType,
                name = attrName,
                keys = keys,
                comment = comment,
            )
        }
        return out
    }

    private fun tokenizeAttributeLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                sb.append(c).append(line[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                if (!inQuotes && sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                inQuotes = !inQuotes
                sb.append(c)
                if (!inQuotes) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                i++
                continue
            }
            if (!inQuotes && c.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    // Merge trailing comma with next key token if user wrote `PK, FK`
                    if (sb.endsWith(',')) {
                        i++
                        continue
                    }
                    out += sb.toString()
                    sb.setLength(0)
                }
                i++
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun parseEntityIdAndAlias(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()
        // Check `id[Label]` or `id["Label"]`
        val bracketStart = trimmed.indexOf('[')
        if (bracketStart > 0 && trimmed.endsWith(']')) {
            val id = unquote(trimmed.substring(0, bracketStart).trim())
            val alias = unquote(trimmed.substring(bracketStart + 1, trimmed.length - 1).trim())
            return id to alias
        }
        val unq = unquote(trimmed)
        return unq to null
    }

    private fun parseSubgraphHeader(spec: String): Pair<String, String> {
        val trimmed = spec.trim()
        val bracketStart = trimmed.indexOf('[')
        if (bracketStart > 0 && trimmed.endsWith(']')) {
            val id = unquote(trimmed.substring(0, bracketStart).trim())
            val label = unquote(trimmed.substring(bracketStart + 1, trimmed.length - 1).trim())
            return id to label
        }
        val unq = unquote(trimmed)
        return unq to unq
    }

    private fun expandAttributeBraces(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var inAccDescrBlock = false
        for (raw in lines) {
            val clean = stripLineComment(raw)
            val trimmed = clean.trim()
            val lower = trimmed.lowercase()
            if (inAccDescrBlock) {
                out += clean
                if (trimmed == "}") inAccDescrBlock = false
                continue
            }
            if (lower.startsWith("accdescr")) {
                out += clean
                if (trimmed.endsWith("{")) inAccDescrBlock = true
                continue
            }
            // Do not split `{` that belongs to a crow's-foot operator (`o{` or `|{`) or after `:`!
            if (containsSymbolicRelationOutsideQuotes(trimmed)) {
                out += clean
                continue
            }
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                if (c == '\\' && inQuotes && i + 1 < clean.length) {
                    sb.append(c).append(clean[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                    sb.append(c)
                    i++
                    continue
                }
                if (!inQuotes && c == ':') {
                    if (clean.startsWith(":::", i)) {
                        sb.append(":::")
                        i += 3
                        continue
                    }
                    sb.append(clean.substring(i))
                    break
                }
                if (!inQuotes && c == '{') {
                    val isCrowsFoot = i > 0 && (
                        clean[i - 1] == '|' ||
                            (clean[i - 1] == 'o' && (i == 1 || !clean[i - 2].isLetterOrDigit()))
                        )
                    if (isCrowsFoot) {
                        sb.append(c)
                        i++
                        continue
                    }
                    sb.append(" {")
                    out += sb.toString()
                    sb.setLength(0)
                    i++
                    continue
                }
                if (!inQuotes && c == '}') {
                    val isCrowsFoot = i + 1 < clean.length && (
                        clean[i + 1] == '|' ||
                            (clean[i + 1] == 'o' && (i + 2 == clean.length || !clean[i + 2].isLetterOrDigit()))
                        )
                    if (isCrowsFoot) {
                        sb.append(c)
                        i++
                        continue
                    }
                    if (sb.isNotBlank()) {
                        out += sb.toString()
                        sb.setLength(0)
                    }
                    out += "}"
                    i++
                    continue
                }
                sb.append(c)
                i++
            }
            if (sb.isNotBlank()) {
                out += sb.toString()
            }
        }
        return out
    }

    private fun findColonOutsideQuotes(s: String): Int {
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
            if (!inQuotes && c == ':') {
                if (s.startsWith(":::", i)) {
                    i += 3
                    continue
                }
                return i
            }
            i++
        }
        return -1
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
                val classes = raw.substring(i + 3).split(":::", ",").map { it.trim() }.filter { it.isNotEmpty() }
                return base to classes
            }
            i++
        }
        return raw to emptyList()
    }

    private fun stripLineComment(line: String): String {
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
            if (!inQuotes && i + 1 < line.length && line[i] == '%' && line[i + 1] == '%') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun splitSemicolonsOutsideQuotes(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inQuotes && i + 1 < line.length) {
                sb.append(c).append(line[i + 1])
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

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return t
    }

    private fun normalizeText(s: String): String =
        s.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")

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
