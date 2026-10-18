package dev.maradona.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Maradona — native Mermaid for Compose") {
        MaradonaSampleApp()
    }
}
