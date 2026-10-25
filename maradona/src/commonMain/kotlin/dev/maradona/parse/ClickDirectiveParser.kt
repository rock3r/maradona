package dev.maradona.parse

import dev.maradona.model.NodeAction

/**
 * Shared parser for Mermaid `click`, `link`, and `callback` interaction directives across
 * `flowchart`, `classDiagram`, `gantt`, and `sequenceDiagram`.
 *
 * Supported forms:
 * - `click <id> "<url>" ["<tooltip>"] [_blank|_self|_parent|_top]`
 * - `click <id> href "<url>" ["<tooltip>"] [_blank|_self|_parent|_top]`
 * - `click <id> call <callbackName([args])> ["<tooltip>"]`
 * - `click <id> <callbackName([args])> ["<tooltip>"]`
 * - `link <id> "<url>" ["<tooltip>"] [_blank|_self|_parent|_top]`
 * - `callback <id> "<callbackName>" ["<tooltip>"]`
 * - `link <actor>: <label> @ <url>` (sequenceDiagram)
 * - `links <actor>: {"<label>": "<url>", ...}` (sequenceDiagram)
 */
internal object ClickDirectiveParser {
    private val TARGET_KEYWORDS = setOf("_blank", "_self", "_parent", "_top")

    data class ParsedNodeAction(
        val nodeIds: List<String>,
        val action: NodeAction,
    )

    fun parseClick(rest: String): ParsedNodeAction? {
        val tokens = tokenize(rest.trim())
        if (tokens.size < 2) return null
        val nodeIds = tokens[0].text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (nodeIds.isEmpty()) return null

        var idx = 1
        var isHref = false
        var isCall = false
        val firstToken = tokens[idx]
        if (!firstToken.wasQuoted) {
            when (firstToken.text.lowercase()) {
                "href" -> {
                    isHref = true
                    idx++
                }
                "call" -> {
                    isCall = true
                    idx++
                }
            }
        }
        if (idx >= tokens.size) return null
        val valueToken = tokens[idx++]
        var url: String? = null
        var callback: String? = null
        when {
            isHref -> url = valueToken.text
            isCall -> callback = valueToken.text
            valueToken.wasQuoted || looksLikeUrl(valueToken.text) -> url = valueToken.text
            else -> callback = valueToken.text
        }

        var tooltip: String? = null
        var target: String? = null
        while (idx < tokens.size) {
            val t = tokens[idx++]
            if (!t.wasQuoted && t.text.lowercase() in TARGET_KEYWORDS) {
                target = t.text.lowercase()
            } else if (tooltip == null) {
                tooltip = t.text
            }
        }

        return ParsedNodeAction(
            nodeIds = nodeIds,
            action = NodeAction(
                url = url?.takeIf { it.isNotBlank() },
                callback = callback?.takeIf { it.isNotBlank() },
                tooltip = tooltip?.takeIf { it.isNotBlank() },
                target = target,
            ),
        )
    }

    fun parseClassLink(rest: String): ParsedNodeAction? {
        val tokens = tokenize(rest.trim())
        if (tokens.size < 2) return null
        val nodeIds = tokens[0].text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (nodeIds.isEmpty()) return null
        val url = tokens[1].text.takeIf { it.isNotBlank() } ?: return null
        var tooltip: String? = null
        var target: String? = null
        for (i in 2 until tokens.size) {
            val t = tokens[i]
            if (!t.wasQuoted && t.text.lowercase() in TARGET_KEYWORDS) {
                target = t.text.lowercase()
            } else if (tooltip == null) {
                tooltip = t.text
            }
        }
        return ParsedNodeAction(
            nodeIds = nodeIds,
            action = NodeAction(url = url, tooltip = tooltip, target = target),
        )
    }

    fun parseClassCallback(rest: String): ParsedNodeAction? {
        val tokens = tokenize(rest.trim())
        if (tokens.size < 2) return null
        val nodeIds = tokens[0].text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (nodeIds.isEmpty()) return null
        val cb = tokens[1].text.takeIf { it.isNotBlank() } ?: return null
        val tooltip = tokens.getOrNull(2)?.text?.takeIf { it.isNotBlank() }
        return ParsedNodeAction(
            nodeIds = nodeIds,
            action = NodeAction(callback = cb, tooltip = tooltip),
        )
    }

    /**
     * Parses `link <actor>: <label> @ <url>` or `links <actor>: {"<label>": "<url>", ...}`.
     */
    fun parseSequenceLinks(stmt: String): Pair<String, Map<String, String>>? {
        val trimmed = stmt.trim()
        val lower = trimmed.lowercase()
        val isSingle = lower.startsWith("link ")
        val isMulti = lower.startsWith("links ")
        if (!isSingle && !isMulti) return null
        val body = trimmed.substring(if (isMulti) 6 else 5).trim()
        val colonIdx = body.indexOf(':')
        if (colonIdx <= 0) return null
        val actorId = body.substring(0, colonIdx).trim().trim('"', '\'')
        if (actorId.isEmpty()) return null
        val rhs = body.substring(colonIdx + 1).trim()
        val out = LinkedHashMap<String, String>()
        if (rhs.startsWith("{") && rhs.endsWith("}")) {
            val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
            for (m in pairRegex.findAll(rhs)) {
                val k = m.groupValues[1].trim()
                val v = m.groupValues[2].trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        } else {
            val spacedAt = Regex("""\s+@\s+""").find(rhs)
            val splitRange = spacedAt?.range ?: rhs.indexOf('@').takeIf { it > 0 }?.let { it..it }
            if (splitRange != null) {
                val label = rhs.substring(0, splitRange.first).trim().trim('"', '\'')
                val url = rhs.substring(splitRange.last + 1).trim().trim('"', '\'')
                if (label.isNotEmpty() && url.isNotEmpty()) out[label] = url
            }
        }
        return if (out.isEmpty()) null else actorId to out
    }

    private fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("/") ||
            lower.startsWith("./") ||
            lower.startsWith("../")
    }

    private data class Token(val text: String, val wasQuoted: Boolean)

    private fun tokenize(input: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = input.length
        while (i < n) {
            while (i < n && input[i].isWhitespace()) i++
            if (i >= n) break
            val ch = input[i]
            if (ch == '"' || ch == '\'') {
                val quote = ch
                i++
                val sb = StringBuilder()
                while (i < n && input[i] != quote) {
                    if (input[i] == '\\' && i + 1 < n) {
                        i++
                    }
                    sb.append(input[i])
                    i++
                }
                if (i < n && input[i] == quote) i++
                out += Token(sb.toString(), wasQuoted = true)
            } else {
                val sb = StringBuilder()
                var parenDepth = 0
                while (i < n) {
                    val c = input[i]
                    if (c.isWhitespace() && parenDepth == 0) break
                    if (c == '(') parenDepth++
                    else if (c == ')' && parenDepth > 0) parenDepth--
                    sb.append(c)
                    i++
                }
                out += Token(sb.toString(), wasQuoted = false)
            }
        }
        return out
    }
}
