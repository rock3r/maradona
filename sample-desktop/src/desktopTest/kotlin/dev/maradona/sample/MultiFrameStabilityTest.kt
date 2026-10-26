package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.maradona.render.MaradonaDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class MultiFrameStabilityTest {

    @Test
    fun all23FamiliesSettleWithinThreeFramesWithoutOscillationAcrossPhoneViewportsAndDensities() {
        val configs = listOf(
            Triple(1080, 1232, 2.8125f),
            Triple(1344, 1600, 3.375f),
        )
        for ((vw, vh, dens) in configs) {
            for ((name, source) in sampleExamples) {
                val natHistory = mutableListOf<IntSize>()
                val layoutSizes = mutableListOf<Pair<Int, Int>>()
                val scene = ImageComposeScene(width = vw, height = vh, density = Density(dens)) {
                    MaradonaDiagram(
                        source = source,
                        modifier = Modifier.fillMaxSize(),
                        onNaturalSize = { natHistory.add(it) },
                        onLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onSequenceLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onClassLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onStateLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onErLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onMindmapLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onGanttLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onPieLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onGitGraphLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onTimelineLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onJourneyLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onQuadrantLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onXyChartLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onKanbanLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onSankeyLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onRequirementLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onPacketLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onRadarLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onBlockLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onArchitectureLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onTreemapLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onC4Layout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                        onZenUmlLayout = { layoutSizes.add(it.width.toInt() to it.height.toInt()) },
                    )
                }
                try {
                    for (frame in 1..3) {
                        scene.render(frame * 16_000_000L)
                    }
                    val settledNatCount = natHistory.size
                    val settledLayoutCount = layoutSizes.size
                    val settledPng = scene.render(4 * 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                    for (frame in 5..8) {
                        val img = scene.render(frame * 16_000_000L)
                        if (frame == 8) {
                            val finalPng = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
                            assertTrue(
                                settledPng.contentEquals(finalPng),
                                "Expected pixel-identical settled frames 4 and 8 for $name at ${vw}x$vh d=$dens",
                            )
                        }
                    }
                    assertEquals(
                        1,
                        natHistory.size,
                        "Expected naturalSize to lock in 1 pass for $name at ${vw}x$vh d=$dens, got $natHistory",
                    )
                    assertEquals(
                        settledNatCount,
                        natHistory.size,
                        "naturalSize continued changing after frame 3 for $name at ${vw}x$vh d=$dens: $natHistory",
                    )
                    assertEquals(
                        settledLayoutCount,
                        layoutSizes.size,
                        "Layout continued recomputing after frame 3 for $name at ${vw}x$vh d=$dens: $layoutSizes",
                    )
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun switchingSourcesInSingleDiagramInstanceSettlesCleanlyAcrossAffectedAndControlFamilies() {
        val sequenceOfExamples = listOf(
            "Basic",
            "Mindmap",
            "Gantt",
            "Journey",
            "Kanban",
            "Radar",
            "ZenUML",
            "Sequence",
            "Class",
            "State",
            "ER",
            "C4",
            "Architecture",
            "Shapes",
        )
        var activeSource by mutableStateOf(sampleExamples.getValue("Basic"))
        val natHistory = mutableListOf<IntSize>()
        val layoutEvents = mutableListOf<String>()

        val scene = ImageComposeScene(width = 1080, height = 1232, density = Density(2.8125f)) {
            MaradonaDiagram(
                source = activeSource,
                modifier = Modifier.fillMaxSize(),
                onNaturalSize = { natHistory.add(it) },
                onLayout = { layoutEvents.add("flowchart:${it.width.toInt()}x${it.height.toInt()}") },
                onSequenceLayout = { layoutEvents.add("sequence:${it.width.toInt()}x${it.height.toInt()}") },
                onClassLayout = { layoutEvents.add("class:${it.width.toInt()}x${it.height.toInt()}") },
                onStateLayout = { layoutEvents.add("state:${it.width.toInt()}x${it.height.toInt()}") },
                onErLayout = { layoutEvents.add("er:${it.width.toInt()}x${it.height.toInt()}") },
                onMindmapLayout = { layoutEvents.add("mindmap:${it.width.toInt()}x${it.height.toInt()}") },
                onGanttLayout = { layoutEvents.add("gantt:${it.width.toInt()}x${it.height.toInt()}") },
                onJourneyLayout = { layoutEvents.add("journey:${it.width.toInt()}x${it.height.toInt()}") },
                onKanbanLayout = { layoutEvents.add("kanban:${it.width.toInt()}x${it.height.toInt()}") },
                onRadarLayout = { layoutEvents.add("radar:${it.width.toInt()}x${it.height.toInt()}") },
                onC4Layout = { layoutEvents.add("c4:${it.width.toInt()}x${it.height.toInt()}") },
                onArchitectureLayout = { layoutEvents.add("arch:${it.width.toInt()}x${it.height.toInt()}") },
                onZenUmlLayout = { layoutEvents.add("zenuml:${it.width.toInt()}x${it.height.toInt()}") },
            )
        }
        try {
            var frameClock = 1L
            for (name in sequenceOfExamples) {
                natHistory.clear()
                layoutEvents.clear()
                activeSource = sampleExamples.getValue(name)
                repeat(3) {
                    scene.render(frameClock++ * 16_000_000L)
                }
                val settledNat = natHistory.toList()
                val settledLayouts = layoutEvents.toList()
                val frame4Png = scene.render(frameClock++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                repeat(4) {
                    scene.render(frameClock++ * 16_000_000L)
                }
                val frame8Png = scene.render(frameClock++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes

                assertEquals(1, settledNat.size, "Expected 1 naturalSize callback after switching to $name, got $settledNat")
                assertEquals(settledNat, natHistory, "naturalSize oscillated after switching to $name: $natHistory")
                assertEquals(settledLayouts, layoutEvents, "Layout oscillated after switching to $name: $layoutEvents")
                assertTrue(frame4Png.contentEquals(frame8Png), "Rendered pixels changed between frame 4 and 8 for $name")
            }
        } finally {
            scene.close()
        }
    }
}
