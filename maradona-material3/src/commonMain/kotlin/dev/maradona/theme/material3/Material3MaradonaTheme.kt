package dev.maradona.theme.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaC4Palette
import dev.maradona.render.MaradonaTheme

private fun Color.orNullIfUnspecified(): Color? =
    if (this == Color.Unspecified || alpha == 0f) null else this

private fun TextUnit.orDefault(fallback: TextUnit): TextUnit =
    if (this != TextUnit.Unspecified && value > 0f) this else fallback

private fun contrastingForeground(background: Color): Color {
    val luminance = 0.2126f * background.red + 0.7152f * background.green + 0.0722f * background.blue
    return if (luminance < 0.52f) Color.White else Color(0xFF100F0F)
}

/**
 * Derives a [MaradonaTheme] from a Material 3 [ColorScheme] and [Typography],
 * applying any explicit host token overrides and [override] post-processor.
 *
 * Available on all supported Compose Multiplatform targets (Desktop, Android, iOS).
 */
fun MaradonaTheme.Companion.fromMaterial3(
    colorScheme: ColorScheme,
    typography: Typography = Typography(),
    useSurfaceAsBackground: Boolean = false,
    background: Color? = null,
    nodeFill: Color? = null,
    nodeBorder: Color? = null,
    nodeText: Color? = null,
    accent: Color? = null,
    onAccent: Color? = null,
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
    val resolvedBackground = background
        ?: if (useSurfaceAsBackground) {
            colorScheme.surface.orNullIfUnspecified() ?: colorScheme.background
        } else {
            colorScheme.background.orNullIfUnspecified() ?: colorScheme.surface
        }
    val resolvedSurface = nodeFill
        ?: colorScheme.surfaceContainerHigh.orNullIfUnspecified()
        ?: colorScheme.surfaceVariant.orNullIfUnspecified()
        ?: lerp(resolvedBackground, colorScheme.onSurface, 0.08f)
    val resolvedBorder = nodeBorder
        ?: colorScheme.outline.orNullIfUnspecified()
        ?: lerp(colorScheme.onSurface, resolvedBackground, 0.45f)
    val resolvedText = nodeText
        ?: colorScheme.onSurface.orNullIfUnspecified()
        ?: Color(0xFF1C1B1F)
    val resolvedMutedText = colorScheme.onSurfaceVariant.orNullIfUnspecified()
        ?: lerp(resolvedText, resolvedBackground, 0.35f)
    val explicitAccent = accent?.orNullIfUnspecified()
    val resolvedAccent = explicitAccent
        ?: colorScheme.primary.orNullIfUnspecified()
        ?: Color(0xFF6750A4)
    val resolvedOnAccent = onAccent?.orNullIfUnspecified()
        ?: if (explicitAccent != null) {
            contrastingForeground(resolvedAccent)
        } else {
            colorScheme.onPrimary.orNullIfUnspecified() ?: contrastingForeground(resolvedAccent)
        }
    val resolvedSecondary = secondaryAccent
        ?: colorScheme.secondary.orNullIfUnspecified()
        ?: resolvedAccent
    val resolvedWarning = warning
        ?: colorScheme.tertiary.orNullIfUnspecified()
        ?: Color(0xFF7D5260)
    val resolvedError = error
        ?: colorScheme.error.orNullIfUnspecified()
        ?: Color(0xFFB3261E)
    val resolvedContainerSurface = containerSurface
        ?: colorScheme.surfaceContainerLow.orNullIfUnspecified()
        ?: lerp(resolvedSurface, resolvedBackground, 0.45f)
    val resolvedContainerBorder = containerBorder
        ?: colorScheme.outlineVariant.orNullIfUnspecified()
        ?: lerp(resolvedBorder, resolvedBackground, 0.25f)

    val resolvedFontFamily = fontFamily
        ?: typography.bodyMedium.fontFamily
        ?: typography.bodyLarge.fontFamily
        ?: FontFamily.Default
    val resolvedMonospace = monospaceFontFamily ?: FontFamily.Monospace
    val resolvedBaseSize = baseFontSize
        ?: typography.bodyMedium.fontSize.orDefault(14.sp)
    val resolvedTitleSize = typography.titleMedium.fontSize.orDefault((resolvedBaseSize.value * 1.2f).sp)
    val resolvedEdgeLabelSize = typography.bodySmall.fontSize.orDefault((resolvedBaseSize.value * 0.86f).sp)
    val resolvedSubgraphSize = typography.titleSmall.fontSize.orDefault(resolvedBaseSize)
    val resolvedCaptionSize = typography.labelSmall.fontSize.orDefault((resolvedBaseSize.value * 0.78f).sp)

    val derived = fromHost(
        background = resolvedBackground,
        surface = resolvedSurface,
        border = resolvedBorder,
        text = resolvedText,
        mutedText = resolvedMutedText,
        accent = resolvedAccent,
        onAccent = resolvedOnAccent,
        secondaryAccent = resolvedSecondary,
        warning = resolvedWarning,
        error = resolvedError,
        containerSurface = resolvedContainerSurface,
        containerBorder = resolvedContainerBorder,
        noteFill = noteFill ?: colorScheme.tertiaryContainer.orNullIfUnspecified(),
        noteBorder = noteBorder ?: resolvedWarning,
        noteText = noteText ?: colorScheme.onTertiaryContainer.orNullIfUnspecified() ?: resolvedText,
        seriesPalette = seriesPalette,
        seriesFillPalette = seriesFillPalette,
        c4Palette = c4Palette,
        fontFamily = resolvedFontFamily,
        monospaceFontFamily = resolvedMonospace,
        baseFontSize = resolvedBaseSize,
        titleFontSize = resolvedTitleSize,
        edgeLabelFontSize = resolvedEdgeLabelSize,
        subgraphFontSize = resolvedSubgraphSize,
        captionFontSize = resolvedCaptionSize,
    )
    return override(derived)
}

/**
 * Remembers a [MaradonaTheme] derived from the active [MaterialTheme.colorScheme]
 * and [MaterialTheme.typography], with optional token overrides.
 */
@Composable
fun rememberMaterial3MaradonaTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    typography: Typography = MaterialTheme.typography,
    useSurfaceAsBackground: Boolean = false,
    background: Color? = null,
    nodeFill: Color? = null,
    nodeBorder: Color? = null,
    nodeText: Color? = null,
    accent: Color? = null,
    onAccent: Color? = null,
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
): MaradonaTheme = remember(
    colorScheme,
    typography,
    useSurfaceAsBackground,
    background,
    nodeFill,
    nodeBorder,
    nodeText,
    accent,
    onAccent,
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
    MaradonaTheme.fromMaterial3(
        colorScheme = colorScheme,
        typography = typography,
        useSurfaceAsBackground = useSurfaceAsBackground,
        background = background,
        nodeFill = nodeFill,
        nodeBorder = nodeBorder,
        nodeText = nodeText,
        accent = accent,
        onAccent = onAccent,
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

/**
 * Provides a [MaradonaTheme] derived from the active [MaterialTheme] via [LocalMaradonaTheme]
 * so all nested [dev.maradona.render.MaradonaDiagram] composables automatically follow Material 3.
 */
@Composable
fun ProvideMaterial3MaradonaTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    typography: Typography = MaterialTheme.typography,
    useSurfaceAsBackground: Boolean = false,
    background: Color? = null,
    nodeFill: Color? = null,
    nodeBorder: Color? = null,
    nodeText: Color? = null,
    accent: Color? = null,
    onAccent: Color? = null,
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
    val theme = rememberMaterial3MaradonaTheme(
        colorScheme = colorScheme,
        typography = typography,
        useSurfaceAsBackground = useSurfaceAsBackground,
        background = background,
        nodeFill = nodeFill,
        nodeBorder = nodeBorder,
        nodeText = nodeText,
        accent = accent,
        onAccent = onAccent,
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
