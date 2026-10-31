package dev.maradona.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class JewelMarkdownDemoTest {

    @Test
    fun rendersJewelMarkdownDemoInDarkAndLightThemesAndCapturesScreenshots() {
        val outDir = File("build/jewel-markdown-demo").apply { mkdirs() }
        val updateDocs = System.getProperty("maradona.updateDocsImages")?.toBoolean() == true
        val docsImgFile = File("../docs/images/jewel-markdown-demo.png")

        // 1. Render full split-pane Jewel Dark demo
        val darkScene = ImageComposeScene(width = 1360, height = 880, density = Density(1f)) {
            JewelMarkdownDemoScreen(
                initialPresetName = "Architecture RFC",
                initialDark = true,
                showEditorPane = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val darkBytes = try {
            repeat(5) { f -> darkScene.render((f + 1) * 16_000_000L) }
            requireNotNull(darkScene.render(120_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
        } finally {
            darkScene.close()
        }
        File(outDir, "jewel-markdown-demo-dark.png").writeBytes(darkBytes)

        // 2. Render full split-pane Jewel Light demo
        val lightScene = ImageComposeScene(width = 1360, height = 880, density = Density(1f)) {
            JewelMarkdownDemoScreen(
                initialPresetName = "C4 & Schema Spec",
                initialDark = false,
                showEditorPane = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val lightBytes = try {
            repeat(5) { f -> lightScene.render((f + 1) * 16_000_000L) }
            requireNotNull(lightScene.render(120_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
        } finally {
            lightScene.close()
        }
        File(outDir, "jewel-markdown-demo-light.png").writeBytes(lightBytes)

        // 3. Render side-by-side Jewel Dark vs Jewel Light Markdown preview for docs/images/jewel-markdown-demo.png
        val sideBySideScene = ImageComposeScene(width = 1440, height = 780, density = Density(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141517))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    JewelMarkdownDemoScreen(
                        initialPresetName = "Architecture RFC",
                        initialDark = true,
                        showEditorPane = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    JewelMarkdownDemoScreen(
                        initialPresetName = "C4 & Schema Spec",
                        initialDark = false,
                        showEditorPane = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val sideBySideBytes = try {
            repeat(5) { f -> sideBySideScene.render((f + 1) * 16_000_000L) }
            requireNotNull(sideBySideScene.render(120_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
        } finally {
            sideBySideScene.close()
        }
        File(outDir, "jewel-markdown-demo-side-by-side.png").writeBytes(sideBySideBytes)
        if (updateDocs && docsImgFile.parentFile?.isDirectory == true) {
            docsImgFile.writeBytes(sideBySideBytes)
        }

        val darkImg = ImageIO.read(ByteArrayInputStream(darkBytes))
        val lightImg = ImageIO.read(ByteArrayInputStream(lightBytes))
        val darkCorner = darkImg.getRGB(40, 40)
        val lightCorner = lightImg.getRGB(40, 40)
        val dr = (darkCorner shr 16) and 0xFF
        val lr = (lightCorner shr 16) and 0xFF
        assertTrue(abs(lr - dr) > 150, "Expected Jewel Light (R=$lr) and Jewel Dark (R=$dr) demo renders to differ")
    }
}
