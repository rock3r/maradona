package dev.maradona.model

/**
 * Port direction on an architecture node or group (`L` = Left, `R` = Right, `T` = Top, `B` = Bottom).
 */
enum class ArchitecturePortDir {
    L,
    R,
    T,
    B,
}

/**
 * Alignment axis for an `align row` or `align column` directive.
 */
enum class ArchitectureAlignDir {
    Row,
    Column,
}

/**
 * A group container in an [ArchitectureDiagram].
 */
data class ArchitectureGroup(
    val id: String,
    val icon: String? = null,
    val title: String? = null,
    val parentId: String? = null,
)

/**
 * A service node in an [ArchitectureDiagram].
 */
data class ArchitectureService(
    val id: String,
    val icon: String? = null,
    val iconText: String? = null,
    val title: String? = null,
    val parentId: String? = null,
)

/**
 * A junction node in an [ArchitectureDiagram].
 */
data class ArchitectureJunction(
    val id: String,
    val parentId: String? = null,
)

/**
 * A port-to-port connection in an [ArchitectureDiagram].
 */
data class ArchitectureEdge(
    val lhsId: String,
    val lhsDir: ArchitecturePortDir,
    val lhsInto: Boolean = false,
    val lhsGroup: Boolean = false,
    val rhsId: String,
    val rhsDir: ArchitecturePortDir,
    val rhsInto: Boolean = false,
    val rhsGroup: Boolean = false,
    val title: String? = null,
)

/**
 * A row or column alignment hint (`align row ...` or `align column ...`).
 */
data class ArchitectureAlignment(
    val direction: ArchitectureAlignDir,
    val members: List<String>,
)

/**
 * Layout configuration for an [ArchitectureDiagram].
 */
data class ArchitectureConfig(
    val padding: Float = 32f,
    val iconSize: Float = 64f,
    val fontSize: Float = 13f,
    val nodeSpacing: Float = 56f,
)

/**
 * Parsed representation of a Mermaid `architecture` / `architecture-beta` diagram.
 */
data class ArchitectureDiagram(
    val groups: List<ArchitectureGroup>,
    val services: List<ArchitectureService>,
    val junctions: List<ArchitectureJunction>,
    val edges: List<ArchitectureEdge>,
    val alignments: List<ArchitectureAlignment> = emptyList(),
    val config: ArchitectureConfig = ArchitectureConfig(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
