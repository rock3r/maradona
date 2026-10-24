package dev.maradona.model

/**
 * High-level C4 diagram subtype from the Mermaid header (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`).
 */
enum class C4DiagramType {
    CONTEXT,
    CONTAINER,
    COMPONENT,
    DYNAMIC,
    DEPLOYMENT,
}

/**
 * Kind of C4 element macro (`Person`, `System`, `Container`, `Component`, and `_Ext` / `Db` / `Queue` variants).
 */
enum class C4ElementKind(
    val isExternal: Boolean,
    val isPerson: Boolean,
    val isDatabase: Boolean,
    val isQueue: Boolean,
    val defaultTypeTag: String,
) {
    PERSON(false, true, false, false, "Person"),
    PERSON_EXT(true, true, false, false, "External Person"),
    SYSTEM(false, false, false, false, "System"),
    SYSTEM_EXT(true, false, false, false, "External System"),
    SYSTEM_DB(false, false, true, false, "System Database"),
    SYSTEM_DB_EXT(true, false, true, false, "External System Database"),
    SYSTEM_QUEUE(false, false, false, true, "System Queue"),
    SYSTEM_QUEUE_EXT(true, false, false, true, "External System Queue"),
    CONTAINER(false, false, false, false, "Container"),
    CONTAINER_EXT(true, false, false, false, "External Container"),
    CONTAINER_DB(false, false, true, false, "Container Database"),
    CONTAINER_DB_EXT(true, false, true, false, "External Container Database"),
    CONTAINER_QUEUE(false, false, false, true, "Container Queue"),
    CONTAINER_QUEUE_EXT(true, false, false, true, "External Container Queue"),
    COMPONENT(false, false, false, false, "Component"),
    COMPONENT_EXT(true, false, false, false, "External Component"),
    COMPONENT_DB(false, false, true, false, "Component Database"),
    COMPONENT_DB_EXT(true, false, true, false, "External Component Database"),
    COMPONENT_QUEUE(false, false, false, true, "Component Queue"),
    COMPONENT_QUEUE_EXT(true, false, false, true, "External Component Queue"),
}

/**
 * Custom styling overrides applied via `UpdateElementStyle` or `UpdateBoundaryStyle`.
 */
data class C4ElementStyle(
    val fontColor: String? = null,
    val bgColor: String? = null,
    val borderColor: String? = null,
    val shadowing: Boolean? = null,
    val shape: String? = null,
) {
    fun merge(other: C4ElementStyle?): C4ElementStyle {
        if (other == null) return this
        return C4ElementStyle(
            fontColor = other.fontColor ?: fontColor,
            bgColor = other.bgColor ?: bgColor,
            borderColor = other.borderColor ?: borderColor,
            shadowing = other.shadowing ?: shadowing,
            shape = other.shape ?: shape,
        )
    }
}

/**
 * A C4 element (Person, System, Container, or Component).
 */
data class C4Element(
    val id: String,
    val label: String,
    val kind: C4ElementKind,
    val technology: String? = null,
    val description: String? = null,
    val sprite: String? = null,
    val tags: String? = null,
    val link: String? = null,
    val parentBoundaryId: String? = null,
    val style: C4ElementStyle? = null,
) {
    val typeHeaderText: String
        get() = if (!technology.isNullOrBlank()) {
            "<<${kind.defaultTypeTag}: $technology>>"
        } else {
            "<<${kind.defaultTypeTag}>>"
        }
}

/**
 * Kind of C4 boundary or deployment node block (`{ ... }`).
 */
enum class C4BoundaryKind {
    GENERIC,
    ENTERPRISE,
    SYSTEM,
    CONTAINER,
    DEPLOYMENT_NODE,
}

/**
 * A hierarchical boundary or deployment node (`Boundary`, `Enterprise_Boundary`, `System_Boundary`, `Container_Boundary`, `Deployment_Node`, `Node`).
 */
data class C4Boundary(
    val id: String,
    val label: String,
    val kind: C4BoundaryKind,
    val type: String,
    val description: String? = null,
    val parentBoundaryId: String? = null,
    val style: C4ElementStyle? = null,
)

/**
 * Kind of C4 relationship macro (`Rel`, `BiRel`, `Rel_Back`, `Rel_U`, `Rel_D`, `Rel_L`, `Rel_R`, `RelIndex`).
 */
enum class C4RelKind {
    REL,
    BI_REL,
    REL_BACK,
    REL_UP,
    REL_DOWN,
    REL_LEFT,
    REL_RIGHT,
    REL_INDEX,
}

/**
 * Custom relationship style overrides applied via `UpdateRelStyle`.
 */
data class C4RelStyle(
    val textColor: String? = null,
    val lineColor: String? = null,
    val offsetX: Float? = null,
    val offsetY: Float? = null,
) {
    fun merge(other: C4RelStyle?): C4RelStyle {
        if (other == null) return this
        return C4RelStyle(
            textColor = other.textColor ?: textColor,
            lineColor = other.lineColor ?: lineColor,
            offsetX = other.offsetX ?: offsetX,
            offsetY = other.offsetY ?: offsetY,
        )
    }
}

/**
 * A directed or bidirectional relationship between two C4 elements or boundaries.
 */
data class C4Relationship(
    val from: String,
    val to: String,
    val label: String,
    val technology: String? = null,
    val description: String? = null,
    val kind: C4RelKind = C4RelKind.REL,
    val index: String? = null,
    val style: C4RelStyle? = null,
)

/**
 * Layout configuration for a [C4Diagram].
 */
data class C4Config(
    val c4ShapeInRow: Int = 4,
    val c4BoundaryInRow: Int = 2,
    val diagramMarginX: Float = 24f,
    val diagramMarginY: Float = 24f,
)

/**
 * Parsed representation of a Mermaid C4 diagram (`C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`).
 */
data class C4Diagram(
    val type: C4DiagramType,
    val direction: Direction = Direction.TB,
    val elements: List<C4Element>,
    val boundaries: List<C4Boundary>,
    val relationships: List<C4Relationship>,
    val config: C4Config = C4Config(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
