package dev.maradona.theme.jewel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaC4Palette
import dev.maradona.render.MaradonaTheme
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme

private fun Color.orNullIfUnspecified(): Color? =
    if (this == Color.Unspecified || alpha == 0f) null else this

private fun TextUnit.orDefault(fallback: TextUnit): TextUnit =
    if (this != TextUnit.Unspecified && value > 0f) this else fallback

private fun deriveJewelNodeSurface(
    background: Color,
    toolwindowBackground: Color?,
    border: Color,
    isDark: Boolean,
): Color {
    if (toolwindowBackground != null && toolwindowBackground != background) {
        return lerp(background, toolwindowBackground, 0.7f)
    }
    return if (isDark) {
        lerp(background, border, 0.28f)
    } else {
        lerp(background, border, 0.14f)
    }
}

/**
 * Derives a [MaradonaTheme] from JetBrains Jewel's [GlobalColors] and text styles
 * ([JewelTheme.globalColors], [JewelTheme.defaultTextStyle], [JewelTheme.editorTextStyle]),
 * applying any explicit host token overrides and [override] post-processor.
 *
 * Works with both standalone Jewel (`IntUiTheme`) and IntelliJ Platform IDE plugins
 * (`SwingBridgeTheme`). Desktop JVM only.
 */
fun MaradonaTheme.Companion.fromJewel(
    globalColors: GlobalColors,
    defaultTextStyle: TextStyle,
    editorTextStyle: TextStyle = defaultTextStyle,
    isDark: Boolean,
    contentColor: Color = Color.Unspecified,
    useToolwindowBackground: Boolean = false,
    background: Color? = null,
    nodeFill: Color? = null,
    nodeBorder: Color? = null,
    nodeText: Color? = null,
    accent: Color? = null,
    secondaryAccent: Color? = null,
    warning: Color? = null,
    error: Color? = null,
    containerSurface: Color? = null,
    containerBorder: Color? = null,
    noteFill: Color? = null,
    noteBorder: Color? = null,
    noteText: Color? = null,
    seriesPalette: List<Color>? = null,
    seriesFillPalette: List<Color>? = null,
    c4Palette: MaradonaC4Palette? = null,
    fontFamily: FontFamily? = null,
    monospaceFontFamily: FontFamily? = null,
    baseFontSize: TextUnit? = null,
    override: (MaradonaTheme) -> MaradonaTheme = { it },
): MaradonaTheme {
    val panelBg = globalColors.panelBackground.orNullIfUnspecified()
        ?: if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val toolwindowBg = globalColors.toolwindowBackground.orNullIfUnspecified()
    val resolvedBackground = background
        ?: if (useToolwindowBackground && toolwindowBg != null) toolwindowBg else panelBg
    val resolvedBorder = nodeBorder
        ?: globalColors.borders.normal.orNullIfUnspecified()
        ?: if (isDark) Color(0xFF4E5157) else Color(0xFFC9CCD6)
    val resolvedSurface = nodeFill
        ?: deriveJewelNodeSurface(
            background = resolvedBackground,
            toolwindowBackground = if (useToolwindowBackground) panelBg else toolwindowBg,
            border = resolvedBorder,
            isDark = isDark,
        )
    val resolvedText = nodeText
        ?: globalColors.text.normal.orNullIfUnspecified()
        ?: contentColor.orNullIfUnspecified()
        ?: defaultTextStyle.color.orNullIfUnspecified()
        ?: if (isDark) Color(0xFFBCBEC4) else Color(0xFF1E1F22)
    val resolvedMutedText = globalColors.text.info.orNullIfUnspecified()
        ?: lerp(resolvedText, resolvedBackground, 0.35f)
    val resolvedAccent = accent
        ?: globalColors.outlines.focused.orNullIfUnspecified()
        ?: globalColors.borders.focused.orNullIfUnspecified()
        ?: Color(0xFF3574F0)
    val resolvedSecondary = secondaryAccent
        ?: if (isDark) Color(0xFF389FD6) else Color(0xFF00796B)
    val resolvedWarning = warning
        ?: globalColors.outlines.warning.orNullIfUnspecified()
        ?: globalColors.text.warning.orNullIfUnspecified()
        ?: if (isDark) Color(0xFFE6A23C) else Color(0xFFD97706)
    val resolvedError = error
        ?: globalColors.outlines.error.orNullIfUnspecified()
        ?: globalColors.text.error.orNullIfUnspecified()
        ?: if (isDark) Color(0xFFF75464) else Color(0xFFDB3B4B)
    val resolvedContainerSurface = containerSurface
        ?: lerp(resolvedSurface, resolvedBackground, 0.45f)
    val resolvedContainerBorder = containerBorder
        ?: globalColors.borders.disabled.orNullIfUnspecified()
        ?: lerp(resolvedBorder, resolvedBackground, 0.25f)

    val resolvedFontFamily = fontFamily
        ?: defaultTextStyle.fontFamily
        ?: FontFamily.SansSerif
    val resolvedMonospace = monospaceFontFamily
        ?: editorTextStyle.fontFamily
        ?: FontFamily.Monospace
    val resolvedBaseSize = baseFontSize
        ?: defaultTextStyle.fontSize.orDefault(13.sp)

    val defaultJewelSeries = seriesPalette ?: if (isDark) {
        listOf(
            resolvedAccent,
            resolvedSecondary,
            Color(0xFF57965C),
            resolvedWarning,
            Color(0xFF9876AA),
            resolvedError,
            Color(0xFF24837B),
            Color(0xFFD07855),
        )
    } else {
        listOf(
            resolvedAccent,
            resolvedSecondary,
            Color(0xFF2E7D32),
            resolvedWarning,
            Color(0xFF7B1FA2),
            resolvedError,
            Color(0xFF0277BD),
            Color(0xFFBF360C),
        )
    }

    val derived = fromHost(
        background = resolvedBackground,
        surface = resolvedSurface,
        border = resolvedBorder,
        text = resolvedText,
        mutedText = resolvedMutedText,
        accent = resolvedAccent,
        secondaryAccent = resolvedSecondary,
        warning = resolvedWarning,
        error = resolvedError,
        containerSurface = resolvedContainerSurface,
        containerBorder = resolvedContainerBorder,
        noteFill = noteFill,
        noteBorder = noteBorder,
        noteText = noteText,
        seriesPalette = defaultJewelSeries,
        seriesFillPalette = seriesFillPalette,
        c4Palette = c4Palette,
        fontFamily = resolvedFontFamily,
        monospaceFontFamily = resolvedMonospace,
        baseFontSize = resolvedBaseSize,
    )
    return override(derived)
}

/**
 * Remembers a [MaradonaTheme] derived live from the surrounding [JewelTheme]
 * (`JewelTheme.globalColors`, `JewelTheme.defaultTextStyle`, `JewelTheme.editorTextStyle`,
 * and `JewelTheme.isDark`), with optional token overrides.
 */
@Composable
fun rememberJewelMaradonaTheme(
    useToolwindowBackground: Boolean = false,
    background: Color? = null,
    nodeFill: Color? = null,
    nodeBorder: Color? = null,
    nodeText: Color? = null,
    accent: Color? = null,
    secondaryAccent: Color? = null,
    warning: Color? = null,
    error: Color? = null,
    containerSurface: Color? = null,
    containerBorder: Color? = null,
    noteFill: Color? = null,
    noteBorder: Color? = null,
    noteText: Color? = null,
    seriesPalette: List<Color>? = null,
    seriesFillPalette: List<Color>? = null,
    c4Palette: MaradonaC4Palette? = null,
    fontFamily: FontFamily? = null,
    monospaceFontFamily: FontFamily? = null,
    baseFontSize: TextUnit? = null,
    override: (MaradonaTheme) -> MaradonaTheme = { it },
): MaradonaTheme {
    val globalColors = JewelTheme.globalColors
    val defaultTextStyle = JewelTheme.defaultTextStyle
    val editorTextStyle = JewelTheme.editorTextStyle
    val isDark = JewelTheme.isDark
    val contentColor = JewelTheme.contentColor
    return remember(
        globalColors,
        defaultTextStyle,
        editorTextStyle,
        isDark,
        contentColor,
        useToolwindowBackground,
        background,
        nodeFill,
        nodeBorder,
        nodeText,
        accent,
        secondaryAccent,
        warning,
        error,
        containerSurface,
        containerBorder,
        noteFill,
        noteBorder,
        noteText,
        seriesPalette,
        seriesFillPalette,
        c4Palette,
        fontFamily,
        monospaceFontFamily,
        baseFontSize,
        override,
    ) {
        MaradonaTheme.fromJewel(
            globalColors = globalColors,
            defaultTextStyle = defaultTextStyle,
            editorTextStyle = editorTextStyle,
            isDark = isDark,
            contentColor = contentColor,
            useToolwindowBackground = useToolwindowBackground,
            background = background,
            nodeFill = nodeFill,
            nodeBorder = nodeBorder,
            nodeText = nodeText,
            accent = accent,
            secondaryAccent = secondaryAccent,
            warning = warning,
            error = error,
            containerSurface = containerSurface,
            containerBorder = containerBorder,
            noteFill = noteFill,
            noteBorder = noteBorder,
            noteText = noteText,
            seriesPalette = seriesPalette,
            seriesFillPalette = seriesFillPalette,
            c4Palette = c4Palette,
            fontFamily = fontFamily,
            monospaceFontFamily = monospaceFontFamily,
            baseFontSize = baseFontSize,
            override = override,
        )
    }
}

/**
 * Provides a [MaradonaTheme] derived from the active [JewelTheme] via [LocalMaradonaTheme]
 * so all nested [dev.maradona.render.MaradonaDiagram] composables automatically match
 * the surrounding Jewel / IntelliJ look and feel.
 */
@Composable
fun ProvideJewelMaradonaTheme(
    useToolwindowBackground: Boolean = false,
    background: Color? = null,
    nodeFill: Color? = null,
    nodeBorder: Color? = null,
    nodeText: Color? = null,
    accent: Color? = null,
    secondaryAccent: Color? = null,
    warning: Color? = null,
    error: Color? = null,
    containerSurface: Color? = null,
    containerBorder: Color? = null,
    noteFill: Color? = null,
    noteBorder: Color? = null,
    noteText: Color? = null,
    seriesPalette: List<Color>? = null,
    seriesFillPalette: List<Color>? = null,
    c4Palette: MaradonaC4Palette? = null,
    fontFamily: FontFamily? = null,
    monospaceFontFamily: FontFamily? = null,
    baseFontSize: TextUnit? = null,
    override: (MaradonaTheme) -> MaradonaTheme = { it },
    content: @Composable () -> Unit,
) {
    val theme = rememberJewelMaradonaTheme(
        useToolwindowBackground = useToolwindowBackground,
        background = background,
        nodeFill = nodeFill,
        nodeBorder = nodeBorder,
        nodeText = nodeText,
        accent = accent,
        secondaryAccent = secondaryAccent,
        warning = warning,
        error = error,
        containerSurface = containerSurface,
        containerBorder = containerBorder,
        noteFill = noteFill,
        noteBorder = noteBorder,
        noteText = noteText,
        seriesPalette = seriesPalette,
        seriesFillPalette = seriesFillPalette,
        c4Palette = c4Palette,
        fontFamily = fontFamily,
        monospaceFontFamily = monospaceFontFamily,
        baseFontSize = baseFontSize,
        override = override,
    )
    CompositionLocalProvider(LocalMaradonaTheme provides theme, content = content)
}
