package dev.maradona.model

enum class ClassClassifier {
    None,
    /** `*` suffix -> abstract member (italic). */
    Abstract,
    /** `$` suffix -> static member (underlined). */
    Static,
}

data class ClassMember(
    val displayText: String,
    val isMethod: Boolean = false,
    val classifier: ClassClassifier = ClassClassifier.None,
)

enum class ClassRelationEnd {
    None,
    /** `<|` or `|>` — hollow closed triangle (inheritance / realization). */
    Extension,
    /** `*` — filled diamond (composition). */
    Composition,
    /** `o` — hollow diamond (aggregation). */
    Aggregation,
    /** `<` or `>` — open V / arrow (association / dependency). */
    Dependency,
    /** `()` — hollow circle socket (lollipop interface). */
    Lollipop,
}

data class ClassNode(
    val id: String,
    val label: String = id,
    val genericType: String? = null,
    val annotations: List<String> = emptyList(),
    val attributes: List<ClassMember> = emptyList(),
    val methods: List<ClassMember> = emptyList(),
    val namespaceId: String? = null,
    val style: NodeStyle? = null,
) {
    val displayTitle: String
        get() = if (genericType.isNullOrBlank()) label else "$label<$genericType>"
}

data class ClassRelation(
    val from: String,
    val to: String,
    val startEnd: ClassRelationEnd = ClassRelationEnd.None,
    val endEnd: ClassRelationEnd = ClassRelationEnd.None,
    val dashed: Boolean = false,
    val label: String? = null,
    val fromCardinality: String? = null,
    val toCardinality: String? = null,
)

data class ClassNote(
    val id: String,
    val text: String,
    val forClassId: String? = null,
    val namespaceId: String? = null,
)

data class ClassNamespace(
    val id: String,
    val label: String = id,
    val parentId: String? = null,
)

data class ClassDiagram(
    val title: String? = null,
    val direction: Direction = Direction.TB,
    val hideEmptyMembersBox: Boolean = false,
    val classes: List<ClassNode> = emptyList(),
    val relations: List<ClassRelation> = emptyList(),
    val notes: List<ClassNote> = emptyList(),
    val namespaces: List<ClassNamespace> = emptyList(),
)
