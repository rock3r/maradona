package dev.maradona.render

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Palette for C4 element cards (`Person`, `System`, `Container`, `Component`, and `_Ext` variants).
 */
@Immutable
data class MaradonaC4Palette(
    val personFill: Color,
    val personBorder: Color,
    val personText: Color,
    val systemFill: Color,
    val systemBorder: Color,
    val systemText: Color,
    val containerFill: Color,
    val containerBorder: Color,
    val containerText: Color,
    val componentFill: Color,
    val componentBorder: Color,
    val componentText: Color,
    val externalFill: Color,
    val externalBorder: Color,
    val externalText: Color,
) {
    companion object {
        fun default(): MaradonaC4Palette = MaradonaC4Palette(
            personFill = Color(0xFF08427B),
            personBorder = Color(0xFF073B6F),
            personText = Color.White,
            systemFill = Color(0xFF1168BD),
            systemBorder = Color(0xFF0B4884),
            systemText = Color.White,
            containerFill = Color(0xFF438DD5),
            containerBorder = Color(0xFF2E6295),
            containerText = Color.White,
            componentFill = Color(0xFF85BBF0),
            componentBorder = Color(0xFF5D82A8),
            componentText = Color(0xFF0B2545),
            externalFill = Color(0xFF999999),
            externalBorder = Color(0xFF7A7A7A),
            externalText = Color.White,
        )

        fun fromAccent(
            accent: Color,
            surface: Color,
            mutedSurface: Color,
            mutedBorder: Color,
            isDark: Boolean,
        ): MaradonaC4Palette {
            val personFill = lerp(accent, Color.Black, if (isDark) 0.35f else 0.42f)
            val systemFill = lerp(accent, Color.Black, if (isDark) 0.12f else 0.16f)
            val containerFill = accent
            val componentFill = lerp(accent, surface, if (isDark) 0.32f else 0.48f)
            val externalFill = lerp(mutedBorder, Color.Black, if (isDark) 0.12f else 0.38f)
            return MaradonaC4Palette(
                personFill = personFill,
                personBorder = lerp(personFill, Color.Black, 0.2f),
                personText = contrastingTextColor(personFill),
                systemFill = systemFill,
                systemBorder = lerp(systemFill, Color.Black, 0.25f),
                systemText = contrastingTextColor(systemFill),
                containerFill = containerFill,
                containerBorder = lerp(containerFill, Color.Black, 0.25f),
                containerText = contrastingTextColor(containerFill),
                componentFill = componentFill,
                componentBorder = lerp(componentFill, accent, 0.5f),
                componentText = contrastingTextColor(componentFill, darkText = Color(0xFF0B2545)),
                externalFill = externalFill,
                externalBorder = lerp(externalFill, Color.Black, 0.25f),
                externalText = contrastingTextColor(externalFill),
            )
        }
    }
}

/**
 * CompositionLocal providing the active [MaradonaTheme] to [MaradonaDiagram] and its sub-layouts.
 *
 * Host applications (such as Jewel IDE toolwindows, Material apps, or custom design systems)
 * can provide their theme once via `CompositionLocalProvider(LocalMaradonaTheme provides myTheme)`
 * so all nested diagrams automatically adopt the host's palette and typography.
 */
val LocalMaradonaTheme = staticCompositionLocalOf { MaradonaTheme.light() }

/**
 * Complete colour, palette, and typography tokens for Maradona diagrams across all 23 Mermaid families.
 *
 * Every renderer reads its surfaces, strokes, notes, activations, semantic status badges,
 * categorical chart series, C4 cards, and font families/sizes from [MaradonaTheme] rather
 * than hardcoding light/dark constants.
 */
@Immutable
data class MaradonaTheme(
    val background: Color,
    val nodeFill: Color,
    val nodeBorder: Color,
    val nodeText: Color,
    val edge: Color,
    val edgeLabelText: Color,
    val edgeLabelBackground: Color,
    val subgraphFill: Color,
    val subgraphBorder: Color,
    val subgraphText: Color,
    val error: Color,
    /** Primary UI / label font family. */
    val fontFamily: FontFamily = FontFamily.Default,
    val nodeFontSize: TextUnit = 14.sp,
    val edgeLabelFontSize: TextUnit = 12.sp,
    val subgraphFontSize: TextUnit = 14.sp,
    /** Monospace font family used for code signatures, packet bits, ER types, and ZenUML calls. */
    val monospaceFontFamily: FontFamily = FontFamily.Monospace,
    /** Diagram title font size. */
    val titleFontSize: TextUnit = 16.sp,
    /** Secondary metadata / stereotype / tick label font size. */
    val captionFontSize: TextUnit = 11.sp,
    /** Note and comment callout surface fill (`sequenceDiagram`, `classDiagram`, `stateDiagram`, `zenuml`). */
    val noteFill: Color = defaultNoteFill(background),
    /** Note and comment callout border stroke. */
    val noteBorder: Color = defaultNoteBorder(background),
    /** Note and comment callout text colour. */
    val noteText: Color = defaultNoteText(background, nodeText),
    /** Primary accent fill (activations, active Gantt tasks, Architecture service tiles, Quadrant points). */
    val accentFill: Color = defaultAccentFill(background),
    /** Primary accent border / vivid stroke (Architecture icon tile `#087EBF`, active task border, activation stroke). */
    val accentBorder: Color = defaultAccentBorder(background),
    /** Text/icon colour drawn on top of [accentBorder] or [accentFill]. */
    val accentText: Color = Color.White,
    /** Secondary header tint (Requirement element header band, secondary badges). */
    val secondaryFill: Color = defaultSecondaryFill(background),
    /** Warning / highlight surface fill (GitGraph tags, medium risk, high priority). */
    val warningFill: Color = defaultWarningFill(background),
    /** Warning / highlight border stroke. */
    val warningBorder: Color = defaultWarningBorder(background),
    /** Critical / urgent surface fill (Gantt `crit` tasks, Mindmap urgent nodes, Kanban VeryHigh priority). */
    val criticalFill: Color = defaultCriticalFill(background),
    /** Critical / urgent border and marker stroke. */
    val criticalBorder: Color = defaultCriticalBorder(background),
    /** Muted surface fill (Gantt `done` tasks, alternating grid bands, disabled items). */
    val mutedFill: Color = defaultMutedFill(background, nodeFill),
    /** Muted border / subtle grid line colour. */
    val mutedBorder: Color = defaultMutedBorder(background, nodeBorder),
    /** Ordered categorical accent stroke/series palette used by charts, branches, timelines, and treemaps. */
    val seriesPalette: List<Color> = defaultSeriesPalette(background),
    /** Ordered categorical tinted fill palette paired with [seriesPalette]. */
    val seriesFillPalette: List<Color> = defaultSeriesFillPalette(background, seriesPalette),
    /** C4 element card palette (`Person`, `System`, `Container`, `Component`, `External`). */
    val c4Palette: MaradonaC4Palette = MaradonaC4Palette.default(),
) {
    /** True when [background] has dark relative luminance. */
    val isDark: Boolean
        get() = isColorDark(background)

    /** Returns the categorical series/accent stroke colour at [index] (wrapping around [seriesPalette]). */
    fun seriesColor(index: Int): Color {
        if (seriesPalette.isEmpty()) return accentBorder
        return seriesPalette[index.mod(seriesPalette.size)]
    }

    /** Returns the categorical tinted fill colour at [index] (wrapping around [seriesFillPalette]). */
    fun seriesFill(index: Int): Color {
        if (seriesFillPalette.isEmpty()) return accentFill
        return seriesFillPalette[index.mod(seriesFillPalette.size)]
    }

    /**
     * Scales a baseline font size in `sp` proportionally to [nodeFontSize] (whose default is `14.sp`),
     * so embedding hosts with compact typography (e.g. `12.sp` or `13.sp` in IDE toolwindows)
     * scale all secondary diagram text harmoniously.
     */
    fun scaledSp(defaultSp: Float): TextUnit {
        val base = if (nodeFontSize.value > 0f) nodeFontSize.value / 14f else 1f
        return (defaultSp * base).sp
    }

    companion object {
        fun light(): MaradonaTheme = MaradonaTheme(
            background = Color(0xFFFFFCF0),
            nodeFill = Color(0xFFF2F0E5),
            nodeBorder = Color(0xFF8A8878),
            nodeText = Color(0xFF100F0F),
            edge = Color(0xFF100F0F),
            edgeLabelText = Color(0xFF100F0F),
            edgeLabelBackground = Color(0xFFFFFCF0),
            subgraphFill = Color(0xFFEAE6D8),
            subgraphBorder = Color(0xFF8A8878),
            subgraphText = Color(0xFF100F0F),
            error = Color(0xFFB3261E),
        )

        fun dark(): MaradonaTheme = MaradonaTheme(
            background = Color(0xFF100F0F),
            nodeFill = Color(0xFF282726),
            nodeBorder = Color(0xFF6F6E69),
            nodeText = Color(0xFFCECDC3),
            edge = Color(0xFFCECDC3),
            edgeLabelText = Color(0xFFB8B5A8),
            edgeLabelBackground = Color(0xFF100F0F),
            subgraphFill = Color(0xFF1C1B1A),
            subgraphBorder = Color(0xFF403E3C),
            subgraphText = Color(0xFF878580),
            error = Color(0xFFF2B8B5),
        )

        /**
         * Builds a complete [MaradonaTheme] from an embedding host's design tokens
         * (such as JetBrains Jewel / IntUi, Material 3, or a custom desktop/mobile theme).
         *
         * All secondary tokens (notes, activations, semantic statuses, tinted series fills,
         * and C4 card colours) are automatically derived from the supplied host tokens unless
         * explicitly overridden.
         */
        fun fromHost(
            background: Color,
            surface: Color,
            border: Color,
            text: Color,
            mutedText: Color = lerp(text, background, 0.35f),
            accent: Color = if (isColorDark(background)) Color(0xFF3574F0) else Color(0xFF3574F0),
            onAccent: Color = contrastingTextColor(accent),
            secondaryAccent: Color = if (isColorDark(background)) Color(0xFF24837B) else Color(0xFF24837B),
            warning: Color = if (isColorDark(background)) Color(0xFFE6A23C) else Color(0xFFD97706),
            error: Color = if (isColorDark(background)) Color(0xFFF75464) else Color(0xFFDB3B4B),
            containerSurface: Color = lerp(surface, background, 0.45f),
            containerBorder: Color = lerp(border, background, 0.2f),
            noteFill: Color? = null,
            noteBorder: Color? = null,
            noteText: Color? = null,
            seriesPalette: List<Color>? = null,
            seriesFillPalette: List<Color>? = null,
            c4Palette: MaradonaC4Palette? = null,
            fontFamily: FontFamily = FontFamily.Default,
            monospaceFontFamily: FontFamily = FontFamily.Monospace,
            baseFontSize: TextUnit = 13.sp,
            titleFontSize: TextUnit = (baseFontSize.value * 1.2f).sp,
            edgeLabelFontSize: TextUnit = (baseFontSize.value * 0.9f).sp,
            subgraphFontSize: TextUnit = baseFontSize,
            captionFontSize: TextUnit = (baseFontSize.value * 0.82f).sp,
        ): MaradonaTheme {
            val dark = isColorDark(background)
            val resolvedSeries = seriesPalette ?: listOf(
                accent,
                secondaryAccent,
                warning,
                lerp(accent, error, 0.55f),
                lerp(secondaryAccent, warning, 0.45f),
                error,
                lerp(accent, secondaryAccent, 0.5f),
                lerp(warning, error, 0.45f),
            )
            val resolvedSeriesFills = seriesFillPalette ?: resolvedSeries.map { c ->
                lerp(background, c, if (dark) 0.26f else 0.18f)
            }
            val resolvedNoteFill = noteFill ?: lerp(background, warning, if (dark) 0.22f else 0.18f)
            val resolvedNoteBorder = noteBorder ?: lerp(border, warning, 0.55f)
            val resolvedNoteText = noteText ?: text
            val mutedSurface = lerp(surface, background, 0.35f)
            val mutedStroke = lerp(border, background, 0.25f)
            return MaradonaTheme(
                background = background,
                nodeFill = surface,
                nodeBorder = border,
                nodeText = text,
                edge = lerp(text, border, 0.35f),
                edgeLabelText = text,
                edgeLabelBackground = background,
                subgraphFill = containerSurface,
                subgraphBorder = containerBorder,
                subgraphText = mutedText,
                error = error,
                fontFamily = fontFamily,
                nodeFontSize = baseFontSize,
                edgeLabelFontSize = edgeLabelFontSize,
                subgraphFontSize = subgraphFontSize,
                monospaceFontFamily = monospaceFontFamily,
                titleFontSize = titleFontSize,
                captionFontSize = captionFontSize,
                noteFill = resolvedNoteFill,
                noteBorder = resolvedNoteBorder,
                noteText = resolvedNoteText,
                accentFill = lerp(background, accent, if (dark) 0.30f else 0.18f),
                accentBorder = accent,
                accentText = onAccent,
                secondaryFill = lerp(background, secondaryAccent, if (dark) 0.28f else 0.16f),
                warningFill = lerp(background, warning, if (dark) 0.25f else 0.18f),
                warningBorder = warning,
                criticalFill = lerp(background, error, if (dark) 0.28f else 0.18f),
                criticalBorder = error,
                mutedFill = mutedSurface,
                mutedBorder = mutedStroke,
                seriesPalette = resolvedSeries,
                seriesFillPalette = resolvedSeriesFills,
                c4Palette = c4Palette ?: MaradonaC4Palette.fromAccent(
                    accent = accent,
                    surface = surface,
                    mutedSurface = mutedSurface,
                    mutedBorder = mutedStroke,
                    isDark = dark,
                ),
            )
        }

        /**
         * JetBrains Jewel / IntelliJ New UI Dark (`IntUiDark`) theme preset, using IntelliJ's
         * editor/toolwindow surface tokens (`#1E1F22`, `#2B2D30`, `#4E5157`, `#BCBEC4`, `#3574F0`)
         * and compact `13.sp` IDE typography.
         */
        fun jewelDark(
            fontFamily: FontFamily = FontFamily.SansSerif,
            monospaceFontFamily: FontFamily = FontFamily.Monospace,
        ): MaradonaTheme = fromHost(
            background = Color(0xFF1E1F22),
            surface = Color(0xFF2B2D30),
            border = Color(0xFF4E5157),
            text = Color(0xFFBCBEC4),
            mutedText = Color(0xFF868A91),
            accent = Color(0xFF3574F0),
            secondaryAccent = Color(0xFF389FD6),
            warning = Color(0xFFE6A23C),
            error = Color(0xFFF75464),
            containerSurface = Color(0xFF25262A),
            containerBorder = Color(0xFF43454A),
            seriesPalette = listOf(
                Color(0xFF3574F0), // IntelliJ Blue
                Color(0xFF389FD6), // IntelliJ Cyan
                Color(0xFF57965C), // IntelliJ Green
                Color(0xFFE6A23C), // IntelliJ Amber
                Color(0xFF9876AA), // Darcula Purple
                Color(0xFFF75464), // IntelliJ Red
                Color(0xFF24837B), // Teal
                Color(0xFFD07855), // Warm Copper
            ),
            fontFamily = fontFamily,
            monospaceFontFamily = monospaceFontFamily,
            baseFontSize = 13.sp,
        )

        /**
         * JetBrains Jewel / IntelliJ New UI Light (`IntUiLight`) theme preset, using IntelliJ's
         * light editor/toolwindow tokens (`#FFFFFF`, `#F7F8FA`, `#C9CCD6`, `#1E1F22`, `#3574F0`)
         * and compact `13.sp` IDE typography.
         */
        fun jewelLight(
            fontFamily: FontFamily = FontFamily.SansSerif,
            monospaceFontFamily: FontFamily = FontFamily.Monospace,
        ): MaradonaTheme = fromHost(
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFF7F8FA),
            border = Color(0xFFC9CCD6),
            text = Color(0xFF1E1F22),
            mutedText = Color(0xFF6C707E),
            accent = Color(0xFF3574F0),
            secondaryAccent = Color(0xFF1E88E5),
            warning = Color(0xFFD97706),
            error = Color(0xFFDB3B4B),
            containerSurface = Color(0xFFEBECF0),
            containerBorder = Color(0xFFD3D5DB),
            seriesPalette = listOf(
                Color(0xFF3574F0), // IntelliJ Blue
                Color(0xFF00796B), // Teal
                Color(0xFF2E7D32), // Green
                Color(0xFFD97706), // Amber
                Color(0xFF7B1FA2), // Purple
                Color(0xFFDB3B4B), // Red
                Color(0xFF0277BD), // Sky
                Color(0xFFBF360C), // Rust
            ),
            fontFamily = fontFamily,
            monospaceFontFamily = monospaceFontFamily,
            baseFontSize = 13.sp,
        )
    }
}

/**
 * Returns [lightText] when [fill] has dark relative luminance, or [darkText] when [fill] is light,
 * ensuring readable foreground text/icons over arbitrary host or user-supplied fills.
 */
fun contrastingTextColor(
    fill: Color,
    darkText: Color = Color(0xFF100F0F),
    lightText: Color = Color.White,
): Color = if (isColorDark(fill)) lightText else darkText

private fun isColorDark(c: Color): Boolean =
    (c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f) < 0.52f

private fun defaultNoteFill(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFF3B3219) else Color(0xFFFFF5AD)

private fun defaultNoteBorder(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFFD0A215) else Color(0xFFE3D296)

private fun defaultNoteText(bg: Color, nodeText: Color): Color =
    if (isColorDark(bg)) nodeText else Color(0xFF333333)

private fun defaultAccentFill(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFF1E3A8A) else Color(0xFFDBEAFE)

private fun defaultAccentBorder(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFF0A8FD8) else Color(0xFF087EBF)

private fun defaultSecondaryFill(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFF1A2E26) else Color(0xFFE8F4EE)

private fun defaultWarningFill(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFF3B2E12) else Color(0xFFF9F0D2)

private fun defaultWarningBorder(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFFD0A215) else Color(0xFFAD8301)

private fun defaultCriticalFill(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFF471E1B) else Color(0xFFFFEBE9)

private fun defaultCriticalBorder(bg: Color): Color =
    if (isColorDark(bg)) Color(0xFFD14D41) else Color(0xFFD14D41)

private fun defaultMutedFill(bg: Color, nodeFill: Color): Color =
    if (isColorDark(bg)) Color(0xFF282726) else lerp(nodeFill, bg, 0.35f)

private fun defaultMutedBorder(bg: Color, nodeBorder: Color): Color =
    if (isColorDark(bg)) Color(0xFF6F6E69) else nodeBorder

private fun defaultSeriesPalette(bg: Color): List<Color> =
    if (isColorDark(bg)) {
        listOf(
            Color(0xFF4385BE),
            Color(0xFFDA702C),
            Color(0xFF3AA99F),
            Color(0xFFD0A215),
            Color(0xFFCE5D97),
            Color(0xFF879A39),
            Color(0xFF8B7EC8),
            Color(0xFFEB6F62),
        )
    } else {
        listOf(
            Color(0xFF205EA6),
            Color(0xFFDA702C),
            Color(0xFF24837B),
            Color(0xFFAD8301),
            Color(0xFFA02F6F),
            Color(0xFF66800B),
            Color(0xFF5E409D),
            Color(0xFFD14D41),
        )
    }

private fun defaultSeriesFillPalette(bg: Color, strokes: List<Color>): List<Color> =
    if (isColorDark(bg)) {
        listOf(
            Color(0xFF1F3D58),
            Color(0xFF4A2817),
            Color(0xFF1C423E),
            Color(0xFF473715),
            Color(0xFF462237),
            Color(0xFF323E14),
            Color(0xFF2B2244),
            Color(0xFF3E2220),
        )
    } else {
        strokes.mapIndexed { idx, s ->
            when (idx) {
                0 -> Color(0xFFC7DFF2)
                1 -> Color(0xFFF2D2C2)
                2 -> Color(0xFFC3E4DF)
                3 -> Color(0xFFEFDFB4)
                4 -> Color(0xFFE5CADB)
                5 -> Color(0xFFD9E6B8)
                6 -> Color(0xFFE1DCF2)
                else -> lerp(bg, s, 0.22f)
            }
        }
    }

/** Parses `#RGB`, `#RRGGBB`, `#RRGGBBAA`, `rgb(...)`, `rgba(...)`, and CSS colour names. */
fun parseCssColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    val v = value.trim().lowercase()
    namedColors[v]?.let { return it }
    if (v.startsWith("rgb(") || v.startsWith("rgba(")) {
        val inside = v.substringAfter('(').substringBeforeLast(')', missingDelimiterValue = "")
        val parts = inside.split(',').map { it.trim() }
        if (parts.size == 3 || parts.size == 4) {
            val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val a = if (parts.size == 4) {
                ((parts[3].toFloatOrNull()?.coerceIn(0f, 1f) ?: return null) * 255f).toInt()
            } else 255
            return Color(red = r, green = g, blue = b, alpha = a)
        }
        return null
    }
    if (!v.startsWith("#")) return null
    val hex = v.removePrefix("#")
    return try {
        when (hex.length) {
            3 -> {
                val r = hex[0].digitToInt(16) * 17
                val g = hex[1].digitToInt(16) * 17
                val b = hex[2].digitToInt(16) * 17
                Color(r, g, b)
            }
            6 -> Color(hex.toLong(16).toInt() or (0xFF shl 24))
            8 -> {
                val n = hex.toLong(16)
                Color(
                    red = ((n shr 24) and 0xFF).toInt(),
                    green = ((n shr 16) and 0xFF).toInt(),
                    blue = ((n shr 8) and 0xFF).toInt(),
                    alpha = (n and 0xFF).toInt(),
                )
            }
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val namedColors = mapOf(
    "black" to Color.Black, "white" to Color.White, "red" to Color.Red,
    "green" to Color(0xFF008000), "blue" to Color.Blue, "yellow" to Color.Yellow,
    "orange" to Color(0xFFFFA500), "purple" to Color(0xFF800080),
    "pink" to Color(0xFFFFC0CB), "gray" to Color.Gray, "grey" to Color.Gray,
    "cyan" to Color.Cyan, "aqua" to Color.Cyan, "teal" to Color(0xFF008080),
    "lightblue" to Color(0xFFADD8E6), "lightgreen" to Color(0xFF90EE90),
    "lightyellow" to Color(0xFFFFFFE0), "magenta" to Color.Magenta,
    "transparent" to Color.Transparent,
)
