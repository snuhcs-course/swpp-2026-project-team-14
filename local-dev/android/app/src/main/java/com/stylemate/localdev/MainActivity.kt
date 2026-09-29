package com.stylemate.localdev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFBF4E25), background = Color(0xFFFAF8F4),
                surface = Color.White, onSurface = Color(0xFF24211D),
                secondaryContainer = Color(0xFF24211D), onSecondaryContainer = Color.White,
            )) {
                var showProbe by rememberSaveable { mutableStateOf(false) }
                BackHandler(showProbe) { showProbe = false }
                if (showProbe) {
                    Column {
                        TextButton(onClick = { showProbe = false }) { Text("옷장으로 돌아가기") }
                        ProbeScreen()
                    }
                } else {
                    WardrobeRoute(onOpenProbe = { showProbe = true })
                }
            }
        }
    }
}
