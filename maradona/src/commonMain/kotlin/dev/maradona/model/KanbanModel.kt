package dev.maradona.model

enum class KanbanPriority {
    VeryHigh,
    High,
    Medium,
    Low,
    VeryLow,
}

data class KanbanTask(
    val id: String,
    val label: String,
    val ticket: String? = null,
    val assigned: String? = null,
    val priority: KanbanPriority? = null,
    val rawPriority: String? = null,
    val icon: String? = null,
    val cssClass: String? = null,
)

data class KanbanColumn(
    val id: String,
    val label: String,
    val tasks: List<KanbanTask> = emptyList(),
    val ticket: String? = null,
    val icon: String? = null,
    val cssClass: String? = null,
)

data class KanbanDiagram(
    val columns: List<KanbanColumn>,
    val sectionWidth: Float = 210f,
    val ticketBaseUrl: String? = null,
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
) {
    fun column(idOrLabel: String): KanbanColumn? =
        columns.firstOrNull { it.id == idOrLabel || it.label == idOrLabel }

    fun task(idOrLabel: String): KanbanTask? =
        columns.asSequence().flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == idOrLabel || it.label == idOrLabel }

    fun resolveTicketUrl(ticket: String): String? {
        val base = ticketBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (base.contains("#TICKET#")) {
            base.replace("#TICKET#", ticket)
        } else {
            base + ticket
        }
    }
}
