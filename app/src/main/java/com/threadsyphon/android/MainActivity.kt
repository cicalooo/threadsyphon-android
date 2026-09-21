package com.threadsyphon.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.threadsyphon.android.data.model.AppSettings
import com.threadsyphon.android.ui.navigation.ThreadSyphonNavHost
import com.threadsyphon.android.ui.theme.ThreadSyphonTheme

class MainActivity : ComponentActivity() {
    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val app = application as ThreadSyphonApp
        setContent {
            val settings by app.repository.settings().collectAsState(initial = AppSettings())
            val dark = if (settings.followSystemTheme) isSystemInDarkTheme() else settings.darkTheme
            ThreadSyphonTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                ThreadSyphonNavHost(repository = app.repository)
            }
        }
    }
}
