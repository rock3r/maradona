package dev.maradona.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Colours and text sizes for a diagram. This is the Compose equivalent of
 * Pele's CSS variables: a plain value you derive from your app theme.
 */
data class MaradonaTheme(
    val background: Color,
    val nodeFill: Color,
    val nodeBorder: Color,
    val nodeText: Color,
    val edge: Color,
    val edgeLabelText: Color,
    val edgeLabelBackground: Color,
    val subgraphFill: Color,
    val subgraphBorder: Color,
    val subgraphText: Color,
    val error: Color,
    val nodeFontSize: TextUnit = 14.sp,
    val edgeLabelFontSize: TextUnit = 12.sp,
    val subgraphFontSize: TextUnit = 12.sp,
) {
    companion object {
        fun light(): MaradonaTheme = MaradonaTheme(
            background = Color(0xFFFFFCF0),
            nodeFill = Color(0xFFF2F0E5),
            nodeBorder = Color(0xFF8A8878),
            nodeText = Color(0xFF100F0F),
            edge = Color(0xFF100F0F),
            edgeLabelText = Color(0xFF44423A),
            edgeLabelBackground = Color(0xFFFFFCF0),
            subgraphFill = Color(0xFFEAE6D8),
            subgraphBorder = Color(0xFFCECDC3),
            subgraphText = Color(0xFF6F6E69),
            error = Color(0xFFB3261E),
        )

        fun dark(): MaradonaTheme = MaradonaTheme(
            background = Color(0xFF100F0F),
            nodeFill = Color(0xFF282726),
            nodeBorder = Color(0xFF6F6E69),
            nodeText = Color(0xFFCECDC3),
            edge = Color(0xFFCECDC3),
            edgeLabelText = Color(0xFFB8B5A8),
            edgeLabelBackground = Color(0xFF100F0F),
            subgraphFill = Color(0xFF1C1B1A),
            subgraphBorder = Color(0xFF403E3C),
            subgraphText = Color(0xFF878580),
            error = Color(0xFFF2B8B5),
        )
    }
}

/** Parses `#RGB`, `#RRGGBB`, `#RRGGBBAA` and a few CSS colour names. */
fun parseCssColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    val v = value.trim().lowercase()
    namedColors[v]?.let { return it }
    if (!v.startsWith("#")) return null
    val hex = v.removePrefix("#")
    return try {
        when (hex.length) {
            3 -> {
                val r = hex[0].digitToInt(16) * 17
                val g = hex[1].digitToInt(16) * 17
                val b = hex[2].digitToInt(16) * 17
                Color(r, g, b)
            }
            6 -> Color(hex.toLong(16).toInt() or (0xFF shl 24))
            8 -> Color(hex.toLong(16).toInt()) // CSS order is RRGGBBAA; approximate as opaque RGB below
                .let {
                    val n = hex.toLong(16)
                    Color(
                        red = ((n shr 24) and 0xFF).toInt(),
                        green = ((n shr 16) and 0xFF).toInt(),
                        blue = ((n shr 8) and 0xFF).toInt(),
                        alpha = (n and 0xFF).toInt(),
                    )
                }
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val namedColors = mapOf(
    "black" to Color.Black, "white" to Color.White, "red" to Color.Red,
    "green" to Color(0xFF008000), "blue" to Color.Blue, "yellow" to Color.Yellow,
    "orange" to Color(0xFFFFA500), "purple" to Color(0xFF800080),
    "pink" to Color(0xFFFFC0CB), "gray" to Color.Gray, "grey" to Color.Gray,
    "cyan" to Color.Cyan, "magenta" to Color.Magenta, "transparent" to Color.Transparent,
)
