package dev.maradona.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.markdown.jewel.MermaidMarkdownProcessorExtension
import dev.maradona.markdown.jewel.MermaidMarkdownStyling
import dev.maradona.markdown.jewel.rememberMaradonaMarkdownBlockRenderer
import dev.maradona.markdown.jewel.rememberMaradonaMarkdownProcessor
import dev.maradona.theme.jewel.ProvideJewelMaradonaTheme
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.standalone.styling.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.github.tables.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.github.tables.light
import org.jetbrains.jewel.intui.markdown.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableStyling
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableRendererExtension
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

val jewelMarkdownSampleDocuments: Map<String, String> = linkedMapOf(
    "Architecture RFC" to """
        # RFC-042: Native Mermaid Diagrams in Jewel Markdown

        This document is rendered by **JetBrains Jewel Markdown** (`org.jetbrains.jewel.markdown.Markdown`) with **`MaradonaMermaidMarkdownExtension`** from `:maradona-jewel`. Standard Markdown blocks, GFM tables, and syntax-highlighted code fences work side-by-side with interactive Mermaid diagrams.

        ## 1. Request & Rendering Pipeline

        Click the **Spec** or **Renderer** nodes inside the diagram below to trigger `onNodeClick` / `onUrlClick`:

        ```mermaid
        flowchart LR
          subgraph jewel[Jewel Markdown Engine]
            MD[Markdown Source] --> Proc[MarkdownProcessor]
            Proc --> Ext{Mermaid Fence?}
          end
          subgraph maradona[Maradona Jewel Extension]
            Ext -- Yes --> Block[MermaidMarkdownBlock]
            Block --> Diag([MaradonaDiagram])
          end
          Ext -- No --> Code[Syntax Highlighted Code]
          click MD "https://mermaid.js.org" "Open Mermaid specification"
          click Diag "https://github.com/rock3r/maradona" "Open Maradona repository"
        ```

        ## 2. Extension Wiring in Kotlin

        Regular `kotlin` fenced code blocks continue to use Jewel's `SimpleCodeHighlighter`:

        ```kotlin
        val processor = rememberMaradonaMarkdownProcessor(
            extensions = listOf(GitHubTableProcessorExtension, AutolinkProcessorExtension),
        )
        val blockRenderer = rememberMaradonaMarkdownBlockRenderer(
            rootStyling = markdownStyling,
            rendererExtensions = listOf(GitHubTableRendererExtension(tableStyling, markdownStyling)),
        )
        Markdown(markdown = doc, processor = processor, blockRenderer = blockRenderer)
        ```

        ## 3. Sequence Handshake (`mmd` fence)

        ```mmd
        sequenceDiagram
          autonumber
          actor Dev as Developer
          participant IDE as Jewel Toolwindow
          participant Mara as Maradona Renderer
          Dev->>IDE: Open README.md
          IDE->>Mara: Render ```mermaid block
          Mara-->>IDE: Native Compose layout (Jewel tokens)
        ```

        | Module | Target | Role |
        | :--- | :--- | :--- |
        | `:maradona` | Desktop, Android, iOS | Core parser, 23 layout engines & renderer |
        | `:maradona-material3` | Desktop, Android, iOS | Live `MaterialTheme` connector |
        | `:maradona-jewel` | Desktop JVM | Live `JewelTheme` + Jewel Markdown extension |
    """.trimIndent(),
    "C4 & Schema Spec" to """
        # Payments Service Design Specification

        ## System Context (C4)

        ```mermaid
        C4Context
          title Payments Platform Context
          Person(buyer, "Customer", "Checkout user")
          System_Boundary(pay, "Payments Boundary") {
            Container(api, "Checkout API", "Kotlin / Ktor", "Handles payment intents")
            ContainerDb(ledger, "Ledger DB", "PostgreSQL", "Immutable double-entry ledger")
          }
          System_Ext(stripe, "Card Network", "External settlement")
          Rel(buyer, api, "Submits order", "HTTPS")
          Rel(api, ledger, "Appends entry", "JDBC")
          Rel(api, stripe, "Captures charge", "TLS")
        ```

        ## Database Schema (`erDiagram`)

        ```mermaid
        erDiagram
          ACCOUNT ||--o{ LEDGER_ENTRY : records
          PAYMENT_INTENT ||--|{ LEDGER_ENTRY : settles
          ACCOUNT {
            uuid id PK
            string currency
          }
          LEDGER_ENTRY {
            uuid id PK
            bigint amount_cents
          }
        ```

        ```sql
        SELECT account_id, SUM(amount_cents) AS balance_cents
        FROM ledger_entry
        GROUP BY account_id;
        ```
    """.trimIndent(),
    "Release & Telemetry" to """
        # Release Schedule & Latency Telemetry

        ## Milestone Schedule (`gantt`)

        ```mermaid
        gantt
          title Maradona 0.1.0 Release Train
          dateFormat YYYY-MM-DD
          section Core
          23 Diagram Families :done, c1, 2026-10-01, 10d
          Viewport Lazy Culling :done, c2, after c1, 5d
          section Integrations
          Material3 & Jewel Themes :active, i1, 2026-10-16, 5d
          Jewel Markdown Extension :crit, i2, after i1, 4d
        ```

        ## Frame Time Distribution (`xychart-beta`)

        ```mermaid
        xychart-beta
          title "Pan Frame Time (ms) — 400-Node Graph"
          x-axis ["Flowchart", "Sequence", "Class", "C4", "GitGraph"]
          y-axis "Milliseconds" 0 --> 6
          bar [2.6, 1.9, 2.4, 2.8, 1.7]
          line [2.6, 1.9, 2.4, 2.8, 1.7]
        ```
    """.trimIndent(),
)

@OptIn(ExperimentalJewelApi::class)
@Composable
fun JewelMarkdownDemoScreen(
    initialPresetName: String = "Architecture RFC",
    initialDark: Boolean = true,
    showEditorPane: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var isDark by remember { mutableStateOf(initialDark) }
    var useToolwindowBg by remember { mutableStateOf(false) }
    var selectedDocName by remember { mutableStateOf(initialPresetName) }
    var markdownText by remember {
        mutableStateOf(
            jewelMarkdownSampleDocuments[initialPresetName]
                ?: jewelMarkdownSampleDocuments.values.first(),
        )
    }
    var lastInteractionMessage by remember {
        mutableStateOf("Click any linked diagram node or Markdown link to inspect callbacks.")
    }
    var streamTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(streamTrigger) {
        if (streamTrigger == 0) return@LaunchedEffect
        val fullTarget = jewelMarkdownSampleDocuments[selectedDocName]
            ?: jewelMarkdownSampleDocuments.values.first()
        val lines = fullTarget.lines()
        val buffer = StringBuilder()
        for (line in lines) {
            buffer.appendLine(line)
            markdownText = buffer.toString().trimEnd()
            delay(45L)
        }
    }

    IntUiTheme(isDark = isDark) {
        ProvideJewelMaradonaTheme(useToolwindowBackground = useToolwindowBg) {
            val panelBg = JewelTheme.globalColors.panelBackground
            val borderColor = JewelTheme.globalColors.borders.normal
            val textColor = JewelTheme.globalColors.text.normal

            val markdownStyling = remember(isDark) {
                if (isDark) MarkdownStyling.dark() else MarkdownStyling.light()
            }
            val tableStyling = remember(isDark) {
                if (isDark) GfmTableStyling.dark() else GfmTableStyling.light()
            }
            val mermaidStyling = remember(useToolwindowBg) {
                MermaidMarkdownStyling(
                    minHeight = 150.dp,
                    maxHeight = 420.dp,
                    useToolwindowBackground = useToolwindowBg,
                )
            }
            val processor = rememberMaradonaMarkdownProcessor(
                extensions = remember {
                    listOf(
                        MermaidMarkdownProcessorExtension,
                        GitHubTableProcessorExtension,
                        AutolinkProcessorExtension,
                    )
                },
            )
            val blockRenderer = rememberMaradonaMarkdownBlockRenderer(
                rootStyling = markdownStyling,
                mermaidStyling = mermaidStyling,
                rendererExtensions = remember(tableStyling, markdownStyling) {
                    listOf(GitHubTableRendererExtension(tableStyling, markdownStyling))
                },
                onNodeClick = { event ->
                    lastInteractionMessage =
                        "Clicked diagram node '${event.nodeId}' (${event.label})" +
                            (event.url?.let { " -> $it" } ?: "")
                },
            )
            val parsedBlocks = remember(markdownText, processor) {
                processor.processMarkdownDocument(markdownText)
            }

            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(panelBg)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Toolbar: Document presets, Jewel theme toggle, and streaming/error simulation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Document:",
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                        )
                        for ((name, template) in jewelMarkdownSampleDocuments) {
                            if (name == selectedDocName) {
                                DefaultButton(
                                    onClick = {
                                        selectedDocName = name
                                        markdownText = template
                                    },
                                ) {
                                    Text(name)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        selectedDocName = name
                                        markdownText = template
                                    },
                                ) {
                                    Text(name)
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { streamTrigger++ },
                        ) {
                            Text("Simulate Stream")
                        }
                        OutlinedButton(
                            onClick = {
                                markdownText = markdownText + "\n```mermaid\nflowchart LR\n  Broken -->\n```"
                            },
                        ) {
                            Text("Append Broken Fence")
                        }
                        OutlinedButton(
                            onClick = { useToolwindowBg = !useToolwindowBg },
                        ) {
                            Text(if (useToolwindowBg) "Surface: Toolwindow" else "Surface: Panel")
                        }
                        DefaultButton(
                            onClick = { isDark = !isDark },
                        ) {
                            Text(if (isDark) "Jewel Dark" else "Jewel Light")
                        }
                    }
                }

                Divider(
                    orientation = Orientation.Horizontal,
                    modifier = Modifier.fillMaxWidth(),
                    color = borderColor,
                )

                // Split Pane: Markdown Source Editor (left) + Live Jewel Markdown Preview (right)
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showEditorPane) {
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Markdown Source (Live Editable)",
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                            )
                            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth(), color = borderColor)
                            val editorScroll = rememberScrollState()
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(editorScroll),
                            ) {
                                BasicTextField(
                                    value = markdownText,
                                    onValueChange = { markdownText = it },
                                    textStyle = JewelTheme.editorTextStyle.copy(
                                        color = textColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp,
                                    ),
                                    cursorBrush = SolidColor(textColor),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(if (showEditorPane) 0.58f else 1f)
                            .fillMaxHeight()
                            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Jewel Markdown Preview (with MaradonaMermaidMarkdownExtension)",
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                            )
                            Text(
                                text = lastInteractionMessage,
                                color = JewelTheme.globalColors.text.info,
                                fontSize = 11.5.sp,
                            )
                        }
                        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth(), color = borderColor)
                        ProvideMarkdownStyling(
                            isDark = isDark,
                            markdownStyling = markdownStyling,
                            markdownProcessor = processor,
                            markdownBlockRenderer = blockRenderer,
                        ) {
                            VerticallyScrollableContainer(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                Markdown(
                                    markdownBlocks = parsedBlocks,
                                    markdown = markdownText,
                                    onUrlClick = { url ->
                                        lastInteractionMessage = "Clicked Markdown URL: $url"
                                    },
                                    blockRenderer = blockRenderer,
                                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
