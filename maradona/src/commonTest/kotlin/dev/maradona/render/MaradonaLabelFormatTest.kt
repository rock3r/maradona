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
    }
}
