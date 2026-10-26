package dev.maradona.sample

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.maradona.render.LocalMaradonaTheme
import dev.maradona.theme.jewel.ProvideJewelMaradonaTheme
import dev.maradona.theme.material3.ProvideMaterial3MaradonaTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Maradona — native Mermaid for Compose") {
        MaradonaSampleApp(
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
    }
}
