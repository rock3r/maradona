package dev.maradona.sample.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.maradona.sample.MaradonaSampleApp
import dev.maradona.sample.SampleThemePreset

/**
 * Oracle mode: `--es oracleChart <id> --es oracleZoom 100|min|max`
 * `[--ef oraclePanX f --ef oraclePanY f] [--ef oracleDensity d]`.
 *
 * The activity is singleTask and reacts to new intents, so a capture run
 * renders tile after tile without paying a cold start per tile.
 */
class MainActivity : ComponentActivity() {
    private var oracleChart by mutableStateOf<String?>(null)
    private var oracleZoom by mutableStateOf<String?>(null)
    private var oraclePanX by mutableStateOf<Float?>(null)
    private var oraclePanY by mutableStateOf<Float?>(null)
    private var oracleDensity by mutableStateOf<Float?>(null)
    private var oracleSeq by mutableStateOf(0)
    private var sampleExample by mutableStateOf<String?>(null)
    private var sampleTheme by mutableStateOf(SampleThemePreset.Light)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyIntent(intent)
        // Captures are compared against the golden pixel for pixel, so the
        // screenshot must contain nothing but the diagram: no status or
        // navigation bars, no dimming, no lock screen.
        if (oracleChart != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hideSystemBars()
        }
        setContent {
            MaradonaSampleApp(
                oracleChart = oracleChart,
                oracleZoom = oracleZoom,
                oraclePanX = oraclePanX,
                oraclePanY = oraclePanY,
                oracleDensity = oracleDensity,
                oracleSeq = oracleSeq,
                initialExample = sampleExample,
                initialThemePreset = sampleTheme,
                onThemePresetChanged = { preset ->
                    if (oracleChart == null) {
                        val lightBars = !preset.theme.isDark
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = lightBars
                            isAppearanceLightNavigationBars = lightBars
                        }
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system can bring the bars back when the window regains focus
        // (the oracle reuses one activity for every tile), so re-hide them.
        if (hasFocus && oracleChart != null) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyIntent(intent: Intent) {
        sampleExample = intent.getStringExtra("sampleExample")
        intent.getStringExtra("sampleTheme")?.let { raw ->
            SampleThemePreset.entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.displayName.equals(raw, ignoreCase = true)
            }?.let { sampleTheme = it }
        }
        val chart = intent.getStringExtra("oracleChart")
        if (chart == null) {
            oracleChart = null
            return
        }
        oracleChart = chart
        oracleZoom = intent.getStringExtra("oracleZoom")
        oraclePanX = intent.getFloatExtra("oraclePanX", -1f).takeIf { it >= 0f }
        oraclePanY = intent.getFloatExtra("oraclePanY", -1f).takeIf { it >= 0f }
        oracleDensity = intent.getFloatExtra("oracleDensity", -1f).takeIf { it > 0f }
        oracleSeq = intent.getIntExtra("oracleSeq", 0)
    }
}
