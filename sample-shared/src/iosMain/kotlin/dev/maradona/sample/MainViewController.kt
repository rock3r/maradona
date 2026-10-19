package dev.maradona.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/** Reads `-oracleChart <id> -oracleZoom <100|min|max> [-oracleDensity d]` from launch arguments. */
private fun launchArg(name: String): String? {
    val args = NSProcessInfo.processInfo.arguments
    for (i in 0 until args.size - 1) {
        if (args[i] as? String == name) return args[i + 1] as? String
    }
    return null
}

fun mainViewController(): UIViewController = ComposeUIViewController {
    MaradonaSampleApp(
        oracleChart = launchArg("-oracleChart"),
        oracleZoom = launchArg("-oracleZoom"),
        oraclePanX = launchArg("-oraclePanX")?.toFloatOrNull(),
        oraclePanY = launchArg("-oraclePanY")?.toFloatOrNull(),
        oracleDensity = launchArg("-oracleDensity")?.toFloatOrNull(),
        oracleSeq = launchArg("-oracleSeq")?.toIntOrNull() ?: 0,
    )
}
