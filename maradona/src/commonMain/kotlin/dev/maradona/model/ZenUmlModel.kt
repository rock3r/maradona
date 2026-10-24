package dev.maradona.model

/**
 * Participant stereotype (`@Actor`, `@Boundary`, `@Control`, `@Entity`, `@Database`, `@Collections`, `@Queue`, etc.) in a [ZenUmlDiagram].
 */
enum class ZenUmlParticipantStereotype {
    DEFAULT,
    ACTOR,
    BOUNDARY,
    CONTROL,
    ENTITY,
    DATABASE,
    COLLECTIONS,
    QUEUE,
}

/**
 * A lifeline participant in a [ZenUmlDiagram].
 */
data class ZenUmlParticipant(
    val id: String,
    val label: String,
    val stereotype: ZenUmlParticipantStereotype = ZenUmlParticipantStereotype.DEFAULT,
    val rawAnnotation: String? = null,
    val groupName: String? = null,
)

/**
 * Named participant group (`group Name { ... }`) in a [ZenUmlDiagram].
 */
data class ZenUmlParticipantGroup(
    val name: String,
    val participantIds: List<String>,
)

/**
 * Kind of interaction message in a [ZenUmlDiagram].
 */
enum class ZenUmlMessageKind {
    /** Synchronous method call (`A.method(args)` or `A->B.method(args) { ... }`). */
    SYNC,
    /** Asynchronous message (`A->B: message`). */
    ASYNC,
    /** Participant creation (`new Service()` or `s = new Service()`). */
    CREATE,
    /** Return message (`return value`, `@return A->B: value`, or automatic return from `ret = A.method()`). */
    RETURN,
}

/**
 * Kind of combined fragment / control flow block in a [ZenUmlDiagram].
 */
enum class ZenUmlControlKind {
    ALT,
    LOOP,
    OPT,
    PAR,
    TRY,
}

/**
 * A branch within a [ZenUmlStatement.ControlBlock] (e.g., `if` / `else if` / `else`, or `try` / `catch` / `finally`).
 */
data class ZenUmlControlBranch(
    val keyword: String,
    val condition: String? = null,
    val statements: List<ZenUmlStatement>,
)

/**
 * A statement inside a [ZenUmlDiagram] or nested inside a synchronous call activation / control block.
 */
sealed interface ZenUmlStatement {
    /**
     * A synchronous call, asynchronous message, constructor call, or return statement.
     */
    data class Message(
        val from: String,
        val to: String,
        val text: String,
        val returnVar: String? = null,
        val returnType: String? = null,
        val kind: ZenUmlMessageKind = ZenUmlMessageKind.SYNC,
        val comment: String? = null,
        val nestedStatements: List<ZenUmlStatement> = emptyList(),
    ) : ZenUmlStatement

    /**
     * A control flow block (`if`/`else if`/`else`, `while`/`forEach`/`for`/`loop`, `opt`, `par`, `try`/`catch`/`finally`).
     */
    data class ControlBlock(
        val kind: ZenUmlControlKind,
        val branches: List<ZenUmlControlBranch>,
        val comment: String? = null,
    ) : ZenUmlStatement

    /**
     * A standalone `// comment` note attached to the current active lifeline.
     */
    data class NoteComment(
        val text: String,
        val onParticipant: String? = null,
    ) : ZenUmlStatement
}

/**
 * Parsed representation of a Mermaid `zenuml` diagram.
 */
data class ZenUmlDiagram(
    val participants: List<ZenUmlParticipant>,
    val groups: List<ZenUmlParticipantGroup> = emptyList(),
    val statements: List<ZenUmlStatement>,
    val mirrorParticipants: Boolean = false,
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
