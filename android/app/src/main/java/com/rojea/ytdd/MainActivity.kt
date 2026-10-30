package com.rojea.ytdd

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rojea.ytdd.core.Engine
import com.rojea.ytdd.core.History
import com.rojea.ytdd.ui.HomeScreen
import com.rojea.ytdd.ui.SettingsScreen
import com.rojea.ytdd.ui.T
import androidx.compose.material3.Surface
import com.rojea.ytdd.ui.YtddTheme
import com.rojea.ytdd.ui.applySystemBars
import com.rojea.ytdd.ui.isDarkUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = true)
        setContent {
            val dark = isDarkUi()
            LaunchedEffect(dark) { applySystemBars(dark) }
            YtddTheme(dark) {
                // Notifications carry the "ready to send" buttons, so ask once up front.
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

                var settings by rememberSaveable { mutableStateOf(false) }
                Surface(color = T.background, contentColor = T.onSurface) {
                    if (settings) SettingsScreen(onBack = { settings = false })
                    else HomeScreen(onOpenSettings = { settings = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        History.prune()
        Engine.checkIfStale()
    }
}
