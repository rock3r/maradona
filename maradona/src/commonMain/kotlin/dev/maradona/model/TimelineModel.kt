package dev.maradona.model

enum class TimelineDirection {
    LR,
    TD,
}

data class TimelinePeriod(
    val id: String,
    val title: String,
    val section: String? = null,
    val events: List<String> = emptyList(),
)

data class TimelineDiagram(
    val periods: List<TimelinePeriod>,
    val sections: List<String> = emptyList(),
    val direction: TimelineDirection = TimelineDirection.LR,
    val title: String? = null,
    val disableMulticolor: Boolean = false,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
