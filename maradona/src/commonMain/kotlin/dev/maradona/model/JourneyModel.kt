package dev.maradona.model

enum class JourneyMood {
    Happy,
    Neutral,
    Sad,
}

data class JourneyTask(
    val id: String,
    val label: String,
    val score: Double,
    val actors: List<String> = emptyList(),
    val section: String? = null,
) {
    val mood: JourneyMood
        get() = when {
            score > 3.0 -> JourneyMood.Happy
            score < 3.0 -> JourneyMood.Sad
            else -> JourneyMood.Neutral
        }
}

data class JourneyDiagram(
    val tasks: List<JourneyTask>,
    val sections: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val title: String? = null,
    val accTitle: String? = null,
    val accDescr: String? = null,
)
