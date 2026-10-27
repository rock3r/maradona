package dev.maradona.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Host-provided resolver that maps an inline image URL/source (from `<img src="..."/>`
 * or Markdown `![alt](url)`) to a Compose [Painter].
 */
fun interface MaradonaImageResolver {
    @Composable
    fun resolve(url: String): Painter?
}

/**
 * CompositionLocal allowing embedding applications to provide a [MaradonaImageResolver]
 * for inline `<img src="..."/>` and `![alt](url)` elements inside diagram labels.
 */
val LocalMaradonaImageResolver = staticCompositionLocalOf<MaradonaImageResolver?> { null }

/**
 * Parsed inline image reference extracted from `<img .../>` or `![alt](url)` inside a label.
 */
@Immutable
data class InlineImageSpec(
    val inlineId: String,
    val url: String,
    val alt: String,
    val widthPx: Float = 24f,
    val heightPx: Float = 24f,
)

private val IMG_TAG_REGEX = Regex("""(?i)<img\b((?:[^>"']|"[^"]*"|'[^']*')*)/?>""")
private val MD_IMAGE_REGEX = Regex("""!\[([^\]]*)]\(((?:[^()\s]|\([^()\s]*\))+)\)""")

private val INLINE_ICON_CALL_REGEX = Regex("""(?i)::icon\(([^)]+)\)""")
private val INLINE_ICON_SHORT_REGEX = Regex("""(?i)\b(?:fa[bsrl]?|mdi):([a-z0-9_-]+)""")

/**
 * Resolves a Mermaid icon specification (such as `fa fa-check`, `fa:fa-database`,
 * `fab:fa-github`, `mdi:cloud`, or `server`) to a crisp Unicode symbol.
 */
fun resolveMermaidIconGlyph(spec: String?): String {
    if (spec.isNullOrBlank()) return ""
    val tokens = spec.trim().lowercase()
        .removePrefix("::icon(").removeSuffix(")")
        .split(Regex("""[\s:]+"""))
        .map { it.removePrefix("fa-").removePrefix("mdi-") }
        .filter { it.isNotEmpty() && it !in setOf("fa", "fas", "far", "fab", "fal", "fad", "mdi") }
    val key = tokens.lastOrNull() ?: return "◈"
    return ICON_GLYPHS[key] ?: when {
        key.contains("check") || key.contains("ok") || key.contains("success") -> "✓"
        key.contains("warn") || key.contains("alert") || key.contains("exclamation") -> "⚠"
        key.contains("err") || key.contains("times") || key.contains("close") || key.contains("ban") -> "✕"
        key.contains("bug") -> "🐞"
        key.contains("star") || key.contains("fav") -> "★"
        key.contains("book") || key.contains("doc") -> "📖"
        key.contains("user") || key.contains("person") || key.contains("actor") -> "👤"
        key.contains("users") || key.contains("group") || key.contains("team") -> "👥"
        key.contains("db") || key.contains("database") || key.contains("sql") -> "🗄"
        key.contains("cloud") -> "☁"
        key.contains("server") || key.contains("host") || key.contains("cpu") -> "🖥"
        key.contains("lock") || key.contains("sec") || key.contains("key") -> "🔒"
        key.contains("folder") || key.contains("dir") -> "📁"
        key.contains("file") || key.contains("page") -> "📄"
        key.contains("code") || key.contains("terminal") -> "⌨"
        key.contains("cog") || key.contains("gear") || key.contains("setting") -> "⚙"
        key.contains("search") || key.contains("find") -> "🔍"
        key.contains("bell") || key.contains("notif") -> "🔔"
        key.contains("mail") || key.contains("envelope") -> "✉"
        key.contains("link") || key.contains("chain") -> "🔗"
        key.contains("chart") || key.contains("graph") || key.contains("analytics") -> "📊"
        key.contains("rocket") || key.contains("launch") || key.contains("deploy") -> "🚀"
        key.contains("bolt") || key.contains("flash") || key.contains("zap") -> "⚡"
        key.contains("box") || key.contains("cube") || key.contains("package") -> "📦"
        key.contains("globe") || key.contains("web") || key.contains("internet") -> "🌐"
        key.contains("clock") || key.contains("time") || key.contains("wait") -> "⏱"
        key.contains("heart") -> "♥"
        key.contains("comment") || key.contains("chat") || key.contains("message") -> "💬"
        key.contains("flag") -> "⚑"
        key.contains("tag") -> "🏷"
        else -> "◈"
    }
}

private val ICON_GLYPHS = mapOf(
    "check" to "✓",
    "check-circle" to "✓",
    "circle-check" to "✓",
    "times" to "✕",
    "xmark" to "✕",
    "ban" to "⊘",
    "spinner" to "↻",
    "sync" to "↻",
    "refresh" to "↻",
    "star" to "★",
    "heart" to "♥",
    "flag" to "⚑",
    "book" to "📖",
    "bookmark" to "🔖",
    "bug" to "🐞",
    "bolt" to "⚡",
    "fire" to "🔥",
    "lock" to "🔒",
    "unlock" to "🔓",
    "key" to "🔑",
    "shield" to "🛡",
    "user" to "👤",
    "users" to "👥",
    "database" to "🗄",
    "server" to "🖥",
    "cloud" to "☁",
    "globe" to "🌐",
    "folder" to "📁",
    "file" to "📄",
    "code" to "⌨",
    "terminal" to "⌨",
    "cog" to "⚙",
    "cogs" to "⚙",
    "gear" to "⚙",
    "wrench" to "🔧",
    "search" to "🔍",
    "bell" to "🔔",
    "envelope" to "✉",
    "link" to "🔗",
    " Rocket" to "🚀",
    "rocket" to "🚀",
    "box" to "📦",
    "cube" to "📦",
    "cubes" to "📦",
    "clock" to "⏱",
    "calendar" to "📅",
    "comment" to "💬",
    "comments" to "💬",
    "info" to "ℹ",
    "info-circle" to "ℹ",
    "question" to "?",
    "question-circle" to "?",
    "exclamation" to "⚠",
    "exclamation-triangle" to "⚠",
    "warning" to "⚠",
    "home" to "⌂",
    "github" to "🐙",
    "gitlab" to "🦊",
    "docker" to "🐳",
    "aws" to "☁",
    "android" to "🤖",
    "apple" to "🍎",
    "linux" to "🐧",
)

/**
 * Extracts inline `<img .../>` and `![alt](url)` specifications from [label] (ignoring code spans).
 */
fun extractInlineImages(label: String): List<InlineImageSpec> =
    prepareLabelWithImagesAndIcons(label).second

private fun decodeAttributeEntities(s: String): String =
    s.replace("&\u200Blt;", "<")
        .replace("&quot;", "\"").replace("#quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("#lt;", "<")
        .replace("&gt;", ">").replace("#gt;", ">")
        .replace("&amp;", "&").replace("#amp;", "&")
        .replace(Regex("&?#(\\d{1,5});")) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0xFFFF) code.toChar().toString() else m.value
        }

private fun parseImgTag(match: MatchResult, inlineId: String): InlineImageSpec? {
    val attrMap = parseHtmlAttributes(match.groupValues[1])
    val url = attrMap["src"]?.let(::decodeAttributeEntities)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val alt = attrMap["alt"]?.let(::decodeAttributeEntities)?.trim()?.takeIf { it.isNotEmpty() }
        ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "image" }
    val w = attrMap["width"]?.trim()?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: 24f
    val h = attrMap["height"]?.trim()?.removeSuffix("px")?.trim()?.toFloatOrNull() ?: w
    return InlineImageSpec(
        inlineId = inlineId,
        url = url,
        alt = alt,
        widthPx = w.coerceIn(10f, 160f),
        heightPx = h.coerceIn(10f, 160f),
    )
}

private fun parseHtmlAttributes(rawAttrs: String): Map<String, String> {
    val attrs = rawAttrs.trimEnd().removeSuffix("/")
    val out = LinkedHashMap<String, String>()
    var i = 0
    val n = attrs.length
    while (i < n) {
        while (i < n && (attrs[i].isWhitespace() || attrs[i] == '/')) i++
        if (i >= n) break
        val nameStart = i
        while (i < n && (attrs[i].isLetterOrDigit() || attrs[i] == '-' || attrs[i] == '_' || attrs[i] == ':')) i++
        if (i == nameStart) {
            i++
            continue
        }
        val name = attrs.substring(nameStart, i).lowercase()
        while (i < n && attrs[i].isWhitespace()) i++
        if (i >= n || attrs[i] != '=') {
            out[name] = ""
            continue
        }
        i++ // skip '='
        while (i < n && attrs[i].isWhitespace()) i++
        if (i >= n) break
        val value = if (attrs[i] == '"' || attrs[i] == '\'') {
            val quote = attrs[i++]
            val valStart = i
            while (i < n && attrs[i] != quote) i++
            val v = attrs.substring(valStart, i)
            if (i < n && attrs[i] == quote) i++
            v
        } else {
            val valStart = i
            while (i < n && !attrs[i].isWhitespace() && attrs[i] != '>') i++
            attrs.substring(valStart, i)
        }
        out[name] = value
    }
    return out
}

private val FORMATTING_OPEN_TAG_ATTRS_REGEX =
    Regex("""(?i)<(u|ins|s|del|strike|sub|sup)\s(?:[^>"']|"[^"]*"|'[^']*')*>""")
private val FORMATTING_CLOSE_TAG_WS_REGEX =
    Regex("""(?i)</(u|ins|s|del|strike|sub|sup)\s+>""")
private val HTML_BR_REGEX = Regex("""(?i)<br\s*/?>""")
private val HTML_BOLD_OPEN_REGEX = Regex("""(?i)<(?:b|strong)(?:\s(?:[^>"']|"[^"]*"|'[^']*')*)?>""")
private val HTML_BOLD_CLOSE_REGEX = Regex("""(?i)</(?:b|strong)\s*>""")
private val HTML_ITALIC_OPEN_REGEX = Regex("""(?i)<(?:i|em)(?:\s(?:[^>"']|"[^"]*"|'[^']*')*)?>""")
private val HTML_ITALIC_CLOSE_REGEX = Regex("""(?i)</(?:i|em)\s*>""")

private fun prepareLabelWithImagesAndIcons(label: String): Pair<String, List<InlineImageSpec>> {
    val images = ArrayList<InlineImageSpec>()
    val prepared = transformOutsideCodeSpans(label) { segment ->
        var s = segment
            .replace(HTML_BR_REGEX, "\n")
            .replace(HTML_BOLD_OPEN_REGEX, "**")
            .replace(HTML_BOLD_CLOSE_REGEX, "**")
            .replace(HTML_ITALIC_OPEN_REGEX, "*")
            .replace(HTML_ITALIC_CLOSE_REGEX, "*")
            .replace(FORMATTING_OPEN_TAG_ATTRS_REGEX) { "<${it.groupValues[1].lowercase()}>" }
            .replace(FORMATTING_CLOSE_TAG_WS_REGEX) { "</${it.groupValues[1].lowercase()}>" }
        if ("<img" in s.lowercase()) {
            s = IMG_TAG_REGEX.replace(s) { m ->
                val spec = parseImgTag(m, "img_${images.size}")
                if (spec != null) {
                    images += spec
                    "\u0000IMG:${images.lastIndex}\u0000"
                } else {
                    ""
                }
            }
        }
        if ("![" in s) {
            s = MD_IMAGE_REGEX.replace(s) { m ->
                val alt = m.groupValues[1].trim().ifEmpty { "image" }
                val url = m.groupValues[2].trim()
                if (url.isNotEmpty()) {
                    images += InlineImageSpec(
                        inlineId = "img_${images.size}",
                        url = url,
                        alt = alt,
                    )
                    "\u0000IMG:${images.lastIndex}\u0000"
                } else {
                    m.value
                }
            }
        }
        expandIconTokens(s)
    }
    return prepared to images
}

/**
 * Builds the [InlineTextContent] map for [images] using [resolver] when available.
 */
@Composable
fun rememberInlineImageContent(
    images: List<InlineImageSpec>,
    resolver: MaradonaImageResolver? = LocalMaradonaImageResolver.current,
): Map<String, InlineTextContent> {
    if (images.isEmpty() || resolver == null) return emptyMap()
    val map = LinkedHashMap<String, InlineTextContent>(images.size)
    for (img in images) {
        val painter = resolver.resolve(img.url) ?: continue
        map[img.inlineId] = InlineTextContent(
            placeholder = Placeholder(
                width = img.widthPx.sp,
                height = img.heightPx.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
        ) {
            Image(
                painter = painter,
                contentDescription = img.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    return map
}

/**
 * Strips Markdown, HTML, icon, and image markup from [label] to produce a clean plain-text
 * string suitable for screen-reader descriptions and character-count heuristics, while
 * preserving literal contents inside inline code spans.
 */
fun plainTextLabel(label: String): String =
    annotatedLabel(label).text.trim()

private fun expandIconTokens(input: String): String {
    if ("::icon(" !in input.lowercase() && "fa:" !in input.lowercase() && "fas:" !in input.lowercase() &&
        "far:" !in input.lowercase() && "fab:" !in input.lowercase() && "mdi:" !in input.lowercase()
    ) {
        return input
    }
    var out = INLINE_ICON_CALL_REGEX.replace(input) { m ->
        resolveMermaidIconGlyph(m.groupValues[1])
    }
    out = INLINE_ICON_SHORT_REGEX.replace(out) { m ->
        resolveMermaidIconGlyph(m.value)
    }
    return out
}

/**
 * Turns Markdown and HTML inline formatting into [AnnotatedString] spans:
 * - `**bold**` and `__bold__` (`FontWeight.Bold`)
 * - `*italic*` (`FontStyle.Italic`)
 * - `~~strikethrough~~` and `<s>...</s>` / `<del>...</del>` (`TextDecoration.LineThrough`)
 * - `<u>...</u>` and `<ins>...</ins>` (`TextDecoration.Underline`)
 * - `` `code` `` and `<code>...</code>` (`monospaceFontFamily`)
 * - `<sub>...</sub>` (`BaselineShift.Subscript`, `0.8.em`)
 * - `<sup>...</sup>` (`BaselineShift.Superscript`, `0.8.em`)
 * - `::icon(...)` and `fa:fa-...` / `mdi:...` icon tokens
 * - `<img src="..." alt="..."/>` and `![alt](url)` inline images
 */
fun annotatedLabel(
    label: String,
    monospaceFontFamily: FontFamily = FontFamily.Monospace,
    inlineImageIds: Set<String> = emptySet(),
): AnnotatedString = buildAnnotatedString {
    val (prepared, images) = prepareLabelWithImagesAndIcons(label)

    var i = 0
    var boldStar = false
    var boldUnderscore = false
    var italic = false
    var underline = false
    var strike = false
    var codeDelimiter: String? = null // "`" or "</code>"
    var subscript = false
    var superscript = false
    val plain = StringBuilder()

    fun currentStyle(): SpanStyle? {
        val bold = boldStar || boldUnderscore
        val code = codeDelimiter != null
        if (!bold && !italic && !underline && !strike && !code && !subscript && !superscript) {
            return null
        }
        val decorations = buildList {
            if (underline) add(TextDecoration.Underline)
            if (strike) add(TextDecoration.LineThrough)
        }
        val textDec = when (decorations.size) {
            0 -> null
            1 -> decorations[0]
            else -> TextDecoration.combine(decorations)
        }
        val baseline = when {
            subscript -> BaselineShift.Subscript
            superscript -> BaselineShift.Superscript
            else -> null
        }
        val fontSize = if (subscript || superscript) 0.8.em else TextUnit.Unspecified
        return SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            fontFamily = if (code) monospaceFontFamily else null,
            textDecoration = textDec,
            baselineShift = baseline,
            fontSize = fontSize,
        )
    }

    fun flush() {
        if (plain.isEmpty()) return
        val text = plain.toString().replace("&\u200Blt;", "<")
        plain.clear()
        val st = currentStyle()
        if (st != null) {
            pushStyle(st)
            append(text)
            pop()
        } else {
            append(text)
        }
    }

    while (i < prepared.length) {
        if (prepared.startsWith("\u0000IMG:", i)) {
            val endMarker = prepared.indexOf('\u0000', i + 5)
            if (endMarker > i + 5) {
                flush()
                val imgIdx = prepared.substring(i + 5, endMarker).toIntOrNull()
                val spec = imgIdx?.let { images.getOrNull(it) }
                if (spec != null) {
                    if (spec.inlineId in inlineImageIds) {
                        appendInlineContent(spec.inlineId, "[${spec.alt}]")
                    } else {
                        append("[🖼 ${spec.alt}]")
                    }
                }
                i = endMarker + 1
                continue
            }
        }
        val activeCodeCloser = codeDelimiter
        if (activeCodeCloser != null) {
            when {
                activeCodeCloser == "`" && prepared[i] == '`' -> {
                    flush(); codeDelimiter = null; i += 1
                }
                activeCodeCloser == "</code>" && prepared.regionMatches(i, "</code>", 0, 7, ignoreCase = true) -> {
                    flush(); codeDelimiter = null; i += 7
                }
                else -> {
                    plain.append(prepared[i]); i += 1
                }
            }
            continue
        }
        when {
            prepared[i] == '`' && prepared.indexOf('`', i + 1) > i -> {
                flush(); codeDelimiter = "`"; i += 1
            }
            prepared.regionMatches(i, "<code>", 0, 6, ignoreCase = true) &&
                prepared.indexOf("</code>", i + 6, ignoreCase = true) >= 0 -> {
                flush(); codeDelimiter = "</code>"; i += 6
            }
            prepared.startsWith("**", i) && (boldStar || prepared.indexOf("**", i + 2) >= 0) -> {
                flush(); boldStar = !boldStar; i += 2
            }
            prepared.startsWith("__", i) && (
                (boldUnderscore && isClosingDoubleUnderscore(prepared, i)) ||
                    (!boldUnderscore && isOpeningDoubleUnderscore(prepared, i))
                ) -> {
                flush(); boldUnderscore = !boldUnderscore; i += 2
            }
            prepared.startsWith("~~", i) && (strike || prepared.indexOf("~~", i + 2) >= 0) -> {
                flush(); strike = !strike; i += 2
            }
            prepared.regionMatches(i, "<u>", 0, 3, ignoreCase = true) -> {
                flush(); underline = true; i += 3
            }
            prepared.regionMatches(i, "</u>", 0, 4, ignoreCase = true) -> {
                flush(); underline = false; i += 4
            }
            prepared.regionMatches(i, "<ins>", 0, 5, ignoreCase = true) -> {
                flush(); underline = true; i += 5
            }
            prepared.regionMatches(i, "</ins>", 0, 6, ignoreCase = true) -> {
                flush(); underline = false; i += 6
            }
            prepared.regionMatches(i, "<s>", 0, 3, ignoreCase = true) -> {
                flush(); strike = true; i += 3
            }
            prepared.regionMatches(i, "</s>", 0, 4, ignoreCase = true) -> {
                flush(); strike = false; i += 4
            }
            prepared.regionMatches(i, "<del>", 0, 5, ignoreCase = true) -> {
                flush(); strike = true; i += 5
            }
            prepared.regionMatches(i, "</del>", 0, 6, ignoreCase = true) -> {
                flush(); strike = false; i += 6
            }
            prepared.regionMatches(i, "<strike>", 0, 8, ignoreCase = true) -> {
                flush(); strike = true; i += 8
            }
            prepared.regionMatches(i, "</strike>", 0, 9, ignoreCase = true) -> {
                flush(); strike = false; i += 9
            }
            prepared.regionMatches(i, "<sub>", 0, 5, ignoreCase = true) -> {
                flush(); subscript = true; i += 5
            }
            prepared.regionMatches(i, "</sub>", 0, 6, ignoreCase = true) -> {
                flush(); subscript = false; i += 6
            }
            prepared.regionMatches(i, "<sup>", 0, 5, ignoreCase = true) -> {
                flush(); superscript = true; i += 5
            }
            prepared.regionMatches(i, "</sup>", 0, 6, ignoreCase = true) -> {
                flush(); superscript = false; i += 6
            }
            prepared[i] == '*' && !prepared.startsWith("**", i) && (italic || hasClosingSingleStar(prepared, i + 1)) -> {
                flush(); italic = !italic; i += 1
            }
            else -> {
                plain.append(prepared[i]); i += 1
            }
        }
    }
    flush()
}

private fun findHtmlTagEnd(s: String, startAfterTagName: Int): Int {
    var i = startAfterTagName
    var quote: Char? = null
    while (i < s.length) {
        val c = s[i]
        if (quote != null) {
            if (c == quote) quote = null
        } else {
            when (c) {
                '"', '\'' -> quote = c
                '>' -> return i
            }
        }
        i++
    }
    return -1
}

private fun transformOutsideCodeSpans(s: String, transform: (String) -> String): String {
    val out = StringBuilder()
    val nonCode = StringBuilder()
    var i = 0
    fun flushNonCode() {
        if (nonCode.isNotEmpty()) {
            out.append(transform(nonCode.toString()))
            nonCode.clear()
        }
    }
    while (i < s.length) {
        if (s.regionMatches(i, "<img", 0, 4, ignoreCase = true) &&
            i + 4 < s.length && (s[i + 4].isWhitespace() || s[i + 4] == '/' || s[i + 4] == '>')
        ) {
            val tagEnd = findHtmlTagEnd(s, i + 4)
            if (tagEnd >= 0) {
                nonCode.append(s, i, tagEnd + 1)
                i = tagEnd + 1
                continue
            }
        }
        if (s.startsWith("![", i)) {
            val mdMatch = MD_IMAGE_REGEX.find(s, i)
            if (mdMatch != null && mdMatch.range.first == i) {
                nonCode.append(mdMatch.value)
                i = mdMatch.range.last + 1
                continue
            }
        }
        if (s[i] == '`') {
            val close = s.indexOf('`', i + 1)
            if (close > i) {
                flushNonCode()
                out.append(s, i, close + 1)
                i = close + 1
                continue
            }
        }
        if (s.regionMatches(i, "<code", 0, 5, ignoreCase = true) &&
            i + 5 < s.length && (s[i + 5] == '>' || s[i + 5].isWhitespace())
        ) {
            val openEnd = findHtmlTagEnd(s, i + 5)
            val close = if (openEnd >= 0) s.indexOf("</code>", openEnd + 1, ignoreCase = true) else -1
            if (openEnd >= 0 && close >= 0) {
                flushNonCode()
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
    flushNonCode()
    return out.toString()
}

private fun isOpeningDoubleUnderscore(s: String, at: Int): Boolean {
    if (at + 2 >= s.length || s[at + 2].isWhitespace() || s[at + 2] == '_') return false
    if (at > 0 && (s[at - 1].isLetterOrDigit() || s[at - 1] == '_' || s[at - 1] in "+-#~")) return false
    var j = at + 2
    while (j <= s.length - 2) {
        if (s.startsWith("__", j) && isClosingDoubleUnderscore(s, j)) return true
        j++
    }
    return false
}

private fun isClosingDoubleUnderscore(s: String, at: Int): Boolean {
    if (at <= 0 || s[at - 1].isWhitespace() || s[at - 1] == '_') return false
    if (at + 2 < s.length && (s[at + 2].isLetterOrDigit() || s[at + 2] == '_' || s[at + 2] == '(')) return false
    return true
}

private fun hasClosingSingleStar(s: String, from: Int): Boolean {
    var j = from
    while (j < s.length) {
        if (s.startsWith("**", j)) {
            j += 2
            continue
        }
        if (s[j] == '*') return true
        j++
    }
    return false
}

/**
 * A structured text or inline-image span produced by [parseFormattedLabelLines]
 * for rich SVG export (`<tspan>` and `<image>`).
 */
@Immutable
data class FormattedTextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val subscript: Boolean = false,
    val superscript: Boolean = false,
    val inlineImage: InlineImageSpec? = null,
) {
    val hasFormatting: Boolean
        get() = bold || italic || underline || strikethrough || code || subscript || superscript
}

/**
 * Parses [label] into lines of [FormattedTextSpan]s preserving bold, italic,
 * underline, strikethrough, inline code, subscript/superscript, expanded icon
 * glyphs (`fa:`, `mdi:`, `::icon()`), and inline `<img .../>` / `![alt](url)` specs.
 */
fun parseFormattedLabelLines(
    label: String,
    includeInlineImages: Boolean = false,
): List<List<FormattedTextSpan>> {
    val (prepared, images) = prepareLabelWithImagesAndIcons(label)
    val lines = ArrayList<MutableList<FormattedTextSpan>>()
    var currentLine = ArrayList<FormattedTextSpan>()
    lines += currentLine

    var i = 0
    var boldStar = false
    var boldUnderscore = false
    var italic = false
    var underline = false
    var strike = false
    var codeDelimiter: String? = null
    var subscript = false
    var superscript = false
    val plain = StringBuilder()

    fun emitText(rawText: String) {
        val parts = rawText.replace("&\u200Blt;", "<").split('\n')
        for ((idx, part) in parts.withIndex()) {
            if (idx > 0) {
                currentLine = ArrayList()
                lines += currentLine
            }
            if (part.isNotEmpty()) {
                currentLine += FormattedTextSpan(
                    text = part,
                    bold = boldStar || boldUnderscore,
                    italic = italic,
                    underline = underline,
                    strikethrough = strike,
                    code = codeDelimiter != null,
                    subscript = subscript,
                    superscript = superscript,
                )
            }
        }
    }

    fun flush() {
        if (plain.isEmpty()) return
        val s = plain.toString()
        plain.clear()
        emitText(s)
    }

    while (i < prepared.length) {
        if (prepared.startsWith("\u0000IMG:", i)) {
            val endMarker = prepared.indexOf('\u0000', i + 5)
            if (endMarker > i + 5) {
                flush()
                val imgIdx = prepared.substring(i + 5, endMarker).toIntOrNull()
                val spec = imgIdx?.let { images.getOrNull(it) }
                if (spec != null) {
                    if (includeInlineImages) {
                        currentLine += FormattedTextSpan(
                            text = "[${spec.alt}]",
                            inlineImage = spec,
                        )
                    } else {
                        emitText("[🖼 ${spec.alt}]")
                    }
                }
                i = endMarker + 1
                continue
            }
        }
        val activeCodeCloser = codeDelimiter
        if (activeCodeCloser != null) {
            when {
                activeCodeCloser == "`" && prepared[i] == '`' -> {
                    flush(); codeDelimiter = null; i += 1
                }
                activeCodeCloser == "</code>" && prepared.regionMatches(i, "</code>", 0, 7, ignoreCase = true) -> {
                    flush(); codeDelimiter = null; i += 7
                }
                else -> {
                    plain.append(prepared[i]); i += 1
                }
            }
            continue
        }
        when {
            prepared[i] == '`' && prepared.indexOf('`', i + 1) > i -> {
                flush(); codeDelimiter = "`"; i += 1
            }
            prepared.regionMatches(i, "<code>", 0, 6, ignoreCase = true) &&
                prepared.indexOf("</code>", i + 6, ignoreCase = true) >= 0 -> {
                flush(); codeDelimiter = "</code>"; i += 6
            }
            prepared.startsWith("**", i) && (boldStar || prepared.indexOf("**", i + 2) >= 0) -> {
                flush(); boldStar = !boldStar; i += 2
            }
            prepared.startsWith("__", i) && (
                (boldUnderscore && isClosingDoubleUnderscore(prepared, i)) ||
                    (!boldUnderscore && isOpeningDoubleUnderscore(prepared, i))
                ) -> {
                flush(); boldUnderscore = !boldUnderscore; i += 2
            }
            prepared.startsWith("~~", i) && (strike || prepared.indexOf("~~", i + 2) >= 0) -> {
                flush(); strike = !strike; i += 2
            }
            prepared.regionMatches(i, "<u>", 0, 3, ignoreCase = true) -> {
                flush(); underline = true; i += 3
            }
            prepared.regionMatches(i, "</u>", 0, 4, ignoreCase = true) -> {
                flush(); underline = false; i += 4
            }
            prepared.regionMatches(i, "<ins>", 0, 5, ignoreCase = true) -> {
                flush(); underline = true; i += 5
            }
            prepared.regionMatches(i, "</ins>", 0, 6, ignoreCase = true) -> {
                flush(); underline = false; i += 6
            }
            prepared.regionMatches(i, "<s>", 0, 3, ignoreCase = true) -> {
                flush(); strike = true; i += 3
            }
            prepared.regionMatches(i, "</s>", 0, 4, ignoreCase = true) -> {
                flush(); strike = false; i += 4
            }
            prepared.regionMatches(i, "<del>", 0, 5, ignoreCase = true) -> {
                flush(); strike = true; i += 5
            }
            prepared.regionMatches(i, "</del>", 0, 6, ignoreCase = true) -> {
                flush(); strike = false; i += 6
            }
            prepared.regionMatches(i, "<strike>", 0, 8, ignoreCase = true) -> {
                flush(); strike = true; i += 8
            }
            prepared.regionMatches(i, "</strike>", 0, 9, ignoreCase = true) -> {
                flush(); strike = false; i += 9
            }
            prepared.regionMatches(i, "<sub>", 0, 5, ignoreCase = true) -> {
                flush(); subscript = true; i += 5
            }
            prepared.regionMatches(i, "</sub>", 0, 6, ignoreCase = true) -> {
                flush(); subscript = false; i += 6
            }
            prepared.regionMatches(i, "<sup>", 0, 5, ignoreCase = true) -> {
                flush(); superscript = true; i += 5
            }
            prepared.regionMatches(i, "</sup>", 0, 6, ignoreCase = true) -> {
                flush(); superscript = false; i += 6
            }
            prepared[i] == '*' && !prepared.startsWith("**", i) && (italic || hasClosingSingleStar(prepared, i + 1)) -> {
                flush(); italic = !italic; i += 1
            }
            else -> {
                plain.append(prepared[i]); i += 1
            }
        }
    }
    flush()
    return lines
}

/**
 * Wraps [label] into lines of [FormattedTextSpan]s within [maxWidth] while
 * preserving span formatting and inline image specifications.
 *
 * When [textMeasurer] and [textStyle] are provided, candidate line widths are
 * measured using Compose's actual font shaping at [density] rather than the
 * headless character-width heuristic.
 */
fun wrapFormattedLabelLines(
    label: String,
    fontSp: Float,
    maxWidth: Float,
    textMeasurer: TextMeasurer? = null,
    textStyle: TextStyle? = null,
    density: Density = Density(1f, 1f),
    includeInlineImages: Boolean = false,
): List<List<FormattedTextSpan>> {
    val maxWidthPx = maxWidth.coerceAtLeast(fontSp * 2f)
    val maxChars = kotlin.math.max(8, (maxWidthPx / (fontSp * 0.62f)).toInt())
    val parsedLines = parseFormattedLabelLines(label, includeInlineImages)
    val out = ArrayList<List<FormattedTextSpan>>()

    fun measureSpansWidthPx(spans: List<FormattedTextSpan>): Float {
        if (spans.isEmpty()) return 0f
        if (textMeasurer != null && textStyle != null) {
            var widthPx = 0f
            val textOnly = buildString {
                for (sp in spans) {
                    if (sp.inlineImage != null) {
                        widthPx += sp.inlineImage.widthPx * density.density + 4f * density.density
                    } else {
                        append(sp.text)
                    }
                }
            }
            if (textOnly.isNotEmpty()) {
                val res = textMeasurer.measure(
                    text = AnnotatedString(textOnly),
                    style = textStyle,
                    constraints = Constraints(),
                    density = density,
                )
                widthPx += res.size.width.toFloat()
            }
            return widthPx
        }
        var est = 0f
        for (sp in spans) {
            if (sp.inlineImage != null) {
                est += sp.inlineImage.widthPx + 4f
            } else {
                est += sp.text.length * fontSp * 0.62f
            }
        }
        return est
    }

    for (line in parsedLines) {
        if (measureSpansWidthPx(line) <= maxWidthPx) {
            out += line
            continue
        }
        // Word-wrap across spans while preserving each word's FormattedTextSpan attributes
        var curLine = ArrayList<FormattedTextSpan>()

        fun appendToLine( list: MutableList<FormattedTextSpan>, template: FormattedTextSpan, tokenText: String) {
            val trimmedToken = if (list.isEmpty()) tokenText.trimStart(' ') else tokenText
            if (trimmedToken.isEmpty() && template.inlineImage == null) return
            val last = list.lastOrNull()
            if (last != null &&
                last.inlineImage == null &&
                template.inlineImage == null &&
                last.bold == template.bold &&
                last.italic == template.italic &&
                last.underline == template.underline &&
                last.strikethrough == template.strikethrough &&
                last.code == template.code &&
                last.subscript == template.subscript &&
                last.superscript == template.superscript
            ) {
                list[list.lastIndex] = last.copy(text = last.text + trimmedToken)
            } else {
                list += template.copy(text = trimmedToken)
            }
        }

        fun pushSpanToken(template: FormattedTextSpan, tokenText: String) {
            val candidate = ArrayList(curLine)
            appendToLine(candidate, template, tokenText)
            if (curLine.isNotEmpty() && measureSpansWidthPx(candidate) > maxWidthPx) {
                out += curLine
                curLine = ArrayList()
                appendToLine(curLine, template, tokenText)
            } else {
                curLine = candidate
            }
        }

        for (span in line) {
            if (span.inlineImage != null) {
                pushSpanToken(span, span.text)
                continue
            }
            val words = Regex("""\S+|\s+""").findAll(span.text).map { it.value }.toList()
            for (word in words) {
                if (word.isBlank()) {
                    if (curLine.isNotEmpty()) {
                        val candidate = ArrayList(curLine)
                        appendToLine(candidate, span, " ")
                        if (measureSpansWidthPx(candidate) <= maxWidthPx) {
                            curLine = candidate
                        }
                    }
                    continue
                }
                val singleWordList = ArrayList<FormattedTextSpan>().apply { appendToLine(this, span, word) }
                if (measureSpansWidthPx(singleWordList) <= maxWidthPx) {
                    pushSpanToken(span, word)
                } else {
                    var pos = 0
                    val stepChars = if (textMeasurer != null && textStyle != null) {
                        val avgGlyphW = (measureSpansWidthPx(singleWordList) / word.length.coerceAtLeast(1)).coerceAtLeast(1f)
                        kotlin.math.max(1, (maxWidthPx / avgGlyphW).toInt())
                    } else {
                        maxChars
                    }
                    while (pos < word.length) {
                        val end = kotlin.math.min(word.length, pos + stepChars)
                        val chunk = word.substring(pos, end)
                        pushSpanToken(span, chunk)
                        if (end < word.length && curLine.isNotEmpty()) {
                            out += curLine
                            curLine = ArrayList()
                        }
                        pos = end
                    }
                }
            }
        }
        if (curLine.isNotEmpty()) {
            out += curLine
        }
    }
    return if (out.isEmpty()) listOf(emptyList()) else out
}
