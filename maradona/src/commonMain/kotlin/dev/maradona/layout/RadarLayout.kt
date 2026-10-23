package dev.maradona.layout

import dev.maradona.model.RadarAxis
import dev.maradona.model.RadarCurve
import dev.maradona.model.RadarDiagram
import dev.maradona.model.RadarGraticule
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Positioned radial axis in a laid-out [RadarDiagram].
 */
data class PositionedRadarAxis(
    val axisIndex: Int,
    val axis: RadarAxis,
    val angleRad: Float,
    val lineEnd: DiagramPoint,
    val labelRect: DiagramRect,
)

/**
 * Positioned data curve in a laid-out [RadarDiagram].
 */
data class PositionedRadarCurve(
    val curveIndex: Int,
    val curve: RadarCurve,
    val points: List<DiagramPoint>,
    val tension: Float,
    val useSmoothClosedCurve: Boolean,
)

/**
 * Positioned legend item in a laid-out [RadarDiagram].
 */
data class PositionedRadarLegendItem(
    val curveIndex: Int,
    val label: String,
    val swatchRect: DiagramRect,
    val labelRect: DiagramRect,
)

/**
 * Complete layout geometry for a [RadarDiagram].
 */
data class RadarDiagramLayout(
    val width: Float,
    val height: Float,
    val center: DiagramPoint,
    val radius: Float,
    val ticks: Int,
    val graticule: RadarGraticule,
    val minValue: Double,
    val maxValue: Double,
    val axes: List<PositionedRadarAxis>,
    val curves: List<PositionedRadarCurve>,
    val legendItems: List<PositionedRadarLegendItem>,
    val titleRect: DiagramRect?,
)

/**
 * Computes radial axis, graticule, curve, and legend geometry for Mermaid `radar` / `radar-beta` diagrams.
 */
object RadarLayout {

    private const val LABEL_PAD = 6f
    private const val SWATCH_SIZE = 12f
    private const val SWATCH_TEXT_GAP = 6f
    private const val LEGEND_ROW_GAP = 8f
    private const val LEGEND_GAP_X = 24f
    private const val TITLE_GAP = 16f

    fun defaultLabelSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 7.4f + 4f) * density, 16f * density)

    fun defaultTitleSize(text: String, density: Float = 1f): DiagramSize =
        DiagramSize((text.length * 9.5f + 8f) * density, 22f * density)

    fun layout(
        diagram: RadarDiagram,
        axisLabelSize: (String) -> DiagramSize = { s -> defaultLabelSize(s, 1f) },
        legendLabelSize: (String) -> DiagramSize = { s -> defaultLabelSize(s, 1f) },
        titleSize: DiagramSize? = null,
        options: LayoutOptions = LayoutOptions(),
    ): RadarDiagramLayout {
        val d = options.density.coerceAtLeast(0.01f)
        val cfg = diagram.config

        // Scale the radar plot radius from config.width/height (default 600 -> 180dp radius for crisp desktop proportions).
        val basePlotDiameter = min(cfg.width, cfg.height).coerceIn(160f, 720f) * 0.60f * d
        val radius = (basePlotDiameter / 2f).coerceAtLeast(48f * d)
        val axisReach = radius * max(1f, cfg.axisScaleFactor)
        val labelPad = LABEL_PAD * d

        val minVal = cfg.min
        val rawMax = cfg.max ?: diagram.curves
            .flatMap { it.entries }
            .maxOrNull()
            ?: (minVal + 1.0)
        val maxVal = if (rawMax > minVal) rawMax else minVal + 1.0

        val numAxes = diagram.axes.size

        // First compute axis endpoints and label rectangles relative to radar center (0, 0).
        data class RelAxis(
            val index: Int,
            val axis: RadarAxis,
            val angle: Float,
            val endX: Float,
            val endY: Float,
            val lblX: Float,
            val lblY: Float,
            val lblW: Float,
            val lblH: Float,
        )

        val relAxes = diagram.axes.mapIndexed { i, axis ->
            val angle = (2.0 * PI * i.toDouble() / max(1, numAxes).toDouble() - PI / 2.0).toFloat()
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()
            val endX = radius * cfg.axisScaleFactor * cosA
            val endY = radius * cfg.axisScaleFactor * sinA

            val lblSize = axisLabelSize(axis.label)
            val anchorDist = radius * cfg.axisLabelFactor + labelPad
            val anchorX = anchorDist * cosA
            val anchorY = anchorDist * sinA

            val lx = when {
                cosA > 0.08f -> anchorX
                cosA < -0.08f -> anchorX - lblSize.width
                else -> anchorX - lblSize.width / 2f
            }
            val ly = when {
                sinA > 0.08f -> anchorY
                sinA < -0.08f -> anchorY - lblSize.height
                else -> anchorY - lblSize.height / 2f
            }
            RelAxis(
                index = i,
                axis = axis,
                angle = angle,
                endX = endX,
                endY = endY,
                lblX = lx,
                lblY = ly,
                lblW = lblSize.width,
                lblH = lblSize.height,
            )
        }

        var relMinX = -axisReach
        var relMaxX = axisReach
        var relMinY = -axisReach
        var relMaxY = axisReach
        for (ra in relAxes) {
            relMinX = minOf(relMinX, ra.endX, ra.lblX)
            relMaxX = maxOf(relMaxX, ra.endX, ra.lblX + ra.lblW)
            relMinY = minOf(relMinY, ra.endY, ra.lblY)
            relMaxY = maxOf(relMaxY, ra.endY, ra.lblY + ra.lblH)
        }

        val padLeft = cfg.marginLeft.coerceAtLeast(16f) * 0.45f * d
        val padRight = cfg.marginRight.coerceAtLeast(16f) * 0.45f * d
        val padTop = cfg.marginTop.coerceAtLeast(16f) * 0.45f * d
        val padBottom = cfg.marginBottom.coerceAtLeast(16f) * 0.45f * d

        val titleText = diagram.title?.takeIf { it.isNotBlank() }
        val resolvedTitleSize = if (titleText != null) {
            titleSize ?: defaultTitleSize(titleText, d)
        } else {
            null
        }
        val titleTopSpace = if (resolvedTitleSize != null) resolvedTitleSize.height + TITLE_GAP * d else 0f

        val showLegend = cfg.showLegend && diagram.curves.isNotEmpty()
        val swatchPx = SWATCH_SIZE * d
        val swatchGapPx = SWATCH_TEXT_GAP * d
        val legendRowGapPx = LEGEND_ROW_GAP * d
        val legendLabelSizes = if (showLegend) {
            diagram.curves.map { legendLabelSize(it.label) }
        } else {
            emptyList()
        }
        val legendBlockWidth = if (showLegend) {
            val maxLblW = legendLabelSizes.maxOfOrNull { it.width } ?: 0f
            swatchPx + swatchGapPx + maxLblW
        } else {
            0f
        }
        val legendExtraWidth = if (showLegend) LEGEND_GAP_X * d + legendBlockWidth else 0f

        val centerX = padLeft - relMinX
        val centerY = padTop + titleTopSpace - relMinY

        val radarContentWidth = centerX + relMaxX + legendExtraWidth + padRight
        val minTitleWidth = if (resolvedTitleSize != null) resolvedTitleSize.width + padLeft + padRight else 0f
        val totalWidth = max(radarContentWidth, minTitleWidth)
        val shiftX = if (totalWidth > radarContentWidth) (totalWidth - radarContentWidth) / 2f else 0f
        val finalCenterX = centerX + shiftX

        val positionedAxes = relAxes.map { ra ->
            PositionedRadarAxis(
                axisIndex = ra.index,
                axis = ra.axis,
                angleRad = ra.angle,
                lineEnd = DiagramPoint(finalCenterX + ra.endX, centerY + ra.endY),
                labelRect = DiagramRect(
                    x = finalCenterX + ra.lblX,
                    y = centerY + ra.lblY,
                    width = ra.lblW,
                    height = ra.lblH,
                ),
            )
        }

        val useSmooth = cfg.graticule == RadarGraticule.Circle && numAxes >= 3 && cfg.curveTension > 0f
        val positionedCurves = diagram.curves.mapIndexed { cIdx, curve ->
            val pts = if (numAxes == 0 || curve.entries.size != numAxes) {
                emptyList()
            } else {
                curve.entries.mapIndexed { aIdx, value ->
                    val angle = relAxes[aIdx].angle.toDouble()
                    val clipped = value.coerceIn(minVal, maxVal)
                    val norm = ((clipped - minVal) / (maxVal - minVal)).toFloat()
                    val r = radius * norm
                    DiagramPoint(
                        x = finalCenterX + r * cos(angle).toFloat(),
                        y = centerY + r * sin(angle).toFloat(),
                    )
                }
            }
            PositionedRadarCurve(
                curveIndex = cIdx,
                curve = curve,
                points = pts,
                tension = cfg.curveTension,
                useSmoothClosedCurve = useSmooth,
            )
        }

        val legendItems = mutableListOf<PositionedRadarLegendItem>()
        var maxLegendBottom = 0f
        if (showLegend) {
            val legendStartX = finalCenterX + relMaxX + LEGEND_GAP_X * d
            var curY = max(padTop + titleTopSpace, centerY - radius * 0.75f)
            for ((cIdx, curve) in diagram.curves.withIndex()) {
                val lblSz = legendLabelSizes[cIdx]
                val rowH = max(swatchPx, lblSz.height)
                val swatchY = curY + (rowH - swatchPx) / 2f
                val lblY = curY + (rowH - lblSz.height) / 2f
                legendItems += PositionedRadarLegendItem(
                    curveIndex = cIdx,
                    label = curve.label,
                    swatchRect = DiagramRect(
                        x = legendStartX,
                        y = swatchY,
                        width = swatchPx,
                        height = swatchPx,
                    ),
                    labelRect = DiagramRect(
                        x = legendStartX + swatchPx + swatchGapPx,
                        y = lblY,
                        width = lblSz.width,
                        height = lblSz.height,
                    ),
                )
                curY += rowH + legendRowGapPx
                maxLegendBottom = curY - legendRowGapPx
            }
        }

        val totalHeight = max(centerY + relMaxY, maxLegendBottom) + padBottom
        val titleRect = if (resolvedTitleSize != null) {
            DiagramRect(
                x = (totalWidth - resolvedTitleSize.width) / 2f,
                y = padTop,
                width = resolvedTitleSize.width,
                height = resolvedTitleSize.height,
            )
        } else {
            null
        }

        return RadarDiagramLayout(
            width = totalWidth,
            height = totalHeight,
            center = DiagramPoint(finalCenterX, centerY),
            radius = radius,
            ticks = cfg.ticks.coerceIn(1, 32),
            graticule = cfg.graticule,
            minValue = minVal,
            maxValue = maxVal,
            axes = positionedAxes,
            curves = positionedCurves,
            legendItems = legendItems,
            titleRect = titleRect,
        )
    }
}
