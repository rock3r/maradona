package dev.maradona.markdown.jewel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import dev.maradona.model.MaradonaNodeClickEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.standalone.styling.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.markdown.LazyMarkdown
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.IndentedCodeBlock
import org.jetbrains.jewel.markdown.MarkdownMode
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalJewelApi::class, ExperimentalComposeUiApi::class)
class MaradonaJewelMarkdownExtensionTest {

    @Test
    fun processorConvertsMermaidAndMmdFencesWhilePreservingRegularCodeBlocks() {
        val processor = MarkdownProcessor(extensions = listOf(MermaidMarkdownProcessorExtension))
        val markdown = """
            # Architecture Overview

            Here is a Mermaid flowchart:

            ```mermaid
            flowchart LR
              Client[Desktop App] --> API[Gateway]
            ```

            And a shorthand `mmd` sequence diagram:

            ~~~MMD title="Auth"
            sequenceDiagram
              Client->>API:Bearer token
            ~~~

            Regular Kotlin code fence must stay a `FencedCodeBlock`:

            ```kotlin
            fun greet(name: String) = "Hello, ${'$'}name"
            ```

            Plain unfenced/indented code block:

                val x = 42
        """.trimIndent()

        val blocks = processor.processMarkdownDocument(markdown)
        val mermaidBlocks = blocks.filterIsInstance<MermaidMarkdownBlock>()
        assertEquals(2, mermaidBlocks.size, "Expected 2 MermaidMarkdownBlock instances")
        assertTrue(mermaidBlocks[0].source.contains("Client[Desktop App] --> API[Gateway]"))
        assertEquals("mermaid", mermaidBlocks[0].info)
        assertTrue(mermaidBlocks[1].source.contains("Client->>API:Bearer token"))
        assertEquals("MMD title=\"Auth\"", mermaidBlocks[1].info)
        assertEquals(mermaidBlocks[0].source, mermaidBlocks[0].content)

        val fencedBlocks = blocks.filterIsInstance<FencedCodeBlock>()
        assertEquals(1, fencedBlocks.size, "Expected Kotlin block to remain a FencedCodeBlock")
        assertEquals("kotlin", fencedBlocks[0].language)
        assertTrue(fencedBlocks[0].content.contains("fun greet"))

        val indentedBlocks = blocks.filterIsInstance<IndentedCodeBlock>()
        assertEquals(1, indentedBlocks.size, "Expected indented code block to remain an IndentedCodeBlock")
        assertEquals("val x = 42", indentedBlocks[0].content)
    }

    @Test
    fun processorSupportsEditorPreviewIncrementalUpdatesAndNestedBlocks() {
        val processor = MarkdownProcessor(
            extensions = listOf(MaradonaMermaidMarkdownExtension),
            markdownMode = MarkdownMode.EditorPreview(scrollingSynchronizer = null),
        )
        val initialDoc = """
            # Live Document

            > ```mermaid
            > flowchart TD
            >   A[Start] --> B[End]
            > ```

            ```kotlin
            println("before")
            ```
        """.trimIndent()

        val initialBlocks = processor.processMarkdownDocument(initialDoc)
        val quote = assertIs<MarkdownBlock.BlockQuote>(initialBlocks[1])
        val nestedMermaid = assertIs<MermaidMarkdownBlock>(quote.children.single())
        assertTrue(nestedMermaid.source.contains("A[Start] --> B[End]"))

        val updatedDoc = initialDoc.replace("B[End]", "B[Updated Target]")
        val updatedBlocks = processor.processMarkdownDocument(updatedDoc)
        val updatedQuote = assertIs<MarkdownBlock.BlockQuote>(updatedBlocks[1])
        val updatedMermaid = assertIs<MermaidMarkdownBlock>(updatedQuote.children.single())
        assertTrue(updatedMermaid.source.contains("B[Updated Target]"))
    }

    @Test
    fun disabledMarkdownSuppressesMermaidNodeClickCallbacks() {
        val clickedUrls = mutableListOf<String>()
        val openedViaUriHandler = mutableListOf<String>()
        var mermaidBlockBounds: Rect? = null
        val doc = """
            ```mermaid
            flowchart LR
              DisabledNode[Disabled Node]
              click DisabledNode "https://example.com/disabled"
            ```
        """.trimIndent()
        val processor = MarkdownProcessor(extensions = listOf(MermaidMarkdownProcessorExtension))
        val blocks = processor.processMarkdownDocument(doc)
        val testUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedViaUriHandler += uri
            }
        }

        val scene = ImageComposeScene(width = 640, height = 320, density = Density(1f)) {
            CompositionLocalProvider(LocalUriHandler provides testUriHandler) {
                IntUiTheme(isDark = true) {
                    val styling = remember { MarkdownStyling.dark() }
                    val renderer = rememberMaradonaMarkdownBlockRenderer(
                        rootStyling = styling,
                        onNodeClick = null,
                    )
                    ProvideMarkdownStyling(
                        isDark = true,
                        markdownStyling = styling,
                        markdownProcessor = processor,
                        markdownBlockRenderer = renderer,
                    ) {
                        Markdown(
                            markdownBlocks = blocks,
                            markdown = doc,
                            enabled = false,
                            onUrlClick = { clickedUrls += it },
                            blockRenderer = object : MarkdownBlockRenderer by renderer {
                                @androidx.compose.runtime.Composable
                                override fun RenderBlock(
                                    block: MarkdownBlock,
                                    enabled: Boolean,
                                    onUrlClick: (String) -> Unit,
                                    modifier: Modifier,
                                ) {
                                    val tracked = if (block is MermaidMarkdownBlock) {
                                        modifier.onGloballyPositioned { mermaidBlockBounds = it.boundsInRoot() }
                                    } else {
                                        modifier
                                    }
                                    renderer.RenderBlock(block, enabled, onUrlClick, tracked)
                                }
                            },
                        )
                    }
                }
            }
        }
        try {
            repeat(4) { f -> scene.render((f + 1) * 16_000_000L) }
            val bounds = assertNotNull(mermaidBlockBounds)
            scene.sendPointerEvent(PointerEventType.Press, bounds.center)
            scene.sendPointerEvent(PointerEventType.Release, bounds.center)
            scene.render(100_000_000L)
            assertTrue(clickedUrls.isEmpty(), "Expected disabled Markdown block not to invoke onUrlClick")
            assertTrue(openedViaUriHandler.isEmpty(), "Expected disabled Markdown block not to open URI via LocalUriHandler")
        } finally {
            scene.close()
        }
    }

    @Test
    fun rendersMermaidAndRegularCodeBlocksInJewelMarkdownWithSensibleSizingAndClickRouting() {
        val clickedUrls = mutableListOf<String>()
        val clickedEvents = mutableListOf<MaradonaNodeClickEvent>()
        var mermaidBlockBounds: Rect? = null
        val doc = """
            # Maradona in Jewel Markdown

            Embedded flowchart with a clickable node:

            ```mermaid
            flowchart LR
              Spec[Spec Node]
              click Spec "https://example.com/spec" "Open Spec"
            ```

            Regular Kotlin code fence:

            ```kotlin
            val answer = 42
            ```
        """.trimIndent()

        val processor = MarkdownProcessor(extensions = listOf(MermaidMarkdownProcessorExtension))
        val blocks = processor.processMarkdownDocument(doc)

        val scene = ImageComposeScene(width = 800, height = 720, density = Density(1f)) {
            IntUiTheme(isDark = true) {
                val styling = remember { MarkdownStyling.dark() }
                val renderer = rememberMaradonaMarkdownBlockRenderer(
                    rootStyling = styling,
                    onNodeClick = { event ->
                        clickedEvents += event
                        event.url?.let { clickedUrls += it }
                    },
                )
                ProvideMarkdownStyling(
                    isDark = true,
                    markdownStyling = styling,
                    markdownProcessor = processor,
                    markdownBlockRenderer = renderer,
                ) {
                    VerticallyScrollableContainer(modifier = Modifier.fillMaxSize()) {
                        Markdown(
                            markdownBlocks = blocks,
                            markdown = doc,
                            onUrlClick = { clickedUrls += it },
                            blockRenderer = object : MarkdownBlockRenderer by renderer {
                                @androidx.compose.runtime.Composable
                                override fun RenderBlock(
                                    block: MarkdownBlock,
                                    enabled: Boolean,
                                    onUrlClick: (String) -> Unit,
                                    modifier: Modifier,
                                ) {
                                    val trackedModifier = if (block is MermaidMarkdownBlock) {
                                        modifier.onGloballyPositioned { mermaidBlockBounds = it.boundsInRoot() }
                                    } else {
                                        modifier
                                    }
                                    renderer.RenderBlock(block, enabled, onUrlClick, trackedModifier)
                                }
                            },
                        )
                    }
                }
            }
        }
        try {
            repeat(4) { f -> scene.render((f + 1) * 16_000_000L) }
            val bounds = assertNotNull(mermaidBlockBounds, "Expected MermaidMarkdownBlock to be laid out")
            assertTrue(bounds.width >= 400f && bounds.height in 140f..480f, "Expected sensible bounded diagram card dimensions, got $bounds")

            // Click the center of the single-node diagram card to trigger onNodeClick
            val center = Offset(bounds.center.x, bounds.center.y)
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            scene.render(100_000_000L)

            assertEquals(1, clickedEvents.size, "Expected node click inside MermaidMarkdownBlock")
            assertEquals("Spec", clickedEvents.single().nodeId)
            assertEquals("https://example.com/spec", clickedUrls.single())
        } finally {
            scene.close()
        }
    }

    @Test
    fun adaptsLiveToJewelLightAndDarkThemesAndSupportsFallbackFencedCodeBlockRenderer() {
        var isDark by mutableStateOf(false)
        val doc = """
            ```mermaid
            flowchart LR
              A[Theme Node] --> B[Target Node]
            ```
        """.trimIndent()
        // Deliberately use a default MarkdownProcessor() without MermaidMarkdownProcessorExtension
        // to verify that MaradonaMarkdownBlockRenderer also intercepts standard FencedCodeBlock("mermaid").
        val defaultProcessor = MarkdownProcessor()
        val blocks = defaultProcessor.processMarkdownDocument(doc)
        assertIs<FencedCodeBlock>(blocks.single())

        val scene = ImageComposeScene(width = 640, height = 320, density = Density(1f)) {
            IntUiTheme(isDark = isDark) {
                val styling = remember(isDark) {
                    if (isDark) MarkdownStyling.dark() else MarkdownStyling.light()
                }
                val renderer = rememberMaradonaMarkdownBlockRenderer(rootStyling = styling)
                ProvideMarkdownStyling(
                    isDark = isDark,
                    markdownStyling = styling,
                    markdownProcessor = defaultProcessor,
                    markdownBlockRenderer = renderer,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Markdown(
                            markdownBlocks = blocks,
                            markdown = doc,
                            blockRenderer = renderer,
                        )
                    }
                }
            }
        }
        try {
            repeat(3) { f -> scene.render((f + 1) * 16_000_000L) }
            val lightPng = requireNotNull(scene.render(80_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
            val lightImg = ImageIO.read(ByteArrayInputStream(lightPng))
            val lightRgb = lightImg.getRGB(24, 24)

            isDark = true
            repeat(3) { f -> scene.render((f + 6) * 16_000_000L) }
            val darkPng = requireNotNull(scene.render(180_000_000L).encodeToData(EncodedImageFormat.PNG)).bytes
            val darkImg = ImageIO.read(ByteArrayInputStream(darkPng))
            val darkRgb = darkImg.getRGB(24, 24)

            val lr = (lightRgb shr 16) and 0xFF
            val dr = (darkRgb shr 16) and 0xFF
            assertTrue(
                abs(lr - dr) > 120,
                "Expected Mermaid block background to switch between Jewel Light (R=$lr) and Jewel Dark (R=$dr)",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun handlesStreamingAndInvalidMermaidSourcesGracefullyInLazyMarkdown() {
        val processor = MarkdownProcessor(extensions = listOf(MermaidMarkdownProcessorExtension))
        var markdown by mutableStateOf(
            """
            ```mermaid
            not_a_diagram_keyword ???
            ```
            """.trimIndent(),
        )

        val scene = ImageComposeScene(width = 640, height = 360, density = Density(1f)) {
            IntUiTheme(isDark = true) {
                val styling = remember { MarkdownStyling.dark() }
                val renderer = remember(styling) {
                    DefaultMarkdownBlockRenderer(
                        rootStyling = styling,
                        rendererExtensions = listOf(MermaidMarkdownRendererExtension()),
                    )
                }
                val blocks = remember(markdown) { processor.processMarkdownDocument(markdown) }
                ProvideMarkdownStyling(
                    isDark = true,
                    markdownStyling = styling,
                    markdownProcessor = processor,
                    markdownBlockRenderer = renderer,
                ) {
                    LazyMarkdown(
                        blocks = blocks,
                        modifier = Modifier.fillMaxSize(),
                        blockRenderer = renderer,
                    )
                }
            }
        }
        try {
            // 1. Initial invalid source renders fallback text + error banner without throwing
            repeat(3) { f -> scene.render((f + 1) * 16_000_000L) }
            val invalidPng = scene.render(64_000_000L).encodeToData(EncodedImageFormat.PNG)
            assertNotNull(invalidPng)

            // 2. Stream a valid diagram update
            markdown = """
                ```mermaid
                flowchart LR
                  ValidStart[Streamed Ready] --> ValidEnd[Done]
                ```
            """.trimIndent()
            repeat(3) { f -> scene.render((f + 6) * 16_000_000L) }
            val validPng = scene.render(160_000_000L).encodeToData(EncodedImageFormat.PNG)
            assertNotNull(validPng)

            // 3. Append an incomplete trailing line mid-stream: last valid diagram remains visible alongside error
            markdown = """
                ```mermaid
                flowchart LR
                  ValidStart[Streamed Ready] --> ValidEnd[Done]
                  ???
                ```
            """.trimIndent()
            repeat(3) { f -> scene.render((f + 11) * 16_000_000L) }
            val midStreamPng = scene.render(260_000_000L).encodeToData(EncodedImageFormat.PNG)
            assertNotNull(midStreamPng)
        } finally {
            scene.close()
        }
    }
}
