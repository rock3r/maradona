package dev.maradona.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaradonaThemeTest {

    @Test
    fun lightAndDarkPresetsDetectLuminanceAndProvideCompleteTokens() {
        val light = MaradonaTheme.light()
        val dark = MaradonaTheme.dark()

        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertEquals(Color(0xFFFFF5AD), light.noteFill)
        assertEquals(Color(0xFF3B3219), dark.noteFill)
        assertTrue(light.seriesPalette.size >= 6)
        assertTrue(dark.seriesPalette.size >= 6)
        assertEquals(light.seriesPalette[0], light.seriesColor(0))
        assertEquals(light.seriesPalette[0], light.seriesColor(light.seriesPalette.size))
    }

    @Test
    fun jewelPresetsMatchIntUiSurfacesAndTypography() {
        val jewelDark = MaradonaTheme.jewelDark()
        val jewelLight = MaradonaTheme.jewelLight()

        assertTrue(jewelDark.isDark)
        assertFalse(jewelLight.isDark)
        assertEquals(Color(0xFF1E1F22), jewelDark.background)
        assertEquals(Color(0xFF2B2D30), jewelDark.nodeFill)
        assertEquals(Color(0xFF3574F0), jewelDark.accentBorder)
        assertEquals(13.sp, jewelDark.nodeFontSize)
        assertEquals(FontFamily.SansSerif, jewelDark.fontFamily)
        assertEquals(FontFamily.Monospace, jewelDark.monospaceFontFamily)

        assertEquals(Color(0xFFFFFFFF), jewelLight.background)
        assertEquals(Color(0xFFF7F8FA), jewelLight.nodeFill)
        assertEquals(Color(0xFF3574F0), jewelLight.accentBorder)
        assertEquals(13.sp, jewelLight.nodeFontSize)
    }

    @Test
    fun fromHostDerivesConsistentSemanticAndC4Tokens() {
        val customAccent = Color(0xFF7C3AED)
        val hostTheme = MaradonaTheme.fromHost(
            background = Color(0xFF18181B),
            surface = Color(0xFF27272A),
            border = Color(0xFF52525B),
            text = Color(0xFFF4F4F5),
            accent = customAccent,
            baseFontSize = 12.sp,
        )

        assertTrue(hostTheme.isDark)
        assertEquals(customAccent, hostTheme.accentBorder)
        assertEquals(customAccent, hostTheme.c4Palette.containerFill)
        assertEquals(customAccent, hostTheme.seriesColor(0))
        assertEquals(12.sp, hostTheme.nodeFontSize)
        // scaledSp scales proportionally to nodeFontSize / 14.sp
        assertEquals((14f * (12f / 14f)).sp.value, hostTheme.scaledSp(14f).value, 0.01f)
    }

    @Test
    fun lightHostAccentSelectsReadableDarkForegrounds() {
        val paleTheme = MaradonaTheme.fromHost(
            background = Color(0xFF101012),
            surface = Color(0xFF1E1E22),
            border = Color(0xFF4A4A52),
            text = Color(0xFFF5F5F7),
            accent = Color.Yellow,
            edgeLabelFontSize = 15.sp,
            subgraphFontSize = 18.sp,
            captionFontSize = 10.sp,
        )

        // Yellow is high-luminance, so onAccent and C4 containerText should be dark, not white.
        assertEquals(Color(0xFF100F0F), paleTheme.accentText)
        assertEquals(Color(0xFF100F0F), paleTheme.c4Palette.containerText)
        assertEquals(15.sp, paleTheme.edgeLabelFontSize)
        assertEquals(18.sp, paleTheme.subgraphFontSize)
        assertEquals(10.sp, paleTheme.captionFontSize)
        assertEquals(Color.White, contrastingTextColor(Color(0xFF1168BD)))
        assertEquals(Color(0xFF100F0F), contrastingTextColor(Color(0xFFFFF5AD)))
    }
}
