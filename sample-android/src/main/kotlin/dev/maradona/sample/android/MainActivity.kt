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
        // Oracle mode: --es oracleChart <id> --es oracleZoom fit|100|min|max
        val oracleChart = intent.getStringExtra("oracleChart")
        val oracleZoom = intent.getStringExtra("oracleZoom")
        setContent {
            MaradonaSampleApp(oracleChart = oracleChart, oracleZoom = oracleZoom)
        }
    }
}
