package dev.maradona.theme.material3

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.maradona.render.MaradonaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Material3MaradonaThemeTest {

    @Test
    fun fromMaterial3DerivesLightAndDarkTokensFromColorSchemeAndTypography() {
        val lightScheme = lightColorScheme(
            primary = Color(0xFF0061A4),
            secondary = Color(0xFF535F70),
            tertiary = Color(0xFF6B5778),
            error = Color(0xFFBA1A1A),
            background = Color(0xFFFDFCFF),
            surface = Color(0xFFF8F9FF),
            onSurface = Color(0xFF1A1C1E),
            outline = Color(0xFF73777F),
        )
        val typography = Typography(
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp),
        )

        val lightTheme = MaradonaTheme.fromMaterial3(lightScheme, typography)
        assertFalse(lightTheme.isDark)
        assertEquals(Color(0xFFFDFCFF), lightTheme.background)
        assertEquals(Color(0xFF73777F), lightTheme.nodeBorder)
        assertEquals(Color(0xFF1A1C1E), lightTheme.nodeText)
        assertEquals(Color(0xFF0061A4), lightTheme.accentBorder)
        assertEquals(Color(0xFFBA1A1A), lightTheme.criticalBorder)
        assertEquals(FontFamily.SansSerif, lightTheme.fontFamily)
        assertEquals(15.sp, lightTheme.nodeFontSize)
        assertEquals(18.sp, lightTheme.titleFontSize)
        assertEquals(13.sp, lightTheme.edgeLabelFontSize)

        val darkScheme = darkColorScheme(
            primary = Color(0xFF9ECAFF),
            background = Color(0xFF1A1C1E),
            onSurface = Color(0xFFE2E2E6),
        )
        val darkTheme = MaradonaTheme.fromMaterial3(darkScheme)
        assertTrue(darkTheme.isDark)
        assertEquals(Color(0xFF1A1C1E), darkTheme.background)
        assertEquals(Color(0xFFE2E2E6), darkTheme.nodeText)
        assertEquals(Color(0xFF9ECAFF), darkTheme.accentBorder)
    }

    @Test
    fun fromMaterial3RespectsUseSurfaceAsBackgroundExplicitOverridesAndPostProcessor() {
        val scheme = lightColorScheme(
            background = Color(0xFFFDFCFF),
            surface = Color(0xFFEEF2F8),
            primary = Color(0xFF0061A4),
        )
        val customNodeFill = Color(0xFFE0ECFF)
        val customAccent = Color(0xFF7C3AED)
        val customEdge = Color(0xFF334155)

        val theme = MaradonaTheme.fromMaterial3(
            colorScheme = scheme,
            useSurfaceAsBackground = true,
            nodeFill = customNodeFill,
            accent = customAccent,
            baseFontSize = 12.sp,
            override = { it.copy(edge = customEdge) },
        )

        assertEquals(Color(0xFFEEF2F8), theme.background)
        assertEquals(customNodeFill, theme.nodeFill)
        assertEquals(customAccent, theme.accentBorder)
        assertEquals(Color.White, theme.accentText)
        assertEquals(12.sp, theme.nodeFontSize)
        assertEquals(customEdge, theme.edge)

        // Overriding accent with a light color derives a dark contrasting accentText
        val lightAccentTheme = MaradonaTheme.fromMaterial3(
            colorScheme = scheme,
            accent = Color(0xFFFDE047),
        )
        assertEquals(Color(0xFF100F0F), lightAccentTheme.accentText)
    }
}
