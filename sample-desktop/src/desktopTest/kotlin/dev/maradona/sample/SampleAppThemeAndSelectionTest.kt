package dev.maradona.sample

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.theme.jewel.ProvideJewelMaradonaTheme
import dev.maradona.theme.material3.ProvideMaterial3MaradonaTheme
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class SampleAppThemeAndSelectionTest {

    private fun findNodeByTag(scene: ImageComposeScene, tag: String): SemanticsNode? {
        fun search(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) return node
            for (child in node.children) {
                search(child)?.let { return it }
            }
            return null
        }
        for (owner in scene.semanticsOwners) {
            search(owner.rootSemanticsNode)?.let { return it }
        }
        return null
    }

    private fun countMatchingPixelsInRect(
        pngBytes: ByteArray,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        targetR: Int,
        targetG: Int,
        targetB: Int,
        tolerance: Int = 6,
    ): Int {
        val img = ImageIO.read(ByteArrayInputStream(pngBytes))
        var count = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(img.height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(img.width)) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (abs(r - targetR) <= tolerance && abs(g - targetG) <= tolerance && abs(b - targetB) <= tolerance) {
                    count++
                }
            }
        }
        return count
    }

    @Test
    fun runtimeThemeAndDiagramSelectionUpdateEntireScreenAndAccessibilityState() {
        val scene = ImageComposeScene(width = 1280, height = 800, density = Density(1.5f)) {
            MaradonaSampleApp(
                hostThemeWrapper = { preset, content ->
                    when (preset) {
                        SampleThemePreset.MaterialLight,
                        SampleThemePreset.MaterialDark -> ProvideMaterial3MaradonaTheme(content = content)
                        SampleThemePreset.JewelLight -> IntUiTheme(isDark = false) {
                            ProvideJewelMaradonaTheme(content = content)
                        }
                        SampleThemePreset.JewelDark -> IntUiTheme(isDark = true) {
                            ProvideJewelMaradonaTheme(content = content)
                        }
                        SampleThemePreset.Light,
                        SampleThemePreset.Dark -> CompositionLocalProvider(
                            LocalMaradonaTheme provides preset.theme,
                            content = content,
                        )
                    }
                },
            )
        }
        try {
            var clock = 1L
            fun settleAndCapture(): ByteArray {
                repeat(4) { scene.render(clock++ * 16_000_000L) }
                return scene.render(clock++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            }

            // 1. Initial state: "Basic" diagram and "Light" theme are selected
            val initialPng = settleAndCapture()
            File("build/screenshots/sample-app-light-basic.png").apply {
                parentFile.mkdirs()
                writeBytes(initialPng)
            }

            val basicBtn0 = assertNotNull(findNodeByTag(scene, "example-btn-Basic"))
            val shapesBtn0 = assertNotNull(findNodeByTag(scene, "example-btn-Shapes"))
            val lightBtn0 = assertNotNull(findNodeByTag(scene, "theme-btn-Light"))
            val jewelDarkBtn0 = assertNotNull(findNodeByTag(scene, "theme-btn-JewelDark"))

            assertEquals(true, basicBtn0.config.getOrNull(SemanticsProperties.Selected))
            assertEquals("Selected", basicBtn0.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(Role.Tab, basicBtn0.config.getOrNull(SemanticsProperties.Role))

            assertEquals(false, shapesBtn0.config.getOrNull(SemanticsProperties.Selected))
            assertEquals("Not selected", shapesBtn0.config.getOrNull(SemanticsProperties.StateDescription))

            assertEquals(true, lightBtn0.config.getOrNull(SemanticsProperties.Selected))
            assertEquals(false, jewelDarkBtn0.config.getOrNull(SemanticsProperties.Selected))

            // Verify visual highlight on selected "Basic" button (#205EA6 primary fill) vs unselected "Shapes" button
            val basicBounds0 = basicBtn0.boundsInRoot
            val shapesBounds0 = shapesBtn0.boundsInRoot
            val basicPrimaryPx0 = countMatchingPixelsInRect(
                initialPng,
                basicBounds0.left.toInt(),
                basicBounds0.top.toInt(),
                basicBounds0.right.toInt(),
                basicBounds0.bottom.toInt(),
                0x20,
                0x5E,
                0xA6,
            )
            val shapesPrimaryPx0 = countMatchingPixelsInRect(
                initialPng,
                shapesBounds0.left.toInt(),
                shapesBounds0.top.toInt(),
                shapesBounds0.right.toInt(),
                shapesBounds0.bottom.toInt(),
                0x20,
                0x5E,
                0xA6,
            )
            assertTrue(basicPrimaryPx0 > 400, "Expected selected Basic button to have primary fill #205EA6 ($basicPrimaryPx0 px)")
            assertEquals(0, shapesPrimaryPx0, "Expected unselected Shapes button to have 0 primary fill pixels")

            // Verify initial Light editor panel (#F2F0E5) and diagram canvas (#FFFCF0)
            val lightEditorPx = countMatchingPixelsInRect(initialPng, 0, 0, 570, 800, 0xF2, 0xF0, 0xE5)
            val lightDiagramPx = countMatchingPixelsInRect(initialPng, 570, 0, 1280, 800, 0xFF, 0xFC, 0xF0)
            assertTrue(lightEditorPx > 40_000, "Expected Light editor panel surface #F2F0E5 ($lightEditorPx px)")
            assertTrue(lightDiagramPx > 100_000, "Expected Light diagram background #FFFCF0 ($lightDiagramPx px)")

            // 2. Runtime click: select "Shapes" diagram button AND "JewelDark" theme button
            assertNotNull(shapesBtn0.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            assertNotNull(jewelDarkBtn0.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            val jewelDarkPng = settleAndCapture()
            File("build/screenshots/sample-app-jewel-dark-shapes.png").apply {
                parentFile.mkdirs()
                writeBytes(jewelDarkPng)
            }

            val basicBtn1 = assertNotNull(findNodeByTag(scene, "example-btn-Basic"))
            val shapesBtn1 = assertNotNull(findNodeByTag(scene, "example-btn-Shapes"))
            val lightBtn1 = assertNotNull(findNodeByTag(scene, "theme-btn-Light"))
            val jewelDarkBtn1 = assertNotNull(findNodeByTag(scene, "theme-btn-JewelDark"))

            assertFalse(basicBtn1.config.getOrNull(SemanticsProperties.Selected) ?: true)
            assertEquals("Not selected", basicBtn1.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(shapesBtn1.config.getOrNull(SemanticsProperties.Selected) ?: false)
            assertEquals("Selected", shapesBtn1.config.getOrNull(SemanticsProperties.StateDescription))

            assertFalse(lightBtn1.config.getOrNull(SemanticsProperties.Selected) ?: true)
            assertTrue(jewelDarkBtn1.config.getOrNull(SemanticsProperties.Selected) ?: false)

            // Verify selected "Shapes" button now carries Jewel primary blue (#3574F0) while "Basic" does not
            val shapesBounds1 = shapesBtn1.boundsInRoot
            val basicBounds1 = basicBtn1.boundsInRoot
            val shapesJewelBluePx = countMatchingPixelsInRect(
                jewelDarkPng,
                shapesBounds1.left.toInt(),
                shapesBounds1.top.toInt(),
                shapesBounds1.right.toInt(),
                shapesBounds1.bottom.toInt(),
                0x35,
                0x74,
                0xF0,
            )
            val basicJewelBluePx = countMatchingPixelsInRect(
                jewelDarkPng,
                basicBounds1.left.toInt(),
                basicBounds1.top.toInt(),
                basicBounds1.right.toInt(),
                basicBounds1.bottom.toInt(),
                0x35,
                0x74,
                0xF0,
            )
            assertTrue(shapesJewelBluePx > 400, "Expected selected Shapes button to have Jewel blue fill #3574F0 ($shapesJewelBluePx px)")
            assertEquals(0, basicJewelBluePx, "Expected deselected Basic button to have 0 Jewel blue fill pixels")

            // Verify ENTIRE screen (both EditorPanel on left and Diagram on right) switched to Jewel Dark (#2B2D30 / #27292C)
            val jewelEditorDarkPx = countMatchingPixelsInRect(jewelDarkPng, 0, 0, 570, 800, 0x2B, 0x2D, 0x30)
            val jewelDiagramDarkPx = countMatchingPixelsInRect(jewelDarkPng, 570, 0, 1280, 800, 0x27, 0x29, 0x2C)
            assertTrue(jewelEditorDarkPx > 40_000, "Expected Jewel Dark editor panel surface #2B2D30 ($jewelEditorDarkPx px)")
            assertTrue(jewelDiagramDarkPx > 100_000, "Expected Jewel Dark diagram background #27292C ($jewelDiagramDarkPx px)")

            // 3. Runtime click on Reset button restores "Basic" selection
            val resetBtn = assertNotNull(findNodeByTag(scene, "reset-btn"))
            assertNotNull(resetBtn.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            settleAndCapture()
            assertTrue(assertNotNull(findNodeByTag(scene, "example-btn-Basic")).config.getOrNull(SemanticsProperties.Selected) ?: false)
            assertFalse(assertNotNull(findNodeByTag(scene, "example-btn-Shapes")).config.getOrNull(SemanticsProperties.Selected) ?: true)
        } finally {
            scene.close()
        }
    }
}
