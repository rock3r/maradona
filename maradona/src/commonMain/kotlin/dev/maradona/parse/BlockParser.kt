package dev.maradona.parse

import dev.maradona.model.BlockArrowDir
import dev.maradona.model.BlockArrowHead
import dev.maradona.model.BlockConfig
import dev.maradona.model.BlockDiagram
import dev.maradona.model.BlockEdge
import dev.maradona.model.BlockEdgePattern
import dev.maradona.model.BlockItem
import dev.maradona.model.BlockNodeStyle
import dev.maradona.model.BlockShape

/**
 * Hand-written parser for Mermaid `block` / `block-beta` diagrams.
 *
 * Supports:
 * - `block` and `block-beta` headers, YAML frontmatter, `%%{init}%%` configuration (`padding`, `title`),
 *   `title`, `accTitle`, and `accDescr`
 * - `columns <N | auto>`
 * - `space` and `space:<N>`
 * - Nested composite blocks: `block ... end` and `block:<id>[shape][:<spanCols>] ... end`
 * - All 15 block shapes (`[]`, `()`, `([])`, `[[]]`, `[()]`, `(())`, `((()))`, `>]`, `{}`, `{{}}`,
 *   `[/ /]`, `[\ \]`, `[/ \]`, `[\ /]`, and `<["Label"]>(right|left|up|down|x|y)`)
 * - Column spans (`id:N`, `id["Label"]:N`, `block:group1:2`)
 * - Edges (`-->`, `---`, `<-->`, `o--o`, `x--x`, `==>`, `===`, `-.->`, `-.-`, `~~~`, and labeled edges
 *   `-- "label" -->`, `-->|label|`)
 * - `classDef`, `class`, `:::className`, and `style` directives
 */
object BlockParser {

    private const val MAX_TOTAL_BLOCKS = 10_000
    private const val MAX_SPACE_SPAN = 1_000

    private class MutableConfig(
        var padding: Float = 8f,
    ) {
        fun toImmutable(): BlockConfig = BlockConfig(padding = padding)
    }

    private class MutableBlock(
        val id: String,
        var label: String,
        var explicitLabel: Boolean = false,
        var shape: BlockShape = BlockShape.Rect,
        var explicitShape: Boolean = false,
        var widthInColumns: Int = 1,
        var columns: Int = -1,
        val children: MutableList<MutableBlock> = mutableListOf(),
        var arrowDirections: List<BlockArrowDir> = emptyList(),
        val classes: MutableList<String> = mutableListOf("default"),
        var inlineStyle: BlockNodeStyle? = null,
        var colorIndex: Int = 0,
    )

    private data class ParsedNodeToken(
        val id: String,
        val label: String?,
        val shape: BlockShape?,
        val arrowDirections: List<BlockArrowDir>,
        val widthInColumns: Int?,
        val classes: List<String>,
    )

    private data class ParsedEdgeOp(
        val label: String?,
        val pattern: BlockEdgePattern,
        val startHead: BlockArrowHead,
        val endHead: BlockArrowHead,
    )

    fun parse(source: String): BlockDiagram {
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
                    val inBlockConfig = path.isEmpty() ||
                        path == listOf("block") ||
                        path == listOf("config", "block")
                    if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                        if (key == "block" && (path.isEmpty() || path == listOf("config"))) {
                            parseInitDirective(rawVal, config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeLabel(t)
                            }
                        } else if (key == "config" && path.isEmpty()) {
                            parseInitDirective(rawVal, config) { t ->
                                if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeLabel(t)
                            }
                        }
                        continue
                    }
                    val value = unquote(rawVal)
                    if (path.isEmpty() && key == "title" && value.isNotEmpty()) {
                        frontmatterTitle = normalizeLabel(value)
                    } else if (inBlockConfig) {
                        applyConfigEntry(config, key, value) { t ->
                            if (frontmatterTitle == null && t.isNotEmpty()) frontmatterTitle = normalizeLabel(t)
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
                if (t.isNotEmpty()) frontmatterTitle = normalizeLabel(t)
            }
        }

        val bodyText = bodyLines.joinToString("\n")
        val cursor = BodyCursor(bodyText)

        cursor.skipWhitespaceAndComments()
        if (!cursor.consumeHeader()) {
            throw IllegalArgumentException("Expected 'block' or 'block-beta' header")
        }

        var chartTitle: String? = null
        var accTitle: String? = null
        var accDescr: String? = null

        val root = MutableBlock(id = "__synthetic_root__", label = "", shape = BlockShape.Composite, explicitShape = true)
        val containerStack = mutableListOf(root)
        val allBlocksById = LinkedHashMap<String, MutableBlock>()

        val edges = mutableListOf<BlockEdge>()
        val edgeCounts = HashMap<String, Int>()
        val classDefs = LinkedHashMap<String, BlockNodeStyle>()
        val pendingClasses = LinkedHashMap<String, MutableList<String>>()
        val pendingStyles = LinkedHashMap<String, BlockNodeStyle>()

        var autoIdCounter = 0
        var nextCompositeColorIndex = 0
        var totalBlockCount = 0

        fun nextSyntheticId(prefix: String): String {
            while (true) {
                val candidate = "${prefix}_${++autoIdCounter}"
                if (candidate !in allBlocksById) return candidate
            }
        }

        fun ensureBlockRegistered(
            token: ParsedNodeToken,
            container: MutableBlock,
            forceComposite: Boolean = false,
        ): MutableBlock {
            val existing = allBlocksById[token.id]
            if (existing != null) {
                if (forceComposite) {
                    existing.shape = BlockShape.Composite
                    existing.explicitShape = true
                } else if (token.shape != null) {
                    existing.shape = token.shape
                    existing.explicitShape = true
                }
                if (token.label != null) {
                    existing.label = token.label
                    existing.explicitLabel = true
                }
                if (token.arrowDirections.isNotEmpty()) {
                    existing.arrowDirections = token.arrowDirections
                }
                if (token.widthInColumns != null) {
                    existing.widthInColumns = token.widthInColumns
                }
                for (c in token.classes) {
                    if (c !in existing.classes) existing.classes += c
                }
                return existing
            }

            if (++totalBlockCount > MAX_TOTAL_BLOCKS) {
                throw IllegalArgumentException("Block diagram exceeds maximum size of $MAX_TOTAL_BLOCKS blocks")
            }
            val shape = when {
                forceComposite -> BlockShape.Composite
                token.shape != null -> token.shape
                else -> BlockShape.Rect
            }
            val label = when {
                token.label != null -> token.label
                forceComposite -> ""
                else -> token.id
            }
            val block = MutableBlock(
                id = token.id,
                label = label,
                explicitLabel = token.label != null,
                shape = shape,
                explicitShape = forceComposite || token.shape != null,
                widthInColumns = token.widthInColumns ?: 1,
                arrowDirections = token.arrowDirections,
                colorIndex = if (shape == BlockShape.Composite) nextCompositeColorIndex++ else 0,
            )
            for (c in token.classes) {
                if (c !in block.classes) block.classes += c
            }
            allBlocksById[block.id] = block
            container.children += block
            return block
        }

        while (true) {
            cursor.skipWhitespaceAndComments()
            if (cursor.isAtEnd()) break

            val currentContainer = containerStack.last()

            // 1. Accessibility and title directives
            val accTitleVal = cursor.tryConsumeAccTitle()
            if (accTitleVal != null) {
                accTitle = normalizeLabel(accTitleVal)
                continue
            }
            val accDescrVal = cursor.tryConsumeAccDescr()
            if (accDescrVal != null) {
                accDescr = accDescrVal
                continue
            }
            val titleVal = cursor.tryConsumeTitle()
            if (titleVal != null) {
                if (titleVal.isNotEmpty()) chartTitle = normalizeLabel(titleVal)
                continue
            }

            // 2. Styling directives (`classDef`, `class`, `style`, `linkStyle`, `interpolate`)
            val classDefPair = cursor.tryConsumeClassDef()
            if (classDefPair != null) {
                val (targetPart, stylePart) = classDefPair
                val names = splitCommaOutsideQuotes(targetPart)
                    .map { unquote(it).trim() }
                    .filter { it.isNotEmpty() }
                    .map { if (it.equals("DEFAULT", ignoreCase = true)) "default" else it }
                val st = parseNodeStyle(stylePart)
                for (n in names) {
                    classDefs[n] = classDefs[n]?.merge(st) ?: st
                }
                continue
            }

            val classPair = cursor.tryConsumeClassAssignment()
            if (classPair != null) {
                val (targetPart, clsPart) = classPair
                val targetIds = splitCommaOutsideQuotes(targetPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                val clsNames = splitCommaOutsideQuotes(clsPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                for (id in targetIds) {
                    val list = pendingClasses.getOrPut(id) { mutableListOf() }
                    for (c in clsNames) if (c !in list) list += c
                }
                continue
            }

            val stylePair = cursor.tryConsumeStyleStatement()
            if (stylePair != null) {
                val (targetPart, stylePart) = stylePair
                val targetIds = splitCommaOutsideQuotes(targetPart).map { unquote(it).trim() }.filter { it.isNotEmpty() }
                val st = parseNodeStyle(stylePart)
                for (id in targetIds) {
                    pendingStyles[id] = pendingStyles[id]?.merge(st) ?: st
                }
                continue
            }

            if (cursor.tryConsumeNoOpDirective()) {
                continue
            }

            // 3. `columns <N | auto>`
            val colsSetting = cursor.tryConsumeColumns()
            if (colsSetting != null) {
                currentContainer.columns = colsSetting
                continue
            }

            // 4. `space` or `space:<N>`
            val spaceSpan = cursor.tryConsumeSpace()
            if (spaceSpan != null) {
                for (j in 0 until spaceSpan) {
                    if (++totalBlockCount > MAX_TOTAL_BLOCKS) {
                        throw IllegalArgumentException("Block diagram exceeds maximum size of $MAX_TOTAL_BLOCKS blocks")
                    }
                    val spaceId = nextSyntheticId("space_$j")
                    val spaceBlock = MutableBlock(
                        id = spaceId,
                        label = "",
                        shape = BlockShape.Space,
                        explicitShape = true,
                        widthInColumns = 1,
                    )
                    allBlocksById[spaceId] = spaceBlock
                    currentContainer.children += spaceBlock
                }
                continue
            }

            // 5. `end` (closes active composite block)
            if (cursor.tryConsumeEndKeyword()) {
                if (containerStack.size <= 1) {
                    throw IllegalArgumentException("Unexpected 'end' without matching 'block' in block diagram")
                }
                containerStack.removeAt(containerStack.lastIndex)
                continue
            }

            // 6. Anonymous `block[:<widthInColumns>]` composite block (checked before `block:<id>` so `block:2` is a 2-column anonymous composite)
            val anonBlockSpan = cursor.tryConsumeAnonymousBlockStart()
            if (anonBlockSpan != null) {
                val compId = nextSyntheticId("composite")
                val compToken = ParsedNodeToken(
                    id = compId,
                    label = "",
                    shape = BlockShape.Composite,
                    arrowDirections = emptyList(),
                    widthInColumns = anonBlockSpan,
                    classes = emptyList(),
                )
                val compBlock = ensureBlockRegistered(compToken, currentContainer, forceComposite = true)
                containerStack += compBlock
                continue
            }

            // 7. `block:<id>[shapeLabel][:<widthInColumns>]` composite block
            if (cursor.tryConsumeBlockColonPrefix()) {
                val headerToken = cursor.parseNodeToken(allowLeadingWhitespace = false)
                val compBlock = ensureBlockRegistered(headerToken, currentContainer, forceComposite = true)
                containerStack += compBlock
                continue
            }

            // 8. Node statement or Edge chain (`nodeA --> nodeB`)
            val firstToken = cursor.parseNodeToken(allowLeadingWhitespace = false)
            var leftBlock = ensureBlockRegistered(firstToken, currentContainer)

            while (true) {
                val edgeOp = cursor.tryConsumeEdgeOp() ?: break
                val rightToken = cursor.parseNodeToken(allowLeadingWhitespace = true)
                val rightBlock = ensureBlockRegistered(rightToken, currentContainer)
                val baseEdgeKey = "${leftBlock.id}-${rightBlock.id}"
                val count = (edgeCounts[baseEdgeKey] ?: 0) + 1
                edgeCounts[baseEdgeKey] = count
                edges += BlockEdge(
                    id = "$count-$baseEdgeKey",
                    from = leftBlock.id,
                    to = rightBlock.id,
                    label = edgeOp.label,
                    pattern = edgeOp.pattern,
                    startHead = edgeOp.startHead,
                    endHead = edgeOp.endHead,
                )
                leftBlock = rightBlock
            }
        }

        if (containerStack.size > 1) {
            throw IllegalArgumentException("Unclosed 'block' container (missing 'end') in block diagram")
        }

        // Apply pending classes and styles
        for ((id, clsList) in pendingClasses) {
            val b = allBlocksById[id] ?: continue
            for (c in clsList) {
                if (c !in b.classes) b.classes += c
            }
        }
        for ((id, st) in pendingStyles) {
            val b = allBlocksById[id] ?: continue
            b.inlineStyle = b.inlineStyle?.merge(st) ?: st
        }

        fun resolveEffectiveStyle(classes: List<String>, inlineStyle: BlockNodeStyle?): BlockNodeStyle? {
            var acc: BlockNodeStyle? = null
            for (c in classes) {
                val cd = classDefs[c] ?: continue
                acc = acc?.merge(cd) ?: cd
            }
            acc = if (acc != null) acc.merge(inlineStyle) else inlineStyle
            return acc
        }

        fun toImmutable(mb: MutableBlock): BlockItem {
            if (mb.columns > 0) {
                // Validate child spans against configured columns (Mermaid allows spans > columns with a warning;
                // we preserve the declared widthInColumns and clamp during grid slot placement).
            }
            return BlockItem(
                id = mb.id,
                label = mb.label,
                shape = mb.shape,
                widthInColumns = mb.widthInColumns,
                columns = mb.columns,
                children = mb.children.map { toImmutable(it) },
                arrowDirections = mb.arrowDirections,
                classes = mb.classes.toList(),
                style = resolveEffectiveStyle(mb.classes, mb.inlineStyle),
                colorIndex = mb.colorIndex,
            )
        }

        return BlockDiagram(
            rootColumns = root.columns,
            children = root.children.map { toImmutable(it) },
            edges = edges,
            config = config.toImmutable(),
            title = chartTitle ?: frontmatterTitle,
            accTitle = accTitle,
            accDescr = accDescr,
        )
    }

    private class BodyCursor(val text: String) {
        var pos: Int = 0

        fun isAtEnd(): Boolean = pos >= text.length

        fun skipHorizontalWhitespace() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) {
                pos++
            }
        }

        fun skipWhitespaceAndComments() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ';') {
                    pos++
                    continue
                }
                if (text.startsWith("%%", pos)) {
                    val nl = text.indexOf('\n', pos + 2)
                    pos = if (nl < 0) text.length else nl + 1
                    continue
                }
                if (c == '#' && isAtLineStart(pos)) {
                    val nl = text.indexOf('\n', pos + 1)
                    pos = if (nl < 0) text.length else nl + 1
                    continue
                }
                break
            }
        }

        private fun isAtLineStart(index: Int): Boolean {
            var j = index - 1
            while (j >= 0 && (text[j] == ' ' || text[j] == '\t')) j--
            return j < 0 || text[j] == '\n' || text[j] == '\r'
        }

        fun consumeHeader(): Boolean {
            val rem = text.substring(pos)
            val m = Regex("""^block(?:-beta)?\b""", RegexOption.IGNORE_CASE).find(rem) ?: return false
            pos += m.value.length
            return true
        }

        fun tryConsumeAccTitle(): String? {
            if (!isAtStatementBoundary()) return null
            val m = Regex("""^accTitle[ \t]*:[ \t]*""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return null
            pos += m.value.length
            return readToLineEndWithoutComment().trim()
        }

        fun tryConsumeAccDescr(): String? {
            if (!isAtStatementBoundary()) return null
            val rem = text.substring(pos)
            val blockMatch = Regex("""^accDescr\s*\{""", RegexOption.IGNORE_CASE).find(rem)
            if (blockMatch != null) {
                pos += blockMatch.value.length
                val closeIdx = text.indexOf('}', pos)
                if (closeIdx < 0) {
                    throw IllegalArgumentException("Unclosed accDescr '{' block in block diagram")
                }
                val content = text.substring(pos, closeIdx).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                pos = closeIdx + 1
                return content
            }
            val singleMatch = Regex("""^accDescr[ \t]*:[ \t]*""", RegexOption.IGNORE_CASE).find(rem)
                ?: return null
            pos += singleMatch.value.length
            return normalizeLabel(readToLineEndWithoutComment().trim())
        }

        fun tryConsumeTitle(): String? {
            if (!isAtLineStart(pos)) return null
            val m = Regex("""^title(?:[ \t]+|:[ \t]*)""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return null
            val afterPos = pos + m.value.length
            // Ensure `title` is not a node declaration like `title["My Node"]` or `title --> B`.
            val restLine = readToLineEndWithoutCommentAt(afterPos).trim()
            if (restLine.startsWith("[") || restLine.startsWith("(") || restLine.startsWith("{") ||
                restLine.startsWith(">") || restLine.startsWith("<[") ||
                Regex("""^(?:-->|---|<-->|==>|===|-\.->|-\.-|~~~)""").containsMatchIn(restLine)
            ) {
                return null
            }
            pos = afterPos
            return readToLineEndWithoutComment().trim()
        }

        fun tryConsumeClassDef(): Pair<String, String>? {
            val m = Regex("""^classDef[ \t]+""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return null
            pos += m.value.length
            val line = readStyleOrDirectiveLine().trim()
            return splitTargetListAndRest(line)
                ?: throw IllegalArgumentException("Invalid classDef syntax in block diagram: classDef $line")
        }

        fun tryConsumeClassAssignment(): Pair<String, String>? {
            val m = Regex("""^class[ \t]+""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return null
            val afterPos = pos + m.value.length
            val (rawLine, nextPos) = readClassAssignmentLineAt(afterPos)
            val line = rawLine.trim()
            val split = splitTargetListAndRest(line) ?: return null
            pos = nextPos
            return split
        }

        fun tryConsumeStyleStatement(): Pair<String, String>? {
            val m = Regex("""^style[ \t]+""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return null
            val afterPos = pos + m.value.length
            val (rawLine, nextPos) = readStyleOrDirectiveLineAt(afterPos)
            val line = rawLine.trim()
            val split = splitTargetListAndRest(line) ?: return null
            pos = nextPos
            return split
        }

        fun tryConsumeNoOpDirective(): Boolean {
            val m = Regex("""^(?:linkStyle|interpolate)[ \t]+""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return false
            pos += m.value.length
            readToLineEndWithoutComment()
            return true
        }

        fun tryConsumeColumns(): Int? {
            val m = Regex("""^columns[ \t]+(auto|-?\d+)\b""", RegexOption.IGNORE_CASE).find(text.substring(pos))
                ?: return null
            val token = m.groupValues[1]
            pos += m.value.length
            if (token.equals("auto", ignoreCase = true)) {
                return -1
            }
            val n = token.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid columns count '$token' in block diagram")
            if (n <= 0) {
                throw IllegalArgumentException("Block columns must be a positive integer or auto, got $n")
            }
            return n
        }

        fun tryConsumeSpace(): Int? {
            val rem = text.substring(pos)
            val m = Regex("""^space(?::(-?\d+))?(?=$|[\s;])""", RegexOption.IGNORE_CASE).find(rem)
                ?: return null
            val afterIdx = pos + m.value.length
            // Ensure `space` is not the source of an edge (`space --> B`).
            if (peekEdgeOpAt(afterIdx) != null) return null
            val spanStr = m.groupValues[1]
            val span = if (spanStr.isEmpty()) {
                1
            } else {
                val n = spanStr.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid space span '$spanStr' in block diagram")
                if (n <= 0 || n > MAX_SPACE_SPAN) {
                    throw IllegalArgumentException("Block space span must be in 1..$MAX_SPACE_SPAN, got $n")
                }
                n
            }
            pos = afterIdx
            return span
        }

        fun tryConsumeEndKeyword(): Boolean {
            val rem = text.substring(pos)
            val m = Regex("""^end(?=$|[\s;])""", RegexOption.IGNORE_CASE).find(rem) ?: return false
            val afterIdx = pos + m.value.length
            if (peekEdgeOpAt(afterIdx) != null) return false
            pos = afterIdx
            return true
        }

        fun tryConsumeBlockColonPrefix(): Boolean {
            val rem = text.substring(pos)
            if (rem.startsWith("block:", ignoreCase = true)) {
                val afterColon = pos + 6
                if (afterColon < text.length && !text[afterColon].isWhitespace()) {
                    pos = afterColon
                    return true
                }
            }
            return false
        }

        fun tryConsumeAnonymousBlockStart(): Int? {
            val rem = text.substring(pos)
            val m = Regex("""^block(?::(\d+))?(?=$|[\s;])""", RegexOption.IGNORE_CASE).find(rem) ?: return null
            val afterIdx = pos + m.value.length
            if (peekEdgeOpAt(afterIdx) != null) return null
            val spanStr = m.groupValues[1]
            val span = if (spanStr.isEmpty()) {
                1
            } else {
                val n = spanStr.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid block column span '$spanStr'")
                if (n <= 0) throw IllegalArgumentException("Block column span must be >= 1, got $n")
                n
            }
            pos = afterIdx
            return span
        }

        fun parseNodeToken(allowLeadingWhitespace: Boolean): ParsedNodeToken {
            if (allowLeadingWhitespace) {
                skipHorizontalWhitespace()
            }
            if (pos >= text.length) {
                throw IllegalArgumentException("Unexpected end of input while expecting block node")
            }

            val id = readNodeId()
            if (id.isEmpty()) {
                val preview = text.substring(pos, minOf(text.length, pos + 24)).replace('\n', ' ')
                throw IllegalArgumentException("Expected block identifier near '$preview'")
            }

            var shape: BlockShape? = null
            var label: String? = null
            var arrowDirs: List<BlockArrowDir> = emptyList()

            val shapeSpec = tryConsumeShapeAndLabel()
            if (shapeSpec != null) {
                shape = shapeSpec.first
                label = shapeSpec.second
                arrowDirs = shapeSpec.third
            }

            val classes = mutableListOf<String>()
            var widthInColumns: Int? = null

            // Optional `:::className` and `:N` column span (in either order)
            var keepReadingSuffix = true
            while (keepReadingSuffix && pos < text.length) {
                keepReadingSuffix = false
                if (text.startsWith(":::", pos)) {
                    pos += 3
                    val clsRaw = readClassListSuffix()
                    if (clsRaw.isEmpty()) {
                        throw IllegalArgumentException("Expected class name after ':::' on block '$id'")
                    }
                    classes += clsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    keepReadingSuffix = true
                } else if (text[pos] == ':' && (pos + 1 >= text.length || text[pos + 1] != ':')) {
                    val m = Regex("""^:(-?\d+)\b""").find(text.substring(pos))
                    if (m != null) {
                        val n = m.groupValues[1].toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid column span '${m.groupValues[1]}' on block '$id'")
                        if (n <= 0) {
                            throw IllegalArgumentException("Block column span must be >= 1, got $n on block '$id'")
                        }
                        widthInColumns = n
                        pos += m.value.length
                        keepReadingSuffix = true
                    } else {
                        throw IllegalArgumentException("Invalid column span syntax after ':' on block '$id'")
                    }
                }
            }

            return ParsedNodeToken(
                id = id,
                label = label,
                shape = shape,
                arrowDirections = arrowDirs,
                widthInColumns = widthInColumns,
                classes = classes,
            )
        }

        private fun readNodeId(): String {
            if (pos >= text.length) return ""
            if (text[pos] == '"' || text[pos] == '\'') {
                return readQuotedString(text[pos])
            }
            val start = pos
            while (pos < text.length) {
                val c = text[pos]
                if (c.isWhitespace() || c == ';' || c == '(' || c == ')' ||
                    c == '[' || c == ']' || c == '{' || c == '}' ||
                    c == '<' || c == '>' || c == ':' || c == '~'
                ) {
                    break
                }
                // Stop if an edge operator starts at `pos` (`-->`, `---`, `==>`, `===`, `-.->`, `-.-`, `-- `, `== `, `-. `)
                if ((c == '-' || c == '=' || c == 'o' || c == 'x') && peekEdgeOpAt(pos) != null) {
                    if (pos > start) break
                }
                pos++
            }
            return text.substring(start, pos)
        }

        private fun tryConsumeShapeAndLabel(): Triple<BlockShape, String, List<BlockArrowDir>>? {
            if (pos >= text.length) return null
            val rem = text.substring(pos)

            // 1. Block arrow: `<["Label"]>(right, down)`
            if (rem.startsWith("<[")) {
                pos += 2
                val rawLabel = readUntilClosingDelimiter("]>")
                val label = normalizeShapeInnerLabel(rawLabel)
                skipHorizontalWhitespace()
                if (pos >= text.length || text[pos] != '(') {
                    throw IllegalArgumentException("Expected '(direction)' after '<[...]>' in block arrow")
                }
                pos++ // consume '('
                val dirRaw = readUntilClosingDelimiter(")")
                val dirs = parseArrowDirections(dirRaw)
                return Triple(BlockShape.BlockArrow, label, dirs)
            }

            // Ordered from longest opening delimiter to shortest:
            val delimiterPairs = listOf(
                Triple("(((", ")))", BlockShape.DoubleCircle),
                Triple("((", "))", BlockShape.Circle),
                Triple("([", "])", BlockShape.Stadium),
                Triple("[(", ")]", BlockShape.Cylinder),
                Triple("[[", "]]", BlockShape.Subroutine),
                Triple("{{", "}}", BlockShape.Hexagon),
                Triple("{", "}", BlockShape.Diamond),
                Triple("(", ")", BlockShape.Round),
                Triple(">", "]", BlockShape.Asymmetric),
            )
            for ((open, close, shape) in delimiterPairs) {
                if (rem.startsWith(open)) {
                    pos += open.length
                    val rawInner = readUntilClosingDelimiter(close)
                    return Triple(shape, normalizeShapeInnerLabel(rawInner), emptyList())
                }
            }

            // Square or parallelogram/trapezoid variants starting with `[`
            if (rem.startsWith("[/") || rem.startsWith("[\\")) {
                val slashOpen = rem[1]
                pos += 2
                val (rawInner, slashClose) = readUntilSlashBracketClose()
                val shape = when {
                    slashOpen == '/' && slashClose == '/' -> BlockShape.LeanRight
                    slashOpen == '\\' && slashClose == '\\' -> BlockShape.LeanLeft
                    slashOpen == '/' && slashClose == '\\' -> BlockShape.Trapezoid
                    else -> BlockShape.InvTrapezoid
                }
                return Triple(shape, normalizeShapeInnerLabel(rawInner), emptyList())
            }

            if (rem.startsWith("[")) {
                pos++
                val rawInner = readUntilClosingDelimiter("]")
                return Triple(BlockShape.Rect, normalizeShapeInnerLabel(rawInner), emptyList())
            }

            return null
        }

        private fun readUntilClosingDelimiter(close: String): String {
            val start = pos
            var activeQuote: Char? = null
            var inBacktickMd = false
            while (pos < text.length) {
                if (inBacktickMd) {
                    if (text.startsWith("`\"", pos)) {
                        inBacktickMd = false
                        pos += 2
                        continue
                    }
                    pos++
                    continue
                }
                if (activeQuote != null) {
                    if (text[pos] == '\\' && pos + 1 < text.length) {
                        pos += 2
                        continue
                    }
                    if (text[pos] == activeQuote) {
                        activeQuote = null
                    }
                    pos++
                    continue
                }
                if (text.startsWith("\"`", pos)) {
                    inBacktickMd = true
                    pos += 2
                    continue
                }
                if (text[pos] == '"') {
                    activeQuote = '"'
                    pos++
                    continue
                }
                if (text.startsWith(close, pos)) {
                    val inner = text.substring(start, pos)
                    pos += close.length
                    return inner
                }
                pos++
            }
            throw IllegalArgumentException("Unclosed '$close' in block shape declaration")
        }

        private fun readUntilSlashBracketClose(): Pair<String, Char> {
            val start = pos
            var activeQuote: Char? = null
            var inBacktickMd = false
            while (pos < text.length) {
                if (inBacktickMd) {
                    if (text.startsWith("`\"", pos)) {
                        inBacktickMd = false
                        pos += 2
                        continue
                    }
                    pos++
                    continue
                }
                if (activeQuote != null) {
                    if (text[pos] == '\\' && pos + 1 < text.length) {
                        pos += 2
                        continue
                    }
                    if (text[pos] == activeQuote) {
                        activeQuote = null
                    }
                    pos++
                    continue
                }
                if (text.startsWith("\"`", pos)) {
                    inBacktickMd = true
                    pos += 2
                    continue
                }
                if (text[pos] == '"') {
                    activeQuote = '"'
                    pos++
                    continue
                }
                if (pos + 1 < text.length && (text[pos] == '/' || text[pos] == '\\') && text[pos + 1] == ']') {
                    val slashClose = text[pos]
                    val inner = text.substring(start, pos)
                    pos += 2
                    return inner to slashClose
                }
                pos++
            }
            throw IllegalArgumentException("Unclosed '[/...]' or '[\\...]' shape in block diagram")
        }

        private fun readClassListSuffix(): String {
            val start = pos
            while (pos < text.length) {
                val c = text[pos]
                if (c.isWhitespace() || c == ';' || c == ':' || c == '-' || c == '=' || c == '~') break
                pos++
            }
            return text.substring(start, pos)
        }

        fun tryConsumeEdgeOp(): ParsedEdgeOp? {
            val saved = pos
            skipHorizontalWhitespace()
            val op = consumeEdgeOpAtCurrentPos()
            if (op == null) {
                pos = saved
                return null
            }
            return op
        }

        private fun peekEdgeOpAt(index: Int): ParsedEdgeOp? {
            val saved = pos
            pos = index
            skipHorizontalWhitespace()
            val op = consumeEdgeOpAtCurrentPos()
            pos = saved
            return op
        }

        private fun consumeEdgeOpAtCurrentPos(): ParsedEdgeOp? {
            if (pos >= text.length) return null
            val rem = text.substring(pos)

            // 1. Labeled inline links: `-- "label" -->`, `== "label" ==>`, `-. "label" .->`
            val labeledStartMatch = Regex("""^([xo<]?(?:--|==|-\.))[ \t]*""").find(rem)
            if (labeledStartMatch != null) {
                val afterStart = pos + labeledStartMatch.value.length
                if (afterStart < text.length && text[afterStart] == '"') {
                    val startPrefix = labeledStartMatch.groupValues[1]
                    pos = afterStart
                    val labelRaw = if (text.startsWith("\"`", pos)) {
                        pos += 2
                        readUntilClosingDelimiter("`\"")
                    } else {
                        readQuotedString('"')
                    }
                    skipHorizontalWhitespace()
                    val endMatch = Regex("""^(?:--+[-xo>]|==+[=xo>]|-?\.+-[xo>]?)""").find(text.substring(pos))
                        ?: throw IllegalArgumentException("Expected closing arrow after edge label \"$labelRaw\"")
                    val endToken = endMatch.value
                    pos += endToken.length
                    return buildEdgeOp(startPrefix, endToken, normalizeLabel(labelRaw))
                }
            }

            // 2. Direct link tokens (`-->`, `---`, `<-->`, `o--o`, `x--x`, `==>`, `===`, `-.->`, `-.-`, `~~~`)
            //    with optional pipe label `-->|label|`
            val directMatch = Regex(
                """^([xo<]?(?:--+[-xo>]|==+[=xo>]|-?\.+-[xo>]?)|~~~+)""",
            ).find(rem) ?: return null
            val token = directMatch.value
            pos += token.length
            var pipeLabel: String? = null
            skipHorizontalWhitespace()
            if (pos < text.length && text[pos] == '|') {
                pos++
                val closePipe = text.indexOf('|', pos)
                if (closePipe < 0) {
                    throw IllegalArgumentException("Unclosed '|label|' on edge in block diagram")
                }
                pipeLabel = normalizeLabel(text.substring(pos, closePipe))
                pos = closePipe + 1
            }
            return buildEdgeOp(token, token, pipeLabel)
        }

        private fun buildEdgeOp(startStr: String, endStr: String, label: String?): ParsedEdgeOp {
            val s = startStr.trim()
            val e = endStr.trim()
            val pattern = when {
                s.startsWith("~~~") || e.startsWith("~~~") -> BlockEdgePattern.Invisible
                s.contains("==") || e.contains("==") -> BlockEdgePattern.Thick
                s.contains(".") || e.contains(".") -> BlockEdgePattern.Dotted
                else -> BlockEdgePattern.Solid
            }
            val startHead = when (s.firstOrNull()) {
                '<' -> BlockArrowHead.Point
                'o' -> BlockArrowHead.Circle
                'x' -> BlockArrowHead.Cross
                else -> BlockArrowHead.None
            }
            val endHead = when (e.lastOrNull()) {
                '>' -> BlockArrowHead.Point
                'o' -> BlockArrowHead.Circle
                'x' -> BlockArrowHead.Cross
                else -> BlockArrowHead.None
            }
            return ParsedEdgeOp(
                label = label?.takeIf { it.isNotEmpty() },
                pattern = pattern,
                startHead = startHead,
                endHead = endHead,
            )
        }

        private fun readQuotedString(quote: Char): String {
            if (pos >= text.length || text[pos] != quote) return ""
            pos++ // consume opening quote
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == '\\' && pos + 1 < text.length) {
                    val next = text[pos + 1]
                    when (next) {
                        '"', '\'', '\\' -> sb.append(next)
                        'n' -> sb.append('\n')
                        else -> sb.append(next)
                    }
                    pos += 2
                    continue
                }
                if (c == quote) {
                    pos++
                    return sb.toString()
                }
                sb.append(c)
                pos++
            }
            throw IllegalArgumentException("Unclosed quote ($quote) in block diagram")
        }

        private fun isAtStatementBoundary(): Boolean {
            var j = pos - 1
            while (j >= 0 && (text[j] == ' ' || text[j] == '\t')) j--
            return j < 0 || text[j] == '\n' || text[j] == '\r' || text[j] == ';'
        }

        private fun readToLineEndWithoutComment(): String {
            val (s, next) = readToLineEndWithoutCommentPair(pos)
            pos = next
            return s
        }

        private fun readToLineEndWithoutCommentAt(startIdx: Int): String =
            readToLineEndWithoutCommentPair(startIdx).first

        private fun readToLineEndWithoutCommentPair(startIdx: Int): Pair<String, Int> {
            var i = startIdx
            var inQuotes = false
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') break
                if (c == '\\' && inQuotes && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                    i++
                    continue
                }
                if (!inQuotes && text.startsWith("%%", i)) {
                    val lineEnd = text.indexOf('\n', i + 2).let { if (it < 0) text.length else it }
                    return text.substring(startIdx, i) to lineEnd
                }
                i++
            }
            return text.substring(startIdx, i) to i
        }

        private fun readClassAssignmentLineAt(startIdx: Int): Pair<String, Int> {
            var i = startIdx
            var inQuotes = false
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r' || (!inQuotes && c == ';')) break
                if (c == '\\' && inQuotes && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                    i++
                    continue
                }
                if (!inQuotes && text.startsWith("%%", i)) {
                    val lineEnd = text.indexOf('\n', i + 2).let { if (it < 0) text.length else it }
                    return text.substring(startIdx, i) to lineEnd
                }
                i++
            }
            val nextIdx = if (i < text.length && text[i] == ';') i + 1 else i
            return text.substring(startIdx, i) to nextIdx
        }

        private fun readStyleOrDirectiveLine(): String {
            val (s, next) = readStyleOrDirectiveLineAt(pos)
            pos = next
            return s
        }

        private fun readStyleOrDirectiveLineAt(startIdx: Int): Pair<String, Int> {
            var i = startIdx
            var inQuotes = false
            var parenDepth = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') break
                if (c == '\\' && inQuotes && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                    i++
                    continue
                }
                if (!inQuotes) {
                    if (c == '(') parenDepth++
                    else if (c == ')' && parenDepth > 0) parenDepth--
                    if (text.startsWith("%%", i)) {
                        val lineEnd = text.indexOf('\n', i + 2).let { if (it < 0) text.length else it }
                        return text.substring(startIdx, i) to lineEnd
                    }
                    if (c == ';' && parenDepth == 0) {
                        // Check if another CSS property (`key:...`) follows on this line; if not, stop at `;`
                        val afterSemi = text.substring(i + 1)
                        val continuesStyle = Regex(
                            """^[ \t]*(?:fill|background|background-color|stroke|border-color|color|stroke-width|stroke-dasharray|font-weight)[ \t]*:""",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(afterSemi)
                        if (!continuesStyle) {
                            return text.substring(startIdx, i) to (i + 1)
                        }
                    }
                }
                i++
            }
            return text.substring(startIdx, i) to i
        }
    }

    private fun parseArrowDirections(raw: String): List<BlockArrowDir> {
        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            throw IllegalArgumentException("Block arrow requires at least one direction in '(...)'")
        }
        return tokens.map { tok ->
            when (tok.lowercase()) {
                "right" -> BlockArrowDir.Right
                "left" -> BlockArrowDir.Left
                "up" -> BlockArrowDir.Up
                "down" -> BlockArrowDir.Down
                "x" -> BlockArrowDir.X
                "y" -> BlockArrowDir.Y
                else -> throw IllegalArgumentException("Unknown block arrow direction '$tok'")
            }
        }
    }

    private fun parseNodeStyle(spec: String): BlockNodeStyle {
        var fill: String? = null
        var stroke: String? = null
        var strokeWidth: Float? = null
        var textColor: String? = null
        var strokeDashArray: List<Float>? = null
        var fontWeightBold: Boolean? = null

        for (rawEntry in splitStyleEntriesOutsideParens(spec)) {
            val entry = rawEntry.trim().removeSuffix(";").trim()
            if (entry.isEmpty()) continue
            val colon = entry.indexOf(':')
            if (colon <= 0) {
                // Bare property name like `style A fill;` in Mermaid's block tests is a valid no-op.
                continue
            }
            val key = entry.substring(0, colon).trim().lowercase()
            val value = entry.substring(colon + 1).trim().removeSuffix(";").trim()
            if (value.isEmpty()) continue
            when (key) {
                "fill", "background", "background-color" -> fill = value
                "stroke", "border-color" -> stroke = value
                "color" -> textColor = value
                "stroke-width" -> {
                    val sw = value.removeSuffix("px").trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid stroke-width '$value' in block diagram")
                    if (!sw.isFinite() || sw < 0f) {
                        throw IllegalArgumentException("stroke-width must be finite and non-negative: $value")
                    }
                    strokeWidth = sw
                }
                "stroke-dasharray" -> {
                    val nums = value.split(Regex("""[\s,]+"""))
                        .map { it.removeSuffix("px").trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { it.toFloatOrNull() }
                        .filter { it > 0f }
                    if (nums.isNotEmpty()) {
                        strokeDashArray = if (nums.size % 2 == 1) nums + nums else nums
                    }
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
        return BlockNodeStyle(
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            textColor = textColor,
            strokeDashArray = strokeDashArray,
            fontWeightBold = fontWeightBold,
        )
    }

    private fun splitTargetListAndRest(spec: String): Pair<String, String>? {
        val s = spec.trim().removeSuffix(";").trim()
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

    private fun applyConfigEntry(
        config: MutableConfig,
        key: String,
        value: String,
        onTitle: (String) -> Unit,
    ) {
        when (key) {
            "title" -> onTitle(value)
            "padding" -> {
                val v = value.removeSuffix("px").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid block padding '$value'")
                if (!v.isFinite() || v < 0f) {
                    throw IllegalArgumentException("Block padding must be finite and >= 0, got $value")
                }
                config.padding = v
            }
        }
    }

    private fun parseInitDirective(
        initDirective: String,
        config: MutableConfig,
        onTitle: (String) -> Unit,
    ) {
        for (blockMatch in Regex(
            """['"]?block['"]?\s*:\s*\{([^{}]*)\}""",
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
            """['"]?([A-Za-z0-9_]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([A-Za-z0-9_.+#-]+))""",
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
            if (activeQuote == null && c == '"') {
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
                if (source.startsWith("%%", i)) {
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
            if (!inQuotes && line.startsWith("%%", i)) {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 4 && t.startsWith("\"`") && t.endsWith("`\"")) {
            return t.substring(2, t.length - 2)
        }
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
        }
        return t
    }

    private fun normalizeShapeInnerLabel(raw: String): String {
        val unquoted = unquote(raw.trim())
        return decodeEntitiesAndBreaks(unquoted)
    }

    private fun normalizeLabel(raw: String): String =
        decodeEntitiesAndBreaks(unquote(raw.trim())).trim()

    private fun decodeEntitiesAndBreaks(s: String): String {
        val brReplaced = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        return brReplaced
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }
}
