package dev.maradona.render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import dev.maradona.parse.FlowchartParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaradonaLabelFormatTest {

    @Test
    fun annotatedLabelFormatsMarkdownAndHtmlSpans() {
        val cleaned = FlowchartParser.parse(
            """
            flowchart TD
              A["**Bold** *italic* ~~strike~~ <u>under</u> `code` H<sub>2</sub>O x<sup>2</sup>"]
            """.trimIndent(),
        ).nodes.single().label

        val annotated = annotatedLabel(cleaned, FontFamily.Monospace)
        assertEquals("Bold italic strike under code H2O x2", annotated.text)

        val styles = annotated.spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(styles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(styles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(styles.any { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(styles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(styles.any { it.item.baselineShift == BaselineShift.Subscript })
        assertTrue(styles.any { it.item.baselineShift == BaselineShift.Superscript })
    }

    @Test
    fun iconTokensExpandToUnicodeGlyphsOutsideCodeSpans() {
        val annotated = annotatedLabel("fa:fa-check Ready ::icon(fa fa-bug) `fa:fa-check`")
        assertEquals("✓ Ready 🐞 fa:fa-check", annotated.text)
        assertEquals("📖", resolveMermaidIconGlyph("fa fa-book"))
        assertEquals("🗄", resolveMermaidIconGlyph("mdi:database"))
        assertEquals("🚀", resolveMermaidIconGlyph("fa-rocket"))
    }

    @Test
    fun inlineImageExtractionParsesImgTagsAndMarkdownImages() {
        val raw = """<img src="https://example.com/logo.png" width="32" height="28" alt="Brand"/> and ![Diagram](https://example.com/d.svg)"""
        val images = extractInlineImages(raw)
        assertEquals(2, images.size)
        assertEquals("https://example.com/logo.png", images[0].url)
        assertEquals("Brand", images[0].alt)
        assertEquals(32f, images[0].widthPx)
        assertEquals(28f, images[0].heightPx)
        assertEquals("https://example.com/d.svg", images[1].url)
        assertEquals("Diagram", images[1].alt)

        val fallbackAnnotated = annotatedLabel(raw)
        assertEquals("[🖼 Brand] and [🖼 Diagram]", fallbackAnnotated.text)
    }

    @Test
    fun unmatchedBacktickInvalidImgAndCodeSpanProtection() {
        // Unmatched backtick must not hang and is preserved literally
        val unmatched = annotatedLabel("Release `candidate fa:fa-check")
        assertEquals("Release `candidate ✓", unmatched.text)

        // HTML <code> and Markdown backticks protect both icons and images
        val codeProtected = annotatedLabel("<code>fa:fa-check</code> and `![alt](https://example.com/a.png)`")
        assertEquals("fa:fa-check and ![alt](https://example.com/a.png)", codeProtected.text)
        assertTrue(extractInlineImages("`![alt](https://example.com/a.png)`").isEmpty())

        // Invalid <img/> without src is ignored without shifting subsequent valid <img/> indices,
        // and data-src does not override src.
        val mixedImgs = """<img alt="missing"/> then <img data-src="https://example.com/preview.png" src="https://example.com/ok.png" alt="OK"/>"""
        val extracted = extractInlineImages(mixedImgs)
        assertEquals(1, extracted.size)
        assertEquals("https://example.com/ok.png", extracted[0].url)
        assertEquals("OK", extracted[0].alt)
        assertEquals("then [🖼 OK]", annotatedLabel(mixedImgs).text.trim())

        // HTML <code> containing backticks and backticks containing </code> close only on their matching delimiter.
        val nestedDelims = annotatedLabel("<code>echo `hostname`</code> and `literal </code> here`")
        assertEquals("echo `hostname` and literal </code> here", nestedDelims.text)

        // Mermaid Markdown-string wrapper A["`...`"] strips outer backticks even when containing inner inline code.
        val mdStringChart = FlowchartParser.parse(
            """
            flowchart TD
              A["`**Ready** `fa:fa-check` now`"]
            """.trimIndent(),
        )
        val mdAnnotated = annotatedLabel(mdStringChart.nodes.single().label)
        assertEquals("Ready fa:fa-check now", mdAnnotated.text)
        assertEquals("Ready fa:fa-check now", plainTextLabel(mdStringChart.nodes.single().label))

        // HTML tags inside inline code are preserved literally by cleanLabel and plainTextLabel,
        // and attributes on preserved formatting tags (<sub class='formula'>, <code class='kt'>) are normalized.
        val codeTagChart = FlowchartParser.parse(
            """
            flowchart TD
              A["Show `<strike>old</strike>` and <strike class='x'>gone</strike> H<sub class='f'>2</sub>O <code class='c'>x</code>"]
            """.trimIndent(),
        )
        assertEquals("Show <strike>old</strike> and gone H2O x", plainTextLabel(codeTagChart.nodes.single().label))

        // Nested ** and __ bold delimiters track their own closers, and quoted '>' or attribute-like text inside alt is preserved.
        assertEquals("outer inner outer", annotatedLabel("**outer __inner__ outer**").text)
        val gtImg = extractInlineImages("""<img alt="Example src='preview.png' x > 0" src='https://example.com/a.png'/>""")
        assertEquals(1, gtImg.size)
        assertEquals("https://example.com/a.png", gtImg[0].url)
        assertEquals("Example src='preview.png' x > 0", gtImg[0].alt)
        assertEquals("[🖼 Example src='preview.png' x > 0]", annotatedLabel("""<img alt="Example src='preview.png' x > 0" src='https://example.com/a.png'/>""").text)

        val unquotedImg = extractInlineImages("<img src=https://example.com/logo.png alt=Logo/>")
        assertEquals(1, unquotedImg.size)
        assertEquals("https://example.com/logo.png", unquotedImg[0].url)
        assertEquals("Logo", unquotedImg[0].alt)

        // Backticks inside <img alt='Run `hostname`'/> and ![Run `cmd`](https://example.com/d_(v2).png), plus '>' in <code title='x > 0'>
        val backtickAltAndParenUrl = """<img src='https://example.com/a.png' alt='Run `hostname`'/> and ![Run `cmd`](https://example.com/d_(v2).png)"""
        val bothImgs = extractInlineImages(backtickAltAndParenUrl)
        assertEquals(2, bothImgs.size)
        assertEquals("Run `hostname`", bothImgs[0].alt)
        assertEquals("Run `cmd`", bothImgs[1].alt)
        assertEquals("https://example.com/d_(v2).png", bothImgs[1].url)

        val codeAttrChart = FlowchartParser.parse(
            """
            flowchart TD
              A["<code title='x > 0'>cmd</code>"]
              B["&lt;sub&gt;2&lt;/sub&gt; and &lt;img src='x.png'/&gt;"]
            """.trimIndent(),
        )
        assertEquals("cmd", plainTextLabel(codeAttrChart.nodes.first { it.id == "A" }.label))
        val nodeBAnnotated = annotatedLabel(codeAttrChart.nodes.first { it.id == "B" }.label)
        assertEquals("<sub>2</sub> and <img src='x.png'/>", nodeBAnnotated.text)
        assertTrue(nodeBAnnotated.spanStyles.isEmpty())
    }
}
