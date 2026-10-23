package dev.maradona.render

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.ErDiagramLayout
import dev.maradona.layout.GanttDiagramLayout
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.JourneyDiagramLayout
import dev.maradona.layout.KanbanDiagramLayout
import dev.maradona.layout.LayoutOptions
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
import dev.maradona.layout.XyChartDiagramLayout
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val MIN_ZOOM = 0.1f
private const val MAX_ZOOM = 5f

/** Starting zoom level for [MaradonaDiagram]. */
enum class DiagramZoom {
    /** Shrink (never enlarge) so the whole diagram fits the viewport. Default. */
    Fit,
    /** Natural size: diagram dp == screen dp, even if it overflows the viewport. */
    Natural,
    /** Smallest zoom: 10% of fit. */
    Min,
    /** Largest zoom: 500% of fit. */
    Max,
}

/**
 * Renders Mermaid flowchart source as native composables.
 *
 * The diagram is shrunk to fit the available space, then can be zoomed
 * (pinch on touch, Cmd/Ctrl + mouse wheel with a mouse) and panned.
 * Zoom is anchored at the pinch centroid / cursor position. Double-tap
 * resets.
 *
 * Zoom is hybrid: while a gesture runs, a cheap layer transform moves the
 * already-rendered diagram (no re-layout per frame, so no flicker); when the
 * gesture settles, the zoom is committed by scaling the density so all dp/sp
 * re-render at the final size and strokes and text stay crisp.
 *
 * On a parse error the last good diagram stays visible, dimmed, with the
 * error shown at the bottom — matching Pele's behaviour.
 */
@Composable
fun MaradonaDiagram(
    source: String,
    modifier: Modifier = Modifier,
    theme: MaradonaTheme = MaradonaTheme.light(),
    autoDirection: Boolean = true,
    zoomEnabled: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(),
    initialZoom: DiagramZoom = DiagramZoom.Fit,
    /**
     * Absolute scale to start at, in px per natural px, overriding [initialZoom].
     * Used by the oracle, which needs exact 0.1x/1x/5x renders regardless of
     * how the diagram happens to fit its viewport.
     */
    initialScale: Float? = null,
    /** Fraction of the scrollable range to start at, per axis (0.5 = centered). Only meaningful when the zoomed diagram overflows the viewport. */
    initialPan: Offset? = null,
    /** Reports the diagram's natural (unzoomed) layout size once measured. */
    onNaturalSize: (IntSize) -> Unit = {},
    /** Reports the computed flowchart layout (see [MaradonaLayout.onLayout]). */
    onLayout: (dev.maradona.layout.DiagramLayout) -> Unit = {},
    /** Reports the computed sequence diagram layout (see [MaradonaSequenceLayout.onLayout]). */
    onSequenceLayout: (SequenceDiagramLayout) -> Unit = {},
    /** Reports the computed class diagram layout (see [MaradonaClassLayout.onLayout]). */
    onClassLayout: (ClassDiagramLayout) -> Unit = {},
    /** Reports the computed state diagram layout (see [MaradonaStateLayout.onLayout]). */
    onStateLayout: (StateDiagramLayout) -> Unit = {},
    /** Reports the computed ER diagram layout (see [MaradonaErLayout.onLayout]). */
    onErLayout: (ErDiagramLayout) -> Unit = {},
    /** Reports the computed mindmap diagram layout (see [MaradonaMindmapLayout.onLayout]). */
    onMindmapLayout: (MindmapDiagramLayout) -> Unit = {},
    /** Reports the computed Gantt diagram layout (see [MaradonaGanttLayout.onLayout]). */
    onGanttLayout: (GanttDiagramLayout) -> Unit = {},
    /** Reports the computed Pie diagram layout (see [MaradonaPieLayout.onLayout]). */
    onPieLayout: (PieDiagramLayout) -> Unit = {},
    /** Reports the computed GitGraph diagram layout (see [MaradonaGitGraphLayout.onLayout]). */
    onGitGraphLayout: (GitGraphDiagramLayout) -> Unit = {},
    /** Reports the computed Timeline diagram layout (see [MaradonaTimelineLayout.onLayout]). */
    onTimelineLayout: (TimelineDiagramLayout) -> Unit = {},
    /** Reports the computed User Journey diagram layout (see [MaradonaJourneyLayout.onLayout]). */
    onJourneyLayout: (JourneyDiagramLayout) -> Unit = {},
    /** Reports the computed Quadrant chart layout (see [MaradonaQuadrantLayout.onLayout]). */
    onQuadrantLayout: (QuadrantDiagramLayout) -> Unit = {},
    /** Reports the computed XY chart layout (see [MaradonaXyChartLayout.onLayout]). */
    onXyChartLayout: (XyChartDiagramLayout) -> Unit = {},
    /** Reports the computed Kanban diagram layout (see [MaradonaKanbanLayout.onLayout]). */
    onKanbanLayout: (KanbanDiagramLayout) -> Unit = {},
    /** Reports the computed Sankey diagram layout (see [MaradonaSankeyLayout.onLayout]). */
    onSankeyLayout: (SankeyDiagramLayout) -> Unit = {},
    /** Reports the computed Requirement diagram layout (see [MaradonaRequirementLayout.onLayout]). */
    onRequirementLayout: (RequirementDiagramLayout) -> Unit = {},
    /** Reports the computed Packet diagram layout (see [MaradonaPacketLayout.onLayout]). */
    onPacketLayout: (PacketDiagramLayout) -> Unit = {},
    /** Reports the computed Radar diagram layout (see [MaradonaRadarLayout.onLayout]). */
    onRadarLayout: (RadarDiagramLayout) -> Unit = {},
) {
    val parsed = remember(source) { runCatching { MaradonaParser.parse(source) } }
    var lastGood by remember { mutableStateOf<MaradonaDiagramModel?>(null) }
    parsed.getOrNull()?.let { lastGood = it }
    val model = parsed.getOrNull() ?: lastGood
    val error = parsed.exceptionOrNull()

    // Committed state: rendered crisply via density scaling.
    var userZoom by remember {
        mutableFloatStateOf(
            when (initialZoom) {
                DiagramZoom.Min -> MIN_ZOOM
                DiagramZoom.Max -> MAX_ZOOM
                else -> 1f
            },
        )
    }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Natural needs the measured size to back out the fit scale; apply once known.
    var initialViewApplied by remember { mutableStateOf(false) }
    // Transient state: applied as a layer transform during a gesture.
    var gestureZoom by remember { mutableFloatStateOf(1f) }
    var gesturePan by remember { mutableStateOf(Offset.Zero) }
    var wheelNonce by remember { mutableIntStateOf(0) }

    var naturalSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    fun fitScale(): Float {
        val availW = viewportSize.width.toFloat()
        val availH = viewportSize.height.toFloat()
        if (naturalSize.width <= 0 || naturalSize.height <= 0 || availW <= 0 || availH <= 0) return 1f
        return minOf(availW / naturalSize.width, availH / naturalSize.height).coerceAtMost(1f)
    }

    fun centerOffset(scale: Float): Offset = Offset(
        (viewportSize.width - naturalSize.width * scale) / 2f,
        (viewportSize.height - naturalSize.height * scale) / 2f,
    )

    /** Transiently scales around [focal] (viewport position) so the point under the fingers/cursor stays put. */
    fun gestureZoomBy(factor: Float, focal: Offset) {
        if (naturalSize.width <= 0 || viewportSize.width <= 0) return
        // Clamp so the committed-equivalent zoom stays within [MIN_ZOOM, MAX_ZOOM];
        // live clamping means commit() can apply gestureZoom exactly, with no jump.
        val newGestureZoom = (gestureZoom * factor).coerceIn(MIN_ZOOM / userZoom, MAX_ZOOM / userZoom)
        if (newGestureZoom == gestureZoom) return
        val ratio = newGestureZoom / gestureZoom
        val center = centerOffset(fitScale() * userZoom)
        gesturePan = Offset(
            focal.x - center.x - pan.x - ratio * (focal.x - center.x - pan.x - gesturePan.x),
            focal.y - center.y - pan.y - ratio * (focal.y - center.y - pan.y - gesturePan.y),
        )
        gestureZoom = newGestureZoom
    }

    /** Bakes the transient layer transform into the density-scaled render, preserving on-screen geometry. */
    fun commitGesture() {
        if (gestureZoom == 1f && gesturePan == Offset.Zero) return
        val fit = fitScale()
        val oldScale = fit * userZoom
        val newZoom = (userZoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newScale = fit * newZoom
        // Screen pos was: oldCenter + pan + gesturePan + gestureZoom*oldScale*p.
        // It becomes:   newCenter + pan' + newScale*p. These match for all p because
        // gestureZoom was clamped live, so gestureZoom*oldScale == newScale.
        val oldCenter = centerOffset(oldScale)
        val newCenter = centerOffset(newScale)
        pan = Offset(
            oldCenter.x - newCenter.x + pan.x + gesturePan.x,
            oldCenter.y - newCenter.y + pan.y + gesturePan.y,
        )
        userZoom = newZoom
        gestureZoom = 1f
        gesturePan = Offset.Zero
    }

    BoxWithConstraints(
        modifier = modifier
            .background(theme.background)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(zoomEnabled) {
                if (!zoomEnabled) return@pointerInput
                while (true) {
                    detectTransformGestures { centroid, gPan, gZoom, _ ->
                        if (gZoom != 1f) gestureZoomBy(gZoom, centroid)
                        gesturePan += gPan
                    }
                    commitGesture()
                }
            }
            .pointerInput(zoomEnabled) {
                if (!zoomEnabled) return@pointerInput
                // Mouse-wheel zoom (Cmd/Ctrl held): desktop affordance; touch platforms
                // essentially never emit Scroll events, and unmodified scrolls pass through.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val modifiers = event.keyboardModifiers
                        if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) continue
                        val dy = change.scrollDelta.y
                        if (dy == 0f) continue
                        // Trackpads emit many small deltas; wheel notches arrive as ~40px jumps.
                        val factor = 1.15.pow(-dy.toDouble() / 10).toFloat().coerceIn(0.5f, 2f)
                        gestureZoomBy(factor, change.position)
                        change.consume()
                        wheelNonce++
                    }
                }
            }
            .pointerInput(zoomEnabled) {
                if (!zoomEnabled) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    userZoom = 1f
                    pan = Offset.Zero
                    gestureZoom = 1f
                    gesturePan = Offset.Zero
                })
            },
    ) {
        // Wheel events are discrete; commit once the wheel goes quiet. Keep the
        // window short: until commit, the transient layer transform looks soft.
        LaunchedEffect(wheelNonce) {
            if (wheelNonce > 0) {
                delay(90)
                commitGesture()
            }
        }

        LaunchedEffect(naturalSize) {
            if (initialViewApplied || naturalSize.width <= 0 || naturalSize.height <= 0) return@LaunchedEffect
            if (initialScale != null) {
                // Absolute scale: the oracle needs exactly 0.1x/1x/5x, and the
                // fit must not feed back into the density (a layout whose
                // rounding shifts by a pixel would otherwise oscillate).
                userZoom = 1f
            } else if (initialZoom == DiagramZoom.Natural) {
                userZoom = (1f / fitScale()).coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
            initialPan?.let { f ->
                val scale = initialScale ?: (fitScale() * userZoom)
                val rangeX = naturalSize.width * scale - viewportSize.width
                val rangeY = naturalSize.height * scale - viewportSize.height
                pan = Offset(
                    if (rangeX > 0) rangeX / 2f - f.x * rangeX else 0f,
                    if (rangeY > 0) rangeY / 2f - f.y * rangeY else 0f,
                )
            }
            initialViewApplied = true
        }

        val availW = constraints.maxWidth.toFloat()
        val availH = constraints.maxHeight.toFloat()
        val layoutWidthPx = constraints.maxWidth
        val fit = if (naturalSize.width > 0 && naturalSize.height > 0 && availW > 0 && availH > 0) {
            minOf(availW / naturalSize.width, availH / naturalSize.height).coerceAtMost(1f)
        } else 1f
        val scale = (initialScale ?: (fit * userZoom)).coerceAtLeast(0.01f)
        val centerX = (availW - naturalSize.width * scale) / 2f
        val centerY = (availH - naturalSize.height * scale) / 2f

        if (model != null) {
            val density = LocalDensity.current
            // sp -> px is sp * fontScale * density, so fontScale must NOT be
            // scaled: doing so would zoom text quadratically while dp-sized
            // shapes zoom linearly, and text bursts out of its nodes.
            val zoomedDensity = Density(density.density * scale, density.fontScale)
            // The pan/zoom transform lives on a viewport-sized wrapper, not on
            // the diagram node itself: the node can be far wider than any GPU
            // texture (a zoomed-in LR chart is tens of thousands of px), and a
            // layer that big silently fails to rasterise. The wrapper must not
            // clip - clipping happens in the parent, after the transform, or
            // zoom would magnify a pre-clipped corner instead of the diagram.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = gestureZoom
                        scaleY = gestureZoom
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = centerX + pan.x + gesturePan.x
                        translationY = centerY + pan.y + gesturePan.y
                    },
            ) {
                CompositionLocalProvider(LocalDensity provides zoomedDensity) {
                    val contentModifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .onSizeChanged {
                            naturalSize = IntSize(
                                (it.width / scale).roundToInt(),
                                (it.height / scale).roundToInt(),
                            )
                            onNaturalSize(naturalSize)
                        }
                        .alpha(if (error != null) 0.45f else 1f)
                    when (model) {
                        is MaradonaDiagramModel.FlowchartModel ->
                            MaradonaLayout(
                                chart = model.chart,
                                theme = theme,
                                autoDirection = autoDirection,
                                layoutOptions = layoutOptions,
                                // The width the auto-direction adaption compares against,
                                // in px at the current (zoom-scaled) density. Passing the
                                // natural-units width here would compare it against
                                // density-scaled values, so zooming in would spuriously
                                // flip a horizontal chart to vertical.
                                availableWidthPx = layoutWidthPx,
                                onLayout = onLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.SequenceModel ->
                            MaradonaSequenceLayout(
                                diagram = model.diagram,
                                theme = theme,
                                onLayout = onSequenceLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.ClassModel ->
                            MaradonaClassLayout(
                                diagram = model.diagram,
                                theme = theme,
                                autoDirection = autoDirection,
                                layoutOptions = layoutOptions,
                                availableWidthPx = layoutWidthPx,
                                onLayout = onClassLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.StateModel ->
                            MaradonaStateLayout(
                                diagram = model.diagram,
                                theme = theme,
                                autoDirection = autoDirection,
                                layoutOptions = layoutOptions,
                                availableWidthPx = layoutWidthPx,
                                onLayout = onStateLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.ErModel ->
                            MaradonaErLayout(
                                diagram = model.diagram,
                                theme = theme,
                                autoDirection = autoDirection,
                                layoutOptions = layoutOptions,
                                availableWidthPx = layoutWidthPx,
                                onLayout = onErLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.MindmapModel ->
                            MaradonaMindmapLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onMindmapLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.GanttModel ->
                            MaradonaGanttLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onGanttLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.PieModel ->
                            MaradonaPieLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onPieLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.GitGraphModel ->
                            MaradonaGitGraphLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onGitGraphLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.TimelineModel ->
                            MaradonaTimelineLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onTimelineLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.JourneyModel ->
                            MaradonaJourneyLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onJourneyLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.QuadrantModel ->
                            MaradonaQuadrantLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onQuadrantLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.XyChartModel ->
                            MaradonaXyChartLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onXyChartLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.KanbanModel ->
                            MaradonaKanbanLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onKanbanLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.SankeyModel ->
                            MaradonaSankeyLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onSankeyLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.RequirementModel ->
                            MaradonaRequirementLayout(
                                diagram = model.diagram,
                                theme = theme,
                                autoDirection = autoDirection,
                                layoutOptions = layoutOptions,
                                availableWidthPx = layoutWidthPx,
                                onLayout = onRequirementLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.PacketModel ->
                            MaradonaPacketLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onPacketLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.RadarModel ->
                            MaradonaRadarLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                onLayout = onRadarLayout,
                                modifier = contentModifier,
                            )
                    }
                }
            }
        }

        if (error != null) {
            BasicText(
                text = error.message ?: "Could not parse diagram",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(theme.background)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(color = theme.error, fontSize = 12.sp),
            )
        }
    }
}
