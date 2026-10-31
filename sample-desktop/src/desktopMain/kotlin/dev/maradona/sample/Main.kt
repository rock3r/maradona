package dev.maradona.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.theme.jewel.ProvideJewelMaradonaTheme
import dev.maradona.theme.material3.ProvideMaterial3MaradonaTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

enum class DesktopDemoMode(val title: String) {
    DiagramStudio("Diagram Studio (23 Families)"),
    JewelMarkdown("Jewel Markdown + Mermaid Demo"),
}

fun main(args: Array<String>) = application {
    val startInMarkdown = args.any { it == "--markdown" || it == "--jewel-markdown" } ||
        System.getProperty("maradona.demo")?.equals("markdown", ignoreCase = true) == true

    Window(onCloseRequest = ::exitApplication, title = "Maradona — native Mermaid for Compose") {
        var mode by remember {
            mutableStateOf(
                if (startInMarkdown) DesktopDemoMode.JewelMarkdown else DesktopDemoMode.DiagramStudio,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top-level desktop demo switcher bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1F22))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "Maradona Desktop:",
                    style = TextStyle(
                        color = Color(0xFFBCBEC4),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                    ),
                )
                for (entry in DesktopDemoMode.entries) {
                    val selected = entry == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (selected) Color(0xFF3574F0) else Color(0xFF2B2D30))
                            .clickable { mode = entry }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        BasicText(
                            text = entry.title,
                            style = TextStyle(
                                color = if (selected) Color.White else Color(0xFFBCBEC4),
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.sp,
                            ),
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    DesktopDemoMode.DiagramStudio -> MaradonaSampleApp(
                        hostThemeWrapper = { preset, content ->
                            when (preset) {
                                SampleThemePreset.MaterialLight,
                                SampleThemePreset.MaterialDark -> ProvideMaterial3MaradonaTheme(content = content)
                                SampleThemePreset.JewelLight -> IntUiTheme(isDark = false) {
                                    ProvideJewelMaradonaTheme(content = content)
                                }
                                SampleThemePreset.JewelDark -> IntUiTheme(isDark = true) {
                                    ProvideJewelMaradonaTheme(content = content)
                                }
                                SampleThemePreset.Light,
                                SampleThemePreset.Dark -> CompositionLocalProvider(
                                    LocalMaradonaTheme provides preset.theme,
                                    content = content,
                                )
                            }
                        },
                    )
                    DesktopDemoMode.JewelMarkdown -> JewelMarkdownDemoScreen()
                }
            }
        }
    }
}
