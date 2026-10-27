package dev.maradona.sample

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
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

    @Test
    fun viewportResetButtonAndExampleSwitchResetPanAndZoomWithoutVibration() {
        val scene = ImageComposeScene(width = 1280, height = 800, density = Density(1.5f)) {
            MaradonaSampleApp()
        }
        try {
            var clock = 1L
            fun advanceFrames(count: Int) {
                repeat(count) { scene.render(clock++ * 16_000_000L) }
            }
            fun capturePng(): ByteArray =
                scene.render(clock++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes

            // 1. Verify top-right reset-viewport-btn exists, is positioned in the top-right of the diagram canvas,
            //    and exposes accessible contentDescription and click action.
            advanceFrames(4)
            val initialBasicPng = capturePng()

            val resetViewportBtn = assertNotNull(findNodeByTag(scene, "reset-viewport-btn"))
            assertEquals(
                listOf("Reset zoom and pan to fit"),
                resetViewportBtn.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertNotNull(resetViewportBtn.config.getOrNull(SemanticsActions.OnClick))
            val btnBounds = resetViewportBtn.boundsInRoot
            assertTrue(btnBounds.right > 1200f && btnBounds.top < 80f, "Expected reset button at top-right of canvas, got $btnBounds")

            // Helper to pan + zoom the diagram canvas away from the fitted state
            fun panAndZoomCanvas() {
                // Drag pan on the diagram canvas (x: 900 -> 680, y: 400 -> 240)
                scene.sendPointerEvent(PointerEventType.Press, Offset(900f, 400f), timeMillis = clock * 16L)
                advanceFrames(1)
                scene.sendPointerEvent(PointerEventType.Move, Offset(680f, 240f), timeMillis = clock * 16L)
                advanceFrames(1)
                scene.sendPointerEvent(PointerEventType.Release, Offset(680f, 240f), timeMillis = clock * 16L)
                advanceFrames(1)
                // Cmd+scroll zoom in on the diagram canvas
                scene.sendPointerEvent(
                    eventType = PointerEventType.Scroll,
                    position = Offset(900f, 400f),
                    scrollDelta = Offset(0f, -6f),
                    timeMillis = clock * 16L,
                    keyboardModifiers = PointerKeyboardModifiers(isMetaPressed = true),
                )
                // Advance past the 90ms wheel commit window
                advanceFrames(10)
            }

            // 2. Pan and zoom the canvas, verify the rendered frame changed, then click reset-viewport-btn
            panAndZoomCanvas()
            val displacedPng = capturePng()
            assertFalse(
                initialBasicPng.contentEquals(displacedPng),
                "Expected pan + zoom to displace the diagram away from its initial fitted render",
            )
            File("build/screenshots/sample-app-panned-zoomed.png").apply {
                parentFile.mkdirs()
                writeBytes(displacedPng)
            }

            val resetClick = assertNotNull(findNodeByTag(scene, "reset-viewport-btn")?.config?.getOrNull(SemanticsActions.OnClick)?.action)
            resetClick.invoke()
            advanceFrames(3)
            val afterManualResetPng = capturePng()
            File("build/screenshots/sample-app-after-manual-reset.png").apply {
                parentFile.mkdirs()
                writeBytes(afterManualResetPng)
            }
            assertTrue(
                initialBasicPng.contentEquals(afterManualResetPng),
                "Expected clicking reset-viewport-btn to restore the exact initial fitted diagram view",
            )
            // Verify multi-frame stability (no vibration) across 5 subsequent frames
            repeat(5) { frameIdx ->
                val nextPng = capturePng()
                assertTrue(
                    afterManualResetPng.contentEquals(nextPng),
                    "Frame $frameIdx vibrated after manual viewport reset",
                )
            }

            // 3. Pan and zoom again, then re-click the already-selected "Basic" example button -> must also reset to fit
            panAndZoomCanvas()
            assertFalse(initialBasicPng.contentEquals(capturePng()))
            val basicClick = assertNotNull(findNodeByTag(scene, "example-btn-Basic")?.config?.getOrNull(SemanticsActions.OnClick)?.action)
            basicClick.invoke()
            advanceFrames(3)
            val afterReclickBasicPng = capturePng()
            assertTrue(
                initialBasicPng.contentEquals(afterReclickBasicPng),
                "Expected re-clicking the active example button to reset pan and zoom to fit",
            )

            // 4. Pan and zoom again, then switch to another diagram family ("Mindmap") -> must reset to fit and settle without vibration
            panAndZoomCanvas()
            val mindmapClick = assertNotNull(findNodeByTag(scene, "example-btn-Mindmap")?.config?.getOrNull(SemanticsActions.OnClick)?.action)
            mindmapClick.invoke()
            advanceFrames(3)
            val mindmapFittedPng = capturePng()
            repeat(5) { frameIdx ->
                val nextPng = capturePng()
                assertTrue(
                    mindmapFittedPng.contentEquals(nextPng),
                    "Mindmap vibrated on frame $frameIdx after switching from a panned/zoomed diagram",
                )
            }

            // Pan and zoom Mindmap, then click reset-viewport-btn -> must return to mindmapFittedPng without vibration
            panAndZoomCanvas()
            val mindmapDisplacedPng = capturePng()
            assertFalse(mindmapFittedPng.contentEquals(mindmapDisplacedPng))
            assertNotNull(findNodeByTag(scene, "reset-viewport-btn")?.config?.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            advanceFrames(3)
            val mindmapResetPng = capturePng()
            assertTrue(
                mindmapFittedPng.contentEquals(mindmapResetPng),
                "Expected reset-viewport-btn on Mindmap to restore exact fitted Mindmap view",
            )
            repeat(5) { frameIdx ->
                assertTrue(
                    mindmapResetPng.contentEquals(capturePng()),
                    "Mindmap vibrated on frame $frameIdx after manual reset-to-fit",
                )
            }

            // 5. Pan and zoom Mindmap again, then edit the source text in TextField to switch diagram type ->
            //    must reset pan/zoom to fit and settle without vibration, and reset-viewport-btn must also work afterward
            panAndZoomCanvas()
            val textFieldNode = assertNotNull(findNodeByTag(scene, "source-text-field"))
            val setTextAction = assertNotNull(textFieldNode.config.getOrNull(SemanticsActions.SetText)?.action)
            setTextAction.invoke(AnnotatedString(sampleExamples.getValue("ZenUML")))
            advanceFrames(3)
            val zenumlFittedPng = capturePng()
            repeat(5) { frameIdx ->
                assertTrue(
                    zenumlFittedPng.contentEquals(capturePng()),
                    "ZenUML vibrated on frame $frameIdx after source text change from panned/zoomed state",
                )
            }
            panAndZoomCanvas()
            assertFalse(zenumlFittedPng.contentEquals(capturePng()))
            assertNotNull(findNodeByTag(scene, "reset-viewport-btn")?.config?.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            advanceFrames(3)
            assertTrue(
                zenumlFittedPng.contentEquals(capturePng()),
                "Expected reset-viewport-btn after source edit to restore exact fitted ZenUML view",
            )
        } finally {
            scene.close()
        }
    }
}
