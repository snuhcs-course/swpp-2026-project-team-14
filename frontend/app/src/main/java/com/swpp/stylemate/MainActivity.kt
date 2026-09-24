package com.swpp.stylemate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swpp.stylemate.ui.StyleMateApp
import com.swpp.stylemate.ui.theme.StyleMateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StyleMateTheme {
                StyleMateApp()
            }
        }
    }
}
