package dev.maradona.model

enum class RequirementKind(val stereotype: String) {
    Requirement("Requirement"),
    FunctionalRequirement("Functional Requirement"),
    InterfaceRequirement("Interface Requirement"),
    PerformanceRequirement("Performance Requirement"),
    PhysicalRequirement("Physical Requirement"),
    DesignConstraint("Design Constraint"),
}

enum class RequirementRisk(val displayText: String) {
    Low("Low"),
    Medium("Medium"),
    High("High"),
}

enum class RequirementVerifyMethod(val displayText: String) {
    Analysis("Analysis"),
    Demonstration("Demonstration"),
    Inspection("Inspection"),
    Test("Test"),
}

enum class RequirementRelationType(val keyword: String) {
    Contains("contains"),
    Copies("copies"),
    Derives("derives"),
    Satisfies("satisfies"),
    Verifies("verifies"),
    Refines("refines"),
    Traces("traces"),
    ;

    val label: String get() = "<<$keyword>>"
}

data class RequirementNodeStyle(
    val fill: String? = null,
    val stroke: String? = null,
    val strokeWidth: Float? = null,
    val textColor: String? = null,
    val fontWeightBold: Boolean? = null,
) {
    fun merge(override: RequirementNodeStyle?): RequirementNodeStyle {
        if (override == null) return this
        return RequirementNodeStyle(
            fill = override.fill ?: fill,
            stroke = override.stroke ?: stroke,
            strokeWidth = override.strokeWidth ?: strokeWidth,
            textColor = override.textColor ?: textColor,
            fontWeightBold = override.fontWeightBold ?: fontWeightBold,
        )
    }
}

sealed interface RequirementDiagramItem {
    val name: String
    val stereotype: String
    val bodyLines: List<String>
    val classes: List<String>
    val style: RequirementNodeStyle?
}

data class RequirementItem(
    override val name: String,
    val kind: RequirementKind = RequirementKind.Requirement,
    val requirementId: String? = null,
    val text: String? = null,
    val risk: RequirementRisk? = null,
    val verifyMethod: RequirementVerifyMethod? = null,
    override val classes: List<String> = emptyList(),
    override val style: RequirementNodeStyle? = null,
) : RequirementDiagramItem {
    override val stereotype: String get() = "<<${kind.stereotype}>>"
    override val bodyLines: List<String>
        get() = buildList {
            requirementId?.takeIf { it.isNotBlank() }?.let { add("ID: $it") }
            text?.takeIf { it.isNotBlank() }?.let { add("Text: $it") }
            risk?.let { add("Risk: ${it.displayText}") }
            verifyMethod?.let { add("Verification: ${it.displayText}") }
        }
}

data class ElementItem(
    override val name: String,
    val type: String? = null,
    val docRef: String? = null,
    override val classes: List<String> = emptyList(),
    override val style: RequirementNodeStyle? = null,
) : RequirementDiagramItem {
    override val stereotype: String get() = "<<Element>>"
    override val bodyLines: List<String>
        get() = buildList {
            type?.takeIf { it.isNotBlank() }?.let { add("Type: $it") }
            docRef?.takeIf { it.isNotBlank() }?.let { add("Doc Ref: $it") }
        }
}

data class RequirementRelation(
    val type: RequirementRelationType,
    val source: String,
    val target: String,
) {
    val label: String get() = type.label
    val isContains: Boolean get() = type == RequirementRelationType.Contains
}

data class RequirementDiagram(
    val requirements: List<RequirementItem>,
    val elements: List<ElementItem>,
    val relations: List<RequirementRelation>,
    val direction: Direction = Direction.TB,
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    val items: List<RequirementDiagramItem>
        get() = requirements + elements

    fun requirement(name: String): RequirementItem? = requirements.firstOrNull { it.name == name }
    fun element(name: String): ElementItem? = elements.firstOrNull { it.name == name }
    fun item(name: String): RequirementDiagramItem? = items.firstOrNull { it.name == name }
}
