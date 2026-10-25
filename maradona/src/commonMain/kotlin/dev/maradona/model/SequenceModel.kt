package dev.maradona.model

enum class ParticipantKind {
    Participant,
    Actor,
}

data class SequenceParticipant(
    val id: String,
    val label: String = id,
    val kind: ParticipantKind = ParticipantKind.Participant,
    val links: Map<String, String> = emptyMap(),
)

enum class SequenceHead {
    /** Solid filled triangle (`->>`, `-->>`). */
    Filled,
    /** Plain line without arrowhead (`->`, `-->`). */
    Open,
    /** Cross head (`-x`, `--x`). */
    Cross,
    /** Open/stick async head (`-)`, `--)`). */
    Async,
}

data class SequenceArrow(
    val dashed: Boolean = false,
    val head: SequenceHead = SequenceHead.Filled,
    val bidirectional: Boolean = false,
)

enum class NotePlacement {
    LeftOf,
    RightOf,
    Over,
}

enum class SequenceBlockKind {
    Loop,
    Alt,
    Opt,
    Par,
    Critical,
    Break,
    Rect,
}

data class SequenceSection(
    val label: String?,
    val statements: List<SequenceStatement>,
)

sealed interface SequenceStatement {
    data class Message(
        val from: String,
        val to: String,
        val text: String,
        val arrow: SequenceArrow = SequenceArrow(),
        val activateTarget: Boolean = false,
        val deactivateSource: Boolean = false,
    ) : SequenceStatement

    data class Activate(val participantId: String) : SequenceStatement

    data class Deactivate(val participantId: String) : SequenceStatement

    data class Note(
        val placement: NotePlacement,
        val participants: List<String>,
        val text: String,
    ) : SequenceStatement

    data class Block(
        val kind: SequenceBlockKind,
        val label: String?,
        val sections: List<SequenceSection>,
    ) : SequenceStatement
}

data class SequenceParticipantBoxGroup(
    val color: String? = null,
    val label: String? = null,
    val participantIds: List<String>,
)

data class SequenceDiagram(
    val title: String? = null,
    val autonumber: Boolean = false,
    val mirrorActors: Boolean = true,
    val participants: List<SequenceParticipant> = emptyList(),
    val boxes: List<SequenceParticipantBoxGroup> = emptyList(),
    val statements: List<SequenceStatement> = emptyList(),
)
