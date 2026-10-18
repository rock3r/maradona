package dev.maradona.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController = ComposeUIViewController { MaradonaSampleApp() }
