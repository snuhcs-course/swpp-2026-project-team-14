// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
package com.swpp.stylemate

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.swpp.stylemate.data.tryon.FittingNotifications
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swpp.stylemate.ui.StyleMateApp
import com.swpp.stylemate.ui.theme.StyleMateTheme

class MainActivity : ComponentActivity() {
    private var fittingTask by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fittingTask = intent.getStringExtra(FittingNotifications.EXTRA_TASK)
        setContent {
            StyleMateTheme {
                StyleMateApp(fittingTask = fittingTask, onFittingHandled = { fittingTask = null; intent.removeExtra(FittingNotifications.EXTRA_TASK) })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fittingTask = intent.getStringExtra(FittingNotifications.EXTRA_TASK)
    }
}
