package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.maradona.export.MaradonaSvgExporter
import dev.maradona.layout.ArchitectureDiagramLayout
import dev.maradona.layout.BlockDiagramLayout
import dev.maradona.layout.C4DiagramLayout
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.DiagramLayout
import dev.maradona.layout.DiagramPoint
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.ErDiagramLayout
import dev.maradona.layout.GanttDiagramLayout
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.JourneyDiagramLayout
import dev.maradona.layout.KanbanDiagramLayout
import dev.maradona.layout.MindmapDiagramLayout
import dev.maradona.layout.PacketDiagramLayout
import dev.maradona.layout.PieDiagramLayout
import dev.maradona.layout.QuadrantDiagramLayout
import dev.maradona.layout.RadarDiagramLayout
import dev.maradona.layout.RequirementDiagramLayout
import dev.maradona.layout.SankeyDiagramLayout
import dev.maradona.layout.SequenceDiagramLayout
import dev.maradona.layout.StateDiagramLayout
import dev.maradona.layout.TimelineDiagramLayout
import dev.maradona.layout.TreemapDiagramLayout
import dev.maradona.layout.XyChartDiagramLayout
import dev.maradona.layout.ZenUmlDiagramLayout
import dev.maradona.model.BlockShape
import dev.maradona.model.StateKind
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/**
 * Renders every oracle chart (the .mmd files in oracle/charts) into
 * oracle/out/desktop, writing both 1x/0.1x/5x PNG captures and a per-chart
 * `<id>.layout.json` geometry dump across all 23 Mermaid diagram families.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OracleGoldenTest {

    private val oracleFontFamily: FontFamily by lazy {
        val typeface = requireNotNull(
            org.jetbrains.skia.FontMgr.default.makeFromFile(
                File("../oracle/fonts/Roboto-Regular.ttf").absolutePath,
            ),
        ) { "could not load oracle/fonts/Roboto-Regular.ttf" }
        FontFamily(androidx.compose.ui.text.platform.Typeface(typeface))
    }

    private val chartsDir = File("../oracle/charts")
    private val goldensDir = File("../oracle/goldens")
    private val outDir = File("../oracle/out/desktop")

    private class CapturedLayouts {
        var flowchart: DiagramLayout? = null
        var sequence: SequenceDiagramLayout? = null
        var clazz: ClassDiagramLayout? = null
        var state: StateDiagramLayout? = null
        var er: ErDiagramLayout? = null
        var mindmap: MindmapDiagramLayout? = null
        var gantt: GanttDiagramLayout? = null
        var pie: PieDiagramLayout? = null
        var gitGraph: GitGraphDiagramLayout? = null
        var timeline: TimelineDiagramLayout? = null
        var journey: JourneyDiagramLayout? = null
        var quadrant: QuadrantDiagramLayout? = null
        var xyChart: XyChartDiagramLayout? = null
        var kanban: KanbanDiagramLayout? = null
        var sankey: SankeyDiagramLayout? = null
        var requirement: RequirementDiagramLayout? = null
        var packet: PacketDiagramLayout? = null
        var radar: RadarDiagramLayout? = null
        var block: BlockDiagramLayout? = null
        var architecture: ArchitectureDiagramLayout? = null
        var treemap: TreemapDiagramLayout? = null
        var c4: C4DiagramLayout? = null
        var zenUml: ZenUmlDiagramLayout? = null
    }

    private fun renderPng(
        source: String,
        scale: Float,
        width: Int,
        height: Int,
        density: Float,
        pan: Offset? = null,
        naturalSize: ((IntSize) -> Unit)? = null,
        captured: CapturedLayouts? = null,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(density)) {
            MaradonaDiagram(
                source = source,
                theme = MaradonaTheme.light().copy(fontFamily = oracleFontFamily),
                modifier = Modifier.fillMaxSize(),
                zoomEnabled = false,
                autoDirection = false,
                initialScale = scale,
                initialPan = pan,
                onNaturalSize = { naturalSize?.invoke(it) },
                onLayout = { captured?.flowchart = it },
                onSequenceLayout = { captured?.sequence = it },
                onClassLayout = { captured?.clazz = it },
                onStateLayout = { captured?.state = it },
                onErLayout = { captured?.er = it },
                onMindmapLayout = { captured?.mindmap = it },
                onGanttLayout = { captured?.gantt = it },
                onPieLayout = { captured?.pie = it },
                onGitGraphLayout = { captured?.gitGraph = it },
                onTimelineLayout = { captured?.timeline = it },
                onJourneyLayout = { captured?.journey = it },
                onQuadrantLayout = { captured?.quadrant = it },
                onXyChartLayout = { captured?.xyChart = it },
                onKanbanLayout = { captured?.kanban = it },
                onSankeyLayout = { captured?.sankey = it },
                onRequirementLayout = { captured?.requirement = it },
                onPacketLayout = { captured?.packet = it },
                onRadarLayout = { captured?.radar = it },
                onBlockLayout = { captured?.block = it },
                onArchitectureLayout = { captured?.architecture = it },
                onTreemapLayout = { captured?.treemap = it },
                onC4Layout = { captured?.c4 = it },
                onZenUmlLayout = { captured?.zenUml = it },
            )
        }
        try {
            var image = scene.render(16_000_000L)
            repeat(2) { frame -> image = scene.render((frame + 2) * 16_000_000L) }
            val data = image.encodeToData(EncodedImageFormat.PNG)
            return requireNotNull(data) { "PNG encode failed" }.bytes
        } finally {
            scene.close()
        }
    }

    /** Content bbox (pixels differing from the top-left corner colour), in xywh. */
    private fun contentBounds(png: ByteArray): IntArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val bg = image.getRGB(0, 0)
        fun diffAt(x: Int, y: Int): Int {
            val p = image.getRGB(x, y)
            return maxOf(
                abs((p shr 16 and 0xFF) - (bg shr 16 and 0xFF)),
                abs((p shr 8 and 0xFF) - (bg shr 8 and 0xFF)),
                abs((p and 0xFF) - (bg and 0xFF)),
            )
        }
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (diffAt(x, y) > 28) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        require(maxX >= 0) { "no content found" }
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun rectStr(r: DiagramRect): String =
        "[${r.x}, ${r.y}, ${r.width}, ${r.height}]"

    private fun ptsStr(pts: List<DiagramPoint>): String =
        "[" + pts.joinToString(", ") { "[${it.x}, ${it.y}]" } + "]"

    private fun pairwiseOverlaps(rects: List<DiagramRect>, shrinkPx: Float = 1.5f): Int {
        var count = 0
        for (i in rects.indices) {
            val a = rects[i]
            val ax1 = a.x + shrinkPx
            val ay1 = a.y + shrinkPx
            val ax2 = a.right - shrinkPx
            val ay2 = a.bottom - shrinkPx
            if (ax2 <= ax1 || ay2 <= ay1) continue
            for (j in i + 1 until rects.size) {
                val b = rects[j]
                val bx1 = b.x + shrinkPx
                val by1 = b.y + shrinkPx
                val bx2 = b.right - shrinkPx
                val by2 = b.bottom - shrinkPx
                if (bx2 <= bx1 || by2 <= by1) continue
                if (ax1 < bx2 && ax2 > bx1 && ay1 < by2 && ay2 > by1) {
                    count++
                }
            }
        }
        return count
    }

    private data class InvariantSpec(
        val name: String,
        val pass: Boolean,
        val actual: String,
        val expected: String,
    )

    private data class NodeEntry(
        val id: String,
        val label: String,
        val rect: DiagramRect,
        val kind: String = "node",
        val checkOverlap: Boolean = true,
    )

    private data class EdgeEntry(
        val from: String,
        val to: String,
        val label: String? = null,
        val points: List<DiagramPoint>,
        val labelRect: DiagramRect? = null,
    )

    private fun buildFamilyLayoutJson(
        family: String,
        width: Float,
        height: Float,
        density: Float,
        nodes: List<NodeEntry>,
        edges: List<EdgeEntry>,
        invariants: List<InvariantSpec>,
    ): String {
        val nonOverlapRects = nodes.filter { it.checkOverlap }.map { it.rect }
        val overlapCount = pairwiseOverlaps(nonOverlapRects)
        val outOfBoundsCount = nodes.count { n ->
            val r = n.rect
            r.width <= 0f || r.height <= 0f ||
                r.x < -2f || r.y < -2f ||
                r.right > width + 4f || r.bottom > height + 4f
        }
        val allInvariants = buildList {
            add(
                InvariantSpec(
                    name = "canvas_bounds",
                    pass = width > 0f && height > 0f && outOfBoundsCount == 0,
                    actual = "${width.roundToInt()}x${height.roundToInt()} (oob=$outOfBoundsCount)",
                    expected = "all ${nodes.size} items inside canvas",
                ),
            )
            add(
                InvariantSpec(
                    name = "sibling_non_overlap",
                    pass = overlapCount == 0,
                    actual = "overlaps=$overlapCount",
                    expected = "0 overlaps across ${nonOverlapRects.size} sibling items",
                ),
            )
            addAll(invariants)
        }
        val nodesJson = nodes.joinToString(", ") { n ->
            """{"id": "${esc(n.id)}", "label": "${esc(n.label)}", "kind": "${esc(n.kind)}", "rect": ${rectStr(n.rect)}, "check_overlap": ${n.checkOverlap}}"""
        }
        val edgesJson = edges.joinToString(", ") { e ->
            val lbl = e.label?.let { "\"${esc(it)}\"" } ?: "null"
            val lr = e.labelRect?.let(::rectStr) ?: "null"
            """{"from": "${esc(e.from)}", "to": "${esc(e.to)}", "label": $lbl, "points": ${ptsStr(e.points)}, "label_rect": $lr}"""
        }
        val invJson = allInvariants.joinToString(", ") { inv ->
            """{"name": "${esc(inv.name)}", "pass": ${inv.pass}, "actual": "${esc(inv.actual)}", "expected": "${esc(inv.expected)}"}"""
        }
        return """
            {
              "family": "$family",
              "width": $width,
              "height": $height,
              "density": $density,
              "nodes": [$nodesJson],
              "edges": [$edgesJson],
              "invariants": [$invJson]
            }
        """.trimIndent() + "\n"
    }

    private fun flowchartLayoutJson(layout: DiagramLayout, density: Float): String {
        val nodes = layout.nodes.joinToString(", ") { positioned ->
            """{"id": "${esc(positioned.node.id)}", "label": "${esc(positioned.node.label)}", "rect": ${rectStr(positioned.rect)}}"""
        }
        val edges = layout.edges.joinToString(", ") { positioned ->
            val points = positioned.points.joinToString(", ") { "[${it.x}, ${it.y}]" }
            val label = positioned.labelRect?.let(::rectStr) ?: "null"
            """{"from": "${esc(positioned.edge.from)}", "to": "${esc(positioned.edge.to)}", "label": ${positioned.edge.label?.let { "\"${esc(it)}\"" } ?: "null"}, "points": [$points], "label_rect": $label}"""
        }
        val subgraphs = layout.subgraphs.joinToString(", ") { positioned ->
            """{"id": "${esc(positioned.subgraph.id)}", "label": "${esc(positioned.subgraph.label)}", "rect": ${rectStr(positioned.rect)}}"""
        }
        return """
            {
              "family": "flowchart",
              "width": ${layout.width},
              "height": ${layout.height},
              "direction": "${layout.direction}",
              "density": $density,
              "nodes": [$nodes],
              "edges": [$edges],
              "subgraphs": [$subgraphs]
            }
        """.trimIndent() + "\n"
    }

    private fun serializeCapturedLayout(
        source: String,
        captured: CapturedLayouts,
        density: Float,
    ): String {
        val parsed = MaradonaParser.parse(source)
        captured.flowchart?.let { return flowchartLayoutJson(it, density) }

        captured.sequence?.let { l ->
            val pNodes = l.participants.map { pp ->
                NodeEntry(pp.participant.id, pp.participant.label, pp.topRect, kind = "participant")
            }
            val msgEdges = l.messages.mapIndexed { idx, m ->
                EdgeEntry(
                    from = m.statement.from,
                    to = m.statement.to,
                    label = m.statement.text.ifBlank { "msg_$idx" },
                    points = listOf(DiagramPoint(m.startX, m.lineY), DiagramPoint(m.stopX, m.lineY)),
                    labelRect = m.labelRect,
                )
            }
            val orderedLr = l.participants.zipWithNext().all { (a, b) -> a.topRect.right <= b.topRect.x + 2f }
            val lifelinesOk = l.participants.all { p ->
                abs(p.lifelineX - p.topRect.centerX) <= 1.5f && p.lifelineBottomY > p.lifelineTopY
            }
            val messagesMonotonicY = l.messages.zipWithNext().all { (a, b) -> a.lineY <= b.lineY + 1.5f }
            return buildFamilyLayoutJson(
                family = "sequence",
                width = l.width,
                height = l.height,
                density = density,
                nodes = pNodes,
                edges = msgEdges,
                invariants = listOf(
                    InvariantSpec("participants_ordered_lr", orderedLr, "${l.participants.size} lifelines", "left-to-right participant headers"),
                    InvariantSpec("lifelines_centered", lifelinesOk, "aligned=${lifelinesOk}", "lifelineX == topRect.centerX"),
                    InvariantSpec("messages_monotonic_y", messagesMonotonicY, "${l.messages.size} messages", "strictly increasing message Y"),
                ),
            )
        }

        captured.clazz?.let { l ->
            val cNodes = l.classes.map { pc ->
                NodeEntry(pc.node.id, pc.node.displayTitle, pc.rect, kind = "class")
            }
            val relEdges = l.relations.map { pr ->
                EdgeEntry(pr.relation.from, pr.relation.to, pr.relation.label, pr.points, pr.labelRect)
            }
            val compartmentsOk = l.classes.all { pc ->
                abs((pc.headerHeight + pc.attributesHeight + pc.methodsHeight) - pc.rect.height) <= 2f
            }
            val relationsOk = l.relations.all { it.points.size >= 2 }
            return buildFamilyLayoutJson(
                family = "class",
                width = l.width,
                height = l.height,
                density = density,
                nodes = cNodes,
                edges = relEdges,
                invariants = listOf(
                    InvariantSpec("compartment_heights_sum", compartmentsOk, "${l.classes.size} classes", "header+attr+method == height"),
                    InvariantSpec("relations_routed", relationsOk, "${l.relations.size} relations", ">= 2 route points per relation"),
                ),
            )
        }

        captured.state?.let { l ->
            val model = (parsed as MaradonaDiagramModel.StateModel).diagram
            val compositeIds = model.states.filter { s ->
                s.kind == StateKind.Composite || model.states.any { it.parentId == s.id }
            }.map { it.id }.toSet()
            val byId = l.states.associateBy { it.state.id }
            val sNodes = l.states.map { ps ->
                NodeEntry(
                    id = ps.state.id,
                    label = ps.state.label,
                    rect = ps.rect,
                    kind = ps.state.kind.name.lowercase(),
                    checkOverlap = ps.state.id !in compositeIds,
                )
            }
            val trEdges = l.transitions.map { pt ->
                EdgeEntry(pt.transition.from, pt.transition.to, pt.transition.label, pt.points, pt.labelRect)
            }
            val compositesEnclose = l.states.all { ps ->
                val pid = ps.state.parentId ?: return@all true
                val parent = byId[pid]?.rect ?: return@all true
                ps.rect.x >= parent.x - 2f && ps.rect.y >= parent.y - 2f &&
                    ps.rect.right <= parent.right + 2f && ps.rect.bottom <= parent.bottom + 2f
            }
            val transitionsOk = l.transitions.all { it.points.size >= 2 }
            return buildFamilyLayoutJson(
                family = "state",
                width = l.width,
                height = l.height,
                density = density,
                nodes = sNodes,
                edges = trEdges,
                invariants = listOf(
                    InvariantSpec("composites_enclose_children", compositesEnclose, "${compositeIds.size} composites", "child states inside parent composite"),
                    InvariantSpec("transitions_routed", transitionsOk, "${l.transitions.size} transitions", ">= 2 points per transition"),
                ),
            )
        }

        captured.er?.let { l ->
            val eNodes = l.entities.map { pe ->
                NodeEntry(pe.entity.id, pe.entity.label, pe.rect, kind = "entity")
            }
            val rEdges = l.relationships.map { pr ->
                EdgeEntry(pr.relationship.from, pr.relationship.to, pr.relationship.label, pr.points, pr.labelRect)
            }
            val rowsSumOk = l.entities.all { pe ->
                pe.rowHeights.isEmpty() || abs((pe.headerHeight + pe.rowHeights.sum()) - pe.rect.height) <= 2f
            }
            val relsOk = l.relationships.all { it.points.size >= 2 }
            return buildFamilyLayoutJson(
                family = "er",
                width = l.width,
                height = l.height,
                density = density,
                nodes = eNodes,
                edges = rEdges,
                invariants = listOf(
                    InvariantSpec("entity_row_heights_sum", rowsSumOk, "${l.entities.size} entities", "header + rows == entity height"),
                    InvariantSpec("relationships_routed", relsOk, "${l.relationships.size} relationships", ">= 2 points per ER relationship"),
                ),
            )
        }

        captured.mindmap?.let { l ->
            val mNodes = l.nodes.map { pn ->
                NodeEntry(pn.node.id, pn.node.label, pn.rect, kind = "depth_${pn.depth}")
            }
            val bEdges = l.edges.map { pb ->
                EdgeEntry(
                    from = pb.fromId,
                    to = pb.toId,
                    points = pb.points,
                )
            }
            val childCount = l.nodes.count { it.depth > 0 }
            val branchesMatch = l.edges.size == childCount
            return buildFamilyLayoutJson(
                family = "mindmap",
                width = l.width,
                height = l.height,
                density = density,
                nodes = mNodes,
                edges = bEdges,
                invariants = listOf(
                    InvariantSpec("branch_count_matches_children", branchesMatch, "branches=${l.edges.size}, children=$childCount", "1 Bezier branch per non-root node"),
                ),
            )
        }

        captured.gantt?.let { l ->
            val tNodes = l.tasks.map { pt ->
                NodeEntry(pt.task.id, pt.task.label, pt.barRect, kind = if (pt.task.isMilestone) "milestone" else "task")
            }
            val inPlotX = l.tasks.all { pt ->
                pt.barRect.x >= l.plotRect.x - 2f && pt.barRect.right <= l.plotRect.right + 2f
            }
            val rowsOrderedY = l.tasks.zipWithNext().all { (a, b) -> a.barRect.y <= b.barRect.y + 1.5f }
            return buildFamilyLayoutJson(
                family = "gantt",
                width = l.width,
                height = l.height,
                density = density,
                nodes = tNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("tasks_within_plot_x", inPlotX, "${l.tasks.size} tasks", "barRect within plotRect.x..right"),
                    InvariantSpec("task_rows_monotonic_y", rowsOrderedY, "rows=${l.tasks.size}", "monotonic vertical task rows"),
                ),
            )
        }

        captured.pie?.let { l ->
            val lNodes = l.legendItems.map { li ->
                val unionRect = DiagramRect(
                    x = li.swatchRect.x,
                    y = minOf(li.swatchRect.y, li.labelRect.y),
                    width = li.labelRect.right - li.swatchRect.x,
                    height = maxOf(li.swatchRect.bottom, li.labelRect.bottom) - minOf(li.swatchRect.y, li.labelRect.y),
                )
                NodeEntry(li.slice.label, li.displayText, unionRect, kind = "legend_item")
            }
            val sweepSum = l.slices.sumOf { it.sweepAngleDeg.toDouble() }
            val sum360Ok = abs(sweepSum - 360.0) <= 0.5
            val legendRightOk = l.legendItems.all { it.swatchRect.x >= l.center.x + l.outerRadius - 2f }
            return buildFamilyLayoutJson(
                family = "pie",
                width = l.width,
                height = l.height,
                density = density,
                nodes = lNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("slice_angles_sum_360", sum360Ok, "sum=${"%.1f".format(sweepSum)}deg", "360.0deg"),
                    InvariantSpec("legend_right_of_pie", legendRightOk, "items=${l.legendItems.size}", "legend swatchX >= pie right"),
                ),
            )
        }

        captured.gitGraph?.let { l ->
            val cNodes = l.commits.map { pc ->
                NodeEntry(pc.commit.id, pc.commit.id, pc.nodeRect, kind = pc.commit.type.name.lowercase())
            }
            val gEdges = l.edges.map { ge ->
                EdgeEntry(ge.fromCommitId, ge.toCommitId, points = ge.points)
            }
            val byId = l.commits.associateBy { it.commit.id }
            val edgesConnectCenters = l.edges.all { ge ->
                val a = byId[ge.fromCommitId]?.center ?: return@all false
                val b = byId[ge.toCommitId]?.center ?: return@all false
                val p0 = ge.points.first()
                val p1 = ge.points.last()
                hypot(p0.x - a.x, p0.y - a.y) <= 2f && hypot(p1.x - b.x, p1.y - b.y) <= 2f
            }
            return buildFamilyLayoutJson(
                family = "gitgraph",
                width = l.width,
                height = l.height,
                density = density,
                nodes = cNodes,
                edges = gEdges,
                invariants = listOf(
                    InvariantSpec("edges_connect_commit_centers", edgesConnectCenters, "${l.edges.size} git edges", "edge endpoints at commit centers"),
                ),
            )
        }

        captured.timeline?.let { l ->
            val pNodes = buildList {
                for (pp in l.periods) {
                    add(NodeEntry(pp.period.id, pp.period.title, pp.rect, kind = "period"))
                    for ((idx, ev) in pp.events.withIndex()) {
                        add(NodeEntry("${pp.period.id}_ev_$idx", ev.text, ev.rect, kind = "event"))
                    }
                }
            }
            val periodsLr = l.periods.zipWithNext().all { (a, b) -> a.rect.right <= b.rect.x + 2f }
            val eventsBelow = l.periods.all { pp ->
                pp.events.all { ev -> ev.rect.y >= pp.rect.bottom - 2f }
            }
            return buildFamilyLayoutJson(
                family = "timeline",
                width = l.width,
                height = l.height,
                density = density,
                nodes = pNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("periods_ordered_lr", periodsLr, "${l.periods.size} periods", "left-to-right periods"),
                    InvariantSpec("events_below_periods", eventsBelow, "events stacked", "event.y >= period.bottom"),
                ),
            )
        }

        captured.journey?.let { l ->
            val jNodes = l.tasks.map { pt ->
                NodeEntry(pt.task.id, pt.task.label, pt.cardRect, kind = "task")
            }
            val tasksLr = l.tasks.zipWithNext().all { (a, b) -> a.cardRect.right <= b.cardRect.x + 2f }
            val facesBelow = l.tasks.all { pt -> pt.faceCenter.y > pt.cardRect.bottom }
            return buildFamilyLayoutJson(
                family = "journey",
                width = l.width,
                height = l.height,
                density = density,
                nodes = jNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("tasks_ordered_lr", tasksLr, "${l.tasks.size} tasks", "left-to-right task cards"),
                    InvariantSpec("mood_faces_below_cards", facesBelow, "${l.tasks.size} faces", "faceCenter.y > cardRect.bottom"),
                ),
            )
        }

        captured.quadrant?.let { l ->
            val qNodes = l.points.map { pt ->
                val r = DiagramRect(
                    x = minOf(pt.center.x - pt.radius, pt.labelRect.x),
                    y = minOf(pt.center.y - pt.radius, pt.labelRect.y),
                    width = maxOf(pt.center.x + pt.radius, pt.labelRect.right) - minOf(pt.center.x - pt.radius, pt.labelRect.x),
                    height = maxOf(pt.center.y + pt.radius, pt.labelRect.bottom) - minOf(pt.center.y - pt.radius, pt.labelRect.y),
                )
                NodeEntry(pt.point.id, pt.point.label, r, kind = "point", checkOverlap = false)
            }
            val fourQuadrants = l.quadrants.size == 4
            val coordsAccurate = l.points.all { pt ->
                val ex = l.gridRect.x + pt.point.x * l.gridRect.width
                val ey = l.gridRect.bottom - pt.point.y * l.gridRect.height
                abs(pt.center.x - ex) <= 2f && abs(pt.center.y - ey) <= 2f
            }
            return buildFamilyLayoutJson(
                family = "quadrant",
                width = l.width,
                height = l.height,
                density = density,
                nodes = qNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("four_quadrants_tile_plot", fourQuadrants, "quadrants=${l.quadrants.size}", "4 quadrants"),
                    InvariantSpec("normalized_coords_exact", coordsAccurate, "${l.points.size} points", "pixel center matches normalized (x,y)"),
                ),
            )
        }

        captured.xyChart?.let { l ->
            val barNodes = l.bars.mapIndexed { idx, b ->
                NodeEntry("bar_${b.seriesIndex}_${b.pointIndex}_$idx", "${b.value}", b.rect, kind = "bar", checkOverlap = false)
            }
            val lineEdges = l.lines.map { ls ->
                EdgeEntry(
                    from = ls.series.id,
                    to = ls.series.id,
                    label = ls.series.title,
                    points = ls.points.map { it.position },
                )
            }
            val inPlot = l.bars.all { b ->
                b.rect.x >= l.plotRect.x - 2f && b.rect.right <= l.plotRect.right + 2f &&
                    b.rect.y >= l.plotRect.y - 2f && b.rect.bottom <= l.plotRect.bottom + 2f
            } && l.lines.all { ls ->
                ls.points.all { pt ->
                    pt.position.x in (l.plotRect.x - 2f)..(l.plotRect.right + 2f) &&
                        pt.position.y in (l.plotRect.y - 2f)..(l.plotRect.bottom + 2f)
                }
            }
            return buildFamilyLayoutJson(
                family = "xychart",
                width = l.width,
                height = l.height,
                density = density,
                nodes = barNodes.ifEmpty {
                    l.lines.flatMap { ls ->
                        ls.points.mapIndexed { idx, pt ->
                            NodeEntry("pt_${ls.seriesIndex}_$idx", "${pt.value}", DiagramRect(pt.position.x - 4f, pt.position.y - 4f, 8f, 8f), kind = "line_point", checkOverlap = false)
                        }
                    }
                },
                edges = lineEdges,
                invariants = listOf(
                    InvariantSpec("series_within_plot_rect", inPlot, "bars=${l.bars.size}, lines=${l.lines.size}", "all bars and line points inside plotRect"),
                ),
            )
        }

        captured.kanban?.let { l ->
            val kNodes = buildList {
                for (col in l.columns) {
                    add(NodeEntry(col.column.id, col.column.label, col.rect, kind = "column", checkOverlap = false))
                    for (pt in col.tasks) {
                        add(NodeEntry(pt.task.id, pt.task.label, pt.cardRect, kind = "card", checkOverlap = true))
                    }
                }
            }
            val colsOrdered = l.columns.zipWithNext().all { (a, b) -> a.rect.right <= b.rect.x + 2f }
            val cardsInCols = l.columns.all { col ->
                col.tasks.all { pt ->
                    pt.cardRect.x >= col.rect.x - 1f && pt.cardRect.right <= col.rect.right + 1f &&
                        pt.cardRect.y >= col.rect.y - 1f && pt.cardRect.bottom <= col.rect.bottom + 1f
                }
            }
            return buildFamilyLayoutJson(
                family = "kanban",
                width = l.width,
                height = l.height,
                density = density,
                nodes = kNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("columns_ordered_lr", colsOrdered, "${l.columns.size} columns", "non-overlapping left-to-right columns"),
                    InvariantSpec("cards_enclosed_by_columns", cardsInCols, "enclosed=$cardsInCols", "every card inside its column rect"),
                ),
            )
        }

        captured.sankey?.let { l ->
            val sNodes = l.nodes.map { pn ->
                NodeEntry(pn.node.id, pn.labelText, pn.rect, kind = "sankey_node")
            }
            val sEdges = l.links.map { pl ->
                EdgeEntry(
                    from = l.nodes[pl.sourceNodeIndex].node.id,
                    to = l.nodes[pl.targetNodeIndex].node.id,
                    points = listOf(pl.start, pl.control1, pl.control2, pl.end),
                )
            }
            val forwardFlow = l.links.all { it.start.x < it.end.x }
            val endpointsOnNodes = l.links.all { pl ->
                val src = l.nodes[pl.sourceNodeIndex].rect
                val dst = l.nodes[pl.targetNodeIndex].rect
                abs(pl.start.x - src.right) <= 2f && abs(pl.end.x - dst.x) <= 2f
            }
            return buildFamilyLayoutJson(
                family = "sankey",
                width = l.width,
                height = l.height,
                density = density,
                nodes = sNodes,
                edges = sEdges,
                invariants = listOf(
                    InvariantSpec("links_flow_forward_x", forwardFlow, "${l.links.size} links", "start.x < end.x for all links"),
                    InvariantSpec("links_attach_node_bars", endpointsOnNodes, "attached=$endpointsOnNodes", "link start/end on source.right / target.left"),
                ),
            )
        }

        captured.requirement?.let { l ->
            val rNodes = l.items.map { pi ->
                NodeEntry(pi.item.name, pi.item.name, pi.rect, kind = pi.item.stereotype)
            }
            val rEdges = l.relations.map { pr ->
                EdgeEntry(pr.relation.source, pr.relation.target, pr.relation.label, pr.points, pr.labelRect)
            }
            val headersOk = l.items.all { it.headerHeight > 0f && it.headerHeight <= it.rect.height + 1f }
            val relsOk = l.relations.all { it.points.size >= 2 }
            return buildFamilyLayoutJson(
                family = "requirement",
                width = l.width,
                height = l.height,
                density = density,
                nodes = rNodes,
                edges = rEdges,
                invariants = listOf(
                    InvariantSpec("item_headers_valid", headersOk, "${l.items.size} items", "0 < headerHeight <= rect.height"),
                    InvariantSpec("relations_routed", relsOk, "${l.relations.size} relations", ">= 2 route points per relation"),
                ),
            )
        }

        captured.packet?.let { l ->
            val pNodes = l.blocks.map { pb ->
                NodeEntry("${pb.block.start}-${pb.block.end}", pb.block.label, pb.rect, kind = "bits_${pb.block.bits}")
            }
            val byRow = l.blocks.groupBy { it.rowIndex }
            val rowsContiguous = byRow.values.all { rowBlocks ->
                val sorted = rowBlocks.sortedBy { it.colOffset }
                sorted.zipWithNext().all { (a, b) ->
                    a.colOffset + a.block.bits == b.colOffset &&
                        b.rect.x >= a.rect.right - 1f &&
                        b.rect.x - a.rect.right <= 12f * density &&
                        (a.block.bits != b.block.bits || abs(a.rect.width - b.rect.width) <= 2f)
                }
            }
            return buildFamilyLayoutJson(
                family = "packet",
                width = l.width,
                height = l.height,
                density = density,
                nodes = pNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("word_rows_contiguous", rowsContiguous, "${byRow.size} word rows", "contiguous bit columns and proportional widths"),
                ),
            )
        }

        captured.radar?.let { l ->
            val aNodes = l.axes.map { pa ->
                NodeEntry(pa.axis.id, pa.axis.label, pa.labelRect, kind = "axis_label")
            }
            val cEdges = l.curves.map { pc ->
                EdgeEntry(pc.curve.id, pc.curve.id, pc.curve.label, pc.points)
            }
            val spokesEqRadius = l.axes.all { pa ->
                abs(hypot(pa.lineEnd.x - l.center.x, pa.lineEnd.y - l.center.y) - l.radius) <= 2f
            }
            val curvesWithinRadius = l.curves.all { pc ->
                pc.points.size == l.axes.size && pc.points.all { pt ->
                    hypot(pt.x - l.center.x, pt.y - l.center.y) <= l.radius + 2f
                }
            }
            return buildFamilyLayoutJson(
                family = "radar",
                width = l.width,
                height = l.height,
                density = density,
                nodes = aNodes,
                edges = cEdges,
                invariants = listOf(
                    InvariantSpec("spokes_equal_radius", spokesEqRadius, "${l.axes.size} axes", "spoke length == radius"),
                    InvariantSpec("curves_within_radius", curvesWithinRadius, "${l.curves.size} curves", "all vertices <= radius from center"),
                ),
            )
        }

        captured.block?.let { l ->
            val bNodes = l.blocks.filter { it.item.shape != BlockShape.Space }.map { pb ->
                NodeEntry(
                    id = pb.item.id,
                    label = pb.item.label.ifBlank { pb.item.id },
                    rect = pb.rect,
                    kind = pb.item.shape.name.lowercase(),
                    checkOverlap = !pb.isCompositeContainer,
                )
            }
            val bEdges = l.edges.map { pe ->
                EdgeEntry(pe.edge.from, pe.edge.to, pe.edge.label, pe.points, pe.labelRect)
            }
            return buildFamilyLayoutJson(
                family = "block",
                width = l.width,
                height = l.height,
                density = density,
                nodes = bNodes,
                edges = bEdges,
                invariants = listOf(
                    InvariantSpec("blocks_positioned", bNodes.isNotEmpty(), "${bNodes.size} blocks", "non-empty grid block layout"),
                ),
            )
        }

        captured.architecture?.let { l ->
            val grpById = l.groups.associateBy { it.group.id }
            val aNodes = l.services.map { ps ->
                NodeEntry(ps.service.id, ps.service.title ?: ps.service.id, ps.iconRect, kind = "service")
            }
            val aEdges = l.edges.map { pe ->
                EdgeEntry(pe.edge.lhsId, pe.edge.rhsId, pe.edge.title, pe.points, pe.labelRect)
            }
            val groupsEnclose = l.services.all { ps ->
                val gid = ps.service.parentId ?: return@all true
                val grp = grpById[gid]?.rect ?: return@all true
                ps.iconRect.x >= grp.x - 2f && ps.iconRect.y >= grp.y - 2f &&
                    ps.iconRect.right <= grp.right + 2f && ps.iconRect.bottom <= grp.bottom + 2f
            }
            val edgesRouted = l.edges.all { it.points.size >= 2 }
            return buildFamilyLayoutJson(
                family = "architecture",
                width = l.width,
                height = l.height,
                density = density,
                nodes = aNodes,
                edges = aEdges,
                invariants = listOf(
                    InvariantSpec("groups_enclose_services", groupsEnclose, "${l.groups.size} groups, ${l.services.size} services", "member services inside group rect"),
                    InvariantSpec("port_edges_routed", edgesRouted, "${l.edges.size} edges", ">= 2 points per port edge"),
                ),
            )
        }

        captured.treemap?.let { l ->
            val tNodes = l.leaves.map { lf ->
                NodeEntry(lf.node.name, "${lf.node.name} (${lf.formattedValue})", lf.rect, kind = "leaf")
            }
            val maxLeaf = l.leaves.maxByOrNull { it.value }
            val minLeaf = l.leaves.minByOrNull { it.value }
            val areaMonotonic = if (maxLeaf != null && minLeaf != null && maxLeaf.value > minLeaf.value) {
                (maxLeaf.rect.width * maxLeaf.rect.height) > (minLeaf.rect.width * minLeaf.rect.height)
            } else {
                true
            }
            return buildFamilyLayoutJson(
                family = "treemap",
                width = l.width,
                height = l.height,
                density = density,
                nodes = tNodes,
                edges = emptyList(),
                invariants = listOf(
                    InvariantSpec("area_monotonic_with_value", areaMonotonic, "${l.leaves.size} leaves", "larger value -> larger tile area"),
                ),
            )
        }

        captured.c4?.let { l ->
            val bndById = l.boundaries.associateBy { it.boundary.id }
            val cNodes = l.elements.map { pe ->
                NodeEntry(pe.element.id, pe.element.label, pe.rect, kind = pe.element.kind.name.lowercase())
            }
            val cEdges = l.relationships.map { pr ->
                EdgeEntry(pr.relationship.from, pr.relationship.to, pr.relationship.label, pr.points, pr.labelRect)
            }
            val boundariesEnclose = l.elements.all { pe ->
                val bid = pe.element.parentBoundaryId ?: return@all true
                val bnd = bndById[bid]?.rect ?: return@all true
                pe.rect.x >= bnd.x - 2f && pe.rect.y >= bnd.y - 2f &&
                    pe.rect.right <= bnd.right + 2f && pe.rect.bottom <= bnd.bottom + 2f
            }
            return buildFamilyLayoutJson(
                family = "c4",
                width = l.width,
                height = l.height,
                density = density,
                nodes = cNodes,
                edges = cEdges,
                invariants = listOf(
                    InvariantSpec("boundaries_enclose_elements", boundariesEnclose, "${l.boundaries.size} boundaries", "child C4 cards inside boundary rect"),
                ),
            )
        }

        captured.zenUml?.let { l ->
            val zNodes = l.participants.map { pp ->
                NodeEntry(pp.participant.id, pp.participant.label, pp.topRect, kind = "participant")
            }
            val zEdges = l.messages.map { pm ->
                EdgeEntry(
                    from = pm.fromId,
                    to = pm.toId,
                    label = pm.displayText,
                    points = listOf(DiagramPoint(pm.startX, pm.y), DiagramPoint(pm.endX, pm.y)),
                    labelRect = pm.labelRect,
                )
            }
            val orderedLr = l.participants.zipWithNext().all { (a, b) -> a.topRect.right <= b.topRect.x + 2f }
            val messagesMonotonic = l.messages.zipWithNext().all { (a, b) -> a.y <= b.y + 1.5f }
            return buildFamilyLayoutJson(
                family = "zenuml",
                width = l.width,
                height = l.height,
                density = density,
                nodes = zNodes,
                edges = zEdges,
                invariants = listOf(
                    InvariantSpec("participants_ordered_lr", orderedLr, "${l.participants.size} participants", "left-to-right participant headers"),
                    InvariantSpec("messages_monotonic_y", messagesMonotonic, "${l.messages.size} messages", "monotonic vertical message order"),
                ),
            )
        }

        error("No layout callback fired for chart")
    }

    /** Pan fractions that cover `scaled` px in `view` px windows without gaps. */
    private fun coverFractions(scaled: Int, view: Int): List<Float> {
        val n = if (scaled <= view) 1 else maxOf(2, kotlin.math.ceil(scaled / view.toDouble()).toInt())
        return if (n == 1) listOf(0.5f) else (0 until n).map { it.toFloat() / (n - 1) }
    }

    @Test
    fun renderOracleCharts() {
        val allCharts = chartsDir.listFiles { f -> f.extension == "mmd" }!!.sortedBy { it.name }
        require(allCharts.isNotEmpty()) { "no charts in ${chartsDir.absolutePath}" }
        val filterIds = (System.getenv("ORACLE_CHARTS") ?: System.getProperty("oracle.charts"))
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        if (filterIds.isNullOrEmpty()) {
            outDir.deleteRecursively()
        }
        outDir.mkdirs()
        val manifest = StringBuilder("{\n  \"charts\": {\n")
        for (chart in allCharts) {
            val id = chart.nameWithoutExtension
            val source = chart.readText()
            val gold = contentBounds(File(goldensDir, "$id.png").readBytes())

            var natW = 0
            var natH = 0
            renderPng(source, 1f, 400, 400, 1f, naturalSize = { size ->
                natW = size.width
                natH = size.height
            })
            require(natW > 0 && natH > 0) { "$id has no measurable layout" }

            val roughDensity = minOf(gold[2].toFloat() / natW, gold[3].toFloat() / natH)
            val roughW = (natW * roughDensity).roundToInt().coerceAtLeast(16)
            val roughH = (natH * roughDensity).roundToInt().coerceAtLeast(16)
            val roughInk = contentBounds(renderPng(source, 1f, roughW, roughH, roughDensity))
            val density = roughDensity * minOf(
                gold[2].toFloat() / roughInk[2],
                gold[3].toFloat() / roughInk[3],
            )
            val layoutW = (natW * density).roundToInt()
            val layoutH = (natH * density).roundToInt()
            val margin = 32
            val canvasW = maxOf(gold[2], layoutW) + margin
            val canvasH = maxOf(gold[3], layoutH) + margin

            val xs = coverFractions(canvasW * 5, canvasW)
            val ys = coverFractions(canvasH * 5, canvasH)
            val shouldRender = filterIds.isNullOrEmpty() || id in filterIds || !File(outDir, "$id-100.png").exists()
            if (shouldRender) {
                val captured = CapturedLayouts()
                File(outDir, "$id-100.png").writeBytes(
                    renderPng(source, 1f, canvasW, canvasH, density, captured = captured),
                )
                File(outDir, "$id.layout.json").writeText(
                    serializeCapturedLayout(source, captured, density),
                )
                File(outDir, "$id-min.png").writeBytes(renderPng(source, 0.1f, canvasW, canvasH, density))
                for ((i, fy) in ys.withIndex()) {
                    for ((j, fx) in xs.withIndex()) {
                        File(outDir, "$id-max-$i-$j.png").writeBytes(
                            renderPng(source, 5f, canvasW, canvasH, density, pan = Offset(fx, fy)),
                        )
                    }
                }
            }
            val tiles = buildList {
                for (fy in ys) {
                    for (fx in xs) {
                        add("[$fx, $fy]")
                    }
                }
            }

            val ours100 = contentBounds(File(outDir, "$id-100.png").readBytes())
            manifest.append(
                """    "$id": {"canvasW": $layoutW, "canvasH": $layoutH, """ +
                    """"viewportW": $canvasW, "viewportH": $canvasH, """ +
                    """"contentW": ${ours100[2]}, "contentH": ${ours100[3]}, """ +
                    """"tiles": {"100": [[0.5, 0.5]], "min": [[0.5, 0.5]], "max": [${tiles.joinToString(", ")}]}},""",
            ).append('\n')
            println(
                "oracle: $id layout=${layoutW}x$layoutH gold=${gold[2]}x${gold[3]} " +
                    "content=${ours100[2]}x${ours100[3]} viewport=${canvasW}x$canvasH",
            )
        }
        manifest.setLength(manifest.length - 2)
        manifest.append("\n  }\n}\n")
        File(outDir, "manifest.json").writeText(manifest.toString())
    }

    @Test
    fun allOracleChartsExportValidSvgAndCoverAll23Families() {
        val allCharts = chartsDir.listFiles { f -> f.extension == "mmd" }!!.sortedBy { it.name }
        kotlin.test.assertEquals(71, allCharts.size, "Expected 71 oracle fixtures across 23 families")
        val countsByFamily = mutableMapOf<String, Int>()
        for (chart in allCharts) {
            val source = chart.readText()
            val model = MaradonaParser.parse(source)
            val familyKey = model::class.simpleName ?: "Unknown"
            countsByFamily[familyKey] = (countsByFamily[familyKey] ?: 0) + 1
            val svg = MaradonaSvgExporter.exportToSvg(source)
            kotlin.test.assertTrue(
                svg.startsWith("<svg ") && svg.trimEnd().endsWith("</svg>"),
                "Expected valid SVG export for ${chart.name}",
            )
        }
        kotlin.test.assertEquals(23, countsByFamily.size, "Expected all 23 Mermaid families in oracle/charts")
        for ((family, count) in countsByFamily) {
            kotlin.test.assertTrue(count >= 3, "Expected >= 3 fixtures for $family, got $count")
        }
    }
}
