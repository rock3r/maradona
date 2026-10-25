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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
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
private val MD_IMAGE_REGEX = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

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

private fun parseImgTag(match: MatchResult, inlineId: String): InlineImageSpec? {
    val attrMap = parseHtmlAttributes(match.groupValues[1])
    val url = attrMap["src"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val alt = attrMap["alt"]?.trim()?.takeIf { it.isNotEmpty() }
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

private fun prepareLabelWithImagesAndIcons(label: String): Pair<String, List<InlineImageSpec>> {
    val images = ArrayList<InlineImageSpec>()
    val prepared = transformOutsideCodeSpans(label) { segment ->
        var s = segment
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
        val text = plain.toString()
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
            prepared.startsWith("__", i) && (boldUnderscore || prepared.indexOf("__", i + 2) >= 0) -> {
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
            val openEnd = s.indexOf('>', i + 5)
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
