package dev.maradona.model

enum class MindmapShape {
    Default,
    Square,
    Rounded,
    Circle,
    Bang,
    Cloud,
    Hexagon,
}

data class MindmapNode(
    val id: String,
    val label: String,
    val shape: MindmapShape = MindmapShape.Default,
    val parentId: String? = null,
    val icon: String? = null,
    val classes: List<String> = emptyList(),
    val style: NodeStyle? = null,
)

data class MindmapDiagram(
    val nodes: List<MindmapNode>,
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    val root: MindmapNode? get() = nodes.firstOrNull { it.parentId == null }

    fun node(idOrLabel: String): MindmapNode? =
        nodes.firstOrNull { it.id == idOrLabel } ?: nodes.firstOrNull { it.label == idOrLabel }
}
