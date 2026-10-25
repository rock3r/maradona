package dev.maradona.model

enum class GanttTaskStatus {
    Default,
    Done,
    Active,
}

enum class GanttWeekendStart {
    Saturday,
    Friday,
}

data class GanttTask(
    val id: String,
    val label: String,
    val section: String?,
    val status: GanttTaskStatus = GanttTaskStatus.Default,
    val isCritical: Boolean = false,
    val isMilestone: Boolean = false,
    val isVerticalMarker: Boolean = false,
    val startMs: Long,
    val endMs: Long,
    val action: NodeAction? = null,
)

data class GanttDiagram(
    val tasks: List<GanttTask>,
    val sections: List<String> = emptyList(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
    val dateFormat: String = "YYYY-MM-DD",
    val axisFormat: String? = null,
    val tickInterval: String? = null,
    val weekday: String? = null,
    val weekend: GanttWeekendStart = GanttWeekendStart.Saturday,
    val excludes: List<String> = emptyList(),
    val inclusiveEndDates: Boolean = false,
    val topAxis: Boolean = false,
    val compact: Boolean = false,
    val todayMarker: String? = null,
) {
    fun task(id: String): GanttTask? = tasks.firstOrNull { it.id == id }
}
