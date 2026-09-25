package com.chronicle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.reminders.Notifier
import com.chronicle.app.ui.nav.ChronicleRoot
import com.chronicle.app.ui.theme.ChronicleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannel(this)
        val container = (application as ChronicleApp).container
        setContent {
            val prefs by container.prefsRepository.prefs.collectAsState(initial = null)
            ChronicleTheme(mode = prefs?.themeMode ?: ThemeMode.SYSTEM) {
                ChronicleRoot(container)
            }
        }
    }
}
