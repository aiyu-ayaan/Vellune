package com.aiyu.vellune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.vellune.data.ThemeMode
import com.aiyu.vellune.ui.VelluneRoot
import com.aiyu.vellune.ui.theme.VelluneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            VelluneTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                VelluneRoot()
            }
        }
    }
}
