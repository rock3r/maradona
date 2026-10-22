package dev.maradona.model

enum class StateKind {
    Normal,
    Start,
    End,
    Fork,
    Join,
    Choice,
    Composite,
}

enum class StateNotePosition {
    Left,
    Right,
}

data class StateNode(
    val id: String,
    val label: String,
    val kind: StateKind = StateKind.Normal,
    val descriptions: List<String> = emptyList(),
    val parentId: String? = null,
    val regionIndex: Int = 0,
    val direction: Direction? = null,
    val regionCount: Int = 1,
    val style: NodeStyle? = null,
)

data class StateTransition(
    val from: String,
    val to: String,
    val label: String? = null,
    val style: EdgeStyle? = null,
)

data class StateNote(
    val id: String,
    val text: String,
    val forStateId: String? = null,
    val position: StateNotePosition = StateNotePosition.Right,
    val parentId: String? = null,
    val regionIndex: Int = 0,
)

data class StateDiagram(
    val direction: Direction = Direction.TB,
    val states: List<StateNode>,
    val transitions: List<StateTransition>,
    val notes: List<StateNote> = emptyList(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
    val hideEmptyDescription: Boolean = false,
) {
    fun state(id: String): StateNode? = states.firstOrNull { it.id == id }
}
