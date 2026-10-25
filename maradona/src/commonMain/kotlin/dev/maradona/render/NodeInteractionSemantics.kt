package dev.maradona.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.model.NodeAction

/**
 * Formats an accessibility suffix for a [NodeAction] (or link/callback metadata).
 */
internal fun formatActionDescription(
    url: String? = null,
    callback: String? = null,
    tooltip: String? = null,
    links: Map<String, String> = emptyMap(),
): String? {
    val parts = buildList {
        tooltip?.takeIf { it.isNotBlank() }?.let { add("Tooltip: $it") }
        if (links.size > 1) {
            add("Links: " + links.entries.joinToString(", ") { "${it.key} (${it.value})" })
        } else {
            url?.takeIf { it.isNotBlank() }?.let { add("Link: $it") }
        }
        callback?.takeIf { it.isNotBlank() }?.let { add("Action: $it") }
    }
    return if (parts.isEmpty()) null else parts.joinToString(". ")
}

internal fun NodeAction.toClickEvent(nodeId: String, label: String): MaradonaNodeClickEvent =
    MaradonaNodeClickEvent(
        nodeId = nodeId,
        label = label,
        url = url,
        callback = callback,
        tooltip = tooltip,
        target = target,
    )

/**
 * Applies `contentDescription`, focusability, and optional `Role.Button` click handling
 * (invoking [onNodeClick] when supplied, or falling back to [uriHandler] when the node has a target URL).
 *
 * Traversal order is intentionally left to Compose's geometric sorting within the parent
 * `isTraversalGroup = true` layout so node headers, members, and labels stay grouped with their boxes.
 */
internal fun Modifier.diagramNodeInteraction(
    baseDescription: String,
    traversalOrder: Int = 0,
    event: MaradonaNodeClickEvent? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
    uriHandler: UriHandler? = null,
    mergeDescendants: Boolean = false,
): Modifier {
    val actionDesc = if (event != null) {
        formatActionDescription(
            url = event.url,
            callback = event.callback,
            tooltip = event.tooltip,
            links = event.links,
        )
    } else {
        null
    }
    val fullDescription = if (actionDesc.isNullOrBlank()) {
        baseDescription
    } else {
        "$baseDescription. $actionDesc"
    }
    val hasActionableTarget = event != null && (
        (!event.url.isNullOrBlank() && (onNodeClick != null || uriHandler != null)) ||
            (!event.callback.isNullOrBlank() && onNodeClick != null) ||
            onNodeClick != null
        )

    val interactionModifier = if (hasActionableTarget) {
        this.clickable(role = Role.Button) {
            if (onNodeClick != null) {
                onNodeClick(event)
            } else if (!event.url.isNullOrBlank() && uriHandler != null) {
                runCatching { uriHandler.openUri(event.url) }
            }
        }
    } else {
        this.focusable()
    }

    return interactionModifier.semantics(mergeDescendants = mergeDescendants) {
        contentDescription = fullDescription
        if (hasActionableTarget) {
            role = Role.Button
        }
        if (event != null && event.links.size > 1 && (onNodeClick != null || uriHandler != null)) {
            customActions = event.links.map { (linkLabel, linkUrl) ->
                CustomAccessibilityAction(label = "Open $linkLabel") {
                    if (onNodeClick != null) {
                        onNodeClick(event.copy(url = linkUrl, tooltip = linkLabel))
                    } else if (uriHandler != null) {
                        runCatching { uriHandler.openUri(linkUrl) }
                    }
                    true
                }
            }
        }
    }
}
