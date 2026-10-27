package dev.maradona.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.maradona.render.MaradonaDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalComposeUiApi::class)
class IosRuntimeAndScreenshotTest {

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

    private fun countNonBackgroundPixels(pngBytes: ByteArray): Int {
        val skiaImage = Image.makeFromEncoded(pngBytes)
        val bitmap = Bitmap.makeFromImage(skiaImage)
        val bgColor = bitmap.getColor(2, 2)
        var nonBg = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (bitmap.getColor(x, y) != bgColor) {
                    nonBg++
                }
            }
        }
        return nonBg
    }

    @Test
    fun all23DiagramFamiliesRenderNonBlankScreenshotsAndSettleWithoutVibrationOnIosSimulator() {
        for ((name, source) in sampleExamples) {
            val natHistory = mutableListOf<IntSize>()
            val scene = ImageComposeScene(width = 750, height = 900, density = Density(2f)) {
                MaradonaDiagram(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    onNaturalSize = { natHistory.add(it) },
                )
            }
            try {
                repeat(3) { frame ->
                    scene.render((frame + 1) * 16_000_000L)
                }
                val settledNat = natHistory.toList()
                assertEquals(1, settledNat.size, "Expected 1 naturalSize pass on iOS for $name, got $settledNat")
                assertTrue(settledNat.single().width > 0 && settledNat.single().height > 0)

                val frame4Image = scene.render(4 * 16_000_000L)
                val frame4Png = assertNotNull(frame4Image.encodeToData(EncodedImageFormat.PNG)).bytes
                val nonBgPixels = countNonBackgroundPixels(frame4Png)
                assertTrue(
                    nonBgPixels > 50,
                    "Expected rendered diagram foreground pixels on iOS for $name, got $nonBgPixels non-background samples",
                )

                repeat(3) { step ->
                    scene.render((5 + step) * 16_000_000L)
                }
                val frame8Png = assertNotNull(scene.render(8 * 16_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
                assertTrue(
                    frame4Png.contentEquals(frame8Png),
                    "Expected pixel-identical settled frames 4 and 8 on iOS Simulator for $name",
                )
                assertEquals(settledNat, natHistory, "naturalSize oscillated on iOS for $name")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun sampleAppThemeSelectionAndViewportResetWorkOnIosSimulator() {
        val scene = ImageComposeScene(width = 390 * 2, height = 844 * 2, density = Density(2f)) {
            MaradonaSampleApp()
        }
        try {
            var clock = 1L
            fun advance(frames: Int) {
                repeat(frames) { scene.render(clock++ * 16_000_000L) }
            }
            fun capturePng(): ByteArray =
                assertNotNull(scene.render(clock++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes

            advance(4)
            val initialPng = capturePng()

            val basicBtn = assertNotNull(findNodeByTag(scene, "example-btn-Basic"))
            val resetViewportBtn = assertNotNull(findNodeByTag(scene, "reset-viewport-btn"))
            assertEquals(true, basicBtn.config.getOrNull(SemanticsProperties.Selected))
            assertEquals(Role.Tab, basicBtn.config.getOrNull(SemanticsProperties.Role))
            assertEquals(
                listOf("Reset zoom and pan to fit"),
                resetViewportBtn.config.getOrNull(SemanticsProperties.ContentDescription),
            )

            // Drag-pan the diagram canvas on iOS and verify reset-viewport-btn restores the fitted screenshot
            scene.sendPointerEvent(PointerEventType.Press, Offset(390f, 400f), timeMillis = clock * 16L)
            advance(1)
            scene.sendPointerEvent(PointerEventType.Move, Offset(180f, 220f), timeMillis = clock * 16L)
            advance(1)
            scene.sendPointerEvent(PointerEventType.Release, Offset(180f, 220f), timeMillis = clock * 16L)
            advance(2)
            val pannedPng = capturePng()
            assertFalse(initialPng.contentEquals(pannedPng), "Expected iOS drag gesture to pan diagram canvas")

            assertNotNull(findNodeByTag(scene, "reset-viewport-btn")?.config?.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            advance(3)
            val resetPng = capturePng()
            assertTrue(
                initialPng.contentEquals(resetPng),
                "Expected iOS reset-viewport-btn to restore exact initial fitted screenshot",
            )

            // Switch theme to M3 Dark and diagram to Shapes on iOS
            val m3DarkBtn = assertNotNull(findNodeByTag(scene, "theme-btn-MaterialDark"))
            val shapesBtn = assertNotNull(findNodeByTag(scene, "example-btn-Shapes"))
            assertNotNull(m3DarkBtn.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            assertNotNull(shapesBtn.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            advance(3)
            val m3DarkShapesPng = capturePng()
            assertFalse(initialPng.contentEquals(m3DarkShapesPng))
            assertEquals(true, assertNotNull(findNodeByTag(scene, "theme-btn-MaterialDark")).config.getOrNull(SemanticsProperties.Selected))
            assertEquals(true, assertNotNull(findNodeByTag(scene, "example-btn-Shapes")).config.getOrNull(SemanticsProperties.Selected))
        } finally {
            scene.close()
        }
    }
}
