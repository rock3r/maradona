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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.layout.ArchitectureDiagramLayout
import dev.maradona.layout.BlockDiagramLayout
import dev.maradona.layout.C4DiagramLayout
import dev.maradona.layout.ClassDiagramLayout
import dev.maradona.layout.DiagramRect
import dev.maradona.layout.ErDiagramLayout
import dev.maradona.layout.GanttDiagramLayout
import dev.maradona.layout.GitGraphDiagramLayout
import dev.maradona.layout.JourneyDiagramLayout
import dev.maradona.layout.KanbanDiagramLayout
import dev.maradona.layout.LayoutOptions
import dev.maradona.layout.MaradonaViewportStats
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
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.parse.MaradonaDiagramModel
import dev.maradona.parse.MaradonaParser
import kotlin.math.abs
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
 *
 * **Scalability & limits:** Sources are bounded to 50,000 characters, 5,000
 * nodes, and 5,000 edges so untrusted inputs cannot exhaust memory. Every node
 * is rendered through the standard Compose layout pipeline (preserving native
 * text measurement, `click` actions, and accessibility semantics without a
 * separate Canvas-only fast path). When [viewportCullingEnabled] is `true`
 * (the default), off-screen nodes, labels, headers, and underlay
 * edges/connectors/containers outside the visible viewport (plus overscan) are
 * lazily skipped during composition and drawing across all 23 diagram families
 * while preserving edges/connectors that cross the viewport between off-screen
 * endpoints; however, when zoomed out so that thousands of nodes are
 * simultaneously visible inside the viewport, layout and frame times may still
 * be slow.
 */
@Composable
fun MaradonaDiagram(
    source: String,
    modifier: Modifier = Modifier,
    theme: MaradonaTheme = LocalMaradonaTheme.current,
    autoDirection: Boolean = true,
    zoomEnabled: Boolean = true,
    /** Whether to lazily skip composing/drawing off-screen diagram items outside the visible viewport across all 23 families. */
    viewportCullingEnabled: Boolean = true,
    layoutOptions: LayoutOptions = LayoutOptions(),
    initialZoom: DiagramZoom = DiagramZoom.Fit,
    /**
     * Absolute scale to start at, in px per natural px, overriding [initialZoom].
     * Used by the oracle, which needs exact 0.1x/1x/5x renders regardless of
     * how the diagram happens to fit its viewport.
     */
    initialScale: Float? = null,
    /**
     * Fraction of the scrollable range to start at, per axis (0.5 = centered). Only meaningful when the zoomed diagram overflows the viewport.
     */
    initialPan: Offset? = null,
    /**
     * Optional caller-controlled key that resets pan and zoom back to [initialZoom] / [initialPan]
     * whenever its value changes (for example, an incrementing counter from a "Reset to fit" button
     * or example selector).
     */
    resetViewKey: Any? = null,
    /** Reports the diagram's natural (unzoomed) layout size once measured. */
    onNaturalSize: (IntSize) -> Unit = {},
    /** Reports diagnostic counts for viewport-lazy composition and underlay culling. */
    onViewportStats: (MaradonaViewportStats) -> Unit = {},
    /**
     * Optional callback invoked when a diagram node, class, task, C4 element,
     * requirement element, or Kanban ticket is clicked/activated.
     * When `null`, nodes with a URL (`click ... href`, C4 `$link`, `docRef`, `ticketBaseUrl`)
     * open their link via [androidx.compose.ui.platform.LocalUriHandler].
     */
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
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
    /** Reports the computed Block diagram layout (see [MaradonaBlockLayout.onLayout]). */
    onBlockLayout: (BlockDiagramLayout) -> Unit = {},
    /** Reports the computed Architecture diagram layout (see [MaradonaArchitectureLayout.onLayout]). */
    onArchitectureLayout: (ArchitectureDiagramLayout) -> Unit = {},
    /** Reports the computed Treemap diagram layout (see [MaradonaTreemapLayout.onLayout]). */
    onTreemapLayout: (TreemapDiagramLayout) -> Unit = {},
    /** Reports the computed C4 diagram layout (see [MaradonaC4Layout.onLayout]). */
    onC4Layout: (C4DiagramLayout) -> Unit = {},
    /** Reports the computed ZenUML diagram layout (see [MaradonaZenUmlLayout.onLayout]). */
    onZenUmlLayout: (ZenUmlDiagramLayout) -> Unit = {},
) {
    val parsed = remember(source) { runCatching { MaradonaParser.parse(source) } }
    var lastGood by remember { mutableStateOf<MaradonaDiagramModel?>(null) }
    parsed.getOrNull()?.let { lastGood = it }
    val model = parsed.getOrNull() ?: lastGood
    val error = parsed.exceptionOrNull()

    // Committed state: rendered crisply via density scaling.
    // We keep a single MutableState instance per property across the lifetime of
    // MaradonaDiagram (so closures in graphicsLayer, onSizeChanged, and pointerInput
    // never hold orphaned state references) and reset their values when the
    // diagram model or layout-affecting parameters change.
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

    val hostDensity = LocalDensity.current
    val imageResolver = LocalMaradonaImageResolver.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontResolutionToken = remember(fontFamilyResolver, theme.fontFamily) {
        runCatching { fontFamilyResolver.resolve(fontFamily = theme.fontFamily).value }.getOrNull()
    }
    val monoFontResolutionToken = remember(fontFamilyResolver, theme.monospaceFontFamily) {
        runCatching { fontFamilyResolver.resolve(fontFamily = theme.monospaceFontFamily).value }.getOrNull()
    }
    var naturalSize by remember { mutableStateOf(IntSize.Zero) }
    var drawnSize by remember { mutableStateOf(IntSize.Zero) }
    var settledScale by remember { mutableFloatStateOf(-1f) }
    var lastAutoDirViewportWidth by remember { mutableIntStateOf(-1) }
    var measureResetNonce by remember { mutableIntStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val viewResetKey = remember(model, initialZoom, initialScale, initialPan, resetViewKey) { Any() }
    var lastViewResetKey by remember { mutableStateOf<Any?>(viewResetKey) }
    if (lastViewResetKey !== viewResetKey) {
        lastViewResetKey = viewResetKey
        userZoom = when (initialZoom) {
            DiagramZoom.Min -> MIN_ZOOM
            DiagramZoom.Max -> MAX_ZOOM
            else -> 1f
        }
        pan = Offset.Zero
        initialViewApplied = false
        gestureZoom = 1f
        gesturePan = Offset.Zero
        wheelNonce = 0
    }

    val measureResetKey = remember(
        model,
        theme.fontFamily,
        theme.monospaceFontFamily,
        fontResolutionToken,
        monoFontResolutionToken,
        imageResolver,
        theme.nodeFontSize,
        theme.edgeLabelFontSize,
        theme.subgraphFontSize,
        theme.titleFontSize,
        theme.captionFontSize,
        autoDirection,
        layoutOptions,
        hostDensity.density,
        hostDensity.fontScale,
    ) { Any() }
    var lastMeasureResetKey by remember { mutableStateOf<Any?>(measureResetKey) }
    if (lastMeasureResetKey !== measureResetKey) {
        lastMeasureResetKey = measureResetKey
        naturalSize = IntSize.Zero
        drawnSize = IntSize.Zero
        settledScale = -1f
        lastAutoDirViewportWidth = -1
        measureResetNonce++
    }

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
            .pointerInput(zoomEnabled, model, resetViewKey) {
                if (!zoomEnabled) return@pointerInput
                while (true) {
                    detectTransformGestures { centroid, gPan, gZoom, _ ->
                        if (gZoom != 1f) gestureZoomBy(gZoom, centroid)
                        gesturePan += gPan
                    }
                    commitGesture()
                }
            }
            .pointerInput(zoomEnabled, model, resetViewKey) {
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
            .pointerInput(zoomEnabled, model, resetViewKey) {
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
        LaunchedEffect(wheelNonce, model, resetViewKey) {
            if (wheelNonce > 0) {
                delay(90)
                commitGesture()
            }
        }

        LaunchedEffect(naturalSize, viewResetKey) {
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
        if (autoDirection && constraints.maxWidth != lastAutoDirViewportWidth) {
            if (lastAutoDirViewportWidth != -1) {
                naturalSize = IntSize.Zero
                drawnSize = IntSize.Zero
                settledScale = -1f
                measureResetNonce++
            }
            lastAutoDirViewportWidth = constraints.maxWidth
        }
        val fit = if (naturalSize.width > 0 && naturalSize.height > 0 && availW > 0 && availH > 0) {
            minOf(availW / naturalSize.width, availH / naturalSize.height).coerceAtMost(1f)
        } else 1f
        val scale = (initialScale ?: if (naturalSize.width > 0 && naturalSize.height > 0) fit * userZoom else 1f)
            .coerceAtLeast(0.01f)
        // Scale availableWidthPx into zoomedDensity units so autoDirection's
        // width and 640.dp breakpoint comparisons are scale-invariant.
        val layoutWidthPx = if (constraints.maxWidth == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            (constraints.maxWidth * scale).roundToInt().coerceAtLeast(1)
        }
        val currentScale by rememberUpdatedState(scale)
        val currentInitialScale by rememberUpdatedState(initialScale)
        val currentOnNaturalSize by rememberUpdatedState(onNaturalSize)

        if (model != null) {
            val density = LocalDensity.current
            // sp -> px is sp * fontScale * density, so fontScale must NOT be
            // scaled: doing so would zoom text quadratically while dp-sized
            // shapes zoom linearly, and text bursts out of its nodes.
            val zoomedDensity = Density(density.density * scale, density.fontScale)
            val overscanPx = 96f * density.density
            val viewportParams = if (viewportCullingEnabled && availW > 0f && availH > 0f) {
                FlowchartViewportParams(
                    viewportWidthPx = availW,
                    viewportHeightPx = availH,
                    gestureZoom = gestureZoom,
                    panPx = pan,
                    gesturePanPx = gesturePan,
                    initialPan = initialPan,
                    initialViewApplied = initialViewApplied,
                    overscanPx = overscanPx,
                    scaleKnownOnFirstFrame = initialScale != null || (naturalSize.width > 0 && naturalSize.height > 0),
                )
            } else {
                null
            }
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
                        val drawnMatchesCurrentScale =
                            drawnSize.width > 0 &&
                                drawnSize.height > 0 &&
                                currentInitialScale == null &&
                                abs(settledScale - currentScale) < 1e-4f
                        val effectiveDrawnW = if (drawnMatchesCurrentScale) {
                            drawnSize.width.toFloat()
                        } else {
                            naturalSize.width * currentScale
                        }
                        val effectiveDrawnH = if (drawnMatchesCurrentScale) {
                            drawnSize.height.toFloat()
                        } else {
                            naturalSize.height * currentScale
                        }
                        // If sub-pixel font hinting at `zoomedDensity` caused the
                        // scaled layout to exceed the viewport by a few pixels at
                        // Fit zoom, absorb that residual delta in the GPU layer
                        // rather than mutating `naturalSize` / `LocalDensity`
                        // (which would create a multi-frame feedback oscillation).
                        val fitCorrection = if (
                            currentInitialScale == null &&
                            initialZoom == DiagramZoom.Fit &&
                            userZoom == 1f &&
                            availW > 0f &&
                            availH > 0f &&
                            effectiveDrawnW > 0f &&
                            effectiveDrawnH > 0f
                        ) {
                            minOf(1f, availW / effectiveDrawnW, availH / effectiveDrawnH)
                        } else {
                            1f
                        }
                        val finalW = effectiveDrawnW * fitCorrection
                        val finalH = effectiveDrawnH * fitCorrection
                        val curCenterX = (availW - finalW) / 2f
                        val curCenterY = (availH - finalH) / 2f
                        val curPan = if (!initialViewApplied && initialPan != null && naturalSize.width > 0 && naturalSize.height > 0) {
                            val rangeX = finalW - availW
                            val rangeY = finalH - availH
                            Offset(
                                if (rangeX > 0f) rangeX / 2f - initialPan.x * rangeX else 0f,
                                if (rangeY > 0f) rangeY / 2f - initialPan.y * rangeY else 0f,
                            )
                        } else {
                            pan
                        }
                        scaleX = gestureZoom * fitCorrection
                        scaleY = gestureZoom * fitCorrection
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = curCenterX + curPan.x + gesturePan.x
                        translationY = curCenterY + curPan.y + gesturePan.y
                    },
            ) {
                CompositionLocalProvider(
                    LocalDensity provides zoomedDensity,
                    LocalMaradonaTheme provides theme,
                ) {
                    val onContentSizeMeasured: (IntSize) -> Unit = remember(measureResetNonce) {
                        { it: IntSize ->
                            if (it.width > 0 && it.height > 0) {
                                val fixedScale = currentInitialScale
                                if (fixedScale != null) {
                                    drawnSize = it
                                    settledScale = fixedScale
                                    val computedNat = IntSize(
                                        (it.width / fixedScale).roundToInt(),
                                        (it.height / fixedScale).roundToInt(),
                                    )
                                    if (naturalSize != computedNat) {
                                        naturalSize = computedNat
                                    }
                                    currentOnNaturalSize(computedNat)
                                } else if (naturalSize.width <= 0 || naturalSize.height <= 0) {
                                    // Pass 1: lock `naturalSize` from the initial 1x pass at `hostDensity`.
                                    naturalSize = it
                                    drawnSize = it
                                    settledScale = currentScale
                                    currentOnNaturalSize(it)
                                } else if (abs(settledScale - currentScale) >= 1e-4f) {
                                    // Pass 2: measurement followed a `scale` change. Record `drawnSize`
                                    // without overwriting `naturalSize` (preventing 60 Hz feedback oscillation).
                                    drawnSize = it
                                    settledScale = currentScale
                                } else if (it != drawnSize) {
                                    // Layout size changed while `scale` was constant (e.g. an async image
                                    // or font resolved inside the child layout). Re-measure at 1x.
                                    if (abs(currentScale - 1f) < 1e-4f) {
                                        naturalSize = it
                                        drawnSize = it
                                        currentOnNaturalSize(it)
                                    } else {
                                        naturalSize = IntSize.Zero
                                        drawnSize = IntSize.Zero
                                        settledScale = -1f
                                        measureResetNonce++
                                    }
                                }
                            }
                        }
                    }
                    val contentModifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .onSizeChanged(onContentSizeMeasured)
                        .alpha(if (error != null) 0.45f else 1f)
                    val activeViewportResolver: ((Float, Float) -> dev.maradona.layout.DiagramRect?)? =
                        viewportParams?.let { params -> { lw, lh -> params.resolve(lw, lh) } }
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
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.SequenceModel ->
                            MaradonaSequenceLayout(
                                diagram = model.diagram,
                                theme = theme,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
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
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
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
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
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
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onErLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.MindmapModel ->
                            MaradonaMindmapLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onMindmapLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.GanttModel ->
                            MaradonaGanttLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onGanttLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.PieModel ->
                            MaradonaPieLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onPieLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.GitGraphModel ->
                            MaradonaGitGraphLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onGitGraphLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.TimelineModel ->
                            MaradonaTimelineLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onTimelineLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.JourneyModel ->
                            MaradonaJourneyLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onJourneyLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.QuadrantModel ->
                            MaradonaQuadrantLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onQuadrantLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.XyChartModel ->
                            MaradonaXyChartLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onXyChartLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.KanbanModel ->
                            MaradonaKanbanLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onKanbanLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.SankeyModel ->
                            MaradonaSankeyLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
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
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onRequirementLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.PacketModel ->
                            MaradonaPacketLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onPacketLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.RadarModel ->
                            MaradonaRadarLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onRadarLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.BlockModel ->
                            MaradonaBlockLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onBlockLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.ArchitectureModel ->
                            MaradonaArchitectureLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onArchitectureLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.TreemapModel ->
                            MaradonaTreemapLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onTreemapLayout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.C4Model ->
                            MaradonaC4Layout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onC4Layout,
                                modifier = contentModifier,
                            )
                        is MaradonaDiagramModel.ZenUmlModel ->
                            MaradonaZenUmlLayout(
                                diagram = model.diagram,
                                theme = theme,
                                layoutOptions = layoutOptions,
                                viewportResolver = activeViewportResolver,
                                onNodeClick = onNodeClick,
                                onViewportStats = onViewportStats,
                                onLayout = onZenUmlLayout,
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
                style = TextStyle(
                    color = theme.error,
                    fontSize = theme.edgeLabelFontSize,
                    fontFamily = theme.fontFamily,
                ),
            )
        }
    }
}
