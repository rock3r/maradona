# Theming, Interaction, and SVG Export

Maradona's rendering and export APIs live in `dev.maradona.render` and `dev.maradona.export`.

## 1. Theming (`MaradonaTheme` & `LocalMaradonaTheme`)

Every diagram renderer reads its surfaces, borders, text, notes, activations, semantic status badges, categorical chart series, C4 cards, and font families/sizes from `MaradonaTheme` (`dev.maradona.render.MaradonaTheme`).

### Built-in Presets

- `MaradonaTheme.light()` — Warm paper light palette (`#FFFCF0` background, `#F2F0E5` cards)
- `MaradonaTheme.dark()` — Warm dark palette (`#100F0F` background, `#282726` cards)
- `MaradonaTheme.jewelLight()` — JetBrains Jewel / IntelliJ New UI Light (`IntUiLight`) palette (`#FFFFFF` background, `#F7F8FA` surface, `#3574F0` accent, `13.sp` typography)
- `MaradonaTheme.jewelDark()` — JetBrains Jewel / IntelliJ New UI Dark (`IntUiDark`) palette (`#1E1F22` background, `#2B2D30` surface, `#3574F0` accent, `13.sp` typography)

### Providing a Theme via `LocalMaradonaTheme`

You can pass `theme` directly to `MaradonaDiagram(source = ..., theme = ...)` or provide `LocalMaradonaTheme` once for a subtree:

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaTheme

CompositionLocalProvider(LocalMaradonaTheme provides MaradonaTheme.jewelDark()) {
    MaradonaDiagram(
        source = """
            sequenceDiagram
              participant IDE as Jewel Toolwindow
              participant M as MaradonaDiagram
              IDE->>+M: Provide LocalMaradonaTheme
              M-->>-IDE: Native Compose Canvas
        """.trimIndent(),
    )
}
```

### Deriving a Theme from Any Host Design System (`MaradonaTheme.fromHost`)

`MaradonaTheme.fromHost(...)` builds a complete `MaradonaTheme` (including derived note callouts, activation bars, semantic warning/error fills, categorical series palettes, and `MaradonaC4Palette`) from a host application's core surface, border, text, and accent tokens:

```kotlin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.maradona.render.MaradonaTheme

val customHostTheme = MaradonaTheme.fromHost(
    background = Color(0xFF181A1F),
    surface = Color(0xFF232730),
    border = Color(0xFF4B5263),
    text = Color(0xFFE6EDF3),
    accent = Color(0xFF58A6FF),
    secondaryAccent = Color(0xFF3FB950),
    warning = Color(0xFFD29922),
    error = Color(0xFFF85149),
    fontFamily = FontFamily.SansSerif,
    monospaceFontFamily = FontFamily.Monospace,
    baseFontSize = 13.sp,
)
```

---

## 2. Interactive Nodes & Accessibility (`onNodeClick` & `MaradonaNodeClickEvent`)

Nodes across all 23 diagram families are real Compose layout children with screen-reader semantics (`contentDescription` announcing the node's label, neighbours, and tooltip) and interactive click handling.

When a diagram defines interactive actions — such as Flowchart/Class/State/Gantt `click` directives, Sequence `link` / `links`, C4 `$link`, Requirement `docRef`, or Kanban `ticketBaseUrl` — or when you pass an `onNodeClick` handler to `MaradonaDiagram`, clicking or activating a node dispatches a `MaradonaNodeClickEvent` (`dev.maradona.model.MaradonaNodeClickEvent`):

```kotlin
import dev.maradona.model.MaradonaNodeClickEvent
import dev.maradona.render.MaradonaDiagram

MaradonaDiagram(
    source = """
        flowchart LR
          Docs[Read Docs] --> Repo[GitHub Repo]
          click Docs "https://mermaid.js.org" "Open Mermaid docs"
          click Repo call openRepo() "Inspect source"
    """.trimIndent(),
    onNodeClick = { event: MaradonaNodeClickEvent ->
        println("Clicked node=${event.nodeId} label=${event.label} url=${event.url} callback=${event.callback}")
    },
)
```

If `onNodeClick` is `null`, clicking a node that carries a URL automatically opens that URL via Compose's `LocalUriHandler`.

---

## 3. Rich Labels, Icon Glyphs & Inline Images (`LocalMaradonaImageResolver`)

Labels support HTML and Markdown inline formatting (`<b>`, `<i>`, `<u>`, `<ins>`, `<s>`, `<code>`, `<sup>`, `<sub>`, `<small>`, `<mark>`, `<h1>`–`<h6>`, `**bold**`, `*italic*`, `` `code` ``, `~~strike~~`), FontAwesome / Material Design icon badges (`fa:fa-check`, `mdi:check`, `::icon(fa fa-rocket)`), and inline images (`<img src="..." width="24" height="24"/>` or `![alt](url)`).

To resolve inline image URLs into Compose `Painter` instances without coupling Maradona to a specific networking library, provide a `MaradonaImageResolver` via `LocalMaradonaImageResolver`:

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import dev.maradona.render.LocalMaradonaImageResolver
import dev.maradona.render.MaradonaDiagram
import dev.maradona.render.MaradonaImageResolver

val imageResolver = MaradonaImageResolver { url ->
    myPainterCache[url] // Return a Compose Painter? for the given URL
}

CompositionLocalProvider(LocalMaradonaImageResolver provides imageResolver) {
    MaradonaDiagram(
        source = """
            flowchart LR
              A["<img src='https://example.com/logo.png' width='24' height='24'/> Service A"] --> B["fa:fa-database Primary DB"]
        """.trimIndent(),
    )
}
```

---

## 4. Standalone SVG Export (`MaradonaSvgExporter`)

`MaradonaSvgExporter.exportToSvg` (`dev.maradona.export.MaradonaSvgExporter`) exports any of the 23 Mermaid diagram families to a standalone SVG string in pure Kotlin, without requiring a Compose UI window or scene:

```kotlin
import dev.maradona.export.MaradonaSvgExporter
import dev.maradona.layout.LayoutOptions
import dev.maradona.render.MaradonaTheme

val svg: String = MaradonaSvgExporter.exportToSvg(
    source = """
        flowchart TD
          A[Mermaid Source] --> B[MaradonaSvgExporter]
          B --> C([Standalone SVG])
    """.trimIndent(),
    theme = MaradonaTheme.light(),
    options = LayoutOptions(density = 1f),
)
```
