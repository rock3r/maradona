package dev.maradona.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.maradona.render.DiagramZoom
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme

private val examples = linkedMapOf(
    "Basic" to """
        flowchart TD
          A[Mermaid text] --> B{Flowchart?}
          B -- Yes --> C[Maradona]
          B -- No --> D[Pele]
          C --> E([Composables])
          D --> E
    """.trimIndent(),
    "Shapes" to """
        flowchart LR
          A[Rect] --> B(Rounded)
          B --> C([Stadium])
          C --> D[(Database)]
          D --> E((Circle))
          E --> F{Decision}
          F --> G{{Hexagon}}
          G --> H[/Parallelogram/]
          H --> I[[Subroutine]]
          I --> J>Flag]
    """.trimIndent(),
    "Edges" to """
        flowchart TD
          A[Start] ==> B[Thick]
          B -. dotted .-> C{Dotted?}
          C -->|pipe label| D[Cross]
          C --o E[Circle head]
          D --x F[End]
          E --- F
          F --> A
    """.trimIndent(),
    "Subgraph" to """
        flowchart TD
          subgraph client[Client]
            UI[Compose UI] --> VM[ViewModel]
          end
          subgraph engine[Maradona]
            P[Parser] --> L[Sugiyama layout]
            L --> R[MaradonaLayout]
          end
          VM --> P
          R --> UI
          style R fill:#d3e5f5,stroke:#205ea6
    """.trimIndent(),
)

private class SampleState(
    val dark: Boolean,
    val source: String,
    val onDarkChange: (Boolean) -> Unit,
    val onSourceChange: (String) -> Unit,
)

/** Live Mermaid editor + diagram, shared by the desktop, Android, and iOS samples. */
@Composable
fun MaradonaSampleApp(oracleChart: String? = null, oracleZoom: String? = null) {
    if (oracleChart != null) {
        OracleMode(oracleChart, oracleZoom)
        return
    }
    var dark by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(examples.getValue("Basic")) }
    val state = SampleState(
        dark = dark,
        source = source,
        onDarkChange = { dark = it },
        onSourceChange = { source = it },
    )

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            if (maxWidth >= 600.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    EditorPanel(state, modifier = Modifier.width(380.dp).fillMaxHeight())
                    Diagram(state, modifier = Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Diagram(state, modifier = Modifier.weight(1f).fillMaxWidth())
                    EditorPanel(state, modifier = Modifier.fillMaxWidth(), compact = true)
                }
            }
        }
    }
}

@Composable
private fun OracleMode(chart: String, zoom: String?) {
    val source = OracleCharts.all[chart]
    if (source == null) {
        Text("Unknown oracle chart '$chart'", modifier = Modifier.padding(24.dp))
        return
    }
    MaradonaDiagram(
        source = source,
        theme = MaradonaTheme.light(),
        modifier = Modifier.fillMaxSize(),
        zoomEnabled = false,
        initialZoom = when (zoom) {
            "100" -> DiagramZoom.Natural
            "min" -> DiagramZoom.Min
            "max" -> DiagramZoom.Max
            else -> DiagramZoom.Fit
        },
    )
}

@Composable
private fun Diagram(state: SampleState, modifier: Modifier = Modifier) {
    MaradonaDiagram(
        source = state.source,
        theme = if (state.dark) MaradonaTheme.dark() else MaradonaTheme.light(),
        modifier = modifier,
    )
}

@Composable
private fun EditorPanel(state: SampleState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Maradona sample", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((name, text) in examples) {
                OutlinedButton(onClick = { state.onSourceChange(text) }) { Text(name) }
            }
        }
        TextField(
            value = state.source,
            onValueChange = state.onSourceChange,
            modifier = if (compact) {
                Modifier.fillMaxWidth().height(140.dp)
            } else {
                Modifier.weight(1f).fillMaxHeight()
            },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            label = { Text("Mermaid flowchart") },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = state.dark, onCheckedChange = state.onDarkChange)
            Text("Dark theme")
            Button(onClick = { state.onSourceChange(examples.getValue("Basic")) }) { Text("Reset") }
        }
        Text(
            "Pan by dragging. Pinch or ⌘/Ctrl+scroll to zoom. Double-tap to reset.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
