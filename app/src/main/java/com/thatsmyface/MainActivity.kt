package com.thatsmyface

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD6ED82), background = Color(0xFF111410))) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.Center) {
                        Text("ThatsMyFace", style = MaterialTheme.typography.headlineLarge)
                        Text("If you're in it, find it.", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
