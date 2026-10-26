package dev.maradona.theme.jewel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class JewelMaradonaThemeTest {

    @Test
    fun provideJewelMaradonaThemeDerivesLiveTokensFromIntUiDarkAndLight() {
        var darkCaptured: MaradonaTheme? = null
        var darkExpectedBg: Color? = null
        var darkExpectedBorder: Color? = null
        var darkExpectedText: Color? = null
        var darkExpectedAccent: Color? = null

        val darkScene = ImageComposeScene(width = 640, height = 360, density = Density(1.5f)) {
            IntUiTheme(isDark = true) {
                darkExpectedBg = JewelTheme.globalColors.panelBackground
                darkExpectedBorder = JewelTheme.globalColors.borders.normal
                darkExpectedText = JewelTheme.globalColors.text.normal
                darkExpectedAccent = JewelTheme.globalColors.outlines.focused
                ProvideJewelMaradonaTheme {
                    darkCaptured = LocalMaradonaTheme.current
                    MaradonaDiagram(
                        source = """
                            flowchart LR
                              Jewel[IntUiTheme Dark] --> Adapter[ProvideJewelMaradonaTheme]
                              Adapter --> Diagram([MaradonaDiagram])
                        """.trimIndent(),
                        modifier = Modifier.fillMaxSize(),
                        zoomEnabled = false,
                    )
                }
            }
        }
        try {
            repeat(3) { frame -> darkScene.render((frame + 1) * 16_000_000L) }
            val png = darkScene.render(80_000_000L).encodeToData(EncodedImageFormat.PNG)
            assertNotNull(png)
            val resolvedDark = assertNotNull(darkCaptured)
            assertTrue(resolvedDark.isDark)
            assertEquals(darkExpectedBg, resolvedDark.background)
            assertEquals(darkExpectedBorder, resolvedDark.nodeBorder)
            assertEquals(darkExpectedText, resolvedDark.nodeText)
            assertEquals(darkExpectedAccent, resolvedDark.accentBorder)
            assertNotEquals(resolvedDark.background, resolvedDark.nodeFill)
        } finally {
            darkScene.close()
        }

        var lightCaptured: MaradonaTheme? = null
        var lightExpectedBg: Color? = null
        val customAccent = Color(0xFF0E7490)
        val lightScene = ImageComposeScene(width = 640, height = 360, density = Density(1.5f)) {
            IntUiTheme(isDark = false) {
                lightExpectedBg = JewelTheme.globalColors.panelBackground
                ProvideJewelMaradonaTheme(
                    accent = customAccent,
                    baseFontSize = 12.sp,
                    override = { it.copy(edge = customAccent) },
                ) {
                    lightCaptured = LocalMaradonaTheme.current
                    MaradonaDiagram(
                        source = """
                            sequenceDiagram
                              participant Toolwindow
                              participant Maradona
                              Toolwindow->>Maradona: Render with Jewel Light
                        """.trimIndent(),
                        modifier = Modifier.fillMaxSize(),
                        zoomEnabled = false,
                    )
                }
            }
        }
        try {
            repeat(3) { frame -> lightScene.render((frame + 1) * 16_000_000L) }
            val resolvedLight = assertNotNull(lightCaptured)
            assertFalse(resolvedLight.isDark)
            assertEquals(lightExpectedBg, resolvedLight.background)
            assertEquals(customAccent, resolvedLight.accentBorder)
            assertEquals(customAccent, resolvedLight.edge)
            assertEquals(12.sp, resolvedLight.nodeFontSize)
        } finally {
            lightScene.close()
        }
    }
}
