package dev.maradona.model

enum class ErCardinality {
    ExactlyOne,
    ZeroOrOne,
    OneOrMore,
    ZeroOrMore,
}

data class ErAttribute(
    val type: String,
    val name: String,
    val keys: List<String> = emptyList(),
    val comment: String? = null,
)

data class ErEntity(
    val id: String,
    val label: String,
    val attributes: List<ErAttribute> = emptyList(),
    val subgraphId: String? = null,
    val style: NodeStyle? = null,
)

data class ErRelationship(
    val from: String,
    val to: String,
    val fromCardinality: ErCardinality,
    val toCardinality: ErCardinality,
    val identifying: Boolean = true,
    val label: String? = null,
)

data class ErSubgraph(
    val id: String,
    val label: String,
    val parentId: String? = null,
    val direction: Direction? = null,
    val style: NodeStyle? = null,
)

data class ErDiagram(
    val direction: Direction = Direction.TB,
    val entities: List<ErEntity>,
    val relationships: List<ErRelationship>,
    val subgraphs: List<ErSubgraph> = emptyList(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    fun entity(id: String): ErEntity? = entities.firstOrNull { it.id == id }
    fun subgraph(id: String): ErSubgraph? = subgraphs.firstOrNull { it.id == id }
}
