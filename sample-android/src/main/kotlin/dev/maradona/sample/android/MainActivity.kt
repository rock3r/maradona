package dev.maradona.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.maradona.sample.MaradonaSampleApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Oracle mode: --es oracleChart <id> --es oracleZoom fit|100|min|max [--ef oraclePanX f --ef oraclePanY f]
        val oracleChart = intent.getStringExtra("oracleChart")
        val oracleZoom = intent.getStringExtra("oracleZoom")
        val panX = intent.getFloatExtra("oraclePanX", -1f)
        val panY = intent.getFloatExtra("oraclePanY", -1f)
        setContent {
            MaradonaSampleApp(
                oracleChart = oracleChart,
                oracleZoom = oracleZoom,
                oraclePanX = panX.takeIf { it >= 0f },
                oraclePanY = panY.takeIf { it >= 0f },
            )
        }
    }
}
