package com.thatsmyface

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.thatsmyface.ui.ThatsMyFaceApp

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        model = ViewModelProvider(this)[AppModel::class.java]
        setContent { ThatsMyFaceApp(model) }
    }

    override fun onStop() {
        model.stopSharing()
        super.onStop()
    }
}
