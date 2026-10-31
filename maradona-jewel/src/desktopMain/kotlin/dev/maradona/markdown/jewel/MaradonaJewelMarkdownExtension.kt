package dev.maradona.markdown.jewel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.parse.MaradonaParser
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme
import dev.maradona.theme.jewel.rememberJewelMaradonaTheme
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomBlock as CommonMarkCustomBlock
import org.commonmark.node.FencedCodeBlock as CommonMarkFencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.text.TextContentNodeRendererContext
import org.commonmark.renderer.text.TextContentRenderer
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.MarkdownBlock.CodeBlock.FencedCodeBlock
import org.jetbrains.jewel.markdown.MarkdownMode
import org.jetbrains.jewel.markdown.WithTextContent
import org.jetbrains.jewel.markdown.extensions.MarkdownBlockProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownBlockRendererExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension
import org.jetbrains.jewel.markdown.extensions.MarkdownRendererExtension
import org.jetbrains.jewel.markdown.extensions.markdownMode
import org.jetbrains.jewel.markdown.extensions.markdownStyling
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.DefaultInlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.DefaultMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling

private const val DISABLED_DIAGRAM_ALPHA = 0.5f

/**
 * Test tag applied to each Mermaid diagram container rendered inside Jewel Markdown.
 */
public const val MERMAID_MARKDOWN_BLOCK_TEST_TAG: String = "maradona_jewel_markdown_mermaid_block"

/**
 * A parsed Mermaid diagram block in a Jewel Markdown document.
 *
 * Produced by [MermaidMarkdownProcessorExtension] when a fenced code block carries a `mermaid`
 * or `mmd` info string (for example ```` ```mermaid ````).
 *
 * @property source The raw Mermaid diagram source text inside the fenced block.
 * @property info The original fence info string (for example `"mermaid"` or `"mmd"`).
 */
@Immutable
@OptIn(ExperimentalJewelApi::class)
public data class MermaidMarkdownBlock(
    public val source: String,
    public val info: String = "mermaid",
) : MarkdownBlock.CustomBlock, WithTextContent {
    override val content: String
        get() = source
}

/**
 * Visual and behavioral configuration for rendering [MermaidMarkdownBlock]s inside Jewel Markdown.
 *
 * @property minHeight Minimum height of the diagram container card.
 * @property maxHeight Maximum height of the diagram container card before fit-scaling applies.
 * @property defaultHeight Initial height used before the diagram's natural dimensions are measured.
 * @property padding Inner padding around the rendered [MaradonaDiagram].
 * @property shape Corner shape of the diagram container card.
 * @property borderWidth Border stroke width around the diagram container card.
 * @property background Optional explicit container background color. When [Color.Unspecified], uses
 *   the resolved [MaradonaTheme.background].
 * @property borderColor Optional explicit container border color. When [Color.Unspecified], uses
 *   the resolved [MaradonaTheme.subgraphBorder].
 * @property useToolwindowBackground Passed to [rememberJewelMaradonaTheme] when deriving the diagram
 *   theme from the active [JewelTheme].
 * @property zoomEnabled Whether interactive pan and zoom gestures are enabled inside embedded diagrams.
 * @property autoDirection Whether wide `LR`/`RL` diagrams automatically adapt to `TB` in narrow Markdown columns.
 * @property showErrorSourceFallback Whether to show the raw Mermaid source text alongside the parse error
 *   banner when a block has not yet produced a valid diagram (for example, while streaming an incomplete fence).
 * @property customizeTheme Optional transform applied to the Jewel-derived [MaradonaTheme].
 */
@Immutable
public data class MermaidMarkdownStyling(
    public val minHeight: Dp = 140.dp,
    public val maxHeight: Dp = 480.dp,
    public val defaultHeight: Dp = 220.dp,
    public val padding: PaddingValues = PaddingValues(12.dp),
    public val shape: Shape = RoundedCornerShape(6.dp),
    public val borderWidth: Dp = 1.dp,
    public val background: Color = Color.Unspecified,
    public val borderColor: Color = Color.Unspecified,
    public val useToolwindowBackground: Boolean = false,
    public val zoomEnabled: Boolean = true,
    public val autoDirection: Boolean = false,
    public val showErrorSourceFallback: Boolean = true,
    public val customizeTheme: (MaradonaTheme) -> MaradonaTheme = { it },
)

internal class MermaidCommonMarkBlock(
    val source: String,
    val info: String,
) : CommonMarkCustomBlock()

/**
 * Jewel [MarkdownProcessorExtension] that recognizes `mermaid` and `mmd` fenced code blocks
 * and transforms them into [MermaidMarkdownBlock] custom blocks while leaving all other fenced
 * and indented code blocks untouched.
 */
@OptIn(ExperimentalJewelApi::class)
public object MermaidMarkdownProcessorExtension : MarkdownProcessorExtension {
    private val MERMAID_LANGUAGES = setOf("mermaid", "mmd")

    /**
     * Returns `true` when [info] (the fenced code block info string) designates a Mermaid diagram
     * (`mermaid` or `mmd`, case-insensitive, ignoring optional trailing metadata tokens).
     */
    public fun isMermaidFenceInfo(info: String?): Boolean {
        if (info.isNullOrBlank()) return false
        val primaryToken = info.trim().substringBefore(' ').substringBefore('\t').lowercase()
        return primaryToken in MERMAID_LANGUAGES
    }

    override val parserExtension: Parser.ParserExtension = MermaidCommonMarkExtension

    override val textRendererExtension: TextContentRenderer.TextContentRendererExtension = MermaidCommonMarkExtension

    override val blockProcessorExtension: MarkdownBlockProcessorExtension = MermaidBlockProcessorExtension

    private object MermaidBlockProcessorExtension : MarkdownBlockProcessorExtension {
        override fun canProcess(block: CommonMarkCustomBlock): Boolean = block is MermaidCommonMarkBlock

        override fun processMarkdownBlock(
            block: CommonMarkCustomBlock,
            processor: MarkdownProcessor,
        ): MarkdownBlock.CustomBlock? {
            val mermaidBlock = block as? MermaidCommonMarkBlock ?: return null
            return MermaidMarkdownBlock(
                source = mermaidBlock.source,
                info = mermaidBlock.info.ifBlank { "mermaid" },
            )
        }
    }

    private object MermaidCommonMarkExtension :
        Parser.ParserExtension,
        TextContentRenderer.TextContentRendererExtension {
        override fun extend(parserBuilder: Parser.Builder) {
            parserBuilder.postProcessor(
                PostProcessor { document ->
                    document.accept(
                        object : AbstractVisitor() {
                            override fun visit(fencedCodeBlock: CommonMarkFencedCodeBlock) {
                                val rawInfo = fencedCodeBlock.info?.trim().orEmpty()
                                if (isMermaidFenceInfo(rawInfo)) {
                                    val custom = MermaidCommonMarkBlock(
                                        source = fencedCodeBlock.literal.removeSuffix("\n"),
                                        info = rawInfo.ifEmpty { "mermaid" },
                                    )
                                    custom.sourceSpans = fencedCodeBlock.sourceSpans
                                    fencedCodeBlock.insertBefore(custom)
                                    fencedCodeBlock.unlink()
                                }
                            }
                        },
                    )
                    document
                },
            )
        }

        override fun extend(rendererBuilder: TextContentRenderer.Builder) {
            rendererBuilder.nodeRendererFactory { context: TextContentNodeRendererContext ->
                object : NodeRenderer {
                    override fun getNodeTypes(): Set<Class<out Node>> = setOf(MermaidCommonMarkBlock::class.java)

                    override fun render(node: Node) {
                        val block = node as? MermaidCommonMarkBlock ?: return
                        context.writer.write(block.source)
                    }
                }
            }
        }
    }
}

/**
 * Jewel [MarkdownBlockRendererExtension] that renders [MermaidMarkdownBlock]s as native Compose
 * diagrams via [MaradonaDiagram], deriving colors and typography from the active [JewelTheme].
 *
 * @property styling Sizing, padding, border, and interaction options for the diagram card.
 * @property theme Optional explicit [MaradonaTheme] override. When `null`, derives a live theme
 *   from the active [JewelTheme] via [rememberJewelMaradonaTheme].
 * @property onNodeClick Optional callback invoked when an interactive diagram node is clicked.
 *   When `null`, nodes carrying a URL delegate to Jewel Markdown's `onUrlClick` callback.
 */
@OptIn(ExperimentalJewelApi::class)
public class MermaidMarkdownBlockRenderer(
    public val styling: MermaidMarkdownStyling = MermaidMarkdownStyling(),
    public val theme: MaradonaTheme? = null,
    public val onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
) : MarkdownBlockRendererExtension {

    override fun canRender(block: MarkdownBlock.CustomBlock): Boolean = block is MermaidMarkdownBlock

    @Composable
    override fun RenderCustomBlock(
        block: MarkdownBlock.CustomBlock,
        blockRenderer: MarkdownBlockRenderer,
        inlineRenderer: InlineMarkdownRenderer,
        enabled: Boolean,
        modifier: Modifier,
        onUrlClick: (String) -> Unit,
    ) {
        val mermaidBlock = block as? MermaidMarkdownBlock
            ?: error("Unsupported CustomBlock type: ${block::class.qualifiedName}")
        RenderMermaidBlockContent(
            source = mermaidBlock.source,
            enabled = enabled,
            modifier = modifier,
            onUrlClick = onUrlClick,
        )
    }

    @Composable
    internal fun RenderMermaidBlockContent(
        source: String,
        enabled: Boolean,
        modifier: Modifier,
        onUrlClick: (String) -> Unit,
    ) {
        val activeTheme = theme ?: rememberJewelMaradonaTheme(
            useToolwindowBackground = styling.useToolwindowBackground,
            override = styling.customizeTheme,
        )
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current

        val parseAttempt = remember(source) { runCatching { MaradonaParser.parse(source) } }
        var hasEverParsedValid by remember { mutableStateOf(false) }
        if (parseAttempt.isSuccess) {
            hasEverParsedValid = true
        }
        val parseError = parseAttempt.exceptionOrNull()

        var naturalSize by remember(source) { mutableStateOf(IntSize.Zero) }
        var containerWidthPx by remember { mutableIntStateOf(0) }

        val padStart = styling.padding.calculateStartPadding(layoutDirection)
        val padEnd = styling.padding.calculateEndPadding(layoutDirection)
        val padTop = styling.padding.calculateTopPadding()
        val padBottom = styling.padding.calculateBottomPadding()
        val horizontalPadPx = with(density) { (padStart + padEnd).roundToPx() }

        val resolvedHeight: Dp = if (naturalSize.width > 0 && naturalSize.height > 0) {
            val innerWidthPx = (containerWidthPx - horizontalPadPx).coerceAtLeast(1)
            val fitRatio = if (containerWidthPx > 0) {
                minOf(1f, innerWidthPx.toFloat() / naturalSize.width.toFloat())
            } else {
                1f
            }
            val diagramHeightDp = with(density) { (naturalSize.height * fitRatio).toDp() }
            (diagramHeightDp + padTop + padBottom).coerceIn(styling.minHeight, styling.maxHeight)
        } else {
            styling.defaultHeight.coerceIn(styling.minHeight, styling.maxHeight)
        }

        val cardBackground = if (styling.background.isSpecified) styling.background else activeTheme.background
        val cardBorder = if (styling.borderColor.isSpecified) styling.borderColor else activeTheme.subgraphBorder

        val clickHandler: ((MaradonaNodeClickEvent) -> Unit)? = if (!enabled) {
            null
        } else {
            { event ->
                val url = event.url
                if (onNodeClick != null) {
                    onNodeClick.invoke(event)
                } else if (!url.isNullOrBlank()) {
                    onUrlClick(url)
                }
            }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(resolvedHeight)
                .onSizeChanged { containerWidthPx = it.width }
                .clip(styling.shape)
                .background(cardBackground, styling.shape)
                .border(styling.borderWidth, cardBorder, styling.shape)
                .padding(styling.padding)
                .alpha(if (enabled) 1f else DISABLED_DIAGRAM_ALPHA)
                .testTag(MERMAID_MARKDOWN_BLOCK_TEST_TAG)
                .semantics {
                    contentDescription = if (parseError != null && !hasEverParsedValid) {
                        "Mermaid diagram with syntax error: ${parseError.message ?: "invalid syntax"}"
                    } else {
                        "Mermaid diagram"
                    }
                },
        ) {
            MaradonaDiagram(
                source = source,
                theme = activeTheme,
                autoDirection = styling.autoDirection,
                zoomEnabled = styling.zoomEnabled && enabled,
                onNaturalSize = { measured ->
                    if (measured.width > 0 && measured.height > 0 && naturalSize != measured) {
                        naturalSize = measured
                    }
                },
                onNodeClick = clickHandler,
                modifier = Modifier.fillMaxSize(),
            )

            if (parseError != null && !hasEverParsedValid && styling.showErrorSourceFallback && source.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicText(
                        text = source,
                        style = TextStyle(
                            color = activeTheme.nodeText.copy(alpha = 0.72f),
                            fontFamily = activeTheme.monospaceFontFamily,
                            fontSize = activeTheme.edgeLabelFontSize,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Jewel [MarkdownRendererExtension] that registers [MermaidMarkdownBlockRenderer] for rendering
 * [MermaidMarkdownBlock]s as native Compose diagrams via Maradona.
 *
 * @property styling Sizing, padding, border, and interaction options for rendered diagrams.
 * @property theme Optional explicit [MaradonaTheme] override. When `null`, derives a live theme
 *   from the active [JewelTheme].
 * @property onNodeClick Optional callback invoked when an interactive diagram node is clicked.
 */
@OptIn(ExperimentalJewelApi::class)
public class MermaidMarkdownRendererExtension(
    public val styling: MermaidMarkdownStyling = MermaidMarkdownStyling(),
    public val theme: MaradonaTheme? = null,
    public val onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
) : MarkdownRendererExtension {
    override val blockRenderer: MarkdownBlockRendererExtension =
        MermaidMarkdownBlockRenderer(
            styling = styling,
            theme = theme,
            onNodeClick = onNodeClick,
        )
}

/**
 * Combined Jewel [MarkdownProcessorExtension] and [MarkdownRendererExtension] for rendering
 * Mermaid fenced code blocks (`mermaid`, `mmd`) natively via Maradona.
 *
 * Can be passed to both [MarkdownProcessor] (either as an instance or via the companion object
 * `MaradonaMermaidMarkdownExtension`) and [DefaultMarkdownBlockRenderer] / [MaradonaMarkdownBlockRenderer].
 *
 * @property styling Sizing, padding, border, and interaction options for rendered diagrams.
 * @property theme Optional explicit [MaradonaTheme] override. When `null`, derives a live theme
 *   from the active [JewelTheme].
 * @property onNodeClick Optional callback invoked when an interactive diagram node is clicked.
 */
@OptIn(ExperimentalJewelApi::class)
public class MaradonaMermaidMarkdownExtension(
    public val styling: MermaidMarkdownStyling = MermaidMarkdownStyling(),
    public val theme: MaradonaTheme? = null,
    public val onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
) : MarkdownProcessorExtension by MermaidMarkdownProcessorExtension,
    MarkdownRendererExtension {

    override val blockRenderer: MarkdownBlockRendererExtension =
        MermaidMarkdownBlockRenderer(
            styling = styling,
            theme = theme,
            onNodeClick = onNodeClick,
        )

    /**
     * Companion object implementing [MarkdownProcessorExtension] so callers can register
     * `MaradonaMermaidMarkdownExtension` directly on a [MarkdownProcessor].
     */
    public companion object : MarkdownProcessorExtension by MermaidMarkdownProcessorExtension
}

/**
 * A [DefaultMarkdownBlockRenderer] that renders both [MermaidMarkdownBlock] custom blocks and
 * standard [FencedCodeBlock]s with a `mermaid` / `mmd` language tag natively through [MaradonaDiagram],
 * while delegating all other Markdown blocks and non-Mermaid code fences to [DefaultMarkdownBlockRenderer].
 *
 * @param rootStyling The [MarkdownStyling] used for standard Markdown blocks.
 * @param rendererExtensions Additional [MarkdownRendererExtension]s. A [MermaidMarkdownRendererExtension]
 *   is automatically included if none is present.
 * @param inlineRenderer The [InlineMarkdownRenderer] used for inline Markdown content.
 * @param mermaidStyling Sizing, padding, border, and interaction options for embedded Mermaid diagrams.
 * @param theme Optional explicit [MaradonaTheme] override. When `null`, derives a live theme from [JewelTheme].
 * @property onNodeClick Optional callback invoked when an interactive diagram node is clicked.
 */
@OptIn(ExperimentalJewelApi::class)
public open class MaradonaMarkdownBlockRenderer(
    rootStyling: MarkdownStyling,
    rendererExtensions: List<MarkdownRendererExtension> = emptyList(),
    inlineRenderer: InlineMarkdownRenderer = DefaultInlineMarkdownRenderer(
        ensureMermaidExtension(rendererExtensions, MermaidMarkdownStyling(), null, null),
    ),
    public val mermaidStyling: MermaidMarkdownStyling = MermaidMarkdownStyling(),
    public val theme: MaradonaTheme? = null,
    public val onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
) : DefaultMarkdownBlockRenderer(
    rootStyling = rootStyling,
    rendererExtensions = ensureMermaidExtension(rendererExtensions, mermaidStyling, theme, onNodeClick),
    inlineRenderer = inlineRenderer,
) {
    private val fallbackMermaidRenderer = MermaidMarkdownBlockRenderer(
        styling = mermaidStyling,
        theme = theme,
        onNodeClick = onNodeClick,
    )

    @Composable
    override fun RenderBlock(
        block: MarkdownBlock,
        enabled: Boolean,
        onUrlClick: (String) -> Unit,
        modifier: Modifier,
    ) {
        if (block is FencedCodeBlock && MermaidMarkdownProcessorExtension.isMermaidFenceInfo(block.language)) {
            fallbackMermaidRenderer.RenderMermaidBlockContent(
                source = block.content,
                enabled = enabled,
                modifier = modifier,
                onUrlClick = onUrlClick,
            )
        } else {
            super.RenderBlock(block, enabled, onUrlClick, modifier)
        }
    }

    @Composable
    override fun RenderFencedCodeBlock(
        block: FencedCodeBlock,
        styling: MarkdownStyling.Code.Fenced,
        enabled: Boolean,
        modifier: Modifier,
    ) {
        if (MermaidMarkdownProcessorExtension.isMermaidFenceInfo(block.language)) {
            fallbackMermaidRenderer.RenderMermaidBlockContent(
                source = block.content,
                enabled = enabled,
                modifier = modifier,
                onUrlClick = {},
            )
        } else {
            super.RenderFencedCodeBlock(block, styling, enabled, modifier)
        }
    }

    override fun createCopy(
        rootStyling: MarkdownStyling?,
        rendererExtensions: List<MarkdownRendererExtension>?,
        inlineRenderer: InlineMarkdownRenderer?,
    ): MarkdownBlockRenderer {
        val resolvedExtensions = ensureMermaidExtension(
            rendererExtensions ?: this.rendererExtensions,
            mermaidStyling,
            theme,
            onNodeClick,
        )
        return MaradonaMarkdownBlockRenderer(
            rootStyling = rootStyling ?: this.rootStyling,
            rendererExtensions = resolvedExtensions,
            inlineRenderer = inlineRenderer ?: this.inlineRenderer,
            mermaidStyling = mermaidStyling,
            theme = theme,
            onNodeClick = onNodeClick,
        )
    }

    override operator fun plus(extension: MarkdownRendererExtension): MarkdownBlockRenderer =
        createCopy(rendererExtensions = rendererExtensions + extension)

    private companion object {
        fun ensureMermaidExtension(
            extensions: List<MarkdownRendererExtension>,
            styling: MermaidMarkdownStyling,
            theme: MaradonaTheme?,
            onNodeClick: ((MaradonaNodeClickEvent) -> Unit)?,
        ): List<MarkdownRendererExtension> {
            val hasMermaid = extensions.any { it.blockRenderer is MermaidMarkdownBlockRenderer }
            return if (hasMermaid) {
                extensions
            } else {
                extensions + MermaidMarkdownRendererExtension(
                    styling = styling,
                    theme = theme,
                    onNodeClick = onNodeClick,
                )
            }
        }
    }
}

/**
 * Remembers a Jewel [MarkdownProcessor] configured with [MermaidMarkdownProcessorExtension] alongside
 * any additional [extensions].
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
public fun rememberMaradonaMarkdownProcessor(
    markdownMode: MarkdownMode = JewelTheme.markdownMode,
    extensions: List<MarkdownProcessorExtension> = emptyList(),
    parseEmbeddedHtml: Boolean = false,
): MarkdownProcessor = remember(markdownMode, extensions, parseEmbeddedHtml) {
    val combined = if (extensions.any { it === MermaidMarkdownProcessorExtension || it is MaradonaMermaidMarkdownExtension }) {
        extensions
    } else {
        extensions + MermaidMarkdownProcessorExtension
    }
    MarkdownProcessor(
        extensions = combined,
        markdownMode = markdownMode,
        parseEmbeddedHtml = parseEmbeddedHtml,
    )
}

/**
 * Remembers a [MaradonaMarkdownBlockRenderer] configured with [MermaidMarkdownRendererExtension]
 * and any additional [rendererExtensions].
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
public fun rememberMaradonaMarkdownBlockRenderer(
    rootStyling: MarkdownStyling = JewelTheme.markdownStyling,
    mermaidStyling: MermaidMarkdownStyling = MermaidMarkdownStyling(),
    rendererExtensions: List<MarkdownRendererExtension> = emptyList(),
    inlineRenderer: InlineMarkdownRenderer? = null,
    theme: MaradonaTheme? = null,
    onNodeClick: ((MaradonaNodeClickEvent) -> Unit)? = null,
): MarkdownBlockRenderer = remember(
    rootStyling,
    mermaidStyling,
    rendererExtensions,
    inlineRenderer,
    theme,
    onNodeClick,
) {
    if (inlineRenderer != null) {
        MaradonaMarkdownBlockRenderer(
            rootStyling = rootStyling,
            rendererExtensions = rendererExtensions,
            inlineRenderer = inlineRenderer,
            mermaidStyling = mermaidStyling,
            theme = theme,
            onNodeClick = onNodeClick,
        )
    } else {
        MaradonaMarkdownBlockRenderer(
            rootStyling = rootStyling,
            rendererExtensions = rendererExtensions,
            mermaidStyling = mermaidStyling,
            theme = theme,
            onNodeClick = onNodeClick,
        )
    }
}
